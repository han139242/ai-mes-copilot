package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.factory.*;
import com.neuedu.ems.entity.mfg.*;
import com.neuedu.ems.entity.org.*;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.factory.*;
import com.neuedu.ems.mapper.mfg.*;
import com.neuedu.ems.mapper.org.*;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import com.neuedu.ems.vo.mfg.DispatchPreparationVO;
import com.neuedu.ems.vo.mfg.DispatchPreparationVO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** 将供给依赖、冻结工时、日历、工位和班组日期排班统一为派工前置条件。 */
@Service
@RequiredArgsConstructor
public class DispatchPreparationService {
    private final MfgWorkOrderMaterialMapper requirements;
    private final MfgSupplySourceMapper sources;
    private final MfgWorkOrderMapper workOrders;
    private final MfgDispatchOrderMapper dispatches;
    private final FactoryLineMapper lines;
    private final FactoryStationMapper stations;
    private final FactoryStationOperationMapper stationOperations;
    private final MfgLineProductCapabilityMapper capabilities;
    private final MfgLineCalendarRuleMapper calendars;
    private final OrgShiftMapper shifts;
    private final OrgTeamScheduleMapper schedules;
    private final OrgTeamMapper teams;
    private final ProcessRouteItemMapper routeItems;
    private final ExecutionSnapshotPolicy snapshots;
    private final ComponentCapacityService capacity;

    public DispatchPreparationVO prepare(MfgWorkOrder wo, MfgProductionPlan plan) {
        List<String> blockers = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<ComponentDependency> dependencies = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime earliest = wo.getPlannedStartTime() == null ? plan.getPlannedStartTime() : wo.getPlannedStartTime();
        if (earliest == null || earliest.isBefore(now)) earliest = now;
        earliest = dependencyStart(wo, earliest, dependencies, blockers);
        var line = lines.selectById(plan.getLineId());
        if (line == null || !Integer.valueOf(0).equals(line.getDeletedFlag())) throw invalid("计划产线不存在");
        List<ProcessRouteItem> steps = routeSteps(wo);
        if (steps.isEmpty()) blockers.add("工单没有有效工序，请维护工艺路线");
        appendStationBlockers(steps, plan, line, blockers);
        LocalDateTime start = null, end = null;
        BigDecimal minutes = BigDecimal.ZERO;
        try {
            for (ProcessRouteItem step : steps) {
                minutes = minutes.add(stepMinutes(wo.getPlannedQuantity(), step));
            }
            var ability = requireAbility(plan, wo);
            var window = capacity.planExecution(line, steps, wo.getPlannedQuantity(), earliest,
                    plan.getPlanId(), ability.getCapacityFactor());
            start = window.start(); end = window.end();
            if (window.produced().compareTo(wo.getPlannedQuantity()) < 0) blockers.add("有效日历窗口排不下整张工单，请先调整排程");
            if (plan.getPlannedEndTime() == null || end.isAfter(plan.getPlannedEndTime()))
                blockers.add("按供给和日历计算需在 " + end + " 完成，超过计划截止时间，请先调整排程");
            notes.add("工时来自工单工艺；完成时间按产线周历、班次、能力和批次工序计算，不能手工延长计划交期");
            notes.add(window.explanation());
        } catch (BusinessException e) { blockers.add(e.getMessage()); }
        List<TeamOption> eligible = start == null || end == null ? List.of() : eligibleTeams(plan.getLineId(), start, end);
        if (eligible.isEmpty()) blockers.add("本产线在预计生产时段没有覆盖全部班次的有效班组，请先维护班组日期排班");
        notes.add("预检不代替领料；派发任务时仍需全部本级用料实际发到本工单，组件计划不能当成现货");
        return new DispatchPreparationVO(wo.getWorkOrderId(), line.getLineName(), start, end,
                minutes, plan.getPlannedEndTime(), eligible, List.copyOf(dependencies),
                List.copyOf(new LinkedHashSet<>(blockers)), notes);
    }

