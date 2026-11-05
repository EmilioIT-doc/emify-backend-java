package com.emify.content;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contenido corto para los paneles. Hoy: frases y datos que rotan en la tarjeta morada de "Mi agenda"
 * del barbero cuando tiene el día libre. Vive en el backend para poder cambiarlo sin publicar el frontend
 * (y, más adelante, moverlo a una tabla o a un servicio externo sin tocar la pantalla).
 */
@RestController
@RequestMapping("/api/content")
public class ContentController {

    /** Cada cuántos segundos cambia la frase en pantalla. */
    private static final int ROTATE_SECONDS = 8;

    private static final List<Map<String, String>> BARBER_PHRASES = List.of(
            phrase("dato", "Un cliente que vuelve en 3 o 4 semanas suele ser un cliente fijo. Recuérdale cuándo le toca."),
            phrase("frase", "Un buen corte se nota hoy; un buen trato se recuerda la próxima vez."),
            phrase("dato", "Comparte tu link de reservas en tus historias: es la forma más rápida de llenar huecos."),
            phrase("frase", "La constancia también se afila."),
            phrase("dato", "Las tardes del viernes y los sábados por la mañana son los horarios más pedidos en barbería."),
            phrase("frase", "Cada silla vacía es una oportunidad de practicar algo nuevo."),
            phrase("dato", "Marcar la asistencia al terminar cada cita mantiene tus ventas al día."),
            phrase("frase", "Tu mejor publicidad sale caminando de tu silla."),
            phrase("dato", "Una foto del resultado, con permiso del cliente, vale más que cualquier anuncio."),
            phrase("frase", "Escuchar bien es la mitad del corte."),
            phrase("dato", "Si llega alguien sin cita, regístralo como cliente imprevisto para que cuente en tus ventas."),
            phrase("frase", "Las herramientas limpias dicen mucho antes de que digas algo."),
            phrase("dato", "Ofrecer barba o cejas al terminar un corte aumenta el ticket sin alargar mucho la cita."),
            phrase("frase", "Un día libre también es parte del trabajo: descansa para volver con buena mano.")
    );

    // GET /api/content/barber-phrases → { phrases: [{kind: "frase"|"dato", text}], rotate_seconds }
    @GetMapping("/barber-phrases")
    public ResponseEntity<ApiResponse<?>> barberPhrases(Authentication authentication) {
        CurrentUser.require(authentication);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phrases", BARBER_PHRASES);
        data.put("rotate_seconds", ROTATE_SECONDS);
        return ResponseEntity.ok(ApiResponse.ok("Frases", data));
    }

    private static Map<String, String> phrase(String kind, String text) {
        return Map.of("kind", kind, "text", text);
    }
}
