package com.neuedu.ems.service.impl.ai;
import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiOrderProgressTest {
    private AiDataQueryServiceImpl progressService(AiDataQueryMapper mapper) {
        when(mapper.selectOrderHeader("PO-1")).thenReturn(Map.of("productionOrderId", 1L, "orderQuantity", 1, "status", "RELEASED"));
        return new AiDataQueryServiceImpl(mapper, mock(MdmMaterialMapper.class), mock(MdmProductMapper.class), mock(WmsWarehouseMapper.class));
    }

    @Test void nextStepDistinguishesNoPlanFromPlanWithoutWorkOrders() {
        var mapper = mock(AiDataQueryMapper.class);
        var service = progressService(mapper);
        assertTrue(service.queryOrderProgress("PO-1").getSummary().contains("没有查到关联生产计划"));
        when(mapper.selectOrderPlans(1L)).thenReturn(List.of(Map.of("planNo", "PL-1", "planStatus", "DRAFT", "plannedQuantity", 1)));
        assertTrue(service.queryOrderProgress("PO-1").getSummary().contains("没有查到已生效的计划"));
        when(mapper.selectOrderPlans(1L)).thenReturn(List.of(Map.of("planNo", "PL-1", "planStatus", "RELEASED", "plannedQuantity", 1)));
        assertTrue(service.queryOrderProgress("PO-1").getSummary().contains("没有查到有效工单"));
    }

    @Test void nextStepUsesRealWorkOrderStatusInsteadOfAssumingItCanStart() {
        var mapper = mock(AiDataQueryMapper.class);
        var service = progressService(mapper);
        when(mapper.selectOrderPlans(1L)).thenReturn(List.of(Map.of("planNo", "PL-1", "planStatus", "RELEASED", "plannedQuantity", 1)));
        when(mapper.selectOrderWorkOrders(1L)).thenReturn(List.of(Map.of("workOrderId", 4L, "workOrderNo", "WO-4", "status", "DRAFT")));
        String pending = service.queryOrderProgress("PO-1").getSummary();
        assertTrue(pending.contains("WO-4 尚未下达"));
        assertTrue(pending.contains("以实际校验为准"));
        when(mapper.selectOrderWorkOrders(1L)).thenReturn(List.of(Map.of("workOrderId", 4L, "workOrderNo", "WO-4", "status", "RELEASED")));
        assertTrue(service.queryOrderProgress("PO-1").getSummary().contains("检查组件依赖、领料和开工条件"));
    }

    @Test void nextStepNamesComponentOrdersAndShowsTheirActualStates() {
        var mapper = mock(AiDataQueryMapper.class);
        var service = progressService(mapper);
        when(mapper.selectOrderPlans(1L)).thenReturn(List.of(Map.of("planNo", "PL-1", "planStatus", "RELEASED", "plannedQuantity", 1)));
        when(mapper.selectOrderWorkOrders(1L)).thenReturn(List.of(
                Map.of("workOrderId", 1L, "workOrderNo", "WO-1", "workOrderType", "ROOT", "status", "RELEASED"),
                Map.of("workOrderId", 2L, "workOrderNo", "WO-2", "workOrderType", "COMPONENT", "productName", "灯头组件", "plannedQuantity", 1, "status", "DRAFT"),
                Map.of("workOrderId", 3L, "workOrderNo", "WO-3", "workOrderType", "COMPONENT", "productName", "灯臂组件", "plannedQuantity", 1, "status", "DRAFT"),
                Map.of("workOrderId", 4L, "workOrderNo", "WO-4", "workOrderType", "COMPONENT", "productName", "控制底座", "plannedQuantity", 1, "status", "DRAFT")));
        var answer = service.queryOrderProgress("PO-1");
        assertTrue(answer.getSummary().contains("先生产组件"));
        for (String expected : List.of("灯头组件", "灯臂组件", "控制底座", "WO-2", "WO-3", "WO-4"))
            assertTrue(answer.getSummary().contains(expected), expected);
        assertTrue(answer.getBlocks().stream().anyMatch(block -> "组件工单".equals(block.getTitle()) && block.getRows().size() == 3));
    }

    @Test void componentsAndUnconfirmedWarehouseDocumentsDoNotInflateOrderProgress() {
        var mapper=mock(AiDataQueryMapper.class);
        when(mapper.selectOrderHeader("PO-1")).thenReturn(Map.of("productionOrderId",1L,"orderQuantity",100));
        var rootPlan=Map.<String,Object>of("planNo","P1","planType","ROOT","planStatus","RELEASED","plannedQuantity",100);
        var childPlan=Map.<String,Object>of("planNo","P2","planType","COMPONENT","planStatus","RELEASED","plannedQuantity",300);
        when(mapper.selectOrderPlans(1L)).thenReturn(List.of(rootPlan,rootPlan,childPlan));
        when(mapper.selectOrderWorkOrders(1L)).thenReturn(List.of(
                Map.of("workOrderId",1L,"workOrderType","ROOT","plannedQuantity",100,"completedQuantity",50),
                Map.of("workOrderId",2L,"workOrderType","COMPONENT","plannedQuantity",300,"completedQuantity",300)));
        when(mapper.selectOrderInbound(List.of(1L))).thenReturn(List.of(
                Map.of("auditStatus","APPROVED","inboundQuantity",25),Map.of("auditStatus","SUBMITTED","inboundQuantity",99)));
        when(mapper.selectOrderShipments(1L)).thenReturn(List.of(
                Map.of("auditStatus","APPROVED","outboundQuantity",20),Map.of("auditStatus","SUBMITTED","outboundQuantity",99)));
        var service=new AiDataQueryServiceImpl(mapper,mock(MdmMaterialMapper.class),mock(MdmProductMapper.class),mock(WmsWarehouseMapper.class));
        var answer=service.queryOrderProgress("PO-1");
        assertFalse(answer.getSummary().contains("把终检合格的产品批次生成生产入库单"));
        var result=answer.getRows().getFirst();
        for(var expected:Map.of("plannedQuantity",100,"completedQuantity",50,"inboundQuantity",25,"shippedQuantity",20).entrySet())
            assertEquals(0,BigDecimal.valueOf(expected.getValue()).compareTo(new BigDecimal(result.get(expected.getKey()).toString())),expected.getKey());
        verify(mapper).selectOrderInbound(List.of(1L));
    }
}
