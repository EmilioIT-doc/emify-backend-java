package com.emify.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByToken(String token);

    // Verificar overlap de staff
    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.staff.id = :staffId
        AND a.status IN ('pending', 'confirmed')
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForStaff(Long staffId, LocalDateTime startTime, LocalDateTime endTime);

    // Verificar overlap excluyendo una cita
    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a
        WHERE a.staff.id = :staffId
        AND a.id <> :excludeId
        AND a.status IN ('pending', 'confirmed')
        AND a.startTime < :endTime
        AND a.endTime > :startTime
    """)
    boolean existsOverlapForStaffExcluding(Long staffId, LocalDateTime startTime, LocalDateTime endTime, Long excludeId);

    // Citas por fecha y staff
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.staff.id = :staffId
        AND CAST(a.startTime AS date) = CAST(:date AS date)
        AND a.status IN ('pending', 'confirmed')
    """)
    List<Appointment> findByLocationAndStaffAndDate(Long locationId, Long staffId, LocalDateTime date);

    // Citas por fecha y location (sin staff específico)
    @Query("""
        SELECT a FROM Appointment a
        WHERE a.location.id = :locationId
        AND CAST(a.startTime AS date) = CAST(:date AS date)
        AND a.status IN ('pending', 'confirmed')
    """)
    List<Appointment> findByLocationAndDate(Long locationId, LocalDateTime date);

    // Conteo de citas pasadas por staff en location
    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.staff.id = :staffId
        AND a.startTime < :now
    """)
    long countByLocationAndStaffBefore(Long locationId, Long staffId, LocalDateTime now);

    // Conteo de citas pasadas por servicio en location
    @Query("""
        SELECT COUNT(a) FROM Appointment a
        WHERE a.location.id = :locationId
        AND a.service.id = :serviceId
        AND a.startTime < :now
    """)
    long countByLocationAndServiceBefore(Long locationId, Long serviceId, LocalDateTime now);
}