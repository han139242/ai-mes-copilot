package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.mapper.factory.FactoryLineMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跨层资源冲突检查。
 *
 * <p>多层 BOM 展开时，每一层都按自己的能力优先级独立选线并各自向后找产能窗口：
 * 组件 A 与组件 B 可能落到同一条产线，组件计划也可能因为该线已被占满而顺延到父工单开工之后。
 * 这类冲突不影响单张计划自身合法，但会让父工单开工时缺料，必须单独识别出来。</p>
 *
 * <p>本服务只做识别与解释，不改任何计划：冲突是否要调整由计划员决定，
 * 自动改时间会掩盖真实的产能不足。</p>
 */
@Service
public class ResourceConflictService {

    /**
     * 参与冲突判定的状态：已完工的批次不再占用未来产能，也不构成"父工单开工时缺料"的风险，
     * 因此不参与判定；已发生的历史重叠属于追溯问题，不在这里当预警反复报。
     */
    private static final List<String> CONFLICT_STATUSES = List.of("DRAFT", "RELEASED", "RUNNING");
    private static final String TYPE_COMPONENT_AFTER_PARENT = "COMPONENT_AFTER_PARENT";
    private static final String TYPE_LINE_OVERLAP = "LINE_OVERLAP";
    /** 追溯父子链的最大层数，与组件 BOM 展开层数上限保持一致。 */
    private static final int MAX_FAMILY_DEPTH = 8;
    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final MfgProductionPlanMapper plans;
    private final FactoryLineMapper lines;

    public ResourceConflictService(MfgProductionPlanMapper plans, FactoryLineMapper lines) {
        this.plans = plans;
        this.lines = lines;
    }

    /**
     * 扫描指定时间窗内的跨层资源冲突。
     *
     * @param from 窗口开始；为空时取当前时间（只预警未来仍会发生的冲突）
     * @param to   窗口结束；为空时取当前时间往后 60 天
     */
    public Map<String, Object> scan(LocalDateTime from, LocalDateTime to) {
        LocalDateTime begin = from == null ? LocalDateTime.now().withNano(0) : from.withNano(0);
        LocalDateTime end = to == null ? LocalDateTime.now().plusDays(60).withNano(0) : to.withNano(0);
        if (!end.isAfter(begin)) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
        List<MfgProductionPlan> booked = plans.selectList(new LambdaQueryWrapper<MfgProductionPlan>()
                .eq(MfgProductionPlan::getDeletedFlag, 0)
                .in(MfgProductionPlan::getStatus, CONFLICT_STATUSES)
                .isNotNull(MfgProductionPlan::getPlannedStartTime)
                .isNotNull(MfgProductionPlan::getPlannedEndTime)
                .lt(MfgProductionPlan::getPlannedStartTime, end)
                .gt(MfgProductionPlan::getPlannedEndTime, begin)
                .orderByAsc(MfgProductionPlan::getPlannedStartTime));
        Map<Long, FactoryLine> lineCache = new HashMap<>();
        List<Map<String, Object>> items = collectConflictItems(booked, indexPlans(booked), lineCache);
        Map<String, Long> counts = countConflicts(items);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("begin", begin.toString());
        result.put("end", end.toString());
        result.put("scannedPlanCount", booked.size());
        result.put("counts", counts);
        result.put("items", items);
        result.put("summary", buildSummary(begin, end, booked.size(), items, counts));
        return result;
    }

    /** 按计划主键建立索引，供组件计划反查父计划。 */
    private Map<Long, MfgProductionPlan> indexPlans(List<MfgProductionPlan> booked) {
        Map<Long, MfgProductionPlan> byId = new HashMap<>();
        for (MfgProductionPlan plan : booked) {
            byId.put(plan.getPlanId(), plan);
        }
        return byId;
    }

