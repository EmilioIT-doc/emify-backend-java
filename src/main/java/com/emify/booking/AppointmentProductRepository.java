package com.emify.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AppointmentProductRepository extends JpaRepository<AppointmentProduct, Long> {

    List<AppointmentProduct> findByAppointmentIdInOrderByIdAsc(Collection<Long> appointmentIds);
}
