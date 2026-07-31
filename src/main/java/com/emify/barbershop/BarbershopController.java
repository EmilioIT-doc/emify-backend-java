package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import com.emify.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/authBarberShop")
@RequiredArgsConstructor
public class BarbershopController {

    private final BarbershopService barbershopService;

    // POST /api/authBarberShop/barbershop/create
    @PostMapping("/barbershop/create")
    public ResponseEntity<ApiResponse<?>> createBarbershop(
            @RequestBody CreateBarbershopRequest request,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = barbershopService.createBarbershop(
                user,
                request.getBusiness_name(),
                request.getDescription(),
                request.getWebsite()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // POST /api/authBarberShop/barbershop/locations
    @PostMapping("/location/create")
    public ResponseEntity<ApiResponse<?>> createLocations(
            @RequestBody Map<String, List<Map<String, Object>>> body,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        List<Map<String, Object>> locations = body.get("locations");

        if (locations == null || locations.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("El campo locations es requerido"));
        }

        ApiResponse<?> response = barbershopService.createLocations(user, locations);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET /api/authBarberShop/barbershop/{id}
    @GetMapping("/barbershop/{id}")
    public ResponseEntity<ApiResponse<?>> getBarbershop(@PathVariable Long id) {
        ApiResponse<?> response = barbershopService.getBarbershop(id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/authBarberShop/barbershop/{id}/update
    @PostMapping("/barbershop/{id}/update")
    public ResponseEntity<ApiResponse<?>> updateBarbershop(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = barbershopService.updateBarbershop(
                user, id,
                body.get("name"),
                body.get("description"),
                body.get("website")
        );
        return ResponseEntity.ok(response);
    }

    // POST /api/authBarberShop/location/{id}/default
    @PostMapping("/location/{id}/default")
    public ResponseEntity<ApiResponse<?>> setDefaultLocation(
            @PathVariable Long id,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = barbershopService.setDefaultLocation(user, id);
        return ResponseEntity.ok(response);
    }

    // POST /api/authBarberShop/location/{id}/delete
    @PostMapping("/location/{id}/delete")
    public ResponseEntity<ApiResponse<?>> deleteLocation(
            @PathVariable Long id,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = barbershopService.deleteLocation(user, id);
        return ResponseEntity.ok(response);
    }

    // POST /api/authBarberShop/location/{id}/schedule
    @PostMapping("/location/{id}/schedule")
    public ResponseEntity<ApiResponse<?>> saveSchedule(
            @PathVariable Long id,
            @RequestBody Map<String, List<Map<String, Object>>> body,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        List<Map<String, Object>> schedule = body.get("schedule");

        if (schedule == null) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("El campo schedule es requerido"));
        }

        ApiResponse<?> response = barbershopService.saveSchedule(user, id, schedule);
        return ResponseEntity.ok(response);
    }

    // DTO interno
    @Data
    static class CreateBarbershopRequest {
        private String business_name;
        private String description;
        private String website;
    }
}