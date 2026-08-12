package com.emify.restaurant;

import jakarta.persistence.*;
import lombok.*;
import com.emify.user.User;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "restaurants")
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String website;

    @Column(name = "cuisine_type")
    private String cuisineType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "order_mode_table")
    @Builder.Default
    private boolean orderModeTable = false;

    @Column(name = "order_mode_delivery")
    @Builder.Default
    private boolean orderModeDelivery = false;

    @Column(name = "order_mode_pickup")
    @Builder.Default
    private boolean orderModePickup = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "theme_color", nullable = false)
    @Builder.Default
    private String themeColor = "#ea580c";

    @Column(name = "subscription_status", nullable = false)
    @Builder.Default
    private String subscriptionStatus = "trial";

    @Column(name = "subscription_starts_at")
    private LocalDateTime subscriptionStartsAt;

    @Column(name = "subscription_ends_at")
    private LocalDateTime subscriptionEndsAt;

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
