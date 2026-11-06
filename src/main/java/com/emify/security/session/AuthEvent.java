package com.emify.security.session;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Registro de actividad de la cuenta: inicios de sesión (correctos y fallidos), cierres, revocaciones,
 * cambios de contraseña. Lo ve el usuario en su perfil ("Actividad reciente"). Fechas en UTC.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "auth_events")
public class AuthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "session_id")
    private Long sessionId;

    /** SIGNUP, LOGIN, LOGIN_FAILED, LOGIN_BLOCKED, LOGOUT, SESSION_REVOKED, SESSIONS_REVOKED, PASSWORD_CHANGED, JOIN_INVITE */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(length = 255)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
