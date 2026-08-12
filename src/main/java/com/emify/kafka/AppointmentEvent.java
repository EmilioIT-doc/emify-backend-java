package com.emify.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentEvent {

    private String eventType;      // appointment.created, appointment.cancelled, etc.
    private Long appointmentId;
    private Long locationId;
    private Long serviceId;
    private String serviceName;
    private Long userId;
    private String clientName;
    private Long staffId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private LocalDateTime eventTimestamp;
}