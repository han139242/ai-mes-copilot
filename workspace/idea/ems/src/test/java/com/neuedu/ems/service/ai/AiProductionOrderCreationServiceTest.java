package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.mdm.MdmCustomer;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmCustomerMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.service.mfg.CommandRequestService;
import com.neuedu.ems.service.mfg.MfgProductionOrderService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiProductionOrderCreationServiceTest {
    private final MfgProductionOrderService orders = mock(MfgProductionOrderService.class);
    private final MdmProductMapper products = mock(MdmProductMapper.class);
    private final MdmCustomerMapper customers = mock(MdmCustomerMapper.class);
    private final CommandRequestService requests = mock(CommandRequestService.class);
    private final AiProductionOrderCreationService service =
            new AiProductionOrderCreationService(orders, products, customers, requests);

    @Test
    void createsRealDraftThroughOrderServiceAndRecordsRequest() {
        MdmProduct product = new MdmProduct(); product.setDeletedFlag(0); product.setStatus(BusinessStatus.Common.ENABLE);
        MdmCustomer customer = new MdmCustomer(); customer.setDeletedFlag(0); customer.setStatus(BusinessStatus.Common.ENABLE);
        when(products.selectById(1L)).thenReturn(product);
        when(customers.selectById(2L)).thenReturn(customer);
        when(requests.digest(anyString())).thenReturn("digest");
        when(requests.replayed("AI_CREATE_PRODUCTION_ORDER", "key", "digest")).thenReturn(null);
        when(orders.saveOrder(any())).thenAnswer(call -> {
            MfgProductionOrder order = call.getArgument(0); order.setProductionOrderId(8L); return true;
        });
        var result = service.create(1L, 2L, new BigDecimal("2"), LocalDate.of(2026, 11, 3), 7L, "key");
        assertEquals(8L, result.getProductionOrderId());
        verify(requests).record(eq("AI_CREATE_PRODUCTION_ORDER"), eq("key"), eq("PRODUCTION_ORDER"), eq(8L),
                eq("digest"), eq(8L), eq(7L), isNull(), any());
    }

    @Test
    void retryReplaysOriginalOrderWithoutCreatingAnother() {
        MfgProductionOrder existing = new MfgProductionOrder(); existing.setProductionOrderId(8L);
        when(requests.digest(anyString())).thenReturn("digest");
        when(requests.replayed("AI_CREATE_PRODUCTION_ORDER", "key", "digest")).thenReturn(8L);
        when(orders.getOrderById(8L)).thenReturn(existing);
        assertSame(existing, service.create(1L, 2L, BigDecimal.valueOf(2), LocalDate.of(2026, 11, 3), 7L, "key"));
        verify(orders, never()).saveOrder(any());
    }

    @Test
    void missingCustomerCannotCreateAnOrder() {
        assertThrows(BusinessException.class,
                () -> service.create(1L, null, BigDecimal.ONE, LocalDate.of(2026, 11, 3), 7L, "key"));
        verify(orders, never()).saveOrder(any());
    }
}
