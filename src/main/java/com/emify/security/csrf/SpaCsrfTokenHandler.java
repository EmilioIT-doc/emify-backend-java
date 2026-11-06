package com.emify.security.csrf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * CSRF para una SPA (receta de la documentación de Spring Security): el frontend lee la cookie XSRF-TOKEN y
 * la manda tal cual en el header X-XSRF-TOKEN (axios lo hace solo). Si el token llega en el header se compara
 * en crudo; si llegara como parámetro de un formulario se usa la versión enmascarada (protección BREACH).
 */
public final class SpaCsrfTokenHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        // Enmascarado para cualquier HTML que se llegue a renderizar del lado del servidor
        this.xor.handle(request, response, csrfToken);
        // Fuerza a cargar el token diferido para que la cookie siempre se escriba
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