    /** 工单工序：优先用执行快照冻结的路线，否则按工艺路线读取启用项。 */
    private List<ProcessRouteItem> routeSteps(MfgWorkOrder wo) {
        if (wo.getSnapshotJson() != null && snapshots.isCurrentFormat(wo)) {
            return snapshots.routeItems(wo);
        }
        return routeItems.selectList(new LambdaQueryWrapper<ProcessRouteItem>()
                .eq(ProcessRouteItem::getRouteId, wo.getRouteId()).eq(ProcessRouteItem::getDeletedFlag, 0)
                .orderByAsc(ProcessRouteItem::getSequenceNo, ProcessRouteItem::getRouteItemId));
    }

    /** 逐道工序检查本线是否有可用工位，缺失时记入阻断说明。 */
    private void appendStationBlockers(List<ProcessRouteItem> steps, MfgProductionPlan plan,
                                       FactoryLine line, List<String> blockers) {
        for (ProcessRouteItem step : steps) {
            if (!hasRunningStation(step, plan.getLineId())) {
                blockers.add("产线「" + line.getLineName() + "」的工序 " + step.getOperationId()
                        + " 没有可用工位，请在工位工序关联中配置本线的启用工位");
            }
        }
    }

    /** 该工序在本线是否至少绑定了一个启用中的工位。 */
    private boolean hasRunningStation(ProcessRouteItem step, Long lineId) {
        var bindings = stationOperations.selectList(new LambdaQueryWrapper<FactoryStationOperation>()
                .eq(FactoryStationOperation::getOperationId, step.getOperationId())
                .eq(FactoryStationOperation::getStatus, "ENABLE")
                .eq(FactoryStationOperation::getDeletedFlag, 0));
        return bindings.stream().anyMatch(b -> stations.selectCount(new LambdaQueryWrapper<FactoryStation>()
                .eq(FactoryStation::getStationId, b.getStationId())
                .eq(FactoryStation::getLineId, lineId)
                .eq(FactoryStation::getStatus, "RUNNING")
                .eq(FactoryStation::getDeletedFlag, 0)) > 0);
    }

    /** 单道工序的冻结工时：批次工序按批次数乘批次时长，其余按标准工时乘数量。 */
    private BigDecimal stepMinutes(BigDecimal quantity, ProcessRouteItem step) {
        if (!"BATCH_EQUIPMENT".equals(step.getResourceType())) {
            if (step.getStandardTime() == null || step.getStandardTime().signum() <= 0)
                throw invalid("工序缺少有效标准工时");
            return step.getStandardTime().multiply(quantity);
        }
        if (step.getBatchCapacity() == null || step.getBatchCapacity().signum() <= 0
                || step.getBatchDurationMinutes() == null || step.getBatchDurationMinutes().signum() <= 0)
            throw invalid("批次工序缺少有效批量或批次时长");
        return quantity.divide(step.getBatchCapacity(), 0, RoundingMode.CEILING)
                .multiply(step.getBatchDurationMinutes());
    }

    /** 产线对该产品的有效生产能力，缺失或系数非正时阻断。 */
    private MfgLineProductCapability requireAbility(MfgProductionPlan plan, MfgWorkOrder wo) {
        var ability = capabilities.selectOne(new LambdaQueryWrapper<MfgLineProductCapability>()
                .eq(MfgLineProductCapability::getLineId, plan.getLineId())
                .eq(MfgLineProductCapability::getProductId, wo.getProductId())
                .eq(MfgLineProductCapability::getStatus, "ENABLE")
                .eq(MfgLineProductCapability::getDeletedFlag, 0));
        if (ability == null || ability.getCapacityFactor() == null || ability.getCapacityFactor().signum() <= 0)
            throw invalid("产线缺少该产品的有效生产能力");
        return ability;
    }

    private LocalDateTime dependencyStart(MfgWorkOrder wo, LocalDateTime earliest,
                                         List<ComponentDependency> dependencies, List<String> blockers) {
        Set<Long> visited = new HashSet<>();
        var direct = requirements.selectList(new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                .eq(MfgWorkOrderMaterial::getWorkOrderId, wo.getWorkOrderId()).eq(MfgWorkOrderMaterial::getDeletedFlag, 0));
        if (direct.isEmpty()) blockers.add("工单没有有效用料需求，请检查工单与BOM");
        for (var requirement : direct) {
            if (needsComponentSource(requirement)) {
                earliest = applyRequirementSources(earliest, requirement, visited, dependencies, blockers);
            }
        }
        return earliest;
    }

