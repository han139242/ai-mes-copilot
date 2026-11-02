package com.neuedu.ems.service.impl.mfg;

import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgSalesOrder;
import com.neuedu.ems.entity.mfg.MfgSalesOrderDetail;
import com.neuedu.ems.mapper.mfg.MfgSalesOrderDetailMapper;
import com.neuedu.ems.mapper.mfg.MfgSalesOrderMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.service.mdm.ProductQuantityService;
import com.neuedu.ems.service.mfg.MfgProductionOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售订单服务单元测试。
 *
 * <p>重点是确认「销售订单 → 生产订单」这条链的口径：确认后必须逐行生成生产订单，
 * 并把来源订单 ID 写回，否则销售出库时无法反查是哪张销售单，D2-5 的追溯就断了。
 */
class MfgSalesOrderServiceImplTest {

    private MfgSalesOrderServiceImpl service;
    private MfgSalesOrderMapper salesOrderMapper;
    private MfgSalesOrderDetailMapper detailMapper;
    private MfgProductionOrderService productionOrderService;
    private ProductQuantityService productQuantities;

    @BeforeEach
    void setUp() {
        service = new MfgSalesOrderServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        salesOrderMapper = mock(MfgSalesOrderMapper.class);
        detailMapper = mock(MfgSalesOrderDetailMapper.class);
        productionOrderService = mock(MfgProductionOrderService.class);
        productQuantities = mock(ProductQuantityService.class);
        ReflectionTestUtils.setField(service, "baseMapper", salesOrderMapper);
        ReflectionTestUtils.setField(service, "detailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "productionOrderService", productionOrderService);
        ReflectionTestUtils.setField(service, "productQuantities", productQuantities);
        ReflectionTestUtils.setField(service, "sysStatusChangeLogMapper", mock(SysStatusChangeLogMapper.class));
        ReflectionTestUtils.setField(service, "sysOperationLogMapper", mock(SysOperationLogMapper.class));
    }

    @Test
    void saveRejectsOrderWithoutCustomer() {
        MfgSalesOrder order = new MfgSalesOrder();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.saveSalesOrderFull(order, List.of(detail(11L, "2")), 7L));

        assertTrue(error.getMessage().contains("客户不能为空"));
        verify(salesOrderMapper, never()).insert(any(MfgSalesOrder.class));
    }

    @Test
    void saveRejectsDetailWithNonPositiveQuantity() {
        MfgSalesOrder order = new MfgSalesOrder();
        order.setCustomerId(3L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.saveSalesOrderFull(order, List.of(detail(11L, "0")), 7L));

        assertTrue(error.getMessage().contains("销售明细数量必须大于 0"));
        verify(salesOrderMapper, never()).insert(any(MfgSalesOrder.class));
    }

    @Test
    void saveForcesDraftAndSumsTotalFromDetails() {
        MfgSalesOrder order = new MfgSalesOrder();
        order.setCustomerId(3L);
        order.setStatus(BusinessStatus.Document.SUBMITTED);
        order.setTotalQuantity(new BigDecimal("999"));
        when(salesOrderMapper.insert(any(MfgSalesOrder.class))).thenAnswer(invocation -> {
            MfgSalesOrder saved = invocation.getArgument(0);
            saved.setSalesOrderId(88L);
            return 1;
        });

        Long id = service.saveSalesOrderFull(order,
                List.of(detail(11L, "2.5"), detail(12L, "1.5")), 7L);

        assertEquals(88L, id);
        // 前端传的状态和总数量一律不信：草稿由后端强制，总数量按明细重算。
        assertEquals(BusinessStatus.Document.DRAFT, order.getStatus());
        assertEquals(0, new BigDecimal("4").compareTo(order.getTotalQuantity()));
        assertTrue(order.getSalesOrderNo().startsWith("T-SO-20261027-"));
    }

