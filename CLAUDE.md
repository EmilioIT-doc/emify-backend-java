# CLAUDE.md — Emify Backend

Guía técnica del backend para cualquier agente o desarrollador. Léela antes de tocar código.
- Contexto de producto, forma de trabajo con Emilio, decisiones tomadas y roadmap: `../CLAUDE.md` (carpeta raíz `emify/`).
- Frontend: `../emify-frontend` (React 19 + Vite), con su propio `CLAUDE.md`. El contrato de la API del lado del
  front está en `emify-frontend/src/utils/API_URL.js`.
- Remoto: `github.com/EmilioIT-doc/emify-backend-java`. **No hagas push** salvo que Emilio lo pida.

---

## 1. Qué es Emify (el negocio)

Emify es una plataforma de **agendamiento entre un negocio y sus clientes**. El cliente directo de Emify
es el **negocio**, que paga una suscripción (`subscription_status`: trial/active/expired, pagos con Clip,
aún no implementado aquí). Los clientes finales del negocio reservan sin crear cuenta.

No es una agenda genérica: cada **rubro (vertical)** tiene su propio flujo, tablas y pantallas, adaptados
a cómo trabaja ese tipo de negocio. Hoy existen:

| Rubro | Estado | Qué reserva el cliente |
|---|---|---|
| **Barbería / salón** (`barbershop`) | ✅ Funcional de punta a punta | Un servicio, con un barbero, en un horario |
| **Restaurante** (`restaurant`) | 🟡 Parcial | Menú y pedidos (el pedido real va por WhatsApp desde el navegador del cliente; aquí solo se guarda una copia para métricas). Reservar mesa está pendiente |

La meta es sumar más rubros (spas, consultorios, tatuajes, uñas, mascotas, entrenadores…).

**Arquitectura:** monolito modular en Spring Boot, a propósito. El plan a largo plazo es migrar poco a poco
a microservicios cuando crezca el volumen. Por eso cada rubro vive en su propio paquete y se evita acoplar
rubros entre sí. Respeta esos límites al agregar código.

### Glosario
- **Negocio / empresa**: `Barbershop` o `Restaurant`. Tiene un **dueño** (`owner_id` → `users`).
- **Sucursal**: `BarbershopLocation` / `RestaurantLocation`. Casi todo cuelga de la sucursal: servicios,
  horarios, personal, citas, productos. Una de ellas es la *predeterminada* (`is_default`). El borrado es
  lógico (`deleted_at`).
- **Personal / barbero**: `BarbershopStaff` = (usuario, sucursal). Un barbero puede atender en varias
  sucursales; **el dueño solo en una** (ver §6 "Personal").
- **Cita**: `Appointment`. Tiene cliente, servicio, sucursal, barbero opcional, inicio, fin y un `token`
  público para que el cliente la vea, reagende o cancele sin cuenta.

---

## 2. Stack y cómo correrlo

- **Java 21**, **Spring Boot 4.1** (Spring Framework 7, Spring Security 7, Hibernate 7.4, **Jackson 3**,
  Flyway 12), Lombok, Spring Kafka. (JJWT se quitó en nov-2026: la sesión es del lado del servidor, ver §5.3.)
- Correr en local, sin instalar nada: `mvnw.cmd spring-boot:run` (Windows) o `./mvnw spring-boot:run`.
  API en `http://localhost:8080/api`.
- No hay pruebas automatizadas reales (solo `contextLoads`). Si agregas lógica de negocio importante,
  agrega pruebas.
- Compilar sin correr: `mvnw.cmd -q compile`. Empaquetar: `mvnw.cmd clean package -DskipTests`.
- **Docker** (`Dockerfile`, multi-stage): `maven:3.9.6-eclipse-temurin-21` compila con `-DskipTests` y
  `eclipse-temurin:21-jre-jammy` corre `app.jar` en el puerto 8080 con `SPRING_PROFILES_ACTIVE=prod`.
  ⚠️ Trae un default inseguro (`DB_PASSWORD=postgres123`): en prod debe sobreescribirse. (Ya no hay `JWT_SECRET`.)
- Despliegue actual: temporal en OpenShift Developer Sandbox. Plan: Lightsail + Docker + Caddy (ver `../CLAUDE.md` §5).

### Perfiles (`src/main/resources`)
| Perfil | BD | Uso |
|---|---|---|
| `sqlite` (**default**, por `spring.profiles.default`) | Archivo `emify-local.db` | Desarrollo / pruebas locales |
| `postgres` | PostgreSQL `localhost:5432/emify` | Local con la BD real |
| `prod` | PostgreSQL del servidor (host `postgresql:5432`); lo activa el `Dockerfile` | Producción |

`application.yml` solo tiene lo común y **ningún datasource**. Cada perfil define el suyo.

