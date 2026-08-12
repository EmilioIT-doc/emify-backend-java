package com.emify.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface RestaurantLocationRepository extends JpaRepository<RestaurantLocation, Long> {

    List<RestaurantLocation> findByRestaurantIdAndDeletedAtIsNull(Long restaurantId);

    boolean existsByRestaurantIdAndDeletedAtIsNull(Long restaurantId);

    Optional<RestaurantLocation> findByIdAndDeletedAtIsNull(Long id);

    @Query("SELECT l FROM RestaurantLocation l WHERE l.id = :id AND l.restaurant.owner.id = :ownerId AND l.deletedAt IS NULL")
    Optional<RestaurantLocation> findByIdAndOwnerId(Long id, Long ownerId);

    @Modifying
    @Transactional
    @Query("UPDATE RestaurantLocation l SET l.isDefault = false WHERE l.restaurant.id = :restaurantId")
    void clearDefaultByRestaurantId(Long restaurantId);
}
