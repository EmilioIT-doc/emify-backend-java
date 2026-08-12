package com.emify.booking;

import com.emify.auth.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    // GET /api/booking/location/{id}
    @GetMapping("/location/{id}")
    public ResponseEntity<ApiResponse<?>> getLocationInfo(@PathVariable Long id) {
        ApiResponse<?> response = bookingService.getLocationInfo(id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // GET /api/booking/location/{id}/slots?date=2026-07-31&duration=30&staff_id=1
    @GetMapping("/location/{id}/slots")
    public ResponseEntity<ApiResponse<?>> getAvailableSlots(
            @PathVariable Long id,
            @RequestParam String date,
            @RequestParam(defaultValue = "30") int duration,
            @RequestParam(required = false) Long staff_id) {

        ApiResponse<?> response = bookingService.getAvailableSlots(id, date, staff_id, duration);
        return ResponseEntity.ok(response);
    }

    // POST /api/booking/appointment
    @PostMapping(value = "/appointment", consumes = {"multipart/form-data", "application/x-www-form-urlencoded"})
    public ResponseEntity<ApiResponse<?>> createAppointment(
            @RequestParam Long location_id,
            @RequestParam Long service_id,
            @RequestParam(required = false) Long staff_id,
            @RequestParam String start_time,
            @RequestParam String name,
            @RequestParam String phone,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) org.springframework.web.multipart.MultipartFile reference_image) {

        // Por ahora ignoramos la imagen (sin S3 configurado)
        ApiResponse<?> response = bookingService.createAppointment(
                location_id, service_id, staff_id,
                start_time, name, phone, notes, null);

        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET /api/booking/appointment/{token}
    @GetMapping("/appointment/{token}")
    public ResponseEntity<ApiResponse<?>> getAppointmentByToken(@PathVariable String token) {
        ApiResponse<?> response = bookingService.getAppointmentByToken(token);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/booking/appointment/{token}/cancel
    @PostMapping("/appointment/{token}/cancel")
    public ResponseEntity<ApiResponse<?>> cancelAppointment(@PathVariable String token) {
        ApiResponse<?> response = bookingService.cancelAppointment(token);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/booking/appointment/{token}/reschedule
    @PostMapping("/appointment/{token}/reschedule")
    public ResponseEntity<ApiResponse<?>> rescheduleAppointment(
            @PathVariable String token,
            @RequestBody Map<String, String> body) {

        ApiResponse<?> response = bookingService.rescheduleAppointment(token, body.get("start_time"));
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.ok(response);
    }
}