package com.emify.booking;

import com.emify.barbershop.BarbershopLocation;
import com.emify.barbershop.BarbershopStaff;
import com.emify.barbershop.BarberService;
import com.emify.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "appointments")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    private BarbershopStaff staff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private BarberService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private BarbershopLocation location;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    @Builder.Default
    private AppointmentStatus status = AppointmentStatus.pending;

    // Cómo pagó el cliente (solo informativo). Se llena al marcar "Sí llegó"; null en las demás.
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "reference_image_url")
    private String referenceImageUrl;

    @Column(unique = true)
    private String token;

    // Visita con varios servicios: citas encadenadas (una por servicio) con el mismo group_token (V8).
    // NULL = cita de un solo servicio.
    @Column(name = "group_token", length = 32)
    private String groupToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // completed = el cliente sí llegó (cuenta como venta); no_show = no llegó (no cuenta)
    public enum AppointmentStatus {
        pending, confirmed, cancelled, completed, no_show
    }

    // Informativo: Emify no procesa pagos, solo registra cómo pagó el cliente en el negocio
    public enum PaymentMethod {
        efectivo, tarjeta, transferencia;

        public static PaymentMethod parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return PaymentMethod.valueOf(value.trim().toLowerCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}