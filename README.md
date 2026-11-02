# Emify — Backend (Spring Boot 4.1 · Java 21)

## Correr en local (sin instalar base de datos)

```bash
# Windows
mvnw.cmd spring-boot:run
# macOS / Linux
./mvnw spring-boot:run
```

- Sin perfil activo se usa **SQLite**: se crea el archivo `emify-local.db` en esta carpeta.
- API: `http://localhost:8080/api`
- Para empezar de cero: detén el backend y borra `emify-local.db` (y la carpeta `uploads/`).
- El código de verificación del registro se imprime en la consola y, en local, el frontend
  también lo muestra en un aviso (todavía no hay SMTP).

## Perfiles

| Perfil | Base de datos | Cómo se activa |
|---|---|---|
| `sqlite` (default) | Archivo local `emify-local.db` | Nada, es el default |
| `postgres` | PostgreSQL en `localhost:5432/emify` (postgres / postgres123) | `mvnw spring-boot:run -Dspring-boot.run.profiles=postgres` |
| `prod` | PostgreSQL del servidor | `SPRING_PROFILES_ACTIVE=prod` (ya lo pone el Dockerfile) |

## Migraciones (Flyway)

- PostgreSQL: `src/main/resources/db/migration` (V1…V6)
- SQLite: `src/main/resources/db/sqlite` (misma numeración, traducida a SQLite)

Si agregas una migración nueva (p. ej. `V6__...sql`) en `db/migration`, agrega también su
equivalente en `db/sqlite` con el mismo número. En SQLite los ENUM de PostgreSQL son
`VARCHAR` + `CHECK`, y `com.emify.config.EmifySQLiteDialect` hace que Hibernate los guarde
como texto.

## Servicios externos (apagados en local)

| Servicio | Estado en local | Configuración |
|---|---|---|
| Kafka | Apagado (antes cada cita tardaba ~3 s esperando al broker) | `emify.kafka.enabled` (solo `prod` lo prende) |
| WhatsApp (UltraMsg) | No implementado todavía | `TODO` en `BookingService` |
| Google Maps | Apagado en el frontend | `VITE_GOOGLE_MAPS_ENABLED` en el frontend |
| Imágenes (S3) | Se guardan en `./uploads` y se sirven en `/uploads/**` | `emify.uploads.enabled` (en `prod` está apagado) |

## Zona horaria

Las citas y los horarios se guardan en hora local del negocio (`emify.timezone`,
default `America/Mexico_City`). Si llega una fecha con `Z` u offset se convierte a esa zona.

## Endpoints de barbería

| Método | Ruta | Uso |
|---|---|---|
| POST | `/api/authBarberShop/barbershop/create` | Alta de barbería (un dueño puede tener varias; `barbershop_id` opcional para actualizar) |
| POST | `/api/authBarberShop/location/create` | Alta / edición de sucursales (`{barbershop_id, locations}`) |
| POST | `/api/authBarberShop/location/{id}/schedule` | Horario semanal |
| POST | `/api/authBarberShop/location/{id}/set-default` | Sucursal predeterminada |
| GET | `/api/authBarberShop/location/{id}/calendar?date=YYYY-MM-DD` | Calendario (dueño o personal) |
| GET | `/api/authBarberShop/location/{id}/recent-sales` · `upcoming-appointments` · `top-services` · `top-staff` · `clients` | Dashboard |
| GET/POST | `/api/authBarberShop/location/{id}/services[/{serviceId}[/delete]]` | Catálogo |
| GET/POST | `/api/authBarberShop/location/{id}/products[/{productId}[/delete]]` | Productos |
| GET | `/api/team/location/{id}` | Equipo + código de invitación |
| POST | `/api/team/location/{id}/staff` · `/api/team/join` · `/api/team/staff/{id}/remove` | Alta / unirse / baja de personal |
| POST | `/api/team/location/{id}/owner` `{move?}` | El dueño atiende en esa sucursal (solo una; 409 si ya atiende en otra, `move: true` lo cambia) |
| GET/POST | `/api/team/owner/locations` · `/api/team/owner/primary` `{location_id}` | Dónde atiende el dueño / elegir su única sucursal |
| GET | `/api/booking/location/{id}` · `/api/booking/location/{id}/slots` | Página pública de reservas |
| POST | `/api/booking/appointment` | Crear cita (multipart) |
| GET/POST | `/api/booking/appointment/{token}[/cancel\|/reschedule]` | Ver / cancelar / reagendar |
| GET | `/api/booking/my-appointments` | Historial del cliente logeado |
| GET | `/api/attendance/pending` | Citas que ya pasaron sin asistencia registrada (pop-up) |
| POST | `/api/attendance/{id}` `{attended: true, payment_method}` · `{attended: false}` | Sí llegó (`completed`, cuenta como venta; `payment_method` = `efectivo`\|`tarjeta`\|`transferencia`) / no llegó (`no_show`) |

## Asistencia y ventas

Solo las citas con estado `completed` (el cliente sí llegó) cuentan como venta, visita o
servicio realizado en el dashboard. Cuando termina una cita, **solo al barbero asignado** le
aparece un pop-up para registrar si el cliente llegó (si se le acumulan varias, se muestran en
una sola lista). El dueño también puede marcar o corregir desde el Calendario.

Al marcar "Sí llegó" se pregunta cómo pagó el cliente (efectivo, tarjeta o transferencia).
Es solo informativo (Emify no cobra nada) y el dashboard muestra las ventas por método.

## Personal por default

Al dar de alta la barbería, el último paso pregunta quién atiende en cada sucursal. Si el dueño
lo omite, queda como barbero solo en la predeterminada (`POST /api/team/location/{id}/owner`).
El dueño atiende en **una sola** sucursal (en el alta elige cuál; si omite, la predeterminada). Un barbero
sí puede estar en varias: el empalme de citas se valida **por persona**, así que si ya tiene cita a las
10:00 en una sucursal, ese horario se bloquea en las demás.
