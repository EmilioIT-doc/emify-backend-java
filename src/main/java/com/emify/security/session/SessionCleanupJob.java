package com.emify.security.session;

import com.emify.security.LoginAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

/**
 * Limpieza diaria: borra sesiones que terminaron hace más de 30 días y actividad de más de 180 días.
 * (Las sesiones vencidas ya no sirven desde el momento en que vencen; esto solo evita que la tabla crezca.)
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SessionCleanupJob {

    private final UserSessionRepository sessions;
    private final AuthEventRepository events;
    private final LoginAttemptService loginAttempts;

    // Todos los días a las 4:17 (hora del servidor)
    @Scheduled(cron = "0 17 4 * * *")
    public void cleanup() {
        LocalDateTime now = SessionService.now();
        int deletedSessions = sessions.deleteEndedBefore(now.minusDays(30));
        int deletedEvents = events.deleteOlderThan(now.minusDays(180));
        loginAttempts.purge();
        if (deletedSessions > 0 || deletedEvents > 0) {
            log.info("Limpieza de sesiones: {} sesiones y {} eventos borrados", deletedSessions, deletedEvents);
        }
    }
}
