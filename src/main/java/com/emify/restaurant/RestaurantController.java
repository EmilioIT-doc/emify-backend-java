package com.emify.restaurant;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/authRestaurant")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    // POST /api/authRestaurant/restaurant/create
    @PostMapping("/restaurant/create")
    public ResponseEntity<ApiResponse<?>> createRestaurant(
            @RequestBody CreateRestaurantRequest request,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.createRestaurant(
                user,
                request.getBusiness_name(),
                request.getDescription(),
                request.getWebsite(),
                request.getCuisine_type(),
                request.getOrder_modes()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // POST /api/authRestaurant/location/create
    @PostMapping("/location/create")
    public ResponseEntity<ApiResponse<?>> createLocations(
            @RequestBody Map<String, List<Map<String, Object>>> body,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        List<Map<String, Object>> locations = body.get("locations");

        if (locations == null || locations.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("El campo locations es requerido"));
        }

        ApiResponse<?> response = restaurantService.createLocations(user, locations);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET /api/authRestaurant/restaurant/{id}
    @GetMapping("/restaurant/{id}")
    public ResponseEntity<ApiResponse<?>> getRestaurant(@PathVariable Long id) {
        ApiResponse<?> response = restaurantService.getRestaurant(id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/authRestaurant/restaurant/{id}/update
    @PostMapping("/restaurant/{id}/update")
    public ResponseEntity<ApiResponse<?>> updateRestaurant(
            @PathVariable Long id,
            @RequestBody CreateRestaurantRequest request,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.updateRestaurant(
                user, id,
                request.getBusiness_name(),
                request.getDescription(),
                request.getWebsite(),
                request.getCuisine_type(),
                request.getOrder_modes(),
                request.getTheme_color()
        );
        return ResponseEntity.ok(response);
    }

    // POST /api/authRestaurant/location/{id}/schedule
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

        ApiResponse<?> response = restaurantService.saveSchedule(user, id, schedule);
        return ResponseEntity.ok(response);
    }

    // GET /api/authRestaurant/location/{id}/recent-sales — últimos 7 días (Home)
    @GetMapping("/location/{id}/recent-sales")
    public ResponseEntity<ApiResponse<?>> getRecentSales(@PathVariable Long id, Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.getRecentSales(user, id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // GET /api/authRestaurant/location/{id}/top-items — platillos más vendidos (Home)
    @GetMapping("/location/{id}/top-items")
    public ResponseEntity<ApiResponse<?>> getTopItems(@PathVariable Long id, Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.getTopItems(user, id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // GET /api/authRestaurant/location/{id}/recent-orders — feed de pedidos (Home)
    @GetMapping("/location/{id}/recent-orders")
    public ResponseEntity<ApiResponse<?>> getRecentOrders(@PathVariable Long id, Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.getRecentOrders(user, id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/authRestaurant/location/{id}/categories — crear categoría (vacía o para reusar por nombre)
    @PostMapping("/location/{id}/categories")
    public ResponseEntity<ApiResponse<?>> createCategory(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.createCategory(user, id, body.get("name"));
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // GET /api/authRestaurant/location/{id}/customers — quiénes han ordenado
    @GetMapping("/location/{id}/customers")
    public ResponseEntity<ApiResponse<?>> getCustomers(@PathVariable Long id, Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.getCustomers(user, id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // GET /api/authRestaurant/location/{id}/menu — vista completa para el dashboard (incluye inactivos)
    @GetMapping("/location/{id}/menu")
    public ResponseEntity<ApiResponse<?>> getMenu(@PathVariable Long id, Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.getMenuForDashboard(user, id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/authRestaurant/location/{id}/menu — crear platillo (crea la categoría si no existe)
    @PostMapping("/location/{id}/menu")
    public ResponseEntity<ApiResponse<?>> createMenuItem(
            @PathVariable Long id,
            @Valid @RequestBody MenuItemRequest request,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.createMenuItem(
                user, id, request.getCategory(), request.getName(),
                request.getDescription(), request.getPrice(), request.getImage_url()
        );
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // POST /api/authRestaurant/location/{id}/menu/{itemId} — actualizar platillo
    @PostMapping("/location/{id}/menu/{itemId}")
    public ResponseEntity<ApiResponse<?>> updateMenuItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestBody MenuItemRequest request,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.updateMenuItem(
                user, itemId, request.getCategory(), request.getName(),
                request.getDescription(), request.getPrice(), request.getImage_url(), request.getIs_active()
        );
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/authRestaurant/location/{id}/menu/{itemId}/delete
    @PostMapping("/location/{id}/menu/{itemId}/delete")
    public ResponseEntity<ApiResponse<?>> deleteMenuItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        ApiResponse<?> response = restaurantService.deleteMenuItem(user, itemId);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // DTOs internos
    @Data
    static class CreateRestaurantRequest {
        private String business_name;
        private String description;
        private String website;
        private String cuisine_type;
        private Map<String, Object> order_modes;
        private String theme_color;
    }

    @Data
    static class MenuItemRequest {
        @NotBlank(message = "El nombre es requerido")
        private String name;
        private String category;
        private String description;
        private BigDecimal price;
        private String image_url;
        private Boolean is_active;
    }
}
