package com.emify.team;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.CurrentUser;
import com.emify.common.RequestValues;
import com.emify.security.session.AuthEventService;
import com.emify.security.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Endpoints de equipo que usa el frontend (API_URL: team, createTempStaff, joinWithCode, removeStaff). */
@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final SessionService sessionService;
    private final CsrfTokenRepository csrfTokenRepository;

    // GET /api/team/location/{id}
    @GetMapping("/location/{id}")
    public ResponseEntity<ApiResponse<?>> getTeam(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(teamService.getTeam(CurrentUser.require(authentication), id));
    }

    // POST /api/team/location/{id}/staff   body: {name, email, phone, password}
    @PostMapping("/location/{id}/staff")
    public ResponseEntity<ApiResponse<?>> createTempStaff(@PathVariable Long id,
                                                          @RequestBody Map<String, String> body,
                                                          Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.createTempStaff(CurrentUser.require(authentication), id, body));
    }

    // POST /api/team/location/{id}/owner   body opcional: {move: true}
    // El dueño se registra como barbero de la sucursal. Solo puede atender en una:
    // si ya atiende en otra responde 409, y con move=true se cambia a esta.
    @PostMapping("/location/{id}/owner")
    public ResponseEntity<ApiResponse<?>> addOwner(@PathVariable Long id,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   Authentication authentication) {
        boolean move = body != null && Boolean.TRUE.equals(body.get("move"));
        return ResponseEntity.ok(teamService.addOwnerAsStaff(CurrentUser.require(authentication), id, move));
    }

    // GET /api/team/owner/locations — dónde atiende hoy el dueño (con citas por venir en cada una)
    @GetMapping("/owner/locations")
    public ResponseEntity<ApiResponse<?>> ownerLocations(Authentication authentication) {
        return ResponseEntity.ok(teamService.getOwnerLocations(CurrentUser.require(authentication)));
    }

    // POST /api/team/owner/primary   body: {location_id} — el dueño elige su sucursal y sale de las demás
    @PostMapping("/owner/primary")
    public ResponseEntity<ApiResponse<?>> ownerPrimary(@RequestBody Map<String, Object> body, Authentication authentication) {
        return ResponseEntity.ok(teamService.setOwnerPrimaryLocation(
                CurrentUser.require(authentication), RequestValues.toLong(body.get("location_id"))));
    }

    // GET /api/team/invite/{code}  (público) — a qué negocio y sucursal pertenece el código
    @GetMapping("/invite/{code}")
    public ResponseEntity<ApiResponse<?>> invite(@PathVariable String code) {
        return ResponseEntity.ok(teamService.getInvite(code));
    }

    // POST /api/team/invite/{code}/join  (público)  body: {name, phone, email, password}
    // Crea la cuenta (o usa la que ya existe con ese correo y contraseña), la une a la sucursal
    // y deja la sesión abierta (cookie HttpOnly) para entrar directo a su agenda.
    @PostMapping("/invite/{code}/join")
    public ResponseEntity<ApiResponse<?>> joinByInvite(@PathVariable String code, @RequestBody Map<String, String> body,
                                                       HttpServletRequest request, HttpServletResponse response) {
        TeamService.InviteJoin joined = teamService.joinByInvite(code, body);
        sessionService.start(joined.user(), AuthEventService.JOIN_INVITE, request, response);
        // Token CSRF nuevo, igual que en el login
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        return ResponseEntity.ok(ApiResponse.ok("Te uniste a " + joined.location().get("name"),
                Map.of("location", joined.location())));
    }

    // POST /api/team/join   body: {invite_code}
    @PostMapping("/join")
    public ResponseEntity<ApiResponse<?>> join(@RequestBody Map<String, String> body, Authentication authentication) {
        return ResponseEntity.ok(teamService.joinWithCode(CurrentUser.require(authentication), body.get("invite_code")));
    }

    // POST /api/team/staff/{id}/remove
    @PostMapping("/staff/{id}/remove")
    public ResponseEntity<ApiResponse<?>> remove(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(teamService.removeStaff(CurrentUser.require(authentication), id));
    }

    // POST /api/team/staff/{id}/receptionist   body: {value: true|false} — solo el dueño (una por sucursal)
    @PostMapping("/staff/{id}/receptionist")
    public ResponseEntity<ApiResponse<?>> receptionist(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                       Authentication authentication) {
        return ResponseEntity.ok(teamService.setReceptionist(CurrentUser.require(authentication), id, bool(body.get("value"))));
    }

    // POST /api/team/staff/{id}/bookable   body: {value: true|false} — solo el dueño
    @PostMapping("/staff/{id}/bookable")
    public ResponseEntity<ApiResponse<?>> bookable(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                   Authentication authentication) {
        return ResponseEntity.ok(teamService.setBookable(CurrentUser.require(authentication), id, bool(body.get("value"))));
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) return Boolean.valueOf(s);
        return null;
    }
}
