package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import com.emify.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BarbershopService {

    private final BarbershopRepository barbershopRepository;
    private final BarbershopLocationRepository locationRepository;
    private final ServiceRepository serviceRepository;
    private final AvailabilityRepository availabilityRepository;
    private final BarbershopStaffRepository staffRepository;

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
    public ApiResponse<?> createBarbershop(User user, String businessName, String description, String website,
                                          Long barbershopId) {
        if (businessName == null || businessName.isBlank()) {
            return ApiResponse.fail("El nombre del negocio es requerido");
        }

        // Un dueño puede tener varias barberías (marcas distintas). Se reutiliza una existente cuando:
        // - el wizard manda barbershop_id (regresó al paso 1 y lo volvió a enviar), o
        // - tiene una barbería sin sucursales (un alta que dejó a medias).
        // Si no, se crea una barbería nueva.
        Barbershop barbershop;
        if (barbershopId != null) {
            barbershop = barbershopRepository.findById(barbershopId)
                    .filter(b -> b.getOwner().getId().equals(user.getId()))
                    .orElse(null);
            if (barbershop == null) return ApiResponse.fail("Barbería no encontrada");
        } else {
            barbershop = barbershopRepository.findByOwnerIdOrderByIdAsc(user.getId()).stream()
                    .filter(b -> !locationRepository.existsByBarbershopIdAndDeletedAtIsNull(b.getId()))
                    .reduce((first, second) -> second)
                    .orElseGet(() -> Barbershop.builder().owner(user).isActive(true).build());
        }
        barbershop.setName(businessName.trim());
        barbershop.setDescription(description);
        barbershop.setWebsite(website);

        barbershopRepository.save(barbershop);

        // El tipo de cuenta (users.role) ya NO se cambia a "owner" aquí: quien se registró para agendar citas y luego
        // abre su negocio sigue siendo cliente y además dueño. Que es dueño se sabe por sus negocios (ver /auth/me).

        return ApiResponse.ok("Barbería creada exitosamente", Map.of("barbershop", buildBarbershopMap(barbershop)));
    }

    // -------------------------------------------------------
    // CREATE LOCATIONS
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createLocations(User user, Long barbershopId, List<Map<String, Object>> locations) {
        Barbershop barbershop = resolveTargetBarbershop(user, barbershopId, locations);

        if (barbershop == null) {
            return ApiResponse.fail(barbershopId != null ? "Barbería no encontrada" : "No tienes una barbería registrada");
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

    /**
     * ¿A qué barbería van las sucursales nuevas? (un dueño puede tener varias)
     * 1. barbershop_id del body (el front siempre lo manda);
     * 2. la barbería de alguna sucursal existente que venga en la lista;
     * 3. compatibilidad: la barbería más reciente del dueño.
     */
    private Barbershop resolveTargetBarbershop(User user, Long barbershopId, List<Map<String, Object>> locations) {
        if (barbershopId != null) {
            return barbershopRepository.findById(barbershopId)
                    .filter(b -> b.getOwner().getId().equals(user.getId()))
                    .orElse(null);
        }
        for (Map<String, Object> loc : locations) {
            if (loc.get("id") == null) continue;
            Optional<BarbershopLocation> existing = locationRepository
                    .findByIdAndOwnerId(Long.valueOf(loc.get("id").toString()), user.getId());
            if (existing.isPresent()) return existing.get().getBarbershop();
        }
        List<Barbershop> owned = barbershopRepository.findByOwnerIdOrderByIdAsc(user.getId());
        return owned.isEmpty() ? null : owned.get(owned.size() - 1);
    }

    // -------------------------------------------------------
    // GET BARBERSHOP
    // -------------------------------------------------------
    public ApiResponse<?> getBarbershop(User user, Long id) {
        Barbershop barbershop = barbershopRepository.findById(id)
                .filter(b -> b.getOwner().getId().equals(user.getId()))
                .orElse(null);

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
        return saveSchedule(user, locationId, schedule, null);
    }

    /**
     * Guarda el horario semanal de la sucursal. Cada día puede traer su hora de comida
     * (break_start / break_end, opcionales). bufferMinutes = tiempo entre citas; null = no cambiarlo.
     */
    @Transactional
    public ApiResponse<?> saveSchedule(User user, Long locationId, List<Map<String, Object>> schedule,
                                       Integer bufferMinutes) {
        BarbershopLocation location = locationRepository
                .findByIdAndOwnerId(locationId, user.getId()).orElse(null);

        if (location == null) {
            return ApiResponse.fail("Sucursal no encontrada");
        }
        if (bufferMinutes != null && (bufferMinutes < 0 || bufferMinutes > 120)) {
            return ApiResponse.fail("El tiempo entre citas debe estar entre 0 y 120 minutos");
        }

        // Validar todo antes de borrar el horario anterior
        List<Availability> newSchedule = new ArrayList<>();
        for (Map<String, Object> day : schedule) {
            if (!getBool(day, "enabled")) continue;

            Object dayOfWeek = day.get("day_of_week");
            if (!(dayOfWeek instanceof Number number) || number.intValue() < 0 || number.intValue() > 6) {
                return ApiResponse.fail("Día de la semana inválido");
            }
            LocalTime start;
            LocalTime end;
            try {
                start = LocalTime.parse(String.valueOf(day.get("start_time")));
                end = LocalTime.parse(String.valueOf(day.get("end_time")));
            } catch (Exception e) {
                return ApiResponse.fail("Hora inválida, usa el formato HH:mm");
            }
            if (!start.isBefore(end)) {
                return ApiResponse.fail("La hora de apertura debe ser antes que la de cierre");
            }

            // Hora de comida (opcional): debe quedar dentro del horario del día
            LocalTime breakStart = null;
            LocalTime breakEnd = null;
            Object rawBreakStart = day.get("break_start");
            Object rawBreakEnd = day.get("break_end");
            if (rawBreakStart != null && rawBreakEnd != null
                    && !String.valueOf(rawBreakStart).isBlank() && !String.valueOf(rawBreakEnd).isBlank()) {
                try {
                    breakStart = LocalTime.parse(String.valueOf(rawBreakStart));
                    breakEnd = LocalTime.parse(String.valueOf(rawBreakEnd));
                } catch (Exception e) {
                    return ApiResponse.fail("Hora de comida inválida, usa el formato HH:mm");
                }
                if (!breakStart.isBefore(breakEnd)) {
                    return ApiResponse.fail("La comida debe terminar después de empezar");
                }
                if (breakStart.isBefore(start) || breakEnd.isAfter(end)) {
                    return ApiResponse.fail("La hora de comida debe quedar dentro del horario del día");
                }
            }

            newSchedule.add(Availability.builder()
                    .locationId(locationId)
                    .staffId(null)
                    .dayOfWeek(number.shortValue())
                    .startTime(start)
                    .endTime(end)
                    .breakStart(breakStart)
                    .breakEnd(breakEnd)
                    .isActive(true)
                    .build());
        }

        availabilityRepository.deleteByLocationIdAndStaffIdIsNull(locationId);
        availabilityRepository.saveAll(newSchedule);

        if (bufferMinutes != null) {
            location.setBufferMinutes(bufferMinutes);
            locationRepository.save(location);
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
        map.put("buffer_minutes", l.getBufferMinutes() != null ? l.getBufferMinutes() : 10);

        // Para el aviso del dashboard: el dueño atendiendo en varias sucursales
        Long ownerId = l.getBarbershop().getOwner().getId();
        List<BarbershopStaff> staff = staffRepository.findByLocationIdAndIsActiveTrue(l.getId());
        map.put("staff_count", staff.size());
        map.put("owner_is_staff", staff.stream().anyMatch(s -> s.getUser().getId().equals(ownerId)));
        return map;
    }
}
