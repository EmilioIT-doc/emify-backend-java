package com.emify.security;

import com.emify.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freno contra fuerza bruta, en memoria (sin dependencias). Cuenta fallos por llave (correo) en una ventana:
 * - login / contraseña al unirse con invitación: 5 fallos en 15 min → bloqueo de 15 min para ese correo.
 * - envío de código de verificación: 1 por minuto y 5 por hora por correo.
 *
 * Limitaciones (documentadas en CLAUDE.md): vive en memoria de una sola instancia (se reinicia con el servidor)
 * y no frena ataques repartidos entre muchos correos. No se limita por IP porque detrás del proxy de
 * Vercel/OpenShift todas las peticiones llegarían con la misma IP y un atacante bloquearía a todos.
 */
@Service
public class LoginAttemptService {

    private static final int MAX_FAILURES = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK_TIME = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> codeRequests = new ConcurrentHashMap<>();

    public static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Lanza 429 si ese correo está bloqueado por intentos fallidos. */
    public void checkNotBlocked(String email) {
        Duration wait = remainingLock(key(email));
        if (!wait.isZero()) {
            long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Demasiados intentos. Espera " + minutes + (minutes == 1 ? " minuto" : " minutos") + " e inténtalo de nuevo.");
        }
    }

    /** Registra un fallo; regresa true si con este fallo la cuenta quedó bloqueada. */
    public boolean recordFailure(String email) {
        Instant now = Instant.now();
        Deque<Instant> list = failures.computeIfAbsent(key(email), k -> new ArrayDeque<>());
        synchronized (list) {
            prune(list, now.minus(FAILURE_WINDOW));
            list.addLast(now);
            return list.size() >= MAX_FAILURES;
        }
    }

    public void reset(String email) {
        failures.remove(key(email));
    }

    /** Código de verificación: máximo 1 por minuto y 5 por hora por correo (evita bombardear un buzón). */
    public void checkCodeRequest(String email) {
        Instant now = Instant.now();
        Deque<Instant> list = codeRequests.computeIfAbsent(key(email), k -> new ArrayDeque<>());
        synchronized (list) {
            prune(list, now.minus(Duration.ofHours(1)));
            if (!list.isEmpty() && list.peekLast().isAfter(now.minus(Duration.ofMinutes(1)))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                        "Ya te enviamos un código. Espera un minuto para pedir otro.");
            }
            if (list.size() >= 5) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                        "Pediste demasiados códigos. Inténtalo de nuevo en una hora.");
            }
            list.addLast(now);
        }
    }

    private Duration remainingLock(String key) {
        Deque<Instant> list = failures.get(key);
        if (list == null) return Duration.ZERO;
        Instant now = Instant.now();
        synchronized (list) {
            prune(list, now.minus(FAILURE_WINDOW));
            if (list.size() < MAX_FAILURES) return Duration.ZERO;
            Instant unlock = list.peekLast().plus(LOCK_TIME);
            return unlock.isAfter(now) ? Duration.between(now, unlock) : Duration.ZERO;
        }
    }

    private static void prune(Deque<Instant> list, Instant before) {
        while (!list.isEmpty() && list.peekFirst().isBefore(before)) list.pollFirst();
    }

    /** Limpieza periódica (la llama SessionCleanupJob) para que los mapas no crezcan sin fin. */
    public void purge() {
        Instant now = Instant.now();
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now.minus(FAILURE_WINDOW.plus(LOCK_TIME)));
                return e.getValue().isEmpty();
            }
        });
        codeRequests.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now.minus(Duration.ofHours(1)));
                return e.getValue().isEmpty();
            }
        });
    }
}
