package com.emify.admin;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * Bitácora de auditoría del super admin (V11). Solo se agrega: en la BD un trigger rechaza UPDATE y DELETE,
 * y aquí la entidad es @Immutable (Hibernate nunca manda un UPDATE).
 * before_data / after_data: JSON del registro antes y después de un cambio; en consultas, los filtros usados.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Immutable
@Entity
@Table(name = "admin_audit_log")
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "admin_email")
    private String adminEmail;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "target_type", length = 30)
    private String targetType;

    @Column(name = "target_id", length = 60)
    private String targetId;

    @Column(length = 500)
    private String reason;

    @Column(name = "before_data", columnDefinition = "TEXT")
    private String beforeData;

    @Column(name = "after_data", columnDefinition = "TEXT")
    private String afterData;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "session_id")
    private Long sessionId;

    /** UTC. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
