package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.ai.AiLineCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 延期筛选失败时必须返回空结果，不能把未延期的排程整表退回。 */
class AiScheduleLateFilterTest {
    private final AiDataQueryMapper mapper = mock(AiDataQueryMapper.class);
    private final AiDataQueryServiceImpl service = new AiDataQueryServiceImpl(
            mapper, mock(MdmMaterialMapper.class), mock(MdmProductMapper.class), mock(WmsWarehouseMapper.class));

    @BeforeEach
    void data() {
        var calendars = mock(AiLineCalendarService.class);
        when(calendars.availability(any(), any())).thenReturn(Map.of());
        ReflectionTestUtils.setField(service, "lineCalendars", calendars);
        when(mapper.selectScheduleByLine(any(), any())).thenReturn(List.of(
                line("LAMP-LINE-1", "一号线", 2),
                line("LAMP-LINE-2", "二号线", 0)));
        when(mapper.selectScheduleItems(any(), any())).thenReturn(List.of(
                item("一号线", "PO-LATE", 30),
                item("二号线", "PO-OK", 0)));
    }

    @Test
    void namedLineIncludesLiveConflictsInsteadOfClaimingNone() {
        var conflicts = mock(com.neuedu.ems.service.mfg.ResourceConflictService.class);
        when(conflicts.scan(any(), any())).thenReturn(Map.of("items", List.of(
                Map.of("lineName", "一号线", "lineCode", "LAMP-LINE-1", "conflictLabel", "同线时段重叠",
                        "planNo", "P1", "relatedPlanNo", "P2", "reason", "两张计划重叠"),
                Map.of("lineName", "二号线", "lineCode", "LAMP-LINE-2", "conflictLabel", "同线时段重叠",
                        "planNo", "P3", "relatedPlanNo", "P4", "reason", "另一条线重叠"))));
        ReflectionTestUtils.setField(service, "resourceConflictService", conflicts);
        var result = service.queryScheduleCapacity(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), "一号线", false);
        assertTrue(result.getSummary().contains("资源冲突扫描发现 1 处"));
        assertEquals(2, result.getBlocks().size());
        assertEquals("P1", result.getBlocks().get(1).getRows().getFirst().get("planNo"));
    }

    @Test
    void lateOnlyDropsLinesThatAreNotLate() {
        var result = service.queryScheduleCapacity(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), null, true);
        assertEquals(1, result.getRows().size());
        assertEquals("一号线", result.getRows().getFirst().get("lineName"));
        assertEquals(1, result.getBlocks().getFirst().getRows().size());
        assertEquals("PO-LATE", result.getBlocks().getFirst().getRows().getFirst().get("productionOrderNo"));
    }

    @Test
    void lateLineWithNoDelayReturnsEmptyInsteadOfEveryLine() {
        var result = service.queryScheduleCapacity(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), "二号线", true);
        assertTrue(result.getRows().isEmpty());
        assertTrue(result.getBlocks().isEmpty());
        assertTrue(result.getSummary().contains("没有延期订单"));
        assertTrue(result.getSummary().contains("二号线"));
    }

    @Test
    void unknownLineDoesNotFallBackToAllLines() {
        var result = service.queryScheduleCapacity(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), "三号线", false);
        assertTrue(result.getRows().isEmpty());
        assertTrue(result.getBlocks().isEmpty());
        assertTrue(result.getSummary().contains("没有名称或编码包含"));
    }

    private Map<String, Object> line(String code, String name, int lateCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lineId", 1L);
        row.put("lineCode", code);
        row.put("lineName", name);
        row.put("lineStatus", "RUNNING");
        row.put("itemCount", 1);
        row.put("plannedQuantity", 10);
        row.put("lateCount", lateCount);
        row.put("conflictCount", 0);
        return row;
    }

    private Map<String, Object> item(String lineName, String orderNo, int tardiness) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lineName", lineName);
        row.put("productionOrderNo", orderNo);
        row.put("productName", "L200");
        row.put("plannedQuantity", 10);
        row.put("estimatedMinutes", 6);
        row.put("tardinessMinutes", tardiness);
        row.put("status", "CONFIRMED");
        row.put("conflictReason", "");
        return row;
    }
}
