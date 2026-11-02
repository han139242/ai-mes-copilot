package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.ai.AiCommandExecution;
import com.neuedu.ems.entity.ai.AiCommandStep;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.mapper.ai.AiCommandExecutionMapper;
import com.neuedu.ems.mapper.ai.AiCommandStepMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.wms.WmsInboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsLocationMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.ai.AiCommandService;
import com.neuedu.ems.service.mfg.MfgWorkOrderService;
import com.neuedu.ems.service.mfg.MfgWorkReportService;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import com.neuedu.ems.service.wms.WmsOutboundOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批量办理与候选单据单元测试：逐项独立办理、一项失败不影响其它项。
 */
class AiCommandBatchTest {

    private AiCommandServiceImpl service;
    private AiCommandExecutionMapper executionMapper;
    private MfgWorkOrderMapper workOrderMapper;
    private MfgProductionPlanMapper productionPlanMapper;
    private MfgWorkOrderMaterialMapper workOrderMaterialMapper;
    private MfgWorkOrderService workOrderService;
    private SysStatusChangeLogMapper statusChangeLogMapper;

    private final AuthorizationContext admin = new AuthorizationContext(7L, 8L, 9L, "ALL",
            Set.of("SUPER_ADMIN"), Set.of("ai:assistant:use"), Set.of());

    private final Map<String, AiCommandExecution> store = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        service = new AiCommandServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        executionMapper = mock(AiCommandExecutionMapper.class);
        workOrderMapper = mock(MfgWorkOrderMapper.class);
        productionPlanMapper = mock(MfgProductionPlanMapper.class);
        workOrderMaterialMapper = mock(MfgWorkOrderMaterialMapper.class);
        workOrderService = mock(MfgWorkOrderService.class);
        statusChangeLogMapper = mock(SysStatusChangeLogMapper.class);

        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(service, "stepMapper", mock(AiCommandStepMapper.class));
        ReflectionTestUtils.setField(service, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "productionPlanMapper", productionPlanMapper);
        ReflectionTestUtils.setField(service, "workOrderMaterialMapper", workOrderMaterialMapper);
        ReflectionTestUtils.setField(service, "taskMapper", mock(MfgTaskMapper.class));
        ReflectionTestUtils.setField(service, "workReportMapper", mock(MfgWorkReportMapper.class));
        ReflectionTestUtils.setField(service, "outboundOrderMapper", mock(WmsOutboundOrderMapper.class));
        ReflectionTestUtils.setField(service, "outboundDetailMapper", mock(WmsOutboundDetailMapper.class));
        ReflectionTestUtils.setField(service, "inboundOrderMapper", mock(WmsInboundOrderMapper.class));
        ReflectionTestUtils.setField(service, "inboundDetailMapper", mock(WmsInboundDetailMapper.class));
        ReflectionTestUtils.setField(service, "warehouseMapper", mock(WmsWarehouseMapper.class));
        ReflectionTestUtils.setField(service, "locationMapper", mock(WmsLocationMapper.class));
        ReflectionTestUtils.setField(service, "inspectionOrderMapper", mock(QmsInspectionOrderMapper.class));
        ReflectionTestUtils.setField(service, "operationLogMapper", mock(SysOperationLogMapper.class));
        ReflectionTestUtils.setField(service, "statusChangeLogMapper", statusChangeLogMapper);
        ReflectionTestUtils.setField(service, "stockMovementMapper", mock(WmsStockMovementMapper.class));
        ReflectionTestUtils.setField(service, "workOrderService", workOrderService);
        ReflectionTestUtils.setField(service, "outboundOrderService", mock(WmsOutboundOrderService.class));
        ReflectionTestUtils.setField(service, "inboundOrderService", mock(WmsInboundOrderService.class));
        ReflectionTestUtils.setField(service, "inspectionOrderService", mock(QmsInspectionOrderService.class));
        ReflectionTestUtils.setField(service, "workReportService", mock(MfgWorkReportService.class));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        AiCommandStepMapper stepMapper = (AiCommandStepMapper) ReflectionTestUtils.getField(service, "stepMapper");
        when(stepMapper.insert(any(AiCommandStep.class))).thenAnswer((Answer<Integer>) invocation -> {
            AiCommandStep step = invocation.getArgument(0);
            step.setStepId(3000L + store.size());
            return 1;
        });
        when(stepMapper.updateById(any(AiCommandStep.class))).thenReturn(1);
        SysOperationLogMapper logMapper = (SysOperationLogMapper) ReflectionTestUtils.getField(service, "operationLogMapper");
        when(logMapper.insert(any(com.neuedu.ems.entity.sys.SysOperationLog.class))).thenReturn(1);

