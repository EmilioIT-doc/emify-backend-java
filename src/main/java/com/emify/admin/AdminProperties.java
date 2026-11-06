package com.emify.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Super admin de la plataforma (application.yml → emify.admin.*).
 *
 * @param superAdmins   correos de las cuentas super admin (variable EMIFY_SUPER_ADMINS, separados por coma).
 *                      Es la ÚNICA forma de dar el rol: la app no tiene ningún endpoint para hacerlo. Al arrancar,
 *                      {@link SuperAdminBootstrap} pone users.platform_role = 'SUPER_ADMIN' a esas cuentas y se lo
 *                      quita a cualquier otra. La cuenta debe existir (registrada con su código) antes de arrancar.
 * @param reauthWindow  cuánto vale la confirmación con contraseña para acciones que modifican algo.
 */
@ConfigurationProperties(prefix = "emify.admin")
public record AdminProperties(
        @DefaultValue("") List<String> superAdmins,
        @DefaultValue("15m") Duration reauthWindow
) {
    public boolean isListed(String email) {
        if (email == null || superAdmins == null) return false;
        String e = email.trim().toLowerCase(Locale.ROOT);
        return superAdmins.stream().anyMatch(s -> s != null && s.trim().toLowerCase(Locale.ROOT).equals(e));
    }

    public List<String> normalized() {
        return superAdmins == null ? List.of() : superAdmins.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
