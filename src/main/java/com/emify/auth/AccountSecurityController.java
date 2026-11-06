package com.emify.auth;

import com.emify.auth.dto.ApiResponse;
import com.emify.auth.dto.ChangePasswordRequest;
import com.emify.common.ApiException;
import com.emify.common.CurrentUser;
import com.emify.security.LoginAttemptService;
import com.emify.security.PasswordPolicy;
import com.emify.security.session.AuthEvent;
import com.emify.security.session.AuthEventRepository;
import com.emify.security.session.AuthEventService;
import com.emify.security.session.ClientInfo;
import com.emify.security.session.SessionService;
import com.emify.security.session.UserSession;
import com.emify.user.User;
import com.emify.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seguridad de la cuenta (perfil → "Seguridad"): sesión actual, dispositivos con sesión abierta, cerrar sesiones,
 * actividad reciente y cambio de contraseña. Todas requieren sesión (ver SecurityConfig).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AccountSecurityController {

    /** Fechas en UTC con "Z" y sin fracciones (Safari no lee microsegundos). */
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final SessionService sessionService;
    private final AuthEventService authEvents;
    private final AuthEventRepository authEventRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;

    // GET /api/auth/session — latido del frontend (cada minuto y al volver a la pestaña). No cuenta como actividad.
    @GetMapping("/session")
    public ResponseEntity<ApiResponse<?>> session(Authentication authentication, HttpServletRequest request) {
        CurrentUser.require(authentication);
        UserSession session = sessionService.current(request)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Inicia sesión para continuar."));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", session.getId());
        data.put("created_at", utc(session.getCreatedAt()));
        data.put("expires_at", utc(session.getExpiresAt()));
        data.put("idle_expires_at", utc(sessionService.idleExpiresAt(session)));
        return ResponseEntity.ok(ApiResponse.ok("Sesión activa", data));
    }

    // GET /api/auth/sessions — dispositivos con sesión abierta
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<?>> sessions(Authentication authentication, HttpServletRequest request) {
        User user = CurrentUser.require(authentication);
        Long currentId = sessionService.current(request).map(UserSession::getId).orElse(null);
        List<Map<String, Object>> list = sessionService.active(user.getId()).stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("device", ClientInfo.deviceLabel(s.getUserAgent()));
            map.put("ip", s.getIpAddress());
            map.put("created_at", utc(s.getCreatedAt()));
            map.put("last_seen_at", utc(s.getLastSeenAt()));
            map.put("current", s.getId().equals(currentId));
            return map;
        }).toList();
        return ResponseEntity.ok(ApiResponse.ok("Sesiones", Map.of("sessions", list)));
    }

    // POST /api/auth/sessions/{id}/revoke — cerrar la sesión de un dispositivo (si es esta misma, es un logout)
    @PostMapping("/sessions/{id}/revoke")
    public ResponseEntity<ApiResponse<?>> revoke(@PathVariable Long id, Authentication authentication,
                                                 HttpServletRequest request, HttpServletResponse response) {
        User user = CurrentUser.require(authentication);
        Long currentId = sessionService.current(request).map(UserSession::getId).orElse(null);
        if (id.equals(currentId)) {
            sessionService.end(request, response, "LOGOUT");
            authEvents.log(user.getId(), id, AuthEventService.LOGOUT, request, null);
            return ResponseEntity.ok(ApiResponse.ok("Cerraste la sesión de este dispositivo", Map.of("current", true)));
        }
        UserSession closed = sessionService.revoke(user.getId(), id, "REVOKED_BY_USER")
                .orElseThrow(() -> ApiException.notFound("Esa sesión ya no está abierta"));
        // El detalle es el dispositivo que se cerró ("Chrome en Windows"), para reconocerlo en la actividad
        authEvents.log(user.getId(), currentId, AuthEventService.SESSION_REVOKED, request,
                ClientInfo.deviceLabel(closed.getUserAgent()));
        return ResponseEntity.ok(ApiResponse.ok("Sesión cerrada", Map.of("current", false)));
    }

    // POST /api/auth/sessions/revoke-others — cerrar sesión en todos los demás dispositivos
    @PostMapping("/sessions/revoke-others")
    public ResponseEntity<ApiResponse<?>> revokeOthers(Authentication authentication, HttpServletRequest request) {
        User user = CurrentUser.require(authentication);
        Long currentId = sessionService.current(request).map(UserSession::getId).orElse(null);
        int closed = sessionService.revokeOthers(user.getId(), currentId, "REVOKED_OTHERS");
        if (closed > 0) {
            authEvents.log(user.getId(), currentId, AuthEventService.SESSIONS_REVOKED, request,
                    AuthEventService.closedSessions(closed));
        }
        return ResponseEntity.ok(ApiResponse.ok(
                closed == 0 ? "No había otras sesiones abiertas" : "Cerraste " + closed + (closed == 1 ? " sesión" : " sesiones") + " en otros dispositivos",
                Map.of("closed", closed)));
    }

    // GET /api/auth/activity — últimos 30 eventos de la cuenta
    @GetMapping("/activity")
    public ResponseEntity<ApiResponse<?>> activity(Authentication authentication) {
        User user = CurrentUser.require(authentication);
        List<Map<String, Object>> list = authEventRepository.findTop30ByUserIdOrderByCreatedAtDescIdDesc(user.getId())
                .stream().map(this::eventMap).toList();
        return ResponseEntity.ok(ApiResponse.ok("Actividad", Map.of("events", list)));
    }

    // POST /api/auth/password {current_password, new_password} — cierra las demás sesiones
    @PostMapping("/password")
    public ResponseEntity<ApiResponse<?>> changePassword(@Valid @RequestBody ChangePasswordRequest body,
                                                         Authentication authentication,
                                                         HttpServletRequest request, HttpServletResponse response) {
        User user = userRepository.findById(CurrentUser.require(authentication).getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        // Mismo freno que el login: con una sesión robada no se puede adivinar la contraseña actual sin límite
        loginAttempts.checkNotBlocked(user.getEmail());
        if (!PasswordPolicy.fitsBcrypt(body.getCurrentPassword())
                || !passwordEncoder.matches(body.getCurrentPassword(), user.getPassword())) {
            loginAttempts.recordFailure(user.getEmail());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WRONG_PASSWORD", "Tu contraseña actual no es correcta");
        }
        loginAttempts.reset(user.getEmail());

        PasswordPolicy.validate(body.getNewPassword());
        if (body.getNewPassword().equals(body.getCurrentPassword())) {
            throw ApiException.unprocessable("La contraseña nueva debe ser distinta a la actual");
        }

        user.setPassword(passwordEncoder.encode(body.getNewPassword()));
        userRepository.save(user);

        // Las demás sesiones se cierran; esta sigue, pero con un token nuevo
        UserSession current = sessionService.current(request).orElse(null);
        int closed = sessionService.revokeOthers(user.getId(), current != null ? current.getId() : null, "PASSWORD_CHANGED");
        if (current != null) sessionService.renew(current, response);
        authEvents.log(user.getId(), current != null ? current.getId() : null, AuthEventService.PASSWORD_CHANGED, request,
                closed > 0 ? AuthEventService.closedSessions(closed) : null);

        return ResponseEntity.ok(ApiResponse.ok("Cambiaste tu contraseña", Map.of("closed_sessions", closed)));
    }

    private Map<String, Object> eventMap(AuthEvent e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("type", e.getEventType());
        map.put("device", ClientInfo.deviceLabel(e.getUserAgent()));
        map.put("ip", e.getIpAddress());
        map.put("detail", e.getDetail());
        map.put("created_at", utc(e.getCreatedAt()));
        return map;
    }

    private static String utc(LocalDateTime value) {
        return value == null ? null : value.format(UTC);
    }
}
