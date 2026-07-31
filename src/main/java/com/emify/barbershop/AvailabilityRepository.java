package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface AvailabilityRepository extends JpaRepository<Availability, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM Availability a WHERE a.locationId = :locationId AND a.staffId IS NULL")
    void deleteByLocationIdAndStaffIdIsNull(Long locationId);

    List<Availability> findByLocationIdAndStaffIdIsNullAndIsActiveTrue(Long locationId);

    Optional<Availability> findByLocationIdAndDayOfWeekAndStaffIdIsNullAndIsActiveTrue(Long locationId, Short dayOfWeek);
}