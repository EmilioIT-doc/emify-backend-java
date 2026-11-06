package com.emify.security.csrf;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security carga el token CSRF de forma diferida: si nadie lo pide, no escribe la cookie XSRF-TOKEN.
 * Este filtro lo pide en cada petición para que el frontend siempre tenga la cookie desde su primera llamada
 * (por ejemplo GET /api/auth/me al abrir la app) y pueda mandar el header en el siguiente POST.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
