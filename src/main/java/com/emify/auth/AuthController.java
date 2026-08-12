package com.emify.auth;

import com.emify.auth.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // POST /api/auth/sendVerificationCode
    @PostMapping("/sendVerificationCode")
    public ResponseEntity<ApiResponse<?>> sendVerificationCode(
            @RequestBody SendVerificationCodeRequest request) {

        ApiResponse<?> response = authService.sendVerificationCode(request.getEmail());

        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/register (createUser)
    @PostMapping("/createUser")
    public ResponseEntity<ApiResponse<?>> createUser(
            @Valid @RequestBody CreateUserRequest request) {

        ApiResponse<?> response = authService.createUser(request);

        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(
            @Valid @RequestBody LoginRequest request) {

        ApiResponse<?> response = authService.login(request);

        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // PUT /api/auth/updateRole  (protegido)
    @PostMapping("/updateRole")
    public ResponseEntity<ApiResponse<?>> updateRole(
            @Valid @RequestBody UpdateRoleRequest request,
            Authentication authentication) {

        ApiResponse<?> response = authService.updateRole(authentication.getName(), request.getRole());
        return ResponseEntity.ok(response);
    }

    // GET /api/auth/me  (protegido) — perfil del usuario logeado
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<?>> me(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("No autenticado"));
        }
        ApiResponse<?> response = authService.getMe(authentication.getName());
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/updateProfile  (protegido) — nombre, teléfono, avatar. El email nunca se toca.
    @PostMapping("/updateProfile")
    public ResponseEntity<ApiResponse<?>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            Authentication authentication) {

        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("No autenticado"));
        }

        ApiResponse<?> response = authService.updateProfile(authentication.getName(), request);

        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/googleAuth  (stub — sin servicio externo)
    @PostMapping("/googleAuth")
    public ResponseEntity<ApiResponse<?>> googleAuth() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail("Google Auth no disponible en entorno local"));
    }

    // POST /api/auth/facebookAuth  (stub — sin servicio externo)
    @PostMapping("/facebookAuth")
    public ResponseEntity<ApiResponse<?>> facebookAuth() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail("Facebook Auth no disponible en entorno local"));
    }

    // POST /api/auth/logout
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<?>> logout() {
        // JWT es stateless — el frontend solo borra el token
        return ResponseEntity.ok(ApiResponse.ok("Sesión cerrada", null));
    }
}