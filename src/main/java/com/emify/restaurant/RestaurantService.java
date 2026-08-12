package com.emify.restaurant;

import com.emify.auth.dto.ApiResponse;
import com.emify.user.User;
import com.emify.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final RestaurantLocationRepository locationRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantAvailabilityRepository availabilityRepository;
    private final RestaurantOrderRepository orderRepository;
    private final RestaurantOrderItemRepository orderItemRepository;
    private final UserRepository userRepository;

    private static final String[] DAY_NAMES = {"Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb"};

    // -------------------------------------------------------
    // CREATE RESTAURANT
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createRestaurant(User user, String businessName, String description, String website,
                                            String cuisineType, Map<String, Object> orderModes) {

        Restaurant restaurant = Restaurant.builder()
                .name(businessName)
                .description(description)
                .website(website)
                .cuisineType(cuisineType)
                .owner(user)
                .orderModeTable(getBool(orderModes, "table"))
                .orderModeDelivery(getBool(orderModes, "delivery"))
                .orderModePickup(getBool(orderModes, "pickup"))
                .isActive(true)
                .build();

        restaurantRepository.save(restaurant);

        // Mismo criterio que en barbería: el rol se sincroniza aquí, no depende
        // de una llamada aparte del frontend a /auth/updateRole.
        if (user.getRole() != User.Role.owner) {
            user.setRole(User.Role.owner);
            userRepository.save(user);
        }

        return ApiResponse.ok("Restaurante creado exitosamente", Map.of("restaurant", buildRestaurantMap(restaurant)));
    }

    // -------------------------------------------------------
    // CREATE / UPDATE LOCATIONS
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createLocations(User user, List<Map<String, Object>> locations) {
        Restaurant restaurant = restaurantRepository.findByOwnerId(user.getId()).orElse(null);

        if (restaurant == null) {
            return ApiResponse.fail("No tienes un restaurante registrado");
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> loc : locations) {
            Long locationId = loc.get("id") != null ? Long.valueOf(loc.get("id").toString()) : null;

            if (locationId != null) {
                RestaurantLocation existing = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
                if (existing != null) {
                    updateLocationFields(existing, loc);
                    locationRepository.save(existing);
                    result.add(Map.of("id", existing.getId(), "restaurant_id", existing.getRestaurant().getId()));
                }
            } else {
                boolean isFirst = !locationRepository.existsByRestaurantIdAndDeletedAtIsNull(restaurant.getId());

                RestaurantLocation newLocation = RestaurantLocation.builder()
                        .restaurant(restaurant)
                        .name((String) loc.get("name"))
                        .address((String) loc.get("address"))
                        .city((String) loc.get("city"))
                        .state((String) loc.get("state"))
                        .phone((String) loc.getOrDefault("phone", null))
                        .phoneCode((String) loc.getOrDefault("phone_code", "+52"))
                        .latitude(getDecimal(loc, "latitude"))
                        .longitude(getDecimal(loc, "longitude"))
                        .isActive(true)
                        .isDefault(isFirst)
                        .build();

                locationRepository.save(newLocation);
                result.add(Map.of("id", newLocation.getId(), "restaurant_id", newLocation.getRestaurant().getId()));
            }
        }

        return ApiResponse.ok("Sucursales guardadas exitosamente", Map.of("Restaurantes", result));
    }

    // -------------------------------------------------------
    // GET RESTAURANT (vista de dueño, con sucursales)
    // -------------------------------------------------------
    public ApiResponse<?> getRestaurant(Long id) {
        Restaurant restaurant = restaurantRepository.findById(id).orElse(null);
        if (restaurant == null) {
            return ApiResponse.fail("Restaurante no encontrado");
        }

        List<RestaurantLocation> locations = locationRepository.findByRestaurantIdAndDeletedAtIsNull(restaurant.getId());
        return ApiResponse.ok("Get success", Map.of("restaurant", buildRestaurantWithLocations(restaurant, locations)));
    }

    // -------------------------------------------------------
    // UPDATE RESTAURANT
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> updateRestaurant(User user, Long id, String name, String description, String website,
                                            String cuisineType, Map<String, Object> orderModes, String themeColor) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .filter(r -> r.getOwner().getId().equals(user.getId()))
                .orElse(null);

        if (restaurant == null) {
            return ApiResponse.fail("Restaurante no encontrado");
        }

        if (name != null) restaurant.setName(name);
        if (description != null) restaurant.setDescription(description);
        if (website != null) restaurant.setWebsite(website);
        if (cuisineType != null) restaurant.setCuisineType(cuisineType);
        if (orderModes != null) {
            restaurant.setOrderModeTable(getBool(orderModes, "table"));
            restaurant.setOrderModeDelivery(getBool(orderModes, "delivery"));
            restaurant.setOrderModePickup(getBool(orderModes, "pickup"));
        }
        if (themeColor != null && !themeColor.isBlank()) restaurant.setThemeColor(themeColor);

        restaurantRepository.save(restaurant);
        return ApiResponse.ok("Restaurante actualizado exitosamente", Map.of("restaurant", buildRestaurantMap(restaurant)));
    }

    // -------------------------------------------------------
    // SAVE SCHEDULE (horario de sucursal)
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> saveSchedule(User user, Long locationId, List<Map<String, Object>> schedule) {
        RestaurantLocation location = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        availabilityRepository.deleteByLocationId(locationId);

        for (Map<String, Object> day : schedule) {
            if (getBool(day, "enabled")) {
                RestaurantAvailability availability = RestaurantAvailability.builder()
                        .locationId(locationId)
                        .dayOfWeek(((Number) day.get("day_of_week")).shortValue())
                        .startTime(java.time.LocalTime.parse((String) day.get("start_time")))
                        .endTime(java.time.LocalTime.parse((String) day.get("end_time")))
                        .isActive(true)
                        .build();
                availabilityRepository.save(availability);
            }
        }

        return ApiResponse.ok("Horario guardado exitosamente", null);
    }

    // -------------------------------------------------------
    // MENÚ — crear categoría vacía (para poder organizarlas antes de tener platillos)
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createCategory(User user, Long locationId, String name) {
        RestaurantLocation location = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }
        if (name == null || name.isBlank()) {
            return ApiResponse.fail("El nombre de la categoría es requerido");
        }

        MenuCategory category = findOrCreateCategory(location, name);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", category.getId());
        map.put("category", category.getName());
        return ApiResponse.ok("Categoría creada", map);
    }

    // -------------------------------------------------------
    // MENÚ — vista completa para el dashboard del dueño (incluye inactivos)
    // -------------------------------------------------------
    public ApiResponse<?> getMenuForDashboard(User user, Long locationId) {
        RestaurantLocation location = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        List<MenuCategory> categories = menuCategoryRepository.findByLocationIdOrderBySortOrderAscIdAsc(locationId);
        List<Map<String, Object>> menu = categories.stream().map(cat -> {
            List<Map<String, Object>> items = menuItemRepository.findByCategoryIdOrderByIdAsc(cat.getId())
                    .stream().map(this::buildMenuItemMap).toList();
            Map<String, Object> catMap = new LinkedHashMap<>();
            catMap.put("id", cat.getId());
            catMap.put("category", cat.getName());
            catMap.put("items", items);
            return catMap;
        }).toList();

        return ApiResponse.ok("Get success", Map.of("menu", menu));
    }

    // -------------------------------------------------------
    // MENÚ — crear item (crea la categoría si no existe todavía)
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createMenuItem(User user, Long locationId, String categoryName, String name,
                                          String description, BigDecimal price, String imageUrl) {
        RestaurantLocation location = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }
        if (name == null || name.isBlank() || price == null) {
            return ApiResponse.fail("Nombre y precio son requeridos");
        }

        MenuCategory category = findOrCreateCategory(location, categoryName);

        MenuItem item = MenuItem.builder()
                .category(category)
                .name(name)
                .description(description)
                .price(price.setScale(2, RoundingMode.HALF_UP))
                .imageUrl(imageUrl)
                .isActive(true)
                .build();

        menuItemRepository.save(item);
        return ApiResponse.ok("Platillo agregado exitosamente", Map.of("item", buildMenuItemMap(item)));
    }

    // -------------------------------------------------------
    // MENÚ — actualizar item
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> updateMenuItem(User user, Long itemId, String categoryName, String name,
                                          String description, BigDecimal price, String imageUrl, Boolean isActive) {
        MenuItem item = menuItemRepository.findByIdAndOwnerId(itemId, user.getId()).orElse(null);
        if (item == null) {
            return ApiResponse.fail("Platillo no encontrado");
        }

        if (categoryName != null && !categoryName.isBlank() && !categoryName.equals(item.getCategory().getName())) {
            item.setCategory(findOrCreateCategory(item.getCategory().getLocation(), categoryName));
        }
        if (name != null) item.setName(name);
        if (description != null) item.setDescription(description);
        if (price != null) item.setPrice(price.setScale(2, RoundingMode.HALF_UP));
        if (imageUrl != null) item.setImageUrl(imageUrl);
        if (isActive != null) item.setActive(isActive);

        menuItemRepository.save(item);
        return ApiResponse.ok("Platillo actualizado exitosamente", Map.of("item", buildMenuItemMap(item)));
    }

    // -------------------------------------------------------
    // MENÚ — eliminar item (soft: is_active = false)
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> deleteMenuItem(User user, Long itemId) {
        MenuItem item = menuItemRepository.findByIdAndOwnerId(itemId, user.getId()).orElse(null);
        if (item == null) {
            return ApiResponse.fail("Platillo no encontrado");
        }

        item.setActive(false);
        menuItemRepository.save(item);
        return ApiResponse.ok("Platillo eliminado correctamente", null);
    }

    // -------------------------------------------------------
    // MÉTRICAS — ventas de los últimos 7 días (para la gráfica del Home)
    // -------------------------------------------------------
    public ApiResponse<?> getRecentSales(User user, Long locationId) {
        RestaurantLocation location = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(6).atStartOfDay();
        List<RestaurantOrder> orders = orderRepository.findByLocationIdAndCreatedAtAfterOrderByCreatedAtAsc(locationId, start);

        List<Map<String, Object>> days = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int totalPedidos = 0;

        for (int i = 0; i < 7; i++) {
            LocalDate day = today.minusDays(6 - i);
            BigDecimal ventas = BigDecimal.ZERO;
            int pedidos = 0;
            for (RestaurantOrder o : orders) {
                if (o.getCreatedAt().toLocalDate().equals(day)) {
                    ventas = ventas.add(o.getSubtotal());
                    pedidos++;
                }
            }
            days.add(Map.of(
                    "day", DAY_NAMES[day.getDayOfWeek().getValue() % 7],
                    "ventas", ventas,
                    "pedidos", pedidos
            ));
            total = total.add(ventas);
            totalPedidos += pedidos;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("total", total);
        result.put("total_pedidos", totalPedidos);
        return ApiResponse.ok("Get success", result);
    }

    // -------------------------------------------------------
    // MÉTRICAS — platillos más vendidos (este mes vs. mes pasado)
    // -------------------------------------------------------
    public ApiResponse<?> getTopItems(User user, Long locationId) {
        RestaurantLocation location = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thisMonthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay();
        LocalDateTime lastMonthStart = thisMonthStart.minusMonths(1);
        LocalDateTime lastMonthEnd = thisMonthStart.minusSeconds(1);

        List<Object[]> thisMonthRows = orderItemRepository.findTopItems(locationId, thisMonthStart, now);
        List<Object[]> lastMonthRows = orderItemRepository.findTopItems(locationId, lastMonthStart, lastMonthEnd);

        Map<String, Integer> lastMonthMap = new HashMap<>();
        for (Object[] row : lastMonthRows) {
            lastMonthMap.put((String) row[0], ((Number) row[1]).intValue());
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] row : thisMonthRows) {
            String name = (String) row[0];
            int qty = ((Number) row[1]).intValue();
            items.add(Map.of(
                    "name", name,
                    "thisMonth", qty,
                    "lastMonth", lastMonthMap.getOrDefault(name, 0)
            ));
        }

        return ApiResponse.ok("Get success", Map.of("items", items));
    }

    // -------------------------------------------------------
    // MÉTRICAS — feed de pedidos recientes
    // -------------------------------------------------------
    public ApiResponse<?> getRecentOrders(User user, Long locationId) {
        RestaurantLocation location = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        List<RestaurantOrder> orders = orderRepository.findTop20ByLocationIdOrderByCreatedAtDesc(locationId);
        List<Map<String, Object>> result = orders.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("customer_name", o.getCustomerName());
            m.put("mode", o.getMode());
            m.put("subtotal", o.getSubtotal());
            m.put("payment_method", o.getPaymentMethod());
            m.put("created_at", o.getCreatedAt());
            return m;
        }).toList();

        return ApiResponse.ok("Get success", Map.of("orders", result));
    }

    // -------------------------------------------------------
    // CLIENTES — quiénes han ordenado, agregado por teléfono
    // -------------------------------------------------------
    public ApiResponse<?> getCustomers(User user, Long locationId) {
        RestaurantLocation location = locationRepository.findByIdAndOwnerId(locationId, user.getId()).orElse(null);
        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        List<RestaurantOrder> orders = orderRepository.findByLocationIdOrderByCreatedAtDesc(locationId);

        // orders ya viene ordenado del más reciente al más viejo, así que la primera
        // vez que vemos un teléfono es su pedido más reciente (nombre + fecha).
        Map<String, Map<String, Object>> byPhone = new LinkedHashMap<>();
        for (RestaurantOrder o : orders) {
            Map<String, Object> entry = byPhone.computeIfAbsent(o.getCustomerPhone(), phone -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", o.getCustomerName());
                e.put("phone", phone);
                e.put("orders", 0);
                e.put("total_spent", BigDecimal.ZERO);
                e.put("last_order_at", o.getCreatedAt());
                return e;
            });
            entry.put("orders", ((Integer) entry.get("orders")) + 1);
            entry.put("total_spent", ((BigDecimal) entry.get("total_spent")).add(o.getSubtotal()));
        }

        return ApiResponse.ok("Get success", Map.of("customers", new ArrayList<>(byPhone.values())));
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------
    private MenuCategory findOrCreateCategory(RestaurantLocation location, String categoryName) {
        String normalized = (categoryName == null || categoryName.isBlank()) ? "General" : categoryName.trim();

        return menuCategoryRepository.findByLocationIdOrderBySortOrderAscIdAsc(location.getId()).stream()
                .filter(c -> c.getName().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseGet(() -> {
                    MenuCategory created = MenuCategory.builder()
                            .location(location)
                            .name(normalized)
                            .sortOrder(0)
                            .build();
                    return menuCategoryRepository.save(created);
                });
    }

    private void updateLocationFields(RestaurantLocation loc, Map<String, Object> data) {
        if (data.get("name") != null) loc.setName((String) data.get("name"));
        if (data.get("address") != null) loc.setAddress((String) data.get("address"));
        if (data.get("city") != null) loc.setCity((String) data.get("city"));
        if (data.get("state") != null) loc.setState((String) data.get("state"));
        loc.setPhone((String) data.getOrDefault("phone", null));
        loc.setPhoneCode((String) data.getOrDefault("phone_code", "+52"));
        loc.setLatitude(getDecimal(data, "latitude"));
        loc.setLongitude(getDecimal(data, "longitude"));
    }

    private boolean getBool(Map<String, Object> map, String key) {
        if (map == null) return false;
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    private BigDecimal getDecimal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        return new BigDecimal(val.toString());
    }

    private Map<String, Object> buildRestaurantMap(Restaurant r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("name", r.getName());
        map.put("description", r.getDescription());
        map.put("website", r.getWebsite());
        map.put("cuisine_type", r.getCuisineType());
        map.put("order_modes", Map.of(
                "table", r.isOrderModeTable(),
                "delivery", r.isOrderModeDelivery(),
                "pickup", r.isOrderModePickup()
        ));
        map.put("is_active", r.isActive());
        map.put("theme_color", r.getThemeColor());
        map.put("subscription_status", r.getSubscriptionStatus());
        map.put("created_at", r.getCreatedAt());
        map.put("updated_at", r.getUpdatedAt());
        return map;
    }

    private Map<String, Object> buildRestaurantWithLocations(Restaurant r, List<RestaurantLocation> locations) {
        Map<String, Object> map = buildRestaurantMap(r);
        map.put("locations", locations.stream().map(this::buildLocationMap).toList());
        return map;
    }

    private Map<String, Object> buildLocationMap(RestaurantLocation l) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", l.getId());
        map.put("restaurant_id", l.getRestaurant().getId());
        map.put("name", l.getName());
        map.put("address", l.getAddress());
        map.put("city", l.getCity());
        map.put("state", l.getState());
        map.put("phone", l.getPhone());
        map.put("phone_code", l.getPhoneCode());
        map.put("is_active", l.isActive());
        map.put("is_default", l.isDefault());
        map.put("latitude", l.getLatitude());
        map.put("longitude", l.getLongitude());
        return map;
    }

    private Map<String, Object> buildMenuItemMap(MenuItem i) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", i.getId());
        map.put("category_id", i.getCategory().getId());
        map.put("category", i.getCategory().getName());
        map.put("name", i.getName());
        map.put("description", i.getDescription());
        map.put("price", i.getPrice());
        map.put("image_url", i.getImageUrl());
        map.put("is_active", i.isActive());
        return map;
    }
}
