package com.emify.barbershop;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "barbershop_locations")
public class BarbershopLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "barbershop_id", nullable = false)
    private Barbershop barbershop;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    private String phone;

    @Column(name = "phone_code")
    @Builder.Default
    private String phoneCode = "+52";

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "is_default")
    @Builder.Default
    private boolean isDefault = false;

    @Column(name = "invite_code", unique = true)
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "service_type")
    @Builder.Default
    private ServiceTypeEnum serviceType = ServiceTypeEnum.physical;

    @Column(name = "serves_physical")
    @Builder.Default
    private boolean servesPhysical = false;

    @Column(name = "serves_home")
    @Builder.Default
    private boolean servesHome = false;

    @Column(name = "serves_online")
    @Builder.Default
    private boolean servesOnline = false;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    public enum ServiceTypeEnum {
        physical, home, online
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

