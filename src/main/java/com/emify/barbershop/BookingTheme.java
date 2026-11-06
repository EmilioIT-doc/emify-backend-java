package com.emify.barbershop;

import java.util.List;
import java.util.Map;

/**
 * Estilos de la página pública de reservas que el dueño elige en Ajustes. Los ids y los colores
 * son los mismos del frontend (pages/verticals/barbershop/booking/bookingThemes.js): si agregas un
 * estilo o un color, cámbialo en los dos lados.
 */
public final class BookingTheme {

    public static final String DEFAULT = "clasico";

    /** Los 4 colores principales de cada estilo; el primero es el del estilo. */
    private static final Map<String, List<String>> PALETTES = Map.of(
            "clasico", List.of("#7C3AED", "#2563EB", "#0F766E", "#DB2777"),
            "noche", List.of("#D9A955", "#C8794A", "#C9CCD3", "#3FB58A"),
            "tradicional", List.of("#8E2A2A", "#1F3A5F", "#2F5D46", "#23190F"),
            "urbano", List.of("#C6F135", "#FF6A1A", "#5B7CFF", "#FF4FA3"));

    private BookingTheme() {
    }

    public static boolean isValid(String theme) {
        return theme != null && PALETTES.containsKey(theme);
    }

    /** null (= el color del estilo) o uno de sus 4 colores. */
    public static boolean isValidAccent(String theme, String accent) {
        if (accent == null) return true;
        return isValid(theme) && PALETTES.get(theme).stream().anyMatch(c -> c.equalsIgnoreCase(accent));
    }

    /** El estilo guardado, o el default si viene vacío o ya no existe. */
    public static String themeOf(String theme) {
        return isValid(theme) ? theme : DEFAULT;
    }
}
