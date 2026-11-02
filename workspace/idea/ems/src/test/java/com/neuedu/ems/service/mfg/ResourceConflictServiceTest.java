package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.mapper.factory.FactoryLineMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 跨层资源冲突检查：只识别与解释，不改计划。 */
class ResourceConflictServiceTest {

    private MfgProductionPlanMapper planMapper;
    private FactoryLineMapper lineMapper;
    private ResourceConflictService service;

    @BeforeEach
    void setUp() {
        planMapper = mock(MfgProductionPlanMapper.class);
        lineMapper = mock(FactoryLineMapper.class);
        service = new ResourceConflictService(planMapper, lineMapper);
        when(lineMapper.selectById(any())).thenAnswer(invocation -> line(((Number) invocation.getArgument(0)).longValue()));
    }

    @Test
    void componentFinishingAfterParentStartIsReported() {
        List<MfgProductionPlan> plans = new ArrayList<>();
        plans.add(plan(1L, "PLN-1", "ROOT", null, 10L,
                "2026-10-26T08:00", "2026-10-26T12:00"));
        plans.add(plan(2L, "PLN-C-2", "COMPONENT", 1L, 20L,
                "2026-10-26T09:00", "2026-10-26T13:00"));
        when(planMapper.selectList(any())).thenReturn(plans);

        Map<String, Object> result = service.scan(null, null);

        List<?> items = (List<?>) result.get("items");
        assertEquals(1, items.size(), "应识别出 1 处组件晚于父工单开工");
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals("COMPONENT_AFTER_PARENT", row.get("conflictType"));
        assertEquals("PLN-C-2", row.get("planNo"));
        assertEquals("PLN-1", row.get("relatedPlanNo"));
        assertEquals(300L, row.get("gapMinutes"), "组件完工 13:00 比父开工 08:00 晚 5 小时，正数表示晚于父开工");
        assertTrue(String.valueOf(row.get("reason")).contains("父工单开工时该组件不齐套"));
    }

    @Test
    void componentFinishedBeforeParentStartIsClean() {
        List<MfgProductionPlan> plans = new ArrayList<>();
        plans.add(plan(1L, "PLN-1", "ROOT", null, 10L,
                "2026-10-26T08:00", "2026-10-26T12:00"));
        plans.add(plan(2L, "PLN-C-2", "COMPONENT", 1L, 20L,
                "2026-10-26T03:00", "2026-10-26T07:00"));
        when(planMapper.selectList(any())).thenReturn(plans);

        Map<String, Object> result = service.scan(null, null);

        assertTrue(((List<?>) result.get("items")).isEmpty());
        assertTrue(String.valueOf(result.get("summary")).contains("没有发现跨层资源冲突"));
    }

    @Test
    void overlappingPlansOnSameLineAreReportedOnce() {
        List<MfgProductionPlan> plans = new ArrayList<>();
        plans.add(plan(11L, "PLN-A", "ROOT", null, 20L,
                "2026-10-26T08:00", "2026-10-26T12:00"));
        plans.add(plan(12L, "PLN-B", "COMPONENT", null, 20L,
                "2026-10-26T10:00", "2026-10-26T14:00"));
        when(planMapper.selectList(any())).thenReturn(plans);

        Map<String, Object> result = service.scan(null, null);

        List<?> items = (List<?>) result.get("items");
        assertEquals(1, items.size(), "同线重叠只记一条，不重复配对");
        Map<?, ?> row = (Map<?, ?>) items.get(0);
        assertEquals("LINE_OVERLAP", row.get("conflictType"));
        assertEquals("PLN-B", row.get("planNo"));
        assertEquals(120L, row.get("gapMinutes"), "10:00~12:00 重叠 120 分钟");
        assertEquals("LAMP-LINE-20", row.get("lineCode"));
    }

