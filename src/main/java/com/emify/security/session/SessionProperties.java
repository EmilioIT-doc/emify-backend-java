package com.emify.security.session;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Reglas de la sesión (application.yml → emify.session.*). Los valores por default son los que
 * acordamos con Emilio: 7 días sin uso / 30 días máximo, varias sesiones por cuenta.
 *
 * @param cookieName       nombre de la cookie. En prod "__Host-emify_session" (el prefijo obliga a Secure,
 *                         Path=/ y sin Domain: el navegador la rechaza si no se cumple).
 * @param cookieSecure     Secure en la cookie. false solo en local (http), true en cualquier servidor (https).
 * @param sameSite         Lax: la cookie no viaja en peticiones POST que vienen de otros sitios.
 * @param idleTimeout      sin usar la app este tiempo, la sesión vence.
 * @param absoluteTimeout  aunque se use todos los días, a este tiempo vuelve a pedir contraseña.
 * @param rotationInterval cada cuánto se cambia el token de la cookie (la sesión sigue siendo la misma).
 * @param rotationGrace    cuánto tiempo se acepta el token anterior tras rotarlo (peticiones en paralelo).
 * @param touchInterval    cada cuánto se guarda "última actividad" (para no escribir en BD en cada petición).
 * @param singleSession    true = una sola sesión activa por cuenta: al entrar en otro dispositivo se cierra la anterior.
 * @param maxActive        máximo de sesiones activas por cuenta; al pasarse se cierra la más vieja.
 */
@ConfigurationProperties(prefix = "emify.session")
public record SessionProperties(
        @DefaultValue("emify_session") String cookieName,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("Lax") String sameSite,
        @DefaultValue("7d") Duration idleTimeout,
        @DefaultValue("30d") Duration absoluteTimeout,
        @DefaultValue("24h") Duration rotationInterval,
        @DefaultValue("2m") Duration rotationGrace,
        @DefaultValue("5m") Duration touchInterval,
        @DefaultValue("false") boolean singleSession,
        @DefaultValue("10") int maxActive
) {
}
