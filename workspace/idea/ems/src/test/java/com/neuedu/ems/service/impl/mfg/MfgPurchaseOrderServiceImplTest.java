package com.neuedu.ems.service.impl.mfg;

import com.neuedu.ems.entity.mfg.MfgPurchaseOrder;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrderDetail;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmSupplierMapper;
import com.neuedu.ems.mapper.mfg.MfgPurchaseOrderDetailMapper;
import com.neuedu.ems.mapper.mfg.MfgPurchaseOrderMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.wms.WmsInboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购订单服务单元测试。
 *
 * <p>重点是「下达 → 生成采购入库单」这条链：入库单必须带
 * {@code source_order_type=PURCHASE_ORDER} 和 {@code source_order_id}，
 * 否则收货确认后回写不到采购单，已收数量永远是 0。</p>
 */
class MfgPurchaseOrderServiceImplTest {

    private MfgPurchaseOrderServiceImpl service;
    private MfgPurchaseOrderMapper purchaseOrderMapper;
    private MfgPurchaseOrderDetailMapper detailMapper;
    private WmsInboundOrderService inboundOrderService;
    private WmsInboundOrderMapper inboundOrderMapper;
    private WmsWarehouseMapper warehouseMapper;
    private MdmSupplierMapper supplierMapper;
    private MdmMaterialMapper materialMapper;

    @BeforeEach
    void setUp() {
        service = new MfgPurchaseOrderServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        purchaseOrderMapper = mock(MfgPurchaseOrderMapper.class);
        detailMapper = mock(MfgPurchaseOrderDetailMapper.class);
        inboundOrderService = mock(WmsInboundOrderService.class);
        inboundOrderMapper = mock(WmsInboundOrderMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        supplierMapper = mock(MdmSupplierMapper.class);
        materialMapper = mock(MdmMaterialMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", purchaseOrderMapper);
        ReflectionTestUtils.setField(service, "detailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "inboundOrderService", inboundOrderService);
        ReflectionTestUtils.setField(service, "inboundOrderMapper", inboundOrderMapper);
        ReflectionTestUtils.setField(service, "inboundDetailMapper", mock(WmsInboundDetailMapper.class));
        ReflectionTestUtils.setField(service, "warehouseMapper", warehouseMapper);
        ReflectionTestUtils.setField(service, "supplierMapper", supplierMapper);
        ReflectionTestUtils.setField(service, "materialMapper", materialMapper);
        ReflectionTestUtils.setField(service, "sysStatusChangeLogMapper", mock(SysStatusChangeLogMapper.class));
        ReflectionTestUtils.setField(service, "sysOperationLogMapper", mock(SysOperationLogMapper.class));
    }

    @Test
    void saveRejectsOrderWithoutSupplier() {
        MfgPurchaseOrder order = new MfgPurchaseOrder();

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.savePurchaseOrderFull(order, List.of(detail(21L, "5")), 7L));

        assertTrue(error.getMessage().contains("供应商不能为空"));
        verify(purchaseOrderMapper, never()).insert(any(MfgPurchaseOrder.class));
    }

    @Test
    void saveRejectsDuplicateMaterialRows() {
        MfgPurchaseOrder order = order(5L, 6L);
        when(supplierMapper.selectOne(any())).thenReturn(enabledSupplier(5L));
        when(materialMapper.selectOne(any())).thenReturn(enabledMaterial(21L));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.savePurchaseOrderFull(order,
                        List.of(detail(21L, "5"), detail(21L, "3")), 7L));

        // 已收数量按「入库单头 + 物料」汇总，两行同料会让已收数量凭空翻倍。
        assertTrue(error.getMessage().contains("同一个物料"));
    }

    @Test
    void saveForcesDraftAndSumsTotalFromDetails() {
        MfgPurchaseOrder order = order(5L, 6L);
        order.setStatus("RELEASED");
        order.setTotalQuantity(new BigDecimal("999"));
        when(supplierMapper.selectOne(any())).thenReturn(enabledSupplier(5L));
        when(materialMapper.selectOne(any())).thenReturn(enabledMaterial(21L));
        when(purchaseOrderMapper.insert(any(MfgPurchaseOrder.class))).thenAnswer(invocation -> {
            MfgPurchaseOrder saved = invocation.getArgument(0);
            saved.setPurchaseOrderId(77L);
            return 1;
        });

        List<MfgPurchaseOrderDetail> details = new ArrayList<>(
                List.of(detail(21L, "5"), detail(22L, "3")));
        service.savePurchaseOrderFull(order, details, 7L);

        // 状态和总量都不能由前端决定：草稿起步，总量按明细汇总。
        assertEquals("DRAFT", order.getStatus());
        assertEquals(0, new BigDecimal("8").compareTo(order.getTotalQuantity()));
        assertEquals(Long.valueOf(77L), details.get(0).getPurchaseOrderId());
    }

    @Test
    void releaseRejectsMissingReceivingWarehouse() {
        MfgPurchaseOrder existing = order(5L, null);
        existing.setPurchaseOrderId(77L);
        existing.setStatus("DRAFT");
        when(purchaseOrderMapper.selectOne(any())).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.releasePurchaseOrder(77L, 7L, null));

        // 下单时还不知道货进哪个仓，但下达前必须指定，否则收货单没有落点。
        assertTrue(error.getMessage().contains("收货仓库"));
        verify(inboundOrderService, never()).saveInboundOrderFull(any(), any());
    }

