package com.neuedu.ems.service.impl.wms;

import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.mfg.MfgReportMaterial;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mdm.MdmUnit;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsOutboundDetail;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsReturnDetail;
import com.neuedu.ems.entity.wms.WmsReturnOrder;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmUnitMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.mfg.MfgReportMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsLocationMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsReturnDetailMapper;
import com.neuedu.ems.mapper.wms.WmsReturnOrderMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.vo.wms.WmsReturnableDetailView;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsReturnOrderServiceImplTest {

    private WmsReturnOrderServiceImpl service;
    private WmsReturnOrderMapper orderMapper;
    private WmsReturnDetailMapper detailMapper;
    private MfgReportMaterialMapper reportMaterialMapper;
    private MfgWorkOrderMapper workOrderMapper;
    private WmsOutboundDetailMapper outboundDetailMapper;
    private WmsOutboundOrderMapper outboundOrderMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsStockMovementMapper movementMapper;
    private WmsLocationMapper locationMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsMaterialBatchMapper materialBatchMapper;
    private MdmMaterialMapper materialMapper;
    private MdmUnitMapper unitMapper;
    private WmsResponsibilityNotificationService notificationService;
    private com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper requirementMapper;
    private com.neuedu.ems.service.mfg.MfgDispatchOrderService dispatchOrderService;

    /** 造一个只含单个对象的 ObjectProvider，替掉容器里的真实代理。 */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T bean) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return java.util.List.of(bean).iterator();
            }
        };
    }

    @BeforeEach
    void setUp() {
        service = new WmsReturnOrderServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        ReflectionTestUtils.setField(service, "executionQuantities", mock(com.neuedu.ems.service.mdm.MaterialExecutionQuantityService.class));
        orderMapper = mock(WmsReturnOrderMapper.class);
        detailMapper = mock(WmsReturnDetailMapper.class);
        reportMaterialMapper = mock(MfgReportMaterialMapper.class);
        workOrderMapper = mock(MfgWorkOrderMapper.class);
        outboundDetailMapper = mock(WmsOutboundDetailMapper.class);
        outboundOrderMapper = mock(WmsOutboundOrderMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        movementMapper = mock(WmsStockMovementMapper.class);
        notificationService = mock(WmsResponsibilityNotificationService.class);
        locationMapper = mock(WmsLocationMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        materialBatchMapper = mock(WmsMaterialBatchMapper.class);
        materialMapper = mock(MdmMaterialMapper.class);
        unitMapper = mock(MdmUnitMapper.class);
        SysStatusChangeLogMapper statusLogMapper = mock(SysStatusChangeLogMapper.class);
        SysOperationLogMapper operationLogMapper = mock(SysOperationLogMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(service, "wmsReturnDetailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "mfgReportMaterialMapper", reportMaterialMapper);
        ReflectionTestUtils.setField(service, "mfgWorkOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "wmsOutboundDetailMapper", outboundDetailMapper);
        ReflectionTestUtils.setField(service, "wmsOutboundOrderMapper", outboundOrderMapper);
        ReflectionTestUtils.setField(service, "wmsInventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(service, "wmsStockMovementMapper", movementMapper);
        ReflectionTestUtils.setField(service, "sysStatusChangeLogMapper", statusLogMapper);
        ReflectionTestUtils.setField(service, "sysOperationLogMapper", operationLogMapper);
        ReflectionTestUtils.setField(service, "responsibilityNotificationService", notificationService);
        ReflectionTestUtils.setField(service, "wmsLocationMapper", locationMapper);
        ReflectionTestUtils.setField(service, "wmsWarehouseMapper", warehouseMapper);
        ReflectionTestUtils.setField(service, "wmsMaterialBatchMapper", materialBatchMapper);
        ReflectionTestUtils.setField(service, "mdmMaterialMapper", materialMapper);
        ReflectionTestUtils.setField(service, "mdmUnitMapper", unitMapper);
        requirementMapper = mock(com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper.class);
        ReflectionTestUtils.setField(service, "mfgWorkOrderMaterialMapper", requirementMapper);
        dispatchOrderService = mock(com.neuedu.ems.service.mfg.MfgDispatchOrderService.class);
        ReflectionTestUtils.setField(service, "mfgDispatchOrders", provider(dispatchOrderService));
        when(requirementMapper.selectList(any())).thenReturn(java.util.List.of());
        when(statusLogMapper.insert(any(SysStatusChangeLog.class))).thenReturn(1);
        when(operationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);
        MfgWorkOrder workOrder = new MfgWorkOrder();
        workOrder.setWorkOrderId(3L);
        workOrder.setStatus(BusinessStatus.Production.RUNNING);
        workOrder.setDeletedFlag(0);
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder);
    }

    @Test
    void saveReturnDerivesImmutableSourceFieldsAndJwtApplicant() {
        stubValidSource(BigDecimal.ZERO);
        when(orderMapper.insert(any(WmsReturnOrder.class))).thenAnswer(invocation -> {
            invocation.<WmsReturnOrder>getArgument(0).setReturnId(20L);
            return 1;
        });
        when(detailMapper.insert(any(WmsReturnDetail.class))).thenReturn(1);
        WmsReturnOrder order = order(null);
        order.setReturnId(null);
        WmsReturnDetail detail = returnDetail("2.0000");
        detail.setMaterialId(null);
        detail.setBatchId(null);
        detail.setLocationId(null);

        Long returnId = service.saveReturnOrderFull(order, List.of(detail), 9L);

        assertEquals(20L, returnId);
        assertEquals(9L, order.getApplicantId());
        assertEquals(BusinessStatus.Document.SUBMITTED, order.getAuditStatus());
        assertTrue(order.getReturnNo().startsWith("T-RT-20261027-"));
        assertEquals(11L, detail.getMaterialId());
        assertEquals(7L, detail.getBatchId());
        assertEquals(8L, detail.getLocationId());
        verify(notificationService).publishReturnPending(order, 9L);
    }

    @Test
    void forgedSourceFieldsAreRejectedBeforeInsert() {
        stubValidSource(BigDecimal.ZERO);
        WmsReturnDetail detail = returnDetail("2.0000");
        detail.setMaterialId(99L);

        assertThrows(BusinessException.class,
                () -> service.saveReturnOrderFull(order(null), List.of(detail), 9L));

        verify(orderMapper, never()).insert(any(WmsReturnOrder.class));
        verify(detailMapper, never()).insert(any(WmsReturnDetail.class));
    }

    @Test
    void confirmReturnUpdatesOrderBeforeInventoryAndWritesDeterministicMovement() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        stubValidSource(BigDecimal.ZERO);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("5.0000"));
        when(orderMapper.updateById(any(WmsReturnOrder.class))).thenReturn(1);
        when(inventoryMapper.updateById(any(WmsInventory.class))).thenReturn(1);
        when(movementMapper.insert(any(WmsStockMovement.class))).thenReturn(1);

        assertTrue(service.confirmReturnOrder(1L, 9L, "剩余良品退料"));

        ArgumentCaptor<WmsInventory> inventoryUpdate = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).updateById(inventoryUpdate.capture());
        assertEquals(new BigDecimal("7.0000"), inventoryUpdate.getValue().getAvailableQuantity());
        ArgumentCaptor<WmsStockMovement> movement = ArgumentCaptor.forClass(WmsStockMovement.class);
        verify(movementMapper).insert(movement.capture());
        assertEquals("WMS-RETURN-1-6", movement.getValue().getMovementNo());
        assertEquals("IN", movement.getValue().getDirection());
    }

    @Test
    void cumulativeReturnAboveIssuedQuantityIsRejectedBeforeMutation() {        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        stubValidSource(new BigDecimal("9.0000"));

        assertThrows(BusinessException.class, () -> service.confirmReturnOrder(1L, 9L, null));

        verify(orderMapper, never()).updateById(any(WmsReturnOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void materialAlreadyConsumedByReportsCannotBeReturned() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        stubValidSource(BigDecimal.ZERO);
        MfgReportMaterial consumed = new MfgReportMaterial();
        consumed.setUsedQuantity(new BigDecimal("9.0000"));
        consumed.setScrapQuantity(BigDecimal.ZERO);
        when(reportMaterialMapper.selectList(any())).thenReturn(List.of(consumed));

        assertThrows(BusinessException.class, () -> service.confirmReturnOrder(1L, 9L, null));

        verify(orderMapper, never()).updateById(any(WmsReturnOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
    }

    @Test
    void returnOrderConflictStopsBeforeInventoryMutation() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        stubValidSource(BigDecimal.ZERO);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("5.0000"));
        when(orderMapper.updateById(any(WmsReturnOrder.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.confirmReturnOrder(1L, 9L, null));

        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void inventoryConflictRaisesExceptionBeforeMovement() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        stubValidSource(BigDecimal.ZERO);
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("5.0000"));
        when(orderMapper.updateById(any(WmsReturnOrder.class))).thenReturn(1);
        when(inventoryMapper.updateById(any(WmsInventory.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.confirmReturnOrder(1L, 9L, null));

        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void lineSideReturnReleasesLockedQuantityAndWritesPairedMovements() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        stubValidSource(BigDecimal.ZERO);
        // 该领料已转线边：存在成对 TRANSFER 流水。
        when(movementMapper.selectCount(any())).thenReturn(1L);
        when(movementMapper.insert(any(WmsStockMovement.class))).thenReturn(1);
        when(orderMapper.updateById(any(WmsReturnOrder.class))).thenReturn(1);
        // 线边占用行：工单 3 在线边仓锁了 5，本次退 2。
        when(inventoryMapper.selectList(any())).thenReturn(List.of(lineSideInventory("5.0000")));
        when(inventoryMapper.updateById(any(WmsInventory.class))).thenReturn(1);
        // 原仓公共余额行已被移库扣空并清理，退料需补建。
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(inventoryMapper.insert(any(WmsInventory.class))).thenReturn(1);
        when(warehouseMapper.selectOne(any())).thenReturn(lineSideWarehouse());
        when(locationMapper.selectOne(any())).thenReturn(lineSideLocation());

        assertTrue(service.confirmReturnOrder(1L, 9L, "线边剩余退回"));

        ArgumentCaptor<WmsInventory> lockedUpdate = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).updateById(lockedUpdate.capture());
        assertEquals(new BigDecimal("3.0000"), lockedUpdate.getValue().getLockedQuantity());
        ArgumentCaptor<WmsInventory> created = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).insert(created.capture());
        assertEquals(new BigDecimal("2.0000"), created.getValue().getAvailableQuantity());
        assertEquals(0L, created.getValue().getWorkOrderId());
        ArgumentCaptor<WmsStockMovement> movements = ArgumentCaptor.forClass(WmsStockMovement.class);
        verify(movementMapper, times(2)).insert(movements.capture());
        List<WmsStockMovement> written = movements.getAllValues();
        assertEquals("LSR-1-6-OUT", written.get(0).getMovementNo());
        assertEquals("OUT", written.get(0).getDirection());
        assertEquals("LSR-1-6", written.get(0).getPairNo());
        assertEquals("LSR-1-6-IN", written.get(1).getMovementNo());
        assertEquals("IN", written.get(1).getDirection());
        assertEquals("RETURN_ORDER", written.get(1).getSourceOrderType());
    }

    @Test
    void lineSideReturnAboveLockedQuantityIsRejectedBeforeMutation() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        stubValidSource(BigDecimal.ZERO);
        when(movementMapper.selectCount(any())).thenReturn(1L);
        when(inventoryMapper.selectList(any())).thenReturn(List.of(lineSideInventory("1.0000")));

        assertThrows(BusinessException.class, () -> service.confirmReturnOrder(1L, 9L, null));

        verify(orderMapper, never()).updateById(any(WmsReturnOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(inventoryMapper, never()).insert(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void repeatedLineSideConfirmIsIdempotentWhenPairedMovementsExist() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.APPROVED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        // 线边退料一条明细写两条成对流水，重放核对应按 2 倍命中。
        when(movementMapper.selectCount(any())).thenReturn(2L);

        assertTrue(service.confirmReturnOrder(1L, 9L, null));

        verify(orderMapper, never()).updateById(any(WmsReturnOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void repeatedConfirmIsIdempotentWhenMovementIsComplete() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.APPROVED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        when(movementMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.confirmReturnOrder(1L, 9L, null));

        verify(orderMapper, never()).updateById(any(WmsReturnOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void repeatedConfirmRejectsMissingMovementsWithoutChangingStock() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.APPROVED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        when(movementMapper.selectCount(any())).thenReturn(0L);

        assertThrows(BusinessException.class, () -> service.confirmReturnOrder(1L, 9L, null));

        verify(orderMapper, never()).updateById(any(WmsReturnOrder.class));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
    }

    @Test
    void withdrawSubmittedReturnClosesWithoutInventoryMovement() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(orderMapper.updateById(any(WmsReturnOrder.class))).thenReturn(1);

        service.withdrawReturnOrder(1L, 9L, null);

        ArgumentCaptor<WmsReturnOrder> update = ArgumentCaptor.forClass(WmsReturnOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Document.CLOSED, update.getValue().getAuditStatus());
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(movementMapper, never()).insert(any(WmsStockMovement.class));
        verify(notificationService).cancelReturnPending(1L, 9L);
    }

    @Test
    void returnableDetailsListIssuedLinesWithNamesAndRemainingQuantity() {
        stubReturnableSource(new BigDecimal("2.0000"), BigDecimal.ZERO, new BigDecimal("8.0000"));

        List<WmsReturnableDetailView> rows = service.listReturnableDetails(3L);

        assertEquals(1, rows.size());
        WmsReturnableDetailView row = rows.get(0);
        assertEquals(4L, row.getOutboundDetailId());
        assertEquals("MI-20261027-001", row.getOutboundNo());
        assertEquals("灯臂铝型材", row.getMaterialName());
        assertEquals("LAMP-M01", row.getMaterialCode());
        assertEquals("根", row.getUnitName());
        assertEquals("B-20261027-01", row.getBatchNo());
        assertEquals("A-01-01", row.getLocationName());
        assertEquals("原材料仓", row.getWarehouseName());
        assertEquals(0, new BigDecimal("10.0000").compareTo(row.getOutboundQuantity()));
        assertEquals(0, new BigDecimal("2.0000").compareTo(row.getReturnedQuantity()));
        assertEquals(0, new BigDecimal("8.0000").compareTo(row.getSourceAvailableQuantity()));
        assertEquals(0, new BigDecimal("8.0000").compareTo(row.getAvailableQuantity()));
        assertEquals(Boolean.FALSE, row.getLineSide());
    }

    @Test
    void returnableDetailsCapQuantityByConsumedAndPreviouslyReturned() {
        // 已领 10，已被报工消耗 3，已退 2，本工单该批次只剩 5 可退。
        stubReturnableSource(new BigDecimal("2.0000"), new BigDecimal("3.0000"), new BigDecimal("5.0000"));

        List<WmsReturnableDetailView> rows = service.listReturnableDetails(3L);

        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("5.0000").compareTo(rows.get(0).getBatchAvailableQuantity()));
        assertEquals(0, new BigDecimal("5.0000").compareTo(rows.get(0).getAvailableQuantity()));
    }

    @Test
    void returnableDetailsShareBatchQuantityAcrossMultipleIssueLines() {
        // 同一物料批次分两张领料单各领 6 与 4，本工单可退 5：按明细顺序先到先得，合计不超过 5。
        WmsOutboundDetail first = sourceDetail();
        first.setDetailId(4L);
        first.setOutboundQuantity(new BigDecimal("6.0000"));
        WmsOutboundDetail second = sourceDetail();
        second.setDetailId(5L);
        second.setOutboundQuantity(new BigDecimal("4.0000"));
        stubReturnableSource(List.of(first, second), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5.0000"));

        List<WmsReturnableDetailView> rows = service.listReturnableDetails(3L);

        assertEquals(2, rows.size());
        assertEquals(0, new BigDecimal("5.0000").compareTo(rows.get(0).getAvailableQuantity()));
        assertEquals(0, BigDecimal.ZERO.compareTo(rows.get(1).getAvailableQuantity()));
    }

    @Test
    void returnableDetailsReturnsEmptyWhenWorkOrderHasNoApprovedIssue() {
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.listReturnableDetails(3L).isEmpty());
        verify(outboundDetailMapper, never()).selectList(any());
    }

    @Test
    void returnableDetailsRequiresWorkOrder() {
        assertThrows(BusinessException.class, () -> service.listReturnableDetails(null));
    }

    @Test
    void returnableDetailsExcludeOwnedQuantityWhenEditingExistingReturn() {
        // 该来源明细共已退 5，其中本单占 2、其他退料单占 3；编辑本单时上限不能把自己那 2 也算成已退。
        stubReturnableSource(new BigDecimal("5.0000"), BigDecimal.ZERO, new BigDecimal("5.0000"));
        when(detailMapper.sumActiveReturnedQuantity(4L, 1L)).thenReturn(new BigDecimal("3.0000"));
        when(detailMapper.sumActiveReturnedQuantityByBatch(3L, 11L, 7L, 1L))
                .thenReturn(new BigDecimal("3.0000"));

        List<WmsReturnableDetailView> rows = service.listReturnableDetails(3L, 1L);

        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("3.0000").compareTo(rows.get(0).getReturnedQuantity()));
        assertEquals(0, new BigDecimal("7.0000").compareTo(rows.get(0).getSourceAvailableQuantity()));
        assertEquals(0, new BigDecimal("7.0000").compareTo(rows.get(0).getAvailableQuantity()));
        // 不排除本单时上限只有 5，说明排除参数确实参与了计算。
        assertEquals(0, new BigDecimal("5.0000")
                .compareTo(service.listReturnableDetails(3L).get(0).getAvailableQuantity()));
    }

    /**
     * 退料确认后必须把领用量减回来，否则工单一直显示已领料，改用库存半成品时会被
     * 「已领料、开工或已有执行记录」挡住；整张领料单退完还要把出库单关闭。
     */
    @Test
    void confirmedReturnRestoresIssuedQuantityAndClosesFullyReturnedIssueOrder() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("10.0000")));
        stubValidSource(BigDecimal.ZERO);
        // 退满 10 后该来源明细已全部退回。
        when(detailMapper.sumActiveReturnedQuantity(4L, null)).thenReturn(new BigDecimal("10.0000"));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("5.0000"));
        when(orderMapper.updateById(any(WmsReturnOrder.class))).thenReturn(1);
        when(inventoryMapper.updateById(any(WmsInventory.class))).thenReturn(1);
        when(movementMapper.insert(any(WmsStockMovement.class))).thenReturn(1);
        when(requirementMapper.selectList(any()))
                .thenReturn(List.of(requirement(new BigDecimal("10.0000"), new BigDecimal("10.0000"))));
        when(requirementMapper.updateById(any(MfgWorkOrderMaterial.class))).thenReturn(1);
        when(outboundOrderMapper.selectById(2L)).thenReturn(sourceOrder());
        when(outboundOrderMapper.updateById(any(WmsOutboundOrder.class))).thenReturn(1);

        assertTrue(service.confirmReturnOrder(1L, 9L, "剩余良品退料"));

        ArgumentCaptor<MfgWorkOrderMaterial> requirementUpdate = ArgumentCaptor.forClass(MfgWorkOrderMaterial.class);
        verify(requirementMapper).updateById(requirementUpdate.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(requirementUpdate.getValue().getIssuedQuantity()));
        assertEquals(0, new BigDecimal("10.0000")
                .compareTo(requirementUpdate.getValue().getShortageQuantity()));
        assertEquals("WAIT_READY", requirementUpdate.getValue().getStatus());
        ArgumentCaptor<WmsOutboundOrder> outboundUpdate = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(outboundOrderMapper).updateById(outboundUpdate.capture());
        assertEquals(BusinessStatus.Document.CLOSED, outboundUpdate.getValue().getAuditStatus());
        verify(dispatchOrderService).refreshMaterialReadiness(3L, 9L);
    }

    @Test
    void partialReturnKeepsIssueOrderOpenButRollsBackIssuedQuantity() {
        when(orderMapper.selectOne(any())).thenReturn(order(BusinessStatus.Document.SUBMITTED));
        when(detailMapper.selectList(any())).thenReturn(List.of(returnDetail("2.0000")));
        stubValidSource(BigDecimal.ZERO);
        when(detailMapper.sumActiveReturnedQuantity(4L, null)).thenReturn(new BigDecimal("2.0000"));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory("5.0000"));
        when(orderMapper.updateById(any(WmsReturnOrder.class))).thenReturn(1);
        when(inventoryMapper.updateById(any(WmsInventory.class))).thenReturn(1);
        when(movementMapper.insert(any(WmsStockMovement.class))).thenReturn(1);
        when(requirementMapper.selectList(any()))
                .thenReturn(List.of(requirement(new BigDecimal("10.0000"), new BigDecimal("10.0000"))));
        when(requirementMapper.updateById(any(MfgWorkOrderMaterial.class))).thenReturn(1);
        when(outboundOrderMapper.selectById(2L)).thenReturn(sourceOrder());

        assertTrue(service.confirmReturnOrder(1L, 9L, "部分剩余物料退回"));

        ArgumentCaptor<MfgWorkOrderMaterial> requirementUpdate = ArgumentCaptor.forClass(MfgWorkOrderMaterial.class);
        verify(requirementMapper).updateById(requirementUpdate.capture());
        assertEquals(0, new BigDecimal("8.0000").compareTo(requirementUpdate.getValue().getIssuedQuantity()));
        assertEquals(0, new BigDecimal("2.0000").compareTo(requirementUpdate.getValue().getShortageQuantity()));
        assertEquals("PARTIAL_READY", requirementUpdate.getValue().getStatus());
        // 没退完就不能关单，否则工单剩余领料量会被一并抹掉。
        verify(outboundOrderMapper, never()).updateById(any(WmsOutboundOrder.class));
    }

    private MfgWorkOrderMaterial requirement(BigDecimal required, BigDecimal issued) {
        MfgWorkOrderMaterial row = new MfgWorkOrderMaterial();
        row.setRequirementId(21L);
        row.setWorkOrderId(3L);
        row.setMaterialId(11L);
        row.setRequiredQuantity(required);
        row.setIssuedQuantity(issued);
        row.setReservedQuantity(BigDecimal.ZERO);
        row.setShortageQuantity(BigDecimal.ZERO);
        row.setStatus("READY");
        row.setVersion(1);
        row.setDeletedFlag(0);
        return row;
    }

    private void stubReturnableSource(BigDecimal alreadyReturned, BigDecimal consumedQuantity,
                                      BigDecimal desiredAvailable) {
        stubReturnableSource(List.of(sourceDetail()), alreadyReturned, consumedQuantity, desiredAvailable);
    }

    /**
     * 按「已领 − 已消耗 − 目标可退」反推该批次已退数量，避免测试里手写互相矛盾的三个数。
     */
    private void stubReturnableSource(List<WmsOutboundDetail> sources, BigDecimal alreadyReturned,
                                      BigDecimal consumedQuantity, BigDecimal desiredAvailable) {
        BigDecimal issued = sources.stream().map(WmsOutboundDetail::getOutboundQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal batchReturned = issued.subtract(consumedQuantity).subtract(desiredAvailable);
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of(sourceOrder()));
        when(outboundDetailMapper.selectList(any())).thenReturn(sources);
        when(detailMapper.sumActiveReturnedQuantity(any(), any())).thenReturn(BigDecimal.ZERO);
        when(detailMapper.sumActiveReturnedQuantity(4L, null)).thenReturn(alreadyReturned);
        when(detailMapper.sumActiveReturnedQuantityByBatch(any(), any(), any(), any())).thenReturn(batchReturned);
        MfgReportMaterial consumed = new MfgReportMaterial();
        consumed.setUsedQuantity(consumedQuantity);
        consumed.setScrapQuantity(BigDecimal.ZERO);
        when(reportMaterialMapper.selectList(any()))
                .thenReturn(consumedQuantity.signum() == 0 ? List.of() : List.of(consumed));
        MdmMaterial material = new MdmMaterial();
        material.setMaterialId(11L);
        material.setMaterialCode("LAMP-M01");
        material.setMaterialName("灯臂铝型材");
        material.setSpecModel("600mm");
        material.setUnitId(6L);
        material.setDeletedFlag(0);
        when(materialMapper.selectList(any())).thenReturn(List.of(material));
        MdmUnit unit = new MdmUnit();
        unit.setUnitId(6L);
        unit.setUnitName("根");
        unit.setDeletedFlag(0);
        when(unitMapper.selectList(any())).thenReturn(List.of(unit));
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchId(7L);
        batch.setBatchNo("B-20261027-01");
        when(materialBatchMapper.selectList(any())).thenReturn(List.of(batch));
        when(locationMapper.selectList(any())).thenReturn(List.of(location()));
        when(warehouseMapper.selectList(any())).thenReturn(List.of(warehouse()));
        when(movementMapper.selectCount(any())).thenReturn(0L);
    }

    private WmsLocation location() {
        WmsLocation location = new WmsLocation();
        location.setLocationId(8L);
        location.setLocationCode("A-01-01");
        location.setLocationName("A-01-01");
        location.setWarehouseId(2L);
        location.setDeletedFlag(0);
        return location;
    }

    private WmsWarehouse warehouse() {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setWarehouseId(2L);
        warehouse.setWarehouseCode("WH-RM");
        warehouse.setWarehouseName("原材料仓");
        warehouse.setWarehouseType("RAW");
        warehouse.setDeletedFlag(0);
        return warehouse;
    }

    private void stubValidSource(BigDecimal alreadyReturned) {
        when(outboundDetailMapper.selectOne(any())).thenReturn(sourceDetail());
        when(outboundDetailMapper.selectList(any())).thenReturn(List.of(sourceDetail()));
        when(outboundOrderMapper.selectOne(any())).thenReturn(sourceOrder());
        when(detailMapper.sumActiveReturnedQuantity(4L, null)).thenReturn(alreadyReturned);
        when(detailMapper.sumActiveReturnedQuantity(4L, 1L)).thenReturn(alreadyReturned);
        when(detailMapper.sumActiveReturnedQuantityByBatch(3L, 11L, 7L, null)).thenReturn(alreadyReturned);
        when(detailMapper.sumActiveReturnedQuantityByBatch(3L, 11L, 7L, 1L)).thenReturn(alreadyReturned);
    }

    private WmsReturnOrder order(String status) {
        WmsReturnOrder order = new WmsReturnOrder();
        order.setReturnId(1L);
        order.setReturnType("GOOD");
        order.setWorkOrderId(3L);
        order.setApplicantId(9L);
        order.setAuditStatus(status);
        order.setVersion(2);
        return order;
    }

    private WmsReturnDetail returnDetail(String quantity) {
        WmsReturnDetail detail = new WmsReturnDetail();
        detail.setDetailId(6L);
        detail.setReturnId(1L);
        detail.setOutboundDetailId(4L);
        detail.setMaterialId(11L);
        detail.setBatchId(7L);
        detail.setLocationId(8L);
        detail.setReturnQuantity(new BigDecimal(quantity));
        detail.setReturnReason("生产剩余物料退回");
        detail.setDeletedFlag(0);
        return detail;
    }

    private WmsOutboundDetail sourceDetail() {
        WmsOutboundDetail detail = new WmsOutboundDetail();
        detail.setDetailId(4L);
        detail.setOutboundId(2L);
        detail.setMaterialId(11L);
        detail.setBatchId(7L);
        detail.setLocationId(8L);
        detail.setOutboundQuantity(new BigDecimal("10.0000"));
        detail.setDeletedFlag(0);
        return detail;
    }

    private WmsOutboundOrder sourceOrder() {
        WmsOutboundOrder order = new WmsOutboundOrder();
        order.setOutboundId(2L);
        order.setOutboundNo("MI-20261027-001");
        order.setOutboundType("MATERIAL_ISSUE");
        order.setWarehouseId(2L);
        order.setWorkOrderId(3L);
        order.setAuditStatus(BusinessStatus.Document.APPROVED);
        order.setDeletedFlag(0);
        return order;
    }

    private WmsInventory inventory(String available) {
        WmsInventory inventory = new WmsInventory();
        inventory.setInventoryId(3L);
        inventory.setWarehouseId(2L);
        inventory.setLocationId(8L);
        inventory.setMaterialId(11L);
        inventory.setBatchId(7L);
        inventory.setWorkOrderId(0L);
        inventory.setAvailableQuantity(new BigDecimal(available));
        inventory.setVersion(2);
        inventory.setDeletedFlag(0);
        return inventory;
    }

    private WmsInventory lineSideInventory(String locked) {
        WmsInventory inventory = new WmsInventory();
        inventory.setInventoryId(11L);
        inventory.setWarehouseId(2L);
        inventory.setLocationId(2L);
        inventory.setMaterialId(11L);
        inventory.setBatchId(7L);
        inventory.setWorkOrderId(3L);
        inventory.setAvailableQuantity(BigDecimal.ZERO);
        inventory.setLockedQuantity(new BigDecimal(locked));
        inventory.setVersion(4);
        inventory.setDeletedFlag(0);
        return inventory;
    }

    private WmsWarehouse lineSideWarehouse() {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setWarehouseId(2L);
        warehouse.setWarehouseType("LINE_SIDE");
        warehouse.setStatus("ENABLE");
        warehouse.setDeletedFlag(0);
        return warehouse;
    }

    private WmsLocation lineSideLocation() {
        WmsLocation location = new WmsLocation();
        location.setLocationId(2L);
        location.setWarehouseId(2L);
        location.setLocationType("NORMAL");
        location.setStatus("ENABLE");
        location.setDeletedFlag(0);
        return location;
    }
}
