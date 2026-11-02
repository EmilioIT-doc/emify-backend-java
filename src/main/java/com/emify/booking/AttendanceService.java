package com.emify.booking;

import com.emify.auth.dto.ApiResponse;
import com.emify.booking.Appointment.AppointmentStatus;
import com.emify.booking.Appointment.PaymentMethod;
import com.emify.common.ApiException;
import com.emify.common.BusinessClock;
import com.emify.kafka.AppointmentEvent;
import com.emify.kafka.KafkaProducerService;
import com.emify.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final AppointmentRepository appointmentRepository;
    private final KafkaProducerService kafkaProducerService;
    private final BusinessClock clock;

    // GET /api/attendance/pending
    public ApiResponse<?> getPending(User user) {
        List<Map<String, Object>> pending = appointmentRepository
                .findPendingAttendance(user.getId(), clock.now(), ACTIVE_STATUSES)
                .stream()
                .map(this::toMap)
                .toList();
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

    /** El dueño de la barbería o el barbero asignado a la cita. */
    private boolean canMark(User user, Appointment a) {
        boolean isOwner = a.getLocation().getBarbershop().getOwner().getId().equals(user.getId());
        boolean isAssignedStaff = a.getStaff() != null && a.getStaff().getUser().getId().equals(user.getId());
        return isOwner || isAssignedStaff;
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
}
