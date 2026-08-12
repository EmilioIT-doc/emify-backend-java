package com.emify.restaurant;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "restaurant_order_items")
public class RestaurantOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private RestaurantOrder order;

    // Sin relación obligatoria al MenuItem — si se borra el platillo, el pedido
    // histórico conserva el nombre/precio que tenía al momento de ordenarse.
    @Column(name = "menu_item_id")
    private Long menuItemId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "unit_price", nullable = false, precision = 8, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private Integer quantity;
}