    @Test
    void releaseGeneratesPurchaseInboundWithSourceLink() {
        MfgPurchaseOrder existing = order(5L, 6L);
        existing.setPurchaseOrderId(77L);
        existing.setPurchaseOrderNo("PO-BUY-20261025001");
        existing.setStatus("DRAFT");
        when(purchaseOrderMapper.selectOne(any())).thenReturn(existing);
        when(warehouseMapper.selectOne(any())).thenReturn(rawWarehouse(6L));
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(21L, "5")));
        when(materialMapper.selectOne(any())).thenReturn(enabledMaterial(21L));
        when(inboundOrderService.saveInboundOrderFull(any(WmsInboundOrder.class), any()))
                .thenReturn(301L);
        when(purchaseOrderMapper.updateById(any(MfgPurchaseOrder.class))).thenReturn(1);

        Long inboundId = service.releasePurchaseOrder(77L, 7L, "催货");

        assertEquals(Long.valueOf(301L), inboundId);
        verify(inboundOrderService).saveInboundOrderFull(
                org.mockito.ArgumentMatchers.argThat(inbound ->
                        "PURCHASE".equals(inbound.getInboundType())
                                && "PURCHASE_ORDER".equals(inbound.getSourceOrderType())
                                && Long.valueOf(77L).equals(inbound.getSourceOrderId())
                                && Long.valueOf(6L).equals(inbound.getWarehouseId())),
                any());
    }

    @Test
    void releaseRejectsNonRawWarehouse() {
        MfgPurchaseOrder existing = order(5L, 6L);
        existing.setPurchaseOrderId(77L);
        existing.setStatus("DRAFT");
        when(purchaseOrderMapper.selectOne(any())).thenReturn(existing);
        WmsWarehouse finished = rawWarehouse(6L);
        finished.setWarehouseType("FINISHED");
        when(warehouseMapper.selectOne(any())).thenReturn(finished);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.releasePurchaseOrder(77L, 7L, null));

        // 采购只能进原材料仓，否则来料检验和齐套都算不对。
        assertTrue(error.getMessage().contains("原料仓"));
        verify(inboundOrderService, never()).saveInboundOrderFull(any(), any());
    }

    @Test
    void releaseRejectsAlreadyReleased() {
        MfgPurchaseOrder existing = order(5L, 6L);
        existing.setPurchaseOrderId(77L);
        existing.setStatus("RELEASED");
        when(purchaseOrderMapper.selectOne(any())).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.releasePurchaseOrder(77L, 7L, null));

        assertTrue(error.getMessage().contains("草稿"));
    }

    @Test
    void withdrawRejectsWhenInboundAlreadyApproved() {
        MfgPurchaseOrder existing = order(5L, 6L);
        existing.setPurchaseOrderId(77L);
        existing.setStatus("RELEASED");
        when(purchaseOrderMapper.selectOne(any())).thenReturn(existing);
        WmsInboundOrder approved = new WmsInboundOrder();
        approved.setInboundId(301L);
        approved.setInboundNo("IN-BUY-77-1");
        approved.setAuditStatus("APPROVED");
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of(approved));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.withdrawPurchaseOrder(77L, 7L, null));

        // 货已经入库了就不能撤回采购单，否则库存和单据对不上。
        assertTrue(error.getMessage().contains("已确认入库"));
        verify(inboundOrderService, never()).removeInboundOrderById(any());
    }

    @Test
    void refreshWritesReceivedQuantityFromApprovedInbound() {
        MfgPurchaseOrderDetail row = detail(21L, "5");
        row.setDetailId(401L);
        row.setReceivedQuantity(BigDecimal.ZERO);
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        Map<String, Object> sum = new HashMap<>();
        sum.put("materialId", 21L);
        sum.put("receivedQuantity", new BigDecimal("3.5"));
        when(purchaseOrderMapper.receivedQuantityByMaterial(77L)).thenReturn(List.of(sum));

        service.refreshReceivedQuantity(77L, 7L);

        verify(detailMapper).updateById(org.mockito.ArgumentMatchers.<MfgPurchaseOrderDetail>argThat(update ->
                update.getDetailId().equals(401L)
                        && new BigDecimal("3.5").compareTo(update.getReceivedQuantity()) == 0));
    }

    @Test
    void refreshSkipsWhenQuantityUnchanged() {
        MfgPurchaseOrderDetail row = detail(21L, "5");
        row.setDetailId(401L);
        row.setReceivedQuantity(new BigDecimal("3.5"));
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        Map<String, Object> sum = new HashMap<>();
        sum.put("materialId", 21L);
        sum.put("receivedQuantity", new BigDecimal("3.5"));
        when(purchaseOrderMapper.receivedQuantityByMaterial(77L)).thenReturn(List.of(sum));

        service.refreshReceivedQuantity(77L, 7L);

        verify(detailMapper, never()).updateById(any(MfgPurchaseOrderDetail.class));
    }

    @Test
    void refreshClosesOrderWhenEveryDetailReceived() {
        MfgPurchaseOrderDetail row = detail(21L, "5");
        row.setDetailId(401L);
        row.setReceivedQuantity(BigDecimal.ZERO);
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        Map<String, Object> sum = new HashMap<>();
        sum.put("materialId", 21L);
        sum.put("receivedQuantity", new BigDecimal("5"));
        when(purchaseOrderMapper.receivedQuantityByMaterial(77L)).thenReturn(List.of(sum));
        when(detailMapper.updateById(any(MfgPurchaseOrderDetail.class))).thenReturn(1);
        MfgPurchaseOrder existing = order(5L, 6L);
        existing.setPurchaseOrderId(77L);
        existing.setPurchaseOrderNo("PO-20261025001");
        existing.setStatus("RELEASED");
        when(purchaseOrderMapper.selectOne(any())).thenReturn(existing);
        when(purchaseOrderMapper.updateById(any(MfgPurchaseOrder.class))).thenReturn(1);

        service.refreshReceivedQuantity(77L, 7L);

        // 全部收满就自动关闭，否则采购订单永远停在「已下达」，对账时分不清是否收完。
        verify(purchaseOrderMapper).updateById(org.mockito.ArgumentMatchers
                .<MfgPurchaseOrder>argThat(update -> "CLOSED".equals(update.getStatus())));
    }

    @Test
    void refreshKeepsOrderOpenWhenReceivedLessThanOrdered() {
        MfgPurchaseOrderDetail row = detail(21L, "5");
        row.setDetailId(401L);
        row.setReceivedQuantity(BigDecimal.ZERO);
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        Map<String, Object> sum = new HashMap<>();
        sum.put("materialId", 21L);
        sum.put("receivedQuantity", new BigDecimal("3.5"));
        when(purchaseOrderMapper.receivedQuantityByMaterial(77L)).thenReturn(List.of(sum));
        when(detailMapper.updateById(any(MfgPurchaseOrderDetail.class))).thenReturn(1);

        service.refreshReceivedQuantity(77L, 7L);

        verify(purchaseOrderMapper, never()).updateById(any(MfgPurchaseOrder.class));
    }

    @Test
    void refreshDoesNotCloseOrderThatIsStillDraft() {
        MfgPurchaseOrderDetail row = detail(21L, "5");
        row.setDetailId(401L);
        row.setReceivedQuantity(BigDecimal.ZERO);
        when(detailMapper.selectList(any())).thenReturn(List.of(row));
        Map<String, Object> sum = new HashMap<>();
        sum.put("materialId", 21L);
        sum.put("receivedQuantity", new BigDecimal("5"));
        when(purchaseOrderMapper.receivedQuantityByMaterial(77L)).thenReturn(List.of(sum));
        when(detailMapper.updateById(any(MfgPurchaseOrderDetail.class))).thenReturn(1);
        MfgPurchaseOrder existing = order(5L, 6L);
        existing.setPurchaseOrderId(77L);
        existing.setStatus("DRAFT");
        when(purchaseOrderMapper.selectOne(any())).thenReturn(existing);

        service.refreshReceivedQuantity(77L, 7L);

        verify(purchaseOrderMapper, never()).updateById(any(MfgPurchaseOrder.class));
    }

    // ------------------------------------------------------------------ 辅助

    private MfgPurchaseOrder order(Long supplierId, Long warehouseId) {
        MfgPurchaseOrder order = new MfgPurchaseOrder();
        order.setSupplierId(supplierId);
        order.setWarehouseId(warehouseId);
        return order;
    }

    private MfgPurchaseOrderDetail detail(Long materialId, String quantity) {
        MfgPurchaseOrderDetail detail = new MfgPurchaseOrderDetail();
        detail.setMaterialId(materialId);
        detail.setOrderQuantity(new BigDecimal(quantity));
        return detail;
    }

    private WmsWarehouse rawWarehouse(Long warehouseId) {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setWarehouseId(warehouseId);
        warehouse.setWarehouseType("RAW");
        warehouse.setStatus("ENABLE");
        return warehouse;
    }

    private com.neuedu.ems.entity.mdm.MdmSupplier enabledSupplier(Long supplierId) {
        com.neuedu.ems.entity.mdm.MdmSupplier supplier = new com.neuedu.ems.entity.mdm.MdmSupplier();
        supplier.setSupplierId(supplierId);
        supplier.setStatus("ENABLE");
        return supplier;
    }

    private com.neuedu.ems.entity.mdm.MdmMaterial enabledMaterial(Long materialId) {
        com.neuedu.ems.entity.mdm.MdmMaterial material = new com.neuedu.ems.entity.mdm.MdmMaterial();
        material.setMaterialId(materialId);
        material.setStatus("ENABLE");
        return material;
    }
}
