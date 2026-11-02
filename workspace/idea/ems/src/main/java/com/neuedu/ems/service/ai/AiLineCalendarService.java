package com.neuedu.ems.service.ai;

import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 只读产线日历统计；跨夜班次裁剪到查询区间，重叠周历取最大系数而不叠加资源。 */
@Service
public class AiLineCalendarService {
    private final AiDataQueryMapper mapper;
    public AiLineCalendarService(AiDataQueryMapper mapper) { this.mapper = mapper; }
    public record Availability(BigDecimal available, BigDecimal remaining) {}
    private record Window(LocalDateTime start, LocalDateTime end, BigDecimal factor, boolean booked) {}

    public Map<Long, Availability> availability(LocalDate from, LocalDate to) {
        requireQueryRange(from, to);
        var start = from.atStartOfDay(); var end = to.plusDays(1).atStartOfDay();
        Map<Long, List<Window>> windows = new LinkedHashMap<>();
        collectRuleWindows(windows, from, to, start, end);
        collectBookedWindows(windows, start, end);
        return buildAvailability(windows);
    }

    /** 查询区间必须非空、顺序正确且不超过 367 天。 */
    private static void requireQueryRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new IllegalArgumentException("查询日期须为顺序正确且不超过367天的区间");
        boolean reversed = from.isAfter(to);
        boolean tooLong = java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366;
        if (reversed || tooLong) throw new IllegalArgumentException("查询日期须为顺序正确且不超过367天的区间");
    }

    /** 按日历规则逐条铺开班次窗口，跨夜班次顺延到次日。 */
    private void collectRuleWindows(Map<Long, List<Window>> windows, LocalDate from, LocalDate to,
                                    LocalDateTime start, LocalDateTime end) {
        for (var row : mapper.selectCalendarRules(from.minusDays(1), to)) {
            var target = windows.computeIfAbsent(number(row.get("lineId")), ignored -> new ArrayList<>());
            var factor = capacityFactorOf(row);
            if (factor == null) continue;
            var shiftStart = LocalTime.parse(row.get("startTime").toString());
            var shiftEnd = LocalTime.parse(row.get("endTime").toString());
            if (shiftStart.equals(shiftEnd)) continue;
            appendShiftWindows(target, row, from, to, start, end, shiftStart, shiftEnd, factor);
        }
    }

    /** 产能系数只在 (0,1] 区间内有效，越界规则整条忽略。 */
    private static BigDecimal capacityFactorOf(Map<String, Object> row) {
        var factor = new BigDecimal(String.valueOf(row.get("capacityFactor")));
        if (factor.signum() <= 0 || factor.compareTo(BigDecimal.ONE) > 0) return null;
        return factor;
    }

    /** 把一条规则的班次按天铺开，只保留落在查询区间内的部分。 */
    private void appendShiftWindows(List<Window> target, Map<String, Object> row, LocalDate from, LocalDate to,
                                    LocalDateTime start, LocalDateTime end, LocalTime shiftStart,
                                    LocalTime shiftEnd, BigDecimal factor) {
        var first = date(row.get("effectiveFrom")); var last = date(row.get("effectiveTo"));
        long dayOfWeek = number(row.get("dayOfWeek"));
        for (var day = from.minusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            if (!matchesDay(day, dayOfWeek, first, last)) continue;
            var a = day.atTime(shiftStart);
            var b = day.atTime(shiftEnd);
            if (!b.isAfter(a)) b = b.plusDays(1);
            add(target, a, b, start, end, factor, false);
        }
    }

    /** 当天是否命中规则：星期匹配，且在生效区间内。 */
    private static boolean matchesDay(LocalDate day, long dayOfWeek, LocalDate first, LocalDate last) {
        if (day.getDayOfWeek().getValue() != dayOfWeek) return false;
        if (first != null && day.isBefore(first)) return false;
        return last == null || !day.isAfter(last);
    }

    /** 已排产计划占用的时段按整系数计入，并标记为已占用。 */
    private void collectBookedWindows(Map<Long, List<Window>> windows, LocalDateTime start, LocalDateTime end) {
        for (var row : mapper.selectBookedPlanWindows(start, end)) {
            var a = timestamp(row.get("plannedStartTime")); var b = timestamp(row.get("plannedEndTime"));
            if (a != null && b != null) add(windows.computeIfAbsent(number(row.get("lineId")), ignored -> new ArrayList<>()),
                    a, b, start, end, BigDecimal.ONE, true);
        }
    }

    /** 逐条产线把窗口边界切成不重叠的区段后累计可用与剩余工时。 */
    private Map<Long, Availability> buildAvailability(Map<Long, List<Window>> windows) {
        Map<Long, Availability> result = new LinkedHashMap<>();
        windows.forEach((line, rows) -> result.put(line, summarize(rows)));
        return result;
    }

    private static Availability summarize(List<Window> rows) {
        var points = new TreeSet<LocalDateTime>();
        for (var row : rows) {
            points.add(row.start);
            points.add(row.end);
        }
        var boundaries = new ArrayList<>(points);
        var available = BigDecimal.ZERO;
        var remaining = BigDecimal.ZERO;
        for (int i = 1; i < boundaries.size(); i++) {
            var a = boundaries.get(i - 1);
            var b = boundaries.get(i);
            var coverage = coverageOf(rows, a, b);
            var minutes = minutesOf(a, b, coverage.factor());
            available = available.add(minutes);
            if (!coverage.booked()) remaining = remaining.add(minutes);
        }
        return new Availability(available, remaining);
    }

    /** 一个区段内生效的产能系数与是否已被计划占用。 */
    private record Coverage(BigDecimal factor, boolean booked) {}

    private static Coverage coverageOf(List<Window> rows, LocalDateTime a, LocalDateTime b) {
        var factor = BigDecimal.ZERO;
        boolean booked = false;
        for (var row : rows) {
            boolean coversSegment = !row.start.isAfter(a) && !row.end.isBefore(b);
            if (!coversSegment) continue;
            if (row.booked) {
                booked = true;
            } else {
                factor = factor.max(row.factor);
            }
        }
        return new Coverage(factor, booked);
    }

    private static BigDecimal minutesOf(LocalDateTime a, LocalDateTime b, BigDecimal factor) {
        return BigDecimal.valueOf(Duration.between(a, b).getSeconds())
                .divide(BigDecimal.valueOf(60), 4, java.math.RoundingMode.HALF_UP)
                .multiply(factor);
    }

    private void add(List<Window> rows, LocalDateTime a, LocalDateTime b, LocalDateTime start, LocalDateTime end, BigDecimal factor, boolean booked) {
        LocalDateTime clippedStart = a.isBefore(start) ? start : a;
        LocalDateTime clippedEnd = b.isAfter(end) ? end : b;
        if (clippedEnd.isAfter(clippedStart)) rows.add(new Window(clippedStart, clippedEnd, factor, booked));
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static LocalDate date(Object value) { return value == null ? null : LocalDate.parse(value.toString()); }
    private static LocalDateTime timestamp(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp stamp) return stamp.toLocalDateTime();
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
