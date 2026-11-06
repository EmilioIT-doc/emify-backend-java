package com.emify.security;

import com.emify.security.csrf.CsrfCookieFilter;
import com.emify.security.csrf.SpaCsrfTokenHandler;
import com.emify.security.session.SessionAuthenticationFilter;
import com.emify.security.session.SessionFailure;
import com.emify.security.session.SessionProperties;
import com.emify.security.session.SessionService;
import com.emify.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.cors.CorsConfigurationSource;

import java.io.IOException;

/**
 * Seguridad de la API.
 * - Sesión: cookie HttpOnly con token aleatorio validado contra user_sessions en cada petición
 *   (SessionAuthenticationFilter). Sin JWT y sin HttpSession de Tomcat (STATELESS).
 * - CSRF: cookie XSRF-TOKEN legible por JavaScript + header X-XSRF-TOKEN en cada POST/PUT/DELETE
 *   (axios lo manda solo). Se suma a SameSite=Lax de la cookie de sesión.
 * - Errores: 401 {code: UNAUTHENTICATED | SESSION_EXPIRED | SESSION_REVOKED} y 403 {code: CSRF_INVALID | FORBIDDEN}.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SessionProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    /** bcrypt costo 12 (~250 ms por intento). Los hashes viejos (costo 10) se actualizan solos al iniciar sesión. */
    public static final int BCRYPT_STRENGTH = 12;

    private final CorsConfigurationSource corsConfigurationSource;
    private final SessionService sessionService;
    private final UserRepository userRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenHandler()))
                .authorizeHttpRequests(auth -> auth
                        // Rutas de /api/auth que SÍ requieren sesión (van antes del permitAll de /api/auth/**)
                        .requestMatchers("/api/auth/me", "/api/auth/session", "/api/auth/sessions", "/api/auth/sessions/**",
                                "/api/auth/activity", "/api/auth/password", "/api/auth/updateRole", "/api/auth/updateProfile")
                        .authenticated()
                        .requestMatchers("/error").permitAll()          // si no, cualquier error se volvía 403
                        .requestMatchers("/uploads/**").permitAll()     // imágenes locales (FileStorageService)
                        .requestMatchers("/api/auth/**").permitAll()    // login, registro, logout, csrf
                        .requestMatchers("/api/booking/**").permitAll()
                        .requestMatchers("/api/ordering/**").permitAll()
                        .requestMatchers("/api/team/invite/**").permitAll() // unirse a un negocio con código, sin sesión
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e
                        // Sin sesión, o con una cookie que ya no sirve → 401 explicando por qué
                        .authenticationEntryPoint((request, response, ex) -> {
                            SessionFailure failure = request.getAttribute(SessionService.REQUEST_FAILURE) instanceof SessionFailure f ? f : null;
                            if (failure != null) {
                                writeJson(response, 401, failure.code(), failure.reason(), failure.message());
                            } else {
                                writeJson(response, 401, "UNAUTHENTICATED", null, "Inicia sesión para continuar.");
                            }
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            if (ex instanceof CsrfException) {
                                writeJson(response, 403, "CSRF_INVALID", null,
                                        "Tu página se quedó sin el permiso de seguridad. Vuelve a intentarlo.");
                            } else {
                                writeJson(response, 403, "FORBIDDEN", null, "No tienes permiso para hacer esto.");
                            }
                        }))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // El logout es /api/auth/logout (AuthController), no el /logout por default de Spring
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new SessionAuthenticationFilter(sessionService, userRepository), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }

    /** Cookie XSRF-TOKEN: legible por JavaScript (para mandarla en el header), Secure en prod, SameSite=Lax. */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(SessionProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path("/"));
        return repository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    private static void writeJson(HttpServletResponse response, int status, String code, String reason, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        StringBuilder json = new StringBuilder("{\"status\":false,\"code\":").append(quote(code));
        if (reason != null) json.append(",\"reason\":").append(quote(reason));
        json.append(",\"message\":").append(quote(message)).append(",\"data\":null}");
        response.getWriter().write(json.toString());
    }

    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
