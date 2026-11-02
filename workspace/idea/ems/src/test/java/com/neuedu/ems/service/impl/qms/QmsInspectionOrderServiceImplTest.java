package com.neuedu.ems.service.impl.qms;

import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.qms.QmsInspectionResult;
import com.neuedu.ems.entity.qms.QmsInspectionSchemeItem;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.trace.TraceProductLot;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionResultMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeItemMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.trace.TraceProductLotMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.service.mfg.MfgWorkReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QmsInspectionOrderServiceImplTest {

    private QmsInspectionOrderServiceImpl service;
    private QmsInspectionOrderMapper orderMapper;
    private WmsMaterialBatchMapper batchMapper;
    private SysStatusChangeLogMapper statusLogMapper;
    private SysOperationLogMapper operationLogMapper;
    private QmsInspectionResultMapper resultMapper;
    private TraceProductLotMapper productLotMapper;
    private MfgWorkReportService mfgWorkReportService;

    @BeforeEach
    void setUp() {
        service = new QmsInspectionOrderServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        ReflectionTestUtils.setField(service, "executionGate", mock(com.neuedu.ems.service.mdm.ManufacturingPreparationGate.class));
        ReflectionTestUtils.setField(service, "executionWorkOrders", mock(com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper.class));
        ReflectionTestUtils.setField(service,"preparationReview",mock(com.neuedu.ems.service.qms.InspectionStandardReviewService.class));
        orderMapper = mock(QmsInspectionOrderMapper.class);
        batchMapper = mock(WmsMaterialBatchMapper.class);
        statusLogMapper = mock(SysStatusChangeLogMapper.class);
        operationLogMapper = mock(SysOperationLogMapper.class);
        productLotMapper = mock(TraceProductLotMapper.class);
        mfgWorkReportService = mock(MfgWorkReportService.class);
        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(service, "nonconformingOrders", mock(com.neuedu.ems.mapper.qms.QmsNonconformingOrderMapper.class));
        resultMapper = mock(QmsInspectionResultMapper.class);
        ReflectionTestUtils.setField(service, "qmsInspectionResultMapper", resultMapper);
        ReflectionTestUtils.setField(service, "qmsInspectionSchemeItemMapper", mock(QmsInspectionSchemeItemMapper.class));
        ReflectionTestUtils.setField(service, "mfgWorkReportMapper", mock(MfgWorkReportMapper.class));
        ReflectionTestUtils.setField(service, "sysStatusChangeLogMapper", statusLogMapper);
        ReflectionTestUtils.setField(service, "sysOperationLogMapper", operationLogMapper);
        ReflectionTestUtils.setField(service, "wmsMaterialBatchMapper", batchMapper);
        ReflectionTestUtils.setField(service, "traceProductLotMapper", productLotMapper);
        ReflectionTestUtils.setField(service, "mfgWorkReportService", mfgWorkReportService);
        ReflectionTestUtils.setField(service, "responsibilityNotificationService",
                mock(QmsResponsibilityNotificationService.class));
        when(statusLogMapper.insert(any(SysStatusChangeLog.class))).thenReturn(1);
        when(operationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);
    }

    @Test
    void submitNormalizesQualityResultAndFinishesOrder() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder());
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);

        boolean result = service.submitInspectionOrder(1L, " pass ", "完成检验", 9L);

        assertTrue(result);
        ArgumentCaptor<QmsInspectionOrder> update = ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Quality.PASS, update.getValue().getInspectionResult());
        assertEquals(BusinessStatus.Production.FINISHED, update.getValue().getStatus());
        assertNotNull(update.getValue().getInspectionTime());
    }

    @Test
    void submitRejectsChineseQualityResultBeforeDatabaseMutation() {
        assertFalse(service.submitInspectionOrder(1L, "合格", null, 9L));
        verify(orderMapper, never()).selectOne(any());
        verify(orderMapper, never()).updateById(any(QmsInspectionOrder.class));
    }

    @Test
    void passingInspectionReleasesLinkedMaterialBatch() {
        QmsInspectionOrder order = existingOrder();
        order.setBatchId(7L);
        WmsMaterialBatch batch = batch("WAIT_INSPECTION", "FROZEN");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(batchMapper.selectOne(any())).thenReturn(batch);
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        when(batchMapper.updateById(any(WmsMaterialBatch.class))).thenReturn(1);

        assertTrue(service.submitInspectionOrder(1L, "PASS", null, 9L));

        ArgumentCaptor<WmsMaterialBatch> update = ArgumentCaptor.forClass(WmsMaterialBatch.class);
        verify(batchMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Quality.PASS, update.getValue().getQualityStatus());
        assertEquals("AVAILABLE", update.getValue().getStatus());
        assertEquals(2, update.getValue().getVersion());
    }

    @Test
    void failedInspectionKeepsLinkedMaterialBatchFrozen() {
        QmsInspectionOrder order = existingOrder();
        order.setBatchId(7L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(batchMapper.selectOne(any())).thenReturn(batch("WAIT_INSPECTION", "FROZEN"));
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        when(batchMapper.updateById(any(WmsMaterialBatch.class))).thenReturn(1);

        assertTrue(service.submitInspectionOrder(1L, "FAIL", null, 9L));

        ArgumentCaptor<WmsMaterialBatch> update = ArgumentCaptor.forClass(WmsMaterialBatch.class);
        verify(batchMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Quality.FAIL, update.getValue().getQualityStatus());
        assertEquals("FROZEN", update.getValue().getStatus());
    }

    @Test
    void batchOptimisticConflictFailsWholeInspectionCommand() {
        QmsInspectionOrder order = existingOrder();
        order.setBatchId(7L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(batchMapper.selectOne(any())).thenReturn(batch("WAIT_INSPECTION", "FROZEN"));
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        when(batchMapper.updateById(any(WmsMaterialBatch.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> service.submitInspectionOrder(1L, "PASS", null, 9L));
    }

    @Test
    void ordinaryUpdateCannotOverwriteResultOrStatus() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder());
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        QmsInspectionOrder request = new QmsInspectionOrder();
        request.setInspectionId(1L);
        request.setInspectionResult(BusinessStatus.Quality.FAIL);
        request.setStatus(BusinessStatus.Production.FINISHED);
        request.setSampleQuantity(BigDecimal.TEN);

        assertTrue(service.updateInspectionOrder(request));

        ArgumentCaptor<QmsInspectionOrder> update = ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertNull(update.getValue().getInspectionResult());
        assertNull(update.getValue().getStatus());
        assertNull(update.getValue().getSampleQuantity());
    }

    @Test
    void submitAcceptsConsistentInspectionQuantities() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder());
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);

        assertTrue(service.submitInspectionOrder(1L, "FAIL", "发现不良", 9L,
                BigDecimal.TEN, new BigDecimal("8"), new BigDecimal("2")));

        ArgumentCaptor<QmsInspectionOrder> update = ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BigDecimal.TEN, update.getValue().getSampleQuantity());
        assertEquals(new BigDecimal("8"), update.getValue().getQualifiedQuantity());
        assertEquals(new BigDecimal("2"), update.getValue().getUnqualifiedQuantity());
        assertEquals(BusinessStatus.Quality.FAIL, update.getValue().getInspectionResult());
        assertNotNull(update.getValue().getInspectionTime());
    }

    @Test
    void submitTreatsUnqualifiedRateAtLimitAsPass() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder());
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);

        assertTrue(service.submitInspectionOrder(1L, "FAIL", null, 9L,
                new BigDecimal("1000"), new BigDecimal("995"), new BigDecimal("5")));

        ArgumentCaptor<QmsInspectionOrder> update = ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Quality.PASS, update.getValue().getInspectionResult());
    }

    @Test
    void submitTreatsUnqualifiedRateAboveLimitAsFail() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder());
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);

        assertTrue(service.submitInspectionOrder(1L, "PASS", null, 9L,
                new BigDecimal("1000"), new BigDecimal("994"), new BigDecimal("6")));

        ArgumentCaptor<QmsInspectionOrder> update = ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Quality.FAIL, update.getValue().getInspectionResult());
    }

    @Test
    void submitRejectsInconsistentInspectionQuantities() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder());

        assertFalse(service.submitInspectionOrder(1L, "PASS", null, 9L,
                BigDecimal.TEN, new BigDecimal("9"), BigDecimal.ZERO));
        assertFalse(service.submitInspectionOrder(1L, "PASS", null, 9L,
                BigDecimal.TEN, new BigDecimal("9"), new BigDecimal("2")));

        verify(orderMapper, never()).updateById(any(QmsInspectionOrder.class));
    }

    @Test
    void submitRejectsSampleQuantityAboveLinkedReportQuantity() {
        QmsInspectionOrder order = existingOrder();
        order.setReportId(3L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        MfgWorkReportMapper reportMapper = (MfgWorkReportMapper) ReflectionTestUtils.getField(service, "mfgWorkReportMapper");
        MfgWorkReport report = new MfgWorkReport();
        report.setQualifiedQuantity(new BigDecimal("8"));
        report.setDefectQuantity(new BigDecimal("2"));
        when(reportMapper.selectOne(any())).thenReturn(report);

        assertFalse(service.submitInspectionOrder(1L, "PASS", null, 9L,
                new BigDecimal("11"), new BigDecimal("11"), BigDecimal.ZERO));
        verify(orderMapper, never()).updateById(any(QmsInspectionOrder.class));
    }

    @Test
    void finalInspectionReleasesLinkedProductLotWithoutTouchingMaterialBatch() {
        QmsInspectionOrder order = existingOrder();
        order.setInspectionType("FINAL");
        order.setWorkOrderId(3L);
        order.setReportId(1L);
        order.setProductLotId(31L);
        TraceProductLot productLot = new TraceProductLot();
        productLot.setProductLotId(31L);
        productLot.setWorkOrderId(3L);
        productLot.setSourceReportId(1L);
        productLot.setStatus("WAIT_INSPECTION");
        productLot.setVersion(2);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(productLotMapper.selectOne(any())).thenReturn(productLot);
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        when(productLotMapper.updateById(any(TraceProductLot.class))).thenReturn(1);

        assertTrue(service.submitInspectionOrder(1L, "PASS", null, 9L));

        ArgumentCaptor<TraceProductLot> update = ArgumentCaptor.forClass(TraceProductLot.class);
        verify(productLotMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Quality.PASS, update.getValue().getStatus());
        assertEquals(2, update.getValue().getVersion());
        verify(batchMapper, never()).updateById(any(WmsMaterialBatch.class));
        verify(mfgWorkReportService).recalculateWorkOrderCompletion(3L, 9L, "终检单 1 提交结果 PASS");
    }

    @Test
    void withdrawDraftInspectionWithoutResultsClosesAndAudits() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder());
        when(resultMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);

        service.withdrawInspectionOrder(1L, 9L, null);

        ArgumentCaptor<QmsInspectionOrder> update = ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(BusinessStatus.Document.CLOSED, update.getValue().getStatus());
        verify(statusLogMapper).insert(any(SysStatusChangeLog.class));
        verify(operationLogMapper).insert(any(SysOperationLog.class));
    }

    @Test
    void withdrawInspectionRejectsExistingResultBeforeMutation() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder());
        when(resultMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class,
                () -> service.withdrawInspectionOrder(1L, 9L, "误建检验单"));

        verify(orderMapper, never()).updateById(any(QmsInspectionOrder.class));
    }

    @Test
    void pieceInspectionAggregatesPieceVerdictsIntoOrderQuantities() {
        QmsInspectionOrder order = existingOrder();
        order.setSchemeId(29L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        QmsInspectionSchemeItemMapper itemMapper =
                (QmsInspectionSchemeItemMapper) ReflectionTestUtils.getField(service, "qmsInspectionSchemeItemMapper");
        when(itemMapper.selectList(any())).thenReturn(List.of(schemeItem(101L), schemeItem(102L)));

        List<QmsInspectionResult> results = new ArrayList<>();
        results.add(pieceResult("L200-001", 101L, BusinessStatus.Quality.PASS));
        results.add(pieceResult("L200-001", 102L, BusinessStatus.Quality.PASS));
        results.add(pieceResult("L200-002", 101L, BusinessStatus.Quality.PASS));
        results.add(pieceResult("L200-002", 102L, BusinessStatus.Quality.FAIL));
        results.add(pieceResult("L200-003", 101L, BusinessStatus.Quality.PASS));
        results.add(pieceResult("L200-003", 102L, BusinessStatus.Quality.PASS));

        assertTrue(service.submitPieceInspection(1L, results, "逐件全检", 9L));

        verify(resultMapper, times(6)).insert(any(QmsInspectionResult.class));
        ArgumentCaptor<QmsInspectionOrder> update = ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals(0, new BigDecimal("3").compareTo(update.getValue().getSampleQuantity()));
        assertEquals(0, new BigDecimal("2").compareTo(update.getValue().getQualifiedQuantity()));
        assertEquals(0, new BigDecimal("1").compareTo(update.getValue().getUnqualifiedQuantity()));
        assertEquals(BusinessStatus.Quality.FAIL, update.getValue().getInspectionResult());
    }

    @Test
    void pieceInspectionRejectsPieceMissingRequiredItem() {
        QmsInspectionOrder order = existingOrder();
        order.setSchemeId(29L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        QmsInspectionSchemeItemMapper itemMapper =
                (QmsInspectionSchemeItemMapper) ReflectionTestUtils.getField(service, "qmsInspectionSchemeItemMapper");
        when(itemMapper.selectList(any())).thenReturn(List.of(schemeItem(101L), schemeItem(102L)));

        List<QmsInspectionResult> results = new ArrayList<>();
        results.add(pieceResult("L200-001", 101L, BusinessStatus.Quality.PASS));
        results.add(pieceResult("L200-001", 102L, BusinessStatus.Quality.PASS));
        results.add(pieceResult("L200-002", 101L, BusinessStatus.Quality.PASS));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submitPieceInspection(1L, results, null, 9L));
        assertTrue(error.getMessage().contains("还缺 1 个必检项结果"));
        verify(resultMapper, never()).insert(any(QmsInspectionResult.class));
        verify(orderMapper, never()).updateById(any(QmsInspectionOrder.class));
    }

    @Test
    void pieceInspectionRejectsDuplicateItemInSamePiece() {
        QmsInspectionOrder order = existingOrder();
        order.setSchemeId(29L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        QmsInspectionSchemeItemMapper itemMapper =
                (QmsInspectionSchemeItemMapper) ReflectionTestUtils.getField(service, "qmsInspectionSchemeItemMapper");
        when(itemMapper.selectList(any())).thenReturn(List.of(schemeItem(101L)));

        List<QmsInspectionResult> results = new ArrayList<>();
        results.add(pieceResult("L200-001", 101L, BusinessStatus.Quality.PASS));
        results.add(pieceResult("L200-001", 101L, BusinessStatus.Quality.PASS));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submitPieceInspection(1L, results, null, 9L));
        assertTrue(error.getMessage().contains("重复登记"));
        verify(resultMapper, never()).insert(any(QmsInspectionResult.class));
    }

    @Test
    void pieceInspectionRejectsResultWithoutPieceNo() {
        QmsInspectionOrder order = existingOrder();
        order.setSchemeId(29L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        QmsInspectionSchemeItemMapper itemMapper =
                (QmsInspectionSchemeItemMapper) ReflectionTestUtils.getField(service, "qmsInspectionSchemeItemMapper");
        when(itemMapper.selectList(any())).thenReturn(List.of(schemeItem(101L)));

        List<QmsInspectionResult> results = new ArrayList<>();
        results.add(pieceResult("  ", 101L, BusinessStatus.Quality.PASS));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submitPieceInspection(1L, results, null, 9L));
        assertTrue(error.getMessage().contains("写明件号"));
    }

    private QmsInspectionSchemeItem schemeItem(Long itemId) {
        QmsInspectionSchemeItem item = new QmsInspectionSchemeItem();
        item.setItemId(itemId);
        item.setSchemeId(29L);
        item.setDeletedFlag(0);
        return item;
    }

    private QmsInspectionResult pieceResult(String pieceNo, Long itemId, String result) {
        QmsInspectionResult item = new QmsInspectionResult();
        item.setPieceNo(pieceNo);
        item.setMeasuredValue("人工检查记录（测试输入）");
        item.setItemId(itemId);
        item.setResult(result);
        return item;
    }

    private QmsInspectionOrder existingOrder() {
        QmsInspectionOrder order = new QmsInspectionOrder();
        order.setInspectionId(1L);
        order.setStatus(BusinessStatus.Document.DRAFT);
        return order;
    }

    private WmsMaterialBatch batch(String qualityStatus, String status) {
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchId(7L);
        batch.setMaterialId(11L);
        batch.setQualityStatus(qualityStatus);
        batch.setStatus(status);
        batch.setVersion(2);
        return batch;
    }

    @Test
    void allInspectionCannotBypassPieceEvidenceThroughLegacySubmit() {
        QmsInspectionOrder order=existingOrder();order.setSchemeId(29L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        var item=schemeItem(101L);item.setSamplingMode("ALL");
        var mapper=(QmsInspectionSchemeItemMapper)ReflectionTestUtils.getField(service,"qmsInspectionSchemeItemMapper");
        when(mapper.selectList(any())).thenReturn(List.of(item));
        BusinessException error=assertThrows(BusinessException.class,()->service.submitInspectionOrder(1L,"PASS",null,9L));
        assertTrue(error.getMessage().contains("逐件全检"));
        verify(orderMapper,never()).updateById(any(QmsInspectionOrder.class));
    }

    @Test
    void pieceInspectionFailsIfEvenOneOfTwoHundredPiecesFails() {
        QmsInspectionOrder order=existingOrder();order.setSchemeId(29L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        var mapper=(QmsInspectionSchemeItemMapper)ReflectionTestUtils.getField(service,"qmsInspectionSchemeItemMapper");
        when(mapper.selectList(any())).thenReturn(List.of(schemeItem(101L)));
        List<QmsInspectionResult> results=new ArrayList<>();
        for(int i=0;i<200;i++)results.add(pieceResult("PIECE-"+i,101L,i==0?"FAIL":"PASS"));
        assertTrue(service.submitPieceInspection(1L,results,null,9L));
        ArgumentCaptor<QmsInspectionOrder> update=ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals("FAIL",update.getValue().getInspectionResult());
    }

    @Test
    void pieceConcessionRemainsExplicitInFinalVerdict() {
        QmsInspectionOrder order=existingOrder();order.setSchemeId(29L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        var mapper=(QmsInspectionSchemeItemMapper)ReflectionTestUtils.getField(service,"qmsInspectionSchemeItemMapper");
        when(mapper.selectList(any())).thenReturn(List.of(schemeItem(101L)));
        assertTrue(service.submitPieceInspection(1L,List.of(pieceResult("P1",101L,"CONCESSION")),null,9L));
        ArgumentCaptor<QmsInspectionOrder> update=ArgumentCaptor.forClass(QmsInspectionOrder.class);
        verify(orderMapper).updateById(update.capture());
        assertEquals("CONCESSION",update.getValue().getInspectionResult());
    }

    @Test
    void pieceInspectionRejectsBlankVerdict() {
        QmsInspectionOrder order=existingOrder();order.setSchemeId(29L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        var mapper=(QmsInspectionSchemeItemMapper)ReflectionTestUtils.getField(service,"qmsInspectionSchemeItemMapper");
        when(mapper.selectList(any())).thenReturn(List.of(schemeItem(101L)));
        var result=pieceResult("P1",101L," ");result.setMeasuredValue(null);
        assertThrows(BusinessException.class,()->service.submitPieceInspection(1L,List.of(result),null,9L));
        verify(resultMapper,never()).insert(any(QmsInspectionResult.class));
    }


    @Test
    void allInspectionCannotSubmitOnlyPartOfSourceReport() {
        QmsInspectionOrder order=existingOrder();order.setSchemeId(29L);order.setReportId(22L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        var mapper=(QmsInspectionSchemeItemMapper)ReflectionTestUtils.getField(service,"qmsInspectionSchemeItemMapper");
        var item=schemeItem(101L);item.setSamplingMode("ALL");
        when(mapper.selectList(any())).thenReturn(List.of(item));
        var reports=(com.neuedu.ems.mapper.mfg.MfgWorkReportMapper)ReflectionTestUtils.getField(service,"mfgWorkReportMapper");
        var report=new com.neuedu.ems.entity.mfg.MfgWorkReport();report.setQualifiedQuantity(new BigDecimal("2"));
        report.setDefectQuantity(BigDecimal.ZERO);when(reports.selectOne(any())).thenReturn(report);
        assertThrows(BusinessException.class,()->service.submitPieceInspection(1L,List.of(pieceResult("P1",101L,"PASS")),null,9L));
        verify(orderMapper,never()).updateById(any(QmsInspectionOrder.class));
    }

    private QmsInspectionOrder productionDefectOrder() {
        var order=existingOrder();order.setReportId(22L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        var reports=(MfgWorkReportMapper)ReflectionTestUtils.getField(service,"mfgWorkReportMapper");
        var report=new MfgWorkReport();report.setReportId(22L);report.setReportNo("WR-TEST");
        report.setAuditStatus("APPROVED");report.setQualifiedQuantity(new BigDecimal("90"));
        report.setDefectQuantity(BigDecimal.TEN);report.setDefectDescription("演练：十件外壳破损");
        when(reports.selectOne(any())).thenReturn(report);when(reports.selectList(any())).thenReturn(List.of(report));
        return order;
    }

    @Test void sourceDefectSlotsCannotBeMovedToOtherPieces() {
        var order = productionDefectOrder();
        order.setInspectionNo("QI-LOCK");
        var pieces = new java.util.LinkedHashMap<String, java.util.Set<Long>>();
        var rows = new ArrayList<QmsInspectionResult>();
        for (int i = 1; i <= 100; i++) {
            String number = "QI-LOCK-" + String.format(java.util.Locale.ROOT, "%04d", i);
            pieces.put(number, java.util.Set.of(101L));
            rows.add(pieceResult(number, 101L, i > 90 ? "FAIL" : "PASS"));
        }
        ReflectionTestUtils.invokeMethod(service, "requireReportedDefectPieces", order, pieces, rows);
        rows.get(99).setResult("PASS");
        rows.get(0).setResult("FAIL");
        assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "requireReportedDefectPieces", order, pieces, rows));
    }

    @Test void sourceDefectSlotCannotBeChangedToConcession() {
        var order = productionDefectOrder();
        order.setInspectionNo("QI-LOCK");
        var pieces = new java.util.LinkedHashMap<String, java.util.Set<Long>>();
        var rows = new ArrayList<QmsInspectionResult>();
        for (int i = 1; i <= 100; i++) {
            String number = "QI-LOCK-" + String.format(java.util.Locale.ROOT, "%04d", i);
            pieces.put(number, java.util.Set.of(101L));
            rows.add(pieceResult(number, 101L, i > 90 ? "FAIL" : "PASS"));
        }
        rows.get(99).setResult("CONCESSION");
        assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "requireReportedDefectPieces", order, pieces, rows));
    }

    @Test void productionDefectsCannotBePassedByAggregateApi() {
        productionDefectOrder();
        assertTrue(assertThrows(BusinessException.class,()->service.submitInspectionOrder(1L,"PASS",null,9L,
                new BigDecimal("100"),new BigDecimal("100"),BigDecimal.ZERO)).getMessage().contains("保留"));
        verify(orderMapper,never()).updateById(any(QmsInspectionOrder.class));
    }

    @Test void productionDefectsCannotBePassedByPieceApi() {
        var order=productionDefectOrder();order.setSchemeId(29L);
        var mapper=(QmsInspectionSchemeItemMapper)ReflectionTestUtils.getField(service,"qmsInspectionSchemeItemMapper");
        when(mapper.selectList(any())).thenReturn(List.of(schemeItem(101L)));
        var rows=new ArrayList<QmsInspectionResult>();
        for(int i=1;i<=100;i++) rows.add(pieceResult("P"+i,101L,"PASS"));
        assertThrows(BusinessException.class,()->service.submitPieceInspection(1L,rows,null,9L));
        verify(orderMapper,never()).updateById(any(QmsInspectionOrder.class));
        verify(resultMapper,never()).insert(any(QmsInspectionResult.class));
    }

    @Test void reportedDefectsAndReasonsAreVisibleAndLegacyConflictIsFlagged() {
        var order=productionDefectOrder();order.setStatus("FINISHED");order.setUnqualifiedQuantity(BigDecimal.ZERO);
        var actual=service.getInspectionOrderById(1L);
        assertEquals(BigDecimal.TEN,actual.getSourceDefectQuantity());
        assertEquals("演练：十件外壳破损",actual.getSourceDefectDescription());
        assertEquals("WR-TEST",actual.getSourceReportNo());assertTrue(actual.getProductionDefectConflict());
    }

    @Test void productionDefectBelowBatchToleranceStillRequiresDisposition() {
        productionDefectOrder();
        var reports=(MfgWorkReportMapper)ReflectionTestUtils.getField(service,"mfgWorkReportMapper");
        var report=reports.selectOne(null);report.setQualifiedQuantity(new BigDecimal("999"));report.setDefectQuantity(BigDecimal.ONE);
        when(orderMapper.updateById(any(QmsInspectionOrder.class))).thenReturn(1);
        assertTrue(service.submitInspectionOrder(1L,"PASS",null,9L,new BigDecimal("1000"),new BigDecimal("999"),BigDecimal.ONE));
        var update=ArgumentCaptor.forClass(QmsInspectionOrder.class);verify(orderMapper).updateById(update.capture());
        assertEquals("FAIL",update.getValue().getInspectionResult());
    }

}