    /** 组件供给且尚未发足料的用料需求才需要追来源工单。 */
    private static boolean needsComponentSource(MfgWorkOrderMaterial requirement) {
        if (!"COMPONENT_WORK_ORDER".equals(requirement.getSupplyType())) {
            return false;
        }
        return zero(requirement.getIssuedQuantity()).compareTo(zero(requirement.getRequiredQuantity())) < 0;
    }

    /** 一条用料需求的来源工单：优先取未关闭供给来源，无来源时回退到需求自带的来源工单。 */
    private LocalDateTime applyRequirementSources(LocalDateTime earliest, MfgWorkOrderMaterial requirement,
                                                  Set<Long> visited, List<ComponentDependency> dependencies,
                                                  List<String> blockers) {
        Set<Long> sourceIds = new LinkedHashSet<>();
        sources.selectList(new LambdaQueryWrapper<MfgSupplySource>()
                .eq(MfgSupplySource::getRequirementId, requirement.getRequirementId())
                .eq(MfgSupplySource::getDeletedFlag, 0).ne(MfgSupplySource::getStatus, "CLOSED"))
                .stream().map(MfgSupplySource::getWorkOrderId).filter(Objects::nonNull).forEach(sourceIds::add);
        if (sourceIds.isEmpty() && requirement.getSourceWorkOrderId() != null)
            sourceIds.add(requirement.getSourceWorkOrderId());
        LocalDateTime cursor = earliest;
        for (Long sourceId : sourceIds) {
            if (visited.add(sourceId)) {
                cursor = applySource(cursor, sourceId, dependencies, blockers);
            }
        }
        return cursor;
    }

    /** 记录一条组件来源依赖，并在来源尚未完工时按预计完成时间推进最早开工时间。 */
    private LocalDateTime applySource(LocalDateTime earliest, Long sourceId,
                                      List<ComponentDependency> dependencies, List<String> blockers) {
        MfgWorkOrder source = workOrders.selectById(sourceId);
        if (source == null || !Integer.valueOf(0).equals(source.getDeletedFlag()) || "CLOSED".equals(source.getStatus())) {
            blockers.add("组件来源工单 " + sourceId + " 已失效，请重新安排供给");
            return earliest;
        }
        LocalDateTime finish = sourceFinishTime(sourceId);
        dependencies.add(new ComponentDependency(sourceId, source.getWorkOrderNo(), source.getStatus(), finish));
        if ("FINISHED".equals(source.getStatus())) {
            return earliest;
        }
        LocalDateTime startable = finish == null || !finish.isAfter(earliest) ? earliest : finish;
        if (finish == null) {
            blockers.add("请先派发组件工单 " + source.getWorkOrderNo() + "，确定组件预计完成时间");
        } else if (!finish.isAfter(LocalDateTime.now())) {
            blockers.add("组件工单 " + source.getWorkOrderNo() + " 的预计完成时间已过且尚未完工，请先更新组件安排");
        }
        return startable;
    }

