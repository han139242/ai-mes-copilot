package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.mfg.MfgLineCalendarRule;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.org.OrgShift;
import com.neuedu.ems.entity.process.ProcessRoute;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgLineCalendarRuleMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.org.OrgShiftMapper;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 组件补产计划的可执行时间窗口计算。
 *
 * <p>组件计划不能复制父计划时间：父计划落在整机线上，组件却在自己那条组件线上生产，
 * 两条线的班次、周历、日产能和已排占用都不同。这里按组件自身产线的有效周历逐段计算可用产能：
 * 每段产能 = 日产能 × 能力系数 × 周历系数 × 该段时长 ÷ 当日班次总时长（与排程场景 {@code RULE_V1} 同一口径）。</p>
 *
 * <p>能力系数来自 {@code mfg_line_product_capability.capacity_factor}：同一条线做整机与做半成品的
 * 单件工时不同，日产能只有一个基数，就用这个系数表达"这条线折算给该产品每天能做多少件"。
 * 例如整机单件 6 分钟、灯头单件 2 分钟，灯头系数就是 3，产线 80 件/天对灯头即 240 件/天。</p>
 *
 * <p>同一产品路线上的多道装配工序是串行工序、共用同一条线的节拍，因此按"单件总工时"合成一道工序
 * 统一消费产能，不能每道工序各扣一遍整线产能，否则多工序组件的可排数量会被凭空缩小 2~3 倍。</p>
 *
 * <p>排产规则：装配工序按整件拆段，单件不切成小数产出；批次工序（如连续老化）必须落在一段
 * 连续窗口内，不允许把两段拼成一批。窗口不够时返回"本次只能排多少"，由调用方决定拆成多张补产单；
 * 完全排不进任何窗口才算产能不足，不做"先排上再说"的静默处理。</p>
 */
@Service
public class ComponentCapacityService {
    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Shanghai");
    /** 一次排产向后寻找窗口的最大天数；超出即判定该窗口排不下，由调用方拆单或阻断。 */
    private static final int HORIZON_DAYS = 7;
    private static final int WORK_ORDER_HORIZON_DAYS = 84;
    /** 已占用计划的状态口径：草稿也算占用，避免同一批补产在草稿阶段互相重叠。 */
    private static final List<String> BOOKED_STATUSES = List.of("DRAFT", "RELEASED", "RUNNING", "FINISHED");
    private static final int CAPACITY_SCALE = 8;
    /**
     * 生效复核允许的完工时间偏差（秒）。
     *
     * <p>草稿生成时窗口从父计划开始时间起算，生效复核时窗口从计划自己的开始时间起算，
     * 同一段班次被裁剪后产能基数不同，件数折算成时间时又分别用了 CEILING/DOWN 取整，
     * 因此同一份计划重算出的完工时间可能比草稿晚 1 秒左右。这点偏差不代表产能真的不足，
     * 若不放宽会把本来排得下的组件补产计划判成"请重建草稿"，导致组件工单无法下达。</p>
     */
    private static final long SCHEDULE_TOLERANCE_SECONDS = 60;

    private final ProcessRouteItemMapper routeItems;
    private final MfgLineCalendarRuleMapper calendarRules;
    private final OrgShiftMapper shifts;
    private final MfgProductionPlanMapper plans;

    public ComponentCapacityService(ProcessRouteItemMapper routeItems, MfgLineCalendarRuleMapper calendarRules,
                                    OrgShiftMapper shifts, MfgProductionPlanMapper plans) {
        this.routeItems = routeItems;
        this.calendarRules = calendarRules;
        this.shifts = shifts;
        this.plans = plans;
    }

    /**
     * 一次排产结果。
     *
     * @param start       实际开工时间（落在有效工作时段内）
     * @param end         实际完工时间
     * @param produced    本次窗口能排下的件数；小于请求量时由调用方拆成多张补产单
     * @param explanation 排产依据，写进计划备注便于核对
     */
    public record Plan(LocalDateTime start, LocalDateTime end, BigDecimal produced, String explanation) {}

    /**
     * 按组件自身产线的周历、班次、日产能与已排占用，排出尽可能多的件数。
     *
     * @param line          组件生产的产线（与父计划同线时就是父计划那条线）
     * @param route         组件已发布路线
     * @param quantity      希望排下的补产数量
     * @param earliestStart 不得早于该时刻
     * @param excludePlanId 计算占用时要排除的计划（计划生效复核时排除自身），新建时传 null
     */
    public Plan plan(FactoryLine line, ProcessRoute route, BigDecimal quantity,
                     LocalDateTime earliestStart, Long excludePlanId) {
        return plan(line, route, quantity, earliestStart, excludePlanId, null, BigDecimal.ONE);
    }

