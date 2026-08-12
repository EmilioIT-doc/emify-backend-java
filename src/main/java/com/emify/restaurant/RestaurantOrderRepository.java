package com.emify.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RestaurantOrderRepository extends JpaRepository<RestaurantOrder, Long> {

    List<RestaurantOrder> findByLocationIdAndCreatedAtAfterOrderByCreatedAtAsc(Long locationId, LocalDateTime after);

    List<RestaurantOrder> findTop20ByLocationIdOrderByCreatedAtDesc(Long locationId);

    List<RestaurantOrder> findByLocationIdOrderByCreatedAtDesc(Long locationId);
}
