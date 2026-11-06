package com.emify.security.session;

import com.emify.user.User;
import com.emify.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lee la cookie de sesión en cada petición y, si es válida, pone al usuario como autenticado.
 * Si la cookie no sirve (vencida, revocada, desconocida) la petición sigue como anónima y se guarda el motivo
 * para que el 401 se lo explique al usuario. No borra la cookie en ese caso: una respuesta atrasada podría
 * borrar la cookie de un login nuevo hecho en el mismo navegador.
 *
 * No es @Component a propósito: si lo fuera, Spring Boot también lo registraría como filtro global del servlet.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final UserRepository userRepository;

    public SessionAuthenticationFilter(SessionService sessionService, UserRepository userRepository) {
        this.sessionService = sessionService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        SessionService.Resolution resolution = sessionService.resolve(request, response);

        if (resolution.session() != null) {
            UserSession session = resolution.session();
            User user = userRepository.findById(session.getUserId()).orElse(null);
            if (user == null || !user.isEnabled()) {
                sessionService.revoke(session.getUserId(), session.getId(), "USER_DISABLED");
                request.setAttribute(SessionService.REQUEST_FAILURE, SessionFailure.fromReason("USER_DISABLED"));
            } else {
                UsernamePasswordAuthenticationToken authentication =
                        UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                request.setAttribute(SessionService.REQUEST_SESSION, session);
            }
        } else if (resolution.failure() != null) {
            request.setAttribute(SessionService.REQUEST_FAILURE, resolution.failure());
        }

        chain.doFilter(request, response);
    }
}
