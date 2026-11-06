package com.emify.admin;

import com.emify.common.ApiException;
import com.emify.security.session.ClientInfo;
import com.emify.security.session.SessionService;
import com.emify.security.session.UserSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Escribe la bitácora del super admin.
 * - {@link #logChange}: va DENTRO de la transacción del cambio. Si la bitácora no se puede escribir, el cambio
 *   se deshace (no hay modificación sin rastro). Si el cambio falla, tampoco queda registro de algo que no pasó.
 * - {@link #logRead} y {@link #logAttempt}: transacción propia, se guardan aunque lo demás falle. Llámalas FUERA de
 *   otra transacción (con SQLite solo hay una conexión: una transacción anidada se quedaría esperando).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AdminAuditLogRepository repository;
    private final UserSessionRepository sessionRepository;

    /** Marca la sesión como reautenticada hasta {@code until} y lo deja en la bitácora (misma transacción). */
    @Transactional
    public LocalDateTime elevate(AdminContext ctx, LocalDateTime until) {
        if (sessionRepository.elevate(ctx.session().getId(), until) != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Inicia sesión para continuar.");
        }
        save(ctx, "REAUTH", "session", ctx.session().getId(), null, null, Map.of("elevated_until", until + "Z"));
        return until;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdminAuditLog logChange(AdminContext ctx, String action, String targetType, Object targetId, String reason,
                                   Map<String, Object> before, Map<String, Object> after) {
        return save(ctx, action, targetType, targetId, reason, before, after);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logRead(AdminContext ctx, String action, String targetType, Object targetId, Map<String, Object> filters) {
        save(ctx, action, targetType, targetId, null, null, filters == null || filters.isEmpty() ? null : Map.of("filters", filters));
    }

    /** Intentos (p. ej. reautenticación fallida o acción rechazada). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAttempt(AdminContext ctx, String action, String targetType, Object targetId, String detail) {
        save(ctx, action, targetType, targetId, detail, null, null);
    }

    private AdminAuditLog save(AdminContext ctx, String action, String targetType, Object targetId, String reason,
                               Map<String, Object> before, Map<String, Object> after) {
        AdminAuditLog entry = AdminAuditLog.builder()
                .adminId(ctx.user() != null ? ctx.user().getId() : null)
                .adminEmail(ctx.user() != null ? ctx.user().getEmail() : null)
                .action(action)
                .targetType(targetType)
                .targetId(targetId != null ? truncate(targetId.toString(), 60) : null)
                .reason(truncate(reason, 500))
                .beforeData(json(before))
                .afterData(json(after))
                .ipAddress(ctx.request() != null ? truncate(ClientInfo.ip(ctx.request()), 64) : null)
                .userAgent(ctx.request() != null ? truncate(ClientInfo.userAgent(ctx.request()), 512) : null)
                .sessionId(ctx.session() != null ? ctx.session().getId() : null)
                .createdAt(SessionService.now())
                .build();
        return repository.save(entry);
    }

    private static String json(Map<String, Object> data) {
        if (data == null) return null;
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            // Sin JSON no hay rastro fiel: se corta la operación
            throw new IllegalStateException("No se pudo escribir la bitácora de auditoría", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