    /** 汇总两类跨层冲突，并按计划开工时间排序。 */
    private List<Map<String, Object>> collectConflictItems(List<MfgProductionPlan> booked,
                                                          Map<Long, MfgProductionPlan> byId,
                                                          Map<Long, FactoryLine> lineCache) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(componentAfterParent(booked, byId, lineCache));
        items.addAll(lineOverlap(booked, lineCache));
        items.sort(Comparator.comparing(item -> String.valueOf(item.get("plannedStartTime"))));
        return items;
    }

    /** 按冲突类型统计条数，保持组件晚于父开工、同线重叠的固定顺序。 */
    private Map<String, Long> countConflicts(List<Map<String, Object>> items) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", (long) items.size());
        counts.put(TYPE_COMPONENT_AFTER_PARENT, countType(items, TYPE_COMPONENT_AFTER_PARENT));
        counts.put(TYPE_LINE_OVERLAP, countType(items, TYPE_LINE_OVERLAP));
        return counts;
    }

    /** 生成扫描结论文案，区分无冲突与有冲突两种表述。 */
    private String buildSummary(LocalDateTime begin, LocalDateTime end, int scannedCount,
                                List<Map<String, Object>> items, Map<String, Long> counts) {
        String prefix = "在 " + begin.toLocalDate() + " 至 " + end.toLocalDate() + " 之间，"
                + scannedCount + " 张有效计划";
        if (items.isEmpty()) {
            return prefix + "没有发现跨层资源冲突。";
        }
        return prefix + "中发现 " + items.size() + " 处跨层资源冲突：组件晚于父工单开工 "
                + counts.get(TYPE_COMPONENT_AFTER_PARENT) + " 处、同线时段重叠 "
                + counts.get(TYPE_LINE_OVERLAP) + " 处。";
    }

    /** 组件计划完工晚于父计划开工：父工单开工时该组件还不齐套。 */
    private List<Map<String, Object>> componentAfterParent(List<MfgProductionPlan> booked,
                                                           Map<Long, MfgProductionPlan> byId,
                                                           Map<Long, FactoryLine> lineCache) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (MfgProductionPlan plan : booked) {
            Map<String, Object> row = componentConflictRow(plan, byId, lineCache);
            if (row != null) {
                items.add(row);
            }
        }
        return items;
    }

    /** 判定单张组件计划是否晚于父计划开工，是则返回冲突行，否则返回 null。 */
    private Map<String, Object> componentConflictRow(MfgProductionPlan plan,
                                                     Map<Long, MfgProductionPlan> byId,
                                                     Map<Long, FactoryLine> lineCache) {
        MfgProductionPlan parent = lateComponentParentOf(plan, byId);
        if (parent == null) {
            return null;
        }
        Map<String, Object> row = baseRow(plan, lineCache);
        row.put("conflictType", TYPE_COMPONENT_AFTER_PARENT);
        row.put("conflictLabel", "组件完工晚于父工单开工");
        row.put("relatedPlanNo", parent.getPlanNo());
        row.put("relatedStartTime", parent.getPlannedStartTime().toString());
        row.put("gapMinutes", minutes(plan.getPlannedEndTime(), parent.getPlannedStartTime()));
        row.put("reason", "组件计划 " + plan.getPlanNo() + " 预计 " + plan.getPlannedEndTime()
                + " 完工，晚于父计划 " + parent.getPlanNo() + " 的开工时间 "
                + parent.getPlannedStartTime() + "，父工单开工时该组件不齐套。");
        return row;
    }

    /** 返回该组件计划的父计划；不构成"父工单开工时缺料"时返回 null。 */
    private MfgProductionPlan lateComponentParentOf(MfgProductionPlan plan,
                                                    Map<Long, MfgProductionPlan> byId) {
        if (!isIndexedComponentPlan(plan, byId)) {
            return null;
        }
        MfgProductionPlan parent = byId.get(plan.getParentPlanId());
        if (!finishesAfterParentStart(plan, parent)) {
            return null;
        }
        return parent;
    }

    /** 是本次扫描里唯一索引到的组件计划（有父计划）。 */
    private static boolean isIndexedComponentPlan(MfgProductionPlan plan, Map<Long, MfgProductionPlan> byId) {
        boolean indexedOnce = byId.containsKey(plan.getPlanId()) && byId.get(plan.getPlanId()) == plan;
        return indexedOnce
                && "COMPONENT".equals(plan.getPlanType())
                && plan.getParentPlanId() != null;
    }

    /** 父计划已知且本计划完工晚于父计划开工。 */
    private static boolean finishesAfterParentStart(MfgProductionPlan plan, MfgProductionPlan parent) {
        if (parent == null || parent.getPlannedStartTime() == null) {
            return false;
        }
        return plan.getPlannedEndTime().isAfter(parent.getPlannedStartTime());
    }

    /** 同一产线上两张有效计划时段重叠：产能被重复承诺。 */
    private List<Map<String, Object>> lineOverlap(List<MfgProductionPlan> booked, Map<Long, FactoryLine> lineCache) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (List<MfgProductionPlan> onLine : groupByLine(booked).values()) {
            items.addAll(overlapRowsOnLine(onLine, lineCache));
        }
        return items;
    }

    /** 按产线归集参与判定的计划，忽略未指定产线的计划。 */
    private Map<Long, List<MfgProductionPlan>> groupByLine(List<MfgProductionPlan> booked) {
        Map<Long, List<MfgProductionPlan>> byLine = new LinkedHashMap<>();
        for (MfgProductionPlan plan : booked) {
            if (plan.getLineId() == null) {
                continue;
            }
            byLine.computeIfAbsent(plan.getLineId(), key -> new ArrayList<>()).add(plan);
        }
        return byLine;
    }

    /** 单条产线上按开工时间两两比对，重叠则记一条冲突。 */
    private List<Map<String, Object>> overlapRowsOnLine(List<MfgProductionPlan> onLine,
                                                        Map<Long, FactoryLine> lineCache) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Long, MfgProductionPlan> byId = indexOnLine(onLine);
        onLine.sort(Comparator.comparing(MfgProductionPlan::getPlannedStartTime));
        for (int i = 0; i < onLine.size(); i++) {
            rows.addAll(overlapRowsForLeft(onLine, i, byId, lineCache));
        }
        return rows;
    }

    /** 按计划主键索引同一条产线上的计划，未落库的计划进不了索引。 */
    private static Map<Long, MfgProductionPlan> indexOnLine(List<MfgProductionPlan> onLine) {
        Map<Long, MfgProductionPlan> byId = new LinkedHashMap<>();
        for (MfgProductionPlan plan : onLine) {
            if (plan.getPlanId() != null) {
                byId.put(plan.getPlanId(), plan);
            }
        }
        return byId;
    }

    /** 以 onLine[index] 为左表，向后比对所有可能重叠的计划。 */
    private List<Map<String, Object>> overlapRowsForLeft(List<MfgProductionPlan> onLine, int index,
                                                         Map<Long, MfgProductionPlan> byId,
                                                         Map<Long, FactoryLine> lineCache) {
        List<Map<String, Object>> rows = new ArrayList<>();
        MfgProductionPlan left = onLine.get(index);
        for (int j = index + 1; j < onLine.size(); j++) {
            MfgProductionPlan right = onLine.get(j);
            if (!right.getPlannedStartTime().isBefore(left.getPlannedEndTime())) {
                break;
            }
            if (isReportableOverlap(left, right, byId)) {
                rows.add(lineOverlapRow(left, right, lineCache));
            }
        }
        return rows;
    }

    // 组件与它的父计划现在排在同一条产线上（一张工单一条线做到底），
    // 父子时段重叠是这套排法本来的样子，不是"产能被重复承诺"，不能报冲突。
    private boolean isReportableOverlap(MfgProductionPlan left, MfgProductionPlan right,
                                        Map<Long, MfgProductionPlan> byId) {
        return overlaps(left, right) && !sameFamily(left, right, byId);
    }

    /**
     * 两张计划是否处于同一条父子链上（父计划与它派生出的组件计划）。
     *
     * <p>同一条产线上，根计划占住整机装配的时段，组件计划填在它前后的空档里，
     * 这正是"一张工单一条产线做到底"的排法，父子时段重叠属于预期，
     * 不能按"产能被重复承诺"记为同线冲突。</p>
     *
     * <p>但同一父计划下的两个兄弟组件仍然会抢同一段产能，那种重叠是真冲突，必须照报，
     * 所以这里只认祖先—后代关系，不认"同一家族"。</p>
     */
    private boolean sameFamily(MfgProductionPlan left, MfgProductionPlan right,
                               Map<Long, MfgProductionPlan> byId) {
        return isAncestor(left, right, byId) || isAncestor(right, left, byId);
    }

    /** candidate 是否是 plan 的祖先计划（沿 parent_plan_id 上溯）。 */
    private boolean isAncestor(MfgProductionPlan candidate, MfgProductionPlan plan,
                               Map<Long, MfgProductionPlan> byId) {
        MfgProductionPlan current = byId.get(plan.getParentPlanId());
        int guard = 0;
        while (current != null && guard++ < MAX_FAMILY_DEPTH) {
            if (Objects.equals(current.getPlanId(), candidate.getPlanId())) {
                return true;
            }
            current = current.getParentPlanId() == null ? null : byId.get(current.getParentPlanId());
        }
        return false;
    }

    /** 组装一条同线时段重叠冲突。 */
    private Map<String, Object> lineOverlapRow(MfgProductionPlan left, MfgProductionPlan right,
                                               Map<Long, FactoryLine> lineCache) {
        Map<String, Object> row = baseRow(right, lineCache);
        row.put("conflictType", TYPE_LINE_OVERLAP);
        row.put("conflictLabel", "同线时段重叠");
        row.put("relatedPlanNo", left.getPlanNo());
        row.put("relatedStartTime", left.getPlannedStartTime().toString());
        row.put("gapMinutes", overlapMinutes(left, right));
        row.put("reason", "产线 " + row.get("lineCode") + " 上计划 " + left.getPlanNo() + "（"
                + left.getPlannedStartTime() + " ~ " + left.getPlannedEndTime() + "）与 "
                + right.getPlanNo() + "（" + right.getPlannedStartTime() + " ~ "
                + right.getPlannedEndTime() + "）时段重叠 " + overlapMinutes(left, right)
                + " 分钟，产能被重复承诺。");
        return row;
    }

    /** 组装冲突行的公共字段。 */
    private Map<String, Object> baseRow(MfgProductionPlan plan, Map<Long, FactoryLine> lineCache) {
        FactoryLine line = resolveLine(plan, lineCache);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("planId", plan.getPlanId());
        row.put("planNo", plan.getPlanNo());
        row.put("planType", plan.getPlanType());
        row.put("planStatus", plan.getStatus());
        row.put("plannedQuantity", plan.getPlannedQuantity() == null ? BigDecimal.ZERO : plan.getPlannedQuantity());
        row.put("plannedStartTime", timeText(plan.getPlannedStartTime()));
        row.put("plannedEndTime", timeText(plan.getPlannedEndTime()));
        row.put("lineId", plan.getLineId());
        putLineInfo(row, line);
        return row;
    }

    /** 产线信息。取不到产线时两个字段都为 null。 */
    private static void putLineInfo(Map<String, Object> row, FactoryLine line) {
        row.put("lineCode", line == null ? null : line.getLineCode());
        row.put("lineName", line == null ? null : line.getLineName());
    }

    /** 取计划所属产线；未指定产线时返回空，同一产线只查一次。 */
    private FactoryLine resolveLine(MfgProductionPlan plan, Map<Long, FactoryLine> lineCache) {
        if (plan.getLineId() == null) {
            return null;
        }
        return lineCache.computeIfAbsent(plan.getLineId(), lines::selectById);
    }

    /** 时间转文本，空值保持空。 */
    private static String timeText(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.toString();
    }

    private static boolean overlaps(MfgProductionPlan left, MfgProductionPlan right) {
        return right.getPlannedStartTime().isBefore(left.getPlannedEndTime())
                && left.getPlannedStartTime().isBefore(right.getPlannedEndTime());
    }

    private static long overlapMinutes(MfgProductionPlan left, MfgProductionPlan right) {
        LocalDateTime start = laterOf(left.getPlannedStartTime(), right.getPlannedStartTime());
        LocalDateTime end = earlierOf(left.getPlannedEndTime(), right.getPlannedEndTime());
        return minutes(end, start);
    }

    /** 取两个时间中较晚的一个。 */
    private static LocalDateTime laterOf(LocalDateTime left, LocalDateTime right) {
        if (left.isAfter(right)) {
            return left;
        }
        return right;
    }

    /** 取两个时间中较早的一个。 */
    private static LocalDateTime earlierOf(LocalDateTime left, LocalDateTime right) {
        if (left.isBefore(right)) {
            return left;
        }
        return right;
    }

    /** 目标时间与基准时间相差多少分钟；晚于基准时返回正数。 */
    private static long minutes(LocalDateTime target, LocalDateTime base) {
        if (target == null || base == null) {
            return 0L;
        }
        return java.time.Duration.between(base, target).toMinutes();
    }

    private static Long countType(List<Map<String, Object>> items, String type) {
        return items.stream().filter(item -> Objects.equals(type, item.get("conflictType"))).count();
    }
}
