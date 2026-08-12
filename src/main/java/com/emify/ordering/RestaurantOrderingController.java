package com.emify.ordering;

import com.emify.auth.dto.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ordering")
@RequiredArgsConstructor
public class RestaurantOrderingController {

    private final RestaurantOrderingService restaurantOrderingService;

    // GET /api/ordering/restaurant-location/{id} — pública, sin login.
    // Info del restaurante + menú activo, para la página donde el cliente ordena.
    @GetMapping("/restaurant-location/{id}")
    public ResponseEntity<ApiResponse<?>> getLocationInfo(@PathVariable Long id) {
        ApiResponse<?> response = restaurantOrderingService.getLocationInfo(id);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/ordering/order — pública, sin login. El pedido real ya se manda
    // por WhatsApp desde el navegador del cliente; esto es solo una copia para
    // el negocio (contacto del cliente + métricas). El frontend la manda en
    // segundo plano y no bloquea ni depende de que esto responda bien.
    @PostMapping("/order")
    public ResponseEntity<ApiResponse<?>> createOrder(@RequestBody CreateOrderRequest request) {
        ApiResponse<?> response = restaurantOrderingService.createOrder(
                request.getLocation_id(),
                request.getMode(),
                request.getName(),
                request.getPhone(),
                request.getAddress(),
                request.getPayment_method(),
                request.getAllergies(),
                request.getNotes(),
                request.getItems()
        );
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Data
    static class CreateOrderRequest {
        private Long location_id;
        private String mode;
        private String name;
        private String phone;
        private String address;
        private String payment_method;
        private String allergies;
        private String notes;
        private List<Map<String, Object>> items;
    }
}
