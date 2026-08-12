package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BarbershopStaffRepository extends JpaRepository<BarbershopStaff, Long> {
    List<BarbershopStaff> findByLocationIdAndIsActiveTrue(Long locationId);
    Optional<BarbershopStaff> findByUserIdAndLocationId(Long userId, Long locationId);
    Optional<BarbershopStaff> findFirstByUserIdAndIsActiveTrue(Long userId);
}