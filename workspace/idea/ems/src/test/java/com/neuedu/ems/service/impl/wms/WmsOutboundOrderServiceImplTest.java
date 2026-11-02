package com.neuedu.ems.service.impl.wms;

import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsOutboundDetail;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsLocationMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.mfg.MfgDispatchOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsOutboundOrderServiceImplTest {
    @Test
    void transferPreviewResolvesParentWithoutUsingWriteLocks() {
        var stock=mock(com.neuedu.ems.service.mfg.StockComponentSupplyService.class);
        ReflectionTestUtils.setField(service,"stockComponents",stock);
        var order=new WmsOutboundOrder();order.setWorkOrderId(11L);
        var child=new MfgWorkOrder();child.setWorkOrderId(11L);child.setParentWorkOrderId(10L);
        var parent=new MfgWorkOrder();parent.setWorkOrderId(10L);
        var detail=new WmsOutboundDetail();detail.setMaterialId(19L);
        when(workOrderMapper.selectById(11L)).thenReturn(child);
        when(workOrderMapper.selectById(10L)).thenReturn(parent);
        when(stock.requirementOwner(11L,19L)).thenReturn(10L);
        MfgWorkOrder owner=ReflectionTestUtils.invokeMethod(service,"consumptionOwner",order,List.of(detail),false);
        assertEquals(parent,owner);
        verify(workOrderMapper,never()).selectOne(any());
        verify(inventoryMapper,never()).updateById(any(WmsInventory.class));
    }
    @Test
    void genericConfirmationCannotBypassSalesInspection() {
        var order = new WmsOutboundOrder(); order.setOutboundType("SALE"); order.setDeletedFlag(0);
        when(orderMapper.selectOne(any())).thenReturn(order);
        var error = assertThrows(BusinessException.class, () -> service.confirmOutboundOrder(1L, 1L, null));
        assertTrue(error.getMessage().contains("出货检验"));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    private WmsOutboundOrderServiceImpl service;

    @Test
    void stockComponentCannotUseOrdinaryConfirmationOrCloseProductionBeforeTransfer() {
        var stock = mock(com.neuedu.ems.service.mfg.StockComponentSupplyService.class);
        ReflectionTestUtils.setField(service, "stockComponents", stock);
        var issue = order();
        issue.setOutboundType("MATERIAL_ISSUE");
        issue.setWorkOrderId(3L);
        var line = detail(4L, "1.0000");
        line.setInspectionId(90L);
        when(orderMapper.selectOne(any())).thenReturn(issue);
        when(workOrderMapper.selectById(3L)).thenReturn(workOrder("RELEASED"));
        when(detailMapper.selectList(any())).thenReturn(List.of(line));

        var error = assertThrows(BusinessException.class, () -> service.confirmOutboundOrder(1L, 9L, null));
        assertTrue(error.getMessage().contains("转线边"));
        verify(stock, never()).requirePassedAndReplace(any(), any(), any());
        verify(orderMapper, never()).updateById(any(WmsOutboundOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(materialMapper, never()).updateById(any(MfgWorkOrderMaterial.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }
    private WmsOutboundOrderMapper orderMapper;

    @Test
    void stockComponentWithoutInspectionStillCannotUseOrdinaryConfirmation() {
        var stock = mock(com.neuedu.ems.service.mfg.StockComponentSupplyService.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(Integer.class),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        ReflectionTestUtils.setField(stock, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "stockComponents", stock);
        var issue = order();
        issue.setOutboundType("MATERIAL_ISSUE");
        issue.setWorkOrderId(3L);
        when(orderMapper.selectOne(any())).thenReturn(issue);
        when(workOrderMapper.selectById(3L)).thenReturn(workOrder("RELEASED"));
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "1.0000")));

        var error = assertThrows(BusinessException.class, () -> service.confirmOutboundOrder(1L, 9L, null));
        assertTrue(error.getMessage().contains("尚未关联领用检验"));
        verify(stock, never()).requirePassedAndReplace(any(), any(), any());
        verify(orderMapper, never()).updateById(any(WmsOutboundOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(materialMapper, never()).updateById(any(MfgWorkOrderMaterial.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }
    private WmsOutboundDetailMapper detailMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsMaterialBatchMapper batchMapper;
    private WmsStockMovementMapper movementMapper;
    private MfgWorkOrderMaterialMapper materialMapper;
    private MfgWorkOrderMapper workOrderMapper;
    private WmsWarehouseMapper warehouseMapper;
    private MfgDispatchOrderService dispatchOrderService;
    private WmsResponsibilityNotificationService notificationService;
    private com.neuedu.ems.service.mfg.CommandRequestService commandRequests;
    private com.neuedu.ems.service.mfg.SupplyAllocationService supplyAllocations;

    @BeforeEach
    void setUp() {
        service = new WmsOutboundOrderServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        ReflectionTestUtils.setField(service, "preparationGate", mock(com.neuedu.ems.service.mdm.ManufacturingPreparationGate.class));
        ReflectionTestUtils.setField(service, "executionQuantities", mock(com.neuedu.ems.service.mdm.MaterialExecutionQuantityService.class));
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        batchMapper = mock(WmsMaterialBatchMapper.class);
        movementMapper = mock(WmsStockMovementMapper.class);
        materialMapper = mock(MfgWorkOrderMaterialMapper.class);
        workOrderMapper = mock(MfgWorkOrderMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        dispatchOrderService = mock(MfgDispatchOrderService.class);
        SysStatusChangeLogMapper statusLogMapper = mock(SysStatusChangeLogMapper.class);
        SysOperationLogMapper operationLogMapper = mock(SysOperationLogMapper.class);
        notificationService = mock(WmsResponsibilityNotificationService.class);

        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(service, "wmsOutboundDetailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "wmsInventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(service, "wmsMaterialBatchMapper", batchMapper);
        ReflectionTestUtils.setField(service, "wmsStockMovementMapper", movementMapper);
        ReflectionTestUtils.setField(service, "workOrderMaterialMapper", materialMapper);
        ReflectionTestUtils.setField(service, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "wmsWarehouseMapper", warehouseMapper);
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        when(locationMapper.selectById(8L)).thenReturn(location());
        ReflectionTestUtils.setField(service, "locationMapper", locationMapper);
        ReflectionTestUtils.setField(service, "mfgDispatchOrderService", dispatchOrderService);
        ReflectionTestUtils.setField(service, "sysStatusChangeLogMapper", statusLogMapper);
        ReflectionTestUtils.setField(service, "sysOperationLogMapper", operationLogMapper);
        ReflectionTestUtils.setField(service, "responsibilityNotificationService", notificationService);
        // P2B：领料明细优先取已预留库存，移库时先消耗预留；默认无预留，回落到公共可用量。
        supplyAllocations = mock(com.neuedu.ems.service.mfg.SupplyAllocationService.class);
        when(supplyAllocations.reservedAllocationsForWorkOrder(any())).thenReturn(java.util.List.of());
        when(supplyAllocations.consumeReservation(any(), any(), any(), any(), any(), any()))
                .thenReturn(java.math.BigDecimal.ZERO.setScale(4));
        ReflectionTestUtils.setField(service, "supplyAllocations", supplyAllocations);
        // P2D：领料单生成与移库确认支持请求键幂等；默认不登记（返回 null 表示未重放）。
        commandRequests = mock(com.neuedu.ems.service.mfg.CommandRequestService.class);
        when(commandRequests.digest(any())).thenReturn("digest");
        when(commandRequests.replayed(any(), any(), any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "commandRequests", commandRequests);

        when(orderMapper.selectOne(any())).thenReturn(order());
        when(batchMapper.selectOne(any())).thenReturn(batch(BusinessStatus.Quality.PASS, "AVAILABLE"));
        when(orderMapper.updateById(any(WmsOutboundOrder.class))).thenReturn(1);
        when(inventoryMapper.updateById(any(WmsInventory.class))).thenReturn(1);
        when(movementMapper.insert(any(WmsStockMovement.class))).thenReturn(1);
        when(statusLogMapper.insert(any(SysStatusChangeLog.class))).thenReturn(1);
        when(operationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);
    }

    @Test
    void createForcesSubmittedStatusAndClearsClientControlledFields() {
        WmsOutboundOrder request = order();
        request.setAuditStatus(BusinessStatus.Document.APPROVED);
        request.setOutboundTime(LocalDateTime.now());
        request.setDeletedFlag(1);
        when(orderMapper.insert(any(WmsOutboundOrder.class))).thenReturn(1);

        assertTrue(service.saveOutboundOrder(request));

        ArgumentCaptor<WmsOutboundOrder> saved = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).insert(saved.capture());
        assertNull(saved.getValue().getOutboundId());
        assertEquals(BusinessStatus.Document.SUBMITTED, saved.getValue().getAuditStatus());
        assertNull(saved.getValue().getOutboundTime());
        assertEquals(0, saved.getValue().getDeletedFlag());
        verify(notificationService).publishOutboundPending(saved.getValue(), saved.getValue().getCreatedBy());
    }

    @Test
    void fullCreateRequiresAtLeastOneDetail() {
        assertThrows(BusinessException.class, () -> service.saveOutboundOrderFull(order(), List.of()));

        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
        verify(detailMapper, never()).insert(any(WmsOutboundDetail.class));
    }

    @Test
    void withdrawSubmittedOutboundClosesWithoutInventoryMovement() {
        service.withdrawOutboundOrder(1L, 9L, null);

        ArgumentCaptor<WmsOutboundOrder> update = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Document.CLOSED, update.getValue().getAuditStatus());
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
        verify(notificationService).cancelOutboundPending(1L, 9L);
    }

    @Test
    void confirmOutboundAggregatesSameInventoryAndWritesOneDeductionWithDetailMovements() {
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "6.0000"), detail(5L, "5.0000")));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("12.0000"));

        assertTrue(service.confirmOutboundOrder(1L, 9L, "确认出库"));

        ArgumentCaptor<WmsInventory> inventoryUpdate = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).updateById(inventoryUpdate.capture());
        assertEquals(new BigDecimal("1.0000"), inventoryUpdate.getValue().getAvailableQuantity());

        ArgumentCaptor<WmsStockMovement> movements = ArgumentCaptor.forClass(WmsStockMovement.class);
        verify(movementMapper, times(2)).insert(movements.capture());
        assertEquals(List.of("WMS-OUT-1-4", "WMS-OUT-1-5"),
                movements.getAllValues().stream().map(WmsStockMovement::getMovementNo).toList());
        verify(inventoryMapper).selectOne(any());
    }

    @Test
    void confirmMaterialIssueUpdatesWorkOrderReadinessInSameTransaction() {
        WmsOutboundOrder materialIssue = order();
        materialIssue.setOutboundType("MATERIAL_ISSUE");
        materialIssue.setWorkOrderId(3L);
        when(workOrderMapper.selectById(3L)).thenReturn(workOrder("RELEASED"));
        when(orderMapper.selectOne(any())).thenReturn(materialIssue);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "8.0000")));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("10.0000"));
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
        requirement.setRequirementId(20L);
        requirement.setWorkOrderId(3L);
        requirement.setMaterialId(11L);
        requirement.setRequiredQuantity(new BigDecimal("10.0000"));
        requirement.setIssuedQuantity(new BigDecimal("2.0000"));
        requirement.setVersion(1);
        when(materialMapper.selectList(any())).thenReturn(List.of(requirement));
        when(materialMapper.updateById(any(MfgWorkOrderMaterial.class))).thenReturn(1);

        assertTrue(service.confirmOutboundOrder(1L, 9L, "确认领料"));

        ArgumentCaptor<MfgWorkOrderMaterial> update = ArgumentCaptor.forClass(MfgWorkOrderMaterial.class);
        verify(materialMapper).updateById(update.capture());
        assertEquals(new BigDecimal("10.0000"), update.getValue().getIssuedQuantity());
        assertEquals(new BigDecimal("0.0000"), update.getValue().getShortageQuantity());
        assertEquals("READY", update.getValue().getStatus());
        verify(dispatchOrderService).refreshMaterialReadiness(3L, 9L);
    }

    @Test
    void confirmMaterialIssueConsumesReservationInsteadOfRequiringAvailableStock() {
        // 半成品仓整份预留后 available 为 0、locked 为 6，确认出库必须走预留转换而不是判库存不足。
        WmsOutboundOrder materialIssue = order();
        materialIssue.setOutboundType("MATERIAL_ISSUE");
        materialIssue.setWorkOrderId(3L);
        when(workOrderMapper.selectById(3L)).thenReturn(workOrder("RELEASED"));
        when(orderMapper.selectOne(any())).thenReturn(materialIssue);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "6.0000")));
        WmsInventory fullyReserved = inventory("0.0000");
        fullyReserved.setLockedQuantity(new BigDecimal("6.0000"));
        when(inventoryMapper.selectOne(any())).thenReturn(fullyReserved);
        stubMaterialIssueRequirement();
        var reservation = new com.neuedu.ems.entity.mfg.MfgSupplyAllocation();
        reservation.setMaterialId(11L);
        reservation.setInventoryId(6L);
        reservation.setReservedQuantity(new BigDecimal("6.0000"));
        when(supplyAllocations.reservedAllocationsForWorkOrder(3L)).thenReturn(List.of(reservation));
        when(supplyAllocations.consumeReservation(any(), any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("6.0000"));

        assertTrue(service.confirmOutboundOrder(1L, 9L, "确认领料"));

        ArgumentCaptor<WmsInventory> inventoryUpdate = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).updateById(inventoryUpdate.capture());
        assertEquals(new BigDecimal("0.0000"), inventoryUpdate.getValue().getAvailableQuantity());
        assertEquals(new BigDecimal("0.0000"), inventoryUpdate.getValue().getLockedQuantity());
        verify(supplyAllocations).consumeReservation(eq(3L), eq(11L), eq(6L),
                eq(new BigDecimal("6.0000")), eq(9L), any());
    }

    @Test
    void confirmMaterialIssueSplitsReservedAndUnreservedParts() {
        // 预留 4、可用 6，出库 6：4 走预留（只扣 locked），2 扣 available。
        WmsOutboundOrder materialIssue = order();
        materialIssue.setOutboundType("MATERIAL_ISSUE");
        materialIssue.setWorkOrderId(3L);
        when(workOrderMapper.selectById(3L)).thenReturn(workOrder("RELEASED"));
        when(orderMapper.selectOne(any())).thenReturn(materialIssue);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "6.0000")));
        WmsInventory mixed = inventory("6.0000");
        mixed.setLockedQuantity(new BigDecimal("4.0000"));
        when(inventoryMapper.selectOne(any())).thenReturn(mixed);
        stubMaterialIssueRequirement();
        var reservation = new com.neuedu.ems.entity.mfg.MfgSupplyAllocation();
        reservation.setMaterialId(11L);
        reservation.setInventoryId(6L);
        reservation.setReservedQuantity(new BigDecimal("4.0000"));
        when(supplyAllocations.reservedAllocationsForWorkOrder(3L)).thenReturn(List.of(reservation));
        when(supplyAllocations.consumeReservation(any(), any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("4.0000"));

        assertTrue(service.confirmOutboundOrder(1L, 9L, "确认领料"));

        ArgumentCaptor<WmsInventory> inventoryUpdate = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).updateById(inventoryUpdate.capture());
        assertEquals(new BigDecimal("4.0000"), inventoryUpdate.getValue().getAvailableQuantity());
        assertEquals(new BigDecimal("0.0000"), inventoryUpdate.getValue().getLockedQuantity());
    }

    @Test
    void materialIssuePreviewRejectsReservationsInAnotherWarehouse() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectOne(any())).thenReturn(warehouse());
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
        requirement.setWorkOrderId(3L);
        requirement.setMaterialId(11L);
        requirement.setRequiredQuantity(new BigDecimal("8.0000"));
        requirement.setIssuedQuantity(BigDecimal.ZERO);
        when(materialMapper.selectList(any())).thenReturn(List.of(requirement));
        var reservation = new com.neuedu.ems.entity.mfg.MfgSupplyAllocation();
        reservation.setMaterialId(11L);
        reservation.setInventoryId(99L);
        reservation.setReservedQuantity(new BigDecimal("8.0000"));
        var allocations = (com.neuedu.ems.service.mfg.SupplyAllocationService)
                ReflectionTestUtils.getField(service, "supplyAllocations");
        when(allocations.reservedAllocationsForWorkOrder(3L)).thenReturn(List.of(reservation));
        WmsInventory otherWarehouse = inventory("0.0000");
        otherWarehouse.setWarehouseId(999L);
        when(inventoryMapper.selectById(99L)).thenReturn(otherWarehouse);
        when(inventoryMapper.selectList(any())).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> service.validateMaterialIssueAvailability(3L, 2L));
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
    }

    @Test
    void materialIssuePreviewUsesBatchReleaseRulesWithoutWriting() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectOne(any())).thenReturn(warehouse());
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
        requirement.setRequirementId(20L);
        requirement.setWorkOrderId(3L);
        requirement.setMaterialId(11L);
        requirement.setRequiredQuantity(new BigDecimal("8.0000"));
        requirement.setIssuedQuantity(BigDecimal.ZERO);
        when(materialMapper.selectList(any())).thenReturn(List.of(requirement));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory("8.0000")));

        service.validateMaterialIssueAvailability(3L, 2L);
        when(batchMapper.selectOne(any())).thenReturn(batch(BusinessStatus.Quality.FAIL, "AVAILABLE"));
        assertThrows(BusinessException.class, () -> service.validateMaterialIssueAvailability(3L, 2L));
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
        verify(detailMapper, never()).insert(any(WmsOutboundDetail.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void generateMaterialIssueDerivesOutstandingQuantityAndFifoInventory() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectOne(any())).thenReturn(warehouse());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
        requirement.setRequirementId(20L);
        requirement.setWorkOrderId(3L);
        requirement.setMaterialId(11L);
        requirement.setRequiredQuantity(new BigDecimal("10.0000"));
        requirement.setIssuedQuantity(new BigDecimal("2.0000"));
        when(materialMapper.selectList(any())).thenReturn(List.of(requirement));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory("8.0000")));
        when(orderMapper.insert(any(WmsOutboundOrder.class))).thenAnswer(invocation -> {
            invocation.<WmsOutboundOrder>getArgument(0).setOutboundId(40L);
            return 1;
        });
        when(detailMapper.insert(any(WmsOutboundDetail.class))).thenReturn(1);

        WmsOutboundOrder generated = service.generateMaterialIssue(3L, 2L, 9L, null);

        assertEquals(40L, generated.getOutboundId());
        assertEquals("MATERIAL_ISSUE", generated.getOutboundType());
        assertEquals(3L, generated.getWorkOrderId());
        assertEquals(2L, generated.getWarehouseId());
        ArgumentCaptor<WmsOutboundDetail> detail = ArgumentCaptor.forClass(WmsOutboundDetail.class);
        verify(detailMapper).insert(detail.capture());
        assertEquals(11L, detail.getValue().getMaterialId());
        assertEquals(7L, detail.getValue().getBatchId());
        assertEquals(8L, detail.getValue().getLocationId());
        assertEquals(new BigDecimal("8.0000"), detail.getValue().getOutboundQuantity());
    }

    @Test
    void generateMaterialIssueRejectsNonRawWarehouse() {
        WmsWarehouse defectiveWarehouse = warehouse();
        defectiveWarehouse.setWarehouseType("DEFECTIVE");
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectOne(any())).thenReturn(defectiveWarehouse);

        assertThrows(BusinessException.class, () -> service.generateMaterialIssue(3L, 2L, 9L, null));

        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
        verify(inventoryMapper, never()).selectList(any());
    }

    @Test
    void generateMaterialIssueRejectsPartialMaterialAvailability() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectOne(any())).thenReturn(warehouse());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
        requirement.setRequirementId(20L);
        requirement.setWorkOrderId(3L);
        requirement.setMaterialId(11L);
        requirement.setRequiredQuantity(new BigDecimal("10.0000"));
        requirement.setIssuedQuantity(BigDecimal.ZERO);
        when(materialMapper.selectList(any())).thenReturn(List.of(requirement));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory("6.0000")));

        assertThrows(BusinessException.class, () -> service.generateMaterialIssue(3L, 2L, 9L, null));

        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
        verify(detailMapper, never()).insert(any(WmsOutboundDetail.class));
    }

    @Test
    void generateMaterialIssueReusesPendingOrderWithoutAllocatingAgain() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectOne(any())).thenReturn(warehouse());
        WmsOutboundOrder pending = order();
        pending.setOutboundId(44L);
        pending.setOutboundType("MATERIAL_ISSUE");
        pending.setWorkOrderId(3L);
        when(orderMapper.selectList(any())).thenReturn(List.of(pending));

        WmsOutboundOrder generated = service.generateMaterialIssue(3L, 2L, 9L, null);

        assertEquals(44L, generated.getOutboundId());
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
        verify(inventoryMapper, never()).selectList(any());
        verify(detailMapper, never()).insert(any(WmsOutboundDetail.class));
    }

    @Test
    void generateMaterialIssueReplaysOriginalOrderWhenSameRequestKeyAndDigest() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(commandRequests.replayed(any(), any(), any())).thenReturn(40L);
        WmsOutboundOrder replayed = order();
        replayed.setOutboundId(40L);
        replayed.setOutboundType("MATERIAL_ISSUE");
        replayed.setWorkOrderId(3L);
        when(orderMapper.selectById(40L)).thenReturn(replayed);

        WmsOutboundOrder generated = service.generateMaterialIssue(3L, 2L, 9L, null, "issue-key-1");

        assertEquals(40L, generated.getOutboundId());
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
        verify(detailMapper, never()).insert(any(WmsOutboundDetail.class));
        verify(inventoryMapper, never()).selectList(any());
    }

    @Test
    void generateMaterialIssueRejectsSameRequestKeyWithChangedParameters() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(commandRequests.replayed(any(), any(), any()))
                .thenThrow(new BusinessException(com.neuedu.ems.common.ResultCode.BUSINESS_ERROR.getCode(),
                        "同一命令请求键的请求参数已变化，不能复用原结果"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.generateMaterialIssue(3L, 2L, 9L, null, "issue-key-1"));

        assertTrue(error.getMessage().contains("不能复用原结果"));
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
    }

    @Test
    void generateMaterialIssueRecordsCommandRequestWhenKeyProvided() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectOne(any())).thenReturn(warehouse());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
        requirement.setRequirementId(20L);
        requirement.setWorkOrderId(3L);
        requirement.setMaterialId(11L);
        requirement.setRequiredQuantity(new BigDecimal("10.0000"));
        requirement.setIssuedQuantity(new BigDecimal("2.0000"));
        when(materialMapper.selectList(any())).thenReturn(List.of(requirement));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory("8.0000")));
        when(orderMapper.insert(any(WmsOutboundOrder.class))).thenAnswer(invocation -> {
            invocation.<WmsOutboundOrder>getArgument(0).setOutboundId(40L);
            return 1;
        });
        when(detailMapper.insert(any(WmsOutboundDetail.class))).thenReturn(1);

        service.generateMaterialIssue(3L, 2L, 9L, "首单", "issue-key-1");

        verify(commandRequests).record(eq(com.neuedu.ems.entity.mfg.MfgCommandRequest.COMMAND_MATERIAL_ISSUE_GENERATE),
                eq("issue-key-1"), eq(com.neuedu.ems.entity.mfg.MfgCommandRequest.AGGREGATE_WORK_ORDER),
                eq(3L), eq("digest"), eq(40L), eq(9L), eq("首单"), any());
    }

    @Test
    void generateMaterialIssueSkipsCommandRequestWhenKeyAbsent() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectOne(any())).thenReturn(warehouse());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
        requirement.setRequirementId(20L);
        requirement.setWorkOrderId(3L);
        requirement.setMaterialId(11L);
        requirement.setRequiredQuantity(new BigDecimal("10.0000"));
        requirement.setIssuedQuantity(new BigDecimal("2.0000"));
        when(materialMapper.selectList(any())).thenReturn(List.of(requirement));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory("8.0000")));
        when(orderMapper.insert(any(WmsOutboundOrder.class))).thenAnswer(invocation -> {
            invocation.<WmsOutboundOrder>getArgument(0).setOutboundId(40L);
            return 1;
        });
        when(detailMapper.insert(any(WmsOutboundDetail.class))).thenReturn(1);

        service.generateMaterialIssue(3L, 2L, 9L, null);

        verify(commandRequests, never()).replayed(any(), any(), any());
        verify(commandRequests, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void combinedQuantityAboveAvailableInventoryIsRejectedBeforeMutation() {
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "6.0000"), detail(5L, "5.0000")));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("10.0000"));

        assertThrows(BusinessException.class, () -> service.confirmOutboundOrder(1L, 9L, null));

        verify(orderMapper, never()).updateById(any(WmsOutboundOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void outboundOrderConflictStopsBeforeInventoryMutation() {
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "6.0000")));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("10.0000"));
        when(orderMapper.updateById(any(WmsOutboundOrder.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.confirmOutboundOrder(1L, 9L, null));

        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void inventoryConflictRaisesExceptionForWholeTransactionRollback() {
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "6.0000")));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("10.0000"));
        when(inventoryMapper.updateById(any(WmsInventory.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.confirmOutboundOrder(1L, 9L, null));

        verify(orderMapper).updateById(any(WmsOutboundOrder.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void frozenBatchIsRejectedBeforeAnyMutation() {
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(4L, "6.0000")));
        when(batchMapper.selectOne(any())).thenReturn(batch("WAIT_INSPECTION", "FROZEN"));

        assertThrows(BusinessException.class, () -> service.confirmOutboundOrder(1L, 9L, null));

        verify(inventoryMapper, never()).selectOne(any());
        verify(orderMapper, never()).updateById(any(WmsOutboundOrder.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void deleteOrderConflictDoesNotSoftDeleteDetails() {
        when(orderMapper.updateById(any(WmsOutboundOrder.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.removeOutboundOrderById(1L));

        verify(detailMapper, never()).selectList(any());
        verify(detailMapper, never()).updateById(any(WmsOutboundDetail.class));
    }

    /** 生产领料确认必须有有效用料需求，这里给一条与明细物料一致的缺口需求。 */
    private void stubMaterialIssueRequirement() {
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
        requirement.setRequirementId(20L);
        requirement.setWorkOrderId(3L);
        requirement.setMaterialId(11L);
        requirement.setRequiredQuantity(new BigDecimal("10.0000"));
        requirement.setIssuedQuantity(new BigDecimal("2.0000"));
        requirement.setVersion(1);
        when(materialMapper.selectList(any())).thenReturn(List.of(requirement));
        when(materialMapper.updateById(any(MfgWorkOrderMaterial.class))).thenReturn(1);
    }

    private WmsOutboundOrder order() {
        WmsOutboundOrder order = new WmsOutboundOrder();
        order.setOutboundId(1L);
        order.setWarehouseId(2L);
        order.setAuditStatus(BusinessStatus.Document.SUBMITTED);
        order.setVersion(3);
        return order;
    }

    private WmsOutboundDetail detail(Long detailId, String quantity) {
        WmsOutboundDetail detail = new WmsOutboundDetail();
        detail.setDetailId(detailId);
        detail.setOutboundId(1L);
        detail.setMaterialId(11L);
        detail.setBatchId(7L);
        detail.setLocationId(8L);
        detail.setOutboundQuantity(new BigDecimal(quantity));
        return detail;
    }

    private WmsInventory inventory(String availableQuantity) {
        WmsInventory inventory = new WmsInventory();
        inventory.setInventoryId(6L);
        inventory.setWarehouseId(2L);
        inventory.setLocationId(8L);
        inventory.setMaterialId(11L);
        inventory.setBatchId(7L);
        inventory.setAvailableQuantity(new BigDecimal(availableQuantity));
        inventory.setVersion(2);
        return inventory;
    }

    private WmsMaterialBatch batch(String qualityStatus, String status) {
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchId(7L);
        batch.setMaterialId(11L);
        batch.setQualityStatus(qualityStatus);
        batch.setStatus(status);
        return batch;
    }

    private MfgWorkOrder workOrder(String status) {
        MfgWorkOrder workOrder = new MfgWorkOrder();
        workOrder.setWorkOrderId(3L);
        workOrder.setStatus(status);
        return workOrder;
    }

    private WmsWarehouse warehouse() {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setWarehouseId(2L);
        warehouse.setWarehouseType("RAW");
        warehouse.setStatus(BusinessStatus.Common.ENABLE);
        return warehouse;
    }

    private WmsLocation location() {
        WmsLocation location = new WmsLocation();
        location.setLocationId(8L);
        location.setWarehouseId(2L);
        location.setLocationType("NORMAL");
        location.setStatus(BusinessStatus.Common.ENABLE);
        return location;
    }
}
