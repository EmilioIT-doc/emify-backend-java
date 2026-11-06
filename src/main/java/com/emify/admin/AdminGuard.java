package com.emify.admin;

import com.emify.common.ApiException;
import com.emify.common.CurrentUser;
import com.emify.security.LoginAttemptService;
import com.emify.security.PasswordPolicy;
import com.emify.security.session.SessionService;
import com.emify.security.session.UserSession;
import com.emify.security.session.UserSessionRepository;
import com.emify.user.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Doble candado del módulo de administración:
 * 1. SecurityConfig exige ROLE_SUPER_ADMIN en /api/admin/** (sale de users.platform_role).
 * 2. Aquí además el correo debe seguir en emify.admin.super-admins: si se quita de la lista, deja de entrar aunque
 *    la columna no se haya limpiado todavía.
 * Para modificar algo además se pide la contraseña otra vez (reautenticación) y vale {@code reauthWindow} en ESA
 * sesión (user_sessions.elevated_until).
 */
@Component
@RequiredArgsConstructor
public class AdminGuard {

    public static final String REAUTH_REQUIRED = "REAUTH_REQUIRED";

    private final AdminProperties properties;
    private final SessionService sessionService;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;
    private final AdminAuditService audit;

    public AdminContext require(Authentication authentication, HttpServletRequest request) {
        User user = CurrentUser.require(authentication);
        if (!user.isSuperAdmin() || !properties.isListed(user.getEmail())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "No tienes permiso para hacer esto.");
        }
        UserSession session = sessionService.current(request).orElse(null);
        return new AdminContext(user, session, request);
    }

    /** Para acciones que modifican: la reautenticación de esta sesión sigue vigente. */
    public AdminContext requireElevated(Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = require(authentication, request);
        if (elevatedUntil(ctx) == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, REAUTH_REQUIRED,
                    "Por seguridad, confirma tu contraseña para hacer cambios.");
        }
        return ctx;
    }

    /** Hasta cuándo vale la confirmación (UTC) o null si ya venció / nunca se hizo. */
    public LocalDateTime elevatedUntil(AdminContext ctx) {
        if (ctx.session() == null) return null;
        LocalDateTime until = sessionRepository.findElevatedUntil(ctx.session().getId()).orElse(null);
        return until != null && until.isAfter(SessionService.now()) ? until : null;
    }

    /**
     * POST /api/admin/reauth — con el mismo freno contra fuerza bruta que el login.
     * Sin @Transactional a propósito: el intento fallido se guarda en su propia transacción (con SQLite solo hay
     * una conexión y una transacción anidada se quedaría esperando).
     */
    public LocalDateTime reauthenticate(AdminContext ctx, String password) {
        String email = ctx.user().getEmail();
        loginAttempts.checkNotBlocked(email);
        if (password == null || !PasswordPolicy.fitsBcrypt(password)
                || !passwordEncoder.matches(password, ctx.user().getPassword())) {
            loginAttempts.recordFailure(email);
            audit.logAttempt(ctx, "REAUTH_FAILED", "session", ctx.session() != null ? ctx.session().getId() : null,
                    "Contraseña incorrecta");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "La contraseña no es correcta.");
        }
        loginAttempts.reset(email);
        if (ctx.session() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Inicia sesión para continuar.");
        }
        return audit.elevate(ctx, SessionService.now().plus(properties.reauthWindow()));
    }
}
