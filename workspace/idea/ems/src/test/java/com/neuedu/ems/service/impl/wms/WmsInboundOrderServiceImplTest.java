package com.neuedu.ems.service.impl.wms;

import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.trace.TraceProductLot;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.trace.TraceProductLotMapper;
import com.neuedu.ems.mapper.wms.WmsInboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInboundOrderServiceImplTest {

    private WmsInboundOrderServiceImpl service;
    private WmsInboundOrderMapper orderMapper;
    private WmsInboundDetailMapper detailMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsStockMovementMapper movementMapper;
    private WmsMaterialBatchMapper batchMapper;
    private WmsResponsibilityNotificationService notificationService;
    private TraceProductLotMapper productLotMapper;
    private MdmProductMapper productMapper;
    private com.neuedu.ems.mapper.mdm.MdmMaterialMapper materialMapper;
    private com.neuedu.ems.mapper.mdm.MdmBomMapper bomMapper;
    private com.neuedu.ems.mapper.mdm.MdmBomItemMapper bomItemMapper;
    private com.neuedu.ems.mapper.qms.QmsInspectionSchemeMapper schemeMapper;
    private com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper inspectionOrderMapper;
    private com.neuedu.ems.service.qms.QmsInspectionOrderService inspectionService;

    @BeforeEach
    void setUp() {
        service = new WmsInboundOrderServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        ReflectionTestUtils.setField(service, "executionQuantities", mock(com.neuedu.ems.service.mdm.MaterialExecutionQuantityService.class));
        orderMapper = mock(WmsInboundOrderMapper.class);
        detailMapper = mock(WmsInboundDetailMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        movementMapper = mock(WmsStockMovementMapper.class);
        batchMapper = mock(WmsMaterialBatchMapper.class);
        productLotMapper = mock(TraceProductLotMapper.class);
        productMapper = mock(MdmProductMapper.class);
        SysStatusChangeLogMapper statusLogMapper = mock(SysStatusChangeLogMapper.class);
        SysOperationLogMapper operationLogMapper = mock(SysOperationLogMapper.class);

        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(service, "wmsInboundDetailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "wmsInventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(service, "wmsStockMovementMapper", movementMapper);
        ReflectionTestUtils.setField(service, "wmsMaterialBatchMapper", batchMapper);
        var locationMapper = mock(com.neuedu.ems.mapper.wms.WmsLocationMapper.class);
        var location = new com.neuedu.ems.entity.wms.WmsLocation();
        location.setLocationId(8L);
        location.setWarehouseId(2L);
        location.setDeletedFlag(0);
        when(locationMapper.selectById(any(java.io.Serializable.class))).thenReturn(location);
        ReflectionTestUtils.setField(service, "inboundLocationMapper", locationMapper);
        ReflectionTestUtils.setField(service, "traceProductLotMapper", productLotMapper);
        ReflectionTestUtils.setField(service, "mdmProductMapper", productMapper);
        ReflectionTestUtils.setField(service, "sysStatusChangeLogMapper", statusLogMapper);
        ReflectionTestUtils.setField(service, "sysOperationLogMapper", operationLogMapper);
        notificationService = mock(WmsResponsibilityNotificationService.class);
        ReflectionTestUtils.setField(service, "responsibilityNotificationService", notificationService);

        materialMapper = mock(com.neuedu.ems.mapper.mdm.MdmMaterialMapper.class);
        bomMapper = mock(com.neuedu.ems.mapper.mdm.MdmBomMapper.class);
        bomItemMapper = mock(com.neuedu.ems.mapper.mdm.MdmBomItemMapper.class);
        schemeMapper = mock(com.neuedu.ems.mapper.qms.QmsInspectionSchemeMapper.class);
        inspectionOrderMapper = mock(com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper.class);
        inspectionService = mock(com.neuedu.ems.service.qms.QmsInspectionOrderService.class);
        ReflectionTestUtils.setField(service, "mdmMaterialMapper", materialMapper);
        ReflectionTestUtils.setField(service, "mdmBomMapper", bomMapper);
        ReflectionTestUtils.setField(service, "mdmBomItemMapper", bomItemMapper);
        ReflectionTestUtils.setField(service, "qmsInspectionSchemeMapper", schemeMapper);
        ReflectionTestUtils.setField(service, "qmsInspectionOrderMapper", inspectionOrderMapper);
        ReflectionTestUtils.setField(service, "qmsInspectionOrderService", inspectionService);

        when(detailMapper.selectList(any())).thenReturn(List.of(detail()));
        when(inventoryMapper.insert(any(WmsInventory.class))).thenReturn(1);
        when(movementMapper.insert(any(WmsStockMovement.class))).thenReturn(1);
        when(statusLogMapper.insert(any(SysStatusChangeLog.class))).thenReturn(1);
        when(operationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);
    }

    @Test
    void confirmInboundAcceptsReleasedBatchAndWritesInventoryMovementAndAudit() {
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(batchMapper.selectOne(any())).thenReturn(batch(BusinessStatus.Quality.PASS, "AVAILABLE"));
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);

        assertTrue(service.confirmInboundOrder(1L, 9L, "确认入库"));

        ArgumentCaptor<WmsInventory> inventory = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).insert(inventory.capture());
        assertEquals(new BigDecimal("12.0000"), inventory.getValue().getAvailableQuantity());

        ArgumentCaptor<WmsStockMovement> movement = ArgumentCaptor.forClass(WmsStockMovement.class);
        verify(movementMapper).insert(movement.capture());
        assertEquals("INBOUND", movement.getValue().getMovementType());
        assertEquals("IN", movement.getValue().getDirection());
        assertEquals(9L, movement.getValue().getCreatedBy());

        ArgumentCaptor<WmsInboundOrder> update = ArgumentCaptor.forClass(WmsInboundOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Document.APPROVED, update.getValue().getAuditStatus());
        assertEquals(3, update.getValue().getVersion());
    }

    @Test
    void concessionBatchCanEnterAvailableInventory() {
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(batchMapper.selectOne(any())).thenReturn(batch(BusinessStatus.Quality.CONCESSION, "AVAILABLE"));
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);

        assertTrue(service.confirmInboundOrder(1L, 9L, null));
        verify(inventoryMapper).insert(any(WmsInventory.class));
    }

    @Test
    void pendingInspectionBatchIsRejectedBeforeAnyInventoryMutation() {
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(batchMapper.selectOne(any())).thenReturn(batch("WAIT_INSPECTION", "FROZEN"));

        assertThrows(BusinessException.class, () -> service.confirmInboundOrder(1L, 9L, null));

        verify(inventoryMapper, never()).insert(any(WmsInventory.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
        verify(orderMapper, never()).updateById(any(WmsInboundOrder.class));
    }

    @Test
    void withdrawSubmittedInboundClosesWithoutInventoryMovement() {
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);

        service.withdrawInboundOrder(1L, 9L, null);

        ArgumentCaptor<WmsInboundOrder> update = ArgumentCaptor.forClass(WmsInboundOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Document.CLOSED, update.getValue().getAuditStatus());
        verify(inventoryMapper, never()).insert(any(WmsInventory.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
        verify(notificationService).cancelInboundPending(1L, 9L);
    }

    @Test
    void inboundOrderOptimisticConflictRaisesExceptionForTransactionRollback() {
        when(orderMapper.selectOne(any())).thenReturn(order());
        when(batchMapper.selectOne(any())).thenReturn(batch(BusinessStatus.Quality.PASS, "AVAILABLE"));
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.confirmInboundOrder(1L, 9L, null));
        verify(inventoryMapper).insert(any(WmsInventory.class));
        verify(movementMapper).insert(any(WmsStockMovement.class));
    }

    @Test
    void generateProductionInboundDerivesFinishedMaterialBatchAndRemainingQuantity() {
        TraceProductLot lot = productLot("20", BusinessStatus.Quality.PASS);
        MdmProduct product = product();
        when(productLotMapper.selectOne(any())).thenReturn(lot);
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectOne(any())).thenReturn(product);
        when(detailMapper.selectList(any())).thenReturn(List.of());
        when(batchMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            WmsMaterialBatch batch = invocation.getArgument(0);
            batch.setBatchId(7L);
            return 1;
        }).when(batchMapper).insert(any(WmsMaterialBatch.class));
        doAnswer(invocation -> {
            WmsInboundOrder inbound = invocation.getArgument(0);
            inbound.setInboundId(1L);
            return 1;
        }).when(orderMapper).insert(any(WmsInboundOrder.class));
        when(detailMapper.insert(any(WmsInboundDetail.class))).thenReturn(1);

        WmsInboundOrder result = service.generateProductionInbound(31L, 2L, 8L, 9L, null);

        assertEquals(1L, result.getInboundId());
        assertEquals("PRODUCTION", result.getInboundType());
        assertEquals("PRODUCT_LOT", result.getSourceOrderType());
        ArgumentCaptor<WmsMaterialBatch> batch = ArgumentCaptor.forClass(WmsMaterialBatch.class);
        verify(batchMapper).insert(batch.capture());
        assertEquals(31L, batch.getValue().getSourceProductLotId());
        assertEquals(11L, batch.getValue().getMaterialId());
        ArgumentCaptor<WmsInboundDetail> detail = ArgumentCaptor.forClass(WmsInboundDetail.class);
        verify(detailMapper).insert(detail.capture());
        assertEquals(new BigDecimal("20"), detail.getValue().getInboundQuantity());
        assertEquals(8L, detail.getValue().getLocationId());
    }

    @Test
    void generateProductionInboundRejectsUnreleasedProductLot() {
        when(productLotMapper.selectOne(any())).thenReturn(productLot("20", BusinessStatus.Quality.FAIL));

        assertThrows(BusinessException.class,
                () -> service.generateProductionInbound(31L, 2L, 8L, 9L, null));

        verify(orderMapper, never()).insert(any(WmsInboundOrder.class));
        verify(detailMapper, never()).insert(any(WmsInboundDetail.class));
        verify(batchMapper, never()).insert(any(WmsMaterialBatch.class));
    }

    @Test
    void repeatedProductionInboundGenerationReturnsExistingSubmittedOrder() {
        WmsInboundOrder existing = order();
        existing.setInboundType("PRODUCTION");
        existing.setSourceOrderType("PRODUCT_LOT");
        existing.setSourceOrderId(31L);
        when(productLotMapper.selectOne(any())).thenReturn(productLot("20", BusinessStatus.Quality.PASS));
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));

        WmsInboundOrder result = service.generateProductionInbound(31L, 2L, 8L, 9L, null);

        assertEquals(existing, result);
        verify(orderMapper, never()).insert(any(WmsInboundOrder.class));
        verify(detailMapper, never()).insert(any(WmsInboundDetail.class));
        verify(batchMapper, never()).insert(any(WmsMaterialBatch.class));
    }

    @Test
    void confirmProductionInboundRejectsCumulativeQuantityAboveReleasedQuantity() {
        WmsInboundOrder order = order();
        order.setInboundType("PRODUCTION");
        order.setSourceOrderType("PRODUCT_LOT");
        order.setSourceOrderId(31L);
        WmsMaterialBatch batch = batch(BusinessStatus.Quality.PASS, "AVAILABLE");
        batch.setSourceProductLotId(31L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail()), List.of());
        when(batchMapper.selectOne(any())).thenReturn(batch);
        when(productLotMapper.selectOne(any())).thenReturn(productLot("10", BusinessStatus.Quality.PASS));
        when(productMapper.selectOne(any())).thenReturn(product());

        assertThrows(BusinessException.class, () -> service.confirmInboundOrder(1L, 9L, null));

        verify(inventoryMapper, never()).insert(any(WmsInventory.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
        verify(orderMapper, never()).updateById(any(WmsInboundOrder.class));
    }

    @Test
    void confirmProductionInboundMarksFullyStoredProductLot() {
        WmsInboundOrder order = order();
        order.setInboundType("PRODUCTION");
        order.setSourceOrderType("PRODUCT_LOT");
        order.setSourceOrderId(31L);
        WmsMaterialBatch batch = batch(BusinessStatus.Quality.PASS, "AVAILABLE");
        batch.setSourceProductLotId(31L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail()), List.of());
        when(batchMapper.selectOne(any())).thenReturn(batch);
        when(productLotMapper.selectOne(any())).thenReturn(productLot("12.0000", BusinessStatus.Quality.PASS));
        when(productMapper.selectOne(any())).thenReturn(product());
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);
        when(productLotMapper.updateById(any(TraceProductLot.class))).thenReturn(1);

        assertTrue(service.confirmInboundOrder(1L, 9L, null));

        ArgumentCaptor<TraceProductLot> update = ArgumentCaptor.forClass(TraceProductLot.class);
        verify(productLotMapper).updateById(update.capture());
        assertEquals("STORED", update.getValue().getStatus());
    }

    @Test
    void incomingSchemePrefersComponentSchemeOverAssemblyScheme() {
        var assembly = new com.neuedu.ems.entity.qms.QmsInspectionScheme();
        assembly.setSchemeId(28L); assembly.setProductId(5L);
        var component = new com.neuedu.ems.entity.qms.QmsInspectionScheme();
        component.setSchemeId(29L); component.setProductId(6L);
        when(schemeMapper.selectList(any())).thenReturn(List.of(assembly, component));
        var root = new com.neuedu.ems.entity.mdm.MdmBom(); root.setBomId(5L);
        var childBom = new com.neuedu.ems.entity.mdm.MdmBom(); childBom.setBomId(6L);
        when(bomMapper.selectList(any())).thenReturn(List.of(root), List.of(childBom));
        var child = new com.neuedu.ems.entity.mdm.MdmBomItem(); child.setChildBomId(6L);
        when(bomItemMapper.selectList(any())).thenReturn(List.of(child));
        when(bomItemMapper.selectCount(any())).thenReturn(0L, 1L, 1L);
        assertEquals(29L, (Long) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "firstMatchingSchemeId", 11L));
    }

    @Test
    void incomingSchemeFindsComponentRawMaterialBelowRootBom() {
        var child = new com.neuedu.ems.entity.mdm.MdmBomItem();
        child.setChildBomId(6L);
        when(bomItemMapper.selectCount(any())).thenReturn(0L, 1L);
        when(bomItemMapper.selectList(any())).thenReturn(List.of(child));
        assertEquals(1, (Integer) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "materialDepth", 5L, 11L));
    }

    @Test
    void incomingSchemeStopsAtCyclicBomWithoutMatchingMaterial() {
        var child = new com.neuedu.ems.entity.mdm.MdmBomItem();
        child.setChildBomId(5L);
        when(bomItemMapper.selectCount(any())).thenReturn(0L);
        when(bomItemMapper.selectList(any())).thenReturn(List.of(child));
        assertEquals(-1, (Integer) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "materialDepth", 5L, 11L));
        verify(bomItemMapper, org.mockito.Mockito.times(1)).selectCount(any());
    }

    @Test
    void purchaseInboundConfirmIssuesIncomingInspectionForRawMaterialBatch() {
        WmsInboundOrder order = order();
        order.setInboundType("PURCHASE");
        order.setInboundNo("IN-PURCHASE-1");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(batchMapper.selectOne(any())).thenReturn(batch(BusinessStatus.Quality.PASS, "AVAILABLE"));
        when(batchMapper.selectById(7L)).thenReturn(batch(BusinessStatus.Quality.PASS, "AVAILABLE"));
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);
        com.neuedu.ems.entity.mdm.MdmMaterial material = new com.neuedu.ems.entity.mdm.MdmMaterial();
        material.setMaterialId(11L);
        material.setMaterialType("RAW");
        when(materialMapper.selectById(11L)).thenReturn(material);
        when(inspectionOrderMapper.selectCount(any())).thenReturn(0L);
        com.neuedu.ems.entity.qms.QmsInspectionScheme scheme = new com.neuedu.ems.entity.qms.QmsInspectionScheme();
        scheme.setSchemeId(28L);
        scheme.setProductId(5L);
        scheme.setInspectionType("INCOMING");
        when(schemeMapper.selectList(any())).thenReturn(List.of(scheme));
        com.neuedu.ems.entity.mdm.MdmBom bom = new com.neuedu.ems.entity.mdm.MdmBom();
        bom.setBomId(5L);
        when(bomMapper.selectList(any())).thenReturn(List.of(bom));
        when(bomItemMapper.selectCount(any())).thenReturn(1L);
        when(inspectionService.saveInspectionOrder(any())).thenReturn(true);

        assertTrue(service.confirmInboundOrder(1L, 9L, null));

        ArgumentCaptor<com.neuedu.ems.entity.qms.QmsInspectionOrder> issued =
                ArgumentCaptor.forClass(com.neuedu.ems.entity.qms.QmsInspectionOrder.class);
        verify(inspectionService).saveInspectionOrder(issued.capture());
        assertEquals("INCOMING", issued.getValue().getInspectionType());
        assertEquals(7L, issued.getValue().getBatchId());
        assertEquals(28L, issued.getValue().getSchemeId());
        assertEquals(new BigDecimal("12.0000"), issued.getValue().getSampleQuantity());
    }

    @Test
    void purchaseInboundConfirmSkipsIncomingInspectionWhenBatchAlreadyInspected() {
        WmsInboundOrder order = order();
        order.setInboundType("PURCHASE");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(batchMapper.selectOne(any())).thenReturn(batch(BusinessStatus.Quality.PASS, "AVAILABLE"));
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);
        com.neuedu.ems.entity.mdm.MdmMaterial material = new com.neuedu.ems.entity.mdm.MdmMaterial();
        material.setMaterialId(11L);
        material.setMaterialType("AUXILIARY");
        when(materialMapper.selectById(11L)).thenReturn(material);
        when(inspectionOrderMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.confirmInboundOrder(1L, 9L, null));

        verify(inspectionService, never()).saveInspectionOrder(any());
    }

    @Test
    void productionInboundDoesNotIssueIncomingInspection() {
        WmsInboundOrder order = order();
        order.setInboundType("PRODUCTION");
        order.setSourceOrderType("PRODUCT_LOT");
        order.setSourceOrderId(31L);
        WmsMaterialBatch batch = batch(BusinessStatus.Quality.PASS, "AVAILABLE");
        batch.setSourceProductLotId(31L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail()), List.of());
        when(batchMapper.selectOne(any())).thenReturn(batch);
        when(productLotMapper.selectOne(any())).thenReturn(productLot("12.0000", BusinessStatus.Quality.PASS));
        when(productMapper.selectOne(any())).thenReturn(product());
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);
        when(productLotMapper.updateById(any(TraceProductLot.class))).thenReturn(1);

        assertTrue(service.confirmInboundOrder(1L, 9L, null));

        verify(inspectionService, never()).saveInspectionOrder(any());
    }

    private WmsInboundOrder order() {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setInboundId(1L);
        order.setWarehouseId(2L);
        order.setAuditStatus(BusinessStatus.Document.SUBMITTED);
        order.setVersion(3);
        return order;
    }

    @Test
    void productionInboundPairsItsReceiptWithTheExistingLineSideTransfer() {
        stubProductionReceipt();
        WmsStockMovement source = new WmsStockMovement();
        source.setMaterialId(11L);
        source.setBatchId(7L);
        source.setQuantity(new BigDecimal("12.0000"));
        when(movementMapper.selectOne(any())).thenReturn(source);
        assertTrue(service.confirmInboundOrder(1L, 9L, null));
        ArgumentCaptor<WmsStockMovement> receipt = ArgumentCaptor.forClass(WmsStockMovement.class);
        verify(movementMapper).insert(receipt.capture());
        assertEquals("LI-1-4", receipt.getValue().getPairNo());
        assertEquals("IN", receipt.getValue().getDirection());
        assertEquals(new BigDecimal("12.0000"), receipt.getValue().getQuantity());
    }

    @Test
    void productionReceiptRejectsAMismatchedLineSideQuantity() {
        stubProductionReceipt();
        WmsStockMovement source = new WmsStockMovement();
        source.setMaterialId(11L);
        source.setBatchId(7L);
        source.setQuantity(BigDecimal.ONE);
        when(movementMapper.selectOne(any())).thenReturn(source);
        assertThrows(BusinessException.class, () -> service.confirmInboundOrder(1L, 9L, null));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    private void stubProductionReceipt() {
        WmsInboundOrder order = order();
        order.setInboundType("PRODUCTION");
        order.setSourceOrderType("PRODUCT_LOT");
        order.setSourceOrderId(31L);
        WmsMaterialBatch batch = batch(BusinessStatus.Quality.PASS, "AVAILABLE");
        batch.setSourceProductLotId(31L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail()), List.of());
        when(batchMapper.selectOne(any())).thenReturn(batch);
        when(productLotMapper.selectOne(any())).thenReturn(productLot("12.0000", BusinessStatus.Quality.PASS));
        when(productMapper.selectOne(any())).thenReturn(product());
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);
        when(productLotMapper.updateById(any(TraceProductLot.class))).thenReturn(1);
    }

    private WmsInboundDetail detail() {
        WmsInboundDetail detail = new WmsInboundDetail();
        detail.setDetailId(4L);
        detail.setInboundId(1L);
        detail.setMaterialId(11L);
        detail.setBatchId(7L);
        detail.setLocationId(8L);
        detail.setInboundQuantity(new BigDecimal("12.0000"));
        return detail;
    }

    private WmsMaterialBatch batch(String qualityStatus, String status) {
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchId(7L);
        batch.setMaterialId(11L);
        batch.setQualityStatus(qualityStatus);
        batch.setStatus(status);
        batch.setVersion(1);
        return batch;
    }

    private TraceProductLot productLot(String qualifiedQuantity, String status) {
        TraceProductLot lot = new TraceProductLot();
        lot.setProductLotId(31L);
        lot.setLotNo("PL-3-1");
        lot.setProductId(8L);
        lot.setQualifiedQuantity(new BigDecimal(qualifiedQuantity));
        lot.setStatus(status);
        lot.setVersion(2);
        return lot;
    }

    private MdmProduct product() {
        MdmProduct product = new MdmProduct();
        product.setProductId(8L);
        product.setMaterialId(11L);
        product.setStatus(BusinessStatus.Common.ENABLE);
        return product;
    }
}
