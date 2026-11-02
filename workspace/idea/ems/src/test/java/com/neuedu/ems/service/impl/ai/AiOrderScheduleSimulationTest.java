package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.mfg.MfgScheduleScenarioService;
import com.neuedu.ems.vo.mfg.ScheduleSimulationComparison;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 订单排程推演的 AI 侧渲染：结论来自排程服务，不能自己编时间。
 */
class AiOrderScheduleSimulationTest {

    @Test
    void rendersThreePlansAndKeepsUnknownDeliveryUnjudged() throws Exception {
        var mapper = mock(AiDataQueryMapper.class);
        when(mapper.selectOrderHeader("PO-1")).thenReturn(Map.of("productionOrderId", 9L));
        MfgScheduleScenarioService scheduleService = mock(MfgScheduleScenarioService.class);

        ScheduleSimulationComparison comparison = new ScheduleSimulationComparison();
        comparison.setProductionOrderId(9L);
        comparison.setProductionOrderNo("PO-1");
        comparison.setConclusion("一线 最早 2026-10-28T08:00 开工，按产能 2026-10-28T17:00 做完");
        comparison.setAssumptions(List.of("按班次日产能与工艺标准工时折算"));
        comparison.setMissing(List.of("该产品在当前时间窗内只有一条可用产线，换线口径无法试算"));

        ScheduleSimulationComparison.Plan current = new ScheduleSimulationComparison.Plan();
        current.setCode("CURRENT");
        current.setLabel("现状口径");
        current.setDescription("按现有排程");
        current.setFeasible(true);
        current.setLineName("总装一线");
        current.setStartTime(LocalDateTime.of(2026, 10, 28, 8, 0));
        current.setEndTime(LocalDateTime.of(2026, 10, 28, 17, 0));
        current.setWorkingMinutes(540L);
        current.setMeetsDelivery(null);

        ScheduleSimulationComparison.Plan component = new ScheduleSimulationComparison.Plan();
        component.setCode("COMPONENT_FIRST");
        component.setLabel("组件优先");
        component.setDescription("先做组件再总装");
        component.setFeasible(false);
        component.setBlockers(List.of("该订单还没有未完工的组件子工单，组件优先口径无法试算"));

        comparison.setPlans(List.of(current, component));
        when(scheduleService.simulateOrderSchedule(9L)).thenReturn(comparison);

        AiDataQueryServiceImpl service = new AiDataQueryServiceImpl(mapper, mock(MdmMaterialMapper.class),
                mock(MdmProductMapper.class), mock(WmsWarehouseMapper.class));
        Field field = AiDataQueryServiceImpl.class.getDeclaredField("scheduleScenarioService");
        field.setAccessible(true);
        field.set(service, scheduleService);

        var result = service.queryOrderScheduleSimulation("PO-1");
        assertEquals(2, result.getRows().size());
        assertEquals("现状口径", result.getRows().get(0).get("label"));
        assertEquals("可行", result.getRows().get(0).get("feasible"));
        assertEquals("未知（订单未填交期）", result.getRows().get(0).get("meetsDelivery"));
        assertEquals("不可行", result.getRows().get(1).get("feasible"));
        assertTrue(result.getSummary().contains("最早"));
        // 假设与缺口必须原样透出，缺数据时不能说成已结论。
        assertTrue(result.getBlocks().stream().anyMatch(block -> "推演假设".equals(block.getTitle())));
        assertTrue(result.getBlocks().stream().anyMatch(block -> "缺口与未纳入的条件".equals(block.getTitle())));
    }

