package com.emify.admin;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Panel del super admin — /api/admin/**. SecurityConfig ya exige ROLE_SUPER_ADMIN; aquí además {@link AdminGuard}
 * revisa la lista EMIFY_SUPER_ADMINS.
 * - Toda consulta queda en admin_audit_log (VIEW_*), con los filtros usados.
 * - Toda modificación pide motivo, reautenticación vigente (POST /reauth) y guarda antes/después.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminGuard guard;
    private final AdminQueryService query;
    private final AdminActionService actions;
    private final AdminAuditService audit;

    // -------------------------------------------------------
    // SESIÓN DE ADMIN
    // -------------------------------------------------------
    // GET /api/admin/me — ¿soy super admin? ¿hasta cuándo vale mi confirmación de contraseña?
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<?>> me(Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        return ok("Super admin", elevation(ctx));
    }

    // POST /api/admin/reauth   body: {password}
    @PostMapping("/reauth")
    public ResponseEntity<ApiResponse<?>> reauth(@RequestBody Map<String, Object> body, Authentication authentication,
                                                 HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        guard.reauthenticate(ctx, body.get("password") != null ? body.get("password").toString() : null);
        return ok("Listo, puedes hacer cambios", elevation(ctx));
    }

    // -------------------------------------------------------
    // CONSULTAS
    // -------------------------------------------------------
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<?>> overview(Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_OVERVIEW", null, null, null);
        return ok("Métricas", query.overview());
    }

    // GET /api/admin/users?q=&kind=owner|staff|client|super_admin&status=active|suspended&guests=true&page=&size=
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<?>> users(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) String kind,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "false") boolean guests,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_USERS", "user", null, filters("q", q, "kind", kind, "status", status, "guests", guests, "page", page));
        return ok("Usuarios", query.users(q, kind, status, guests, AdminQueryService.PageRequest.of(page, size)));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<?>> user(@PathVariable Long id, Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_USER", "user", id, null);
        return ok("Usuario", query.userDetail(id));
    }

    @GetMapping("/owners")
    public ResponseEntity<ApiResponse<?>> owners(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_OWNERS", "user", null, filters("q", q, "page", page));
        return ok("Propietarios", query.owners(q, AdminQueryService.PageRequest.of(page, size)));
    }

    // GET /api/admin/businesses?q=&rubro=barbershop|restaurant&status=active|suspended|expired|trial|paid
    @GetMapping("/businesses")
    public ResponseEntity<ApiResponse<?>> businesses(@RequestParam(required = false) String q,
                                                     @RequestParam(required = false) String rubro,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_BUSINESSES", "business", null, filters("q", q, "rubro", rubro, "status", status, "page", page));
        return ok("Negocios", query.businesses(q, rubro, status, AdminQueryService.PageRequest.of(page, size)));
    }

    @GetMapping("/businesses/{rubro}/{id}")
    public ResponseEntity<ApiResponse<?>> business(@PathVariable String rubro, @PathVariable Long id,
                                                   Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_BUSINESS", "business", rubro + ":" + id, null);
        return ok("Negocio", query.businessDetail(rubro, id));
    }

    @GetMapping("/locations")
    public ResponseEntity<ApiResponse<?>> locations(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_LOCATIONS", "location", null, filters("q", q, "page", page));
        return ok("Sucursales", query.locations(q, AdminQueryService.PageRequest.of(page, size)));
    }

    @GetMapping("/staff")
    public ResponseEntity<ApiResponse<?>> staff(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_STAFF", "staff", null, filters("q", q, "page", page));
        return ok("Empleados", query.staff(q, AdminQueryService.PageRequest.of(page, size)));
    }

    @GetMapping("/clients")
    public ResponseEntity<ApiResponse<?>> clients(@RequestParam(required = false) String q,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size,
                                                  Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_CLIENTS", "client", null, filters("q", q, "page", page));
        return ok("Clientes", query.clients(q, AdminQueryService.PageRequest.of(page, size)));
    }

    // GET /api/admin/subscriptions?status=trial|active|expired
    @GetMapping("/subscriptions")
    public ResponseEntity<ApiResponse<?>> subscriptions(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size,
                                                        Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_SUBSCRIPTIONS", "business", null, filters("status", status, "page", page));
        return ok("Suscripciones", query.subscriptions(status, AdminQueryService.PageRequest.of(page, size)));
    }

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<?>> plans(Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_PLANS", "plan", null, null);
        return ok("Planes", Map.of("plans", query.plans()));
    }

    // GET /api/admin/payments?business_type=&business_id=&status=paid|void
    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<?>> payments(@RequestParam(name = "business_type", required = false) String businessType,
                                                   @RequestParam(name = "business_id", required = false) Long businessId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_PAYMENTS", "payment", null,
                filters("business_type", businessType, "business_id", businessId, "status", status, "page", page));
        return ok("Pagos", query.payments(businessType, businessId, status, AdminQueryService.PageRequest.of(page, size)));
    }

    // GET /api/admin/activity?type=LOGIN|SIGNUP|...&user_id=
    @GetMapping("/activity")
    public ResponseEntity<ApiResponse<?>> activity(@RequestParam(required = false) String type,
                                                   @RequestParam(name = "user_id", required = false) Long userId,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_ACTIVITY", "auth_event", null, filters("type", type, "user_id", userId, "page", page));
        return ok("Actividad", query.activity(type, userId, AdminQueryService.PageRequest.of(page, size)));
    }

    // GET /api/admin/audit?action=&target_type=&target_id=&admin_id=
    @GetMapping("/audit")
    public ResponseEntity<ApiResponse<?>> auditLog(@RequestParam(required = false) String action,
                                                   @RequestParam(name = "target_type", required = false) String targetType,
                                                   @RequestParam(name = "target_id", required = false) String targetId,
                                                   @RequestParam(name = "admin_id", required = false) Long adminId,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        audit.logRead(ctx, "VIEW_AUDIT", "audit", null,
                filters("action", action, "target_type", targetType, "target_id", targetId, "admin_id", adminId, "page", page));
        return ok("Bitácora", query.audit(action, targetType, targetId, adminId, AdminQueryService.PageRequest.of(page, size)));
    }

    // -------------------------------------------------------
    // ACCIONES (motivo + reautenticación + bitácora con antes/después)
    // -------------------------------------------------------
    @PostMapping("/users/{id}/suspend")
    public ResponseEntity<ApiResponse<?>> suspendUser(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Cuenta suspendida", attempt(ctx, "USER_SUSPEND", "user", id, () -> actions.suspendUser(ctx, id, reason(body))));
    }

    @PostMapping("/users/{id}/reactivate")
    public ResponseEntity<ApiResponse<?>> reactivateUser(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                         Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Cuenta reactivada", attempt(ctx, "USER_REACTIVATE", "user", id, () -> actions.reactivateUser(ctx, id, reason(body))));
    }

    @PostMapping("/businesses/{rubro}/{id}/suspend")
    public ResponseEntity<ApiResponse<?>> suspendBusiness(@PathVariable String rubro, @PathVariable Long id,
                                                          @RequestBody Map<String, Object> body,
                                                          Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Negocio suspendido", attempt(ctx, "BUSINESS_SUSPEND", "business", rubro + ":" + id, () -> actions.suspendBusiness(ctx, rubro, id, reason(body))));
    }

    @PostMapping("/businesses/{rubro}/{id}/reactivate")
    public ResponseEntity<ApiResponse<?>> reactivateBusiness(@PathVariable String rubro, @PathVariable Long id,
                                                             @RequestBody Map<String, Object> body,
                                                             Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Negocio reactivado", attempt(ctx, "BUSINESS_REACTIVATE", "business", rubro + ":" + id, () -> actions.reactivateBusiness(ctx, rubro, id, reason(body))));
    }

    // body: {plan_id?, clear_plan?, status?, starts_at?, ends_at?, reason}
    @PostMapping("/businesses/{rubro}/{id}/subscription")
    public ResponseEntity<ApiResponse<?>> subscription(@PathVariable String rubro, @PathVariable Long id,
                                                       @RequestBody Map<String, Object> body,
                                                       Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Suscripción actualizada", attempt(ctx, "SUBSCRIPTION_UPDATE", "business", rubro + ":" + id, () -> actions.updateSubscription(ctx, rubro, id, body)));
    }

    // body: {code, name, description?, price, billing_period, max_locations?, reason}
    @PostMapping("/plans")
    public ResponseEntity<ApiResponse<?>> createPlan(@RequestBody Map<String, Object> body,
                                                     Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Plan creado", attempt(ctx, "PLAN_CREATE", "plan", null, () -> actions.createPlan(ctx, body)));
    }

    // body: {name?, description?, price?, billing_period?, max_locations?, is_active?, reason}
    @PostMapping("/plans/{id}")
    public ResponseEntity<ApiResponse<?>> updatePlan(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                     Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Plan actualizado", attempt(ctx, "PLAN_UPDATE", "plan", id, () -> actions.updatePlan(ctx, id, body)));
    }

    // body: {business_type, business_id, amount, method, plan_id?, reference?, paid_at?, period_start?, period_end?, notes?, reason?}
    @PostMapping("/payments")
    public ResponseEntity<ApiResponse<?>> registerPayment(@RequestBody Map<String, Object> body,
                                                          Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Pago registrado", attempt(ctx, "PAYMENT_REGISTER", "payment", null, () -> actions.registerPayment(ctx, body)));
    }

    @PostMapping("/payments/{id}/void")
    public ResponseEntity<ApiResponse<?>> voidPayment(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = elevated(authentication, request);
        return ok("Pago anulado", attempt(ctx, "PAYMENT_VOID", "payment", id, () -> actions.voidPayment(ctx, id, reason(body))));
    }

    // -------------------------------------------------------
    /** Admin con reautenticación vigente; si no la tiene, el intento también queda en la bitácora. */
    private AdminContext elevated(Authentication authentication, HttpServletRequest request) {
        AdminContext ctx = guard.require(authentication, request);
        try {
            return guard.requireElevated(authentication, request);
        } catch (ApiException e) {
            audit.logAttempt(ctx, "REAUTH_REQUIRED", null, null, request.getMethod() + " " + request.getRequestURI());
            throw e;
        }
    }

    /** Ejecuta la acción; si se rechaza (validación, no encontrado…), deja el intento en la bitácora y responde el error. */
    private Map<String, Object> attempt(AdminContext ctx, String action, String targetType, Object targetId,
                                        Supplier<Map<String, Object>> work) {
        try {
            return work.get();
        } catch (ApiException e) {
            audit.logAttempt(ctx, action + "_REJECTED", targetType, targetId, e.getMessage());
            throw e;
        }
    }

    private Map<String, Object> elevation(AdminContext ctx) {
        LocalDateTime until = guard.elevatedUntil(ctx);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", ctx.user().getId());
        data.put("email", ctx.user().getEmail());
        data.put("name", ctx.user().getName());
        data.put("elevated", until != null);
        data.put("elevated_until", until != null ? until + "Z" : null);
        return data;
    }

    private static String reason(Map<String, Object> body) {
        return body != null && body.get("reason") != null ? body.get("reason").toString() : null;
    }

    private static Map<String, Object> filters(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null && !(value instanceof Boolean b && !b) && !(value instanceof String s && s.isBlank())) {
                map.put((String) keyValues[i], value);
            }
        }
        return map;
    }

    private static ResponseEntity<ApiResponse<?>> ok(String message, Object data) {
        return ResponseEntity.ok(ApiResponse.ok(message, data));
    }
}
