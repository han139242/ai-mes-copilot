package com.neuedu.ems.service.impl.wms;

import com.neuedu.ems.entity.mdm.MdmCustomer;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmCustomerMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsSalesOutboundCustomerTest {
    private WmsSalesOutboundServiceImpl service;
    private WmsOutboundOrderMapper outbounds;
    private MfgProductionOrderMapper productions;

    @BeforeEach
    void setUp() {
        service = new WmsSalesOutboundServiceImpl();
        outbounds = mock(WmsOutboundOrderMapper.class);
        productions = mock(MfgProductionOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", outbounds);
        ReflectionTestUtils.setField(service, "mfgProductionOrderMapper", productions);
        ReflectionTestUtils.setField(service, "preparationGate",
                mock(com.neuedu.ems.service.mdm.ManufacturingPreparationGate.class));
        var production = new MfgProductionOrder();
        production.setProductionOrderId(10L);
        production.setCustomerId(1L);
        production.setStatus("FINISHED");
        production.setDeletedFlag(0);
        when(productions.selectOne(any())).thenReturn(production);
        when(productions.selectById(10L)).thenReturn(production);
    }

    @Test
    void generationRejectsDifferentCustomerEvenWhenBothCustomerAndWarehouseAreEnabled() {
        var customers = mock(MdmCustomerMapper.class);
        var customer = new MdmCustomer();
        customer.setCustomerId(2L);
        customer.setStatus("ENABLE");
        when(customers.selectOne(any())).thenReturn(customer);
        ReflectionTestUtils.setField(service, "mdmCustomerMapper", customers);
        var warehouses = mock(WmsWarehouseMapper.class);
        var warehouse = new WmsWarehouse();
        warehouse.setWarehouseType("FINISHED");
        warehouse.setStatus("ENABLE");
        when(warehouses.selectOne(any())).thenReturn(warehouse);
        ReflectionTestUtils.setField(service, "wmsWarehouseMapper", warehouses);
        when(outbounds.selectList(any())).thenReturn(List.of(outbound()));

        var error = assertThrows(BusinessException.class,
                () -> service.generateSalesOutbound(10L, 2L, 4L, null, null, 1L));
        assertTrue(error.getMessage().contains("客户") && error.getMessage().contains("一致"));
        verify(outbounds, never()).insert(any(WmsOutboundOrder.class));
    }

    @Test
    void confirmationRejectsLegacyMismatchedCustomerBeforeInspectionAndInventoryChanges() {
        when(outbounds.selectOne(any())).thenReturn(outbound());
        var error = assertThrows(BusinessException.class, () -> service.confirmSalesOutbound(20L, 1L, null));
        assertTrue(error.getMessage().contains("客户") && error.getMessage().contains("一致"));
        verify(outbounds, never()).updateById(any(WmsOutboundOrder.class));
    }

    private WmsOutboundOrder outbound() {
        var order = new WmsOutboundOrder();
        order.setOutboundId(20L);
        order.setOutboundType("SALE");
        order.setSourceProductionOrderId(10L);
        order.setCustomerId(2L);
        order.setAuditStatus("SUBMITTED");
        order.setDeletedFlag(0);
        return order;
    }
}