    /**
     * 同上，但可以额外排除一组计划。
     *
     * <p>组件与父计划排在同一条产线时，父子共用这条线的产能：父计划占住的时段不该再从组件
     * 可用时段里扣一次，否则组件会被整体推到整机装完之后，变成"先整机后半成品"。
     * 产能口径不变——仍然是"日产能 × 能力系数"，所以同线共做一件产品所需的工时，
     * 由能力系数表达，不会凭空多出产能。</p>
     *
     * @param alsoExcludedPlanIds 额外不参与占用计算的计划（通常是父计划及其祖先链），可为 null
     */
    public Plan plan(FactoryLine line, ProcessRoute route, BigDecimal quantity,
                     LocalDateTime earliestStart, Long excludePlanId,
                     java.util.Collection<Long> alsoExcludedPlanIds) {
        return plan(line, route, quantity, earliestStart, excludePlanId, alsoExcludedPlanIds, BigDecimal.ONE);
    }

    /**
     * 同上，并指定这条产线折算给该组件产品的能力系数。
     *
     * @param capacityFactor 产线对该组件产品的日产能倍数（{@code mfg_line_product_capability.capacity_factor}），
     *                       为 null 或非正数时按 1 处理
     */
    public Plan plan(FactoryLine line, ProcessRoute route, BigDecimal quantity,
                     LocalDateTime earliestStart, Long excludePlanId,
                     java.util.Collection<Long> alsoExcludedPlanIds, BigDecimal capacityFactor) {
        validateRequest(line, quantity);
        return planSteps(line, mergedSteps(route), quantity, earliestStart, excludePlanId,
                alsoExcludedPlanIds, capacityFactor);
    }

    /** 派工复核读取工单冻结工艺，使用与排产相同的日历、整件和完整批次算法。 */
    public Plan planExecution(FactoryLine line, List<ProcessRouteItem> frozenItems, BigDecimal quantity,
                              LocalDateTime earliestStart, Long planId, BigDecimal capacityFactor) {
        validateRequest(line, quantity);
        if (frozenItems == null || frozenItems.isEmpty()) throw invalid("工单没有有效冻结工序");
        return planSteps(line, mergeSteps(frozenItems.stream().map(this::stepOf).toList()), quantity,
                earliestStart, planId, null, capacityFactor, WORK_ORDER_HORIZON_DAYS);
    }

    /** 完整工单可跨多个周历窗口；7天窗口只用于组件拆单，不能截断整机的完整工时。 */
    public Plan planWorkOrder(FactoryLine line, ProcessRoute route, BigDecimal quantity,
                              LocalDateTime earliestStart, Long planId, BigDecimal capacityFactor) {
        return planWorkOrder(line, route, quantity, earliestStart, planId, capacityFactor, null);
    }

    /** 连锁顺延时，后续待重新安排的计划暂不作为旧占用；已执行计划仍保留占用。 */
    public Plan planWorkOrder(FactoryLine line, ProcessRoute route, BigDecimal quantity,
                              LocalDateTime earliestStart, Long planId, BigDecimal capacityFactor,
                              java.util.Collection<Long> alsoExcludedPlanIds) {
        validateRequest(line, quantity);
        return planSteps(line, mergedSteps(route), quantity, earliestStart, planId, alsoExcludedPlanIds, capacityFactor, WORK_ORDER_HORIZON_DAYS);
    }

    private void validateRequest(FactoryLine line, BigDecimal quantity) {
        if (line == null || line.getLineId() == null) {
            throw invalid("组件计划缺少产线，不能计算可执行窗口");
        }
        if (!oneOf(line.getStatus(), "ENABLE", "RUNNING")) {
            throw invalid("组件产线未启用，不能计算可执行窗口");
        }
        if (line.getDailyCapacity() == null || line.getDailyCapacity().signum() <= 0) {
            throw invalid("组件产线未配置有效日产能，不能计算可执行窗口");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw invalid("组件补产数量必须大于 0，不能计算可执行窗口");
        }
    }

