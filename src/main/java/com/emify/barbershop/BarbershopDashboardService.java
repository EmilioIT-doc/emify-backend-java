package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import com.emify.booking.Appointment;
import com.emify.booking.AppointmentRepository;
import com.emify.common.ApiException;
import com.emify.common.BusinessClock;
import com.emify.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.emify.booking.AppointmentRepository.ACTIVE_STATUSES;
import static com.emify.booking.AppointmentRepository.ATTENDED;
import static com.emify.booking.AppointmentRepository.CALENDAR_STATUSES;

/**
 * Datos del dashboard de una sucursal: Calendario, Home (ventas, próximas citas, top servicios,
 * top staff) y Clientes. Los formatos coinciden con lo que leen los componentes del frontend
 * (pages/verticals/barbershop/DashboardComponents).
 */
@Service
@RequiredArgsConstructor
public class BarbershopDashboardService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] DAY_NAMES = {"Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb"};
    private static final String[] MONTH_NAMES = {"Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};

    private final LocationAccessService access;
    private final AppointmentRepository appointmentRepository;
    private final BarbershopStaffRepository staffRepository;
    private final AvailabilityRepository availabilityRepository;
    private final BusinessClock clock;

    // -------------------------------------------------------
    // CALENDARIO — GET /location/{id}/calendar?date=YYYY-MM-DD
    // -------------------------------------------------------
    // El dueño ve todo. El barbero ve completas SUS citas y de las de su equipo solo cliente, servicio y horario
    // (sin teléfono, precio, notas, foto ni método de pago). "my_staff_id" = su registro de personal en esta
    // sucursal (también el del dueño si atiende aquí), para marcar su columna como "Tú".
    public ApiResponse<?> getCalendar(User user, Long locationId, String date) {
        BarbershopLocation location = access.requireOwnerOrStaff(user, locationId);
        boolean isOwner = access.isOwner(user, location);
        Long myStaffId = staffRepository.findByUserIdAndLocationId(user.getId(), locationId)
                .filter(BarbershopStaff::isActive)
                .map(BarbershopStaff::getId)
                .orElse(null);
        LocalDate day = date == null || date.isBlank() ? clock.today() : clock.parseDate(date);

        List<Map<String, Object>> appointments = appointmentRepository
                .findByLocationBetween(locationId, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), CALENDAR_STATUSES)
                .stream()
                .map(a -> {
                    Map<String, Object> map = calendarAppointment(a);
                    boolean mine = myStaffId != null && a.getStaff() != null && myStaffId.equals(a.getStaff().getId());
                    map.put("is_mine", mine);
                    if (!isOwner && !mine) {
                        for (String key : List.of("token", "phone", "price", "payment_method", "notes", "reference_image_url")) {
                            map.remove(key);
                        }
                    }
                    return map;
                })
                .toList();

        List<Map<String, Object>> staff = staffRepository.findByLocationIdAndIsActiveTrueOrderByIdAsc(locationId).stream()
                .map(s -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", s.getId());
                    map.put("name", s.getUser().getName());
                    map.put("avatar", initials(s.getUser().getName()));
                    map.put("avatar_url", s.getUser().getAvatarUrl());
                    return map;
                })
                .toList();

        // Horario de la sucursal ese día (null = cierra). Sirve para los huecos libres y el rango de horas.
        short dow = (short) (day.getDayOfWeek().getValue() % 7);
        Optional<Availability> schedule = availabilityRepository
                .findByLocationIdAndDayOfWeekAndStaffIdIsNullAndIsActiveTrue(locationId, dow);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", day.toString());
        data.put("appointments", appointments);
        data.put("staff", staff);
        data.put("is_owner", isOwner);
        data.put("my_staff_id", myStaffId);
        data.put("opens_at", schedule.map(a -> a.getStartTime().format(HH_MM)).orElse(null));
        data.put("closes_at", schedule.map(a -> a.getEndTime().format(HH_MM)).orElse(null));
        return ApiResponse.ok("Calendario", data);
    }

    // -------------------------------------------------------
    // HOME: VENTAS RECIENTES (últimos 7 días, incluye hoy) — solo citas atendidas
    // -------------------------------------------------------
    public ApiResponse<?> getRecentSales(User user, Long locationId) {
        access.requireOwnerOrStaff(user, locationId);
        LocalDate today = clock.today();
        LocalDate first = today.minusDays(6);

        List<Appointment> appointments = appointmentRepository.findByLocationBetween(
                locationId, first.atStartOfDay(), today.plusDays(1).atStartOfDay(), ATTENDED);

        Map<LocalDate, List<Appointment>> byDay = appointments.stream()
                .collect(Collectors.groupingBy(a -> a.getStartTime().toLocalDate()));

        List<Map<String, Object>> days = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            List<Appointment> list = byDay.getOrDefault(d, List.of());
            BigDecimal sales = sumPrices(list);
            total = total.add(sales);

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("day", DAY_NAMES[d.getDayOfWeek().getValue() % 7]);
            map.put("date", d.toString());
            map.put("ventas", sales);
            map.put("citas", list.size());
            days.add(map);
        }

        // Los 7 días anteriores, para comparar ("vs. semana anterior")
        List<Appointment> previous = appointmentRepository.findByLocationBetween(
                locationId, first.minusDays(7).atStartOfDay(), first.atStartOfDay(), ATTENDED);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", days);
        data.put("total", total);
        data.put("total_citas", appointments.size());
        data.put("previous_total", sumPrices(previous));
        data.put("previous_citas", previous.size());
        data.put("by_payment", salesByPayment(appointments));
        return ApiResponse.ok("Ventas recientes", data);
    }

    // -------------------------------------------------------
    // HOME: VENTAS POR PERIODO — ?period=day|week|month|year&date=YYYY-MM-DD   (solo citas atendidas)
    //   day   → el día de "date", un punto por hora del horario de la sucursal
    //   week  → lun–dom de la semana de "date", un punto por día (los que no abre van con closed=true)
    //   month → ene–dic del año de "date", un punto por mes (cada mes = suma de sus días)
    //   year  → los últimos 10 años hasta el de "date", un punto por año (cada año = suma de sus meses)
    // El total es la suma de lo que muestra la gráfica. Si el rango está en curso, se compara contra el
    // rango anterior HASTA EL MISMO PUNTO (lunes 11:30 vs. el lunes anterior 11:30).
    // -------------------------------------------------------
    public ApiResponse<?> getSales(User user, Long locationId, String period, String date) {
        access.requireOwnerOrStaff(user, locationId);
        String p = period.trim().toLowerCase(Locale.ROOT);
        LocalDateTime now = clock.now();
        LocalDate anchor = date == null || date.isBlank() ? now.toLocalDate() : clock.parseDate(date);
        if (anchor.isAfter(now.toLocalDate())) anchor = now.toLocalDate();

        LocalDateTime start = switch (p) {
            case "day" -> anchor.atStartOfDay();
            case "week" -> anchor.minusDays(anchor.getDayOfWeek().getValue() - 1L).atStartOfDay();
            case "month" -> anchor.withDayOfYear(1).atStartOfDay();
            case "year" -> anchor.withDayOfYear(1).minusYears(9).atStartOfDay();
            default -> throw ApiException.unprocessable("Periodo inválido: usa day, week, month o year");
        };
        LocalDateTime end = switch (p) {
            case "day" -> start.plusDays(1);
            case "week" -> start.plusWeeks(1);
            case "month" -> start.plusYears(1);
            default -> start.plusYears(10);
        };
        boolean current = !now.isBefore(start) && now.isBefore(end);
        LocalDateTime cutoff = current ? now : end;

        List<Appointment> appointments = appointmentRepository.findByLocationBetween(locationId, start, cutoff, ATTENDED);
        List<Appointment> previous = appointmentRepository.findByLocationBetween(
                locationId, shiftRange(p, start), shiftRange(p, cutoff), ATTENDED);
        Set<Integer> openDays = openWeekdays(locationId);

        // Puntos de la gráfica
        List<LocalDateTime> bucketStarts = new ArrayList<>();
        switch (p) {
            case "day" -> {
                int[] hours = openingHours(locationId, anchor, appointments);
                for (int h = hours[0]; h < hours[1]; h++) bucketStarts.add(start.plusHours(h));
            }
            case "week" -> { for (LocalDateTime b = start; b.isBefore(end); b = b.plusDays(1)) bucketStarts.add(b); }
            case "month" -> { for (LocalDateTime b = start; b.isBefore(end); b = b.plusMonths(1)) bucketStarts.add(b); }
            default -> { for (LocalDateTime b = start; b.isBefore(end); b = b.plusYears(1)) bucketStarts.add(b); }
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (int i = 0; i < bucketStarts.size(); i++) {
            LocalDateTime from = bucketStarts.get(i);
            LocalDateTime to = switch (p) {
                case "day" -> from.plusHours(1);
                case "week" -> from.plusDays(1);
                case "month" -> from.plusMonths(1);
                default -> from.plusYears(1);
            };
            final LocalDateTime f = from, t = to;
            List<Appointment> list = appointments.stream()
                    .filter(a -> !a.getStartTime().isBefore(f) && a.getStartTime().isBefore(t))
                    .toList();

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("day", switch (p) {
                case "day" -> from.format(HH_MM);
                case "week" -> DAY_NAMES[from.getDayOfWeek().getValue() % 7];
                case "month" -> MONTH_NAMES[from.getMonthValue() - 1];
                default -> String.valueOf(from.getYear());
            });
            map.put("date", switch (p) {
                case "day" -> from.toString();
                case "week" -> from.toLocalDate().toString();
                case "month" -> YearMonth.from(from).toString();
                default -> String.valueOf(from.getYear());
            });
            map.put("ventas", sumPrices(list));
            map.put("citas", list.size());
            map.put("future", current && from.isAfter(now));
            map.put("closed", p.equals("week") && !openDays.contains(from.getDayOfWeek().getValue() % 7) && list.isEmpty());
            buckets.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", p);
        data.put("start", start.toLocalDate().toString());
        data.put("end", end.toLocalDate().minusDays(1).toString());
        data.put("is_current", current);
        data.put("closed", p.equals("day") && !openDays.contains(anchor.getDayOfWeek().getValue() % 7) && appointments.isEmpty());
        data.put("days", buckets);
        data.put("total", sumPrices(appointments));
        data.put("total_citas", appointments.size());
        data.put("previous_total", sumPrices(previous));
        data.put("previous_citas", previous.size());
        data.put("by_payment", salesByPayment(appointments));
        return ApiResponse.ok("Ventas por periodo", data);
    }

    // El rango anterior del mismo tamaño: día anterior, semana anterior, año anterior o los 10 años anteriores
    private static LocalDateTime shiftRange(String period, LocalDateTime value) {
        return switch (period) {
            case "day" -> value.minusDays(1);
            case "week" -> value.minusWeeks(1);
            case "month" -> value.minusYears(1);
            default -> value.minusYears(10);
        };
    }

    // Días de la semana que abre la sucursal (0 = domingo … 6 = sábado). Sin horario capturado: todos.
    private Set<Integer> openWeekdays(Long locationId) {
        Set<Integer> days = availabilityRepository.findByLocationIdAndStaffIdIsNullAndIsActiveTrue(locationId).stream()
                .map(a -> (int) a.getDayOfWeek())
                .collect(Collectors.toSet());
        return days.isEmpty() ? Set.of(0, 1, 2, 3, 4, 5, 6) : days;
    }

    // Horas a mostrar en la vista "día": el horario de la sucursal ese día (o 10–20 si cierra),
    // ampliado si hubo citas fuera de él. Regresa {horaInicio, horaFinExclusiva}.
    private int[] openingHours(Long locationId, LocalDate day, List<Appointment> appointments) {
        short dow = (short) (day.getDayOfWeek().getValue() % 7);
        int from = 10, to = 20;
        Optional<Availability> schedule = availabilityRepository
                .findByLocationIdAndDayOfWeekAndStaffIdIsNullAndIsActiveTrue(locationId, dow);
        if (schedule.isPresent()) {
            LocalTime open = schedule.get().getStartTime();
            LocalTime close = schedule.get().getEndTime();
            from = open.getHour();
            to = close.getMinute() > 0 ? close.getHour() + 1 : close.getHour();
        }
        for (Appointment a : appointments) {
            int h = a.getStartTime().getHour();
            from = Math.min(from, h);
            to = Math.max(to, h + 1);
        }
        if (to <= from) to = from + 1;
        return new int[]{from, to};
    }

    // -------------------------------------------------------
    // HOME: PRÓXIMAS CITAS (desde ahora, próximos 7 días)
    // -------------------------------------------------------
    public ApiResponse<?> getUpcomingAppointments(User user, Long locationId) {
        access.requireOwnerOrStaff(user, locationId);
        LocalDateTime now = clock.now();

        List<Map<String, Object>> appointments = appointmentRepository
                .findByLocationBetween(locationId, now, now.toLocalDate().plusDays(8).atStartOfDay(), ACTIVE_STATUSES)
                .stream()
                .limit(20)
                .map(a -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", a.getId());
                    map.put("date", a.getStartTime().toLocalDate().toString());
                    map.put("time", a.getStartTime().format(HH_MM));
                    map.put("client", a.getUser().getName());
                    map.put("service", a.getService().getName());
                    map.put("price", a.getService().getPrice());
                    map.put("end_time", a.getEndTime().format(HH_MM));
                    map.put("staff", staffName(a));
                    map.put("staff_avatar_url", a.getStaff() != null && a.getStaff().getUser() != null
                            ? a.getStaff().getUser().getAvatarUrl() : null);
                    map.put("status", a.getStatus().name());
                    return map;
                })
                .toList();

        return ApiResponse.ok("Próximas citas", Map.of("appointments", appointments));
    }

    // -------------------------------------------------------
    // HOME: TOP SERVICIOS (este mes vs. mes pasado)
    // -------------------------------------------------------
    public ApiResponse<?> getTopServices(User user, Long locationId) {
        access.requireOwnerOrStaff(user, locationId);
        YearMonth thisMonth = YearMonth.from(clock.today());
        YearMonth lastMonth = thisMonth.minusMonths(1);

        List<Appointment> appointments = appointmentRepository.findByLocationBetween(
                locationId, lastMonth.atDay(1).atStartOfDay(), thisMonth.plusMonths(1).atDay(1).atStartOfDay(), ATTENDED);

        Map<Long, Map<String, Object>> byService = new LinkedHashMap<>();
        for (Appointment a : appointments) {
            BarberService service = a.getService();
            Map<String, Object> row = byService.computeIfAbsent(service.getId(), id -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", id);
                map.put("name", service.getName());
                map.put("thisMonth", 0);
                map.put("lastMonth", 0);
                return map;
            });
            String key = YearMonth.from(a.getStartTime()).equals(thisMonth) ? "thisMonth" : "lastMonth";
            row.put(key, (Integer) row.get(key) + 1);
        }

        List<Map<String, Object>> services = byService.values().stream()
                .sorted(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("thisMonth")).reversed()
                        .thenComparing(m -> -(Integer) m.get("lastMonth")))
                .limit(5)
                .toList();

        return ApiResponse.ok("Top servicios", Map.of("services", services));
    }

    // -------------------------------------------------------
    // HOME: TOP STAFF (este mes, por ingresos)
    // -------------------------------------------------------
    public ApiResponse<?> getTopStaff(User user, Long locationId) {
        access.requireOwnerOrStaff(user, locationId);
        YearMonth thisMonth = YearMonth.from(clock.today());

        List<Appointment> appointments = appointmentRepository.findByLocationBetween(
                locationId, thisMonth.atDay(1).atStartOfDay(), thisMonth.plusMonths(1).atDay(1).atStartOfDay(), ATTENDED);
        Map<Long, List<Appointment>> byStaff = appointments.stream()
                .filter(a -> a.getStaff() != null)
                .collect(Collectors.groupingBy(a -> a.getStaff().getId()));

        List<Map<String, Object>> staff = staffRepository.findByLocationIdAndIsActiveTrueOrderByIdAsc(locationId).stream()
                .map(s -> {
                    List<Appointment> list = byStaff.getOrDefault(s.getId(), List.of());
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", s.getId());
                    map.put("name", s.getUser().getName());
                    map.put("avatar", initials(s.getUser().getName()));
                    map.put("avatar_url", s.getUser().getAvatarUrl());
                    map.put("services", list.size());
                    map.put("revenue", sumPrices(list));
                    return map;
                })
                .sorted(Comparator.comparing((Map<String, Object> m) -> (BigDecimal) m.get("revenue")).reversed())
                .toList();

        return ApiResponse.ok("Top staff", Map.of("staff", staff));
    }

    // -------------------------------------------------------
    // CLIENTES — quiénes han agendado en esta sucursal
    // -------------------------------------------------------
    public ApiResponse<?> getClients(User user, Long locationId) {
        access.requireOwnerOrStaff(user, locationId);

        // Ya vienen ordenadas de la más reciente a la más antigua
        Map<Long, List<Appointment>> byClient = appointmentRepository.findAllByLocation(locationId).stream()
                .collect(Collectors.groupingBy(a -> a.getUser().getId(), LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> clients = byClient.values().stream()
                .map(list -> {
                    User client = list.get(0).getUser();
                    // Visitas = citas a las que sí llegó
                    List<Appointment> visits = list.stream()
                            .filter(a -> a.getStatus() == Appointment.AppointmentStatus.completed)
                            .toList();

                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", client.getId());
                    map.put("name", client.getName());
                    map.put("phone", client.getPhone());
                    map.put("visits", visits.size());
                    map.put("last_visit", list.get(0).getStartTime().toString());
                    map.put("appointments", list.stream().map(a -> {
                        Map<String, Object> appt = new LinkedHashMap<>();
                        appt.put("id", a.getId());
                        appt.put("service", a.getService().getName());
                        appt.put("staff", staffName(a));
                        appt.put("start_time", a.getStartTime().toString());
                        appt.put("price", a.getService().getPrice());
                        appt.put("status", a.getStatus().name());
                        return appt;
                    }).toList());
                    return map;
                })
                .toList();

        return ApiResponse.ok("Clientes", Map.of("clients", clients));
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------
    private Map<String, Object> calendarAppointment(Appointment a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("token", a.getToken());
        map.put("date", a.getStartTime().toLocalDate().toString());
        map.put("start_time", a.getStartTime().format(HH_MM));
        map.put("end_time", a.getEndTime().format(HH_MM));
        map.put("client", a.getUser().getName());
        map.put("phone", a.getUser().getPhone());
        map.put("service", a.getService().getName());
        map.put("service_id", a.getService().getId());
        map.put("price", a.getService().getPrice());
        map.put("staff_id", a.getStaff() != null ? a.getStaff().getId() : null); // null → columna "Sin asignar"
        map.put("staff_name", staffName(a));
        map.put("status", a.getStatus().name());
        map.put("payment_method", a.getPaymentMethod() != null ? a.getPaymentMethod().name() : null);
        map.put("notes", a.getNotes());
        map.put("reference_image_url", a.getReferenceImageUrl());
        return map;
    }

    private static String staffName(Appointment a) {
        return a.getStaff() != null && a.getStaff().getUser() != null ? a.getStaff().getUser().getName() : null;
    }

    /**
     * Ventas separadas por cómo pagó el cliente (informativo, para el corte de caja).
     * Siempre trae efectivo/tarjeta/transferencia; "sin_registrar" solo si hay citas atendidas
     * sin método (las que se marcaron antes de que existiera este dato).
     */
    private static List<Map<String, Object>> salesByPayment(List<Appointment> appointments) {
        Map<Appointment.PaymentMethod, List<Appointment>> grouped = appointments.stream()
                .filter(a -> a.getPaymentMethod() != null)
                .collect(Collectors.groupingBy(Appointment::getPaymentMethod));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Appointment.PaymentMethod method : Appointment.PaymentMethod.values()) {
            List<Appointment> list = grouped.getOrDefault(method, List.of());
            result.add(paymentRow(method.name(), sumPrices(list), list.size()));
        }
        List<Appointment> unknown = appointments.stream().filter(a -> a.getPaymentMethod() == null).toList();
        if (!unknown.isEmpty()) {
            result.add(paymentRow("sin_registrar", sumPrices(unknown), unknown.size()));
        }
        return result;
    }

    private static Map<String, Object> paymentRow(String method, BigDecimal total, int citas) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", method);
        map.put("total", total);
        map.put("citas", citas);
        return map;
    }

    private static BigDecimal sumPrices(List<Appointment> appointments) {
        return appointments.stream()
                .map(a -> a.getService().getPrice())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].substring(0, 1);
        String second = parts.length > 1 ? parts[1].substring(0, 1) : "";
        return (first + second).toUpperCase(Locale.ROOT);
    }
}
