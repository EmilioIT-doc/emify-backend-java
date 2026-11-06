package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BarbershopStaffRepository extends JpaRepository<BarbershopStaff, Long> {
    List<BarbershopStaff> findByLocationIdAndIsActiveTrue(Long locationId);
    List<BarbershopStaff> findByLocationIdAndIsActiveTrueOrderByIdAsc(Long locationId);
    /** Personal al que se le pueden agendar citas (V11: una recepcionista que no atiende tiene bookable = false). */
    List<BarbershopStaff> findByLocationIdAndIsActiveTrueAndBookableTrueOrderByIdAsc(Long locationId);
    Optional<BarbershopStaff> findByUserIdAndLocationId(Long userId, Long locationId);
    Optional<BarbershopStaff> findFirstByUserIdAndIsActiveTrue(Long userId);
    List<BarbershopStaff> findByUserIdAndIsActiveTrueOrderByIdAsc(Long userId);

    // Sucursales (de cualquiera de SUS negocios) donde el dueño está activo como barbero.
    // Regla: el dueño atiende como máximo en una (ver TeamService).
    @Query("SELECT s FROM BarbershopStaff s WHERE s.user.id = :ownerId AND s.isActive = true " +
           "AND s.location.barbershop.owner.id = :ownerId AND s.location.deletedAt IS NULL ORDER BY s.id")
    List<BarbershopStaff> findActiveOwnerMemberships(@Param("ownerId") Long ownerId);
}