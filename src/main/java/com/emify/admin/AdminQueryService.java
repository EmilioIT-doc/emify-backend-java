package com.emify.admin;

import com.emify.barbershop.Barbershop;
import com.emify.barbershop.BarbershopLocation;
import com.emify.barbershop.BarbershopStaff;
import com.emify.booking.Appointment.AppointmentStatus;
import com.emify.booking.AttendanceService;
import com.emify.common.ApiException;
import com.emify.common.BusinessClock;
import com.emify.restaurant.Restaurant;
import com.emify.restaurant.RestaurantLocation;
import com.emify.security.session.AuthEvent;
import com.emify.security.session.SessionService;
import com.emify.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consultas del super admin (solo lectura). Todo con JPQL para que funcione igual en PostgreSQL y SQLite.
 * Los listados de negocios juntan barberías y restaurantes en memoria: con el volumen actual (decenas o cientos)
 * es lo más simple; con miles habría que paginar en la BD por rubro.
 *
 * Guests: quien reserva sin cuenta queda como usuario con correo @guest.emify.com. No cuentan como "usuarios
 * registrados" pero sí como clientes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminQueryService {

    public static final String GUEST_DOMAIN = "%@guest.emify.com";
    private static final int MAX_PAGE_SIZE = 100;

    private final EntityManager em;
    private final BusinessClock clock;
    private final PlanRepository planRepository;
    private final SubscriptionPaymentRepository paymentRepository;

    public record PageRequest(int page, int size) {
        public static PageRequest of(Integer page, Integer size) {
            int p = page == null || page < 1 ? 1 : page;
            int s = size == null || size < 1 ? 25 : Math.min(size, MAX_PAGE_SIZE);
            return new PageRequest(p, s);
        }

        int offset() {
            return (page - 1) * size;
        }
    }

    // -------------------------------------------------------
    // MÉTRICAS GENERALES
    // -------------------------------------------------------
    public Map<String, Object> overview() {
        LocalDateTime now = clock.now();
        LocalDateTime monthAgo = now.minusDays(30);
        LocalDateTime utcNow = SessionService.now();

        Map<String, Object> users = new LinkedHashMap<>();
        users.put("total", count("select count(u) from User u where u.email not like :g", Map.of("g", GUEST_DOMAIN)));
        users.put("new_30d", count("select count(u) from User u where u.email not like :g and u.createdAt >= :from",
                Map.of("g", GUEST_DOMAIN, "from", monthAgo)));
        users.put("suspended", count("select count(u) from User u where u.isActive = false and u.email not like :g",
                Map.of("g", GUEST_DOMAIN)));
        users.put("owners", ownerIds().size());
        users.put("staff", count("select count(distinct s.user.id) from BarbershopStaff s where s.isActive = true", Map.of()));
        users.put("clients", count("select count(distinct a.user.id) from Appointment a where a.user.phone <> :walkIn",
                Map.of("walkIn", AttendanceService.WALK_IN_PHONE)));
        users.put("active_sessions", count("select count(s) from UserSession s where s.revokedAt is null and s.expiresAt > :now",
                Map.of("now", utcNow)));

        List<Map<String, Object>> businesses = allBusinesses();
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("total", businesses.size());
        biz.put("active", businesses.stream().filter(b -> !(boolean) b.get("suspended")).count());
        biz.put("suspended", businesses.stream().filter(b -> (boolean) b.get("suspended")).count());
        biz.put("barbershops", businesses.stream().filter(b -> "barbershop".equals(b.get("rubro"))).count());
        biz.put("restaurants", businesses.stream().filter(b -> "restaurant".equals(b.get("rubro"))).count());
        biz.put("locations", count("select count(l) from BarbershopLocation l where l.deletedAt is null", Map.of())
                + count("select count(l) from RestaurantLocation l where l.deletedAt is null", Map.of()));

        Map<String, Long> bySubscription = businesses.stream()
                .collect(Collectors.groupingBy(b -> (String) b.get("effective_status"), TreeMap::new, Collectors.counting()));

        LocalDate today = clock.today();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        Map<String, Object> payments = new LinkedHashMap<>();
        payments.put("month_total", sum("select coalesce(sum(p.amount), 0) from SubscriptionPayment p where p.status = 'paid' and p.paidAt >= :from",
                Map.of("from", monthStart)));
        payments.put("month_count", count("select count(p) from SubscriptionPayment p where p.status = 'paid' and p.paidAt >= :from",
                Map.of("from", monthStart)));
        payments.put("last_30d_total", sum("select coalesce(sum(p.amount), 0) from SubscriptionPayment p where p.status = 'paid' and p.paidAt >= :from",
                Map.of("from", monthAgo)));

        Map<String, Object> appointments = new LinkedHashMap<>();
        appointments.put("last_30d", count("select count(a) from Appointment a where a.startTime >= :from and a.startTime < :now",
                Map.of("from", monthAgo, "now", now)));
        appointments.put("completed_30d", count("select count(a) from Appointment a where a.startTime >= :from and a.status = :st",
                Map.of("from", monthAgo, "st", AppointmentStatus.completed)));
        appointments.put("upcoming", count("select count(a) from Appointment a where a.startTime >= :now and a.status in :st",
                Map.of("now", now, "st", List.of(AppointmentStatus.pending, AppointmentStatus.confirmed))));

        // Altas por día (últimos 30 días) para la gráfica
        List<LocalDateTime> created = em.createQuery(
                        "select u.createdAt from User u where u.email not like :g and u.createdAt >= :from", LocalDateTime.class)
                .setParameter("g", GUEST_DOMAIN)
                .setParameter("from", today.minusDays(29).atStartOfDay())
                .getResultList();
        Map<LocalDate, Long> perDay = created.stream().collect(Collectors.groupingBy(LocalDateTime::toLocalDate, Collectors.counting()));
        List<Map<String, Object>> signups = new ArrayList<>();
        for (LocalDate d = today.minusDays(29); !d.isAfter(today); d = d.plusDays(1)) {
            signups.add(map("date", d.toString(), "count", perDay.getOrDefault(d, 0L)));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("users", users);
        data.put("businesses", biz);
        data.put("subscriptions", bySubscription);
        data.put("payments", payments);
        data.put("appointments", appointments);
        data.put("signups_by_day", signups);
        data.put("generated_at", now.toString());
        return data;
    }

    // -------------------------------------------------------
    // USUARIOS / PROPIETARIOS
    // -------------------------------------------------------
    /** kind: owner | staff | client | super_admin | null; status: active | suspended | null. */
    public Map<String, Object> users(String q, String kind, String status, boolean includeGuests, PageRequest page) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (!includeGuests) {
            where.append(" and u.email not like :g");
            params.put("g", GUEST_DOMAIN);
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(u.name) like :q or lower(u.email) like :q or u.phone like :q)");
            params.put("q", like(q));
        }
        if ("active".equals(status)) where.append(" and u.isActive = true");
        if ("suspended".equals(status)) where.append(" and u.isActive = false");
        if (kind != null) {
            switch (kind) {
                case "owner" -> where.append(" and (exists (select b.id from Barbershop b where b.owner.id = u.id)"
                        + " or exists (select r.id from Restaurant r where r.owner.id = u.id))");
                case "staff" -> where.append(" and exists (select s.id from BarbershopStaff s where s.user.id = u.id and s.isActive = true)");
                case "client" -> where.append(" and exists (select a.id from Appointment a where a.user.id = u.id)");
                case "super_admin" -> {
                    where.append(" and u.platformRole = :pr");
                    params.put("pr", User.PLATFORM_SUPER_ADMIN);
                }
                default -> throw ApiException.unprocessable("Tipo de usuario inválido: usa owner, staff, client o super_admin");
            }
        }
        long total = count("select count(u) from User u" + where, params);
        TypedQuery<User> query = em.createQuery("select u from User u" + where + " order by u.id desc", User.class);
        params.forEach(query::setParameter);
        List<User> list = query.setFirstResult(page.offset()).setMaxResults(page.size()).getResultList();

        Set<Long> owners = ownerIds();
        List<Map<String, Object>> items = list.stream().map(u -> {
            Map<String, Object> m = userSummary(u);
            m.put("is_owner", owners.contains(u.getId()));
            m.put("workplaces_count", count("select count(s) from BarbershopStaff s where s.user.id = :id and s.isActive = true",
                    Map.of("id", u.getId())));
            m.put("last_seen_at", em.createQuery("select max(s.lastSeenAt) from UserSession s where s.userId = :id", LocalDateTime.class)
                    .setParameter("id", u.getId()).getSingleResult());
            return m;
        }).toList();
        return pageOf(items, total, page);
    }

    public Map<String, Object> userDetail(Long id) {
        User u = em.find(User.class, id);
        if (u == null) throw ApiException.notFound("Usuario no encontrado");
        Map<String, Object> data = userSummary(u);

        data.put("businesses", allBusinesses().stream()
                .filter(b -> id.equals(((Map<?, ?>) b.get("owner")).get("id")))
                .toList());
        data.put("workplaces", em.createQuery("select s from BarbershopStaff s where s.user.id = :id order by s.id", BarbershopStaff.class)
                .setParameter("id", id).getResultList().stream().map(this::staffSummary).toList());
        data.put("appointments_as_client", count("select count(a) from Appointment a where a.user.id = :id", Map.of("id", id)));
        data.put("active_sessions", count("select count(s) from UserSession s where s.userId = :id and s.revokedAt is null and s.expiresAt > :now",
                Map.of("id", id, "now", SessionService.now())));
        data.put("recent_activity", em.createQuery("select e from AuthEvent e where e.userId = :id order by e.id desc", AuthEvent.class)
                .setParameter("id", id).setMaxResults(15).getResultList().stream().map(e -> authEventMap(e, null)).toList());
        data.put("audit", auditFor("user", id.toString(), 20));
        return data;
    }

    /** Dueños con sus negocios (un renglón por dueño). */
    public Map<String, Object> owners(String q, PageRequest page) {
        Map<Long, List<Map<String, Object>>> byOwner = new LinkedHashMap<>();
        for (Map<String, Object> b : allBusinesses()) {
            Long ownerId = (Long) ((Map<?, ?>) b.get("owner")).get("id");
            byOwner.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(b);
        }
        List<User> owners = byOwner.isEmpty() ? List.of()
                : em.createQuery("select u from User u where u.id in :ids order by u.id desc", User.class)
                .setParameter("ids", byOwner.keySet()).getResultList();
        String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = owners.stream()
                .filter(u -> needle == null || needle.isEmpty() || contains(u.getName(), needle) || contains(u.getEmail(), needle)
                        || byOwner.get(u.getId()).stream().anyMatch(b -> contains((String) b.get("name"), needle)))
                .map(u -> {
                    Map<String, Object> m = userSummary(u);
                    List<Map<String, Object>> list = byOwner.get(u.getId());
                    m.put("businesses", list.stream().map(b -> map("rubro", b.get("rubro"), "id", b.get("id"), "name", b.get("name"),
                            "suspended", b.get("suspended"), "effective_status", b.get("effective_status"))).toList());
                    m.put("locations_count", list.stream().mapToLong(b -> ((Number) b.get("locations_count")).longValue()).sum());
                    return m;
                })
                .toList();
        return slice(rows, page);
    }

    // -------------------------------------------------------
    // NEGOCIOS / SUSCRIPCIONES
    // -------------------------------------------------------
    /** status: active | suspended | expired | trial | paid (suscripción activa) | null; rubro: barbershop | restaurant | null. */
    public Map<String, Object> businesses(String q, String rubro, String status, PageRequest page) {
        String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = allBusinesses().stream()
                .filter(b -> rubro == null || rubro.isBlank() || rubro.equals(b.get("rubro")))
                .filter(b -> matchesStatus(b, status))
                .filter(b -> needle == null || needle.isEmpty() || contains((String) b.get("name"), needle)
                        || contains((String) ((Map<?, ?>) b.get("owner")).get("email"), needle)
                        || contains((String) ((Map<?, ?>) b.get("owner")).get("name"), needle))
                .toList();
        return slice(rows, page);
    }

    public Map<String, Object> subscriptions(String status, PageRequest page) {
        List<Map<String, Object>> rows = allBusinesses().stream()
                .filter(b -> status == null || status.isBlank() || status.equals(b.get("effective_status")))
                .sorted(Comparator.comparing((Map<String, Object> b) -> (String) b.get("subscription_ends_at"),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return slice(rows, page);
    }

    public Map<String, Object> businessDetail(String rubro, Long id) {
        Map<String, Object> data;
        List<Map<String, Object>> locations;
        if ("barbershop".equals(rubro)) {
            Barbershop b = em.find(Barbershop.class, id);
            if (b == null) throw ApiException.notFound("Negocio no encontrado");
            data = barbershopSummary(b);
            data.put("description", b.getDescription());
            data.put("website", b.getWebsite());
            locations = em.createQuery("select l from BarbershopLocation l where l.barbershop.id = :id and l.deletedAt is null order by l.id",
                            BarbershopLocation.class).setParameter("id", id).getResultList().stream()
                    .map(l -> {
                        Map<String, Object> m = barbershopLocationSummary(l);
                        m.put("staff", em.createQuery("select s from BarbershopStaff s where s.location.id = :id and s.isActive = true order by s.id",
                                BarbershopStaff.class).setParameter("id", l.getId()).getResultList().stream().map(this::staffSummary).toList());
                        return m;
                    }).toList();
        } else if ("restaurant".equals(rubro)) {
            Restaurant r = em.find(Restaurant.class, id);
            if (r == null) throw ApiException.notFound("Negocio no encontrado");
            data = restaurantSummary(r);
            data.put("description", r.getDescription());
            data.put("website", r.getWebsite());
            locations = em.createQuery("select l from RestaurantLocation l where l.restaurant.id = :id and l.deletedAt is null order by l.id",
                    RestaurantLocation.class).setParameter("id", id).getResultList().stream().map(this::restaurantLocationSummary).toList();
        } else {
            throw ApiException.unprocessable("Rubro inválido: usa barbershop o restaurant");
        }
        data.put("locations", locations);
        data.put("payments", paymentRepository.findByBusinessTypeAndBusinessIdOrderByPaidAtDesc(rubro, id).stream().map(this::paymentMap).toList());
        data.put("audit", auditFor("business", rubro + ":" + id, 30));
        return data;
    }

    // -------------------------------------------------------
    // SUCURSALES / EMPLEADOS / CLIENTES
    // -------------------------------------------------------
    public Map<String, Object> locations(String q, PageRequest page) {
        String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        em.createQuery("select l from BarbershopLocation l where l.deletedAt is null order by l.id desc", BarbershopLocation.class)
                .getResultList().forEach(l -> rows.add(barbershopLocationSummary(l)));
        em.createQuery("select l from RestaurantLocation l where l.deletedAt is null order by l.id desc", RestaurantLocation.class)
                .getResultList().forEach(l -> rows.add(restaurantLocationSummary(l)));
        rows.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("created_at"), Comparator.nullsLast(Comparator.reverseOrder())));
        return slice(rows.stream()
                .filter(m -> needle == null || needle.isEmpty() || contains((String) m.get("name"), needle)
                        || contains((String) m.get("business_name"), needle) || contains((String) m.get("city"), needle))
                .toList(), page);
    }

    public Map<String, Object> staff(String q, PageRequest page) {
        StringBuilder where = new StringBuilder(" where s.isActive = true and s.location.deletedAt is null");
        Map<String, Object> params = new HashMap<>();
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(s.user.name) like :q or lower(s.user.email) like :q or lower(s.location.name) like :q"
                    + " or lower(s.location.barbershop.name) like :q)");
            params.put("q", like(q));
        }
        long total = count("select count(s) from BarbershopStaff s" + where, params);
        TypedQuery<BarbershopStaff> query = em.createQuery("select s from BarbershopStaff s" + where + " order by s.id desc", BarbershopStaff.class);
        params.forEach(query::setParameter);
        return pageOf(query.setFirstResult(page.offset()).setMaxResults(page.size()).getResultList().stream()
                .map(this::staffSummary).toList(), total, page);
    }

    /** Clientes = personas con al menos una cita (con o sin cuenta). Sin el cliente genérico de imprevistos. */
    public Map<String, Object> clients(String q, PageRequest page) {
        StringBuilder where = new StringBuilder(" where a.user.phone <> :walkIn");
        Map<String, Object> params = new HashMap<>();
        params.put("walkIn", AttendanceService.WALK_IN_PHONE);
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(a.user.name) like :q or lower(a.user.email) like :q or a.user.phone like :q)");
            params.put("q", like(q));
        }
        long total = count("select count(distinct a.user.id) from Appointment a" + where, params);
        TypedQuery<Object[]> query = em.createQuery(
                "select a.user.id, count(a), max(a.startTime), count(distinct a.location.id) from Appointment a" + where
                        + " group by a.user.id order by max(a.startTime) desc", Object[].class);
        params.forEach(query::setParameter);
        List<Object[]> rows = query.setFirstResult(page.offset()).setMaxResults(page.size()).getResultList();
        Map<Long, User> users = byId(rows.stream().map(r -> (Long) r[0]).toList());
        List<Map<String, Object>> items = rows.stream().map(r -> {
            User u = users.get((Long) r[0]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", r[0]);
            m.put("name", u != null ? u.getName() : null);
            m.put("phone", u != null ? u.getPhone() : null);
            m.put("email", u != null && !u.getEmail().endsWith("@guest.emify.com") ? u.getEmail() : null);
            m.put("has_account", u != null && !u.getEmail().endsWith("@guest.emify.com"));
            m.put("appointments", r[1]);
            m.put("last_appointment_at", r[2] != null ? r[2].toString() : null);
            m.put("locations_count", r[3]);
            return m;
        }).toList();
        return pageOf(items, total, page);
    }

    // -------------------------------------------------------
    // PLANES / PAGOS
    // -------------------------------------------------------
    public List<Map<String, Object>> plans() {
        return planRepository.findAllByOrderByActiveDescPriceAsc().stream().map(AdminQueryService::planMap).toList();
    }

    public Map<String, Object> payments(String businessType, Long businessId, String status, PageRequest page) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (businessType != null && !businessType.isBlank()) {
            where.append(" and p.businessType = :bt");
            params.put("bt", businessType);
        }
        if (businessId != null) {
            where.append(" and p.businessId = :bid");
            params.put("bid", businessId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and p.status = :st");
            params.put("st", status);
        }
        long total = count("select count(p) from SubscriptionPayment p" + where, params);
        TypedQuery<SubscriptionPayment> query = em.createQuery("select p from SubscriptionPayment p" + where + " order by p.paidAt desc, p.id desc",
                SubscriptionPayment.class);
        params.forEach(query::setParameter);
        List<SubscriptionPayment> list = query.setFirstResult(page.offset()).setMaxResults(page.size()).getResultList();
        Map<String, String> names = businessNames();
        return pageOf(list.stream().map(p -> {
            Map<String, Object> m = paymentMap(p);
            m.put("business_name", names.get(p.getBusinessType() + ":" + p.getBusinessId()));
            return m;
        }).toList(), total, page);
    }

    // -------------------------------------------------------
    // ACTIVIDAD / AUDITORÍA
    // -------------------------------------------------------
    public Map<String, Object> activity(String type, Long userId, PageRequest page) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (type != null && !type.isBlank()) {
            where.append(" and e.eventType = :t");
            params.put("t", type);
        }
        if (userId != null) {
            where.append(" and e.userId = :u");
            params.put("u", userId);
        }
        long total = count("select count(e) from AuthEvent e" + where, params);
        TypedQuery<AuthEvent> query = em.createQuery("select e from AuthEvent e" + where + " order by e.id desc", AuthEvent.class);
        params.forEach(query::setParameter);
        List<AuthEvent> events = query.setFirstResult(page.offset()).setMaxResults(page.size()).getResultList();
        Map<Long, User> users = byId(events.stream().map(AuthEvent::getUserId).filter(Objects::nonNull).distinct().toList());
        return pageOf(events.stream().map(e -> authEventMap(e, users.get(e.getUserId()))).toList(), total, page);
    }

    public Map<String, Object> audit(String action, String targetType, String targetId, Long adminId, PageRequest page) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (action != null && !action.isBlank()) {
            where.append(" and a.action = :action");
            params.put("action", action);
        }
        if (targetType != null && !targetType.isBlank()) {
            where.append(" and a.targetType = :tt");
            params.put("tt", targetType);
        }
        if (targetId != null && !targetId.isBlank()) {
            where.append(" and a.targetId = :tid");
            params.put("tid", targetId);
        }
        if (adminId != null) {
            where.append(" and a.adminId = :admin");
            params.put("admin", adminId);
        }
        long total = count("select count(a) from AdminAuditLog a" + where, params);
        TypedQuery<AdminAuditLog> query = em.createQuery("select a from AdminAuditLog a" + where + " order by a.id desc", AdminAuditLog.class);
        params.forEach(query::setParameter);
        return pageOf(query.setFirstResult(page.offset()).setMaxResults(page.size()).getResultList().stream()
                .map(AdminQueryService::auditMap).toList(), total, page);
    }

    // -------------------------------------------------------
    // MAPEOS (los usa también AdminActionService para el antes/después)
    // -------------------------------------------------------
    public static Map<String, Object> userSummary(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        m.put("phone", u.getPhone());
        m.put("role", u.getRole() != null ? u.getRole().name() : null);
        m.put("is_active", u.isActive());
        m.put("is_super_admin", u.isSuperAdmin());
        m.put("email_verified", u.getEmailVerifiedAt() != null);
        m.put("suspended_at", str(u.getSuspendedAt()));
        m.put("suspension_reason", u.getSuspensionReason());
        m.put("created_at", str(u.getCreatedAt()));
        return m;
    }

    public Map<String, Object> barbershopSummary(Barbershop b) {
        Map<String, Object> m = businessBase("barbershop", b.getId(), b.getName(), b.getOwner(), b.isActive(), b.getPlanId(),
                b.getSubscriptionStatus(), b.getSubscriptionStartsAt(), b.getSubscriptionEndsAt(), b.getLastPaymentId(),
                b.getSuspendedAt(), b.getSuspensionReason(), b.getSuspendedBy(), b.getCreatedAt());
        m.put("locations_count", count("select count(l) from BarbershopLocation l where l.barbershop.id = :id and l.deletedAt is null",
                Map.of("id", b.getId())));
        m.put("staff_count", count("select count(s) from BarbershopStaff s where s.location.barbershop.id = :id and s.isActive = true"
                + " and s.location.deletedAt is null", Map.of("id", b.getId())));
        return m;
    }

    public Map<String, Object> restaurantSummary(Restaurant r) {
        Map<String, Object> m = businessBase("restaurant", r.getId(), r.getName(), r.getOwner(), r.isActive(), r.getPlanId(),
                r.getSubscriptionStatus(), r.getSubscriptionStartsAt(), r.getSubscriptionEndsAt(), null,
                r.getSuspendedAt(), r.getSuspensionReason(), r.getSuspendedBy(), r.getCreatedAt());
        m.put("locations_count", count("select count(l) from RestaurantLocation l where l.restaurant.id = :id and l.deletedAt is null",
                Map.of("id", r.getId())));
        m.put("staff_count", 0L);
        return m;
    }

    private Map<String, Object> businessBase(String rubro, Long id, String name, User owner, boolean active, Long planId,
                                             String subscriptionStatus, LocalDateTime startsAt, LocalDateTime endsAt,
                                             String lastPaymentId, LocalDateTime suspendedAt, String suspensionReason,
                                             Long suspendedBy, LocalDateTime createdAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rubro", rubro);
        m.put("id", id);
        m.put("key", rubro + ":" + id);
        m.put("name", name);
        m.put("owner", owner == null ? null : map("id", owner.getId(), "name", owner.getName(), "email", owner.getEmail(),
                "phone", owner.getPhone(), "is_active", owner.isActive()));
        m.put("is_active", active);
        Plan plan = planId != null ? planRepository.findById(planId).orElse(null) : null;
        m.put("plan", plan != null ? map("id", plan.getId(), "code", plan.getCode(), "name", plan.getName(), "price", plan.getPrice(),
                "billing_period", plan.getBillingPeriod()) : null);
        m.put("subscription_status", subscriptionStatus);
        m.put("effective_status", effectiveStatus(subscriptionStatus, endsAt, clock.now()));
        m.put("subscription_starts_at", str(startsAt));
        m.put("subscription_ends_at", str(endsAt));
        m.put("last_payment_id", lastPaymentId);
        m.put("suspended", suspendedAt != null);
        m.put("suspended_at", str(suspendedAt));
        m.put("suspension_reason", suspensionReason);
        m.put("suspended_by", suspendedBy);
        m.put("created_at", str(createdAt));
        return m;
    }

    /** trial | active | expired: "expired" si la fecha de fin ya pasó, aunque el estado guardado diga otra cosa. */
    public static String effectiveStatus(String status, LocalDateTime endsAt, LocalDateTime now) {
        if ("expired".equals(status) || (endsAt != null && endsAt.isBefore(now))) return "expired";
        return status == null || status.isBlank() ? "trial" : status;
    }

    private Map<String, Object> barbershopLocationSummary(BarbershopLocation l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rubro", "barbershop");
        m.put("id", l.getId());
        m.put("name", l.getName());
        m.put("address", l.getAddress());
        m.put("city", l.getCity());
        m.put("state", l.getState());
        m.put("phone", l.getPhone());
        m.put("is_default", l.isDefault());
        m.put("business_id", l.getBarbershop().getId());
        m.put("business_name", l.getBarbershop().getName());
        m.put("business_suspended", l.getBarbershop().isSuspended());
        m.put("staff_count", count("select count(s) from BarbershopStaff s where s.location.id = :id and s.isActive = true", Map.of("id", l.getId())));
        m.put("created_at", str(l.getCreatedAt()));
        return m;
    }

    private Map<String, Object> restaurantLocationSummary(RestaurantLocation l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rubro", "restaurant");
        m.put("id", l.getId());
        m.put("name", l.getName());
        m.put("address", l.getAddress());
        m.put("city", l.getCity());
        m.put("state", l.getState());
        m.put("phone", l.getPhone());
        m.put("is_default", l.isDefault());
        m.put("business_id", l.getRestaurant().getId());
        m.put("business_name", l.getRestaurant().getName());
        m.put("business_suspended", l.getRestaurant().isSuspended());
        m.put("staff_count", 0L);
        m.put("created_at", str(l.getCreatedAt()));
        return m;
    }

    private Map<String, Object> staffSummary(BarbershopStaff s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("user", map("id", s.getUser().getId(), "name", s.getUser().getName(), "email", s.getUser().getEmail(),
                "phone", s.getUser().getPhone(), "is_active", s.getUser().isActive()));
        boolean owner = s.getLocation().getBarbershop().getOwner().getId().equals(s.getUser().getId());
        m.put("access_level", owner ? "owner" : s.isReceptionist() ? "receptionist" : "staff");
        m.put("is_receptionist", s.isReceptionist());
        m.put("bookable", s.isBookable());
        m.put("is_active", s.isActive());
        m.put("location_id", s.getLocation().getId());
        m.put("location_name", s.getLocation().getName());
        m.put("business_id", s.getLocation().getBarbershop().getId());
        m.put("business_name", s.getLocation().getBarbershop().getName());
        m.put("created_at", str(s.getCreatedAt()));
        return m;
    }

    public static Map<String, Object> planMap(Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("price", p.getPrice());
        m.put("currency", p.getCurrency());
        m.put("billing_period", p.getBillingPeriod());
        m.put("max_locations", p.getMaxLocations());
        m.put("is_active", p.isActive());
        m.put("created_at", str(p.getCreatedAt()));
        m.put("updated_at", str(p.getUpdatedAt()));
        return m;
    }

    public Map<String, Object> paymentMap(SubscriptionPayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("business_type", p.getBusinessType());
        m.put("business_id", p.getBusinessId());
        m.put("plan_id", p.getPlanId());
        m.put("plan_name", p.getPlanId() != null ? planRepository.findById(p.getPlanId()).map(Plan::getName).orElse(null) : null);
        m.put("amount", p.getAmount());
        m.put("currency", p.getCurrency());
        m.put("method", p.getMethod());
        m.put("reference", p.getReference());
        m.put("paid_at", str(p.getPaidAt()));
        m.put("period_start", p.getPeriodStart() != null ? p.getPeriodStart().toString() : null);
        m.put("period_end", p.getPeriodEnd() != null ? p.getPeriodEnd().toString() : null);
        m.put("status", p.getStatus());
        m.put("notes", p.getNotes());
        m.put("recorded_by", p.getRecordedBy());
        m.put("voided_at", utc(p.getVoidedAt()));
        m.put("voided_by", p.getVoidedBy());
        m.put("void_reason", p.getVoidReason());
        m.put("created_at", utc(p.getCreatedAt()));
        return m;
    }

    public static Map<String, Object> auditMap(AdminAuditLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("admin_id", a.getAdminId());
        m.put("admin_email", a.getAdminEmail());
        m.put("action", a.getAction());
        m.put("target_type", a.getTargetType());
        m.put("target_id", a.getTargetId());
        m.put("reason", a.getReason());
        m.put("before", a.getBeforeData());
        m.put("after", a.getAfterData());
        m.put("ip_address", a.getIpAddress());
        m.put("user_agent", a.getUserAgent());
        m.put("session_id", a.getSessionId());
        m.put("created_at", utc(a.getCreatedAt()));
        return m;
    }

    private static Map<String, Object> authEventMap(AuthEvent e, User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("type", e.getEventType());
        m.put("user_id", e.getUserId());
        m.put("user_name", user != null ? user.getName() : null);
        m.put("user_email", user != null ? user.getEmail() : null);
        m.put("detail", e.getDetail());
        m.put("ip_address", e.getIpAddress());
        m.put("user_agent", e.getUserAgent());
        m.put("created_at", utc(e.getCreatedAt()));
        return m;
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------
    public List<Map<String, Object>> allBusinesses() {
        List<Map<String, Object>> list = new ArrayList<>();
        em.createQuery("select b from Barbershop b order by b.id desc", Barbershop.class).getResultList()
                .forEach(b -> list.add(barbershopSummary(b)));
        em.createQuery("select r from Restaurant r order by r.id desc", Restaurant.class).getResultList()
                .forEach(r -> list.add(restaurantSummary(r)));
        list.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("created_at"), Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    private Map<String, String> businessNames() {
        Map<String, String> names = new HashMap<>();
        em.createQuery("select b.id, b.name from Barbershop b", Object[].class).getResultList()
                .forEach(r -> names.put("barbershop:" + r[0], (String) r[1]));
        em.createQuery("select r.id, r.name from Restaurant r", Object[].class).getResultList()
                .forEach(r -> names.put("restaurant:" + r[0], (String) r[1]));
        return names;
    }

    private boolean matchesStatus(Map<String, Object> b, String status) {
        if (status == null || status.isBlank()) return true;
        boolean suspended = (boolean) b.get("suspended");
        return switch (status) {
            case "active" -> !suspended;
            case "suspended" -> suspended;
            case "expired", "trial" -> status.equals(b.get("effective_status"));
            case "paid" -> "active".equals(b.get("effective_status"));
            default -> throw ApiException.unprocessable("Estado inválido: usa active, suspended, expired, trial o paid");
        };
    }

    private Set<Long> ownerIds() {
        Set<Long> ids = new HashSet<>(em.createQuery("select distinct b.owner.id from Barbershop b", Long.class).getResultList());
        ids.addAll(em.createQuery("select distinct r.owner.id from Restaurant r", Long.class).getResultList());
        return ids;
    }

    private List<Map<String, Object>> auditFor(String targetType, String targetId, int limit) {
        return em.createQuery("select a from AdminAuditLog a where a.targetType = :t and a.targetId = :id order by a.id desc", AdminAuditLog.class)
                .setParameter("t", targetType).setParameter("id", targetId).setMaxResults(limit)
                .getResultList().stream().map(AdminQueryService::auditMap).toList();
    }

    private Map<Long, User> byId(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return em.createQuery("select u from User u where u.id in :ids", User.class).setParameter("ids", ids)
                .getResultList().stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private long count(String jpql, Map<String, Object> params) {
        TypedQuery<Long> query = em.createQuery(jpql, Long.class);
        params.forEach(query::setParameter);
        return query.getSingleResult();
    }

    private BigDecimal sum(String jpql, Map<String, Object> params) {
        TypedQuery<BigDecimal> query = em.createQuery(jpql, BigDecimal.class);
        params.forEach(query::setParameter);
        BigDecimal value = query.getSingleResult();
        return value != null ? value : BigDecimal.ZERO;
    }

    private static Map<String, Object> pageOf(List<Map<String, Object>> items, long total, PageRequest page) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("total", total);
        m.put("page", page.page());
        m.put("size", page.size());
        return m;
    }

    private static Map<String, Object> slice(List<Map<String, Object>> rows, PageRequest page) {
        int from = Math.min(page.offset(), rows.size());
        int to = Math.min(from + page.size(), rows.size());
        return pageOf(rows.subList(from, to), rows.size(), page);
    }

    private static String like(String q) {
        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    static String str(LocalDateTime value) {
        return value != null ? value.toString() : null;
    }

    /** Fechas guardadas en UTC (sesiones, bitácora): con "Z" para que el navegador las convierta a su hora. */
    static String utc(LocalDateTime value) {
        return value != null ? value + "Z" : null;
    }

    static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }
}
