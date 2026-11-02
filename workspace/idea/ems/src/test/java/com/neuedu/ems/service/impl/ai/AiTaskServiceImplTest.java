package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.entity.ai.AiQueryResult;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.entity.ai.AiTaskDefinition;
import com.neuedu.ems.entity.ai.AiTaskInstance;
import com.neuedu.ems.entity.ai.AiTaskStep;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.ai.AiTaskDefinitionMapper;
import com.neuedu.ems.mapper.ai.AiTaskInstanceMapper;
import com.neuedu.ems.mapper.ai.AiTaskStepMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.service.ai.AiDataQueryService;
import com.neuedu.ems.service.impl.sys.ResponsibilityNotificationSupport;
import com.neuedu.ems.service.mfg.MfgProductionPlanService;
import com.neuedu.ems.service.mfg.MfgWorkOrderService;
import com.neuedu.ems.service.mfg.MfgWorkReportService;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 多步任务的幂等、步骤编排与失败恢复测试。
 *
 * <p>全部使用 mock 仓储，不连接数据库，重点验证：
 * 同一事件键重复到达时只执行一次；步骤失败时后续步骤跳过并记录续跑点；
 * 未配置负责人时通知步骤仍然记为成功但提示待补发。</p>
 */
class AiTaskServiceImplTest {

    private AiTaskDefinitionMapper definitionMapper;
    private AiTaskInstanceMapper instanceMapper;
    private AiTaskStepMapper stepMapper;
    private WmsInboundOrderMapper inboundOrderMapper;
    private WmsInboundOrderService inboundOrderService;
    private QmsInspectionOrderService inspectionOrderService;
    private MfgWorkReportService workReportService;
    private ResponsibilityNotificationSupport notificationSupport;
    private MfgProductionPlanService productionPlanService;
    private MfgWorkOrderService workOrderService;
    private AiDataQueryService dataQueryService;
    private AiTaskServiceImpl service;

    private final List<AiTaskInstance> insertedInstances = new ArrayList<>();
    private final List<AiTaskStep> insertedSteps = new ArrayList<>();
    private final List<AiTaskDefinition> insertedDefinitions = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        definitionMapper = mock(AiTaskDefinitionMapper.class);
        instanceMapper = mock(AiTaskInstanceMapper.class);
        stepMapper = mock(AiTaskStepMapper.class);
        inboundOrderMapper = mock(WmsInboundOrderMapper.class);
        inboundOrderService = mock(WmsInboundOrderService.class);
        inspectionOrderService = mock(QmsInspectionOrderService.class);
        workReportService = mock(MfgWorkReportService.class);
        notificationSupport = mock(ResponsibilityNotificationSupport.class);
        productionPlanService = mock(MfgProductionPlanService.class);
        workOrderService = mock(MfgWorkOrderService.class);
        dataQueryService = mock(AiDataQueryService.class);
        service = new AiTaskServiceImpl(definitionMapper, instanceMapper, stepMapper, inboundOrderMapper,
                inboundOrderService, inspectionOrderService, workReportService, notificationSupport,
                new ObjectMapper(), productionPlanService, workOrderService, dataQueryService);
        when(instanceMapper.insert(any(AiTaskInstance.class))).thenAnswer(invocation -> {
            AiTaskInstance instance = invocation.getArgument(0);
            instance.setInstanceId((long) (insertedInstances.size() + 1));
            insertedInstances.add(instance);
            return 1;
        });
        when(stepMapper.insert(any(AiTaskStep.class))).thenAnswer(invocation -> {
            insertedSteps.add(invocation.getArgument(0));
            return 1;
        });
        when(definitionMapper.insert(any(AiTaskDefinition.class))).thenAnswer(invocation -> {
            AiTaskDefinition definition = invocation.getArgument(0);
            definition.setDefinitionId((long) (insertedDefinitions.size() + 1));
            insertedDefinitions.add(definition);
            return 1;
        });
        when(instanceMapper.updateById(any(AiTaskInstance.class))).thenReturn(1);
        when(definitionMapper.updateById(any(AiTaskDefinition.class))).thenReturn(1);
        when(instanceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    }

    private AiTaskDefinition inboundDefinition() {
        AiTaskDefinition definition = new AiTaskDefinition();
        definition.setDefinitionId(1L);
        definition.setDefinitionNo("TASK-INBOUND-IQC");
        definition.setDefinitionName("入库单待确认自动来料检验");
        definition.setTriggerType("EVENT");
        definition.setTriggerEventKey("WMS_INBOUND_PENDING");
        definition.setStatus("ENABLED");
        definition.setMaxAttempts(1);
        definition.setMaxInstancesPerHour(50);
        definition.setStepsJson("[{\"stepNo\":1,\"label\":\"读取触发入库单\",\"kind\":\"query\","
                + "\"capability\":\"inbound_lookup\",\"targetType\":\"WMS_INBOUND_ORDER\"},"
                + "{\"stepNo\":2,\"label\":\"生成来料检验通知\",\"kind\":\"notify\","
                + "\"capability\":\"notify_iqc_pending\",\"targetType\":\"WMS_INBOUND_ORDER\"},"
                + "{\"stepNo\":3,\"label\":\"通知仓管\",\"kind\":\"notify\","
                + "\"capability\":\"notify_warehouse\",\"targetType\":\"WMS_INBOUND_ORDER\"}]");
        return definition;
    }

