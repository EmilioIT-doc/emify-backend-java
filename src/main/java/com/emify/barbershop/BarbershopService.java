package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import com.emify.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BarbershopService {

    private final BarbershopRepository barbershopRepository;
    private final BarbershopLocationRepository locationRepository;
    private final ServiceRepository serviceRepository;
    private final AvailabilityRepository availabilityRepository;

    private static final List<Map<String, Object>> DEFAULT_SERVICES = List.of(
            Map.of("name", "Corte",                    "description", "Corte clásico a tu elección",       "price", 160, "duration", 30),
            Map.of("name", "Corte de niño",            "description", "Corte clásico para niños",           "price", 150, "duration", 30),
            Map.of("name", "Corte y Barba",            "description", "Corte + arreglo de barba",           "price", 290, "duration", 30),
            Map.of("name", "Barba",                    "description", "Arreglo y perfilado de barba",       "price", 150, "duration", 30),
            Map.of("name", "Tinte y arreglo de barba", "description", "Tinte + arreglo completo de barba", "price", 180, "duration", 30),
            Map.of("name", "Cejas",                    "description", "Perfilado y arreglo de cejas",       "price", 60,  "duration", 30),
            Map.of("name", "Perfilado de cabello",     "description", "Perfilado y definición del cabello", "price", 80,  "duration", 30)
    );

    // -------------------------------------------------------
    // CREATE BARBERSHOP
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createBarbershop(User user, String businessName, String description, String website) {
        Barbershop barbershop = Barbershop.builder()
                .name(businessName)
                .description(description)
                .website(website)
                .owner(user)
                .isActive(true)
                .build();

        barbershopRepository.save(barbershop);

        return ApiResponse.ok("Barbería creada exitosamente", Map.of("barbershop", buildBarbershopMap(barbershop)));
    }

    // -------------------------------------------------------
    // CREATE LOCATIONS
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createLocations(User user, List<Map<String, Object>> locations) {
        Barbershop barbershop = barbershopRepository.findByOwnerId(user.getId())
                .orElse(null);

        if (barbershop == null) {
            return ApiResponse.fail("No tienes una barbería registrada");
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> loc : locations) {
            Long locationId = loc.get("id") != null ? Long.valueOf(loc.get("id").toString()) : null;

            if (locationId != null) {
                // Actualizar existente
                BarbershopLocation existing = locationRepository
                        .findByIdAndOwnerId(locationId, user.getId()).orElse(null);

                if (existing != null) {
                    updateLocationFields(existing, loc);
                    locationRepository.save(existing);
                    result.add(Map.of("id", existing.getId(), "barbershop_id", existing.getBarbershop().getId()));
                }
            } else {
                // Nueva sucursal
                boolean isFirst = !locationRepository.existsByBarbershopIdAndDeletedAtIsNull(barbershop.getId());
                String inviteCode = generateInviteCode();

                BarbershopLocation newLocation = BarbershopLocation.builder()
                        .barbershop(barbershop)
                        .inviteCode(inviteCode)
                        .name((String) loc.get("name"))
                        .address((String) loc.get("address"))
                        .city((String) loc.get("city"))
                        .state((String) loc.get("state"))
                        .phone((String) loc.getOrDefault("phone", null))
                        .phoneCode((String) loc.getOrDefault("phone_code", "+52"))
                        .servesPhysical(getBool(loc, "serves_physical"))
                        .servesHome(getBool(loc, "serves_home"))
                        .servesOnline(getBool(loc, "serves_online"))
                        .latitude(getDecimal(loc, "latitude"))
                        .longitude(getDecimal(loc, "longitude"))
                        .isActive(true)
                        .isDefault(isFirst)
                        .build();

                locationRepository.save(newLocation);

                // Crear servicios por default
                for (Map<String, Object> svc : DEFAULT_SERVICES) {
                    BarberService service = BarberService.builder()
                            .location(newLocation)
                            .name((String) svc.get("name"))
                            .description((String) svc.get("description"))
                            .price(BigDecimal.valueOf(((Number) svc.get("price")).longValue()))
                            .duration((Integer) svc.get("duration"))
                            .isActive(true)
                            .build();
                    serviceRepository.save(service);
                }

                result.add(Map.of("id", newLocation.getId(), "barbershop_id", newLocation.getBarbershop().getId()));
            }
        }

        return ApiResponse.ok("Sucursales guardadas exitosamente", Map.of("Barberias", result));
    }

    // -------------------------------------------------------
    // GET BARBERSHOP
    // -------------------------------------------------------
    public ApiResponse<?> getBarbershop(Long id) {
        Barbershop barbershop = barbershopRepository.findById(id).orElse(null);

        if (barbershop == null) {
            return ApiResponse.fail("Barbería no encontrada");
        }

        List<BarbershopLocation> locations = locationRepository
                .findByBarbershopIdAndDeletedAtIsNull(barbershop.getId());

        return ApiResponse.ok("Get success", Map.of("barbershop", buildBarbershopWithLocations(barbershop, locations)));
    }

    // -------------------------------------------------------
    // UPDATE BARBERSHOP
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> updateBarbershop(User user, Long id, String name, String description, String website) {
        Barbershop barbershop = barbershopRepository.findById(id)
                .filter(b -> b.getOwner().getId().equals(user.getId()))
                .orElse(null);

        if (barbershop == null) {
            return ApiResponse.fail("Barbería no encontrada");
        }

        if (name != null) barbershop.setName(name);
        if (description != null) barbershop.setDescription(description);
        if (website != null) barbershop.setWebsite(website);

        barbershopRepository.save(barbershop);

        return ApiResponse.ok("Empresa actualizada exitosamente", Map.of("barbershop", buildBarbershopMap(barbershop)));
    }

    // -------------------------------------------------------
    // SET DEFAULT LOCATION
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> setDefaultLocation(User user, Long locationId) {
        BarbershopLocation location = locationRepository
                .findByIdAndOwnerId(locationId, user.getId()).orElse(null);

        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        locationRepository.clearDefaultByBarbershopId(location.getBarbershop().getId());
        location.setDefault(true);
        locationRepository.save(location);

        return ApiResponse.ok("Sucursal predeterminada actualizada", Map.of("location", buildLocationMap(location)));
    }

    // -------------------------------------------------------
    // DELETE LOCATION (soft delete)
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> deleteLocation(User user, Long locationId) {
        BarbershopLocation location = locationRepository
                .findByIdAndOwnerId(locationId, user.getId()).orElse(null);

        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        location.setDeletedAt(LocalDateTime.now());
        locationRepository.save(location);

        // Si era default, asignar otra
        if (location.isDefault()) {
            locationRepository.findByBarbershopIdAndDeletedAtIsNull(location.getBarbershop().getId())
                    .stream().findFirst().ifPresent(next -> {
                        next.setDefault(true);
                        locationRepository.save(next);
                    });
        }

        return ApiResponse.ok("Sucursal eliminada correctamente", null);
    }

    // -------------------------------------------------------
    // SAVE SCHEDULE
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> saveSchedule(User user, Long locationId, List<Map<String, Object>> schedule) {
        BarbershopLocation location = locationRepository
                .findByIdAndOwnerId(locationId, user.getId()).orElse(null);

        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }

        availabilityRepository.deleteByLocationIdAndStaffIdIsNull(locationId);

        for (Map<String, Object> day : schedule) {
            boolean enabled = getBool(day, "enabled");
            if (enabled) {
                Availability availability = Availability.builder()
                        .locationId(locationId)
                        .staffId(null)
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
    // HELPERS
    // -------------------------------------------------------
    private void updateLocationFields(BarbershopLocation loc, Map<String, Object> data) {
        if (data.get("name") != null)     loc.setName((String) data.get("name"));
        if (data.get("address") != null)  loc.setAddress((String) data.get("address"));
        if (data.get("city") != null)     loc.setCity((String) data.get("city"));
        if (data.get("state") != null)    loc.setState((String) data.get("state"));
        loc.setPhone((String) data.getOrDefault("phone", null));
        loc.setPhoneCode((String) data.getOrDefault("phone_code", "+52"));
        loc.setServesPhysical(getBool(data, "serves_physical"));
        loc.setServesHome(getBool(data, "serves_home"));
        loc.setServesOnline(getBool(data, "serves_online"));
        loc.setLatitude(getDecimal(data, "latitude"));
        loc.setLongitude(getDecimal(data, "longitude"));
    }

    private boolean getBool(Map<String, Object> map, String key) {
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

    private String generateInviteCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private Map<String, Object> buildBarbershopMap(Barbershop b) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", b.getId());
        map.put("name", b.getName());
        map.put("description", b.getDescription());
        map.put("website", b.getWebsite());
        map.put("is_active", b.isActive());
        map.put("subscription_status", b.getSubscriptionStatus());
        map.put("created_at", b.getCreatedAt());
        map.put("updated_at", b.getUpdatedAt());
        return map;
    }

    private Map<String, Object> buildBarbershopWithLocations(Barbershop b, List<BarbershopLocation> locations) {
        Map<String, Object> map = buildBarbershopMap(b);
        map.put("locations", locations.stream().map(this::buildLocationMap).toList());
        return map;
    }

    private Map<String, Object> buildLocationMap(BarbershopLocation l) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", l.getId());
        map.put("barbershop_id", l.getBarbershop().getId());
        map.put("name", l.getName());
        map.put("address", l.getAddress());
        map.put("city", l.getCity());
        map.put("state", l.getState());
        map.put("phone", l.getPhone());
        map.put("phone_code", l.getPhoneCode());
        map.put("is_active", l.isActive());
        map.put("is_default", l.isDefault());
        map.put("invite_code", l.getInviteCode());
        map.put("serves_physical", l.isServesPhysical());
        map.put("serves_home", l.isServesHome());
        map.put("serves_online", l.isServesOnline());
        map.put("latitude", l.getLatitude());
        map.put("longitude", l.getLongitude());
        return map;
    }
}