package com.neuedu.ems.controller.wms;

import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.wms.WmsSalesOutboundService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsSalesOutboundControllerTest {
    @Test
    void detailOnlyReturnsActiveSalesOrders() {
        WmsSalesOutboundService service = mock(WmsSalesOutboundService.class);
        WmsSalesOutboundController controller = new WmsSalesOutboundController();
        ReflectionTestUtils.setField(controller, "wmsSalesOutboundService", service);
        WmsOutboundOrder sale = new WmsOutboundOrder();
        sale.setOutboundId(7L);
        sale.setOutboundType("SALE");
        sale.setDeletedFlag(0);
        when(service.getById(7L)).thenReturn(sale);
        assertEquals(sale, controller.get(7L).getData());

        WmsOutboundOrder issue = new WmsOutboundOrder();
        issue.setOutboundType("MATERIAL_ISSUE");
        issue.setDeletedFlag(0);
        when(service.getById(8L)).thenReturn(issue);
        assertThrows(BusinessException.class, () -> controller.get(8L));
        assertThrows(BusinessException.class, () -> controller.get(9L));
        sale.setDeletedFlag(1);
        assertThrows(BusinessException.class, () -> controller.get(7L));
    }
}
