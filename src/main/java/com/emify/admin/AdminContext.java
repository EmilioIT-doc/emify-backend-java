package com.emify.admin;

import com.emify.security.session.UserSession;
import com.emify.user.User;
import jakarta.servlet.http.HttpServletRequest;

/** Quién hace la petición de administración, con qué sesión y desde dónde (para la bitácora). */
public record AdminContext(User user, UserSession session, HttpServletRequest request) {
}
