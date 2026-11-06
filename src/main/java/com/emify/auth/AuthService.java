package com.emify.auth;

import com.emify.user.AccountCapabilities;
import com.emify.auth.dto.*;
import com.emify.barbershop.Barbershop;
import com.emify.barbershop.BarbershopLocation;
import com.emify.barbershop.BarbershopLocationRepository;
import com.emify.barbershop.BarbershopRepository;
import com.emify.barbershop.BarbershopStaff;
import com.emify.barbershop.BarbershopStaffRepository;
import com.emify.common.ApiException;
import com.emify.restaurant.Restaurant;
import com.emify.restaurant.RestaurantLocationRepository;
import com.emify.restaurant.RestaurantRepository;
import com.emify.security.LoginAttemptService;
import com.emify.security.PasswordPolicy;
import com.emify.security.session.AuthEventService;
import com.emify.security.session.SessionService;
import com.emify.user.User;
import com.emify.user.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Registro, login y perfil. La sesión (cookie HttpOnly) la abre SessionService: el token nunca viaja en el
 * cuerpo de la respuesta ni lo ve JavaScript.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Intentos permitidos con un mismo código de verificación antes de invalidarlo. */
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final BarbershopRepository barbershopRepository;
    private final BarbershopStaffRepository barbershopStaffRepository;
    private final RestaurantRepository restaurantRepository;
    private final BarbershopLocationRepository barbershopLocationRepository;
    private final RestaurantLocationRepository restaurantLocationRepository;
    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final AuthEventService authEvents;
    private final LoginAttemptService loginAttempts;
    private final AccountCapabilities capabilities;

    @Value("${emify.dev.expose-verification-code:false}")
    private boolean exposeVerificationCode;

    /** Hash de relleno: si el correo no existe igual se calcula bcrypt, para no revelar qué correos tienen cuenta. */
    private String dummyHash;

    private static final List<String> AVATARS = List.of(
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/chamuel.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/egroj.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/jorge.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/leandros.png"
    );

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    // -------------------------------------------------------
    // SEND VERIFICATION CODE
    // -------------------------------------------------------
    public ApiResponse<?> sendVerificationCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (email == null || email.isBlank()) {
            return ApiResponse.fail("Escribe tu correo");
        }
        // Verificar si ya está registrado (el frontend compara este texto exacto)
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return ApiResponse.fail("Este correo ya está registrado");
        }
        // Máximo 1 código por minuto y 5 por hora por correo
        loginAttempts.checkCodeRequest(email);

        // Código de 6 dígitos con un generador seguro (antes era java.util.Random)
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));

        EmailVerificationCode record = verificationCodeRepository
                .findByEmail(email)
                .orElse(EmailVerificationCode.builder().email(email).build());

        record.setCode(code);
        record.setAttempts(0);
        record.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        verificationCodeRepository.save(record);

        // TODO: configurar SMTP para producción (mailService.sendVerificationCode(email, code)).
        // Sin SMTP el código solo existe en el log del servidor y, en local, en la respuesta.
        if (exposeVerificationCode) {
            log.info("Código de verificación para {}: {}", email, code);
        }

        return ApiResponse.ok("Código de verificación enviado",
                exposeVerificationCode ? Map.of("dev_code", code) : null);
    }

    // -------------------------------------------------------
    // CREATE USER (registro con código) → abre la sesión
    // -------------------------------------------------------
    // Sin @Transactional a propósito: los intentos fallidos del código deben quedar guardados aunque se responda error,
    // y el registro de actividad (dentro de la sesión) no debe poder deshacer la cuenta recién creada.
    public ApiResponse<?> createUser(CreateUserRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String email = normalizeEmail(request.getEmail());

        EmailVerificationCode record = verificationCodeRepository.findByEmail(email).orElse(null);
        // Los textos de estos dos errores los compara el frontend (muestra "Reenviar código")
        if (record == null) {
            return ApiResponse.fail("Código de verificación inválido");
        }
        if (LocalDateTime.now().isAfter(record.getExpiresAt())) {
            return ApiResponse.fail("El código de verificación ha expirado");
        }
        boolean codeMatches = request.getCode() != null && MessageDigest.isEqual(
                record.getCode().getBytes(StandardCharsets.UTF_8), request.getCode().getBytes(StandardCharsets.UTF_8));
        if (!codeMatches) {
            // Cada código aguanta 5 intentos; después hay que pedir otro (frena adivinar los 6 dígitos)
            int attempts = record.getAttempts() + 1;
            if (attempts >= MAX_CODE_ATTEMPTS) {
                verificationCodeRepository.deleteByEmail(email);
            } else {
                record.setAttempts(attempts);
                verificationCodeRepository.save(record);
            }
            return ApiResponse.fail("Código de verificación inválido");
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            return ApiResponse.fail("Este correo ya está registrado");
        }
        PasswordPolicy.validate(request.getPassword());

        String avatarUrl = AVATARS.get(RANDOM.nextInt(AVATARS.size()));

        // Toda cuenta nace como "client" (quiere agendar citas). Si luego elige registrar un negocio,
        // /auth/updateRole la marca como "owner"; el tipo de cuenta ya no se sobrescribe después.
        User user = User.builder()
                .name(request.getName().trim())
                .email(email)
                .phone(request.getPhone())
                .phoneCode(request.getPhoneCode())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.client)
                .isActive(true)
                .emailVerifiedAt(LocalDateTime.now())
                .avatarUrl(avatarUrl)
                .build();

        userRepository.save(user);
        verificationCodeRepository.deleteByEmail(email);

        sessionService.start(user, AuthEventService.SIGNUP, httpRequest, httpResponse);

        Map<String, Object> data = new HashMap<>(buildUserMap(user));
        putBusinessInfo(data, user);
        return ApiResponse.ok("Usuario registrado exitosamente", Map.of("user", data));
    }

    // -------------------------------------------------------
    // LOGIN → abre la sesión
    // -------------------------------------------------------
    public ApiResponse<?> login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String email = normalizeEmail(request.getEmail());
        String password = request.getPassword();
        loginAttempts.checkNotBlocked(email);

        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        boolean valid;
        if (user == null) {
            // Mismo trabajo que con un correo real: el tiempo de respuesta no revela si la cuenta existe
            passwordEncoder.matches(password != null ? password : "", dummyHash);
            valid = false;
        } else {
            valid = PasswordPolicy.fitsBcrypt(password) && passwordEncoder.matches(password, user.getPassword());
        }

        if (!valid) {
            boolean blocked = loginAttempts.recordFailure(email);
            if (user != null) {
                authEvents.log(user.getId(), null, blocked ? AuthEventService.LOGIN_BLOCKED : AuthEventService.LOGIN_FAILED,
                        httpRequest, blocked ? "Bloqueo de 15 min por intentos fallidos" : null);
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Correo o contraseña incorrectos");
        }
        if (!user.isEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Tu cuenta está desactivada.");
        }
        loginAttempts.reset(email);

        // Hashes viejos (bcrypt costo 10) se actualizan al costo actual aprovechando que tenemos la contraseña
        try {
            if (passwordEncoder.upgradeEncoding(user.getPassword())) {
                user.setPassword(passwordEncoder.encode(password));
                userRepository.save(user);
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo actualizar el hash del usuario {}: {}", user.getId(), e.getMessage());
        }

        sessionService.start(user, AuthEventService.LOGIN, httpRequest, httpResponse);

        Map<String, Object> response = new HashMap<>();
        response.put("role", user.getRole().name());
        putBusinessInfo(response, user);
        response.put("user", buildUserMap(user));
        return ApiResponse.ok("Autenticación exitosa", response);
    }

    // -------------------------------------------------------
    // UPDATE ROLE (tipo de cuenta elegido al registrarse: client u owner)
    // -------------------------------------------------------
    public ApiResponse<?> updateRole(User current, String role) {
        User user = userRepository.findById(current.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        user.setRole(User.Role.valueOf(role));
        userRepository.save(user);

        return ApiResponse.ok("Role actualizado exitosamente", Map.of(
                "user", buildUserMap(user)
        ));
    }

    // -------------------------------------------------------
    // GET ME (perfil del usuario logeado)
    // -------------------------------------------------------
    @Transactional(readOnly = true)
    public ApiResponse<?> getMe(User current) {
        User user = userRepository.findById(current.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        Map<String, Object> userMap = new HashMap<>(buildUserMap(user));
        putBusinessInfo(userMap, user);

        return ApiResponse.ok("Perfil obtenido", userMap);
    }

    // -------------------------------------------------------
    // UPDATE PROFILE (nombre, teléfono, avatar — el email nunca se toca)
    // -------------------------------------------------------
    public ApiResponse<?> updateProfile(User current, UpdateProfileRequest request) {
        User user = userRepository.findById(current.getId())
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        if (request.getAvatarUrl() != null && !request.getAvatarUrl().isBlank()) {
            if (!AVATARS.contains(request.getAvatarUrl())) {
                return ApiResponse.fail("Avatar inválido");
            }
            user.setAvatarUrl(request.getAvatarUrl());
        }

        user.setName(request.getName());
        user.setPhone(request.getPhone());
        userRepository.save(user);

        Map<String, Object> userMap = new HashMap<>(buildUserMap(user));
        putBusinessInfo(userMap, user);

        return ApiResponse.ok("Perfil actualizado", userMap);
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    /**
     * Negocios del usuario para el front:
     * - business:       el primero (compatibilidad con pantallas viejas)
     * - businesses:     todos los negocios de los que es dueño, uno por rubro (barbería, restaurante...)
     * - staff_location: primera sucursal donde trabaja como personal (compatibilidad)
     * - workplaces:     sucursales de OTROS dueños donde trabaja ("Donde trabajas" en /mis-negocios)
     */
    private void putBusinessInfo(Map<String, Object> target, User user) {
        target.put("business", buildBusinessMap(user));
        target.put("businesses", buildBusinessList(user));
        target.put("staff_location", buildStaffLocationMap(user));
        target.put("workplaces", buildWorkplaces(user));
        // Dueño o quien agenda: sí. Personal de otro dueño: no (ver AccountCapabilities)
        target.put("can_create_business", capabilities.canCreateBusiness(user));
    }

    // Todos los negocios del dueño, de todos los rubros. Puede tener varios del mismo rubro
    // (marcas distintas), cada uno con sus sucursales. Para sumar un rubro nuevo, agrega su bloque.
    private List<Map<String, Object>> buildBusinessList(User user) {
        List<Map<String, Object>> list = new ArrayList<>();

        for (Barbershop b : barbershopRepository.findByOwnerIdOrderByIdAsc(user.getId())) {
            list.add(businessEntry(b.getId(), b.getName(), "barbershop",
                    barbershopLocationRepository.countByBarbershopIdAndDeletedAtIsNull(b.getId()), b.getSuspendedAt()));
        }

        for (Restaurant r : restaurantRepository.findByOwnerIdOrderByIdAsc(user.getId())) {
            list.add(businessEntry(r.getId(), r.getName(), "restaurant",
                    restaurantLocationRepository.countByRestaurantIdAndDeletedAtIsNull(r.getId()), r.getSuspendedAt()));
        }

        return list;
    }

    private static Map<String, Object> businessEntry(Long id, String name, String rubro, long locationsCount,
                                                     LocalDateTime suspendedAt) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("rubro", rubro);
        map.put("locations_count", locationsCount);
        map.put("suspended", suspendedAt != null);
        return map;
    }

    // Sucursales donde el usuario es personal activo en un negocio que NO es suyo
    // (las de su propio negocio ya se ven desde "Mis negocios").
    private List<Map<String, Object>> buildWorkplaces(User user) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BarbershopStaff staff : barbershopStaffRepository.findByUserIdAndIsActiveTrueOrderByIdAsc(user.getId())) {
            BarbershopLocation location = staff.getLocation();
            if (location == null || location.getDeletedAt() != null) continue;
            Barbershop barbershop = location.getBarbershop();
            if (barbershop.getOwner().getId().equals(user.getId())) continue;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("location_id", location.getId());
            map.put("location_name", location.getName());
            map.put("business_id", barbershop.getId());
            map.put("business_name", barbershop.getName());
            map.put("rubro", "barbershop");
            // V11: qué puede hacer en esa sucursal
            map.put("access_level", staff.isReceptionist() ? "receptionist" : "staff");
            map.put("bookable", staff.isBookable());
            map.put("suspended", barbershop.isSuspended());
            list.add(map);
        }
        return list;
    }

    // Compatibilidad: el primer negocio (las pantallas nuevas usan "businesses")
    private Map<String, Object> buildBusinessMap(User user) {
        Barbershop barbershop = barbershopRepository.findByOwnerIdOrderByIdAsc(user.getId()).stream().findFirst().orElse(null);
        if (barbershop != null) {
            return Map.of(
                    "id", barbershop.getId(),
                    "name", barbershop.getName(),
                    "rubro", "barbershop"
            );
        }

        Restaurant restaurant = restaurantRepository.findByOwnerIdOrderByIdAsc(user.getId()).stream().findFirst().orElse(null);
        if (restaurant != null) {
            return Map.of(
                    "id", restaurant.getId(),
                    "name", restaurant.getName(),
                    "rubro", "restaurant"
            );
        }

        return null;
    }

    // Ubicación donde trabaja el usuario como staff (role = barber), si tiene alguna asignada.
    private Map<String, Object> buildStaffLocationMap(User user) {
        BarbershopStaff staff = barbershopStaffRepository.findFirstByUserIdAndIsActiveTrue(user.getId()).orElse(null);
        if (staff == null) return null;

        BarbershopLocation location = staff.getLocation();
        if (location.getDeletedAt() != null) return null;
        return Map.of(
                "id", location.getId(),
                "name", location.getName(),
                "barbershop_id", location.getBarbershop().getId()
        );
    }

    private Map<String, Object> buildUserMap(User user) {
        return Map.of(
                "id", user.getId(),
                "name", user.getName(),
                "email", user.getEmail(),
                "phone", user.getPhone(),
                "phone_code", user.getPhoneCode(),
                "role", user.getRole().name(),
                "is_active", user.isActive(),
                "avatar_url", user.getAvatarUrl() != null ? user.getAvatarUrl() : "",
                "email_verified_at", user.getEmailVerifiedAt() != null ? user.getEmailVerifiedAt().toString() : "",
                // V11: true solo para las cuentas de emify.admin.super-admins (muestra el menú "Administración")
                "is_super_admin", user.isSuperAdmin()
        );
    }
}