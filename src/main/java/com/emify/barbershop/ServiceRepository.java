package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceRepository extends JpaRepository<BarberService, Long> {
    List<BarberService> findByLocationIdOrderByCreatedAtDesc(Long locationId);
    Optional<BarberService> findByIdAndLocationId(Long id, Long locationId);
    List<BarberService> findByLocationIdAndIsActiveTrue(Long locationId);
}