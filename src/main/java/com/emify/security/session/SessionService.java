package com.emify.security.session;

import com.emify.user.User;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Sesiones del lado del servidor (reemplaza al JWT).
 *
 * - La cookie (HttpOnly, Secure, SameSite=Lax) lleva un token aleatorio de 256 bits. JavaScript no la puede leer.
 * - En la BD solo se guarda el SHA-256 del token: si alguien lee la tabla, no puede usar las sesiones.
 * - Cada petición protegida valida la sesión aquí, así que revocarla (cerrar sesión, cerrar en otro dispositivo,
 *   cambio de contraseña, una sola sesión por cuenta) surte efecto en la siguiente petición, sin esperar a que venza.
 * - Vence por inactividad (idleTimeout) y siempre a los absoluteTimeout. El token se renueva cada rotationInterval.
 *
 * No es @Transactional a propósito: cada consulta es corta y el registro de actividad no debe poder deshacer un login.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    /** Atributo del request con la UserSession validada (lo pone SessionAuthenticationFilter). */
    public static final String REQUEST_SESSION = "emify.session";
    /** Atributo del request con el SessionFailure cuando la cookie no sirvió (lo lee el entry point del 401). */
    public static final String REQUEST_FAILURE = "emify.session.failure";
    /** El latido del frontend (revisar si la sesión sigue viva) no cuenta como actividad. */
    public static final String HEARTBEAT_PATH = "/api/auth/session";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final UserSessionRepository sessions;
    private final AuthEventService events;
    private final SessionProperties props;

    /** Resultado de validar la cookie: sesión válida, motivo de rechazo, o ninguna cookie. */
    public record Resolution(UserSession session, SessionFailure failure) {
        static final Resolution NONE = new Resolution(null, null);

        static Resolution ok(UserSession session) {
            return new Resolution(session, null);
        }

        static Resolution fail(SessionFailure failure) {
            return new Resolution(null, failure);
        }
    }

    @PostConstruct
    void checkConfig() {
        if (props.cookieName().startsWith("__Host-") && !props.cookieSecure()) {
            log.warn("emify.session.cookie-name usa el prefijo __Host- pero cookie-secure=false: el navegador va a rechazar la cookie");
        }
        if (props.singleSession()) {
            log.info("Sesiones: una sola sesión activa por cuenta (emify.session.single-session=true)");
        }
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    // -------------------------------------------------------
    // ABRIR (login, registro, unirse con invitación)
    // -------------------------------------------------------
    public UserSession start(User user, String eventType, HttpServletRequest request, HttpServletResponse response) {
        LocalDateTime now = now();

        // Nunca se reutiliza un token de antes del login (fijación de sesión): si el navegador traía una sesión, se cierra.
        findActiveByCookie(request).ifPresent(old -> sessions.revoke(old.getId(), now, "REPLACED"));

        if (props.singleSession()) {
            int closed = sessions.revokeAll(user.getId(), now, "NEW_LOGIN");
            if (closed > 0) {
                events.log(user.getId(), null, AuthEventService.SESSIONS_REVOKED, request,
                        AuthEventService.closedSessions(closed) + " por iniciar sesión en otro dispositivo");
            }
        } else {
            // Tope de sesiones por cuenta: se cierran las menos usadas para dejar lugar a la nueva
            List<UserSession> active = sessions.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(user.getId());
            for (int i = Math.max(0, props.maxActive() - 1); i < active.size(); i++) {
                sessions.revoke(active.get(i).getId(), now, "LIMIT");
            }
        }

        String token = newToken();
        UserSession session = sessions.save(UserSession.builder()
                .userId(user.getId())
                .tokenHash(sha256(token))
                .createdAt(now)
                .lastSeenAt(now)
                .rotatedAt(now)
                .expiresAt(now.plus(props.absoluteTimeout()))
                .ipAddress(ClientInfo.ip(request))
                .userAgent(ClientInfo.userAgent(request))
                .build());
        writeCookie(response, token, props.absoluteTimeout());
        request.setAttribute(REQUEST_SESSION, session);
        events.log(user.getId(), session.getId(), eventType, request, ClientInfo.deviceLabel(session.getUserAgent()));
        return session;
    }

    // -------------------------------------------------------
    // VALIDAR (en cada petición, desde SessionAuthenticationFilter)
    // -------------------------------------------------------
    public Resolution resolve(HttpServletRequest request, HttpServletResponse response) {
        String token = readCookie(request);
        if (token == null) return Resolution.NONE;

        String hash = sha256(token);
        LocalDateTime now = now();

        UserSession session = sessions.findByTokenHash(hash).orElse(null);
        boolean previousToken = false;
        if (session == null) {
            // Token recién rotado: otra petición en paralelo ya recibió el nuevo. Se acepta un par de minutos.
            session = sessions.findFirstByPreviousTokenHash(hash).orElse(null);
            previousToken = session != null;
        }
        if (session == null) return Resolution.fail(SessionFailure.invalid());
        if (session.getRevokedAt() != null) return Resolution.fail(SessionFailure.fromReason(session.getRevokedReason()));
        if (previousToken && session.getRotatedAt().plus(props.rotationGrace()).isBefore(now)) {
            return Resolution.fail(SessionFailure.invalid());
        }
        if (now.isAfter(session.getExpiresAt())) {
            sessions.revoke(session.getId(), now, "EXPIRED");
            return Resolution.fail(SessionFailure.fromReason("EXPIRED"));
        }
        if (now.isAfter(session.getLastSeenAt().plus(props.idleTimeout()))) {
            sessions.revoke(session.getId(), now, "IDLE");
            return Resolution.fail(SessionFailure.fromReason("IDLE"));
        }

        boolean countsAsActivity = !HEARTBEAT_PATH.equals(request.getRequestURI());
        if (!previousToken && countsAsActivity && session.getRotatedAt().plus(props.rotationInterval()).isBefore(now)) {
            rotate(session, hash, response, now);
        } else if (countsAsActivity && session.getLastSeenAt().plus(props.touchInterval()).isBefore(now)) {
            sessions.touch(session.getId(), now);
            session.setLastSeenAt(now);
        }
        return Resolution.ok(session);
    }

    /** Cambia el token de la cookie (misma sesión). Si otra petición ya lo cambió, no hace nada. */
    private void rotate(UserSession session, String currentHash, HttpServletResponse response, LocalDateTime now) {
        String fresh = newToken();
        String freshHash = sha256(fresh);
        if (sessions.rotate(session.getId(), currentHash, freshHash, now) == 1) {
            writeCookie(response, fresh, Duration.between(now, session.getExpiresAt()));
            session.setPreviousTokenHash(currentHash);
            session.setTokenHash(freshHash);
            session.setRotatedAt(now);
            session.setLastSeenAt(now);
        }
    }

    /** Renueva el token de la sesión actual ya mismo (p. ej. después de cambiar la contraseña). */
    public void renew(UserSession session, HttpServletResponse response) {
        rotate(session, session.getTokenHash(), response, now());
    }

    // -------------------------------------------------------
    // CERRAR
    // -------------------------------------------------------

    /** Cerrar sesión en este dispositivo: revoca la sesión de la cookie (si la hay) y borra la cookie. */
    public Optional<UserSession> end(HttpServletRequest request, HttpServletResponse response, String reason) {
        Optional<UserSession> current = current(request).or(() -> findActiveByCookie(request));
        current.ifPresent(s -> sessions.revoke(s.getId(), now(), reason));
        clearCookie(response);
        return current;
    }

    /** Cierra una sesión de ese usuario. Regresa la sesión cerrada (para el registro), o vacío si ya no estaba abierta. */
    public Optional<UserSession> revoke(Long userId, Long sessionId, String reason) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .filter(UserSession::isActive)
                .filter(s -> sessions.revoke(s.getId(), now(), reason) == 1);
    }

    public int revokeOthers(Long userId, Long keepSessionId, String reason) {
        return keepSessionId == null
                ? sessions.revokeAll(userId, now(), reason)
                : sessions.revokeAllExcept(userId, keepSessionId, now(), reason);
    }

    public List<UserSession> active(Long userId) {
        return sessions.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId);
    }

    /** La sesión ya validada de esta petición. */
    public Optional<UserSession> current(HttpServletRequest request) {
        return request.getAttribute(REQUEST_SESSION) instanceof UserSession s ? Optional.of(s) : Optional.empty();
    }

    public LocalDateTime idleExpiresAt(UserSession session) {
        LocalDateTime idle = session.getLastSeenAt().plus(props.idleTimeout());
        return idle.isBefore(session.getExpiresAt()) ? idle : session.getExpiresAt();
    }

    // -------------------------------------------------------
    // COOKIE
    // -------------------------------------------------------
    public void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie("").maxAge(Duration.ZERO).build().toString());
    }

    private void writeCookie(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(token).maxAge(maxAge).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(props.cookieName(), value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite(props.sameSite())
                .path("/");
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (props.cookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                // Un token nuestro mide 43 caracteres; cualquier otra cosa ni se busca en la BD
                return value != null && value.length() >= 32 && value.length() <= 128 ? value : null;
            }
        }
        return null;
    }

    private Optional<UserSession> findActiveByCookie(HttpServletRequest request) {
        String token = readCookie(request);
        if (token == null) return Optional.empty();
        return sessions.findByTokenHash(sha256(token)).filter(UserSession::isActive);
    }

    // -------------------------------------------------------
    // TOKENS
    // -------------------------------------------------------
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
