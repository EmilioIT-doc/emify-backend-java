package com.emify.security.session;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Datos del dispositivo para la lista de sesiones y el registro de actividad. Son informativos: la IP sale de
 * X-Forwarded-For (lo pone el proxy de Vercel/Caddy) y un cliente podría falsearla, así que nunca se usa
 * para decidir permisos.
 */
public final class ClientInfo {

    private ClientInfo() {
    }

    public static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return truncate(ip, 64);
    }

    public static String userAgent(HttpServletRequest request) {
        return truncate(request.getHeader("User-Agent"), 512);
    }

    /** "Chrome en Windows", "Safari en iPhone"… (suficiente para reconocer el dispositivo). */
    public static String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Dispositivo desconocido";
        String ua = userAgent;

        String os;
        if (ua.contains("iPhone")) os = "iPhone";
        else if (ua.contains("iPad")) os = "iPad";
        else if (ua.contains("Android")) os = "Android";
        else if (ua.contains("Windows")) os = "Windows";
        else if (ua.contains("Macintosh") || ua.contains("Mac OS X")) os = "Mac";
        else if (ua.contains("CrOS")) os = "Chromebook";
        else if (ua.contains("Linux")) os = "Linux";
        else os = null;

        String browser;
        if (ua.contains("Edg/") || ua.contains("EdgiOS") || ua.contains("EdgA")) browser = "Edge";
        else if (ua.contains("OPR/") || ua.contains("Opera")) browser = "Opera";
        else if (ua.contains("SamsungBrowser")) browser = "Samsung Internet";
        else if (ua.contains("CriOS") || ua.contains("Chrome/")) browser = "Chrome";
        else if (ua.contains("FxiOS") || ua.contains("Firefox/")) browser = "Firefox";
        else if (ua.contains("Safari/")) browser = "Safari";
        else browser = null;

        if (browser != null && os != null) return browser + " en " + os;
        if (browser != null) return browser;
        if (os != null) return os;
        return "Dispositivo desconocido";
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
