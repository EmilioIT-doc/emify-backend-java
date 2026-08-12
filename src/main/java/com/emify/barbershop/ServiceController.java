package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/authBarberShop/location/{locationId}/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceRepository serviceRepository;
    private final BarbershopLocationRepository locationRepository;

    // GET /api/authBarberShop/location/{locationId}/services
    @GetMapping
    public ResponseEntity<?> getServices(@PathVariable Long locationId) {
        var services = serviceRepository.findByLocationIdOrderByCreatedAtDesc(locationId);
        return ResponseEntity.ok(Map.of("data", Map.of("services", services)));
    }

    // POST /api/authBarberShop/location/{locationId}/services
    @PostMapping
    public ResponseEntity<?> createService(
            @PathVariable Long locationId,
            @Valid @RequestBody ServiceRequest request) {

        BarbershopLocation location = locationRepository.findById(locationId).orElse(null);
        if (location == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("Sucursal no encontrada"));
        }

        BarberService service = BarberService.builder()
                .location(location)
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .duration(request.getDuration())
                .isActive(request.isActive())
                .build();

        serviceRepository.save(service);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("data", Map.of("service", buildServiceMap(service))));
    }

    // PUT /api/authBarberShop/location/{locationId}/services/{serviceId}
    @PutMapping("/{serviceId}")
    public ResponseEntity<?> updateService(
            @PathVariable Long locationId,
            @PathVariable Long serviceId,
            @RequestBody ServiceRequest request) {

        BarberService service = serviceRepository.findByIdAndLocationId(serviceId, locationId).orElse(null);
        if (service == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("Servicio no encontrado"));
        }

        if (request.getName() != null)        service.setName(request.getName());
        if (request.getDescription() != null) service.setDescription(request.getDescription());
        if (request.getPrice() != null)       service.setPrice(request.getPrice());
        if (request.getDuration() != null)    service.setDuration(request.getDuration());
        service.setActive(request.isActive());

        serviceRepository.save(service);

        return ResponseEntity.ok(Map.of("data", Map.of("service", buildServiceMap(service))));
    }

    // DELETE /api/authBarberShop/location/{locationId}/services/{serviceId}
    @DeleteMapping("/{serviceId}")
    public ResponseEntity<?> deleteService(
            @PathVariable Long locationId,
            @PathVariable Long serviceId) {

        BarberService service = serviceRepository.findByIdAndLocationId(serviceId, locationId).orElse(null);
        if (service == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("Servicio no encontrado"));
        }

        serviceRepository.delete(service);

        return ResponseEntity.ok(Map.of("data", Map.of("message", "Servicio eliminado")));
    }

    private Map<String, Object> buildServiceMap(BarberService s) {
        return Map.of(
                "id",          s.getId(),
                "location_id", s.getLocation().getId(),
                "name",        s.getName(),
                "description", s.getDescription() != null ? s.getDescription() : "",
                "price",       s.getPrice(),
                "duration",    s.getDuration(),
                "is_active",   s.isActive(),
                "created_at",  s.getCreatedAt().toString(),
                "updated_at",  s.getUpdatedAt().toString()
        );
    }

    @Data
    static class ServiceRequest {
        @NotBlank(message = "El nombre es requerido")
        private String name;
        private String description;
        @NotNull(message = "El precio es requerido")
        @DecimalMin(value = "0.0", message = "El precio debe ser mayor o igual a 0")
        private BigDecimal price;
        @NotNull(message = "La duración es requerida")
        @Min(value = 1, message = "La duración debe ser al menos 1 minuto")
        private Integer duration;
        private boolean isActive = true;
    }
}