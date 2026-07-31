package com.emify.auth;

import com.emify.auth.dto.*;
import com.emify.security.JwtUtil;
import com.emify.user.User;
import com.emify.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EmailVerificationCodeRepository verificationCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

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

        return ApiResponse.ok("Código de verificación enviado", null);
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

        return ApiResponse.ok("Autenticación exitosa", Map.of(
                "token", token,
                "barbershop_id", "", // TODO: buscar barbershop del owner
                "role", user.getRole().name(),
                "location", "",      // TODO: buscar location del staff
                "user", buildUserMap(user)
        ));
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
    // HELPERS
    // -------------------------------------------------------
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