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