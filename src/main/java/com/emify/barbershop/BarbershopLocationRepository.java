package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface BarbershopLocationRepository extends JpaRepository<BarbershopLocation, Long> {

    List<BarbershopLocation> findByBarbershopIdAndDeletedAtIsNull(Long barbershopId);

    boolean existsByBarbershopIdAndDeletedAtIsNull(Long barbershopId);

    @Query("SELECT l FROM BarbershopLocation l WHERE l.id = :id AND l.barbershop.owner.id = :ownerId AND l.deletedAt IS NULL")
    Optional<BarbershopLocation> findByIdAndOwnerId(Long id, Long ownerId);

    @Modifying
    @Transactional
    @Query("UPDATE BarbershopLocation l SET l.isDefault = false WHERE l.barbershop.id = :barbershopId")
    void clearDefaultByBarbershopId(Long barbershopId);
}