        // 预览落库时按确认凭据建索引，执行时按凭据回查。
        when(executionMapper.insert(any(AiCommandExecution.class))).thenAnswer((Answer<Integer>) invocation -> {
            AiCommandExecution record = invocation.getArgument(0);
            record.setCommandId(2000L + store.size());
            store.put(record.getConfirmationId(), record);
            return 1;
        });
        when(executionMapper.updateById(any(AiCommandExecution.class))).thenReturn(1);
        when(executionMapper.selectOne(any())).thenAnswer((Answer<AiCommandExecution>) invocation -> {
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(0);
            // MyBatis-Plus 的参数占位是延迟生成的，只有先渲染 SQL 片段才会填充 paramNameValuePairs。
            wrapper.getSqlSegment();
            for (Object value : wrapper.getParamNameValuePairs().values()) {
                if (value instanceof String text && store.containsKey(text)) {
                    return store.get(text);
                }
            }
            return null;
        });
    }

    @Test
    void batchKeepsGoingWhenOneItemFails() {
        when(workOrderMapper.selectOne(any())).thenReturn(draftOrder());
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);
        when(workOrderService.releaseWorkOrder(11L, 7L, null)).thenReturn(draftOrder());
        // 后置复核回查执行后的库内状态：下达成功后工单必须是已下达，且留下状态流水。
        MfgWorkOrder released = draftOrder();
        released.setStatus(BusinessStatus.Document.RELEASED);
        when(workOrderMapper.selectById(11L)).thenReturn(released);
        when(statusChangeLogMapper.selectCount(any())).thenReturn(1L);

        List<AiCommandService.BatchItem> items = new ArrayList<>();
        items.add(new AiCommandService.BatchItem(null, AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER,
                "WO-202610240011", new LinkedHashMap<>()));
        // 第二项对同一张草稿工单要求「开始生产」，前置条件必然不通过，必须只影响自己。
        items.add(new AiCommandService.BatchItem(null, AiCommandServiceImpl.ACTION_START_WORK_ORDER,
                "WO-202610240011", new LinkedHashMap<>()));

        Map<String, Object> result = service.executeBatch(
                new AiCommandService.AiCommandBatchRequest(items, null, null, null), admin);

        assertEquals(2, result.get("total"));
        assertEquals(1, result.get("succeeded"));
        assertEquals(1, result.get("failed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("items");
        assertEquals(Boolean.TRUE, rows.get(0).get("ok"));
        assertEquals("WO-202610240011", rows.get(0).get("bizNo"));
        assertNotNull(rows.get(0).get("commandId"));
        assertEquals("/mfg/work-orders?focus=11", rows.get(0).get("bizRef"));
        assertEquals(Boolean.FALSE, rows.get(1).get("ok"));
        assertNotNull(rows.get(1).get("message"));
    }

    @Test
    void batchRejectsEmptyRequest() {
        com.neuedu.ems.exception.BusinessException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.neuedu.ems.exception.BusinessException.class,
                () -> service.executeBatch(new AiCommandService.AiCommandBatchRequest(
                        List.of(), List.of(), null, null), admin));
        assertTrue(error.getMessage().contains("请给出要办理的单据"));
    }

    @Test
    void candidatesListOnlyActionableWorkOrders() {
        when(workOrderMapper.selectList(any())).thenReturn(List.of(draftOrder()));
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);

        Map<String, Object> data = service.candidates(
                AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER, Map.of(), admin);

        assertEquals(1, data.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("candidates");
        assertEquals("WO-202610240011", rows.get(0).get("bizNo"));
        assertEquals(Boolean.TRUE, rows.get(0).get("canExecute"));
        assertEquals(BusinessStatus.Document.DRAFT, rows.get(0).get("status"));
    }

    @Test
    void candidatesExplainWhyRecordIsBlocked() {
        MfgWorkOrder finished = draftOrder();
        finished.setStatus(BusinessStatus.Production.FINISHED);
        when(workOrderMapper.selectList(any())).thenReturn(List.of(finished));

        Map<String, Object> data = service.candidates(
                AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER, Map.of(), admin);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("candidates");
        assertEquals(Boolean.FALSE, rows.get(0).get("canExecute"));
        assertNotNull(rows.get(0).get("blockingReason"));
        assertFalse(String.valueOf(rows.get(0).get("blockingReason")).isEmpty());
    }

    /* ---------- 构造辅助 ---------- */

    private MfgWorkOrder draftOrder() {
        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderId(11L);
        order.setWorkOrderNo("WO-202610240011");
        order.setPlanId(6L);
        order.setBomId(3L);
        order.setRouteId(4L);
        order.setProductId(2L);
        order.setStatus(BusinessStatus.Document.DRAFT);
        order.setDeletedFlag(0);
        order.setVersion(0);
        return order;
    }

    private MfgProductionPlan releasedPlan() {
        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanId(6L);
        plan.setPlanNo("PLN-5-20261024125958900");
        plan.setStatus(BusinessStatus.Document.RELEASED);
        plan.setPlannedStartTime(LocalDateTime.now().minusMinutes(1));
        plan.setPlannedEndTime(LocalDateTime.now().plusHours(6));
        plan.setVersion(0);
        return plan;
    }
}
