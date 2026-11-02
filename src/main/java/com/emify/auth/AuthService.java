package com.emify.auth;

import com.emify.auth.dto.*;
import com.emify.barbershop.Barbershop;
import com.emify.barbershop.BarbershopLocation;
import com.emify.barbershop.BarbershopLocationRepository;
import com.emify.barbershop.BarbershopRepository;
import com.emify.barbershop.BarbershopStaff;
import com.emify.barbershop.BarbershopStaffRepository;
import com.emify.restaurant.Restaurant;
import com.emify.restaurant.RestaurantLocationRepository;
import com.emify.restaurant.RestaurantRepository;
import com.emify.security.JwtUtil;
import com.emify.user.User;
import com.emify.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final BarbershopRepository barbershopRepository;
    private final BarbershopStaffRepository barbershopStaffRepository;
    private final RestaurantRepository restaurantRepository;
    private final BarbershopLocationRepository barbershopLocationRepository;
    private final RestaurantLocationRepository restaurantLocationRepository;
    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Value("${emify.dev.expose-verification-code:false}")
    private boolean exposeVerificationCode;

    private static final List<String> AVATARS = List.of(
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/chamuel.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/egroj.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/jorge.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/leandros.png"
    );

    // -------------------------------------------------------
    // SEND VERIFICATION CODE
    // -------------------------------------------------------
    public ApiResponse<?> sendVerificationCode(String email) {
        // Verificar si ya está registrado
        if (userRepository.existsByEmail(email)) {
            return ApiResponse.fail("Este correo ya está registrado");
        }

        // Generar código de 6 dígitos
        String code = String.format("%06d", new Random().nextInt(1000000));

        // Guardar o actualizar en DB (updateOrInsert como Laravel)
        EmailVerificationCode record = verificationCodeRepository
                .findByEmail(email)
                .orElse(EmailVerificationCode.builder().email(email).build());

        record.setCode(code);
        record.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        verificationCodeRepository.save(record);

        // Intentar enviar email — si falla, no importa (dev mode)
        try {
            // TODO: configurar SMTP para producción
            // mailService.sendVerificationCode(email, code);
            log.info("📧 Código de verificación para {}: {}", email, code);
        } catch (Exception e) {
            log.warn("No se pudo enviar el email: {}", e.getMessage());
            // No retornamos error — solo logueamos
        }

        // Solo en local (emify.dev.expose-verification-code): sin SMTP no hay forma de
        // recibir el código, así que se regresa para que el frontend lo muestre.
        return ApiResponse.ok("Código de verificación enviado",
                exposeVerificationCode ? Map.of("dev_code", code) : null);
    }

    // -------------------------------------------------------
    // CREATE USER (register con código)
    // -------------------------------------------------------
    public ApiResponse<?> createUser(CreateUserRequest request) {
        // Verificar código
        EmailVerificationCode record = verificationCodeRepository
                .findByEmailAndCode(request.getEmail(), request.getCode())
                .orElse(null);

        if (record == null) {
            return ApiResponse.fail("Código de verificación inválido");
        }

        if (LocalDateTime.now().isAfter(record.getExpiresAt())) {
            return ApiResponse.fail("El código de verificación ha expirado");
        }

        // Verificar que el email no esté ya registrado
        if (userRepository.existsByEmail(request.getEmail())) {
            return ApiResponse.fail("Este correo ya está registrado");
        }

        // Avatar random
        String avatarUrl = AVATARS.get(new Random().nextInt(AVATARS.size()));

        // Crear usuario
        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .phoneCode(request.getPhoneCode())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.client)
                .isActive(true)
                .emailVerifiedAt(LocalDateTime.now())
                .avatarUrl(avatarUrl)
                .build();

        userRepository.save(user);

        // Eliminar código usado
        verificationCodeRepository.deleteByEmail(request.getEmail());

        // Generar JWT
        String token = generateToken(user);

        return ApiResponse.ok("Usuario registrado exitosamente", Map.of(
                "user", buildUserMap(user),
                "token", token
        ));
    }

    // -------------------------------------------------------
    // LOGIN
    // -------------------------------------------------------
    public ApiResponse<?> login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return ApiResponse.fail("Credenciales incorrectas");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
        } catch (BadCredentialsException e) {
            return ApiResponse.fail("Credenciales incorrectas");
        }

        String token = generateToken(user);

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("role", user.getRole().name());
        putBusinessInfo(response, user);
        response.put("user", buildUserMap(user));

        return ApiResponse.ok("Autenticación exitosa", response);
    }

    // -------------------------------------------------------
    // UPDATE ROLE
    // -------------------------------------------------------
    public ApiResponse<?> updateRole(String email, String role) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

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
    public ApiResponse<?> getMe(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        Map<String, Object> userMap = new HashMap<>(buildUserMap(user));
        putBusinessInfo(userMap, user);

        return ApiResponse.ok("Perfil obtenido", userMap);
    }

    // -------------------------------------------------------
    // UPDATE PROFILE (nombre, teléfono, avatar — el email nunca se toca)
    // -------------------------------------------------------
    public ApiResponse<?> updateProfile(String email, UpdateProfileRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

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
    }

    // Todos los negocios del dueño, de todos los rubros. Puede tener varios del mismo rubro
    // (marcas distintas), cada uno con sus sucursales. Para sumar un rubro nuevo, agrega su bloque.
    private List<Map<String, Object>> buildBusinessList(User user) {
        List<Map<String, Object>> list = new ArrayList<>();

        for (Barbershop b : barbershopRepository.findByOwnerIdOrderByIdAsc(user.getId())) {
            list.add(businessEntry(b.getId(), b.getName(), "barbershop",
                    barbershopLocationRepository.countByBarbershopIdAndDeletedAtIsNull(b.getId())));
        }

        for (Restaurant r : restaurantRepository.findByOwnerIdOrderByIdAsc(user.getId())) {
            list.add(businessEntry(r.getId(), r.getName(), "restaurant",
                    restaurantLocationRepository.countByRestaurantIdAndDeletedAtIsNull(r.getId())));
        }

        return list;
    }

    private static Map<String, Object> businessEntry(Long id, String name, String rubro, long locationsCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("rubro", rubro);
        map.put("locations_count", locationsCount);
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

    private String generateToken(User user) {
        Map<String, Object> claims = Map.of(
                "role", user.getRole().name(),
                "userId", user.getId()
        );
        return jwtUtil.generateToken(user, claims);
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
                "email_verified_at", user.getEmailVerifiedAt() != null ? user.getEmailVerifiedAt().toString() : ""
        );
    }
}