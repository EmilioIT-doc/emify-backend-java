package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByLocationIdOrderByCreatedAtDesc(Long locationId);
    Optional<Product> findByIdAndLocationId(Long id, Long locationId);
}