    private Plan planSteps(FactoryLine line, List<Step> steps, BigDecimal quantity,
                               LocalDateTime earliestStart, Long excludePlanId,
                               java.util.Collection<Long> alsoExcludedPlanIds, BigDecimal capacityFactor) {
        return planSteps(line, steps, quantity, earliestStart, excludePlanId, alsoExcludedPlanIds, capacityFactor, HORIZON_DAYS);
    }

    private Plan planSteps(FactoryLine line, List<Step> steps, BigDecimal quantity,
                               LocalDateTime earliestStart, Long excludePlanId,
                               java.util.Collection<Long> alsoExcludedPlanIds, BigDecimal capacityFactor, int horizonDays) {
        BigDecimal factor = factorOf(capacityFactor);
        LocalDateTime from = startOf(earliestStart);
        List<CapacityWindow> windows = buildWindows(line, from, excludePlanId, alsoExcludedPlanIds, factor, horizonDays);
        // 一件都排不下就是明确阻断，不做"先排上再说"；能排一部分则返回部分数量由调用方拆单。
        WindowRun run = consumeSteps(steps, windows, quantity);
        if (run.produced().signum() <= 0) {
            throw invalid("组件产线在排程窗口内没有可用产能窗口，无法排产");
        }
        return new Plan(orDefault(run.start(), from), orDefault(run.end(), from),
                run.produced(), run.basis().toString() + factorSuffix(factor));
    }

    /** 排程起点：未指定时取当前时间（截到秒）。 */
    private LocalDateTime startOf(LocalDateTime earliestStart) {
        LocalDateTime start = earliestStart == null ? LocalDateTime.now() : earliestStart;
        return start.withNano(0);
    }

    /** 未产出任何窗口时回退到排程起点。 */
    private LocalDateTime orDefault(LocalDateTime value, LocalDateTime fallback) {
        return value == null ? fallback : value;
    }

    /** 铺开可排窗口并扣掉同线已占用的时段。 */
    private List<CapacityWindow> buildWindows(FactoryLine line, LocalDateTime from, Long excludePlanId,
                                              java.util.Collection<Long> alsoExcludedPlanIds, BigDecimal factor, int horizonDays) {
        LocalDateTime horizonEnd = from.plusDays(horizonDays);
        List<CapacityWindow> windows = capacityWindows(line, from, horizonEnd, factor);
        if (windows.isEmpty()) {
            throw invalid("组件产线在排程窗口内没有有效产能（缺少启用周历或班次）");
        }
        for (Interval booking : bookings(line.getLineId(), from, horizonEnd, excludePlanId, alsoExcludedPlanIds)) {
            occupy(windows, booking.start(), booking.end());
        }
        return windows;
    }

    /** 逐道工序消费产能，返回首末时段、最终可排件数与口径说明。 */
    private WindowRun consumeSteps(List<Step> steps, List<CapacityWindow> windows, BigDecimal quantity) {
        StringBuilder basis = new StringBuilder();
        LocalDateTime start = null;
        LocalDateTime end = null;
        BigDecimal produced = quantity;
        for (Step step : steps) {
            BigDecimal pieces = produced.min(quantity);
            if (pieces.signum() <= 0) {
                break;
            }
            Consumed consumed = consumeStep(step, windows, pieces);
            if (start == null && consumed.end() != null) {
                start = consumed.start();
            }
            if (consumed.end() != null) {
                end = consumed.end();
            }
            produced = produced.min(consumed.pieces());
            basis.append(stepText(step, consumed));
        }
        return new WindowRun(start, end, produced, basis);
    }

    /** 单道工序按批次或按装配工时消费产能。 */
    private Consumed consumeStep(Step step, List<CapacityWindow> windows, BigDecimal pieces) {
        Step sized = step.withQuantity(pieces);
        return step.batch() ? consumeBatch(windows, sized) : consumeLabor(windows, sized);
    }

    /** 一段可使用窗口的消费结果，用于拼接计划说明。 */
    private record WindowRun(LocalDateTime start, LocalDateTime end, BigDecimal produced, StringBuilder basis) {}

    private static String stepText(Step step, Consumed consumed) {
        StringBuilder text = new StringBuilder();
        text.append(step.batch() ? "批次" : "装配")
                .append(consumed.pieces().stripTrailingZeros().toPlainString()).append("件");
        if (step.batch()) {
            text.append("×").append(step.batchMinutes().stripTrailingZeros().toPlainString()).append("min");
        }
        return text.append("；").toString();
    }

