package com.emify.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByCategoryIdAndIsActiveTrueOrderByIdAsc(Long categoryId);

    List<MenuItem> findByCategoryIdOrderByIdAsc(Long categoryId);

    @Query("SELECT i FROM MenuItem i WHERE i.id = :id AND i.category.location.restaurant.owner.id = :ownerId")
    Optional<MenuItem> findByIdAndOwnerId(Long id, Long ownerId);
}
