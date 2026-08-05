package com.emify.booking;

import com.emify.auth.dto.ApiResponse;
import com.emify.barbershop.*;
import com.emify.user.User;
import com.emify.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.emify.kafka.AppointmentEvent;
import com.emify.kafka.KafkaProducerService;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {
    private final KafkaProducerService kafkaProducerService;
    private final AppointmentRepository appointmentRepository;
    private final BarbershopLocationRepository locationRepository;
    private final BarbershopStaffRepository staffRepository;
    private final ServiceRepository serviceRepository;
    private final AvailabilityRepository availabilityRepository;
    private final UserRepository userRepository;

    // -------------------------------------------------------
    // GET LOCATION INFO
    // -------------------------------------------------------
    public ApiResponse<?> getLocationInfo(Long locationId) {
        BarbershopLocation location = locationRepository.findById(locationId).orElse(null);
        if (location == null) return ApiResponse.fail("Sucursal no encontrada");

        List<BarberService> services = serviceRepository.findByLocationIdAndIsActiveTrue(locationId);
        List<BarbershopStaff> staff = staffRepository.findByLocationIdAndIsActiveTrue(locationId);
        List<Availability> availability = availabilityRepository
                .findByLocationIdAndStaffIdIsNullAndIsActiveTrue(locationId);

        LocalDateTime now = LocalDateTime.now();

        // Conteo de servicios por staff
        List<Map<String, Object>> staffList = staff.stream().map(s -> {
            long count = appointmentRepository.countByLocationAndStaffBefore(locationId, s.getId(), now);
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
            long count = appointmentRepository.countByLocationAndServiceBefore(locationId, s.getId(), now);
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
        List<Map<String, Object>> availabilityList = availability.stream().map(a -> Map.of(
                "id", (Object) a.getId(),
                "day_of_week", a.getDayOfWeek(),
                "start_time", a.getStartTime().toString(),
                "end_time", a.getEndTime().toString(),
                "is_active", a.isActive()
        )).toList();

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
        // Obtener día de la semana
        java.time.LocalDate localDate = java.time.LocalDate.parse(date);
        int dayOfWeek = localDate.getDayOfWeek().getValue() % 7; // 0=Domingo, 6=Sábado

        Availability availability = availabilityRepository
                .findByLocationIdAndDayOfWeekAndStaffIdIsNullAndIsActiveTrue(locationId, (short) dayOfWeek)
                .orElse(null);

        if (availability == null) {
            return ApiResponse.ok("No hay disponibilidad", Map.of("slots", List.of()));
        }

        // Generar slots
        List<String> slots = new ArrayList<>();
        LocalTime start = availability.getStartTime();
        LocalTime end = availability.getEndTime();
        LocalTime current = start;

        while (current.plusMinutes(duration).compareTo(end) <= 0) {
            slots.add(current.format(DateTimeFormatter.ofPattern("HH:mm")));
            current = current.plusMinutes(duration);
        }

        // Obtener citas reservadas
        LocalDateTime dateTime = localDate.atStartOfDay();
        List<Appointment> bookedAppointments;

        if (staffId != null) {
            bookedAppointments = appointmentRepository.findByLocationAndStaffAndDate(locationId, staffId, dateTime);
        } else {
            bookedAppointments = appointmentRepository.findByLocationAndDate(locationId, dateTime);
        }

        // Filtrar slots bloqueados (con buffer de 10 minutos)
        int buffer = 10;
        List<String> availableSlots = slots.stream().filter(slot -> {
            LocalDateTime slotStart = localDate.atTime(LocalTime.parse(slot));
            LocalDateTime slotEnd = slotStart.plusMinutes(duration);

            for (Appointment appt : bookedAppointments) {
                LocalDateTime apptStart = appt.getStartTime();
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

        BarberService service = serviceRepository.findById(serviceId).orElse(null);
        if (service == null) return ApiResponse.fail("Servicio no encontrado");

        BarbershopLocation location = locationRepository.findById(locationId).orElse(null);
        if (location == null) return ApiResponse.fail("Sucursal no encontrada");

        LocalDateTime startTime = LocalDateTime.parse(startTimeStr, DateTimeFormatter.ISO_DATE_TIME);
        LocalDateTime endTime = startTime.plusMinutes(service.getDuration());

        // Verificar overlap
        if (staffId != null) {
            boolean overlap = appointmentRepository.existsOverlapForStaff(staffId, startTime, endTime);
            if (overlap) {
                return ApiResponse.fail("El barbero ya tiene una cita en ese horario");
            }
        }

        // Buscar o crear usuario guest por teléfono
        String guestEmail = phone + "@guest.emify.com";
        User user = userRepository.findFirstByPhoneOrderByIdAsc(phone)
                .orElseGet(() -> userRepository.findByEmail(guestEmail).orElse(null));

        if (user == null) {
            user = User.builder()
                    .phone(phone)
                    .name(name)
                    .email(guestEmail)
                    .password("$2a$10$guest_placeholder_password")
                    .role(User.Role.client)
                    .isActive(true)
                    .build();
            user = userRepository.save(user);
            userRepository.flush(); // ← fuerza que el INSERT se ejecute antes del appointment
        }

        BarbershopStaff staff = staffId != null ? staffRepository.findById(staffId).orElse(null) : null;

        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

        Appointment appointment = Appointment.builder()
                .user(user)
                .staff(staff)
                .service(service)
                .location(location)
                .startTime(startTime)
                .endTime(endTime)
                .status(Appointment.AppointmentStatus.pending)
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

        try {
            kafkaProducerService.publishAppointmentEvent(
                    KafkaProducerService.TOPIC_APPOINTMENT_CREATED, event);
        } catch (Exception e) {
            log.warn("⚠️ Kafka no disponible: {}", e.getMessage());
        }

        // TODO: WhatsApp notifications (UltraMsg) — se activan en producción
        log.info("📅 Cita creada: {} - {} - {}", name, startTime, service.getName());

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

        if (appointment.getStatus() == Appointment.AppointmentStatus.cancelled) {
            return ApiResponse.fail("La cita ya está cancelada");
        }

        if (appointment.getStartTime().isBefore(LocalDateTime.now())) {
            return ApiResponse.fail("No puedes cancelar una cita pasada");
        }

        appointment.setStatus(Appointment.AppointmentStatus.cancelled);
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

        try {
            kafkaProducerService.publishAppointmentEvent(
                    KafkaProducerService.TOPIC_APPOINTMENT_CANCELLED, cancelEvent);
        } catch (Exception e) {
            log.warn("⚠️ Kafka no disponible: {}", e.getMessage());
        }

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

        if (appointment.getStatus() == Appointment.AppointmentStatus.cancelled) {
            return ApiResponse.fail("La cita está cancelada");
        }

        if (appointment.getStartTime().isBefore(LocalDateTime.now())) {
            return ApiResponse.fail("No puedes modificar una cita pasada");
        }

        LocalDateTime newStart = LocalDateTime.parse(newStartTimeStr, DateTimeFormatter.ISO_DATE_TIME);
        LocalDateTime newEnd = newStart.plusMinutes(appointment.getService().getDuration());

        if (appointment.getStaff() != null) {
            boolean overlap = appointmentRepository.existsOverlapForStaffExcluding(
                    appointment.getStaff().getId(), newStart, newEnd, appointment.getId());
            if (overlap) return ApiResponse.fail("Ese horario ya no está disponible");
        }

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
}