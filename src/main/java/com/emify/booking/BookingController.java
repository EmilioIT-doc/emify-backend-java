package com.emify.booking;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.CurrentUser;
import com.emify.common.FileStorageService;
import com.emify.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final FileStorageService fileStorageService;

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

    // POST /api/booking/appointment  (multipart/form-data o x-www-form-urlencoded)
    // Varios servicios: service_ids=1&service_ids=2 (en el orden de la visita). service_id sigue funcionando.
    // Productos: product_ids + product_quantities (listas paralelas).
    // Fotos de referencia (máx. 3): reference_images + reference_service_ids (a qué servicio va cada una).
    // reference_image (una sola foto) se sigue aceptando.
    @PostMapping(value = "/appointment", consumes = {"multipart/form-data", "application/x-www-form-urlencoded"})
    public ResponseEntity<ApiResponse<?>> createAppointment(
            @RequestParam Long location_id,
            @RequestParam(required = false) Long service_id,
            @RequestParam(required = false) List<Long> service_ids,
            @RequestParam(required = false) Long staff_id,
            @RequestParam String start_time,
            @RequestParam String name,
            @RequestParam String phone,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) List<Long> product_ids,
            @RequestParam(required = false) List<Integer> product_quantities,
            @RequestParam(required = false) List<MultipartFile> reference_images,
            @RequestParam(required = false) List<Long> reference_service_ids,
            @RequestParam(required = false) MultipartFile reference_image) {

        List<Long> serviceIds = service_ids != null && !service_ids.isEmpty()
                ? service_ids
                : service_id != null ? List.of(service_id) : List.of();

        List<BookingService.ProductLine> products = new ArrayList<>();
        if (product_ids != null) {
            for (int i = 0; i < product_ids.size(); i++) {
                Integer qty = product_quantities != null && i < product_quantities.size() ? product_quantities.get(i) : 1;
                products.add(new BookingService.ProductLine(product_ids.get(i), qty));
            }
        }

        // Sin S3: en local las fotos se guardan en disco (emify.uploads.*); en prod se ignoran.
        List<BookingService.ReferenceImage> refs = new ArrayList<>();
        if (reference_images != null) {
            for (int i = 0; i < reference_images.size() && refs.size() < AppointmentImage.MAX_PER_VISIT; i++) {
                String url = fileStorageService.storeImage(reference_images.get(i), "appointments");
                if (url == null) continue;
                Long serviceId = reference_service_ids != null && i < reference_service_ids.size() ? reference_service_ids.get(i) : null;
                refs.add(new BookingService.ReferenceImage(serviceId, url));
            }
        }
        if (reference_image != null && refs.size() < AppointmentImage.MAX_PER_VISIT) {
            String url = fileStorageService.storeImage(reference_image, "appointments");
            if (url != null) refs.add(new BookingService.ReferenceImage(null, url));
        }

        ApiResponse<?> response = bookingService.createAppointment(
                location_id, serviceIds, staff_id,
                start_time, name, phone, notes, refs, products);

        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET /api/booking/my-appointments  (requiere sesión; /api/booking/** es público en SecurityConfig)
    @GetMapping("/my-appointments")
    public ResponseEntity<ApiResponse<?>> getMyAppointments(Authentication authentication) {
        User user = CurrentUser.of(authentication).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("No autenticado"));
        }
        return ResponseEntity.ok(bookingService.getMyAppointments(user));
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

        ApiResponse<?> response = bookingService.rescheduleAppointment(token, body != null ? body.get("start_time") : null);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.ok(response);
    }
}