### Configuración propia (`emify.*`)
| Propiedad | Default | Para qué |
|---|---|---|
| `emify.timezone` | `America/Mexico_City` | Zona horaria de los negocios (ver §6) |
| `emify.kafka.enabled` | `false` (solo `prod` = `true`) | Publicar eventos de citas |
| `emify.dev.expose-verification-code` | `true` en sqlite/postgres | Regresa el código de registro como `dev_code` (no hay SMTP) |
| `emify.uploads.enabled` / `dir` | `true` local, `false` en prod | Guarda imágenes en disco y las sirve en `/uploads/**` (sustituto de S3) |
| `emify.session.cookie-name` | `emify_session` (prod: `__Host-emify_session`) | Cookie HttpOnly de la sesión |
| `emify.session.cookie-secure` | `true` (sqlite/postgres: `false`, es http://localhost) | `Secure` en la cookie de sesión y en la de CSRF |
| `emify.session.same-site` | `Lax` | SameSite de las cookies |
| `emify.session.idle-timeout` / `absolute-timeout` | `7d` / `30d` | Sin uso 7 días o 30 días desde el login → hay que volver a entrar |
| `emify.session.rotation-interval` / `rotation-grace` | `24h` / `2m` | Cada 24 h la sesión cambia de token; el anterior sirve 2 min (peticiones en paralelo) |
| `emify.session.touch-interval` | `5m` | Cada cuánto se guarda `last_seen_at` (no en cada petición) |
| `emify.session.single-session` | `false` (prod: `EMIFY_SINGLE_SESSION`) | `true` = una sola sesión por cuenta: entrar cierra las demás (`NEW_LOGIN`) |
| `emify.session.max-active` | `10` | Tope de sesiones abiertas por cuenta; la menos usada se cierra (`LIMIT`) |

Otras propiedades relevantes de `application.yml`:
- `spring.servlet.multipart.max-file-size: 10MB` / `max-request-size: 12MB` (fotos de celular en reservas).
- `spring.flyway.validate-on-migrate: true`, `baseline-on-migrate: false`.
- Kafka: `localhost:9092` con serializadores JSON (solo se usa si `emify.kafka.enabled`).

---

## 3. Base de datos y migraciones (⚠️ lee esto)

- El esquema lo maneja **Flyway**. **Nunca** modifiques una migración ya aplicada; crea una nueva.
- Hay **dos carpetas de migraciones con la misma numeración**:
  - `db/migration` → PostgreSQL (la fuente de verdad, se usa en prod).
  - `db/sqlite` → la misma versión traducida a SQLite.
- **Regla:** toda migración nueva `V{n}__...sql` en `db/migration` necesita su equivalente `V{n}` en `db/sqlite`.
  - Los `ENUM` de PostgreSQL en SQLite son `VARCHAR` + `CHECK`. Para cambiar un CHECK en SQLite hay que
    reconstruir la tabla (ejemplo: `db/sqlite/V5__appointment_no_show.sql`).
  - `ALTER TYPE ... ADD VALUE` en PostgreSQL dentro de una transacción requiere PostgreSQL 12 o mayor.
- Versiones actuales: V1 esquema base (barbería y usuarios), V2–V4 restaurante, V5 estado `no_show`,
  V6 `appointments.payment_method`, V7 hora de comida (`availability.break_start/break_end`) y tiempo entre
  citas por sucursal (`barbershop_locations.buffer_minutes`, default 10), V8 reserva con varios servicios
  (`appointments.group_token`), fotos por servicio (`service_images`), fotos de referencia del cliente
  (`appointment_images`) y productos apartados (`appointment_products`), V9 sesiones (`user_sessions`), registro
  de actividad (`auth_events`) y `email_verification_codes.attempts`.
- ⚠️ Desde V8 `appointment_images` y `appointment_products` tienen FK hacia `appointments`: si alguna vez se
  reconstruye esa tabla en SQLite (como en V5), tenlas en cuenta.
- ⚠️ **Dos personas no pueden usar el mismo número de migración.** Antes de crear `V{n}`, haz `pull` y usa el
  siguiente número libre (el 5-nov hubo dos V8: la de sesiones pasó a V9). Flyway no arranca con versiones repetidas.
- En PostgreSQL los enums son nativos (`user_role`, `staff_role`, `appointment_status`, `service_type_enum`,
  `barbershop_image_type`). Las entidades los mapean con
  `@Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)`. **Usa ese mismo patrón** para enums
  nuevos: un `String` simple no entra en una columna enum de PostgreSQL.
- En SQLite, `com.emify.config.EmifySQLiteDialect` registra `NAMED_ENUM` como `VARCHAR`. Sin él, Hibernate
  guardaría el enum serializado como bytes.
- `ddl-auto`: `validate` en postgres/prod y `none` en sqlite.
- Las fechas en SQLite se guardan como texto legible (`date_class=TEXT`). Evita funciones de fecha de la BD
  en JPQL (`CAST(x AS date)`, etc.); usa **rangos** `[inicio, fin)` como en `AppointmentRepository`.
- ⚠️ Varias FK tienen `ON DELETE CASCADE` (por ejemplo `appointments.service_id` y `appointments.staff_id`).
  Borrar un servicio o un barbero **borraría el historial de citas**. Por eso:
  - Borrar un servicio que ya tiene citas se rechaza (solo se puede desactivar).
  - Quitar a un barbero del equipo es una baja lógica (`is_active = false`).

---

## 4. Estructura de paquetes (`com.emify`)

| Paquete | Contenido |
|---|---|
| `auth` | Registro con código por email, login, logout, `/auth/me`, tipo de cuenta, editar perfil (`AuthController`) y seguridad de la cuenta: sesiones, actividad, contraseña (`AccountSecurityController`). `auth/dto/ApiResponse` es el sobre de todas las respuestas |
| `user` | `User` (implementa `UserDetails`; roles `client` / `barber` / `owner`) |
| `security` | `SecurityConfig`, `LoginAttemptService` (freno a fuerza bruta), `PasswordPolicy`; `session/` (sesiones: `SessionService`, `SessionAuthenticationFilter`, `UserSession`, `AuthEvent`, limpieza diaria) y `csrf/` (CSRF para SPA) |
| `config` | CORS y `EmifySQLiteDialect` |
| `common` | Utilidades transversales: `ApiException` + `GlobalExceptionHandler`, `BusinessClock` (zona horaria), `CurrentUser`, `FileStorageService` y `UploadsWebConfig` |
| `barbershop` | Rubro barbería: negocio, sucursales, horarios, servicios (catálogo), productos, dashboard (calendario, métricas, clientes) y `LocationAccessService` (permisos) |
| `booking` | Citas: página pública de reservas, cálculo de horarios, crear/ver/cancelar/reagendar por token, mis citas y **asistencia** |
| `team` | Personal de una sucursal: listar, crear cuenta "temporal", unirse con código, dueño como barbero, baja |
| `kafka` | Productor de eventos de citas (`appointment.created`, `.cancelled`, `.completed`) |
| `restaurant`, `ordering` | Rubro restaurante (dashboard y flujo público de pedidos) |

---

## 5. Convenciones de la API

- **Prefijos:**
  - `/api/auth/**`, `/api/booking/**`, `/api/ordering/**` y `/api/team/invite/**` son públicos (salvo
    `/auth/me|session|sessions|activity|password|updateRole|updateProfile`, que piden sesión).
  - Todo lo demás requiere la **cookie de sesión** (la pone el backend al registrarse, entrar o unirse con código),
    salvo `/uploads/**` y `/error`. Un header `Authorization` se ignora.
  - **Todo POST/PUT/DELETE lleva CSRF**: header `X-XSRF-TOKEN` = cookie `XSRF-TOKEN` (axios lo hace solo).
  - `/api/booking/my-appointments` es público en la configuración de seguridad, pero valida la sesión en el controlador.
- **Formato de respuesta:** siempre `ApiResponse` → `{ "status": true|false, "message": "...", "data": ... }`.
  El frontend lee `response.data.data.<campo>`.
- **Llaves en `snake_case`.** Las respuestas se arman a mano con `Map` / `LinkedHashMap`.
  - **Nunca regreses entidades JPA directamente.** Tienen proxies lazy, y además `User` trae el hash del password.
  - Usa `LinkedHashMap` si algún valor puede ser `null` (`Map.of` no acepta nulls).
- **Errores:** lanza `ApiException.notFound(...)` o `ApiException.unprocessable(...)` desde cualquier capa.
  `GlobalExceptionHandler` los convierte a `{status:false, message}`; las validaciones `@Valid` salen como 422
  con el primer mensaje. El código viejo también regresa `ApiResponse.fail(...)` con 200/422; ambos estilos conviven.
- **Usuario logeado:** `CurrentUser.require(authentication)`. Es la entidad `User` puesta por `SessionAuthenticationFilter`
  (está *detached*: úsala por id o por referencia, no navegues sus relaciones lazy).
- **Permisos sobre una sucursal:** usa siempre `LocationAccessService`.
  - `requireOwner(user, locationId)` para escrituras del dueño.
  - `requireOwnerOrStaff(user, locationId)` para lecturas del dashboard (el barbero ve su calendario).
  - Nunca confíes en el id de la URL sin validarlo.
- Muchas rutas del frontend usan `POST` incluso para editar o borrar (`/{id}` y `/{id}/delete`). Si agregas
  un `PUT`/`DELETE`, considera aceptar también el `POST` que espera el frontend.
- Las rutas `/api/authBarberShop/...` y `/api/authRestaurant/...` tienen nombres heredados del backend
  anterior en Laravel. No las renombres sin cambiar `API_URL.js` en el frontend.
- Open-in-view está activo: se puede navegar relaciones lazy en servicios no transaccionales durante el request.

---

## 5.1 Modelo de datos (tablas)

| Tabla | Entidad | Notas |
|---|---|---|
| `users` | `User` | `role` enum `user_role` (`client`/`barber`/`owner`), `avatar_url` (una de 4 URLs de S3), `phone`, `email_verified_at`. Los clientes "guest" usan `{telefono}@guest.emify.com` |
| `email_verification_codes` | `EmailVerificationCode` | Código de 6 dígitos, expira en 10 min |
| `barbershops` | `Barbershop` | `owner_id` (no único: varias marcas por dueño), `subscription_status`, `last_payment_id` |
| `barbershop_locations` | `BarbershopLocation` | Sucursal: dirección, teléfono, `is_default`, `serves_physical`/`serves_home`, `invite_code` (8), `buffer_minutes` (V7, descanso entre citas, default 10), `deleted_at` (borrado lógico) |
| `barbershop_images` | — | enum `barbershop_image_type` (`logo`/`banner`/`gallery`); poco usada |
| `services` | `BarberService` | Catálogo por sucursal: nombre, precio, `duration` (min), `service_type` (`service_type_enum`), `is_active` |
| `products` | `Product` | Productos por sucursal (con imagen) |
| `barbershop_staff` | `BarbershopStaff` | (usuario, sucursal), `staff_role` (siempre `barber`), `is_active` (baja lógica) |
| `staff_services` | — | Relación personal ↔ servicios (sin uso real todavía) |
| `availability` | `Availability` | Horario semanal: `day_of_week` 0=domingo…6, `start_time`, `end_time`, `break_start`/`break_end` (V7, comida; NULL = sin comida); `staff_id NULL` = horario de la sucursal. Una fila por día |
| `appointments` | `Appointment` | `location_id`, `service_id`, `staff_id` (nullable), `client_id`, `start_time`, `end_time`, `status`, `payment_method` (V6), `token` (único, público), `reference_image_url` (primera foto de referencia), `group_token` (V8: misma visita con varios servicios) |
| `service_images` | `ServiceImage` | V8. Fotos del trabajo de un servicio, **máx. 3** (`sort_order`; la primera es la portada). Las sube el dueño en Catálogo |
| `appointment_images` | `AppointmentImage` | V8. Fotos de referencia del cliente (**máx. 3 por visita**), en la cita del servicio al que corresponden |
| `appointment_products` | `AppointmentProduct` | V8. Productos apartados en la visita (copia de nombre y precio, `quantity`). Van en la primera cita del grupo. **No cuentan como venta** (pendiente de decidir) |
| `restaurants`, `restaurant_locations`, `menu_categories`, `menu_items`, `restaurant_availability`, `restaurant_orders`, `restaurant_order_items` | `restaurant/*` | Rubro restaurante (V2–V4) |

Enums nativos de PostgreSQL: `user_role`, `staff_role` (`barber`/`admin`/`manager`), `appointment_status`
(+ `no_show` en V5), `barbershop_image_type`, `service_type_enum`. `payment_method` es `VARCHAR(20)` + `CHECK`.

## 5.2 Inventario de endpoints

Todos bajo `/api`. 🔓 = público. El resto requiere la cookie de sesión. "dueño" = `LocationAccessService.requireOwner`.

**`auth`** (`AuthController` y `AccountSecurityController`; 🔓 salvo los que dicen "sesión")
| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/auth/sendVerificationCode` | Genera código de 6 dígitos (en local lo regresa como `dev_code`). Máx. 1 por minuto y 5 por hora por correo (429) |
| POST | `/auth/createUser` | Crea usuario con el código (5 intentos por código); rol `client`, avatar al azar; **abre la sesión (cookie)**, 201. No regresa token |
| POST | `/auth/login` | Email + password → abre la sesión (cookie) y regresa `{user, role, businesses…}`. 401 `INVALID_CREDENTIALS`; 5 fallos = 15 min de bloqueo (429 `TOO_MANY_ATTEMPTS`) |
| POST | `/auth/logout` | Cierra la sesión en el servidor y borra la cookie |
| GET | `/auth/csrf` | Solo asegura la cookie `XSRF-TOKEN` |
| GET | `/auth/session` | (sesión) Latido del frontend; **no cuenta como actividad**. `{id, created_at, expires_at, idle_expires_at}` |
| GET | `/auth/sessions` | (sesión) Dispositivos con sesión abierta: `{id, device, ip, created_at, last_seen_at, current}` |
| POST | `/auth/sessions/{id}/revoke` | (sesión) Cierra la sesión de un dispositivo (si es la actual, es un logout) |
| POST | `/auth/sessions/revoke-others` | (sesión) Cierra todas las demás → `{closed}` |
| GET | `/auth/activity` | (sesión) Últimos 30 eventos: `{type, device, ip, detail, created_at}` |
| POST | `/auth/password` | (sesión) `{current_password, new_password}`. 422 `WRONG_PASSWORD`; cierra las demás sesiones y renueva la actual → `{closed_sessions}` |
| POST | `/auth/updateRole` | (sesión) `owner` o `client`: el tipo de cuenta con el que se registró (no da ni quita permisos) |
| GET | `/auth/me` | Perfil + `businesses` + `workplaces` + `business`/`staff_location` (compatibilidad) |
| POST | `/auth/updateProfile` | name, phone, avatar (validado contra `AVATARS`). El email no se edita |
| POST | `/auth/googleAuth`, `/auth/facebookAuth` | Stub → 503 |

**`authBarberShop`** (`BarbershopController`, `ServiceController`, `ProductController`, `BarbershopDashboardController`)
| Método | Ruta | Permiso |
|---|---|---|
| POST | `/authBarberShop/barbershop/create` | Usuario logeado (ya no cambia `users.role`) |
| POST | `/authBarberShop/location/create` | Dueño del negocio (`barbershop_id`) |
| GET | `/authBarberShop/barbershop/{id}` | Dueño. Incluye sucursales activas |
| POST | `/authBarberShop/barbershop/{id}/update` | Dueño |
| POST | `/authBarberShop/location/{id}/default` (alias `/set-default`) | Dueño |
| POST | `/authBarberShop/location/{id}/delete` | Dueño (borrado lógico) |
| POST | `/authBarberShop/location/{id}/schedule` | Dueño |
| GET/POST | `/authBarberShop/location/{id}/services` | GET dueño o personal / POST dueño |
| POST | `/authBarberShop/location/{id}/services/{sid}` · `/{sid}/delete` (también `DELETE /{sid}`) | Dueño |
| POST | `/authBarberShop/location/{id}/services/{sid}/images` (multipart `image`) · `/images/{imgId}/delete` | Dueño. Máx. 3 fotos; el GET de servicios trae `images: [{id, url}]` |
| GET/POST | `/authBarberShop/location/{id}/products` · `/{pid}` · `/{pid}/delete` | Dueño (multipart para imagen) |
| GET | `/authBarberShop/location/{id}/calendar?date=YYYY-MM-DD` | Dueño o personal |
| GET | `/authBarberShop/location/{id}/recent-sales` | Dueño. Sin params: últimos 7 días. Con `?period=&date=YYYY-MM-DD`: `day` = horas del horario de ese día · `week` = lun–dom (`closed` en días que no abre) · `month` = ene–dic del año · `year` = últimos 10 años. `total` = suma de la gráfica; `previous_total` = rango anterior hasta el mismo punto; `future`, `is_current`, `by_payment` |
| GET | `/authBarberShop/location/{id}/upcoming-appointments` | Dueño |
| GET | `/authBarberShop/location/{id}/top-services` · `/top-staff` · `/clients` | Dueño |

**`booking`** (`BookingController`, 🔓)
| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/booking/location/{id}` | Datos públicos: servicios activos (con `images` y `is_top`), personal activo (con `is_top` = más citas atendidas), `products` activos y horario |
| GET | `/booking/location/{id}/slots?date=&duration=&staff_id=` | Horarios libres. Sin `staff_id` y con personal = unión de los horarios de cada barbero ("Cualquiera") |
| POST | `/booking/appointment` | `multipart/form-data` o `x-www-form-urlencoded`. Crea la visita (+ usuario guest). `service_ids` (varios, en orden; `service_id` sigue funcionando), `staff_id` opcional, `product_ids` + `product_quantities`, `reference_images` + `reference_service_ids` (máx. 3), `notes` |
| GET | `/booking/my-appointments` | Valida sesión en el controlador |
| GET | `/booking/appointment/{token}` | Ver cita |
| POST | `/booking/appointment/{token}/cancel` · `/reschedule` | Solo citas futuras no cerradas |

**`attendance`** (`AttendanceController`): `GET /attendance/pending`, `POST /attendance/{id}` `{attended, payment_method}`,
`POST /attendance/walk-in` `{location_id, service_id, start_time?, payment_method, client_name?}` = **cliente imprevisto**
(llegó sin cita): cita `completed` del barbero que la registra (debe ser personal activo de la sucursal), hora de
hasta 7 días atrás y no futura, cliente genérico con teléfono `0000000000` ("Cliente imprevisto"; el nombre opcional
va en `notes`). Así se cuentan los imprevistos por barbero.

**`content`** (`ContentController`): `GET /content/barber-phrases` → `{phrases: [{kind: frase|dato, text}], rotate_seconds}`.
Frases que rotan en "Mi agenda" del barbero cuando tiene el día libre (lista fija en el código).

**`team`** (`TeamController`)
| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/team/location/{id}` | Personal + `invite_code` (solo si es el dueño) |
| POST | `/team/location/{id}/staff` | Crea cuenta "temporal" de barbero |
| POST | `/team/location/{id}/owner` | Dueño atiende aquí; 409 si ya atiende en otra; `{move:true}` para cambiarse |
| GET | `/team/owner/locations` | Dónde atiende el dueño (+ `upcoming_appointments`) |
| POST | `/team/owner/primary` | `{location_id}`: deja al dueño solo en esa sucursal |
| POST | `/team/join` | Unirse con código estando logeado (ya no cambia `users.role`) |
| GET | `/team/invite/{code}` | **Público.** Datos de la sucursal del código (nombre, negocio, dirección) |
| POST | `/team/invite/{code}/join` | **Público.** `{name, phone, email, password}`. Correo nuevo → crea cuenta barber (sin verificar correo; reusa al guest con ese teléfono). Correo existente → se une con esa cuenta si la contraseña coincide; si no, **428**. Entra directo al equipo, **abre la sesión (cookie)** y regresa `{location}`. Contraseña nueva: mínimo 8 caracteres; 5 contraseñas incorrectas = bloqueo de 15 min |
| POST | `/team/staff/{id}/remove` | Baja lógica |

**`authRestaurant`** y **`ordering`**: ver `RestaurantController` y `RestaurantOrderingController` (fuera de foco).

## 5.3 Seguridad (sesiones del lado del servidor, nov-2026)

**Por qué no JWT:** un JWT no se puede invalidar antes de que venza (logout, "cerrar en otro dispositivo", cambio de
contraseña) sin guardar estado en el servidor. Como de todos modos hay que consultar la BD, se usa una sesión
clásica: más simple y revocable al instante.

- **Cookie de sesión** (`emify.session.*`): token aleatorio de 256 bits (base64url) en una cookie **HttpOnly**,
  `SameSite=Lax`, `Path=/`, `Secure` en prod, `Max-Age` = 30 días. En prod se llama `__Host-emify_session`
  (el navegador exige Secure, sin Domain y Path=/). JavaScript no la puede leer.
- **En la BD solo va el SHA-256 del token** (`user_sessions.token_hash`). Un respaldo filtrado no sirve para entrar.
- `SessionAuthenticationFilter` valida la sesión en **cada petición**. Por eso revocar surte efecto en la siguiente
  petición protegida del otro dispositivo (y el frontend revisa `/auth/session` cada minuto).
- Caducidad: 7 días sin uso (`IDLE`) o 30 días desde el login (`EXPIRED`). `last_seen_at` se actualiza cada 5 min;
  el latido `/auth/session` no cuenta como uso.
- Rotación: cada 24 h el token cambia (UPDATE condicional sobre el hash viejo: dos peticiones simultáneas no crean
  dos tokens); el anterior sigue valiendo 2 min para las peticiones que ya iban en camino.
- Al entrar se cierra la sesión que traía el navegador (`REPLACED`, contra fijación de sesión). Máx. 10 por cuenta
  (`LIMIT`). `single-session=true` cierra todas las demás (`NEW_LOGIN`).
- Motivos (`revoked_reason`): LOGOUT, REVOKED_BY_USER, REVOKED_OTHERS, NEW_LOGIN, PASSWORD_CHANGED, LIMIT, EXPIRED,
  IDLE, REPLACED, USER_DISABLED. `SessionCleanupJob` (4:17 am) borra sesiones terminadas hace más de 30 días y
  eventos de más de 180.
- **CSRF**: `CookieCsrfTokenRepository` (cookie `XSRF-TOKEN` legible + header `X-XSRF-TOKEN`) con
  `SpaCsrfTokenHandler`. Se rota al entrar, registrarse, unirse con código y salir. 403 `CSRF_INVALID`.
- **Errores en JSON** (`{status:false, code, reason?, message, data:null}`): 401 `UNAUTHENTICATED` (sin cookie),
  `SESSION_EXPIRED` / `SESSION_REVOKED` (+ `reason`), 401 `INVALID_CREDENTIALS`, 403 `CSRF_INVALID` / `FORBIDDEN` /
  `ACCOUNT_DISABLED`, 422 `WRONG_PASSWORD`, 429 `TOO_MANY_ATTEMPTS`. `ApiException(status, code, message)`.
- **Contraseñas**: BCrypt costo 12 (los hashes viejos de costo 10 se actualizan al entrar). `PasswordPolicy`: 8
  caracteres a 72 bytes (más allá bcrypt no distingue) para contraseñas nuevas. Si el correo no existe se compara
  contra un hash falso (mismo tiempo de respuesta). Correos en minúsculas; las búsquedas ignoran mayúsculas.
- **Fuerza bruta** (`LoginAttemptService`, en memoria): 5 fallos en 15 min por correo = 15 min bloqueado (login,
  cambio de contraseña, unirse con código a una cuenta existente). Código de verificación: 1 por minuto, 5 por hora,
  5 intentos por código. ⚠️ En memoria: se reinicia al reiniciar y no se comparte entre instancias.
- **Registro de actividad** (`auth_events`): SIGNUP, LOGIN, LOGIN_FAILED, LOGIN_BLOCKED, LOGOUT, SESSION_REVOKED,
  SESSIONS_REVOKED, PASSWORD_CHANGED, JOIN_INVITE, con dispositivo (del User-Agent) e IP (`X-Forwarded-For`:
  informativa, se puede falsear si se llama directo al backend).
- **Notificación inmediata (SSE/WebSocket): no.** La revisión cada minuto + la siguiente petición bastan para una
  agenda; SSE exigiría conexiones abiertas por usuario detrás del proxy. Se puede sumar después sin cambiar el modelo.
- CORS en `config/CorsConfig` con `allowedOriginPatterns`. En prod el frontend llama `/api` en su mismo dominio
  (rewrite de Vercel / Caddy), así que las cookies son de primera parte.

---

## 6. Reglas de negocio clave (barbería)

### Varios negocios por cuenta ("Mis negocios")
- Una cuenta puede tener **varios negocios, incluso del mismo rubro** (por ejemplo dos barberías de marcas
  distintas), y cada negocio tiene N sucursales. No hay restricción en BD (`owner_id` no es único).
- **Nunca busques "el" negocio de un dueño**: `findByOwnerIdOrderByIdAsc` regresa una lista. Para operar,
  identifica el negocio por id (`barbershop_id` / `restaurant_id`) o por la sucursal (`findByIdAndOwnerId`).
- `barbershop/create` y `restaurant/create` crean uno nuevo, salvo que:
  - el body traiga `barbershop_id` / `restaurant_id` (el wizard regresó al paso 1 y reenvió): se actualiza ese;
  - el dueño tenga uno **sin sucursales** (un alta a medias): se reutiliza.
- `location/create` recibe `{barbershop_id | restaurant_id, locations: [...]}` para saber a qué negocio van
  las sucursales nuevas. Si no viene, usa el negocio de alguna sucursal existente de la lista o el más reciente.
- `/auth/me` (y login/updateProfile) regresan:
  - `businesses`: `[{id, name, rubro, locations_count}]`, todos los negocios del dueño (`AuthService.buildBusinessList`);
  - `workplaces`: sucursales de **otros** dueños donde el usuario es personal activo (`{location_id,
    location_name, business_id, business_name, rubro}`);
  - `business` y `staff_location`: el primero de cada uno, por compatibilidad.
- Frontend: `/mis-negocios` los lista agrupados por rubro. Al iniciar sesión, con 2+ negocios va a la lista y
  con 1 directo al panel (`shared/resolveRedirect.js`). En "Crear otro negocio", si elige un rubro donde ya
  tiene negocios, se le pregunta si crea otra marca o agrega una sucursal a uno existente.

### Alta de un negocio (wizard del frontend)
1. Registro: `sendVerificationCode` → `createUser` (rol `client`, avatar al azar) → `updateRole` (`owner` o `client`).
2. `barbershop/create`. Crea una barbería nueva (ver "Varios negocios por cuenta" para cuándo reutiliza una). Ya **no** cambia `users.role`.
3. `location/create` con `barbershop_id`. Recibe una lista de sucursales; sin `id` crea y con `id` actualiza. Cada sucursal nueva recibe:
   - un `invite_code` de 8 caracteres;
   - **7 servicios por default** (Corte, Barba, etc.);
   - la primera queda como `is_default`.
4. Servicios: el wizard deja activos los que elige el dueño (con su precio/duración) y desactiva los demás
   default con `services/{sid}` (`is_active=false`).
5. `location/{id}/schedule`: `{schedule: [{day_of_week, enabled, start_time, end_time, break_start?, break_end?}],
   buffer_minutes?}`. Horario por día (0 = domingo … 6 = sábado, `start_time` < `end_time`); la comida debe quedar
   dentro del horario del día. `buffer_minutes` (0–120) es opcional: si no viene se conserva el de la sucursal.
   Se guarda en `availability` con `staff_id = NULL` (es el horario de la sucursal).
6. Finalizar (pantalla de revisión): si el dueño no atiende en ninguna sucursal queda en la predeterminada
   (`POST /api/team/location/{id}/owner`; 409 = ya atiende en otra y se respeta). Con una sucursal sin barberos
   se ofrece invitar; con varias, se avisa qué sucursal se quedó sin nadie. Ya no existe el paso "¿Quién atiende?".

### Personal (`team`)
- En la BD todo el personal tiene `staff_role = barber`. La etiqueta que ve el frontend se calcula:
  - `owner`: es el dueño;
  - `temp`: cuenta creada por el dueño, sin correo verificado;
  - `staff`: se unió con el código de invitación.
- Un barbero puede ser personal de **varias sucursales**. No se crean cuentas duplicadas.
- **El dueño atiende como barbero en UNA sola sucursal** (entre todas las de sus negocios):
  - `POST /api/team/location/{id}/owner` responde **409** si ya atiende en otra; con `{move: true}` se cambia
    (sale de la anterior). Unirse con código a una sucursal propia también respeta la regla.
  - `GET /api/team/owner/locations` lista dónde atiende hoy (con `upcoming_appointments`); si son 2+ (datos
    anteriores a la regla), el panel muestra un aviso para elegir.
  - `POST /api/team/owner/primary {location_id}` lo deja solo en esa sucursal.
  - Al salir de una sucursal, sus citas ya agendadas ahí **se quedan con él** (las atiende); solo ya no le
    pueden agendar nuevas, porque la página de reservas solo muestra personal activo.
- **Tipo de cuenta vs. capacidades:** `users.role` es solo cómo se registró (cliente, dueño, o barbero si entró con
  invitación siendo cuenta nueva). Ya **no** se cambia al crear un negocio ni al unirse a un equipo: una cuenta puede
  ser cliente, personal en sucursales de otros y dueño a la vez. Lo que puede hacer se calcula de `businesses` y
  `workplaces` en `/auth/me`. Al hacer login, quien solo es personal cae en `/calendar/{slug}` (`staff_location`).

### Reservas (`booking`, públicas)
- La página pública es `/book?id={locationId}`. `GET /booking/location/{id}` regresa servicios activos,
  personal activo y horario.
- **Horarios disponibles** (`/slots`): empiezan a la apertura; cada uno dura `duration` y el siguiente empieza tras
  el **tiempo entre citas** de la sucursal (`buffer_minutes`, 10 por default). Si un horario cae en la comida se
  brinca al final de la comida. Se quitan los que chocan con citas `pending`/`confirmed` dejando ese mismo
  descanso antes y después de cada cita. Crear y reagendar validan el empalme con la misma ventana ampliada, y
  `validateSchedule` rechaza citas que caen en la comida.
  Si se manda `staff_id`, se considera la agenda de **esa persona en todas sus sucursales**.
- **Crear cita:**
  - Valida que el servicio y el barbero pertenezcan a la sucursal, que no sea en el pasado y que caiga dentro del horario.
  - Valida que no haya empalme:
    - **con barbero: por persona (usuario) en todas las sucursales**;
    - sin barbero: contra cualquier cita de la sucursal.
  - El cliente se identifica por teléfono: si no existe, se crea un usuario "guest"
    (`{telefono}@guest.emify.com`, sin password usable).
- **Varios servicios en una visita (V8):** se crea una cita por servicio, encadenadas **sin** tiempo entre citas
  (es la misma visita) y con el mismo `group_token`. Así ventas, top servicios y top personal cuentan cada servicio
  sin cambiar las métricas. Ver por token, cancelar y reagendar actúan sobre **toda la visita**; "Mis citas" la
  muestra una vez ("Corte + Barba"). Asistencia: el pop-up sale una sola vez y cuando termina el último servicio;
  marcar uno marca los demás que ya empezaron. El calendario sigue mostrando un bloque por servicio, cada uno con
  `group_services`, `reference_images` y `products` de toda la visita.
- **"Cualquiera":** sin `staff_id` y con personal, los horarios son la unión de los de cada barbero y al crear la
  cita se asigna al barbero libre con menos citas ese día (`staff_auto_assigned: true` en la respuesta).
- La estrella del barbero en la reserva es `is_top` (más citas `completed` en la sucursal).
- Cancelar o reagendar: por `token`, solo citas futuras, no canceladas y no cerradas.
- Fotos de referencia opcionales (multipart `reference_images`, máx. 3 por visita, o `reference_image`): se guardan
  en disco si `emify.uploads.enabled` está activo (en prod se ignoran hasta tener S3). Las fotos de servicio igual.

### Estados de cita y asistencia (ventas)
`pending` → (`confirmed`) → `completed` (**sí llegó**) | `no_show` (no llegó) | `cancelled`.

- **Solo `completed` cuenta como venta**: ventas recientes, top servicios y top personal, visitas en Clientes,
  y "servicios realizados" en la página pública. Las constantes están en `AppointmentRepository`:
  `ACTIVE_STATUSES` (ocupan horario), `CALENDAR_STATUSES` y `ATTENDED`.
- Cuando termina una cita, al **barbero asignado** le aparece un pop-up en el dashboard
  (`GET /api/attendance/pending`) para responder sí llegó o no llegó (`POST /api/attendance/{id}` con `{attended}`).
  - "Sí llegó" exige también `payment_method`: `efectivo` | `tarjeta` | `transferencia`
    (enum `Appointment.PaymentMethod`). Es **solo informativo**: Emify no cobra ni procesa pagos.
    `no_show` deja el método en `null`; las citas atendidas antes de V6 también quedan en `null`.
  - Ventas recientes devuelve `by_payment` (total y citas por método, más `sin_registrar` si hay nulos)
    para el corte de caja del dashboard.
  - El dueño solo lo ve de las citas donde él es el barbero, pero puede marcar o corregir cualquier cita
    desde el Calendario.
  - Marcar `completed` publica el evento `appointment.completed`.

### Zona horaria (⚠️ fuente de bugs)
- Todo se guarda como `LocalDateTime` en **hora local del negocio**. Para "ahora" y "hoy" usa
  `BusinessClock.now()` / `today()`, **no** `LocalDateTime.now()`: el servidor de prod corre en UTC.
- El frontend manda `YYYY-MM-DDTHH:mm:00` sin zona. `BusinessClock.parseClientDateTime` acepta eso, y si
  llega con `Z`/offset lo convierte a `emify.timezone`.
- Los timestamps de auditoría (`created_at` / `updated_at`, vía `@PrePersist`) sí usan la hora del servidor.

---

## 7. Servicios externos y su estado

| Servicio | Estado |
|---|---|
| Kafka | Solo en prod. En local está apagado (sin broker, cada `send()` bloqueaba unos 3 s) |
| WhatsApp (UltraMsg) | No implementado (hay `TODO` en `BookingService`). Mientras tanto, el cliente administra su cita con el link `/cita?token=...` |
| Email / SMTP | No implementado: el código de verificación se loguea y en local se regresa como `dev_code` |
| Google Maps / geocode | El frontend lo tiene apagado; `/api/geocode` no existe. Las sucursales se guardan sin lat/lng |
| S3 | No hay. Imágenes en disco local (solo dev). Los avatares son 4 URLs fijas de S3 que el frontend mapea a imágenes locales |
| Clip (pagos) | `/api/subscription` no existe todavía |
| Google / Facebook login | Endpoints stub que responden 503 |

---

## 8. Cómo agregar cosas

**Un endpoint nuevo de barbería:**
1. Controlador en `barbershop`, `booking` o `team`.
2. Permisos con `LocationAccessService`.
3. Usuario con `CurrentUser.require`.
4. Respuesta `ApiResponse.ok(msg, Map)` con llaves `snake_case`.
5. Errores con `ApiException`.
6. Agrega la URL en `API_URL.js` del frontend.

**Un rubro nuevo** (por ejemplo `spa`):
1. Crea su paquete (entidades, repos, servicio, controlador `/api/auth{Rubro}/...` y su flujo público).
   Sigue el patrón barbería/restaurante: negocio → sucursales → horario → catálogo.
2. Escribe las migraciones `V{n}` en **ambas** carpetas.
3. En `AuthService.buildBusinessList` (y `buildBusinessMap`), agrega su bloque con su `rubro` y el conteo de
   sucursales, para que aparezca en "Mis negocios" y el login redirija bien. Su `create` y `location/create`
   reciben el id del negocio (un dueño puede tener varios del mismo rubro).
4. Frontend: agrega el rubro en `shared/businesses.js` (`RUBRO_INFO`: panel y ruta para nueva sucursal),
   `chooseRubro.jsx` / `businessOnboarding.jsx`, y crea `pages/verticals/{rubro}/`.
5. Reutiliza `booking` solo si el modelo realmente es "servicio con persona en un horario". Si no, crea su
   propio flujo; no fuerces el rubro dentro de las tablas de barbería.

**Un estado o valor de enum nuevo:** migración PostgreSQL (`ALTER TYPE`) más migración SQLite (reconstruir
la tabla por el CHECK), y actualiza el enum Java.

---

## 9. Pendientes y deuda conocida

**Alta prioridad**
- **Carrera en reservas:** `BookingService.createAppointment` (`@Transactional`) revisa
  `existsOverlapForStaffUser` / `existsOverlapForLocation` y luego inserta, sin lock. Dos peticiones simultáneas
  al mismo horario pueden crear dos citas. Opciones: `@Lock(PESSIMISTIC_WRITE)` sobre la fila del barbero o de la
  sucursal antes de validar, o una restricción de exclusión en PostgreSQL (`EXCLUDE USING gist` con `tsrange`).
  También aplica a `reschedule`.
- **Freno a fuerza bruta en memoria** (`LoginAttemptService`): por correo, no por IP; se reinicia al reiniciar y no
  se comparte entre instancias. Con más de una instancia, moverlo a la BD o a Redis.
- **Sin recuperación de contraseña** (falta SMTP): quien la olvida no puede entrar.
- **Secreto por default** en `application-prod.yml` / `Dockerfile` (password de BD).
- `recent-sales`, `upcoming-appointments`, `top-services`, `top-staff` y `clients` usan `requireOwnerOrStaff` aunque
  la tabla de §5.2 dice "Dueño": un barbero puede leer las ventas y la lista de clientes (con teléfonos) de su
  sucursal llamando la API directo. Pendiente de decidir con Emilio.
- `RestaurantService` usa `LocalDate.now()` / `LocalDateTime.now()` (líneas ~312 y ~355) en vez de `BusinessClock`.

**Resto**

- `RestaurantService.getRestaurant(id)` no valida que quien consulta sea el dueño (en barbería ya se corrigió).
- No hay pruebas de integración. El primer candidato es el flujo de citas (horarios, empalme por persona y asistencia).
- Fotos y productos en disco local no sirven en prod (contenedor efímero): falta S3 o similar.
- Las métricas y listados hacen N+1 consultas (lazy loading). Bien para el volumen actual; revisa con `JOIN FETCH` si crece.
- Los archivos del repo usan finales de línea mixtos (CRLF/LF; Windows con `autocrlf`). No reformatees
  archivos completos sin necesidad.
- Lombok y `boolean isActive`: el getter es `isActive()` y el setter `setActive()`. Jackson lo vería como
  `active`, otra razón para responder con mapas y no con entidades. En DTOs de entrada usa
  `@JsonProperty("is_active") Boolean isActive`.
