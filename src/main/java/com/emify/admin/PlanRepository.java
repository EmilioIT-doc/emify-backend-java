package com.emify.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanRepository extends JpaRepository<Plan, Long> {
    List<Plan> findAllByOrderByActiveDescPriceAsc();
    boolean existsByCodeIgnoreCase(String code);
}
