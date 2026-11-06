package com.emify.common;

import com.emify.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.util.Optional;

/** Usuario logeado (puesto por SessionAuthenticationFilter a partir de la cookie de sesión). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<User> of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof User user ? Optional.of(user) : Optional.empty();
    }

    public static User require(Authentication authentication) {
        return of(authentication).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "No autenticado"));
    }
}