    @Test
    void confirmGeneratesOneOrderPerDetailWithSourceLink() {
        MfgSalesOrder existing = new MfgSalesOrder();
        existing.setSalesOrderId(88L);
        existing.setSalesOrderNo("SO-20261025001");
        existing.setCustomerId(3L);
        existing.setStatus(BusinessStatus.Document.DRAFT);
        existing.setDeliveryDate(LocalDate.now().plusDays(7));
        when(salesOrderMapper.selectOne(any())).thenReturn(existing);
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detailRow(501L, 11L, "2"), detailRow(502L, 12L, "3")));
        List<MfgProductionOrder> savedOrders = new ArrayList<>();
        when(productionOrderService.saveOrder(any(MfgProductionOrder.class))).thenAnswer(invocation -> {
            MfgProductionOrder order = invocation.getArgument(0);
            order.setProductionOrderId(900L + order.getProductId());
            savedOrders.add(order);
            return true;
        });
        when(salesOrderMapper.updateById(any(MfgSalesOrder.class))).thenReturn(1);

        List<Long> generated = service.confirmSalesOrder(88L, 7L, "客户催单");

        assertEquals(2, generated.size());
        // 每行明细一张生产订单，来源字段必须写回，否则销售出库反查不到销售单。
        assertEquals(2, savedOrders.size());
        assertEquals(Long.valueOf(88L), savedOrders.get(0).getSourceSalesOrderId());
        assertEquals(Long.valueOf(501L), savedOrders.get(0).getSourceSalesOrderDetailId());
        assertEquals("SALES_ORDER", savedOrders.get(0).getSourceType());
        assertEquals(Long.valueOf(502L), savedOrders.get(1).getSourceSalesOrderDetailId());
    }

    @Test
    void confirmRejectsPastDeliveryDate() {
        MfgSalesOrder existing = new MfgSalesOrder();
        existing.setSalesOrderId(88L);
        existing.setStatus(BusinessStatus.Document.DRAFT);
        existing.setDeliveryDate(LocalDate.now().minusDays(1));
        when(salesOrderMapper.selectOne(any())).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.confirmSalesOrder(88L, 7L, null));

        assertTrue(error.getMessage().contains("已早于当前日期"));
        verify(productionOrderService, never()).saveOrder(any());
    }

    @Test
    void confirmRejectsSubmittedOrder() {
        MfgSalesOrder existing = new MfgSalesOrder();
        existing.setSalesOrderId(88L);
        existing.setStatus(BusinessStatus.Document.SUBMITTED);
        existing.setDeliveryDate(LocalDate.now().plusDays(3));
        when(salesOrderMapper.selectOne(any())).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.confirmSalesOrder(88L, 7L, null));

        assertTrue(error.getMessage().contains("只有草稿销售订单可以确认"));
        verify(productionOrderService, never()).saveOrder(any());
    }

    @Test
    void refreshWritesDeliveredQuantityFromOutboundFlow() {
        MfgSalesOrderDetail row = detailRow(501L, 11L, "5");
        row.setDeliveredQuantity(BigDecimal.ZERO);
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        when(salesOrderMapper.deliveredQuantityOfDetail(501L)).thenReturn(new BigDecimal("3"));
        when(detailMapper.updateById(any(MfgSalesOrderDetail.class))).thenReturn(1);

        service.refreshDeliveredQuantity(88L, 7L);

        verify(detailMapper).updateById(any(MfgSalesOrderDetail.class));
    }

    @Test
    void refreshSkipsWhenQuantityUnchanged() {
        MfgSalesOrderDetail row = detailRow(501L, 11L, "5");
        row.setDeliveredQuantity(new BigDecimal("3"));
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        when(salesOrderMapper.deliveredQuantityOfDetail(501L)).thenReturn(new BigDecimal("3"));

        service.refreshDeliveredQuantity(88L, 7L);

        // 数量没变就不写库，避免每条出库都产生一次无意义更新和版本号跳动。
        verify(detailMapper, never()).updateById(any(MfgSalesOrderDetail.class));
    }

    @Test
    void refreshClosesOrderWhenEveryDetailDelivered() {
        MfgSalesOrderDetail row = detailRow(501L, 11L, "5");
        row.setDeliveredQuantity(BigDecimal.ZERO);
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        when(salesOrderMapper.deliveredQuantityOfDetail(501L)).thenReturn(new BigDecimal("5"));
        when(detailMapper.updateById(any(MfgSalesOrderDetail.class))).thenReturn(1);
        MfgSalesOrder existing = new MfgSalesOrder();
        existing.setSalesOrderId(88L);
        existing.setSalesOrderNo("SO-20261025001");
        existing.setStatus(BusinessStatus.Document.SUBMITTED);
        existing.setVersion(0);
        when(salesOrderMapper.selectOne(any())).thenReturn(existing);
        when(salesOrderMapper.updateById(any(MfgSalesOrder.class))).thenReturn(1);

        service.refreshDeliveredQuantity(88L, 7L);

        // 全部发完就该自动关闭，否则订单页永远停在「已确认」，台账看不出结束。
        verify(salesOrderMapper).updateById(org.mockito.ArgumentMatchers
                .<MfgSalesOrder>argThat(update -> BusinessStatus.Document.CLOSED.equals(update.getStatus())));
    }

    @Test
    void refreshKeepsOrderOpenWhenAnyDetailShort() {
        MfgSalesOrderDetail delivered = detailRow(501L, 11L, "5");
        delivered.setDeliveredQuantity(BigDecimal.ZERO);
        MfgSalesOrderDetail short1 = detailRow(502L, 12L, "5");
        short1.setDeliveredQuantity(BigDecimal.ZERO);
        when(detailMapper.selectList(any())).thenReturn(List.of(delivered, short1));
        when(salesOrderMapper.deliveredQuantityOfDetail(501L)).thenReturn(new BigDecimal("5"));
        when(salesOrderMapper.deliveredQuantityOfDetail(502L)).thenReturn(new BigDecimal("2"));
        when(detailMapper.updateById(any(MfgSalesOrderDetail.class))).thenReturn(1);

        service.refreshDeliveredQuantity(88L, 7L);

        // 还有一行没发完就不能关闭，否则未交付的部分会被当成已结束。
        verify(salesOrderMapper, never()).updateById(any(MfgSalesOrder.class));
    }

    @Test
    void refreshDoesNotCloseOrderThatIsStillDraft() {
        MfgSalesOrderDetail row = detailRow(501L, 11L, "5");
        row.setDeliveredQuantity(BigDecimal.ZERO);
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        when(salesOrderMapper.deliveredQuantityOfDetail(501L)).thenReturn(new BigDecimal("5"));
        when(detailMapper.updateById(any(MfgSalesOrderDetail.class))).thenReturn(1);
        MfgSalesOrder existing = new MfgSalesOrder();
        existing.setSalesOrderId(88L);
        existing.setStatus(BusinessStatus.Document.DRAFT);
        existing.setVersion(0);
        when(salesOrderMapper.selectOne(any())).thenReturn(existing);

        service.refreshDeliveredQuantity(88L, 7L);

        // 只对「已确认」的订单自动关闭；草稿单状态由人工确认流程推进。
        verify(salesOrderMapper, never()).updateById(any(MfgSalesOrder.class));
    }

    // ------------------------------------------------------------------ 辅助

    private MfgSalesOrderDetail detail(Long productId, String quantity) {
        MfgSalesOrderDetail detail = new MfgSalesOrderDetail();
        detail.setProductId(productId);
        detail.setOrderQuantity(new BigDecimal(quantity));
        return detail;
    }

    private MfgSalesOrderDetail detailRow(Long detailId, Long productId, String quantity) {
        MfgSalesOrderDetail detail = detail(productId, quantity);
        detail.setDetailId(detailId);
        detail.setSalesOrderId(88L);
        detail.setVersion(0);
        return detail;
    }
}