    @Test
    void plansOnDifferentLinesDoNotConflict() {
        List<MfgProductionPlan> plans = new ArrayList<>();
        plans.add(plan(11L, "PLN-A", "ROOT", null, 20L,
                "2026-10-26T08:00", "2026-10-26T12:00"));
        plans.add(plan(12L, "PLN-B", "ROOT", null, 21L,
                "2026-10-26T09:00", "2026-10-26T13:00"));
        when(planMapper.selectList(any())).thenReturn(plans);

        Map<String, Object> result = service.scan(null, null);

        assertTrue(((List<?>) result.get("items")).isEmpty(), "不同产线不算资源冲突");
    }

    @Test
    void summaryCountsBothConflictTypes() {
        List<MfgProductionPlan> plans = new ArrayList<>();
        plans.add(plan(1L, "PLN-1", "ROOT", null, 10L,
                "2026-10-26T08:00", "2026-10-26T12:00"));
        plans.add(plan(2L, "PLN-C-2", "COMPONENT", 1L, 10L,
                "2026-10-26T09:00", "2026-10-26T13:00"));
        plans.add(plan(3L, "PLN-C-3", "COMPONENT", 1L, 10L,
                "2026-10-26T09:30", "2026-10-26T11:00"));
        when(planMapper.selectList(any())).thenReturn(plans);

        Map<String, Object> result = service.scan(null, null);

        Map<?, ?> counts = (Map<?, ?>) result.get("counts");
        // 两张组件计划都晚于父开工；同一条线上三张计划两两重叠。
        // 其中"父计划 × 组件"的重叠是"一张工单一条线做到底"的正常形态，不再报；
        // 但两个兄弟组件仍然要抢同一条线的产能，所以剩下的那 1 处同线重叠必须照报。
        assertEquals(3L, counts.get("total"));
        assertEquals(2L, counts.get("COMPONENT_AFTER_PARENT"));
        assertEquals(1L, counts.get("LINE_OVERLAP"));
        assertFalse(String.valueOf(result.get("summary")).isEmpty());
    }

    @Test
    void parentAndChildSharingOneLineAreNotReportedAsLineOverlap() {
        List<MfgProductionPlan> plans = new ArrayList<>();
        plans.add(plan(1L, "PLN-1", "ROOT", null, 10L,
                "2026-10-26T08:00", "2026-10-26T12:00"));
        plans.add(plan(2L, "PLN-C-2", "COMPONENT", 1L, 10L,
                "2026-10-26T09:00", "2026-10-26T10:00"));
        plans.add(plan(3L, "PLN-C-3", "COMPONENT", 2L, 10L,
                "2026-10-26T10:00", "2026-10-26T11:00"));
        when(planMapper.selectList(any())).thenReturn(plans);

        Map<String, Object> result = service.scan(null, null);

        List<?> items = (List<?>) result.get("items");
        boolean anyLineOverlap = items.stream()
                .map(row -> ((Map<?, ?>) row).get("conflictType"))
                .anyMatch("LINE_OVERLAP"::equals);
        assertFalse(anyLineOverlap,
                "父计划、组件、孙组件同线交叠是流水排产的正常形态，不应报同线冲突");
    }

    private static MfgProductionPlan plan(Long planId, String planNo, String planType, Long parentPlanId,
                                          Long lineId, String start, String end) {
        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanId(planId);
        plan.setPlanNo(planNo);
        plan.setPlanType(planType);
        plan.setParentPlanId(parentPlanId);
        plan.setLineId(lineId);
        plan.setStatus("RELEASED");
        plan.setPlannedQuantity(new BigDecimal("10.0000"));
        plan.setPlannedStartTime(LocalDateTime.parse(start));
        plan.setPlannedEndTime(LocalDateTime.parse(end));
        plan.setVersion(0);
        plan.setDeletedFlag(0);
        return plan;
    }

    private static FactoryLine line(Long lineId) {
        FactoryLine line = new FactoryLine();
        line.setLineId(lineId);
        line.setLineCode("LAMP-LINE-" + lineId);
        line.setLineName("台灯线 " + lineId);
        return line;
    }
}
