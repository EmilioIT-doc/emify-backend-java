package com.emify.security.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    Optional<UserSession> findFirstByPreviousTokenHash(String previousTokenHash);

    List<UserSession> findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(Long userId);

    Optional<UserSession> findByIdAndUserId(Long id, Long userId);

    /** Última actividad (solo cada touchInterval, para no escribir en cada petición). */
    @Modifying
    @Transactional
    @Query("update UserSession s set s.lastSeenAt = :now where s.id = :id")
    int touch(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Cambia el token solo si nadie lo cambió antes (dos peticiones en paralelo pueden intentarlo a la vez:
     * gana una y la otra sigue con el token anterior, que se acepta durante rotationGrace).
     */
    @Modifying
    @Transactional
    @Query("update UserSession s set s.previousTokenHash = s.tokenHash, s.tokenHash = :newHash, s.rotatedAt = :now, "
            + "s.lastSeenAt = :now where s.id = :id and s.tokenHash = :oldHash and s.revokedAt is null")
    int rotate(@Param("id") Long id, @Param("oldHash") String oldHash, @Param("newHash") String newHash,
               @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("update UserSession s set s.revokedAt = :now, s.revokedReason = :reason where s.id = :id and s.revokedAt is null")
    int revoke(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("reason") String reason);

    /** Cierra todas las sesiones activas de la cuenta menos una. */
    @Modifying
    @Transactional
    @Query("update UserSession s set s.revokedAt = :now, s.revokedReason = :reason "
            + "where s.userId = :userId and s.revokedAt is null and s.id <> :exceptId")
    int revokeAllExcept(@Param("userId") Long userId, @Param("exceptId") Long exceptId,
                        @Param("now") LocalDateTime now, @Param("reason") String reason);

    /** Cierra todas las sesiones activas de la cuenta. */
    @Modifying
    @Transactional
    @Query("update UserSession s set s.revokedAt = :now, s.revokedReason = :reason "
            + "where s.userId = :userId and s.revokedAt is null")
    int revokeAll(@Param("userId") Long userId, @Param("now") LocalDateTime now, @Param("reason") String reason);

    /** Limpieza: sesiones que terminaron o vencieron hace tiempo. */
    @Modifying
    @Transactional
    @Query("delete from UserSession s where (s.revokedAt is not null and s.revokedAt < :before) or s.expiresAt < :before")
    int deleteEndedBefore(@Param("before") LocalDateTime before);
}
