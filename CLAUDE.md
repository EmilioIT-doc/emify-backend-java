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
  Flyway 12), Lombok, JJWT 0.12, Spring Kafka.
- Correr en local, sin instalar nada: `mvnw.cmd spring-boot:run` (Windows) o `./mvnw spring-boot:run`.
  API en `http://localhost:8080/api`.
- No hay pruebas automatizadas reales (solo `contextLoads`). Si agregas lógica de negocio importante,
  agrega pruebas.
- Compilar sin correr: `mvnw.cmd -q compile`. Empaquetar: `mvnw.cmd clean package -DskipTests`.
- **Docker** (`Dockerfile`, multi-stage): `maven:3.9.6-eclipse-temurin-21` compila con `-DskipTests` y
  `eclipse-temurin:21-jre-jammy` corre `app.jar` en el puerto 8080 con `SPRING_PROFILES_ACTIVE=prod`.
  ⚠️ Trae defaults inseguros (`DB_PASSWORD=postgres123`, `JWT_SECRET=...`): en prod deben sobreescribirse.
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

Otras propiedades relevantes de `application.yml`:
- `spring.security.jwt.secret` / `expiration` (`86400000` = **24 h**, sin refresh token).
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
  V6 `appointments.payment_method`.
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
| `auth` | Registro con código por email, login JWT, `/auth/me`, cambio de rol, editar perfil. `auth/dto/ApiResponse` es el sobre de todas las respuestas |
| `user` | `User` (implementa `UserDetails`; roles `client` / `barber` / `owner`) |
| `security` | `SecurityConfig`, `JwtFilter`, `JwtUtil` |
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
  - `/api/auth/**`, `/api/booking/**` y `/api/ordering/**` son públicos.
  - Todo lo demás requiere `Authorization: Bearer <jwt>`, salvo `/uploads/**` y `/error`.
  - `/api/booking/my-appointments` es público en la configuración de seguridad, pero valida la sesión en el controlador.
- **Formato de respuesta:** siempre `ApiResponse` → `{ "status": true|false, "message": "...", "data": ... }`.
  El frontend lee `response.data.data.<campo>`.
- **Llaves en `snake_case`.** Las respuestas se arman a mano con `Map` / `LinkedHashMap`.
  - **Nunca regreses entidades JPA directamente.** Tienen proxies lazy, y además `User` trae el hash del password.
  - Usa `LinkedHashMap` si algún valor puede ser `null` (`Map.of` no acepta nulls).
- **Errores:** lanza `ApiException.notFound(...)` o `ApiException.unprocessable(...)` desde cualquier capa.
  `GlobalExceptionHandler` los convierte a `{status:false, message}`; las validaciones `@Valid` salen como 422
  con el primer mensaje. El código viejo también regresa `ApiResponse.fail(...)` con 200/422; ambos estilos conviven.
- **Usuario logeado:** `CurrentUser.require(authentication)`. Es la entidad `User` puesta por `JwtFilter`
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
| `barbershop_locations` | `BarbershopLocation` | Sucursal: dirección, teléfono, `is_default`, `serves_physical`/`serves_home`, `invite_code` (8), `deleted_at` (borrado lógico) |
| `barbershop_images` | — | enum `barbershop_image_type` (`logo`/`banner`/`gallery`); poco usada |
| `services` | `BarberService` | Catálogo por sucursal: nombre, precio, `duration` (min), `service_type` (`service_type_enum`), `is_active` |
| `products` | `Product` | Productos por sucursal (con imagen) |
| `barbershop_staff` | `BarbershopStaff` | (usuario, sucursal), `staff_role` (siempre `barber`), `is_active` (baja lógica) |
| `staff_services` | — | Relación personal ↔ servicios (sin uso real todavía) |
| `availability` | `Availability` | Horario semanal: `day_of_week` 0=domingo…6, `start_time`, `end_time`; `staff_id NULL` = horario de la sucursal |
| `appointments` | `Appointment` | `location_id`, `service_id`, `staff_id` (nullable), `client_id`, `start_time`, `end_time`, `status`, `payment_method` (V6), `token` (único, público), `reference_image` |
| `restaurants`, `restaurant_locations`, `menu_categories`, `menu_items`, `restaurant_availability`, `restaurant_orders`, `restaurant_order_items` | `restaurant/*` | Rubro restaurante (V2–V4) |

Enums nativos de PostgreSQL: `user_role`, `staff_role` (`barber`/`admin`/`manager`), `appointment_status`
(+ `no_show` en V5), `barbershop_image_type`, `service_type_enum`. `payment_method` es `VARCHAR(20)` + `CHECK`.

