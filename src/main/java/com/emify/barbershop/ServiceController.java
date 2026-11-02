package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import com.emify.booking.AppointmentRepository;
import com.emify.common.ApiException;
import com.emify.common.CurrentUser;
import com.emify.user.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catálogo de servicios de una sucursal (pestaña "Catálogo").
 *
 * Cambios respecto a la versión anterior:
 *  - El GET regresaba las entidades JPA tal cual (con el proxy lazy de la sucursal → error al
 *    serializar, o peor, exponía datos del dueño). Ahora regresa un mapa plano.
 *  - El frontend edita con POST /{id} y borra con POST /{id}/delete; se aceptan ambos estilos.
 *  - "is_active" no se leía (Lombok + boolean isActive → Jackson esperaba "active").
 *  - Solo el dueño de la sucursal puede crear/editar/borrar.
 */
@RestController
@RequestMapping("/api/authBarberShop/location/{locationId}/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceRepository serviceRepository;
    private final AppointmentRepository appointmentRepository;
    private final LocationAccessService access;

    // GET /api/authBarberShop/location/{locationId}/services
    @GetMapping
    public ResponseEntity<ApiResponse<?>> getServices(@PathVariable Long locationId, Authentication authentication) {
        access.requireOwnerOrStaff(CurrentUser.require(authentication), locationId);
        var services = serviceRepository.findByLocationIdOrderByCreatedAtDesc(locationId).stream()
                .map(this::buildServiceMap)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok("Servicios", Map.of("services", services)));
    }

    // POST /api/authBarberShop/location/{locationId}/services
    @PostMapping
    public ResponseEntity<ApiResponse<?>> createService(
            @PathVariable Long locationId,
            @Valid @RequestBody ServiceRequest request,
            Authentication authentication) {

        BarbershopLocation location = access.requireOwner(CurrentUser.require(authentication), locationId);

        BarberService service = BarberService.builder()
                .location(location)
                .name(request.getName().trim())
                .description(request.getDescription())
                .price(request.getPrice())
                .duration(request.getDuration())
                .isActive(request.getIsActive() == null || request.getIsActive())
                .build();

        serviceRepository.save(service);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Servicio creado", Map.of("service", buildServiceMap(service))));
    }

    // PUT o POST /api/authBarberShop/location/{locationId}/services/{serviceId}
    @RequestMapping(value = "/{serviceId}", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<ApiResponse<?>> updateService(
            @PathVariable Long locationId,
            @PathVariable Long serviceId,
            @RequestBody ServiceRequest request,
            Authentication authentication) {

        access.requireOwner(CurrentUser.require(authentication), locationId);
        BarberService service = serviceRepository.findByIdAndLocationId(serviceId, locationId)
                .orElseThrow(() -> ApiException.notFound("Servicio no encontrado"));

        if (request.getName() != null) {
            if (request.getName().isBlank()) throw ApiException.unprocessable("El nombre es requerido");
            service.setName(request.getName().trim());
        }
        if (request.getDescription() != null) service.setDescription(request.getDescription());
        if (request.getPrice() != null) {
            if (request.getPrice().signum() < 0) throw ApiException.unprocessable("El precio debe ser mayor o igual a 0");
            service.setPrice(request.getPrice());
        }
        if (request.getDuration() != null) {
            if (request.getDuration() < 1) throw ApiException.unprocessable("La duración debe ser al menos 1 minuto");
            service.setDuration(request.getDuration());
        }
        if (request.getIsActive() != null) service.setActive(request.getIsActive());

        serviceRepository.save(service);

        return ResponseEntity.ok(ApiResponse.ok("Servicio actualizado", Map.of("service", buildServiceMap(service))));
    }

    // DELETE /api/authBarberShop/location/{locationId}/services/{serviceId}
    @DeleteMapping("/{serviceId}")
    public ResponseEntity<ApiResponse<?>> deleteService(
            @PathVariable Long locationId,
            @PathVariable Long serviceId,
            Authentication authentication) {
        return delete(locationId, serviceId, authentication);
    }

    // POST /api/authBarberShop/location/{locationId}/services/{serviceId}/delete  (lo que usa el frontend)
    @PostMapping("/{serviceId}/delete")
    public ResponseEntity<ApiResponse<?>> deleteServicePost(
            @PathVariable Long locationId,
            @PathVariable Long serviceId,
            Authentication authentication) {
        return delete(locationId, serviceId, authentication);
    }

    private ResponseEntity<ApiResponse<?>> delete(Long locationId, Long serviceId, Authentication authentication) {
        User user = CurrentUser.require(authentication);
        access.requireOwner(user, locationId);
        BarberService service = serviceRepository.findByIdAndLocationId(serviceId, locationId)
                .orElseThrow(() -> ApiException.notFound("Servicio no encontrado"));

        // En la BD, appointments.service_id tiene ON DELETE CASCADE: borrar el servicio
        // borraría también el historial de citas. Si ya tiene citas, solo se puede desactivar.
        if (appointmentRepository.existsByServiceId(serviceId)) {
            throw ApiException.unprocessable("Este servicio ya tiene citas registradas. Desactívalo en lugar de eliminarlo.");
        }

        serviceRepository.delete(service);
        return ResponseEntity.ok(ApiResponse.ok("Servicio eliminado", null));
    }

    private Map<String, Object> buildServiceMap(BarberService s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("location_id", s.getLocation().getId());
        map.put("name", s.getName());
        map.put("description", s.getDescription() != null ? s.getDescription() : "");
        map.put("price", s.getPrice());
        map.put("duration", s.getDuration());
        map.put("is_active", s.isActive());
        map.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        map.put("updated_at", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        return map;
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
        @JsonProperty("is_active")
        private Boolean isActive;
    }
}