    /** 能力系数不是 1 时在说明里标出来。 */
    private static String factorSuffix(BigDecimal factor) {
        if (factor.compareTo(BigDecimal.ONE) == 0) {
            return "";
        }
        return "能力系数" + factor.stripTrailingZeros().toPlainString();
    }

    /** 能力系数缺失或非正数时按 1 处理，避免一条坏数据把整条线判成零产能。 */
    private static BigDecimal factorOf(BigDecimal capacityFactor) {
        if (capacityFactor == null || capacityFactor.signum() <= 0) {
            return BigDecimal.ONE;
        }
        return capacityFactor;
    }

    /**
     * 把路线上连续的多道装配工序合成一道：单件总工时相加。
     *
     * <p>多道装配工序串行做同一件产品，产线节拍是各工序工时之和，因此只需按"总工时"消费一次产能；
     * 若每道工序各扣一遍整线产能，多工序组件的可排数量会被缩小成 1/工序数。批次工序（连续老化等）
     * 本身按批次时长占窗口，与装配工序口径不同，仍单独成步。</p>
     */
    private List<Step> mergedSteps(ProcessRoute route) {
        return mergeSteps(steps(route));
    }

    private List<Step> mergeSteps(List<Step> raw) {
        List<Step> merged = new ArrayList<>();
        BigDecimal laborMinutes = null;
        for (Step step : raw) {
            if (step.batch()) {
                flushLabor(merged, laborMinutes);
                // 批次工序把前面攒的装配工时截断，后续装配工时重新从零累计。
                laborMinutes = null;
                merged.add(step);
                continue;
            }
            laborMinutes = laborMinutes == null ? step.unitMinutes() : laborMinutes.add(step.unitMinutes());
        }
        flushLabor(merged, laborMinutes);
        return merged;
    }