## 5.2 Inventario de endpoints

Todos bajo `/api`. 🔓 = público. El resto requiere JWT. "dueño" = `LocationAccessService.requireOwner`.

**`auth`** (`AuthController`, 🔓 salvo `me`, `updateRole`, `updateProfile`, `logout`)
| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/auth/sendVerificationCode` | Genera código de 6 dígitos (en local lo regresa como `dev_code`) |
| POST | `/auth/createUser` | Crea usuario con el código; rol `client`, avatar al azar; regresa token |
| POST | `/auth/login` | Email + password → `{token, user}` |
| POST | `/auth/updateRole` | `owner` o `client` |
| GET | `/auth/me` | Perfil + `businesses` + `workplaces` + `business`/`staff_location` (compatibilidad) |
| POST | `/auth/updateProfile` | name, phone, avatar (validado contra `AVATARS`). El email no se edita |
| POST | `/auth/googleAuth`, `/auth/facebookAuth` | Stub → 503 |
| POST | `/auth/logout` | No-op (JWT sin estado); el front borra la cookie |

**`authBarberShop`** (`BarbershopController`, `ServiceController`, `ProductController`, `BarbershopDashboardController`)
| Método | Ruta | Permiso |
|---|---|---|
| POST | `/authBarberShop/barbershop/create` | Usuario logeado (sube a `owner`) |
| POST | `/authBarberShop/location/create` | Dueño del negocio (`barbershop_id`) |
| GET | `/authBarberShop/barbershop/{id}` | Dueño. Incluye sucursales activas |
| POST | `/authBarberShop/barbershop/{id}/update` | Dueño |
| POST | `/authBarberShop/location/{id}/default` (alias `/set-default`) | Dueño |
| POST | `/authBarberShop/location/{id}/delete` | Dueño (borrado lógico) |
| POST | `/authBarberShop/location/{id}/schedule` | Dueño |
| GET/POST | `/authBarberShop/location/{id}/services` | GET dueño o personal / POST dueño |
| POST | `/authBarberShop/location/{id}/services/{sid}` · `/{sid}/delete` (también `DELETE /{sid}`) | Dueño |
| GET/POST | `/authBarberShop/location/{id}/products` · `/{pid}` · `/{pid}/delete` | Dueño (multipart para imagen) |
| GET | `/authBarberShop/location/{id}/calendar?date=YYYY-MM-DD` | Dueño o personal |
| GET | `/authBarberShop/location/{id}/recent-sales` | Dueño. Sin params: últimos 7 días. Con `?period=&date=YYYY-MM-DD`: `day` = horas del horario de ese día · `week` = lun–dom (`closed` en días que no abre) · `month` = ene–dic del año · `year` = últimos 10 años. `total` = suma de la gráfica; `previous_total` = rango anterior hasta el mismo punto; `future`, `is_current`, `by_payment` |
| GET | `/authBarberShop/location/{id}/upcoming-appointments` | Dueño |
| GET | `/authBarberShop/location/{id}/top-services` · `/top-staff` · `/clients` | Dueño |

**`booking`** (`BookingController`, 🔓)
| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/booking/location/{id}` | Datos públicos: servicios activos, personal activo, horario |
| GET | `/booking/location/{id}/slots?date=&duration=&staff_id=` | Horarios libres |
| POST | `/booking/appointment` | `multipart/form-data` o `x-www-form-urlencoded`. Crea cita (+ usuario guest) |
| GET | `/booking/my-appointments` | Valida sesión en el controlador |
| GET | `/booking/appointment/{token}` | Ver cita |
| POST | `/booking/appointment/{token}/cancel` · `/reschedule` | Solo citas futuras no cerradas |

**`attendance`** (`AttendanceController`): `GET /attendance/pending`, `POST /attendance/{id}` `{attended, payment_method}`.

