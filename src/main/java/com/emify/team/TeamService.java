package com.emify.team;

import com.emify.auth.dto.ApiResponse;
import com.emify.barbershop.*;
import com.emify.booking.AppointmentRepository;
import com.emify.common.ApiException;
import com.emify.common.BusinessClock;
import com.emify.user.User;
import com.emify.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Equipo (barberos) de una sucursal — pestaña "Equipo" del dashboard.
 *
 * Roles que espera el frontend (StaffCard): "owner" (el dueño trabajando como barbero),
 * "staff" (se unió con el código de invitación) y "temp" (cuenta creada por el dueño,
 * sin verificar correo). En la BD todos son staff_role = 'barber'; la etiqueta se calcula.
 *
 * Regla del dueño: atiende como barbero en UNA sola sucursal (entre todas las de sus negocios).
 * Si quiere atender en otra, se "cambia" (sale de la anterior). Sus citas ya agendadas en la
 * sucursal que deja se quedan con él; solo ya no le pueden agendar nuevas ahí.
 * El resto del personal sí puede estar en varias sucursales.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final List<String> AVATARS = List.of(
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/chamuel.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/egroj.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/jorge.png",
            "https://emify-media.s3.us-east-2.amazonaws.com/avatars/leandros.png"
    );

    private final LocationAccessService access;
    private final BarbershopLocationRepository locationRepository;
    private final BarbershopStaffRepository staffRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppointmentRepository appointmentRepository;
    private final BusinessClock clock;

    // GET /api/team/location/{id}
    public ApiResponse<?> getTeam(User user, Long locationId) {
        BarbershopLocation location = access.requireOwnerOrStaff(user, locationId);
        boolean isOwner = access.isOwner(user, location);

        List<Map<String, Object>> staff = staffRepository.findByLocationIdAndIsActiveTrueOrderByIdAsc(locationId).stream()
                .map(s -> toMap(s, location))
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("staff", staff);
        // El código de invitación solo lo ve el dueño
        data.put("invite_code", isOwner ? location.getInviteCode() : null);
        return ApiResponse.ok("Equipo", data);
    }

    // POST /api/team/location/{id}/staff — el dueño crea la cuenta del barbero ("temporal")
    @Transactional
    public ApiResponse<?> createTempStaff(User owner, Long locationId, Map<String, String> body) {
        BarbershopLocation location = access.requireOwner(owner, locationId);

        String name = trim(body.get("name"));
        String email = trim(body.get("email"));
        String phone = trim(body.get("phone"));
        String password = body.get("password");

        if (name == null) throw ApiException.unprocessable("El nombre es requerido");
        if (email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw ApiException.unprocessable("Correo inválido");
        }
        if (password == null || password.length() < 6) {
            throw ApiException.unprocessable("La contraseña debe tener al menos 6 caracteres");
        }

        if (userRepository.existsByEmail(email)) {
            throw ApiException.unprocessable(
                    "Ese correo ya tiene una cuenta. Pídele que se una con el código de invitación de la sucursal.");
        }

        User barber = userRepository.save(User.builder()
                .name(name)
                .email(email)
                .phone(phone != null ? phone : "")
                .phoneCode("+52")
                .password(passwordEncoder.encode(password))
                .role(User.Role.barber)
                .isActive(true)
                .emailVerifiedAt(null) // ← así se distingue como "temporal"
                .avatarUrl(AVATARS.get(new Random().nextInt(AVATARS.size())))
                .build());

        BarbershopStaff staff = staffRepository.save(BarbershopStaff.builder()
                .user(barber)
                .location(location)
                .role(BarbershopStaff.Role.barber)
                .isActive(true)
                .build());

        log.info("👤 Personal agregado a la sucursal {}: {}", locationId, email);
        return ApiResponse.ok("Personal agregado", Map.of("staff", toMap(staff, location)));
    }

    // POST /api/team/location/{id}/owner — el dueño atiende en esta sucursal (paso de personal
    // del alta, o si lo omite: queda él como barbero por default). Idempotente.
    @Transactional
    // move=true: si ya atiende en otra sucursal, sale de ella y se queda en esta
    public ApiResponse<?> addOwnerAsStaff(User owner, Long locationId, boolean move) {
        BarbershopLocation location = access.requireOwner(owner, locationId);

        List<BarbershopStaff> others = staffRepository.findActiveOwnerMemberships(owner.getId()).stream()
                .filter(s -> !s.getLocation().getId().equals(locationId))
                .toList();
        if (!others.isEmpty()) {
            if (!move) {
                String names = others.stream().map(s -> s.getLocation().getName()).collect(java.util.stream.Collectors.joining(", "));
                throw new ApiException(HttpStatus.CONFLICT,
                        "Ya atiendes en " + names + ". Solo puedes atender en una sucursal.");
            }
            others.forEach(s -> s.setActive(false));
            staffRepository.saveAll(others);
        }

        BarbershopStaff staff = staffRepository.findByUserIdAndLocationId(owner.getId(), locationId)
                .orElseGet(() -> BarbershopStaff.builder()
                        .user(owner)
                        .location(location)
                        .role(BarbershopStaff.Role.barber)
                        .build());
        staff.setActive(true);
        staffRepository.save(staff);

        return ApiResponse.ok("Ahora atiendes en " + location.getName(), Map.of("staff", toMap(staff, location)));
    }

    // GET /api/team/owner/locations — sucursales donde el dueño atiende hoy (con sus citas por venir)
    @Transactional(readOnly = true)
    public ApiResponse<?> getOwnerLocations(User owner) {
        List<Map<String, Object>> list = staffRepository.findActiveOwnerMemberships(owner.getId()).stream()
                .map(this::ownerMembershipMap)
                .toList();
        return ApiResponse.ok("Sucursales donde atiendes", Map.of("locations", list));
    }

    // POST /api/team/owner/primary   body: {location_id}
    // El dueño elige la sucursal donde atiende; sale de las demás (sus citas ya agendadas se quedan con él).
    @Transactional
    public ApiResponse<?> setOwnerPrimaryLocation(User owner, Long locationId) {
        if (locationId == null) throw ApiException.unprocessable("Elige una sucursal");
        BarbershopLocation location = access.requireOwner(owner, locationId);

        List<String> released = new ArrayList<>();
        for (BarbershopStaff s : staffRepository.findActiveOwnerMemberships(owner.getId())) {
            if (!s.getLocation().getId().equals(locationId)) {
                s.setActive(false);
                staffRepository.save(s);
                released.add(s.getLocation().getName());
            }
        }

        BarbershopStaff staff = staffRepository.findByUserIdAndLocationId(owner.getId(), locationId)
                .orElseGet(() -> BarbershopStaff.builder()
                        .user(owner)
                        .location(location)
                        .role(BarbershopStaff.Role.barber)
                        .build());
        staff.setActive(true);
        staffRepository.save(staff);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("location_id", location.getId());
        data.put("location_name", location.getName());
        data.put("released", released);
        return ApiResponse.ok("Ahora atiendes solo en " + location.getName(), data);
    }

    private Map<String, Object> ownerMembershipMap(BarbershopStaff s) {
        BarbershopLocation l = s.getLocation();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("location_id", l.getId());
        map.put("location_name", l.getName());
        map.put("barbershop_id", l.getBarbershop().getId());
        map.put("barbershop_name", l.getBarbershop().getName());
        map.put("upcoming_appointments", appointmentRepository.countUpcomingForStaff(
                s.getId(), clock.now(), AppointmentRepository.ACTIVE_STATUSES));
        return map;
    }

    // POST /api/team/join — un usuario logeado se une con el código de invitación
    @Transactional
    public ApiResponse<?> joinWithCode(User user, String inviteCode) {
        String code = trim(inviteCode);
        if (code == null) throw ApiException.unprocessable("Ingresa el código de invitación");

        BarbershopLocation location = locationRepository.findByInviteCodeAndDeletedAtIsNull(code.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.unprocessable("Código inválido o expirado"));

        BarbershopStaff staff = staffRepository.findByUserIdAndLocationId(user.getId(), location.getId()).orElse(null);
        if (staff != null && staff.isActive()) {
            throw ApiException.unprocessable("Ya eres parte del equipo de esta sucursal");
        }
        // El dueño solo atiende en una de sus sucursales (se cambia desde la pestaña Equipo)
        if (access.isOwner(user, location) && !staffRepository.findActiveOwnerMemberships(user.getId()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ya atiendes en otra de tus sucursales. Cámbiate desde la pestaña Equipo.");
        }
        if (staff == null) {
            staff = BarbershopStaff.builder()
                    .user(user)
                    .location(location)
                    .role(BarbershopStaff.Role.barber)
                    .build();
        }
        staff.setActive(true);
        staffRepository.save(staff);

        // Un cliente que se une a un equipo pasa a ser barbero (el dueño se queda como owner)
        if (user.getRole() == User.Role.client) {
            user.setRole(User.Role.barber);
            userRepository.save(user);
        }

        return ApiResponse.ok("Te uniste a " + location.getName(), Map.of(
                "location", Map.of(
                        "id", location.getId(),
                        "name", location.getName(),
                        "barbershop_id", location.getBarbershop().getId()
                )
        ));
    }

    // POST /api/team/staff/{id}/remove — baja lógica (borrarlo eliminaría sus citas por el CASCADE)
    @Transactional
    public ApiResponse<?> removeStaff(User owner, Long staffId) {
        BarbershopStaff staff = staffRepository.findById(staffId)
                .orElseThrow(() -> ApiException.notFound("Personal no encontrado"));
        access.requireOwner(owner, staff.getLocation().getId());

        staff.setActive(false);
        staffRepository.save(staff);
        return ApiResponse.ok("Personal eliminado del equipo", null);
    }

    private Map<String, Object> toMap(BarbershopStaff s, BarbershopLocation location) {
        User u = s.getUser();
        String role;
        if (u.getId().equals(location.getBarbershop().getOwner().getId())) {
            role = "owner";
        } else if (u.getEmailVerifiedAt() == null) {
            role = "temp";
        } else {
            role = "staff";
        }

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", u.getId());
        user.put("name", u.getName());
        user.put("email", u.getEmail());
        user.put("phone", u.getPhone());
        user.put("avatar_url", u.getAvatarUrl());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("role", role);
        map.put("is_active", s.isActive());
        map.put("user", user);
        return map;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
