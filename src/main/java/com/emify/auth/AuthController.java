package com.emify.auth;

import com.emify.auth.dto.*;
import com.emify.common.CurrentUser;
import com.emify.security.session.AuthEventService;
import com.emify.security.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

/**
 * Registro, login, logout y perfil. Login y registro abren la sesión con una cookie HttpOnly (SessionService);
 * la respuesta ya no trae token. Las rutas de sesiones, actividad y contraseña están en AccountSecurityController.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final AuthEventService authEvents;
    private final CsrfTokenRepository csrfTokenRepository;

    // POST /api/auth/sendVerificationCode
    @PostMapping("/sendVerificationCode")
    public ResponseEntity<ApiResponse<?>> sendVerificationCode(@RequestBody SendVerificationCodeRequest request) {
        ApiResponse<?> response = authService.sendVerificationCode(request.getEmail());
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/createUser — crea la cuenta y deja la sesión abierta (cookie)
    @PostMapping("/createUser")
    public ResponseEntity<ApiResponse<?>> createUser(@Valid @RequestBody CreateUserRequest request,
                                                     HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        ApiResponse<?> response = authService.createUser(request, httpRequest, httpResponse);
        if (!response.isStatus()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }
        rotateCsrfToken(httpRequest, httpResponse);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // POST /api/auth/login — 401 {code: INVALID_CREDENTIALS} · 429 {code: TOO_MANY_ATTEMPTS}
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        ApiResponse<?> response = authService.login(request, httpRequest, httpResponse);
        rotateCsrfToken(httpRequest, httpResponse);
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/logout — cierra la sesión de este dispositivo (en el servidor, no solo en el navegador)
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<?>> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        sessionService.end(httpRequest, httpResponse, "LOGOUT")
                .ifPresent(s -> authEvents.log(s.getUserId(), s.getId(), AuthEventService.LOGOUT, httpRequest, null));
        rotateCsrfToken(httpRequest, httpResponse);
        return ResponseEntity.ok(ApiResponse.ok("Sesión cerrada", null));
    }

    // GET /api/auth/csrf — solo asegura que el navegador tenga la cookie XSRF-TOKEN (la escribe CsrfCookieFilter)
    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<?>> csrf() {
        return ResponseEntity.ok(ApiResponse.ok("ok", null));
    }

    // POST /api/auth/updateRole  (con sesión)
    @PostMapping("/updateRole")
    public ResponseEntity<ApiResponse<?>> updateRole(@Valid @RequestBody UpdateRoleRequest request, Authentication authentication) {
        return ResponseEntity.ok(authService.updateRole(CurrentUser.require(authentication), request.getRole()));
    }

    // GET /api/auth/me  (con sesión) — perfil del usuario logeado
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<?>> me(Authentication authentication) {
        return ResponseEntity.ok(authService.getMe(CurrentUser.require(authentication)));
    }

    // POST /api/auth/updateProfile  (con sesión) — nombre, teléfono, avatar. El email nunca se toca.
    @PostMapping("/updateProfile")
    public ResponseEntity<ApiResponse<?>> updateProfile(@Valid @RequestBody UpdateProfileRequest request,
                                                        Authentication authentication) {
        ApiResponse<?> response = authService.updateProfile(CurrentUser.require(authentication), request);
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

    /**
     * Token CSRF nuevo al entrar o salir: un token que existía antes del login (por ejemplo, plantado en el
     * navegador) deja de servir. La cookie nueva viaja en esta misma respuesta.
     */
    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken fresh = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(fresh, request, response);
    }
}