    private WmsInboundOrder submittedInbound() {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setInboundId(53L);
        order.setInboundNo("IN-AITASK-001");
        order.setAuditStatus("SUBMITTED");
        return order;
    }

    @Test
    @DisplayName("同一事件键重复到达时只执行一次")
    void duplicateEventRunsOnce() {
        AiTaskDefinition definition = inboundDefinition();
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(definition))
                .thenReturn(List.of(definition));
        when(inboundOrderMapper.selectById(53L)).thenReturn(submittedInbound());
        // 第一次查 instance_key 不存在，第二次已存在
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null)
                .thenReturn(insertedInstances.isEmpty() ? new AiTaskInstance() : insertedInstances.get(0));

        int first = service.runByEvent("WMS_INBOUND_PENDING", 53L, "WMS_INBOUND_ORDER", "IN-AITASK-001");
        int second = service.runByEvent("WMS_INBOUND_PENDING", 53L, "WMS_INBOUND_ORDER", "IN-AITASK-001");

        assertEquals(1, first, "首次事件应执行一次");
        assertEquals(0, second, "重复事件不应再执行");
        assertEquals(1, insertedInstances.size(), "只应产生一个任务实例");
    }

    @Test
    @DisplayName("步骤全部成功时实例记为 SUCCEEDED 且每步都落库")
    void allStepsSucceed() {
        AiTaskDefinition definition = inboundDefinition();
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(definition));
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(inboundOrderMapper.selectById(53L)).thenReturn(submittedInbound());
        when(notificationSupport.departmentManager(anyString())).thenReturn(null);

        int executed = service.runByEvent("WMS_INBOUND_PENDING", 53L, "WMS_INBOUND_ORDER", "IN-AITASK-001");

        assertEquals(1, executed);
        ArgumentCaptor<AiTaskInstance> captor = ArgumentCaptor.forClass(AiTaskInstance.class);
        verify(instanceMapper).updateById(captor.capture());
        assertEquals("SUCCEEDED", captor.getValue().getStatus());
        assertEquals(3, captor.getValue().getCurrentStepNo());
        assertEquals(3, insertedSteps.size(), "三步都要落库");
        assertEquals("SUCCEEDED", insertedSteps.get(0).getStatus());
        assertTrue(insertedSteps.get(1).getMessage().contains("待补发"),
                "负责人未配置时应提示待补发");
    }

    @Test
    @DisplayName("触发单号缺失时第一步失败，后续步骤标记跳过并给出续跑点")
    void missingAggregateFailsFirstStep() {
        AiTaskDefinition definition = inboundDefinition();
        when(definitionMapper.selectById(1L)).thenReturn(definition);

        Map<String, Object> result = service.run(1L, 1L);

        assertEquals("FAILED", result.get("status"));
        assertEquals(1, result.get("resumeStepNo"), "续跑点应回到第一步");
        assertEquals("FAILED", insertedSteps.get(0).getStatus());
        assertEquals("SKIPPED", insertedSteps.get(1).getStatus());
        assertTrue(insertedSteps.get(0).getMessage().contains("缺少入库单ID"));
    }

    @Test
    @DisplayName("未配置步骤的任务不允许执行")
    void emptyStepsRejected() {
        AiTaskDefinition definition = inboundDefinition();
        definition.setStepsJson("[]");
        when(definitionMapper.selectById(1L)).thenReturn(definition);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.run(1L, 1L));

        assertTrue(exception.getMessage().contains("没有配置步骤"));
    }

    @Test
    @DisplayName("停用的任务不能手动执行")
    void disabledDefinitionRejected() {
        AiTaskDefinition definition = inboundDefinition();
        definition.setStatus("DISABLED");
        when(definitionMapper.selectById(1L)).thenReturn(definition);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.run(1L, 1L));

        assertTrue(exception.getMessage().contains("已停用"));
    }

    @Test
    @DisplayName("每小时实例数达到上限时不再执行，改落一条跳过实例")
    void hourlyLimitStopsExecution() {
        AiTaskDefinition definition = inboundDefinition();
        definition.setMaxInstancesPerHour(2);
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(definition));
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(instanceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        int executed = service.runByEvent("WMS_INBOUND_PENDING", 53L, "WMS_INBOUND_ORDER", "IN-AITASK-001");

        assertEquals(0, executed, "达到上限不应继续执行");
        assertEquals(1, insertedInstances.size());
        assertEquals("SKIPPED", insertedInstances.get(0).getStatus());
        assertTrue(insertedInstances.get(0).getSummary().contains("上限"));
    }

    @Test
    @DisplayName("预览不写库，只回显步骤与支持情况")
    void previewIsReadOnly() {
        AiTaskDefinition definition = inboundDefinition();
        when(definitionMapper.selectById(1L)).thenReturn(definition);

        Map<String, Object> preview = service.preview(1L);

        assertEquals(true, preview.get("dryRun"));
        assertEquals(3, preview.get("totalSteps"));
        assertTrue(insertedInstances.isEmpty(), "预览不应创建实例");
        assertTrue(insertedSteps.isEmpty(), "预览不应写步骤");
    }

    @Test
    @DisplayName("实例详情返回步骤流水")
    void instanceDetailReturnsSteps() {
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceId(9L);
        instance.setInstanceNo("AI-TASK-9");
        when(instanceMapper.selectById(9L)).thenReturn(instance);
        AiTaskStep step = new AiTaskStep();
        step.setInstanceId(9L);
        step.setStepNo(1);
        step.setStatus("SUCCEEDED");
        when(stepMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(step));

        Map<String, Object> detail = service.getInstanceDetail(9L);

        assertNotNull(detail);
        assertEquals("AI-TASK-9", ((AiTaskInstance) detail.get("instance")).getInstanceNo());
        assertEquals(1, ((List<?>) detail.get("steps")).size());
        assertFalse(((List<?>) detail.get("steps")).isEmpty());
    }

    @Test
    @DisplayName("查询不存在的实例直接报错")
    void missingInstanceRejected() {
        when(instanceMapper.selectById(404L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getInstanceDetail(404L));

        assertTrue(exception.getMessage().contains("实例不存在"));
    }

    @Test
    @DisplayName("提交检验步骤不传逐件结果，缺实测时停在等待补充而不是记失败")
    void submitInspectionStepNeverFabricatesPieceResults() {
        AiTaskDefinition definition = inspectionDefinition(2L, "TASK-SUBMIT-INSPECTION", "检验单自动提交流程");
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(definition));
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // 领域服务在没有逐件结果时要求补齐，这里用异常模拟这条业务规则。
        when(inspectionOrderService.submitInspectionAutoPieces(any(), any(), anyString(), any(), any(), any(),
                any(), any())).thenThrow(new BusinessException(1003,
                "本方案要求逐件全检，缺少每件产品的实际检验结果，请补齐后再生效"));

        int executed = service.runByEvent(QMS_EVENT, 77L, "INSPECTION_ORDER", "QI-77");

        assertEquals(1, executed);
        // 第 8 个参数是逐件结果，自动任务必须传 null，不能凭空造台账。
        verify(inspectionOrderService).submitInspectionAutoPieces(eq(77L), isNull(), anyString(), any(),
                isNull(), isNull(), isNull(), isNull());
        assertEquals(1, insertedSteps.size());
        assertEquals("FAILED", insertedSteps.get(0).getStatus());
        assertTrue(insertedSteps.get(0).getMessage().contains("逐件全检"),
                "失败原因要让用户看到需要补逐件结果，实际：" + insertedSteps.get(0).getMessage());
        ArgumentCaptor<AiTaskInstance> captor = ArgumentCaptor.forClass(AiTaskInstance.class);
        verify(instanceMapper).updateById(captor.capture());
        assertEquals("WAITING_INPUT", captor.getValue().getStatus(),
                "缺的是人填的实测值，流程本身没错，应停在等待补充");
        assertEquals(1, captor.getValue().getResumeStepNo(), "补齐后应从同一步继续");
        assertTrue(captor.getValue().getWaitingReason() != null
                && captor.getValue().getWaitingReason().contains("逐件全检"),
                "等待原因要能直接展示给人看");
    }

    @Test
    @DisplayName("提交检验步骤在领域服务返回 false 时同样记为失败")
    void submitInspectionStepFailsWhenServiceReturnsFalse() {
        AiTaskDefinition definition = inspectionDefinition(3L, "TASK-SUBMIT-INSPECTION-2", "检验单自动提交流程二");
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(definition));
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(inspectionOrderService.submitInspectionAutoPieces(any(), any(), anyString(), any(), any(), any(),
                any(), any())).thenReturn(false);

        service.runByEvent(QMS_EVENT, 78L, "INSPECTION_ORDER", "QI-78");

        assertEquals("FAILED", insertedSteps.get(0).getStatus());
        assertTrue(insertedSteps.get(0).getMessage().contains("未生效"));
    }

    private static final String QMS_EVENT = "QMS_INSPECTION_DRAFT";

    private AiTaskDefinition inspectionDefinition(Long definitionId, String no, String name) {
        AiTaskDefinition definition = new AiTaskDefinition();
        definition.setDefinitionId(definitionId);
        definition.setDefinitionNo(no);
        definition.setDefinitionName(name);
        definition.setTriggerType("EVENT");
        definition.setTriggerEventKey(QMS_EVENT);
        definition.setStatus("ENABLED");
        definition.setMaxAttempts(1);
        definition.setMaxInstancesPerHour(50);
        definition.setStepsJson("[{\"stepNo\":1,\"label\":\"提交检验结果\",\"kind\":\"command\","
                + "\"capability\":\"submit_inspection\",\"targetType\":\"INSPECTION_ORDER\"}]");
        return definition;
    }

    @Test
    @DisplayName("手动执行每次使用新的实例键，允许重复手动触发")
    void manualRunUsesFreshKey() {
        AiTaskDefinition definition = inboundDefinition();
        when(definitionMapper.selectById(1L)).thenReturn(definition);
        when(notificationSupport.departmentManager(anyString())).thenReturn(null);

        service.run(1L, 1L);
        service.run(1L, 1L);

        assertEquals(2, insertedInstances.size(), "手动执行两次应产生两个实例");
        assertFalse(insertedInstances.get(0).getInstanceKey()
                .equals(insertedInstances.get(1).getInstanceKey()), "手动实例键应不同");
        verify(instanceMapper, times(2)).insert(any(AiTaskInstance.class));
    }

    @Test
    @DisplayName("建实例时按定义的超时分钟数写下截止时间")
    void executeWritesDeadlineFromDefinitionTimeout() {
        AiTaskDefinition definition = inboundDefinition();
        definition.setTimeoutMinutes(15);
        when(definitionMapper.selectById(1L)).thenReturn(definition);
        when(notificationSupport.departmentManager(anyString())).thenReturn(null);

        service.run(1L, 1L);

        AiTaskInstance created = insertedInstances.get(0);
        assertNotNull(created.getDeadlineTime(), "实例必须带截止时间，否则超时收敛无从判断");
        assertEquals(created.getStartedTime().plusMinutes(15), created.getDeadlineTime());
    }

    @Test
    @DisplayName("定义没写超时分钟数时按 30 分钟兜底")
    void executeFallsBackToDefaultTimeout() {
        AiTaskDefinition definition = inboundDefinition();
        when(definitionMapper.selectById(1L)).thenReturn(definition);
        when(notificationSupport.departmentManager(anyString())).thenReturn(null);

        service.run(1L, 1L);

        AiTaskInstance created = insertedInstances.get(0);
        assertEquals(created.getStartedTime().plusMinutes(30), created.getDeadlineTime());
    }

    @Test
    @DisplayName("手动触发时今日步骤调用量达上限也要拦住并说明原因")
    void dailyStepLimitStopsManualRun() {
        AiTaskDefinition definition = inboundDefinition();
        definition.setMaxStepCallsPerDay(5);
        when(definitionMapper.selectById(1L)).thenReturn(definition);
        when(stepMapper.countStepsSince(eq(1L), any())).thenReturn(5L);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.run(1L, 1L));

        assertTrue(exception.getMessage().contains("步骤调用量已达上限"),
                "手动触发超限也要说清原因，实际：" + exception.getMessage());
        assertTrue(insertedInstances.isEmpty(), "超限时不应该再建实例");
    }

    @Test
    @DisplayName("今日步骤调用量达上限时事件不再执行，并落一条带原因的跳过实例")
    void dailyStepLimitStopsEventExecution() {
        AiTaskDefinition definition = inboundDefinition();
        definition.setMaxStepCallsPerDay(500);
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(definition));
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(stepMapper.countStepsSince(eq(1L), any())).thenReturn(600L);

        int executed = service.runByEvent("WMS_INBOUND_PENDING", 9L, "WMS_INBOUND_ORDER", "IN-9");

        assertEquals(0, executed, "达上限当天不应再真的执行业务步骤");
        assertEquals(1, insertedInstances.size());
        assertEquals("SKIPPED", insertedInstances.get(0).getStatus());
        assertTrue(insertedInstances.get(0).getSummary().contains("步骤调用量已达上限"),
                "跳过原因要写清是调用量上限，实际：" + insertedInstances.get(0).getSummary());
    }

    @Test
    @DisplayName("取消实例后落为 CANCELLED 并保留取消原因")
    void cancelMarksInstanceCancelled() {
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceId(31L);
        instance.setInstanceNo("AI-TASK-31");
        instance.setStatus("WAITING_INPUT");
        instance.setVersion(0);
        when(instanceMapper.selectById(31L)).thenReturn(instance);

        Map<String, Object> data = service.cancel(31L, 1L, "单据已作废，不用再提交了");

        assertEquals("CANCELLED", data.get("status"));
        ArgumentCaptor<AiTaskInstance> captor = ArgumentCaptor.forClass(AiTaskInstance.class);
        verify(instanceMapper).updateById(captor.capture());
        assertEquals("CANCELLED", captor.getValue().getStatus());
        assertTrue(captor.getValue().getSummary().contains("单据已作废"));
        assertNotNull(captor.getValue().getFinishedTime(), "取消也要收尾时间，否则列表里会一直显示在跑");
    }

    @Test
    @DisplayName("已成功的实例不允许取消")
    void cancelRejectsSucceededInstance() {
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceId(32L);
        instance.setStatus("SUCCEEDED");
        when(instanceMapper.selectById(32L)).thenReturn(instance);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.cancel(32L, 1L, "试试"));

        assertTrue(exception.getMessage().contains("已经执行成功"));
        verify(instanceMapper, never()).updateById(any(AiTaskInstance.class));
    }

    @Test
    @DisplayName("超时收敛把过了截止时间还在跑的实例标成 TIMEOUT 并留下续跑点")
    void expireOverdueMarksRunningInstanceTimedOut() {
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceId(41L);
        instance.setInstanceNo("AI-TASK-41");
        instance.setStatus("RUNNING");
        instance.setCurrentStepNo(2);
        instance.setResumeStepNo(3);
        instance.setDeadlineTime(java.time.LocalDateTime.now().minusMinutes(5));
        instance.setVersion(0);
        when(instanceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(instance));

        int expired = service.expireOverdue();

        assertEquals(1, expired);
        ArgumentCaptor<AiTaskInstance> captor = ArgumentCaptor.forClass(AiTaskInstance.class);
        verify(instanceMapper).updateById(captor.capture());
        assertEquals("TIMEOUT", captor.getValue().getStatus());
        assertEquals(3, captor.getValue().getResumeStepNo(), "已跑完第 2 步，续跑点应是第 3 步");
    }

    @Test
    @DisplayName("等待补充的实例超时收敛后仍从卡住的那一步续跑")
    void expireOverdueKeepsWaitingStepAsResumePoint() {
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceId(42L);
        instance.setInstanceNo("AI-TASK-42");
        instance.setStatus("RUNNING");
        instance.setCurrentStepNo(1);
        instance.setResumeStepNo(1);
        instance.setWaitingReason("本方案要求逐件全检，缺少每件产品的实际检验结果，请补齐后再生效");
        instance.setDeadlineTime(java.time.LocalDateTime.now().minusMinutes(5));
        instance.setVersion(0);
        when(instanceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(instance));

        assertEquals(1, service.expireOverdue());

        ArgumentCaptor<AiTaskInstance> captor = ArgumentCaptor.forClass(AiTaskInstance.class);
        verify(instanceMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getResumeStepNo(),
                "等人补齐的是第 1 步，不能顺推成第 2 步，否则会跳过卡住的那步");
    }

    @Test
    @DisplayName("等待补充的实例超过截止时间也要收敛为超时")
    void expireOverdueAlsoConvergesWaitingInputInstance() {
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceId(43L);
        instance.setInstanceNo("AI-TASK-43");
        instance.setStatus("WAITING_INPUT");
        instance.setCurrentStepNo(1);
        instance.setResumeStepNo(1);
        instance.setWaitingReason("本方案要求逐件全检，缺少每件产品的实际检验结果，请补齐后再生效");
        instance.setDeadlineTime(java.time.LocalDateTime.now().minusMinutes(5));
        instance.setVersion(0);
        when(instanceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(instance));

        assertEquals(1, service.expireOverdue(),
                "等人补齐的实例不收敛就会永远挂着，等于没有超时保护");

        ArgumentCaptor<AiTaskInstance> captor = ArgumentCaptor.forClass(AiTaskInstance.class);
        verify(instanceMapper).updateById(captor.capture());
        assertEquals("TIMEOUT", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("没有超时实例时收敛返回 0 且不写库")
    void expireOverdueDoesNothingWhenNothingOverdue() {
        when(instanceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertEquals(0, service.expireOverdue());
        verify(instanceMapper, never()).updateById(any(AiTaskInstance.class));
    }

    @Test
    @DisplayName("已取消的实例不能续跑，要重新触发")
    void resumeRejectsCancelledInstance() {
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceId(51L);
        instance.setStatus("CANCELLED");
        when(instanceMapper.selectById(51L)).thenReturn(instance);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resume(51L, 1L));

        assertTrue(exception.getMessage().contains("已被取消"));
    }

    @Test
    @DisplayName("处置方案落成任务时先下达来源计划、再下达组件工单、最后复查开工条件")
    void startPlanBecomesTaskDefinitionInTheRightOrder() {
        AiDataQueryMapper dataQueryMapper = mock(AiDataQueryMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "dataQueryMapper", dataQueryMapper);

        Map<String, Object> header = new java.util.LinkedHashMap<>();
        header.put("workOrderId", 5L);
        header.put("workOrderNo", "WO-5-20261025214222723");
        when(dataQueryMapper.selectWorkOrderHeader("WO-5-20261025214222723")).thenReturn(header);
        when(dataQueryMapper.selectComponentWorkOrders(5L)).thenReturn(List.of(
                component("WO-C-6-20261025214222723", 6L, 55L, "PLN-C-5-19-1", "DRAFT"),
                component("WO-C-7-20261025214222723", 7L, 56L, "PLN-C-5-20-1", "DRAFT")));
        when(dataQueryService.queryWorkOrderReadiness("WO-5-20261025214222723"))
                .thenReturn(readinessResult(true));

        Map<String, Object> data = service.createDefinitionFromStartPlan("WO-5-20261025214222723", 1L);

        assertEquals(2, data.get("planCount"), "两张来源计划都还没生效");
        assertEquals(5, data.get("stepCount"), "2 张计划 + 2 张工单 + 1 次复查");
        assertEquals("DRAFT", data.get("status"), "默认建成草稿，等用户确认后再启用");

        AiTaskDefinition saved = insertedDefinitions.get(0);
        String steps = saved.getStepsJson();
        assertTrue(steps.indexOf("release_plan") < steps.indexOf("release_work_order"),
                "来源计划必须先于组件工单，否则下达工单会被前置检查拦住");
        assertTrue(steps.indexOf("release_work_order") < steps.indexOf("readiness_recheck"),
                "复查要排在所有下达动作之后");
        assertTrue(steps.contains("WO-5-20261025214222723"), "最后一步复查的是父工单");
    }

    @Test
    @DisplayName("工单没有待办的前置条件时不生成空任务")
    void startPlanWithoutPendingStepsRefusesToBuildAnEmptyTask() {
        AiDataQueryMapper dataQueryMapper = mock(AiDataQueryMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "dataQueryMapper", dataQueryMapper);

        Map<String, Object> header = new java.util.LinkedHashMap<>();
        header.put("workOrderId", 9L);
        header.put("workOrderNo", "WO-9-20261025214348845");
        when(dataQueryMapper.selectWorkOrderHeader("WO-9-20261025214348845")).thenReturn(header);
        when(dataQueryMapper.selectComponentWorkOrders(9L)).thenReturn(List.of());
        when(dataQueryService.queryWorkOrderReadiness("WO-9-20261025214348845"))
                .thenReturn(readinessResult(false));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createDefinitionFromStartPlan("WO-9-20261025214348845", 1L));

        assertTrue(exception.getMessage().contains("不需要生成处置任务"));
    }

    @Test
    @DisplayName("下达计划与下达工单步骤都走领域服务并把单号写进流水")
    void releaseStepsCallDomainServices() {
        AiTaskDefinition definition = releaseDefinition();
        when(definitionMapper.selectById(1L)).thenReturn(definition);
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanId(55L);
        plan.setPlanNo("PLN-C-5-19-1");
        when(productionPlanService.releasePlan(eq(55L), eq(1L), anyString())).thenReturn(plan);

        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderId(6L);
        order.setWorkOrderNo("WO-C-6-20261025214222723");
        when(workOrderService.releaseWorkOrder(eq(6L), eq(1L), anyString())).thenReturn(order);

        Map<String, Object> instance = service.run(1L, 1L);

        assertEquals("SUCCEEDED", instance.get("status"), "三步都成功后实例应为成功");
        assertEquals(2, insertedSteps.size(), "两个下达步骤各落一条流水");
        assertTrue(insertedSteps.get(0).getMessage().contains("PLN-C-5-19-1"),
                "计划下达后要把计划号写进流水，便于追溯");
        assertTrue(insertedSteps.get(1).getMessage().contains("WO-C-6-20261025214222723"));
    }

    private AiTaskDefinition releaseDefinition() {
        AiTaskDefinition definition = new AiTaskDefinition();
        definition.setDefinitionId(1L);
        definition.setDefinitionNo("TASK-RELEASE-PLAN");
        definition.setDefinitionName("下达来源计划与组件工单");
        definition.setTriggerType("MANUAL");
        definition.setStatus("ENABLED");
        definition.setMaxAttempts(1);
        definition.setMaxInstancesPerHour(50);
        definition.setStepsJson("[{\"stepNo\":1,\"label\":\"下达来源生产计划 #55\",\"kind\":\"command\","
                + "\"capability\":\"release_plan\",\"targetType\":\"PRODUCTION_PLAN\",\"bizId\":55},"
                + "{\"stepNo\":2,\"label\":\"下达组件工单 WO-C-6\",\"kind\":\"command\","
                + "\"capability\":\"release_work_order\",\"targetType\":\"WORK_ORDER\",\"bizId\":6}]");
        return definition;
    }

    @Test
    void blockedReadinessWaitsForInputInsteadOfClaimingSuccess() {
        AiTaskDefinition definition=releaseDefinition();
        definition.setStepsJson("[{\"stepNo\":1,\"label\":\"复查开工\",\"kind\":\"query\",\"capability\":\"readiness_recheck\",\"targetType\":\"WORK_ORDER\",\"targetNo\":\"B-WO-20261027-001\"}]");
        when(definitionMapper.selectById(1L)).thenReturn(definition);
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(dataQueryService.queryWorkOrderReadiness("B-WO-20261027-001")).thenReturn(readinessResult(true));
        Map<String,Object> instance=service.run(1L,1L);
        assertEquals("WAITING_INPUT",instance.get("status"));
        assertEquals(1,instance.get("resumeStepNo"));
        assertTrue(insertedSteps.get(0).getMessage().contains("未通过"));
    }

    @Test
    void allPassedReadinessCanCompleteSuccessfully() {
        AiTaskDefinition definition=releaseDefinition();
        definition.setStepsJson("[{\"stepNo\":1,\"label\":\"复查开工\",\"kind\":\"query\",\"capability\":\"readiness_recheck\",\"targetType\":\"WORK_ORDER\",\"targetNo\":\"B-WO-20261027-001\"}]");
        when(definitionMapper.selectById(1L)).thenReturn(definition);
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(dataQueryService.queryWorkOrderReadiness("B-WO-20261027-001")).thenReturn(readinessResult(false));
        assertEquals("SUCCEEDED",service.run(1L,1L).get("status"));
    }

    @Test
    void alreadyReleasedStepsDoNotCallReleaseAgain() {
        when(definitionMapper.selectById(1L)).thenReturn(releaseDefinition());
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        var plan=new MfgProductionPlan();plan.setPlanId(55L);plan.setPlanNo("B-PL-20261027-001");plan.setStatus("RELEASED");plan.setDeletedFlag(0);
        var order=new MfgWorkOrder();order.setWorkOrderId(6L);order.setWorkOrderNo("B-WO-20261027-002");order.setStatus("RUNNING");order.setDeletedFlag(0);
        when(productionPlanService.getPlanById(55L)).thenReturn(plan);
        when(workOrderService.getWorkOrderById(6L)).thenReturn(order);
        assertEquals("SUCCEEDED",service.run(1L,1L).get("status"));
        verify(productionPlanService,never()).releasePlan(any(),any(),anyString());
        verify(workOrderService,never()).releaseWorkOrder(any(),any(),anyString());
    }

    @Test
    void releasedComponentsDoNotCreateRedundantReleaseTasks() {
        var mapper=mock(AiDataQueryMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"dataQueryMapper",mapper);
        when(mapper.selectWorkOrderHeader("B-WO-20261027-001")).thenReturn(Map.of("workOrderId",5L,"workOrderNo","B-WO-20261027-001"));
        var child=component("B-WO-20261027-002",6L,55L,"B-PL-20261027-001","RELEASED");child.put("status","RELEASED");
        when(mapper.selectComponentWorkOrders(5L)).thenReturn(List.of(child));
        when(dataQueryService.queryWorkOrderReadiness("B-WO-20261027-001")).thenReturn(readinessResult(true));
        assertThrows(BusinessException.class,()->service.createDefinitionFromStartPlan("B-WO-20261027-001",1L));
        assertTrue(insertedDefinitions.isEmpty());
    }

    @Test
    void definitionCreationResolvesOldWorkOrderBeforeLookup() {
        var mapper=mock(AiDataQueryMapper.class);
        var numbers=mock(com.neuedu.ems.service.sys.BusinessNumberService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"dataQueryMapper",mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"documentNumbers",numbers);
        when(numbers.canonical("OLD-WORK")).thenReturn("B-WO-20261027-001");
        when(mapper.selectWorkOrderHeader("B-WO-20261027-001")).thenReturn(Map.of("workOrderId",5L,"workOrderNo","B-WO-20261027-001"));
        when(mapper.selectComponentWorkOrders(5L)).thenReturn(List.of(component("B-WO-20261027-002",6L,55L,"B-PL-20261027-001","DRAFT")));
        when(dataQueryService.queryWorkOrderReadiness("B-WO-20261027-001")).thenReturn(readinessResult(true));
        assertEquals("B-WO-20261027-001",service.createDefinitionFromStartPlan("OLD-WORK",1L).get("workOrderNo"));
        verify(mapper,never()).selectWorkOrderHeader("OLD-WORK");
    }

    @Test
    void eventAggregateNeverOverridesExplicitPlanAndComponentTargets() {
        var definition=releaseDefinition();definition.setTriggerType("EVENT");definition.setTriggerEventKey("TEST_START_PLAN");
        when(definitionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(definition));
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        var plan=new MfgProductionPlan();plan.setPlanId(55L);plan.setPlanNo("B-PL-20261027-001");
        var order=new MfgWorkOrder();order.setWorkOrderId(6L);order.setWorkOrderNo("B-WO-20261027-002");
        when(productionPlanService.releasePlan(eq(55L),eq(1L),anyString())).thenReturn(plan);
        when(workOrderService.releaseWorkOrder(eq(6L),eq(1L),anyString())).thenReturn(order);
        assertEquals(1,service.runByEvent("TEST_START_PLAN",999L,"WORK_ORDER","B-WO-20261027-999"));
        verify(productionPlanService).releasePlan(eq(55L),eq(1L),anyString());
        verify(workOrderService).releaseWorkOrder(eq(6L),eq(1L),anyString());
        verify(productionPlanService,never()).releasePlan(eq(999L),any(),anyString());
        verify(workOrderService,never()).releaseWorkOrder(eq(999L),any(),anyString());
        assertTrue(insertedSteps.stream().allMatch(s->"SUCCEEDED".equals(s.getStatus())));
    }

    private Map<String, Object> component(String workOrderNo, Long workOrderId, Long planId,
                                          String planNo, String planStatus) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("workOrderNo", workOrderNo);
        row.put("workOrderId", workOrderId);
        row.put("status", "DRAFT");
        row.put("planId", planId);
        row.put("planNo", planNo);
        row.put("planStatus", planStatus);
        return row;
    }

    /* ==================== 确认入库的后置复核 ==================== */

    private AiTaskDefinition confirmInboundDefinition() {
        AiTaskDefinition definition = new AiTaskDefinition();
        definition.setDefinitionId(1L);
        definition.setDefinitionNo("TASK-CONFIRM-INBOUND");
        definition.setDefinitionName("入库单待确认自动确认");
        definition.setTriggerType("MANUAL");
        definition.setStatus("ENABLED");
        definition.setMaxAttempts(1);
        definition.setMaxInstancesPerHour(50);
        definition.setStepsJson("[{\"stepNo\":1,\"label\":\"确认入库\",\"kind\":\"command\","
                + "\"capability\":\"confirm_inbound\",\"targetType\":\"WMS_INBOUND_ORDER\",\"bizId\":53}]");
        return definition;
    }

    private void stubInboundOutcomeMocks(long detailCount, long movementCount) {
        var movementMapper = mock(com.neuedu.ems.mapper.wms.WmsStockMovementMapper.class);
        var detailMapper = mock(com.neuedu.ems.mapper.wms.WmsInboundDetailMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "stockMovementMapper", movementMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "inboundDetailMapper", detailMapper);
        when(detailMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(detailCount);
        when(movementMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(movementCount);
    }

    private WmsInboundOrder approvedInbound() {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setInboundId(53L);
        order.setInboundNo("IN-AITASK-001");
        order.setAuditStatus("APPROVED");
        order.setInboundTime(java.time.LocalDateTime.now());
        return order;
    }

    @Test
    @DisplayName("确认入库后回查流水，明细与流水条数一致才记成功")
    void confirmInboundChecksMovementsAfterConfirm() {
        when(definitionMapper.selectById(1L)).thenReturn(confirmInboundDefinition());
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(inboundOrderMapper.selectById(53L)).thenReturn(submittedInbound()).thenReturn(approvedInbound());
        when(inboundOrderService.confirmInboundOrder(eq(53L), any(), anyString())).thenReturn(true);
        stubInboundOutcomeMocks(2L, 2L);

        Map<String, Object> result = service.run(1L, 1L);

        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals(1, insertedSteps.size());
        assertTrue(insertedSteps.get(0).getMessage().contains("流水与明细条数一致"),
                "成功文案要说明核对过流水，实际：" + insertedSteps.get(0).getMessage());
    }

    @Test
    @DisplayName("确认接口返回成功但入库流水没写全时记为失败，不宣布办成")
    void confirmInboundFailsWhenMovementsMissing() {
        when(definitionMapper.selectById(1L)).thenReturn(confirmInboundDefinition());
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(inboundOrderMapper.selectById(53L)).thenReturn(submittedInbound()).thenReturn(approvedInbound());
        when(inboundOrderService.confirmInboundOrder(eq(53L), any(), anyString())).thenReturn(true);
        stubInboundOutcomeMocks(2L, 0L);

        Map<String, Object> result = service.run(1L, 1L);

        assertEquals("FAILED", result.get("status"));
        assertEquals("FAILED", insertedSteps.get(0).getStatus());
        assertTrue(insertedSteps.get(0).getMessage().contains("后置复核未通过"),
                "要说明是单据已确认但结果没核对通过，实际：" + insertedSteps.get(0).getMessage());
        assertTrue(insertedSteps.get(0).getMessage().contains("入库流水"),
                "要指出缺的是入库流水，实际：" + insertedSteps.get(0).getMessage());
    }

    @Test
    @DisplayName("确认后单据状态没变成 APPROVED 时同样记为失败")
    void confirmInboundFailsWhenStatusUnchanged() {
        when(definitionMapper.selectById(1L)).thenReturn(confirmInboundDefinition());
        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(inboundOrderMapper.selectById(53L)).thenReturn(submittedInbound()).thenReturn(submittedInbound());
        when(inboundOrderService.confirmInboundOrder(eq(53L), any(), anyString())).thenReturn(true);
        stubInboundOutcomeMocks(2L, 2L);

        Map<String, Object> result = service.run(1L, 1L);

        assertEquals("FAILED", result.get("status"));
        assertTrue(insertedSteps.get(0).getMessage().contains("不是已确认"),
                "实际：" + insertedSteps.get(0).getMessage());
    }

    private AiQueryResult readinessResult(boolean blocked) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        Map<String, Object> pass = new java.util.LinkedHashMap<>();
        pass.put("item", "制造准备");
        pass.put("passed", "通过");
        rows.add(pass);
        if (blocked) {
            Map<String, Object> fail = new java.util.LinkedHashMap<>();
            fail.put("item", "物料齐套");
            fail.put("passed", "未通过");
            rows.add(fail);
        }
        return new AiQueryResult("开工条件核查", "摘要", List.of(), rows, List.of());
    }
}
