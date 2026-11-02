package com.neuedu.ems.service.mfg;

import com.neuedu.ems.entity.factory.*;
import com.neuedu.ems.entity.mfg.*;
import com.neuedu.ems.entity.org.*;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.mapper.factory.*;
import com.neuedu.ems.mapper.mfg.*;
import com.neuedu.ems.mapper.org.*;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchPreparationServiceTest {
    MfgWorkOrderMaterialMapper requirements = mock(MfgWorkOrderMaterialMapper.class);
    MfgSupplySourceMapper sources = mock(MfgSupplySourceMapper.class);
    MfgWorkOrderMapper workOrders = mock(MfgWorkOrderMapper.class);
    MfgDispatchOrderMapper dispatches = mock(MfgDispatchOrderMapper.class);
    FactoryLineMapper lines = mock(FactoryLineMapper.class);
    FactoryStationMapper stations = mock(FactoryStationMapper.class);
    FactoryStationOperationMapper stationOperations = mock(FactoryStationOperationMapper.class);
    MfgLineProductCapabilityMapper capabilities = mock(MfgLineProductCapabilityMapper.class);
    MfgLineCalendarRuleMapper calendars = mock(MfgLineCalendarRuleMapper.class);
    OrgShiftMapper shifts = mock(OrgShiftMapper.class);
    OrgTeamScheduleMapper schedules = mock(OrgTeamScheduleMapper.class);
    OrgTeamMapper teams = mock(OrgTeamMapper.class);
    ProcessRouteItemMapper routeItems = mock(ProcessRouteItemMapper.class);
    ExecutionSnapshotPolicy snapshots = mock(ExecutionSnapshotPolicy.class);
    ComponentCapacityService capacity = mock(ComponentCapacityService.class);
    DispatchPreparationService service;
    LocalDateTime from = LocalDate.now().plusWeeks(1)
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atTime(8, 0);
    LocalDateTime to = from.withHour(16);
    MfgWorkOrder wo;
    MfgProductionPlan plan;
    ProcessRouteItem step;

    @BeforeEach void setup() {
        service = new DispatchPreparationService(requirements, sources, workOrders, dispatches, lines,
                stations, stationOperations, capabilities, calendars, shifts, schedules, teams, routeItems, snapshots, capacity);
        wo = new MfgWorkOrder(); wo.setWorkOrderId(1L); wo.setProductId(2L); wo.setRouteId(3L);
        wo.setPlannedQuantity(new BigDecimal("51")); wo.setPlannedStartTime(from); wo.setPlanId(4L);
        var basic = new MfgWorkOrderMaterial(); basic.setSupplyType("STOCK"); basic.setRequiredQuantity(BigDecimal.ONE);
        when(requirements.selectList(any())).thenReturn(List.of(basic));
        plan = new MfgProductionPlan(); plan.setPlanId(4L); plan.setLineId(5L); plan.setPlannedEndTime(to.plusDays(1));
        var line = new FactoryLine(); line.setLineId(5L); line.setLineName("L200线"); line.setDeletedFlag(0);
        when(lines.selectById(5L)).thenReturn(line);
        step = new ProcessRouteItem(); step.setOperationId(6L); step.setStandardTime(new BigDecimal("2"));
        when(routeItems.selectList(any())).thenReturn(List.of(step));
        var binding = new FactoryStationOperation(); binding.setStationId(7L);
        when(stationOperations.selectList(any())).thenReturn(List.of(binding));
        when(stations.selectCount(any())).thenReturn(1L);
        var ability = new MfgLineProductCapability(); ability.setCapacityFactor(BigDecimal.ONE);
        when(capabilities.selectOne(any())).thenReturn(ability);
        when(capacity.planExecution(any(), anyList(), any(), any(), any(), any()))
                .thenReturn(new ComponentCapacityService.Plan(from, to, new BigDecimal("51"), "有效班次"));
        when(calendars.selectList(any())).thenReturn(List.of(rule(8L), rule(9L)));
        when(shifts.selectById(8L)).thenReturn(shift(8L, 8, 12));
        when(shifts.selectById(9L)).thenReturn(shift(9L, 13, 17));
        when(schedules.selectList(any())).thenReturn(List.of(schedule(10L, 8L), schedule(10L, 9L), schedule(11L, 8L)));
        when(teams.selectBatchIds(anyCollection())).thenAnswer(i -> {
            assertEquals(java.util.Set.of(10L), new java.util.HashSet<>(i.getArgument(0)));
            var team = new OrgTeam(); team.setTeamId(10L); team.setTeamName("台灯一班"); team.setDeletedFlag(0);
            return List.of(team);
        });
    }

    @Test void candidatesCoverBothWorkingShiftsAndTimesAreComputed() {
        var result = service.prepare(wo, plan);
        assertTrue(result.blockers().isEmpty());
        assertEquals(10L, result.teams().get(0).teamId());
        assertEquals(to, result.dueTime());
        assertEquals(new BigDecimal("102"), result.estimatedMinutes());
    }

    @Test void noDatedScheduleIsAnExplicitBlocker() {
        when(schedules.selectList(any())).thenReturn(List.of());
        var result = service.prepare(wo, plan);
        assertTrue(result.teams().isEmpty());
        assertTrue(result.blockers().stream().anyMatch(m -> m.contains("日期排班")));
    }

    @Test void draftComponentCannotProvideAConfirmedAssemblyStart() {
        componentSource();
        var result = service.prepare(wo, plan);
        assertTrue(result.blockers().stream().anyMatch(m -> m.contains("请先派发组件工单")));
        assertEquals(12L, result.dependencies().get(0).workOrderId());
    }

    @Test void sourceDispatchCompletionConstrainsAssemblyStart() {
        componentSource();
        var dispatch = new MfgDispatchOrder(); dispatch.setDueTime(to.plusDays(1));
        when(dispatches.selectList(any())).thenReturn(List.of(dispatch));
        service.prepare(wo, plan);
        verify(capacity).planExecution(any(), anyList(), eq(wo.getPlannedQuantity()), eq(to.plusDays(1)), eq(4L), eq(BigDecimal.ONE));
    }

    @Test void issuedSemiFinishedDoesNotWaitForAnOldSourceWorkOrder() {
        var r = componentSource(); r.setIssuedQuantity(r.getRequiredQuantity());
        var result = service.prepare(wo, plan);
        assertTrue(result.dependencies().isEmpty());
        assertTrue(result.blockers().isEmpty());
        verifyNoInteractions(sources);
    }

    @Test void completeBatchesUseCeilingAndFrozenRouteInsteadOfLiveRoute() {
        step.setResourceType("BATCH_EQUIPMENT"); step.setBatchCapacity(new BigDecimal("50"));
        step.setBatchDurationMinutes(new BigDecimal("60"));
        wo.setSnapshotJson("frozen"); when(snapshots.isCurrentFormat(wo)).thenReturn(true);
        when(snapshots.routeItems(wo)).thenReturn(List.of(step));
        var result = service.prepare(wo, plan);
        assertEquals(new BigDecimal("120"), result.estimatedMinutes());
        verifyNoInteractions(routeItems);
    }

    @Test void calculatedCompletionCannotExtendThePlanDeadline() {
        plan.setPlannedEndTime(to.minusMinutes(1));
        var result = service.prepare(wo, plan);
        assertTrue(result.blockers().stream().anyMatch(m -> m.contains("超过计划截止时间")));
    }

    @Test void stationFailureIsReportedBeforeAnyTaskIsCreated() {
        when(stations.selectCount(any())).thenReturn(0L);
        var result = service.prepare(wo, plan);
        assertTrue(result.blockers().stream().anyMatch(m -> m.contains("没有可用工位")));
        verify(workOrders, never()).updateById(any(MfgWorkOrder.class));
    }

    private MfgWorkOrderMaterial componentSource() {
        var r = new MfgWorkOrderMaterial(); r.setRequirementId(20L); r.setSupplyType("COMPONENT_WORK_ORDER");
        r.setRequiredQuantity(BigDecimal.TEN); r.setSourceWorkOrderId(12L);
        when(requirements.selectList(any())).thenReturn(List.of(r));
        var source = new MfgWorkOrder(); source.setWorkOrderId(12L); source.setWorkOrderNo("WO-C-12");
        source.setStatus("DRAFT"); source.setDeletedFlag(0);
        when(workOrders.selectById(12L)).thenReturn(source);
        return r;
    }
    private MfgLineCalendarRule rule(Long shift) {
        var r = new MfgLineCalendarRule(); r.setShiftId(shift); r.setDayOfWeek(1); r.setCapacityFactor(BigDecimal.ONE); return r;
    }
    private OrgShift shift(Long id, int start, int end) {
        var s = new OrgShift(); s.setShiftId(id); s.setStartTime(LocalTime.of(start, 0));
        s.setEndTime(LocalTime.of(end, 0)); s.setDeletedFlag(0); return s;
    }
    private OrgTeamSchedule schedule(Long team, Long shift) {
        var s = new OrgTeamSchedule(); s.setTeamId(team); s.setShiftId(shift); s.setWorkDate(from.toLocalDate()); return s;
    }
}
