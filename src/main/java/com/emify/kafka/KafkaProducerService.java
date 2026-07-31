package com.emify.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private final KafkaTemplate<String, AppointmentEvent> kafkaTemplate;

    public static final String TOPIC_APPOINTMENT_CREATED   = "appointment.created";
    public static final String TOPIC_APPOINTMENT_CANCELLED = "appointment.cancelled";
    public static final String TOPIC_APPOINTMENT_COMPLETED = "appointment.completed";

    public void publishAppointmentEvent(String topic, AppointmentEvent event) {
        // Usamos locationId como key para que eventos de la misma sucursal
        // vayan siempre a la misma partición — importante para Flink windows
        String key = "location-" + event.getLocationId();

        CompletableFuture<SendResult<String, AppointmentEvent>> future =
                kafkaTemplate.send(topic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("❌ Error publicando evento {} al topic {}: {}",
                        event.getEventType(), topic, ex.getMessage());
            } else {
                log.info("✅ Evento publicado → topic={} partition={} offset={} key={}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        key);
            }
        });
    }
}