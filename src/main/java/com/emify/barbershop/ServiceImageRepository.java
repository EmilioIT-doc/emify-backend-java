package com.emify.barbershop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceImageRepository extends JpaRepository<ServiceImage, Long> {

    List<ServiceImage> findByServiceIdOrderBySortOrderAscIdAsc(Long serviceId);

    List<ServiceImage> findByServiceIdInOrderBySortOrderAscIdAsc(Collection<Long> serviceIds);

    Optional<ServiceImage> findByIdAndServiceId(Long id, Long serviceId);

    long countByServiceId(Long serviceId);
}
