package com.emify.booking;

import com.emify.auth.dto.ApiResponse;
import com.emify.barbershop.BarberService;
import com.emify.barbershop.BarbershopStaff;
import com.emify.barbershop.BarbershopStaffRepository;
import com.emify.barbershop.LocationAccessService;
import com.emify.barbershop.ServiceRepository;
import com.emify.booking.Appointment.AppointmentStatus;
import com.emify.booking.Appointment.PaymentMethod;
import com.emify.common.ApiException;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.emify.booking.AppointmentRepository.ACTIVE_STATUSES;

/**
 * Asistencia: cuando ya pasó la hora de una cita, el barbero asignado registra si el cliente
 * llegó (pop-up). El dueño también puede marcar/corregir desde el Calendario, pero el pop-up
 * solo le sale de las citas donde él es el barbero. Solo "completed" cuenta como venta.
 * Al marcar "Sí llegó" se pide también cómo pagó (efectivo / tarjeta / transferencia, informativo).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** Teléfono con el que se registran todos los clientes imprevistos (sin cita): así se cuentan aparte. */
    public static final String WALK_IN_PHONE = "0000000000";
    public static final String WALK_IN_NAME = "Cliente imprevisto";

    private final AppointmentRepository appointmentRepository;
    private final KafkaProducerService kafkaProducerService;
    private final BusinessClock clock;
    private final BarbershopStaffRepository staffRepository;
    private final ServiceRepository serviceRepository;
    private final UserRepository userRepository;
    private final LocationAccessService access;

    // GET /api/attendance/pending
    // Una visita con varios servicios (mismo group_token) sale una sola vez y hasta que termina el último.
    public ApiResponse<?> getPending(User user) {
        LocalDateTime now = clock.now();
        Set<String> seenGroups = new HashSet<>();
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Appointment a : appointmentRepository.findPendingAttendance(user.getId(), now, ACTIVE_STATUSES)) {
            if (a.getLocation().getBarbershop().isSuspended()) continue; // panel en solo lectura: no se pide
            if (a.getGroupToken() == null) {
                pending.add(toMap(a));
                continue;
            }
            if (!seenGroups.add(a.getGroupToken())) continue;
            List<Appointment> open = appointmentRepository.findByGroupTokenOrderByStartTimeAsc(a.getGroupToken()).stream()
                    .filter(x -> ACTIVE_STATUSES.contains(x.getStatus()))
                    .toList();
            if (open.isEmpty() || open.get(open.size() - 1).getEndTime().isAfter(now)) continue;
            pending.add(groupMap(open));
        }
        return ApiResponse.ok("Citas por confirmar asistencia", Map.of("appointments", pending));
    }

    // POST /api/attendance/{id}   body: {attended: true, payment_method: "efectivo"|"tarjeta"|"transferencia"}
    //                             body: {attended: false}
    @Transactional
    public ApiResponse<?> mark(User user, Long appointmentId, Boolean attended, String paymentMethodValue) {
        if (attended == null) throw ApiException.unprocessable("Indica si el cliente llegó o no");

        PaymentMethod paymentMethod = PaymentMethod.parse(paymentMethodValue);
        if (attended && paymentMethod == null) {
            throw ApiException.unprocessable("Indica cómo pagó el cliente: efectivo, tarjeta o transferencia");
        }

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .filter(a -> canMark(user, a))
                .orElseThrow(() -> ApiException.notFound("Cita no encontrada"));

        access.ensureWritable(appointment.getLocation().getBarbershop());
        if (appointment.getStatus() == AppointmentStatus.cancelled) {
            throw ApiException.unprocessable("La cita fue cancelada");
        }
        if (appointment.getStartTime().isAfter(clock.now())) {
            throw ApiException.unprocessable("La cita todavía no empieza");
        }

        AppointmentStatus newStatus = attended ? AppointmentStatus.completed : AppointmentStatus.no_show;
        appointment.setStatus(newStatus);
        appointment.setPaymentMethod(attended ? paymentMethod : null);
        appointmentRepository.save(appointment);

        // Visita con varios servicios: lo mismo para los demás servicios que ya empezaron
        if (appointment.getGroupToken() != null) {
            for (Appointment other : appointmentRepository.findByGroupTokenOrderByStartTimeAsc(appointment.getGroupToken())) {
                if (other.getId().equals(appointment.getId())) continue;
                if (other.getStatus() == AppointmentStatus.cancelled || other.getStartTime().isAfter(clock.now())) continue;
                other.setStatus(newStatus);
                other.setPaymentMethod(attended ? paymentMethod : null);
                appointmentRepository.save(other);
            }
        }

        if (attended) {
            kafkaProducerService.publishAppointmentEvent(KafkaProducerService.TOPIC_APPOINTMENT_COMPLETED,
                    AppointmentEvent.builder()
                            .eventType("appointment.completed")
                            .appointmentId(appointment.getId())
                            .locationId(appointment.getLocation().getId())
                            .serviceId(appointment.getService().getId())
                            .serviceName(appointment.getService().getName())
                            .userId(appointment.getUser().getId())
                            .clientName(appointment.getUser().getName())
                            .staffId(appointment.getStaff() != null ? appointment.getStaff().getId() : null)
                            .startTime(appointment.getStartTime())
                            .endTime(appointment.getEndTime())
                            .status("completed")
                            .eventTimestamp(LocalDateTime.now())
                            .build());
        }

        log.info("✔️ Asistencia registrada: cita {} → {} {} (por {})", appointmentId, newStatus,
                attended ? paymentMethod : "", user.getEmail());
        return ApiResponse.ok(attended ? "Asistencia registrada" : "Marcada como no llegó",
                Map.of("appointment", toMap(appointment)));
    }

    /**
     * POST /api/attendance/walk-in — cliente imprevisto: alguien que llegó sin cita y el barbero atendió.
     * Se guarda como cita ya atendida (cuenta como venta) del barbero que la registra, con el cliente
     * genérico de teléfono 0000000000 ("Cliente imprevisto"); el nombre opcional va en las notas.
     */
    @Transactional
    public ApiResponse<?> registerWalkIn(User user, Long locationId, Long serviceId, String startTimeValue,
                                         String paymentMethodValue, String clientName) {
        return registerWalkIn(user, locationId, serviceId, startTimeValue, paymentMethodValue, clientName, null);
    }

    @Transactional
    public ApiResponse<?> registerWalkIn(User user, Long locationId, Long serviceId, String startTimeValue,
                                         String paymentMethodValue, String clientName, Long staffId) {
        if (locationId == null) throw ApiException.unprocessable("Falta la sucursal");
        if (serviceId == null) throw ApiException.unprocessable("Elige el servicio que hiciste");

        PaymentMethod paymentMethod = PaymentMethod.parse(paymentMethodValue);
        if (paymentMethod == null) {
            throw ApiException.unprocessable("Indica cómo pagó el cliente: efectivo, tarjeta o transferencia");
        }

        BarbershopStaff staff;
        if (staffId != null) {
            // Dueño o recepción registran el imprevisto a nombre de un barbero de la sucursal
            access.requireManagerForWrite(user, locationId);
            staff = staffRepository.findById(staffId)
                    .filter(BarbershopStaff::isActive)
                    .filter(s -> s.getLocation().getId().equals(locationId))
                    .orElseThrow(() -> ApiException.notFound("Ese barbero no está en esta sucursal"));
        } else {
            staff = staffRepository.findByUserIdAndLocationId(user.getId(), locationId)
                    .filter(BarbershopStaff::isActive)
                    .orElseThrow(() -> ApiException.notFound("No atiendes en esta sucursal"));
        }
        access.ensureWritable(staff.getLocation().getBarbershop());

        BarberService service = serviceRepository.findByIdAndLocationId(serviceId, locationId)
                .filter(BarberService::isActive)
                .orElseThrow(() -> ApiException.notFound("Servicio no encontrado"));

        LocalDateTime now = clock.now();
        LocalDateTime start = (startTimeValue == null || startTimeValue.isBlank())
                ? now.withSecond(0).withNano(0)
                : clock.parseClientDateTime(startTimeValue);
        if (start.isAfter(now.plusMinutes(5))) {
            throw ApiException.unprocessable("Registra al cliente cuando ya llegó: la hora no puede ser después de ahora");
        }
        if (start.isBefore(now.minusDays(7))) {
            throw ApiException.unprocessable("Solo puedes registrar clientes imprevistos de los últimos 7 días");
        }
        LocalDateTime end = start.plusMinutes(service.getDuration());

        User client = userRepository.findFirstByPhoneOrderByIdAsc(WALK_IN_PHONE)
                .orElseGet(() -> userRepository.save(User.builder()
                        .phone(WALK_IN_PHONE)
                        .name(WALK_IN_NAME)
                        .email(WALK_IN_PHONE + "@guest.emify.com")
                        .password("$2a$10$guest_placeholder_password")
                        .role(User.Role.client)
                        .isActive(true)
                        .build()));

        String name = clientName != null ? clientName.trim() : "";
        Appointment appointment = Appointment.builder()
                .user(client)
                .staff(staff)
                .service(service)
                .location(staff.getLocation())
                .startTime(start)
                .endTime(end)
                .status(AppointmentStatus.completed)
                .paymentMethod(paymentMethod)
                .notes(name.isEmpty() ? WALK_IN_NAME : WALK_IN_NAME + ": " + name)
                .token(UUID.randomUUID().toString().replace("-", "").substring(0, 32))
                .build();
        appointmentRepository.save(appointment);

        kafkaProducerService.publishAppointmentEvent(KafkaProducerService.TOPIC_APPOINTMENT_COMPLETED,
                AppointmentEvent.builder()
                        .eventType("appointment.completed")
                        .appointmentId(appointment.getId())
                        .locationId(locationId)
                        .serviceId(service.getId())
                        .serviceName(service.getName())
                        .userId(client.getId())
                        .clientName(client.getName())
                        .staffId(staff.getId())
                        .startTime(start)
                        .endTime(end)
                        .status("completed")
                        .eventTimestamp(LocalDateTime.now())
                        .build());

        log.info("🚶 Cliente imprevisto: {} en sucursal {} ({}) por {}", service.getName(), locationId, paymentMethod, user.getEmail());
        return ApiResponse.ok("Cliente imprevisto registrado", Map.of("appointment", toMap(appointment)));
    }

    /** El dueño, la recepción de esa sucursal (V11) o el barbero asignado a la cita. */
    private boolean canMark(User user, Appointment a) {
        boolean isAssignedStaff = a.getStaff() != null && a.getStaff().getUser().getId().equals(user.getId());
        return isAssignedStaff || access.canManage(user, a.getLocation());
    }

    private Map<String, Object> toMap(Appointment a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("client", a.getUser().getName());
        map.put("phone", a.getUser().getPhone());
        map.put("service", a.getService().getName());
        map.put("price", a.getService().getPrice());
        map.put("staff_name", a.getStaff() != null ? a.getStaff().getUser().getName() : null);
        map.put("location_id", a.getLocation().getId());
        map.put("location_name", a.getLocation().getName());
        map.put("date", a.getStartTime().toLocalDate().toString());
        map.put("start_time", a.getStartTime().format(HH_MM));
        map.put("end_time", a.getEndTime().format(HH_MM));
        map.put("status", a.getStatus().name());
        map.put("payment_method", a.getPaymentMethod() != null ? a.getPaymentMethod().name() : null);
        return map;
    }

    /** Visita de varios servicios como una sola tarjeta ("Corte + Barba", total y horario completo). */
    private Map<String, Object> groupMap(List<Appointment> group) {
        Appointment first = group.get(0);
        Appointment last = group.get(group.size() - 1);
        Map<String, Object> map = toMap(last); // el id es el del último servicio; marcarlo marca toda la visita
        map.put("service", group.stream().map(x -> x.getService().getName()).collect(Collectors.joining(" + ")));
        map.put("price", group.stream().map(x -> x.getService().getPrice()).reduce(BigDecimal.ZERO, BigDecimal::add));
        map.put("start_time", first.getStartTime().format(HH_MM));
        map.put("group_token", first.getGroupToken());
        return map;
    }
}
