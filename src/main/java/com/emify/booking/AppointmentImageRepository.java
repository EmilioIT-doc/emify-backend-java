package com.emify.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AppointmentImageRepository extends JpaRepository<AppointmentImage, Long> {

    List<AppointmentImage> findByAppointmentIdInOrderByIdAsc(Collection<Long> appointmentIds);
}