**`team`** (`TeamController`)
| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/team/location/{id}` | Personal + `invite_code` (solo si es el dueño) |
| POST | `/team/location/{id}/staff` | Crea cuenta "temporal" de barbero |
| POST | `/team/location/{id}/owner` | Dueño atiende aquí; 409 si ya atiende en otra; `{move:true}` para cambiarse |
| GET | `/team/owner/locations` | Dónde atiende el dueño (+ `upcoming_appointments`) |
| POST | `/team/owner/primary` | `{location_id}`: deja al dueño solo en esa sucursal |
| POST | `/team/join` | Unirse con código estando logeado (client → barber) |
| GET | `/team/invite/{code}` | **Público.** Datos de la sucursal del código (nombre, negocio, dirección) |
| POST | `/team/invite/{code}/join` | **Público.** `{name, phone, email, password}`. Correo nuevo → crea cuenta barber (sin verificar correo; reusa al guest con ese teléfono). Correo existente → se une con esa cuenta si la contraseña coincide; si no, **428**. Entra directo al equipo y regresa `{token, location}` |
| POST | `/team/staff/{id}/remove` | Baja lógica |

**`authRestaurant`** y **`ordering`**: ver `RestaurantController` y `RestaurantOrderingController` (fuera de foco).

## 5.3 Seguridad

- `SecurityConfig`: stateless, CSRF apagado, `JwtFilter` antes de `UsernamePasswordAuthenticationFilter`.
  Públicos: `/api/auth/**`, `/api/booking/**`, `/api/ordering/**`, `/api/team/invite/**`, `/uploads/**`, `/error`.
- Sin token o con token vencido/inválido responde **401** `{status:false, message:"Tu sesión expiró…"}` (entry point en `SecurityConfig`); el frontend limpia la cookie y manda a login.
- `JwtUtil`: HMAC-SHA con `spring.security.jwt.secret`; `sub` = email; expira en 24 h.
- `JwtFilter` carga el `User` y lo pone como principal; `CurrentUser.require(auth)` lo obtiene (lanza 401 si falta).
- CORS en `config/CorsConfig` con `allowedOriginPatterns` (no mezclar con `allowedOrigins`: Spring ignora el segundo).
- Passwords con BCrypt. Los usuarios guest no tienen password usable.
- ⚠️ Falta: rate limiting en auth, rotación/revocación de tokens, secretos fuera del repo (ver §9).

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
2. `barbershop/create`. Crea una barbería nueva (ver "Varios negocios por cuenta" para cuándo reutiliza una). Además sube el rol a `owner`.
3. `location/create` con `barbershop_id`. Recibe una lista de sucursales; sin `id` crea y con `id` actualiza. Cada sucursal nueva recibe:
   - un `invite_code` de 8 caracteres;
   - **7 servicios por default** (Corte, Barba, etc.);
   - la primera queda como `is_default`.
4. `location/{id}/schedule`: horario semanal (día 0 = domingo … 6 = sábado, `start_time` < `end_time`).
   Se guarda en `availability` con `staff_id = NULL` (es el horario de la sucursal).
5. "¿Quién atiende?": el dueño elige **una** sucursal donde atiende (`POST /api/team/location/{id}/owner`)
   y puede crear cuentas de barberos en cada sucursal. Si omite el paso, queda solo en la predeterminada.

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
- Al unirse con código, un `client` pasa a `barber`. Al hacer login, el `barber` cae en `/calendar/{slug}`
  (ver `staff_location` en `/auth/me`).

### Reservas (`booking`, públicas)
- La página pública es `/book?id={locationId}`. `GET /booking/location/{id}` regresa servicios activos,
  personal activo y horario.
- **Horarios disponibles** (`/slots`): se generan cada `duration` minutos dentro del horario del día y se quitan
  los que chocan con citas `pending`/`confirmed`, con un **colchón de 10 min** después de cada cita.
  Si se manda `staff_id`, se considera la agenda de **esa persona en todas sus sucursales**.
- **Crear cita:**
  - Valida que el servicio y el barbero pertenezcan a la sucursal, que no sea en el pasado y que caiga dentro del horario.
  - Valida que no haya empalme:
    - **con barbero: por persona (usuario) en todas las sucursales**;
    - sin barbero: contra cualquier cita de la sucursal.
  - El cliente se identifica por teléfono: si no existe, se crea un usuario "guest"
    (`{telefono}@guest.emify.com`, sin password usable).
- Si la sucursal tiene personal, el frontend obliga a elegir barbero.
- Cancelar o reagendar: por `token`, solo citas futuras, no canceladas y no cerradas.
- Fotos de referencia opcionales (multipart `reference_image`): se guardan en disco si `emify.uploads.enabled` está activo.

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
- **Sin rate limiting** en `/auth/login`, `/auth/sendVerificationCode` y `/auth/createUser` (fuerza bruta de
  passwords y de códigos de 6 dígitos). Opción ligera: Bucket4j en memoria por IP + email.
- **Secretos por default** en `application.yml` y `Dockerfile` (JWT, password de BD).
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
