package com.emify.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
    List<MenuCategory> findByLocationIdOrderBySortOrderAscIdAsc(Long locationId);
    Optional<MenuCategory> findByIdAndLocationId(Long id, Long locationId);
}
