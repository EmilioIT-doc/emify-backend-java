package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.CurrentUser;
import com.emify.user.User;
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

    // GET /api/authBarberShop/location/{id}/recent-sales                         → últimos 7 días (formato original)
    // GET /api/authBarberShop/location/{id}/recent-sales?period=day|week|month|year&date=YYYY-MM-DD
    //     → el periodo de calendario que contiene "date" (default hoy), comparado con el periodo anterior
    @GetMapping("/recent-sales")
    public ResponseEntity<ApiResponse<?>> recentSales(@PathVariable Long id,
                                                      @RequestParam(required = false) String period,
                                                      @RequestParam(required = false) String date,
                                                      Authentication authentication) {
        User user = CurrentUser.require(authentication);
        if (period == null || period.isBlank()) {
            return ResponseEntity.ok(dashboardService.getRecentSales(user, id));
        }
        return ResponseEntity.ok(dashboardService.getSales(user, id, period, date));
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
