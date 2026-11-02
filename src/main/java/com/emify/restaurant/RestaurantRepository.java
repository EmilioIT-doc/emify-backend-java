package com.emify.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    // Un dueño puede tener varios negocios del mismo rubro (marcas distintas), cada uno con sus sucursales
    List<Restaurant> findByOwnerIdOrderByIdAsc(Long ownerId);
}