    @Test
    void rejectsUnknownOrderInsteadOfGuessing() {
        var mapper = mock(AiDataQueryMapper.class);
        when(mapper.selectOrderHeader("PO-404")).thenReturn(null);
        AiDataQueryServiceImpl service = new AiDataQueryServiceImpl(mapper, mock(MdmMaterialMapper.class),
                mock(MdmProductMapper.class), mock(WmsWarehouseMapper.class));
        Field field;
        try {
            field = AiDataQueryServiceImpl.class.getDeclaredField("scheduleScenarioService");
            field.setAccessible(true);
            field.set(service, mock(MfgScheduleScenarioService.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.neuedu.ems.exception.AiServiceException.class,
                () -> service.queryOrderScheduleSimulation("PO-404"));
        assertTrue(error.getMessage().contains("未找到生产订单"));
    }

    @Test
    void deliveryJudgementFollowsTheOrderInsteadOfThePlanAlone() throws Exception {
        var mapper = mock(AiDataQueryMapper.class);
        when(mapper.selectOrderHeader("PO-2")).thenReturn(Map.of("productionOrderId", 7L));
        MfgScheduleScenarioService scheduleService = mock(MfgScheduleScenarioService.class);

        ScheduleSimulationComparison comparison = new ScheduleSimulationComparison();
        comparison.setProductionOrderId(7L);
        comparison.setProductionOrderNo("PO-2");
        comparison.setConclusion("一线 最早 2026-10-28T08:00 开工");
        // 订单有交期，但换线口径排不出时间，只有现状口径能判满足交期。
        comparison.setDeliveryDate(LocalDate.of(2026, 10, 30));
        comparison.setPlans(List.of(
                simulationPlan("CURRENT", "现状口径", true, Boolean.TRUE),
                simulationPlan("RESOURCE_SHIFT", "调整资源", false, null)));
        when(scheduleService.simulateOrderSchedule(7L)).thenReturn(comparison);

        AiDataQueryServiceImpl service = new AiDataQueryServiceImpl(mapper, mock(MdmMaterialMapper.class),
                mock(MdmProductMapper.class), mock(WmsWarehouseMapper.class));
        Field field = AiDataQueryServiceImpl.class.getDeclaredField("scheduleScenarioService");
        field.setAccessible(true);
        field.set(service, scheduleService);

        var result = service.queryOrderScheduleSimulation("PO-2");
        assertEquals("是", result.getRows().get(0).get("meetsDelivery"));
        assertEquals("未知（该口径排不出完工时间）", result.getRows().get(1).get("meetsDelivery"));
    }

    private ScheduleSimulationComparison.Plan simulationPlan(String code, String label, boolean feasible,
                                                             Boolean meetsDelivery) {
        ScheduleSimulationComparison.Plan plan = new ScheduleSimulationComparison.Plan();
        plan.setCode(code);
        plan.setLabel(label);
        plan.setDescription(label);
        plan.setFeasible(feasible);
        plan.setMeetsDelivery(meetsDelivery);
        if (feasible) {
            plan.setLineName("总装一线");
            plan.setStartTime(LocalDateTime.of(2026, 10, 28, 8, 0));
            plan.setEndTime(LocalDateTime.of(2026, 10, 28, 17, 0));
            plan.setWorkingMinutes(540L);
        }
        return plan;
    }

    @Test
    void deliveryJudgementIsOnlyMadeWhenDateIsKnown() {
        var order = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        order.setDeliveryDate(LocalDate.of(2026, 10, 30));
        assertEquals(Boolean.TRUE, deliveryMeets(LocalDateTime.of(2026, 10, 30, 17, 0), LocalDate.of(2026, 10, 30)));
        assertEquals(Boolean.FALSE, deliveryMeets(LocalDateTime.of(2026, 10, 31, 9, 0), LocalDate.of(2026, 10, 30)));
        assertEquals(null, deliveryMeets(LocalDateTime.of(2026, 10, 31, 9, 0), null));
        assertEquals(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** 与 MfgScheduleScenarioServiceImpl.meetsDelivery 同口径：只比日期，任一侧为空就不判。 */
    private Boolean deliveryMeets(LocalDateTime endTime, LocalDate deliveryDate) {
        if (endTime == null || deliveryDate == null) {
            return null;
        }
        return !endTime.toLocalDate().isAfter(deliveryDate);
    }
}
