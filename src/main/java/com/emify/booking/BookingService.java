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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.emify.booking.AppointmentRepository.ACTIVE_STATUSES;
import static com.emify.booking.AppointmentRepository.ATTENDED;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final KafkaProducerService kafkaProducerService;
    private final AppointmentRepository appointmentRepository;
    private final BarbershopLocationRepository locationRepository;
    private final BarbershopStaffRepository staffRepository;
    private final ServiceRepository serviceRepository;
    private final AvailabilityRepository availabilityRepository;
    private final UserRepository userRepository;
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
            return map;
        }).toList();

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
        final int slotMinutes = duration;
        int dayOfWeek = localDate.getDayOfWeek().getValue() % 7; // 0=Domingo, 6=Sábado

        Availability availability = availabilityRepository
                .findByLocationIdAndDayOfWeekAndStaffIdIsNullAndIsActiveTrue(locationId, (short) dayOfWeek)
                .orElse(null);

        if (availability == null) {
            return ApiResponse.ok("No hay disponibilidad", Map.of("slots", List.of()));
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

        return ApiResponse.ok("slots", Map.of("slots", availableSlots));
    }

    // -------------------------------------------------------
    // CREATE APPOINTMENT
    // -------------------------------------------------------
    @Transactional
    public ApiResponse<?> createAppointment(
            Long locationId, Long serviceId, Long staffId,
            String startTimeStr, String name, String phone,
            String notes, String referenceImageUrl) {

        if (name == null || name.isBlank()) return ApiResponse.fail("Tu nombre es requerido");
        if (phone == null || phone.isBlank()) return ApiResponse.fail("Tu teléfono es requerido");
        name = name.trim();
        phone = phone.trim();

        BarbershopLocation location = findActiveLocation(locationId);
        if (location == null) return ApiResponse.fail("Sucursal no encontrada");

        BarberService service = serviceRepository.findById(serviceId).orElse(null);
        if (service == null || !service.getLocation().getId().equals(locationId)) {
            return ApiResponse.fail("Servicio no encontrado");
        }

        BarbershopStaff staff = null;
        if (staffId != null) {
            staff = staffRepository.findById(staffId)
                    .filter(BarbershopStaff::isActive)
                    .filter(s -> s.getLocation().getId().equals(locationId))
                    .orElse(null);
            if (staff == null) return ApiResponse.fail("Barbero no encontrado en esta sucursal");
        }

        // Hora local del negocio (si llega con "Z"/offset se convierte; antes se guardaba corrida)
        LocalDateTime startTime = clock.parseClientDateTime(startTimeStr);
        LocalDateTime endTime = startTime.plusMinutes(service.getDuration());

        String scheduleError = validateSchedule(locationId, startTime, endTime);
        if (scheduleError != null) return ApiResponse.fail(scheduleError);

        // Verificar overlap (con barbero: su agenda en cualquier sucursal; sin barbero: la de la sucursal).
        // La ventana se amplía con el tiempo entre citas de la sucursal.
        int buffer = bufferOf(location);
        LocalDateTime checkStart = startTime.minusMinutes(buffer);
        LocalDateTime checkEnd = endTime.plusMinutes(buffer);
        if (staff != null) {
            if (appointmentRepository.existsOverlapForStaffUser(staff.getUser().getId(), checkStart, checkEnd, ACTIVE_STATUSES)) {
                return ApiResponse.fail("El barbero ya tiene una cita en ese horario");
            }
        } else if (appointmentRepository.existsOverlapForLocation(locationId, checkStart, checkEnd, ACTIVE_STATUSES)) {
            return ApiResponse.fail("Ese horario ya está ocupado, elige otro");
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

        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

        Appointment appointment = Appointment.builder()
                .user(user)
                .staff(staff)
                .service(service)
                .location(location)
                .startTime(startTime)
                .endTime(endTime)
                .status(AppointmentStatus.pending)
                .notes(notes)
                .referenceImageUrl(referenceImageUrl)
                .token(token)
                .build();

        appointmentRepository.save(appointment);

        AppointmentEvent event = AppointmentEvent.builder()
                .eventType("appointment.created")
                .appointmentId(appointment.getId())
                .locationId(location.getId())
                .serviceId(service.getId())
                .serviceName(service.getName())
                .userId(user.getId())
                .clientName(user.getName())
                .staffId(staffId)
                .startTime(startTime)
                .endTime(endTime)
                .status("pending")
                .eventTimestamp(LocalDateTime.now())
                .build();

        kafkaProducerService.publishAppointmentEvent(KafkaProducerService.TOPIC_APPOINTMENT_CREATED, event);

        // TODO: WhatsApp notifications (UltraMsg) — se activan en producción
        log.info("📅 Cita creada: {} - {} - {} (token {})", name, startTime, service.getName(), token);

        return ApiResponse.ok("Cita creada exitosamente", Map.of(
                "appointment", Map.of(
                        "id", appointment.getId(),
                        "token", appointment.getToken(),
                        "status", appointment.getStatus().name(),
                        "start_time", appointment.getStartTime().toString(),
                        "end_time", appointment.getEndTime().toString()
                )
        ));
    }

    // -------------------------------------------------------
    // GET APPOINTMENT BY TOKEN
    // -------------------------------------------------------
    public ApiResponse<?> getAppointmentByToken(String token) {
        Appointment a = appointmentRepository.findByToken(token).orElse(null);
        if (a == null) return ApiResponse.fail("Cita no encontrada");

        Map<String, Object> apptMap = new LinkedHashMap<>();
        apptMap.put("id",         a.getId());
        apptMap.put("token",      a.getToken());
        apptMap.put("client",     a.getUser() != null ? a.getUser().getName() : "");
        apptMap.put("service",    a.getService() != null ? a.getService().getName() : "");
        apptMap.put("service_id", a.getService() != null ? a.getService().getId() : "");
        apptMap.put("duration",   a.getService() != null ? a.getService().getDuration() : 30);
        apptMap.put("price",      a.getService() != null ? a.getService().getPrice() : 0);
        apptMap.put("staff",      a.getStaff() != null && a.getStaff().getUser() != null ? a.getStaff().getUser().getName() : "");
        apptMap.put("staff_id",   a.getStaff() != null ? a.getStaff().getId() : "");
        apptMap.put("location_id",a.getLocation() != null ? a.getLocation().getId() : "");
        apptMap.put("location",   a.getLocation() != null ? a.getLocation().getName() : "");
        apptMap.put("barbershop", a.getLocation() != null && a.getLocation().getBarbershop() != null ? a.getLocation().getBarbershop().getName() : "");
        apptMap.put("start_time", a.getStartTime().toString());
        apptMap.put("end_time",   a.getEndTime().toString());
        apptMap.put("status",     a.getStatus().name());
        apptMap.put("notes",      a.getNotes() != null ? a.getNotes() : "");

        return ApiResponse.ok("Get success", Map.of("appointment", apptMap));
    }

    // -------------------------------------------------------
    // CANCEL APPOINTMENT
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

        if (appointment.getStartTime().isBefore(clock.now())) {
            return ApiResponse.fail("No puedes cancelar una cita pasada");
        }

        appointment.setStatus(AppointmentStatus.cancelled);
        appointmentRepository.save(appointment);

        AppointmentEvent cancelEvent = AppointmentEvent.builder()
                .eventType("appointment.cancelled")
                .appointmentId(appointment.getId())
                .locationId(appointment.getLocation().getId())
                .serviceId(appointment.getService().getId())
                .serviceName(appointment.getService().getName())
                .userId(appointment.getUser().getId())
                .clientName(appointment.getUser().getName())
                .staffId(appointment.getStaff() != null ? appointment.getStaff().getId() : null)
                .startTime(appointment.getStartTime())
                .endTime(appointment.getEndTime())
                .status("cancelled")
                .eventTimestamp(LocalDateTime.now())
                .build();

        kafkaProducerService.publishAppointmentEvent(KafkaProducerService.TOPIC_APPOINTMENT_CANCELLED, cancelEvent);

        log.info("❌ Cita cancelada: token={}", token);

        return ApiResponse.ok("Cita cancelada exitosamente", null);
    }

    // -------------------------------------------------------
    // RESCHEDULE APPOINTMENT
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

        if (appointment.getStartTime().isBefore(clock.now())) {
            return ApiResponse.fail("No puedes modificar una cita pasada");
        }

        LocalDateTime newStart = clock.parseClientDateTime(newStartTimeStr);
        LocalDateTime newEnd = newStart.plusMinutes(appointment.getService().getDuration());
        Long locationId = appointment.getLocation().getId();

        String scheduleError = validateSchedule(locationId, newStart, newEnd);
        if (scheduleError != null) return ApiResponse.fail(scheduleError);

        int buffer = bufferOf(appointment.getLocation());
        LocalDateTime checkStart = newStart.minusMinutes(buffer);
        LocalDateTime checkEnd = newEnd.plusMinutes(buffer);
        boolean overlap = appointment.getStaff() != null
                ? appointmentRepository.existsOverlapForStaffUserExcluding(
                        appointment.getStaff().getUser().getId(), checkStart, checkEnd, ACTIVE_STATUSES, appointment.getId())
                : appointmentRepository.existsOverlapForLocationExcluding(
                        locationId, checkStart, checkEnd, ACTIVE_STATUSES, appointment.getId());
        if (overlap) return ApiResponse.fail("Ese horario ya no está disponible");

        appointment.setStartTime(newStart);
        appointment.setEndTime(newEnd);
        appointmentRepository.save(appointment);

        return ApiResponse.ok("Cita reagendada exitosamente", Map.of(
                "appointment", Map.of(
                        "id", appointment.getId(),
                        "token", appointment.getToken(),
                        "start_time", appointment.getStartTime().toString(),
                        "end_time", appointment.getEndTime().toString(),
                        "status", appointment.getStatus().name()
                )
        ));
    }

    // -------------------------------------------------------
    // MIS CITAS (cliente logeado) — /perfil → Historial de citas
    // -------------------------------------------------------
    public ApiResponse<?> getMyAppointments(User user) {
        List<Map<String, Object>> list = appointmentRepository.findByUserIdOrderByStartTimeDesc(user.getId()).stream()
                .map(a -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", a.getId());
                    map.put("token", a.getToken());
                    map.put("business_name", a.getLocation().getBarbershop().getName());
                    map.put("location_name", a.getLocation().getName());
                    map.put("service_name", a.getService().getName());
                    map.put("date", a.getStartTime().format(DISPLAY_DATE_TIME));
                    map.put("start_time", a.getStartTime().toString());
                    map.put("status", a.getStatus().name());
                    return map;
                })
                .toList();
        return ApiResponse.ok("Mis citas", list);
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
