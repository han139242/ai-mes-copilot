package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.ai.AiDataQueryService;
import com.neuedu.ems.service.sys.BusinessNumberService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiBusinessNumberCompatibilityTest {
    private final AiIntentRouterImpl router = new AiIntentRouterImpl(mock(AiDataQueryService.class));

    @Test void readinessKeepsCustomerPrefix() {
        var result=router.route("B-WO-20261027-001 这张工单能不能开工",List.of(),null);
        assertEquals("work_order_readiness",result.intent());
        assertEquals("B-WO-20261027-001",result.params().get("workOrderNo"));
    }

    @Test void startPlanKeepsEightCharacterCustomerAndLongerDailySequence() {
        var result=router.route("C1234567-WO-20261027-1000 怎么才能开工，先做什么",List.of(),null);
        assertEquals("work_order_start_plan",result.intent());
        assertEquals("C1234567-WO-20261027-1000",result.params().get("workOrderNo"));
    }

    @Test void orderProgressKeepsCustomerPrefix() {
        var result=router.route("查询 B-PO-20261027-001 的订单进度",List.of(),null);
        assertEquals("order_progress",result.intent());
        assertEquals("B-PO-20261027-001",result.params().get("orderNo"));
    }

    @Test void oldComponentWorkOrderStillRoutesWithoutTruncation() {
        String number="WO-C-71-75-1-20261026195033348";
        assertEquals(number,router.route(number+" 能不能开工",List.of(),null).params().get("workOrderNo"));
    }

    @Test void readQueriesResolveHistoricalAliasesBeforeExactLookup() {
        var mapper=mock(AiDataQueryMapper.class);
        var service=new AiDataQueryServiceImpl(mapper,mock(MdmMaterialMapper.class),mock(MdmProductMapper.class),mock(WmsWarehouseMapper.class));
        var numbers=mock(BusinessNumberService.class);
        ReflectionTestUtils.setField(service,"documentNumbers",numbers);
        when(numbers.canonical("OLD-WORK")).thenReturn("B-WO-20261027-001");
        when(numbers.canonical("B-WO-20261027-001")).thenReturn("B-WO-20261027-001");
        when(numbers.canonical("OLD-ORDER")).thenReturn("B-PO-20261027-001");
        service.queryWorkOrderDetail(" OLD-WORK ");
        service.queryWorkOrderReadiness("OLD-WORK");
        service.queryWorkOrderStartPlan("OLD-WORK");
        service.queryOrderProgress("OLD-ORDER");
        verify(mapper,times(3)).selectWorkOrderHeader("B-WO-20261027-001");
        verify(mapper).selectOrderHeader("B-PO-20261027-001");
        verify(mapper,never()).selectWorkOrderHeader("OLD-WORK");
    }
}
