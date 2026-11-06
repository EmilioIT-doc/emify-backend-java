package com.emify.admin;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Pago de suscripción de un negocio (V11), registrado por el super admin (Clip después).
 * No se edita ni se borra: un error se corrige anulándolo (status = void) con motivo.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "subscription_payments")
public class SubscriptionPayment {

    public static final String PAID = "paid";
    public static final String VOID = "void";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** barbershop | restaurant */
    @Column(name = "business_type", nullable = false, length = 20)
    private String businessType;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "MXN";

    /** clip | transferencia | efectivo | tarjeta | otro */
    @Column(nullable = false, length = 20)
    private String method;

    @Column(length = 120)
    private String reference;

    /** Hora local del negocio (America/Mexico_City). */
    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = PAID;

    private String notes;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "void_reason")
    private String voidReason;

    /** UTC. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
