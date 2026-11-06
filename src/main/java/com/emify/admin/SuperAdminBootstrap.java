package com.emify.admin;

import com.emify.user.User;
import com.emify.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Al arrancar sincroniza users.platform_role con emify.admin.super-admins (EMIFY_SUPER_ADMINS):
 * - a las cuentas de la lista les pone SUPER_ADMIN (solo si ya existen y están activas),
 * - a cualquier otra que lo tuviera se lo quita.
 * Cada cambio queda en admin_audit_log (acción SUPER_ADMIN_GRANTED / SUPER_ADMIN_REVOKED, sin admin = el sistema).
 *
 * ⚠️ Registra la cuenta del super admin (con su código de verificación) ANTES de poner su correo en la lista:
 * si el correo no existe todavía, alguien más podría crear una cuenta con ese correo (p. ej. con un código de
 * invitación de equipo, que no verifica el correo) y recibir el rol en el siguiente arranque.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminBootstrap implements ApplicationRunner {

    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final AdminAuditService audit;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AdminContext system = new AdminContext(null, null, null);

        for (User user : userRepository.findByPlatformRole(User.PLATFORM_SUPER_ADMIN)) {
            if (!properties.isListed(user.getEmail())) {
                user.setPlatformRole(null);
                userRepository.save(user);
                audit.logChange(system, "SUPER_ADMIN_REVOKED", "user", user.getId(),
                        "Ya no está en EMIFY_SUPER_ADMINS", role(User.PLATFORM_SUPER_ADMIN, user), role(null, user));
                log.warn("🛡️ Super admin retirado: {}", user.getEmail());
            }
        }

        for (String email : properties.normalized()) {
            User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (user == null) {
                log.warn("🛡️ EMIFY_SUPER_ADMINS incluye {} pero esa cuenta no existe. Regístrala y reinicia.", email);
                continue;
            }
            if (!user.isActive()) {
                log.warn("🛡️ {} está en EMIFY_SUPER_ADMINS pero la cuenta está desactivada: no se le da el rol.", email);
                continue;
            }
            if (!user.isSuperAdmin()) {
                user.setPlatformRole(User.PLATFORM_SUPER_ADMIN);
                userRepository.save(user);
                audit.logChange(system, "SUPER_ADMIN_GRANTED", "user", user.getId(),
                        "Está en EMIFY_SUPER_ADMINS", role(null, user), role(User.PLATFORM_SUPER_ADMIN, user));
                log.info("🛡️ Super admin: {}", email);
            }
        }
    }

    private static Map<String, Object> role(String value, User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("email", user.getEmail());
        map.put("platform_role", value);
        return map;
    }
}
