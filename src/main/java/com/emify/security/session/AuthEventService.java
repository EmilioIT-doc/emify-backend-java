package com.emify.security.session;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Guarda el registro de actividad. Si falla, solo se loguea: nunca debe tumbar un login. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthEventService {

    public static final String SIGNUP = "SIGNUP";
    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGIN_BLOCKED = "LOGIN_BLOCKED";
    public static final String LOGOUT = "LOGOUT";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String SESSIONS_REVOKED = "SESSIONS_REVOKED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String JOIN_INVITE = "JOIN_INVITE";

    private final AuthEventRepository repository;

    /** "Se cerró 1 sesión" / "Se cerraron 3 sesiones" (detalle del registro de actividad). */
    public static String closedSessions(int closed) {
        return closed == 1 ? "Se cerró 1 sesión" : "Se cerraron " + closed + " sesiones";
    }

    public void log(Long userId, Long sessionId, String type, HttpServletRequest request, String detail) {
        try {
            repository.save(AuthEvent.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .eventType(type)
                    .ipAddress(request != null ? ClientInfo.ip(request) : null)
                    .userAgent(request != null ? ClientInfo.userAgent(request) : null)
                    .detail(detail != null && detail.length() > 255 ? detail.substring(0, 255) : detail)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build());
        } catch (RuntimeException e) {
            log.warn("No se pudo guardar el evento {} del usuario {}: {}", type, userId, e.getMessage());
        }
    }
}
