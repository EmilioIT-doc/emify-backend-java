package com.emify.booking;

import com.emify.auth.dto.ApiResponse;
import com.emify.barbershop.*;
import com.emify.booking.Appointment.AppointmentStatus;
import com.emify.common.BusinessClock;
import com.emify.kafka.AppointmentEvent;
import com.emify.kafka.KafkaProducerService;
import com.emify.user.User;
import com.emify.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.emify.booking.AppointmentRepository.ACTIVE_STATUSES;
import static com.emify.booking.AppointmentRepository.ATTENDED;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    /** Servicios que se pueden juntar en una misma visita. */
    private static final int MAX_SERVICES_PER_VISIT = 8;

    private final KafkaProducerService kafkaProducerService;
    private final AppointmentRepository appointmentRepository;
    private final BarbershopLocationRepository locationRepository;
    private final BarbershopStaffRepository staffRepository;
    private final ServiceRepository serviceRepository;
    private final AvailabilityRepository availabilityRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ServiceImageRepository serviceImageRepository;
    private final AppointmentImageRepository appointmentImageRepository;
    private final AppointmentProductRepository appointmentProductRepository;
    private final BusinessClock clock;

    // -------------------------------------------------------
    // GET LOCATION INFO
    // -------------------------------------------------------
    public ApiResponse<?> getLocationInfo(Long locationId) {
        BarbershopLocation location = findActiveLocation(locationId);
        if (location == null) return ApiResponse.fail("Sucursal no encontrada");

        List<BarberService> services = serviceRepository.findByLocationIdAndIsActiveTrue(locationId);
        List<BarbershopStaff> staff = staffRepository.findByLocationIdAndIsActiveTrueOrderByIdAsc(locationId);
        List<Availability> availability = availabilityRepository
                .findByLocationIdAndStaffIdIsNullAndIsActiveTrue(locationId);

        // Conteo de servicios atendidos por staff
        List<Map<String, Object>> staffList = staff.stream().map(s -> {
            long count = appointmentRepository.countByLocationAndStaff(locationId, s.getId(), ATTENDED);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("role", s.getRole());
            map.put("is_active", s.isActive());
            map.put("services_count", count);
            map.put("is_top", false); // se actualiza abajo
            if (s.getUser() != null) {
                map.put("user", Map.of(
                        "id", s.getUser().getId(),
                        "name", s.getUser().getName(),
                        "avatar_url", s.getUser().getAvatarUrl() != null ? s.getUser().getAvatarUrl() : ""
                ));
            }
            return map;
        }).toList();

        // Marcar top staff
        staffList.stream()
                .max(Comparator.comparingLong(m -> (Long) m.get("services_count")))
                .ifPresent(top -> {
                    if ((Long) top.get("services_count") > 0) {
                        top.put("is_top", true);
                    }
                });

        // Fotos del trabajo por servicio (máx. 3, las sube el dueño en Catálogo)
        Map<Long, List<String>> imagesByService = new HashMap<>();
        if (!services.isEmpty()) {
            serviceImageRepository.findByServiceIdInOrderBySortOrderAscIdAsc(
                            services.stream().map(BarberService::getId).toList())
                    .forEach(img -> imagesByService
                            .computeIfAbsent(img.getService().getId(), k -> new ArrayList<>())
                            .add(img.getImageUrl()));
        }

        // Conteo de bookings por servicio
        List<Map<String, Object>> serviceList = services.stream().map(s -> {
            long count = appointmentRepository.countByLocationAndService(locationId, s.getId(), ATTENDED);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("location_id", s.getLocation().getId());
            map.put("name", s.getName());
            map.put("description", s.getDescription() != null ? s.getDescription() : "");
            map.put("price", s.getPrice());
            map.put("duration", s.getDuration());
            map.put("is_active", s.isActive());
            map.put("bookings_count", count);
            map.put("is_top", false); // se actualiza abajo
            map.put("images", imagesByService.getOrDefault(s.getId(), List.of()));
            return map;
        }).toList();

        // Productos activos que el cliente puede apartar para su visita
        List<Map<String, Object>> productList = productRepository.findByLocationIdOrderByCreatedAtDesc(locationId).stream()
                .filter(Product::isActive)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("description", p.getDescription() != null ? p.getDescription() : "");
                    m.put("price", p.getPrice());
                    m.put("image_url", p.getImageUrl());
                    return m;
                })
                .toList();

        // Marcar top service
        serviceList.stream()
                .max(Comparator.comparingLong(m -> (Long) m.get("bookings_count")))
                .ifPresent(top -> {
                    if ((Long) top.get("bookings_count") > 0) {
                        top.put("is_top", true);
                    }
                });

        // Availability
        List<Map<String, Object>> availabilityList = availability.stream()
                .sorted(Comparator.comparing(Availability::getDayOfWeek))
                .map(a -> {
                    // LinkedHashMap: break_start/break_end pueden ser null (día sin hora de comida)
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("day_of_week", a.getDayOfWeek());
                    m.put("start_time", a.getStartTime().format(HH_MM));
                    m.put("end_time", a.getEndTime().format(HH_MM));
                    m.put("break_start", a.getBreakStart() != null ? a.getBreakStart().format(HH_MM) : null);
                    m.put("break_end", a.getBreakEnd() != null ? a.getBreakEnd().format(HH_MM) : null);
                    m.put("is_active", a.isActive());
                    return m;
                }).toList();

        // Location map
        Map<String, Object> locationMap = new LinkedHashMap<>();
        locationMap.put("id", location.getId());
        locationMap.put("name", location.getName());
        locationMap.put("address", location.getAddress());
        locationMap.put("city", location.getCity());
        locationMap.put("state", location.getState());
        locationMap.put("phone", location.getPhone() != null ? location.getPhone() : "");
        locationMap.put("phone_code", location.getPhoneCode());
        locationMap.put("serves_physical", location.isServesPhysical());
        locationMap.put("serves_home", location.isServesHome());
        locationMap.put("serves_online", location.isServesOnline());
        locationMap.put("buffer_minutes", bufferOf(location));
        locationMap.put("services", serviceList);
        locationMap.put("staff", staffList);
        locationMap.put("products", productList);
        if (location.getBarbershop() != null) {
            locationMap.put("barbershop", Map.of(
                    "id", location.getBarbershop().getId(),
                    "name", location.getBarbershop().getName()
            ));
        }

        return ApiResponse.ok("Get success", Map.of(
                "location", locationMap,
                "availability", availabilityList
        ));
    }

    // -------------------------------------------------------
    // GET AVAILABLE SLOTS
    // -------------------------------------------------------
    public ApiResponse<?> getAvailableSlots(Long locationId, String date, Long staffId, int duration) {
        LocalDate localDate = clock.parseDate(date);
        if (duration <= 0) duration = 30;

        // Sin barbero ("Cualquiera") y con personal: la unión de los horarios libres de cada barbero
        // activo. Al crear la cita se asigna a uno que esté libre (ver pickFreeStaff).
        if (staffId == null) {
            List<BarbershopStaff> team = staffRepository.findByLocationIdAndIsActiveTrueOrderByIdAsc(locationId);
            if (!team.isEmpty()) {
                TreeSet<String> union = new TreeSet<>(); // "HH:mm" ordena igual que la hora
                for (BarbershopStaff member : team) {
                    union.addAll(computeSlots(locationId, localDate, member.getId(), duration));
                }
                return ApiResponse.ok("slots", Map.of("slots", new ArrayList<>(union)));
            }
        }
        return ApiResponse.ok("slots", Map.of("slots", computeSlots(locationId, localDate, staffId, duration)));
    }

    private List<String> computeSlots(Long locationId, LocalDate localDate, Long staffId, int duration) {
        final int slotMinutes = duration;
        int dayOfWeek = localDate.getDayOfWeek().getValue() % 7; // 0=Domingo, 6=Sábado

        Availability availability = availabilityRepository
                .findByLocationIdAndDayOfWeekAndStaffIdIsNullAndIsActiveTrue(locationId, (short) dayOfWeek)
                .orElse(null);

        if (availability == null) {
            return List.of();
        }

        // Tiempo entre citas configurado por la sucursal (10 min por default)
        BarbershopLocation slotLocation = findActiveLocation(locationId);
        final int buffer = bufferOf(slotLocation);

        // Generar slots: cada uno dura el servicio y el siguiente empieza después del descanso.
        // Si un slot cae sobre la hora de comida, se brinca al final de la comida.
        List<String> slots = new ArrayList<>();
        LocalTime end = availability.getEndTime();
        LocalTime current = availability.getStartTime();
        LocalTime breakStart = availability.getBreakStart();
        LocalTime breakEnd = availability.getBreakEnd();

        while (!current.plusMinutes(slotMinutes).isAfter(end)
                && current.plusMinutes(slotMinutes).isAfter(current)) { // evita dar la vuelta a medianoche
            LocalTime slotEnd = current.plusMinutes(slotMinutes);
            if (breakStart != null && breakEnd != null
                    && current.isBefore(breakEnd) && slotEnd.isAfter(breakStart)) {
                current = breakEnd;
                continue;
            }
            slots.add(current.format(HH_MM));
            LocalTime next = slotEnd.plusMinutes(buffer);
            if (!next.isAfter(current)) break; // dio la vuelta a medianoche
            current = next;
        }

        // Citas reservadas ese día
        LocalDateTime from = localDate.atStartOfDay();
        LocalDateTime to = from.plusDays(1);
        // Con barbero: su agenda en TODAS las sucursales donde atiende (es la misma persona)
        Long staffUserId = staffId != null
                ? staffRepository.findById(staffId).map(s -> s.getUser().getId()).orElse(null)
                : null;
        List<Appointment> bookedAppointments = staffUserId != null
                ? appointmentRepository.findByStaffUserBetween(staffUserId, from, to, ACTIVE_STATUSES)
                : staffId != null
                    ? appointmentRepository.findByLocationAndStaffBetween(locationId, staffId, from, to, ACTIVE_STATUSES)
                    : appointmentRepository.findByLocationBetween(locationId, from, to, ACTIVE_STATUSES);

        // Filtrar slots bloqueados: entre una cita y otra debe quedar el tiempo de descanso
        List<String> availableSlots = slots.stream().filter(slot -> {
            LocalDateTime slotStart = localDate.atTime(LocalTime.parse(slot));
            LocalDateTime slotEnd = slotStart.plusMinutes(slotMinutes);

            for (Appointment appt : bookedAppointments) {
                LocalDateTime apptStart = appt.getStartTime().minusMinutes(buffer);
                LocalDateTime apptEnd = appt.getEndTime().plusMinutes(buffer);
                if (slotStart.isBefore(apptEnd) && slotEnd.isAfter(apptStart)) {
                    return false;
                }
            }
            return true;
        }).toList();

        return availableSlots;
    }

    // -------------------------------------------------------
    // CREATE APPOINTMENT
    // -------------------------------------------------------

    /** Foto de referencia del cliente y el servicio de la visita al que corresponde (null = el primero). */
    public record ReferenceImage(Long serviceId, String url) {}

    /** Producto que el cliente aparta para su visita. */
    public record ProductLine(Long productId, Integer quantity) {}

    /** Un solo servicio (firma anterior, se conserva por compatibilidad). */
    @Transactional
    public ApiResponse<?> createAppointment(
            Long locationId, Long serviceId, Long staffId,
            String startTimeStr, String name, String phone,
            String notes, String referenceImageUrl) {
        List<ReferenceImage> refs = referenceImageUrl != null
                ? List.of(new ReferenceImage(serviceId, referenceImageUrl))
                : List.of();
        return createAppointment(locationId, serviceId != null ? List.of(serviceId) : List.of(), staffId,
                startTimeStr, name, phone, notes, refs, List.of());
    }

    /**
     * Crea la visita: una cita por servicio, encadenadas sin descanso entre ellas (es la misma visita)
     * y con el mismo group_token cuando son varias. Así ventas, top servicios y top personal cuentan
     * cada servicio sin cambios. Sin barbero elegido ("Cualquiera") y con personal en la sucursal, se
     * asigna al barbero libre con menos citas ese día. Productos y fotos de referencia van aparte (V8).
     */
    @Transactional
    public ApiResponse<?> createAppointment(
            Long locationId, List<Long> serviceIds, Long staffId,
            String startTimeStr, String name, String phone, String notes,
            List<ReferenceImage> referenceImages, List<ProductLine> productLines) {

        if (name == null || name.isBlank()) return ApiResponse.fail("Tu nombre es requerido");
        if (phone == null || phone.isBlank()) return ApiResponse.fail("Tu teléfono es requerido");
        name = name.trim();
        phone = phone.trim();

        BarbershopLocation location = findActiveLocation(locationId);
        if (location == null) return ApiResponse.fail("Sucursal no encontrada");

        // Servicios sin repetir, en el orden en que los eligió el cliente
        List<Long> ids = serviceIds == null ? List.of()
                : serviceIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return ApiResponse.fail("Elige al menos un servicio");
        if (ids.size() > MAX_SERVICES_PER_VISIT) {
            return ApiResponse.fail("Puedes elegir hasta " + MAX_SERVICES_PER_VISIT + " servicios por cita");
        }
        List<BarberService> services = new ArrayList<>();
        for (Long id : ids) {
            BarberService service = serviceRepository.findByIdAndLocationId(id, locationId)
                    .filter(BarberService::isActive)
                    .orElse(null);
            if (service == null) return ApiResponse.fail("Servicio no encontrado");
            services.add(service);
        }
        int totalMinutes = services.stream().mapToInt(BarberService::getDuration).sum();

        // Productos: de la sucursal y activos; las cantidades del mismo producto se suman
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        if (productLines != null) {
            for (ProductLine line : productLines) {
                if (line == null || line.productId() == null) continue;
                int qty = line.quantity() == null ? 1 : line.quantity();
                if (qty <= 0) continue;
                quantities.merge(line.productId(), qty, Integer::sum);
            }
        }
        List<AppointmentProduct> productRows = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            Product product = productRepository.findByIdAndLocationId(entry.getKey(), locationId)
                    .filter(Product::isActive)
                    .orElse(null);
            if (product == null) return ApiResponse.fail("Uno de los productos ya no está disponible");
            productRows.add(AppointmentProduct.builder()
                    .product(product)
                    .productName(product.getName())
                    .unitPrice(product.getPrice())
                    .quantity(Math.min(entry.getValue(), AppointmentProduct.MAX_QUANTITY))
                    .build());
        }

        // Hora local del negocio (si llega con "Z"/offset se convierte; antes se guardaba corrida)
        LocalDateTime startTime = clock.parseClientDateTime(startTimeStr);
        LocalDateTime endTime = startTime.plusMinutes(totalMinutes);

        String scheduleError = validateSchedule(locationId, startTime, endTime);
        if (scheduleError != null) return ApiResponse.fail(scheduleError);

        // Empalme con la ventana ampliada por el tiempo entre citas de la sucursal
        int buffer = bufferOf(location);
        LocalDateTime checkStart = startTime.minusMinutes(buffer);
        LocalDateTime checkEnd = endTime.plusMinutes(buffer);

        BarbershopStaff staff = null;
        boolean autoAssigned = false;
        if (staffId != null) {
            staff = staffRepository.findById(staffId)
                    .filter(BarbershopStaff::isActive)
                    .filter(s -> s.getLocation().getId().equals(locationId))
                    .orElse(null);
            if (staff == null) return ApiResponse.fail("Barbero no encontrado en esta sucursal");
            // Su agenda en cualquier sucursal (es la misma persona)
            if (appointmentRepository.existsOverlapForStaffUser(staff.getUser().getId(), checkStart, checkEnd, ACTIVE_STATUSES)) {
                return ApiResponse.fail("El barbero ya tiene una cita en ese horario");
            }
        } else {
            List<BarbershopStaff> team = staffRepository.findByLocationIdAndIsActiveTrueOrderByIdAsc(locationId);
            if (!team.isEmpty()) {
                staff = pickFreeStaff(team, startTime.toLocalDate(), checkStart, checkEnd);
                if (staff == null) return ApiResponse.fail("Ese horario ya no está disponible, elige otro");
                autoAssigned = true;
            } else if (appointmentRepository.existsOverlapForLocation(locationId, checkStart, checkEnd, ACTIVE_STATUSES)) {
                return ApiResponse.fail("Ese horario ya está ocupado, elige otro");
            }
        }

        // Buscar o crear usuario guest por teléfono
        String guestEmail = phone + "@guest.emify.com";
        final String guestPhone = phone;
        User user = userRepository.findFirstByPhoneOrderByIdAsc(guestPhone)
                .orElseGet(() -> userRepository.findByEmail(guestEmail).orElse(null));

        if (user == null) {
            user = User.builder()
                    .phone(guestPhone)
                    .name(name)
                    .email(guestEmail)
                    .password("$2a$10$guest_placeholder_password")
                    .role(User.Role.client)
                    .isActive(true)
                    .build();
            user = userRepository.save(user);
            userRepository.flush(); // ← fuerza que el INSERT se ejecute antes del appointment
        }

        // Una cita por servicio, una tras otra
        String groupToken = services.size() > 1 ? newToken() : null;
        String cleanNotes = notes == null || notes.isBlank() ? null : notes.trim();
        List<Appointment> created = new ArrayList<>();
        LocalDateTime cursor = startTime;
        for (BarberService service : services) {
            LocalDateTime serviceEnd = cursor.plusMinutes(service.getDuration());
            Appointment appointment = Appointment.builder()
                    .user(user)
                    .staff(staff)
                    .service(service)
                    .location(location)
                    .startTime(cursor)
                    .endTime(serviceEnd)
                    .status(AppointmentStatus.pending)
                    .notes(cleanNotes)
                    .token(newToken())
                    .groupToken(groupToken)
                    .build();
            appointmentRepository.save(appointment);
            created.add(appointment);
            cursor = serviceEnd;
        }
        Appointment first = created.get(0);

        // Fotos de referencia (máx. 3 por visita) en la cita de su servicio. La primera de cada cita
        // también queda en reference_image_url, que es la que ya muestra el detalle del calendario.
        if (referenceImages != null) {
            referenceImages.stream()
                    .filter(ref -> ref != null && ref.url() != null && !ref.url().isBlank())
                    .limit(AppointmentImage.MAX_PER_VISIT)
                    .forEach(ref -> {
                        Appointment target = created.stream()
                                .filter(a -> ref.serviceId() != null && a.getService().getId().equals(ref.serviceId()))
                                .findFirst()
                                .orElse(first);
                        appointmentImageRepository.save(AppointmentImage.builder()
                                .appointment(target)
                                .imageUrl(ref.url())
                                .build());
                        if (target.getReferenceImageUrl() == null) {
                            target.setReferenceImageUrl(ref.url());
                            appointmentRepository.save(target);
                        }
                    });
        }

        // Productos apartados: van en la primera cita de la visita
        for (AppointmentProduct row : productRows) {
            row.setAppointment(first);
            appointmentProductRepository.save(row);
        }

        for (Appointment appointment : created) {
            publishEvent(KafkaProducerService.TOPIC_APPOINTMENT_CREATED, "appointment.created", appointment, "pending");
        }

        // TODO: WhatsApp notifications (UltraMsg) — se activan en producción
        log.info("📅 Cita creada: {} - {} - {} (token {})", name, startTime,
                services.stream().map(BarberService::getName).collect(Collectors.joining(" + ")), first.getToken());

        BigDecimal servicesTotal = services.stream().map(BarberService::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal productsTotal = productRows.stream().map(AppointmentProduct::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> apptMap = new LinkedHashMap<>();
        apptMap.put("id", first.getId());
        apptMap.put("token", first.getToken());
        apptMap.put("group_token", groupToken);
        apptMap.put("status", first.getStatus().name());
        apptMap.put("start_time", startTime.toString());
        apptMap.put("end_time", endTime.toString());
        apptMap.put("duration", totalMinutes);
        apptMap.put("staff_id", staff != null ? staff.getId() : null);
        apptMap.put("staff_name", staff != null && staff.getUser() != null ? staff.getUser().getName() : null);
        apptMap.put("staff_auto_assigned", autoAssigned);
        apptMap.put("services", services.stream().map(this::serviceLine).toList());
        apptMap.put("products", productRows.stream().map(this::productLine).toList());
        apptMap.put("reference_images_count", (int) Math.min(AppointmentImage.MAX_PER_VISIT,
                referenceImages == null ? 0 : referenceImages.stream().filter(r -> r != null && r.url() != null).count()));
        apptMap.put("services_total", servicesTotal);
        apptMap.put("products_total", productsTotal);
        apptMap.put("total", servicesTotal.add(productsTotal));

        return ApiResponse.ok("Cita creada exitosamente", Map.of("appointment", apptMap));
    }

    /** "Cualquiera": el barbero libre en ese horario con menos citas ese día (empate: el primero del equipo). */
    private BarbershopStaff pickFreeStaff(List<BarbershopStaff> team, LocalDate day,
                                          LocalDateTime checkStart, LocalDateTime checkEnd) {
        BarbershopStaff best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (BarbershopStaff member : team) {
            if (member.getUser() == null) continue;
            Long userId = member.getUser().getId();
            if (appointmentRepository.existsOverlapForStaffUser(userId, checkStart, checkEnd, ACTIVE_STATUSES)) continue;
            int load = appointmentRepository.findByStaffUserBetween(
                    userId, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), ACTIVE_STATUSES).size();
            if (load < bestLoad) {
                best = member;
                bestLoad = load;
            }
        }
        return best;
    }

    // -------------------------------------------------------
    // GET APPOINTMENT BY TOKEN (con todos los servicios de la visita)
    // -------------------------------------------------------
    public ApiResponse<?> getAppointmentByToken(String token) {
        Appointment a = appointmentRepository.findByToken(token).orElse(null);
        if (a == null) return ApiResponse.fail("Cita no encontrada");

        List<Appointment> group = groupOf(a);
        Appointment first = group.get(0);
        Appointment last = group.get(group.size() - 1);
        List<Long> groupIds = group.stream().map(Appointment::getId).toList();

        BigDecimal servicesTotal = group.stream().map(x -> x.getService().getPrice()).reduce(BigDecimal.ZERO, BigDecimal::add);
        int minutes = group.stream().mapToInt(x -> x.getService().getDuration()).sum();
        List<AppointmentProduct> products = appointmentProductRepository.findByAppointmentIdInOrderByIdAsc(groupIds);
        BigDecimal productsTotal = products.stream().map(AppointmentProduct::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> apptMap = new LinkedHashMap<>();
        apptMap.put("id",         first.getId());
        apptMap.put("token",      first.getToken());
        apptMap.put("group_token", first.getGroupToken());
        apptMap.put("client",     first.getUser() != null ? first.getUser().getName() : "");
        apptMap.put("service",    group.stream().map(x -> x.getService().getName()).collect(Collectors.joining(" + ")));
        apptMap.put("service_id", first.getService().getId());
        apptMap.put("duration",   minutes);
        apptMap.put("price",      servicesTotal);
        apptMap.put("services",   group.stream().map(x -> serviceLine(x.getService())).toList());
        apptMap.put("products",   products.stream().map(this::productLine).toList());
        apptMap.put("products_total", productsTotal);
        apptMap.put("total",      servicesTotal.add(productsTotal));
        apptMap.put("staff",      first.getStaff() != null && first.getStaff().getUser() != null ? first.getStaff().getUser().getName() : "");
        apptMap.put("staff_id",   first.getStaff() != null ? first.getStaff().getId() : "");
        apptMap.put("location_id",first.getLocation() != null ? first.getLocation().getId() : "");
        apptMap.put("location",   first.getLocation() != null ? first.getLocation().getName() : "");
        apptMap.put("address",    first.getLocation() != null && first.getLocation().getAddress() != null ? first.getLocation().getAddress() : "");
        apptMap.put("barbershop", first.getLocation() != null && first.getLocation().getBarbershop() != null ? first.getLocation().getBarbershop().getName() : "");
        apptMap.put("start_time", first.getStartTime().toString());
        apptMap.put("end_time",   last.getEndTime().toString());
        apptMap.put("status",     first.getStatus().name());
        apptMap.put("notes",      first.getNotes() != null ? first.getNotes() : "");
        apptMap.put("reference_images", appointmentImageRepository.findByAppointmentIdInOrderByIdAsc(groupIds).stream()
                .map(AppointmentImage::getImageUrl).toList());

        return ApiResponse.ok("Get success", Map.of("appointment", apptMap));
    }

    // -------------------------------------------------------
    // CANCEL APPOINTMENT (toda la visita)
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> cancelAppointment(String token) {
        Appointment appointment = appointmentRepository.findByToken(token).orElse(null);
        if (appointment == null) return ApiResponse.fail("Cita no encontrada");

        if (appointment.getStatus() == AppointmentStatus.cancelled) {
            return ApiResponse.fail("La cita ya está cancelada");
        }
        if (appointment.getStatus() == AppointmentStatus.completed || appointment.getStatus() == AppointmentStatus.no_show) {
            return ApiResponse.fail("Esta cita ya se cerró");
        }

        List<Appointment> group = groupOf(appointment);
        if (group.get(0).getStartTime().isBefore(clock.now())) {
            return ApiResponse.fail("No puedes cancelar una cita pasada");
        }

        for (Appointment a : group) {
            if (!ACTIVE_STATUSES.contains(a.getStatus())) continue;
            a.setStatus(AppointmentStatus.cancelled);
            appointmentRepository.save(a);
            publishEvent(KafkaProducerService.TOPIC_APPOINTMENT_CANCELLED, "appointment.cancelled", a, "cancelled");
        }

        log.info("❌ Cita cancelada: token={} ({} servicio/s)", token, group.size());

        return ApiResponse.ok("Cita cancelada exitosamente", null);
    }

    // -------------------------------------------------------
    // RESCHEDULE APPOINTMENT (mueve toda la visita)
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> rescheduleAppointment(String token, String newStartTimeStr) {
        Appointment appointment = appointmentRepository.findByToken(token).orElse(null);
        if (appointment == null) return ApiResponse.fail("Cita no encontrada");

        if (appointment.getStatus() == AppointmentStatus.cancelled) {
            return ApiResponse.fail("La cita está cancelada");
        }
        if (appointment.getStatus() == AppointmentStatus.completed || appointment.getStatus() == AppointmentStatus.no_show) {
            return ApiResponse.fail("Esta cita ya se cerró");
        }
        if (newStartTimeStr == null || newStartTimeStr.isBlank()) {
            return ApiResponse.fail("Elige el nuevo horario");
        }

        List<Appointment> group = groupOf(appointment).stream()
                .filter(a -> ACTIVE_STATUSES.contains(a.getStatus()))
                .toList();
        Appointment first = group.get(0);
        Appointment last = group.get(group.size() - 1);

        if (first.getStartTime().isBefore(clock.now())) {
            return ApiResponse.fail("No puedes modificar una cita pasada");
        }

        LocalDateTime newStart = clock.parseClientDateTime(newStartTimeStr);
        long blockMinutes = Duration.between(first.getStartTime(), last.getEndTime()).toMinutes();
        LocalDateTime newEnd = newStart.plusMinutes(blockMinutes);
        Duration shift = Duration.between(first.getStartTime(), newStart);
        Long locationId = first.getLocation().getId();

        String scheduleError = validateSchedule(locationId, newStart, newEnd);
        if (scheduleError != null) return ApiResponse.fail(scheduleError);

        int buffer = bufferOf(first.getLocation());
        LocalDateTime checkStart = newStart.minusMinutes(buffer);
        LocalDateTime checkEnd = newEnd.plusMinutes(buffer);
        String groupToken = first.getGroupToken();
        boolean overlap;
        if (first.getStaff() != null) {
            Long staffUserId = first.getStaff().getUser().getId();
            overlap = groupToken == null
                    ? appointmentRepository.existsOverlapForStaffUserExcluding(staffUserId, checkStart, checkEnd, ACTIVE_STATUSES, first.getId())
                    : appointmentRepository.existsOverlapForStaffUserExcludingGroup(staffUserId, checkStart, checkEnd, ACTIVE_STATUSES, groupToken);
        } else {
            overlap = groupToken == null
                    ? appointmentRepository.existsOverlapForLocationExcluding(locationId, checkStart, checkEnd, ACTIVE_STATUSES, first.getId())
                    : appointmentRepository.existsOverlapForLocationExcludingGroup(locationId, checkStart, checkEnd, ACTIVE_STATUSES, groupToken);
        }
        if (overlap) return ApiResponse.fail("Ese horario ya no está disponible");

        for (Appointment a : group) {
            a.setStartTime(a.getStartTime().plus(shift));
            a.setEndTime(a.getEndTime().plus(shift));
            appointmentRepository.save(a);
        }

        return ApiResponse.ok("Cita reagendada exitosamente", Map.of(
                "appointment", Map.of(
                        "id", first.getId(),
                        "token", first.getToken(),
                        "start_time", newStart.toString(),
                        "end_time", newEnd.toString(),
                        "status", first.getStatus().name()
                )
        ));
    }

    // -------------------------------------------------------
    // MIS CITAS (cliente logeado) — /perfil → Historial de citas
    // Una visita con varios servicios sale una sola vez ("Corte + Barba").
    // -------------------------------------------------------
    public ApiResponse<?> getMyAppointments(User user) {
        Set<String> seenGroups = new HashSet<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Appointment a : appointmentRepository.findByUserIdOrderByStartTimeDesc(user.getId())) {
            Appointment shown = a;
            String serviceName = a.getService().getName();
            if (a.getGroupToken() != null) {
                if (!seenGroups.add(a.getGroupToken())) continue;
                List<Appointment> group = appointmentRepository.findByGroupTokenOrderByStartTimeAsc(a.getGroupToken());
                if (!group.isEmpty()) {
                    shown = group.get(0);
                    serviceName = group.stream().map(x -> x.getService().getName()).collect(Collectors.joining(" + "));
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", shown.getId());
            map.put("token", shown.getToken());
            map.put("business_name", shown.getLocation().getBarbershop().getName());
            map.put("location_name", shown.getLocation().getName());
            map.put("service_name", serviceName);
            map.put("date", shown.getStartTime().format(DISPLAY_DATE_TIME));
            map.put("start_time", shown.getStartTime().toString());
            map.put("status", shown.getStatus().name());
            list.add(map);
        }
        return ApiResponse.ok("Mis citas", list);
    }

    /** Las citas de la misma visita (o solo esta si es de un servicio), en orden. */
    private List<Appointment> groupOf(Appointment a) {
        if (a.getGroupToken() == null) return List.of(a);
        List<Appointment> group = appointmentRepository.findByGroupTokenOrderByStartTimeAsc(a.getGroupToken());
        return group.isEmpty() ? List.of(a) : group;
    }

    private Map<String, Object> serviceLine(BarberService s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("price", s.getPrice());
        m.put("duration", s.getDuration());
        return m;
    }

    private Map<String, Object> productLine(AppointmentProduct p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product_id", p.getProduct() != null ? p.getProduct().getId() : null);
        m.put("name", p.getProductName());
        m.put("unit_price", p.getUnitPrice());
        m.put("quantity", p.getQuantity());
        m.put("total", p.getTotal());
        return m;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void publishEvent(String topic, String type, Appointment a, String status) {
        kafkaProducerService.publishAppointmentEvent(topic, AppointmentEvent.builder()
                .eventType(type)
                .appointmentId(a.getId())
                .locationId(a.getLocation().getId())
                .serviceId(a.getService().getId())
                .serviceName(a.getService().getName())
                .userId(a.getUser().getId())
                .clientName(a.getUser().getName())
                .staffId(a.getStaff() != null ? a.getStaff().getId() : null)
                .startTime(a.getStartTime())
                .endTime(a.getEndTime())
                .status(status)
                .eventTimestamp(LocalDateTime.now())
                .build());
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------
    private BarbershopLocation findActiveLocation(Long locationId) {
        return locationRepository.findById(locationId)
                .filter(l -> l.getDeletedAt() == null)
                .orElse(null);
    }

    /** @return mensaje de error, o null si el horario es válido. */
    private String validateSchedule(Long locationId, LocalDateTime start, LocalDateTime end) {
        if (start.isBefore(clock.now())) {
            return "Ese horario ya pasó, elige otro";
        }
        int dayOfWeek = start.getDayOfWeek().getValue() % 7;
        Availability availability = availabilityRepository
                .findByLocationIdAndDayOfWeekAndStaffIdIsNullAndIsActiveTrue(locationId, (short) dayOfWeek)
                .orElse(null);
        if (availability == null
                || !end.toLocalDate().equals(start.toLocalDate())
                || start.toLocalTime().isBefore(availability.getStartTime())
                || end.toLocalTime().isAfter(availability.getEndTime())) {
            return "La sucursal no atiende en ese horario";
        }
        if (availability.getBreakStart() != null && availability.getBreakEnd() != null
                && start.toLocalTime().isBefore(availability.getBreakEnd())
                && end.toLocalTime().isAfter(availability.getBreakStart())) {
            return "Ese horario cae en la hora de comida, elige otro";
        }
        return null;
    }

    /** Minutos de descanso entre citas de la sucursal (10 si no está configurado). */
    private int bufferOf(BarbershopLocation location) {
        if (location == null || location.getBufferMinutes() == null) return 10;
        return Math.max(0, location.getBufferMinutes());
    }
}
