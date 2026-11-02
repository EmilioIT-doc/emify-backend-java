package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Endpoints del dashboard de una sucursal (dueño o personal de esa sucursal). */
@RestController
@RequestMapping("/api/authBarberShop/location/{id}")
@RequiredArgsConstructor
public class BarbershopDashboardController {

    private final BarbershopDashboardService dashboardService;

    // GET /api/authBarberShop/location/{id}/calendar?date=2026-11-02
    @GetMapping("/calendar")
    public ResponseEntity<ApiResponse<?>> calendar(@PathVariable Long id,
                                                   @RequestParam(required = false) String date,
                                                   Authentication authentication) {
        return ResponseEntity.ok(dashboardService.getCalendar(CurrentUser.require(authentication), id, date));
    }

    // GET /api/authBarberShop/location/{id}/recent-sales
    @GetMapping("/recent-sales")
    public ResponseEntity<ApiResponse<?>> recentSales(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(dashboardService.getRecentSales(CurrentUser.require(authentication), id));
    }

    // GET /api/authBarberShop/location/{id}/upcoming-appointments
    @GetMapping("/upcoming-appointments")
    public ResponseEntity<ApiResponse<?>> upcoming(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(dashboardService.getUpcomingAppointments(CurrentUser.require(authentication), id));
    }

    // GET /api/authBarberShop/location/{id}/top-services
    @GetMapping("/top-services")
    public ResponseEntity<ApiResponse<?>> topServices(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(dashboardService.getTopServices(CurrentUser.require(authentication), id));
    }

    // GET /api/authBarberShop/location/{id}/top-staff
    @GetMapping("/top-staff")
    public ResponseEntity<ApiResponse<?>> topStaff(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(dashboardService.getTopStaff(CurrentUser.require(authentication), id));
    }

    // GET /api/authBarberShop/location/{id}/clients
    @GetMapping("/clients")
    public ResponseEntity<ApiResponse<?>> clients(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(dashboardService.getClients(CurrentUser.require(authentication), id));
    }
}
