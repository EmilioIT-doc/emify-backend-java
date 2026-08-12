package com.emify.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RestaurantOrderItemRepository extends JpaRepository<RestaurantOrderItem, Long> {

    // Cada fila: [itemName, totalQty, totalRevenue] — agregado para "platillos más vendidos".
    @Query("""
            SELECT i.itemName, SUM(i.quantity), SUM(i.quantity * i.unitPrice)
            FROM RestaurantOrderItem i
            WHERE i.order.locationId = :locationId
              AND i.order.createdAt BETWEEN :start AND :end
            GROUP BY i.itemName
            ORDER BY SUM(i.quantity) DESC
            """)
    List<Object[]> findTopItems(Long locationId, LocalDateTime start, LocalDateTime end);
}
