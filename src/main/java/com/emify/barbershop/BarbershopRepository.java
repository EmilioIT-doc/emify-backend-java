package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BarbershopRepository extends JpaRepository<Barbershop, Long> {
    // Un dueño puede tener varios negocios del mismo rubro (marcas distintas), cada uno con sus sucursales
    List<Barbershop> findByOwnerIdOrderByIdAsc(Long ownerId);
}