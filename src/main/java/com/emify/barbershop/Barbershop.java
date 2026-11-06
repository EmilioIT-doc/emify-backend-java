package com.emify.barbershop;

import jakarta.persistence.*;
import lombok.*;
import com.emify.user.User;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "barbershops")
public class Barbershop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String website;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "subscription_status", nullable = false)
    @Builder.Default
    private String subscriptionStatus = "trial";

    @Column(name = "subscription_starts_at")
    private LocalDateTime subscriptionStartsAt;

    @Column(name = "subscription_ends_at")
    private LocalDateTime subscriptionEndsAt;

    @Column(name = "clip_customer_id")
    private String clipCustomerId;

    @Column(name = "last_payment_id")
    private String lastPaymentId;

    // Estilo de la página pública de reservas (V10). Ver BookingTheme.
    @Column(name = "booking_theme", nullable = false, length = 20)
    @Builder.Default
    private String bookingTheme = BookingTheme.DEFAULT;

    // Color principal dentro del estilo; null = el del estilo
    @Column(name = "booking_accent", length = 7)
    private String bookingAccent;

    /** Plan contratado (V11). Null = sin plan asignado todavía. */
    @Column(name = "plan_id")
    private Long planId;

    /** Suspendido por el super admin (V11): reservas pausadas y panel en solo lectura. */
    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "suspended_by")
    private Long suspendedBy;

    public boolean isSuspended() {
        return suspendedAt != null;
    }

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
}