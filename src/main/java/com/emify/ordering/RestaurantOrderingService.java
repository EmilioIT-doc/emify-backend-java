package com.emify.ordering;

import com.emify.auth.dto.ApiResponse;
import com.emify.restaurant.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Servicio público (sin autenticación) para el flujo de reservar/ordenar de un
// cliente final — equivalente a BookingService pero para restaurantes.
@Service
@RequiredArgsConstructor
public class RestaurantOrderingService {

    private final RestaurantLocationRepository locationRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantOrderRepository restaurantOrderRepository;

    // -------------------------------------------------------
    // GET LOCATION INFO (pública) — datos del restaurante + menú activo
    // -------------------------------------------------------
    public ApiResponse<?> getLocationInfo(Long locationId) {
        RestaurantLocation location = locationRepository.findByIdAndDeletedAtIsNull(locationId).orElse(null);
        if (location == null || !location.isActive()) {
            return ApiResponse.fail("Restaurante no encontrado");
        }

        Restaurant restaurant = location.getRestaurant();
        if (!restaurant.isActive()) {
            return ApiResponse.fail("Restaurante no encontrado");
        }

        return ApiResponse.ok("Get success", Map.of("location", buildPublicLocationMap(location, restaurant)));
    }

    // -------------------------------------------------------
    // CREATE ORDER (pública) — el mensaje ya se mandó por WhatsApp desde el
    // navegador del cliente; esto solo guarda una copia para el negocio
    // (contacto del cliente) y para alimentar las métricas del dashboard.
    // Si esto falla, no debe tumbar el flujo del cliente — ver el controller.
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createOrder(Long locationId, String mode, String name, String phone, String address,
                                       String paymentMethod, String allergies, String notes,
                                       List<Map<String, Object>> items) {

        RestaurantLocation location = locationRepository.findByIdAndDeletedAtIsNull(locationId).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }
        if (items == null || items.isEmpty()) {
            return ApiResponse.fail("El pedido no tiene artículos");
        }

        RestaurantOrder order = RestaurantOrder.builder()
                .locationId(locationId)
                .mode(mode)
                .customerName(name)
                .customerPhone(phone)
                .address(address)
                .paymentMethod(paymentMethod)
                .allergies(allergies)
                .notes(notes)
                .subtotal(BigDecimal.ZERO)
                .build();

        BigDecimal subtotal = BigDecimal.ZERO;
        List<RestaurantOrderItem> orderItems = new ArrayList<>();

        for (Map<String, Object> line : items) {
            Long menuItemId = line.get("id") != null ? Long.valueOf(line.get("id").toString()) : null;
            int qty = line.get("qty") != null ? ((Number) line.get("qty")).intValue() : 0;
            if (menuItemId == null || qty <= 0) continue;

            MenuItem menuItem = menuItemRepository.findById(menuItemId).orElse(null);
            if (menuItem == null) continue;

            BigDecimal lineTotal = menuItem.getPrice().multiply(BigDecimal.valueOf(qty));
            subtotal = subtotal.add(lineTotal);

            orderItems.add(RestaurantOrderItem.builder()
                    .order(order)
                    .menuItemId(menuItem.getId())
                    .itemName(menuItem.getName())
                    .unitPrice(menuItem.getPrice())
                    .quantity(qty)
                    .build());
        }

        order.setSubtotal(subtotal);
        order.setItems(orderItems);
        restaurantOrderRepository.save(order); // cascade guarda los items

        return ApiResponse.ok("Pedido registrado", Map.of("order_id", order.getId()));
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------
    private Map<String, Object> buildPublicLocationMap(RestaurantLocation l, Restaurant r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", l.getId());
        map.put("name", l.getName());
        map.put("address", l.getAddress());
        map.put("city", l.getCity());
        map.put("state", l.getState());
        map.put("latitude", l.getLatitude());
        map.put("longitude", l.getLongitude());
        map.put("phone", l.getPhone());
        map.put("phone_code", l.getPhoneCode());

        Map<String, Object> restaurantMap = new LinkedHashMap<>();
        restaurantMap.put("id", r.getId());
        restaurantMap.put("name", r.getName());
        restaurantMap.put("description", r.getDescription());
        restaurantMap.put("cuisine_type", r.getCuisineType());
        restaurantMap.put("theme_color", r.getThemeColor());
        map.put("restaurant", restaurantMap);

        map.put("order_modes", Map.of(
                "table", r.isOrderModeTable(),
                "delivery", r.isOrderModeDelivery(),
                "pickup", r.isOrderModePickup()
        ));

        map.put("menu", buildMenu(l.getId()));
        return map;
    }

    private List<Map<String, Object>> buildMenu(Long locationId) {
        List<MenuCategory> categories = menuCategoryRepository.findByLocationIdOrderBySortOrderAscIdAsc(locationId);

        return categories.stream()
                .map(cat -> {
                    List<Map<String, Object>> items = menuItemRepository
                            .findByCategoryIdAndIsActiveTrueOrderByIdAsc(cat.getId())
                            .stream()
                            .map(item -> {
                                Map<String, Object> itemMap = new LinkedHashMap<>();
                                itemMap.put("id", item.getId());
                                itemMap.put("name", item.getName());
                                itemMap.put("description", item.getDescription());
                                itemMap.put("price", item.getPrice());
                                itemMap.put("image_url", item.getImageUrl());
                                return itemMap;
                            }).toList();

                    Map<String, Object> catMap = new LinkedHashMap<>();
                    catMap.put("id", cat.getId());
                    catMap.put("category", cat.getName());
                    catMap.put("items", items);
                    return catMap;
                })
                .filter(cat -> !((List<?>) cat.get("items")).isEmpty())
                .toList();
    }
}
