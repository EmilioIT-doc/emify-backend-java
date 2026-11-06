package com.emify.booking;

import com.emify.booking.Appointment.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Nota: las consultas por día usan rangos [desde, hasta) en vez de CAST(... AS date):
 * funcionan igual en PostgreSQL y SQLite y pueden usar el índice de start_time.
 * Los estados se pasan como parámetro (enum) en vez de literales de texto.
 */
@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    // Citas por venir de un barbero en una sucursal (para avisar antes de que el dueño salga de ella)
    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.staff.id = :staffId AND a.startTime >= :since AND a.status IN :statuses")
    long countUpcomingForStaff(@Param("staffId") Long staffId,
                               @Param("since") LocalDateTime since,
                               @Param("statuses") Collection<AppointmentStatus> statuses);

    /** Estados que ocupan un horario. */
    List<AppointmentStatus> ACTIVE_STATUSES = List.of(AppointmentStatus.pending, AppointmentStatus.confirmed);

    /** Lo que se muestra en el calendario (todo menos canceladas). */
    List<AppointmentStatus> CALENDAR_STATUSES = List.of(
            AppointmentStatus.pending, AppointmentStatus.confirmed,
            AppointmentStatus.completed, AppointmentStatus.no_show);

    /** Solo las citas a las que el cliente sí llegó cuentan como venta / visita. */
    List<AppointmentStatus> ATTENDED = List.of(AppointmentStatus.completed);

    Optional<Appointment> findByToken(String token);

    /** Citas de una misma visita (varios servicios encadenados), en orden. */
    List<Appointment> findByGroupTokenOrderByStartTimeAsc(String groupToken);

    // Reagendar una visita de varios servicios: el empalme ignora las citas del mismo grupo
    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.staff.user.id = :userId
        AND (a.groupToken IS NULL OR a.groupToken <> :groupToken)
        AND a.status IN :statuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForStaffUserExcludingGroup(@Param("userId") Long userId,
                                                    @Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime,
                                                    @Param("statuses") Collection<AppointmentStatus> statuses,
                                                    @Param("groupToken") String groupToken);

    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.location.id = :locationId
        AND (a.groupToken IS NULL OR a.groupToken <> :groupToken)
        AND a.status IN :statuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForLocationExcludingGroup(@Param("locationId") Long locationId,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime,
                                                   @Param("statuses") Collection<AppointmentStatus> statuses,
                                                   @Param("groupToken") String groupToken);

    // Verificar overlap de staff
    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.staff.id = :staffId
        AND a.status IN :statuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForStaff(@Param("staffId") Long staffId,
                                  @Param("startTime") LocalDateTime startTime,
                                  @Param("endTime") LocalDateTime endTime,
                                  @Param("statuses") Collection<AppointmentStatus> statuses);

    // Verificar overlap de staff excluyendo una cita (reagendar)
    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.staff.id = :staffId
        AND a.id <> :excludeId
        AND a.status IN :statuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForStaffExcluding(@Param("staffId") Long staffId,
                                           @Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime,
                                           @Param("statuses") Collection<AppointmentStatus> statuses,
                                           @Param("excludeId") Long excludeId);

    // Overlap por PERSONA: el mismo usuario puede ser barbero en varias sucursales (p. ej. el
    // dueño cuando no registró personal). Si ya tiene cita a las 10:00 en una, no se le puede
    // agendar a las 10:00 en otra.
    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.staff.user.id = :userId
        AND a.status IN :statuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForStaffUser(@Param("userId") Long userId,
                                      @Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime,
                                      @Param("statuses") Collection<AppointmentStatus> statuses);

    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.staff.user.id = :userId
        AND a.id <> :excludeId
        AND a.status IN :statuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForStaffUserExcluding(@Param("userId") Long userId,
                                               @Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime,
                                               @Param("statuses") Collection<AppointmentStatus> statuses,
                                               @Param("excludeId") Long excludeId);

    // Citas de una persona (en cualquier sucursal) en un rango [rangeStart, rangeEnd)
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.staff.user.id = :userId
        AND a.startTime >= :rangeStart
        AND a.startTime < :rangeEnd
        AND a.status IN :statuses
        ORDER BY a.startTime ASC
    """)
    List<Appointment> findByStaffUserBetween(@Param("userId") Long userId,
                                             @Param("rangeStart") LocalDateTime rangeStart,
                                             @Param("rangeEnd") LocalDateTime rangeEnd,
                                             @Param("statuses") Collection<AppointmentStatus> statuses);

    // Sucursal sin barbero elegido: cualquier cita de la sucursal ocupa el horario
    // (misma regla que usa el cálculo de slots cuando no se manda staff_id).
    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.status IN :statuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForLocation(@Param("locationId") Long locationId,
                                     @Param("startTime") LocalDateTime startTime,
                                     @Param("endTime") LocalDateTime endTime,
                                     @Param("statuses") Collection<AppointmentStatus> statuses);

    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.id <> :excludeId
        AND a.status IN :statuses
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForLocationExcluding(@Param("locationId") Long locationId,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("statuses") Collection<AppointmentStatus> statuses,
                                              @Param("excludeId") Long excludeId);

    // Citas de una sucursal en un rango [rangeStart, rangeEnd)
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.startTime >= :rangeStart
        AND a.startTime < :rangeEnd
        AND a.status IN :statuses
        ORDER BY a.startTime ASC
    """)
    List<Appointment> findByLocationBetween(@Param("locationId") Long locationId,
                                            @Param("rangeStart") LocalDateTime rangeStart,
                                            @Param("rangeEnd") LocalDateTime rangeEnd,
                                            @Param("statuses") Collection<AppointmentStatus> statuses);

    // Citas de un barbero en una sucursal en un rango [rangeStart, rangeEnd)
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.staff.id = :staffId
        AND a.startTime >= :rangeStart
        AND a.startTime < :rangeEnd
        AND a.status IN :statuses
        ORDER BY a.startTime ASC
    """)
    List<Appointment> findByLocationAndStaffBetween(@Param("locationId") Long locationId,
                                                    @Param("staffId") Long staffId,
                                                    @Param("rangeStart") LocalDateTime rangeStart,
                                                    @Param("rangeEnd") LocalDateTime rangeEnd,
                                                    @Param("statuses") Collection<AppointmentStatus> statuses);

    // Todas las citas de una sucursal (pestaña Clientes)
    @Query("SELECT a FROM Appointment a WHERE a.location.id = :locationId ORDER BY a.startTime DESC")
    List<Appointment> findAllByLocation(@Param("locationId") Long locationId);

    // Historial del cliente logeado
    List<Appointment> findByUserIdOrderByStartTimeDesc(Long userId);

    boolean existsByServiceId(Long serviceId);

    // Citas atendidas por staff en location (antes contaba cualquier cita pasada, aunque no llegara)
    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.staff.id = :staffId
        AND a.status IN :statuses
    """)
    long countByLocationAndStaff(@Param("locationId") Long locationId,
                                 @Param("staffId") Long staffId,
                                 @Param("statuses") Collection<AppointmentStatus> statuses);

    // Citas atendidas por servicio en location
    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.service.id = :serviceId
        AND a.status IN :statuses
    """)
    long countByLocationAndService(@Param("locationId") Long locationId,
                                   @Param("serviceId") Long serviceId,
                                   @Param("statuses") Collection<AppointmentStatus> statuses);

    // Citas que ya terminaron y el barbero asignado no ha dicho si el cliente llegó.
    // Solo las ve ese barbero (el dueño solo cuando él es el barbero asignado).
    @Query("""
        SELECT a FROM Appointment a
        JOIN a.location l
        JOIN a.staff s
        WHERE a.status IN :statuses
        AND a.endTime <= :now
        AND l.deletedAt IS NULL
        AND s.user.id = :userId
        ORDER BY a.startTime ASC
    """)
    List<Appointment> findPendingAttendance(@Param("userId") Long userId,
                                            @Param("now") LocalDateTime now,
                                            @Param("statuses") Collection<AppointmentStatus> statuses);
}
