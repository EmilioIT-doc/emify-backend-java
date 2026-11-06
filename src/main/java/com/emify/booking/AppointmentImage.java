package com.emify.booking;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Foto de referencia que sube el cliente al reservar, ligada a la cita del servicio al que corresponde. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "appointment_images")
public class AppointmentImage {

    /** Máximo de fotos de referencia por visita (sumando todos sus servicios). */
    public static final int MAX_PER_VISIT = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
