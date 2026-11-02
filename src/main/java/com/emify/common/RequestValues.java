package com.emify.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lectura tolerante de valores que llegan en bodies JSON genéricos (Map<String, Object>). */
public final class RequestValues {

    private RequestValues() {
    }

    /** Número o texto numérico → Long; null o vacío → null; cualquier otra cosa → 422. */
    public static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        String text = value.toString().trim();
        if (text.isEmpty() || text.equals("null")) return null;
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("Identificador inválido: " + text);
        }
    }

    /** Lista de objetos JSON → List<Map>; lo que no sea objeto se ignora. null si no es lista. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return result;
    }
}
