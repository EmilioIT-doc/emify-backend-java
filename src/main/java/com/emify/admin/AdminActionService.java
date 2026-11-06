package com.emify.admin;

import com.emify.barbershop.Barbershop;
import com.emify.barbershop.BarbershopRepository;
import com.emify.common.ApiException;
import com.emify.common.BusinessClock;
import com.emify.restaurant.Restaurant;
import com.emify.restaurant.RestaurantRepository;
import com.emify.security.session.SessionService;
import com.emify.user.User;
import com.emify.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Acciones del super admin que modifican datos. Reglas:
 * - Siempre con motivo (mínimo 5 caracteres) y con la reautenticación vigente (lo revisa el controlador).
 * - Cada una escribe en admin_audit_log el antes y el después DENTRO de la misma transacción: si la bitácora
 *   falla, el cambio se deshace.
 * - Nada se borra: los planes se archivan y los pagos se anulan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminActionService {

    private static final Set<String> RUBROS = Set.of("barbershop", "restaurant");
    private static final Set<String> METHODS = Set.of("clip", "transferencia", "efectivo", "tarjeta", "otro");
    private static final Set<String> PERIODS = Set.of("monthly", "yearly");
    private static final Set<String> SUBSCRIPTION_STATUSES = Set.of("trial", "active", "expired");

    private final UserRepository userRepository;
    private final BarbershopRepository barbershopRepository;
    private final RestaurantRepository restaurantRepository;
    private final PlanRepository planRepository;
    private final SubscriptionPaymentRepository paymentRepository;
    private final SessionService sessionService;
    private final AdminAuditService audit;
    private final AdminQueryService query;
    private final BusinessClock clock;

    // -------------------------------------------------------
    // CUENTAS
    // -------------------------------------------------------
    /** Suspende la cuenta: no puede entrar y se cierran todas sus sesiones. Sus negocios NO se suspenden solos. */
    @Transactional
    public Map<String, Object> suspendUser(AdminContext ctx, Long userId, String reason) {
        String why = requireReason(reason);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        if (user.getId().equals(ctx.user().getId())) throw ApiException.unprocessable("No puedes suspender tu propia cuenta");
        if (user.isSuperAdmin()) throw ApiException.unprocessable("No se puede suspender a un super admin desde aquí");
        if (!user.isActive()) throw ApiException.unprocessable("La cuenta ya está suspendida");

        Map<String, Object> before = AdminQueryService.userSummary(user);
        user.setActive(false);
        user.setSuspendedAt(clock.now());
        user.setSuspensionReason(why);
        userRepository.save(user);
        int closed = sessionService.revokeOthers(user.getId(), null, "USER_SUSPENDED");

        Map<String, Object> after = AdminQueryService.userSummary(user);
        after.put("sessions_closed", closed);
        audit.logChange(ctx, "USER_SUSPEND", "user", user.getId(), why, before, after);
        log.warn("🛡️ {} suspendió la cuenta {} ({} sesiones cerradas)", ctx.user().getEmail(), user.getEmail(), closed);
        return after;
    }

    @Transactional
    public Map<String, Object> reactivateUser(AdminContext ctx, Long userId, String reason) {
        String why = requireReason(reason);
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        if (user.isActive()) throw ApiException.unprocessable("La cuenta ya está activa");

        Map<String, Object> before = AdminQueryService.userSummary(user);
        user.setActive(true);
        user.setSuspendedAt(null);
        user.setSuspensionReason(null);
        userRepository.save(user);

        Map<String, Object> after = AdminQueryService.userSummary(user);
        audit.logChange(ctx, "USER_REACTIVATE", "user", user.getId(), why, before, after);
        return after;
    }

    // -------------------------------------------------------
    // NEGOCIOS
    // -------------------------------------------------------
    /** Suspendido = reservas pausadas y panel en solo lectura (ver LocationAccessService / BookingService). */
    @Transactional
    public Map<String, Object> suspendBusiness(AdminContext ctx, String rubro, Long id, String reason) {
        String why = requireReason(reason);
        if (why.length() > 255) throw ApiException.unprocessable("El motivo puede tener hasta 255 caracteres");
        Business b = business(rubro, id);
        if (b.suspendedAt() != null) throw ApiException.unprocessable("El negocio ya está suspendido");
        Map<String, Object> before = b.summary();
        b.suspend(clock.now(), why, ctx.user().getId());
        Map<String, Object> after = business(rubro, id).summary();
        audit.logChange(ctx, "BUSINESS_SUSPEND", "business", rubro + ":" + id, why, before, after);
        log.warn("🛡️ {} suspendió {} {}", ctx.user().getEmail(), rubro, id);
        return after;
    }

    @Transactional
    public Map<String, Object> reactivateBusiness(AdminContext ctx, String rubro, Long id, String reason) {
        String why = requireReason(reason);
        Business b = business(rubro, id);
        if (b.suspendedAt() == null) throw ApiException.unprocessable("El negocio no está suspendido");
        Map<String, Object> before = b.summary();
        b.suspend(null, null, null);
        Map<String, Object> after = business(rubro, id).summary();
        audit.logChange(ctx, "BUSINESS_REACTIVATE", "business", rubro + ":" + id, why, before, after);
        return after;
    }

    /**
     * Ajuste manual de la suscripción: plan, estado y fechas. Lo que no venga en el body no cambia.
     * body: {plan_id?, clear_plan?, status? (trial|active|expired), starts_at? (YYYY-MM-DD), ends_at? (YYYY-MM-DD), reason}
     */
    @Transactional
    public Map<String, Object> updateSubscription(AdminContext ctx, String rubro, Long id, Map<String, Object> body) {
        String why = requireReason(str(body.get("reason")));
        Business b = business(rubro, id);
        Map<String, Object> before = b.summary();

        Long planId = b.planId();
        if (Boolean.TRUE.equals(body.get("clear_plan"))) {
            planId = null;
        } else if (body.get("plan_id") != null) {
            planId = toLong(body.get("plan_id"));
            Plan plan = planRepository.findById(planId).orElseThrow(() -> ApiException.notFound("Plan no encontrado"));
            if (!plan.isActive() && !plan.getId().equals(b.planId())) throw ApiException.unprocessable("Ese plan está archivado");
        }
        String status = b.status();
        if (body.get("status") != null) {
            status = str(body.get("status"));
            if (!SUBSCRIPTION_STATUSES.contains(status)) throw ApiException.unprocessable("Estado inválido: usa trial, active o expired");
        }
        LocalDateTime startsAt = body.containsKey("starts_at") ? dayStart(str(body.get("starts_at"))) : b.startsAt();
        LocalDateTime endsAt = body.containsKey("ends_at") ? dayEnd(str(body.get("ends_at"))) : b.endsAt();
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            throw ApiException.unprocessable("La fecha de fin debe ser después del inicio");
        }
        b.subscription(planId, status, startsAt, endsAt, null);
        Map<String, Object> after = business(rubro, id).summary();
        audit.logChange(ctx, "SUBSCRIPTION_UPDATE", "business", rubro + ":" + id, why, before, after);
        return after;
    }

    // -------------------------------------------------------
    // PLANES
    // -------------------------------------------------------
    @Transactional
    public Map<String, Object> createPlan(AdminContext ctx, Map<String, Object> body) {
        String why = requireReason(str(body.get("reason")));
        String code = str(body.get("code"));
        if (code == null || !code.matches("^[a-z0-9_-]{2,40}$")) {
            throw ApiException.unprocessable("Clave inválida: 2 a 40 letras minúsculas, números, - o _");
        }
        if (planRepository.existsByCodeIgnoreCase(code)) throw ApiException.unprocessable("Ya existe un plan con esa clave");
        Plan plan = Plan.builder().code(code).build();
        applyPlanFields(plan, body, true);
        plan = planRepository.save(plan);
        Map<String, Object> after = AdminQueryService.planMap(plan);
        audit.logChange(ctx, "PLAN_CREATE", "plan", plan.getId(), why, null, after);
        return after;
    }

    /** Cambia nombre, descripción, precio, periodo o máximo de sucursales; is_active=false lo archiva. La clave no cambia. */
    @Transactional
    public Map<String, Object> updatePlan(AdminContext ctx, Long id, Map<String, Object> body) {
        String why = requireReason(str(body.get("reason")));
        Plan plan = planRepository.findById(id).orElseThrow(() -> ApiException.notFound("Plan no encontrado"));
        Map<String, Object> before = AdminQueryService.planMap(plan);
        applyPlanFields(plan, body, false);
        if (body.get("is_active") instanceof Boolean active) plan.setActive(active);
        plan = planRepository.save(plan);
        Map<String, Object> after = AdminQueryService.planMap(plan);
        String action = before.get("is_active").equals(after.get("is_active")) ? "PLAN_UPDATE"
                : plan.isActive() ? "PLAN_RESTORE" : "PLAN_ARCHIVE";
        audit.logChange(ctx, action, "plan", plan.getId(), why, before, after);
        return after;
    }

    private void applyPlanFields(Plan plan, Map<String, Object> body, boolean creating) {
        String name = str(body.get("name"));
        if (name != null) {
            if (name.length() > 100) throw ApiException.unprocessable("El nombre puede tener hasta 100 caracteres");
            plan.setName(name);
        } else if (creating) {
            throw ApiException.unprocessable("El nombre es requerido");
        }
        if (body.containsKey("description")) plan.setDescription(str(body.get("description")));
        if (body.get("price") != null) {
            plan.setPrice(money(body.get("price")));
        } else if (creating) {
            throw ApiException.unprocessable("El precio es requerido");
        }
        String period = str(body.get("billing_period"));
        if (period != null) {
            if (!PERIODS.contains(period)) throw ApiException.unprocessable("Periodo inválido: monthly o yearly");
            plan.setBillingPeriod(period);
        } else if (creating) {
            plan.setBillingPeriod("monthly");
        }
        if (body.containsKey("max_locations")) {
            Long max = toLong(body.get("max_locations"));
            if (max != null && max < 1) throw ApiException.unprocessable("El máximo de sucursales debe ser 1 o más (vacío = sin límite)");
            plan.setMaxLocations(max != null ? max.intValue() : null);
        }
    }

    // -------------------------------------------------------
    // PAGOS
    // -------------------------------------------------------
    /**
     * Registra un pago. Si trae period_end, la suscripción queda "active" hasta ese día (si era antes) y,
     * si trae plan_id, el negocio queda con ese plan. Anular un pago NO revierte esto: se ajusta a mano.
     */
    @Transactional
    public Map<String, Object> registerPayment(AdminContext ctx, Map<String, Object> body) {
        String why = str(body.get("reason"));
        String rubro = str(body.get("business_type"));
        Long businessId = toLong(body.get("business_id"));
        if (businessId == null) throw ApiException.unprocessable("Falta el negocio");
        Business b = business(rubro, businessId);

        BigDecimal amount = money(body.get("amount"));
        if (amount.signum() <= 0) throw ApiException.unprocessable("El monto debe ser mayor a 0");
        String method = str(body.get("method"));
        if (method == null || !METHODS.contains(method)) {
            throw ApiException.unprocessable("Método inválido: clip, transferencia, efectivo, tarjeta u otro");
        }
        Long planId = toLong(body.get("plan_id"));
        if (planId != null && !planRepository.existsById(planId)) throw ApiException.notFound("Plan no encontrado");
        LocalDate periodStart = date(str(body.get("period_start")));
        LocalDate periodEnd = date(str(body.get("period_end")));
        if (periodStart != null && periodEnd != null && periodEnd.isBefore(periodStart)) {
            throw ApiException.unprocessable("El periodo termina antes de empezar");
        }
        String paidAtValue = str(body.get("paid_at"));
        LocalDateTime paidAt = paidAtValue == null ? clock.now()
                : paidAtValue.length() == 10 ? date(paidAtValue).atTime(12, 0) : clock.parseClientDateTime(paidAtValue);
        String reference = str(body.get("reference"));
        if (reference != null && reference.length() > 120) throw ApiException.unprocessable("La referencia puede tener hasta 120 caracteres");
        String notes = str(body.get("notes"));
        if (notes != null && notes.length() > 255) throw ApiException.unprocessable("Las notas pueden tener hasta 255 caracteres");

        Map<String, Object> businessBefore = b.summary();
        SubscriptionPayment payment = paymentRepository.save(SubscriptionPayment.builder()
                .businessType(rubro)
                .businessId(businessId)
                .planId(planId)
                .amount(amount)
                .method(method)
                .reference(reference)
                .paidAt(paidAt)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .notes(notes)
                .recordedBy(ctx.user().getId())
                .createdAt(SessionService.now())
                .build());

        if (periodEnd != null) {
            LocalDateTime newEnd = periodEnd.atTime(LocalTime.of(23, 59, 59));
            LocalDateTime end = b.endsAt() != null && b.endsAt().isAfter(newEnd) ? b.endsAt() : newEnd;
            LocalDateTime start = b.startsAt() != null ? b.startsAt() : (periodStart != null ? periodStart.atStartOfDay() : paidAt);
            b.subscription(planId != null ? planId : b.planId(), "active", start, end, String.valueOf(payment.getId()));
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("payment", query.paymentMap(payment));
        after.put("business", business(rubro, businessId).summary());
        audit.logChange(ctx, "PAYMENT_REGISTER", "payment", payment.getId(), why,
                Map.of("business", businessBefore), after);
        return after;
    }

    @Transactional
    public Map<String, Object> voidPayment(AdminContext ctx, Long paymentId, String reason) {
        String why = requireReason(reason);
        if (why.length() > 255) throw ApiException.unprocessable("El motivo puede tener hasta 255 caracteres");
        SubscriptionPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Pago no encontrado"));
        if (SubscriptionPayment.VOID.equals(payment.getStatus())) throw ApiException.unprocessable("El pago ya está anulado");
        Map<String, Object> before = query.paymentMap(payment);
        payment.setStatus(SubscriptionPayment.VOID);
        payment.setVoidedAt(SessionService.now());
        payment.setVoidedBy(ctx.user().getId());
        payment.setVoidReason(why);
        paymentRepository.save(payment);
        Map<String, Object> after = query.paymentMap(payment);
        audit.logChange(ctx, "PAYMENT_VOID", "payment", payment.getId(), why, before, after);
        return after;
    }

    // -------------------------------------------------------
    // NEGOCIO DE CUALQUIER RUBRO
    // -------------------------------------------------------
    /** Barbería o restaurante con los mismos campos de suscripción y suspensión. */
    private interface Business {
        LocalDateTime suspendedAt();

        Long planId();

        String status();

        LocalDateTime startsAt();

        LocalDateTime endsAt();

        void suspend(LocalDateTime at, String reason, Long by);

        void subscription(Long planId, String status, LocalDateTime startsAt, LocalDateTime endsAt, String lastPaymentId);

        Map<String, Object> summary();
    }

    private Business business(String rubro, Long id) {
        if (rubro == null || !RUBROS.contains(rubro)) throw ApiException.unprocessable("Rubro inválido: usa barbershop o restaurant");
        if ("barbershop".equals(rubro)) {
            Barbershop b = barbershopRepository.findById(id).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
            return new Business() {
                public LocalDateTime suspendedAt() { return b.getSuspendedAt(); }
                public Long planId() { return b.getPlanId(); }
                public String status() { return b.getSubscriptionStatus(); }
                public LocalDateTime startsAt() { return b.getSubscriptionStartsAt(); }
                public LocalDateTime endsAt() { return b.getSubscriptionEndsAt(); }

                public void suspend(LocalDateTime at, String reason, Long by) {
                    b.setSuspendedAt(at);
                    b.setSuspensionReason(reason);
                    b.setSuspendedBy(by);
                    barbershopRepository.save(b);
                }

                public void subscription(Long planId, String status, LocalDateTime startsAt, LocalDateTime endsAt, String lastPaymentId) {
                    b.setPlanId(planId);
                    b.setSubscriptionStatus(status);
                    b.setSubscriptionStartsAt(startsAt);
                    b.setSubscriptionEndsAt(endsAt);
                    if (lastPaymentId != null) b.setLastPaymentId(lastPaymentId);
                    barbershopRepository.save(b);
                }

                public Map<String, Object> summary() { return query.barbershopSummary(b); }
            };
        }
        Restaurant r = restaurantRepository.findById(id).orElseThrow(() -> ApiException.notFound("Negocio no encontrado"));
        return new Business() {
            public LocalDateTime suspendedAt() { return r.getSuspendedAt(); }
            public Long planId() { return r.getPlanId(); }
            public String status() { return r.getSubscriptionStatus(); }
            public LocalDateTime startsAt() { return r.getSubscriptionStartsAt(); }
            public LocalDateTime endsAt() { return r.getSubscriptionEndsAt(); }

            public void suspend(LocalDateTime at, String reason, Long by) {
                r.setSuspendedAt(at);
                r.setSuspensionReason(reason);
                r.setSuspendedBy(by);
                restaurantRepository.save(r);
            }

            public void subscription(Long planId, String status, LocalDateTime startsAt, LocalDateTime endsAt, String lastPaymentId) {
                r.setPlanId(planId);
                r.setSubscriptionStatus(status);
                r.setSubscriptionStartsAt(startsAt);
                r.setSubscriptionEndsAt(endsAt);
                restaurantRepository.save(r);
            }

            public Map<String, Object> summary() { return query.restaurantSummary(r); }
        };
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------
    static String requireReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 5) throw ApiException.unprocessable("Escribe el motivo (mínimo 5 caracteres): queda en la bitácora");
        if (r.length() > 500) throw ApiException.unprocessable("El motivo puede tener hasta 500 caracteres");
        return r;
    }

    private static String str(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        String s = str(value);
        if (s == null) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("Número inválido: " + s);
        }
    }

    private static BigDecimal money(Object value) {
        String s = str(value);
        if (s == null) throw ApiException.unprocessable("Falta el monto");
        try {
            BigDecimal amount = new BigDecimal(s.replace(",", "")).setScale(2, java.math.RoundingMode.HALF_UP);
            if (amount.signum() < 0 || amount.compareTo(new BigDecimal("99999999.99")) > 0) {
                throw ApiException.unprocessable("Monto fuera de rango");
            }
            return amount;
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("Monto inválido");
        }
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (Exception e) {
            throw ApiException.unprocessable("Fecha inválida, usa AAAA-MM-DD: " + value.toLowerCase(Locale.ROOT));
        }
    }

    private static LocalDateTime dayStart(String value) {
        LocalDate d = date(value);
        return d != null ? d.atStartOfDay() : null;
    }

    private static LocalDateTime dayEnd(String value) {
        LocalDate d = date(value);
        return d != null ? d.atTime(LocalTime.of(23, 59, 59)) : null;
    }
}
