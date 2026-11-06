package com.emify.security.session;

/**
 * Por qué la cookie de sesión de una petición ya no sirve. Viaja en la respuesta 401 como
 * {code, reason, message} para que el frontend le diga al usuario qué pasó.
 *
 * @param code    SESSION_EXPIRED (venció o ya no existe) | SESSION_REVOKED (alguien la cerró)
 * @param reason  motivo guardado en user_sessions.revoked_reason (o INVALID)
 * @param message texto para el usuario
 */
public record SessionFailure(String code, String reason, String message) {

    public static final String EXPIRED = "SESSION_EXPIRED";
    public static final String REVOKED = "SESSION_REVOKED";

    public static SessionFailure invalid() {
        return new SessionFailure(EXPIRED, "INVALID", "Tu sesión ya no es válida. Inicia sesión de nuevo.");
    }

    public static SessionFailure fromReason(String reason) {
        String r = reason == null ? "REVOKED" : reason;
        return switch (r) {
            case "EXPIRED", "IDLE" -> new SessionFailure(EXPIRED, r, "Tu sesión expiró. Inicia sesión de nuevo.");
            case "NEW_LOGIN" -> new SessionFailure(REVOKED, r, "Tu cuenta se abrió en otro dispositivo, así que cerramos esta sesión.");
            case "PASSWORD_CHANGED" -> new SessionFailure(REVOKED, r, "Tu contraseña cambió. Inicia sesión con la nueva.");
            case "LOGOUT", "REPLACED" -> new SessionFailure(REVOKED, r, "Cerraste esta sesión. Inicia sesión de nuevo.");
            case "LIMIT" -> new SessionFailure(REVOKED, r, "Cerramos esta sesión porque tu cuenta está abierta en muchos dispositivos.");
            case "USER_DISABLED" -> new SessionFailure(REVOKED, r, "Tu cuenta está desactivada.");
            case "USER_SUSPENDED" -> new SessionFailure(REVOKED, r, "Tu cuenta fue suspendida. Escríbenos a soporte para revisarlo.");
            default -> new SessionFailure(REVOKED, r, "Se cerró tu sesión desde otro dispositivo. Inicia sesión de nuevo.");
        };
    }
}
