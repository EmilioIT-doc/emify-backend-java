package com.emify.security;

import com.emify.common.ApiException;

import java.nio.charset.StandardCharsets;

/**
 * Reglas de contraseña para cuentas nuevas y cambios de contraseña.
 * - Mínimo 8 caracteres.
 * - Máximo 72 bytes: bcrypt ignora todo lo que pasa de 72 bytes, y Spring Security rechaza (con error 500)
 *   contraseñas más largas al hashearlas. Mejor avisar con un mensaje claro.
 * Las contraseñas que ya existen no se tocan: estas reglas aplican al crear o cambiar.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw ApiException.unprocessable("La contraseña debe tener al menos " + MIN_LENGTH + " caracteres");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.unprocessable("La contraseña es demasiado larga (máximo " + MAX_BYTES + " caracteres sin acentos)");
        }
        if (password.isBlank()) {
            throw ApiException.unprocessable("La contraseña no puede ser solo espacios");
        }
    }

    /** Para comparar contraseñas al iniciar sesión: una de más de 72 bytes nunca puede coincidir. */
    public static boolean fitsBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
