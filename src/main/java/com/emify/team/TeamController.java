package com.emify.team;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.CurrentUser;
import com.emify.common.RequestValues;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Endpoints de equipo que usa el frontend (API_URL: team, createTempStaff, joinWithCode, removeStaff). */
@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

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
}
