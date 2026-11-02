package com.emify.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Reloj en la zona horaria de los negocios (emify.timezone, default America/Mexico_City).
 *
 * Las citas y los horarios se guardan como hora local del negocio (LocalDateTime). Antes se
 * usaba LocalDateTime.now() del servidor (UTC en OpenShift → 6 h adelantado) y el frontend
 * mandaba toISOString() (UTC) que se guardaba tal cual, así que las citas quedaban corridas.
 */
@Component
public class BusinessClock {

    private final ZoneId zone;

    public BusinessClock(@Value("${emify.timezone:America/Mexico_City}") String timezone) {
        this.zone = ZoneId.of(timezone);
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /**
     * Acepta hora local ("2026-11-02T10:00" / "2026-11-02T10:00:00") o con zona
     * ("2026-11-02T16:00:00.000Z", "2026-11-02T10:00:00-06:00"). Con zona, se convierte
     * a la hora local del negocio.
     */
    public LocalDateTime parseClientDateTime(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("La fecha y hora de la cita son requeridas");
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(zone).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // no trae zona horaria → es hora local
        }
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("Formato de fecha inválido: " + value);
        }
    }

    public LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.unprocessable("Fecha inválida, usa el formato AAAA-MM-DD");
        }
    }
}