    /** 把已累计的装配工时落成一道合成工序；没有累计值时不产生任何步骤。 */
    private static void flushLabor(List<Step> merged, BigDecimal laborMinutes) {
        if (laborMinutes == null) {
            return;
        }
        merged.add(new Step(false, BigDecimal.ZERO, laborMinutes, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /**
     * 计划生效时复核：草稿保存后周历可能被停用、日产能可能被下调、同线可能已被别的计划占用，
     * 因此必须用同一套计算再算一次；排不满或超出原计划结束时间就阻断。
     */
    public void assertSchedulable(FactoryLine line, ProcessRoute route, BigDecimal quantity,
                                  LocalDateTime plannedStart, LocalDateTime plannedEnd, Long planId) {
        assertSchedulable(line, route, quantity, plannedStart, plannedEnd, planId, BigDecimal.ONE);
    }

    /** 同上，并指定这条产线折算给该组件产品的能力系数。 */
    public void assertSchedulable(FactoryLine line, ProcessRoute route, BigDecimal quantity,
                                  LocalDateTime plannedStart, LocalDateTime plannedEnd, Long planId,
                                  BigDecimal capacityFactor) {
        assertSchedulable(line, route, quantity, plannedStart, plannedEnd, planId, capacityFactor, null);
    }

    /**
     * 同上，并排除同一条产线上属于父计划家族的计划占用。
     *
     * <p>组件与父计划同线时，生成阶段已经用这份祖先链排除过一次占用；生效复核若不再排除，
     * 父计划占住的时段会被重复计入，重算结果就会比草稿晚一整段，把本来正常的组件计划判成不可排。
     * 因此复核必须与生成用同一套口径。</p>
     *
     * @param alsoExcludedPlanIds 额外不参与占用计算的计划（父计划及其祖先链），可为 null
     */
    public void assertSchedulable(FactoryLine line, ProcessRoute route, BigDecimal quantity,
                                  LocalDateTime plannedStart, LocalDateTime plannedEnd, Long planId,
                                  BigDecimal capacityFactor,
                                  java.util.Collection<Long> alsoExcludedPlanIds) {
        if (plannedStart == null || plannedEnd == null) {
            throw invalid("组件计划缺少开始或结束时间，请重建草稿");
        }
        Plan plan = plan(line, route, quantity, plannedStart, planId, alsoExcludedPlanIds, capacityFactor);
        if (plan.produced().compareTo(quantity) < 0) {
            throw invalid("组件计划已不在有效日历产能窗口内（当前窗口只能排 "
                    + plan.produced().stripTrailingZeros().toPlainString() + " 件，计划为 "
                    + quantity.stripTrailingZeros().toPlainString() + " 件），请重建草稿");
        }
        // 只放宽"重算比草稿晚一点"的取整偏差；超出容忍窗口说明产能确实变了，仍要阻断重建。
        if (plan.end().isAfter(plannedEnd.plusSeconds(SCHEDULE_TOLERANCE_SECONDS))) {
            throw invalid("组件计划结束时间早于有效日历产能窗口（重算需到 " + plan.end()
                    + "，计划结束为 " + plannedEnd + "），请重建草稿");
        }
    }

    /** 一条路线上的工序清单；批次工序的批量与批次时长必须完整。 */
    public List<Step> steps(ProcessRoute route) {
        if (route == null || route.getRouteId() == null) {
            throw invalid("组件计划缺少工艺路线，不能计算可执行窗口");
        }
        List<ProcessRouteItem> items = routeItems.selectList(new LambdaQueryWrapper<ProcessRouteItem>()
                .eq(ProcessRouteItem::getRouteId, route.getRouteId())
                .eq(ProcessRouteItem::getDeletedFlag, 0)
                .orderByAsc(ProcessRouteItem::getSequenceNo));
        if (items.isEmpty()) {
            throw invalid("组件工艺路线没有有效工序，不能计算可执行窗口");
        }
        List<Step> steps = new ArrayList<>();
        for (ProcessRouteItem item : items) {
            steps.add(stepOf(item));
        }
        return steps;
    }

    /** 把一条路线明细换算成排产步骤，批次设备工序与装配工序分别校验各自参数。 */
    private Step stepOf(ProcessRouteItem item) {
        if ("BATCH_EQUIPMENT".equals(item.getResourceType())) {
            return batchStep(item);
        }
        return laborStep(item);
    }

    /** 批次设备工序：批量与批次时长都必须为正数。 */
    private Step batchStep(ProcessRouteItem item) {
        if (item.getBatchCapacity() == null || item.getBatchCapacity().signum() <= 0
                || item.getBatchDurationMinutes() == null || item.getBatchDurationMinutes().signum() <= 0) {
            throw invalid("组件工艺路线的批次工序缺少有效批量或批次时长，不能计算可执行窗口");
        }
        return new Step(true, BigDecimal.ZERO, BigDecimal.ZERO,
                item.getBatchCapacity(), item.getBatchDurationMinutes());
    }

    /** 装配工序：标准工时必须为正数。 */
    private Step laborStep(ProcessRouteItem item) {
        if (item.getStandardTime() == null || item.getStandardTime().signum() <= 0) {
            throw invalid("组件工艺路线缺少有效标准工时，不能计算可执行窗口");
        }
        return new Step(false, BigDecimal.ZERO, item.getStandardTime(), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * 一个工序的排产参数。
     *
     * @param batch         是否批次设备工序（如连续老化）
     * @param quantity      本次要处理的件数
     * @param unitMinutes   装配工序单件标准工时；批次工序为 0
     * @param batchCapacity 批次工序单批件数；装配工序为 0
     * @param batchMinutes  批次工序单批占用分钟；装配工序为 0
     */
    public record Step(boolean batch, BigDecimal quantity, BigDecimal unitMinutes,
                       BigDecimal batchCapacity, BigDecimal batchMinutes) {
        Step withQuantity(BigDecimal pieces) {
            return new Step(batch, pieces, unitMinutes, batchCapacity, batchMinutes);
        }

        BigDecimal blocks() {
            return quantity.divide(batchCapacity, 0, RoundingMode.CEILING);
        }
    }

    private record Consumed(LocalDateTime start, LocalDateTime end, BigDecimal pieces) {}

    /** 装配工序按整件拆段：每段只排得下完整件数，单件不切成小数产出。 */
    private Consumed consumeLabor(List<CapacityWindow> windows, Step step) {
        BigDecimal remaining = step.quantity();
        LocalDateTime start = null;
        LocalDateTime end = null;
        for (CapacityWindow window : windows) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal freePieces = window.freePieces().setScale(0, RoundingMode.FLOOR);
            BigDecimal take = freePieces.min(remaining);
            if (take.signum() <= 0) {
                continue;
            }
            if (start == null) {
                start = window.currentTime();
            }
            end = window.consumePieces(take);
            remaining = remaining.subtract(take);
        }
        return new Consumed(start, end, step.quantity().subtract(remaining));
    }

    /** 批次工序（连续老化等）必须落在一段连续窗口内，不允许拆成两段拼接。 */
    private Consumed consumeBatch(List<CapacityWindow> windows, Step step) {
        long blockSeconds = step.batchMinutes().multiply(BigDecimal.valueOf(60))
                .setScale(0, RoundingMode.CEILING).longValue();
        int required = step.blocks().intValueExact();
        LocalDateTime start = null;
        LocalDateTime end = null;
        int done = 0;
        for (int i = 0; i < required; i++) {
            CapacityWindow target = firstWindowFitting(windows, blockSeconds);
            if (target == null) {
                break;
            }
            if (start == null) {
                start = target.currentTime();
            }
            end = target.consumeSeconds(blockSeconds);
            done++;
        }
        BigDecimal pieces = step.batchCapacity().multiply(BigDecimal.valueOf(done)).min(step.quantity());
        return new Consumed(start, end, pieces);
    }

    /** 找出第一段还能容下整批时长的产能窗口；都放不下时返回 null。 */
    private CapacityWindow firstWindowFitting(List<CapacityWindow> windows, long blockSeconds) {
        for (CapacityWindow window : windows) {
            if (window.freeSeconds() >= blockSeconds) {
                return window;
            }
        }
        return null;
    }

    private record Interval(LocalDateTime start, LocalDateTime end) {
        long seconds() {
            return Duration.between(start, end).toSeconds();
        }
    }

    /** 一段有产能的工作时段：按整数秒推进游标，避免件数占比反复换算丢失整批所需的一秒。 */
    private static final class CapacityWindow {
        private final LocalDateTime start;
        private final LocalDateTime end;
        private final BigDecimal capacityNumerator;
        private final long capacityDenominator;
        private long usedSeconds;

        private CapacityWindow(LocalDateTime start, LocalDateTime end, BigDecimal capacityNumerator,
                               long capacityDenominator) {
            this.start = start;
            this.end = end;
            this.capacityNumerator = capacityNumerator;
            this.capacityDenominator = capacityDenominator;
        }

        private BigDecimal freePieces() {
            long total = Duration.between(start, end).toSeconds();
            if (total <= 0) return BigDecimal.ZERO;
            return capacityNumerator.multiply(BigDecimal.valueOf(freeSeconds()))
                    .divide(BigDecimal.valueOf(capacityDenominator), CAPACITY_SCALE, RoundingMode.DOWN);
        }

        private long freeSeconds() {
            return Math.max(0L, Duration.between(start, end).toSeconds() - usedSeconds);
        }

        private LocalDateTime consumePieces(BigDecimal pieces) {
            long seconds = pieces.multiply(BigDecimal.valueOf(capacityDenominator))
                    .divide(capacityNumerator, 0, RoundingMode.CEILING).longValueExact();
            return consumeSeconds(seconds);
        }

        private LocalDateTime consumeSeconds(long seconds) {
            long total = Duration.between(start, end).toSeconds();
            if (total <= 0 || capacityNumerator.signum() <= 0) {
                return start;
            }
            usedSeconds = Math.min(total, usedSeconds + Math.max(0L, seconds));
            return currentTime();
        }

        private LocalDateTime currentTime() {
            return start.plusSeconds(usedSeconds);
        }
    }

    /** 由启用周历与班次生成产能时段；日产能按当日班次总时长分摊到每段，并按能力系数放大。 */
    private List<CapacityWindow> capacityWindows(FactoryLine line, LocalDateTime from, LocalDateTime to,
                                                 BigDecimal capacityFactor) {
        List<MfgLineCalendarRule> rules = calendarRules.selectList(new LambdaQueryWrapper<MfgLineCalendarRule>()
                .eq(MfgLineCalendarRule::getLineId, line.getLineId())
                .eq(MfgLineCalendarRule::getStatus, "ENABLE")
                .eq(MfgLineCalendarRule::getDeletedFlag, 0));
        if (rules.isEmpty()) {
            throw invalid("组件产线没有启用中的产线周历，不能计算可执行窗口");
        }
        Map<Long, OrgShift> shiftsById = new HashMap<>();
        List<CapacityWindow> windows = new ArrayList<>();
        LocalDate date = from.toLocalDate();
        LocalDate endDate = to.toLocalDate();
        while (!date.isAfter(endDate)) {
            appendDayWindows(windows, line, rules, shiftsById, date, from, to, capacityFactor);
            date = date.plusDays(1);
        }
        windows.sort(Comparator.comparing(window -> window.start));
        return windows;
    }

    /** 追加某一天的产能时段：先算当日班次总时长，再按各段裁剪时长分摊日产能。 */
    private void appendDayWindows(List<CapacityWindow> windows, FactoryLine line, List<MfgLineCalendarRule> rules,
                                  Map<Long, OrgShift> shiftsById, LocalDate date,
                                  LocalDateTime from, LocalDateTime to, BigDecimal capacityFactor) {
        List<ShiftSegment> segments = new ArrayList<>();
        long fullDaySeconds = collectDaySegments(segments, rules, shiftsById, date, from, to);
        if (fullDaySeconds <= 0) {
            return;
        }
        for (ShiftSegment segment : segments) {
            CapacityWindow window = windowOf(line, segment, fullDaySeconds, capacityFactor);
            if (window != null) {
                windows.add(window);
            }
        }
    }

    /** 收集某天所有有效班次片段，并返回当日班次总秒数。 */
    private long collectDaySegments(List<ShiftSegment> segments, List<MfgLineCalendarRule> rules,
                                    Map<Long, OrgShift> shiftsById, LocalDate date,
                                    LocalDateTime from, LocalDateTime to) {
        Map<Long, MfgLineCalendarRule> rulesByShift = latestRulesForDate(rules, date);
        long fullDaySeconds = 0L;
        for (MfgLineCalendarRule rule : rulesByShift.values()) {
            OrgShift shift = shiftsById.computeIfAbsent(rule.getShiftId(), shifts::selectById);
            if (!isUsableShift(shift, rule)) {
                continue;
            }
            LocalDateTime fullStart = at(date, shift.getStartTime());
            LocalDateTime fullEnd = fullEndOf(date, shift);
            if (!fullEnd.isAfter(fullStart)) {
                continue;
            }
            fullDaySeconds += Duration.between(fullStart, fullEnd).toSeconds();
            ShiftSegment segment = clipSegment(fullStart, fullEnd, rule, from, to);
            if (segment != null) {
                segments.add(segment);
            }
        }
        return fullDaySeconds;
    }

    /** 班次与周历系数必须完整有效才参与产能计算。 */
    private boolean isUsableShift(OrgShift shift, MfgLineCalendarRule rule) {
        if (shift == null || shift.getStartTime() == null || shift.getEndTime() == null) {
            return false;
        }
        return rule.getCapacityFactor() != null && rule.getCapacityFactor().signum() > 0;
    }

    /** 班次结束时间早于开始时间表示跨夜，结束时刻顺延一天。 */
    private LocalDateTime fullEndOf(LocalDate date, OrgShift shift) {
        LocalDateTime fullEnd = at(date, shift.getEndTime());
        if (shift.getEndTime().isBefore(shift.getStartTime())) {
            return fullEnd.plusDays(1);
        }
        return fullEnd;
    }

    /** 把班次时段裁剪到查询窗口内；没有交集时返回 null。 */
    private ShiftSegment clipSegment(LocalDateTime fullStart, LocalDateTime fullEnd, MfgLineCalendarRule rule,
                                     LocalDateTime from, LocalDateTime to) {
        LocalDateTime start = fullStart.isBefore(from) ? from : fullStart;
        LocalDateTime end = fullEnd.isAfter(to) ? to : fullEnd;
        if (!end.isAfter(start)) {
            return null;
        }
        return new ShiftSegment(start, end, rule.getCapacityFactor());
    }

    /** 按片段时长占比把日产能分摊到该片段，产能不足正数时不生成窗口。 */
    private CapacityWindow windowOf(FactoryLine line, ShiftSegment segment, long fullDaySeconds,
                                    BigDecimal capacityFactor) {
        long clippedSeconds = Duration.between(segment.start(), segment.end()).toSeconds();
        BigDecimal capacity = line.getDailyCapacity()
                .multiply(factorOf(capacityFactor))
                .multiply(segment.capacityFactor());
        if (capacity.signum() <= 0 || clippedSeconds <= 0 || fullDaySeconds <= 0) {
            return null;
        }
        return new CapacityWindow(segment.start(), segment.end(), capacity, fullDaySeconds);
    }

    private record ShiftSegment(LocalDateTime start, LocalDateTime end, BigDecimal capacityFactor) {}

    /** 取某天每个班次最新生效的一条周历规则。 */
    private Map<Long, MfgLineCalendarRule> latestRulesForDate(List<MfgLineCalendarRule> rules, LocalDate date) {
        Map<Long, MfgLineCalendarRule> result = new HashMap<>();
        int dayOfWeek = date.getDayOfWeek().getValue();
        for (MfgLineCalendarRule rule : rules) {
            if (!appliesToDate(rule, dayOfWeek, date)) {
                continue;
            }
            MfgLineCalendarRule existing = result.get(rule.getShiftId());
            if (existing == null || rule.getEffectiveFrom().isAfter(existing.getEffectiveFrom())) {
                result.put(rule.getShiftId(), rule);
            }
        }
        return result;
    }

    /** 周历规则是否在指定星期且处于生效区间内。 */
    private boolean appliesToDate(MfgLineCalendarRule rule, int dayOfWeek, LocalDate date) {
        if (!Objects.equals(rule.getDayOfWeek(), dayOfWeek)) {
            return false;
        }
        if (rule.getEffectiveFrom() == null || rule.getEffectiveFrom().isAfter(date)) {
            return false;
        }
        return rule.getEffectiveTo() == null || !rule.getEffectiveTo().isBefore(date);
    }

    /** 同线已占用计划（排除自身），占用时段内可用产能按重叠比例扣减。 */
    private List<Interval> bookings(Long lineId, LocalDateTime from, LocalDateTime to, Long excludePlanId) {
        return bookings(lineId, from, to, excludePlanId, null);
    }

    /** 同线已占用计划（排除指定计划），占用时段内可用产能按重叠比例扣减。 */
    private List<Interval> bookings(Long lineId, LocalDateTime from, LocalDateTime to, Long excludePlanId,
                                    java.util.Collection<Long> alsoExcludedPlanIds) {
        List<MfgProductionPlan> booked = plans.selectList(new LambdaQueryWrapper<MfgProductionPlan>()
                .eq(MfgProductionPlan::getLineId, lineId)
                .eq(MfgProductionPlan::getDeletedFlag, 0)
                .in(MfgProductionPlan::getStatus, BOOKED_STATUSES)
                .lt(MfgProductionPlan::getPlannedStartTime, to)
                .gt(MfgProductionPlan::getPlannedEndTime, from));
        List<Interval> result = new ArrayList<>();
        for (MfgProductionPlan plan : booked) {
            if (isCountedBooking(plan, excludePlanId, alsoExcludedPlanIds)) {
                result.add(new Interval(plan.getPlannedStartTime(), plan.getPlannedEndTime()));
            }
        }
        result.sort(Comparator.comparing(Interval::start));
        return result;
    }

    /** 该计划是否算作占用：排除自身与被显式排除的计划，且起止时间完整有效。 */
    private boolean isCountedBooking(MfgProductionPlan plan, Long excludePlanId,
                                     java.util.Collection<Long> alsoExcludedPlanIds) {
        if (excludePlanId != null && excludePlanId.equals(plan.getPlanId())) {
            return false;
        }
        if (alsoExcludedPlanIds != null && alsoExcludedPlanIds.contains(plan.getPlanId())) {
            return false;
        }
        return plan.getPlannedStartTime() != null
                && plan.getPlannedEndTime() != null
                && plan.getPlannedEndTime().isAfter(plan.getPlannedStartTime());
    }

    /** 从日历中切除真实占用区间，保留其前后的空档；重叠占用只扣除一次。 */
    private void occupy(List<CapacityWindow> windows, LocalDateTime start, LocalDateTime end) {
        List<CapacityWindow> free = new ArrayList<>();
        for (CapacityWindow window : windows) {
            if (!end.isAfter(window.start) || !start.isBefore(window.end)) {
                free.add(window);
                continue;
            }
            if (start.isAfter(window.start)) addFreeFragment(free, window, window.start, start);
            if (end.isBefore(window.end)) addFreeFragment(free, window, end, window.end);
        }
        windows.clear();
        windows.addAll(free);
    }

    private void addFreeFragment(List<CapacityWindow> target, CapacityWindow original,
                                 LocalDateTime start, LocalDateTime end) {
        long seconds = Duration.between(start, end).toSeconds();
        if (seconds <= 0) return;
        target.add(new CapacityWindow(start, end, original.capacityNumerator, original.capacityDenominator));
    }

    private static LocalDateTime at(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, SCHEDULE_ZONE).toLocalDateTime();
    }

    private static boolean oneOf(String value, String... allowed) {
        return value != null && List.of(allowed).contains(value);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message);
    }
}