    /** 组件来源工单已派发单据里最晚的预计完成时间；没有派发单据时返回 null。 */
    private LocalDateTime sourceFinishTime(Long sourceId) {
        var active = dispatches.selectList(new LambdaQueryWrapper<MfgDispatchOrder>()
                .eq(MfgDispatchOrder::getWorkOrderId, sourceId).eq(MfgDispatchOrder::getDeletedFlag, 0)
                .in(MfgDispatchOrder::getStatus, "RELEASED", "ACCEPTED", "RUNNING", "FINISHED"));
        return active.stream().map(MfgDispatchOrder::getDueTime).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);
    }

    /** 有效班次窗口；该天该规则不适用或与计算区间不重叠时返回 null。 */
    private record ShiftWindow(Long shiftId, LocalDateTime start, LocalDateTime end) {
    }

    private ShiftWindow shiftWindow(MfgLineCalendarRule rule, LocalDate day, Map<Long, OrgShift> shiftCache,
                                    LocalDateTime from, LocalDateTime to) {
        OrgShift shift = ruleAppliesToDay(rule, day) ? usableShift(rule, shiftCache) : null;
        if (shift == null) {
            return null;
        }
        LocalDateTime start = day.atTime(shift.getStartTime()), end = day.atTime(shift.getEndTime());
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }
        if (!start.isBefore(to) || !end.isAfter(from)) {
            return null;
        }
        return new ShiftWindow(shift.getShiftId(), start, end);
    }

    /** 规则关联的班次；班次已删除或起止时间缺失时返回 null。 */
    private OrgShift usableShift(MfgLineCalendarRule rule, Map<Long, OrgShift> shiftCache) {
        OrgShift shift = shiftCache.computeIfAbsent(rule.getShiftId(), shifts::selectById);
        if (shift == null || !Integer.valueOf(0).equals(shift.getDeletedFlag())
                || shift.getStartTime() == null || shift.getEndTime() == null) {
            return null;
        }
        return shift;
    }

    /** 班次日历规则是否适用于这一天：星期匹配、落在生效区间内、产能系数为正。 */
    private static boolean ruleAppliesToDay(MfgLineCalendarRule rule, LocalDate day) {
        boolean weekdayHit = Objects.equals(rule.getDayOfWeek(), day.getDayOfWeek().getValue());
        boolean afterStart = rule.getEffectiveFrom() == null || !rule.getEffectiveFrom().isAfter(day);
        boolean beforeEnd = rule.getEffectiveTo() == null || !rule.getEffectiveTo().isBefore(day);
        boolean factorPositive = rule.getCapacityFactor() != null && rule.getCapacityFactor().signum() > 0;
        return weekdayHit && afterStart && beforeEnd && factorPositive;
    }

    /** 该班次当日报到且人数有效的班组集合。 */
    private static Set<Long> presentTeams(List<OrgTeamSchedule> assigned, LocalDate day, Long shiftId) {
        Set<Long> present = new HashSet<>();
        for (OrgTeamSchedule s : assigned) {
            if (day.equals(s.getWorkDate()) && Objects.equals(shiftId, s.getShiftId())) {
                present.add(s.getTeamId());
            }
        }
        return present;
    }

    /** 候选班组须覆盖计算窗口内每个有效工作班次，排除草稿、别线、缺勤和过期排班。 */
    private List<TeamOption> eligibleTeams(Long lineId, LocalDateTime from, LocalDateTime to) {
        var rules = calendars.selectList(new LambdaQueryWrapper<MfgLineCalendarRule>()
                .eq(MfgLineCalendarRule::getLineId, lineId).eq(MfgLineCalendarRule::getStatus, "ENABLE")
                .eq(MfgLineCalendarRule::getDeletedFlag, 0));
        var assigned = schedules.selectList(new LambdaQueryWrapper<OrgTeamSchedule>()
                .eq(OrgTeamSchedule::getLineId, lineId).eq(OrgTeamSchedule::getStatus, "RELEASED")
                .eq(OrgTeamSchedule::getDeletedFlag, 0).gt(OrgTeamSchedule::getPlannedHeadcount, 0)
                .ge(OrgTeamSchedule::getWorkDate, from.toLocalDate().minusDays(1))
                .le(OrgTeamSchedule::getWorkDate, to.toLocalDate()));
        Set<Long> eligible = null;
        Map<Long, OrgShift> shiftCache = new HashMap<>();
        for (LocalDate day = from.toLocalDate().minusDays(1); !day.isAfter(to.toLocalDate()); day = day.plusDays(1)) {
            for (MfgLineCalendarRule rule : rules) {
                ShiftWindow window = shiftWindow(rule, day, shiftCache, from, to);
                if (window == null) {
                    continue;
                }
                Set<Long> present = presentTeams(assigned, day, window.shiftId());
                if (eligible == null) {
                    eligible = present;
                } else {
                    eligible.retainAll(present);
                }
            }
        }
        if (eligible == null || eligible.isEmpty()) return List.of();
        return teams.selectBatchIds(eligible).stream().filter(t -> Integer.valueOf(0).equals(t.getDeletedFlag()))
                .map(t -> new TeamOption(t.getTeamId(), t.getTeamName())).toList();
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BusinessException invalid(String message) { return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message); }
}
