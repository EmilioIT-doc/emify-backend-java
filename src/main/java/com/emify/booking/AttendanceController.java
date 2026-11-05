package com.emify.booking;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Registro de asistencia de las citas (pop-up del dashboard). Requiere sesión. */
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    // GET /api/attendance/pending — citas ya terminadas sin asistencia registrada
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<?>> pending(Authentication authentication) {
        return ResponseEntity.ok(attendanceService.getPending(CurrentUser.require(authentication)));
    }

    // POST /api/attendance/walk-in — cliente imprevisto (llegó sin cita)
    //   body: {"location_id": 1, "service_id": 2, "start_time": "2026-11-05T13:20:00" (opcional = ahora),
    //          "payment_method": "efectivo"|"tarjeta"|"transferencia", "client_name": "opcional"}
    @PostMapping("/walk-in")
    public ResponseEntity<ApiResponse<?>> walkIn(@RequestBody Map<String, Object> body, Authentication authentication) {
        return ResponseEntity.ok(attendanceService.registerWalkIn(
                CurrentUser.require(authentication),
                asLong(body.get("location_id")),
                asLong(body.get("service_id")),
                body.get("start_time") != null ? body.get("start_time").toString() : null,
                body.get("payment_method") != null ? body.get("payment_method").toString() : null,
                body.get("client_name") != null ? body.get("client_name").toString() : null));
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null || value.toString().isBlank()) return null;
        try { return Long.valueOf(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    // POST /api/attendance/{id}
    //   body: {"attended": true, "payment_method": "efectivo"|"tarjeta"|"transferencia"} → sí llegó (cuenta como venta)
    //   body: {"attended": false}                                                       → no llegó
    @PostMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> mark(@PathVariable Long id,
                                               @RequestBody(required = false) Map<String, Object> body,
                                               Authentication authentication) {
        Boolean attended = null;
        String paymentMethod = null;
        if (body != null) {
            Object a = body.get("attended");
            if (a instanceof Boolean b) attended = b;
            else if (a != null) attended = Boolean.valueOf(a.toString());
            Object p = body.get("payment_method");
            if (p != null) paymentMethod = p.toString();
        }
        return ResponseEntity.ok(attendanceService.mark(CurrentUser.require(authentication), id, attended, paymentMethod));
    }
}
