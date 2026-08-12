package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BarbershopRepository extends JpaRepository<Barbershop, Long> {
    Optional<Barbershop> findByOwnerId(Long ownerId);
}