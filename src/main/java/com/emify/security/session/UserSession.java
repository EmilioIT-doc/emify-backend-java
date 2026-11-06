package com.emify.security.session;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Una sesión abierta en un dispositivo (navegador). La cookie solo lleva un token aleatorio; aquí se guarda
 * su hash SHA-256, nunca el token. Revocar = poner revoked_at: la siguiente petición con esa cookie ya no entra.
 * Todas las fechas van en UTC (no son horas del negocio).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Hash del token anterior a la última rotación (se acepta un par de minutos). */
    @Column(name = "previous_token_hash", length = 64)
    private String previousTokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "rotated_at", nullable = false)
    private LocalDateTime rotatedAt;

    /** Vencimiento absoluto (aunque se siga usando). */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** Por qué terminó: LOGOUT, REVOKED_BY_USER, REVOKED_OTHERS, NEW_LOGIN, PASSWORD_CHANGED, LIMIT, EXPIRED, IDLE, REPLACED, USER_DISABLED. */
    @Column(name = "revoked_reason", length = 40)
    private String revokedReason;

    /** Hasta cuándo vale la reautenticación (contraseña) del super admin para acciones sensibles (V11). */
    @Column(name = "elevated_until")
    private LocalDateTime elevatedUntil;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    public boolean isActive() {
        return revokedAt == null;
    }
}
