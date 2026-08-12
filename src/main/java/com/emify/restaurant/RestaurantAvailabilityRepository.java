package com.emify.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RestaurantAvailabilityRepository extends JpaRepository<RestaurantAvailability, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM RestaurantAvailability a WHERE a.locationId = :locationId")
    void deleteByLocationId(Long locationId);

    List<RestaurantAvailability> findByLocationIdAndIsActiveTrue(Long locationId);
}
