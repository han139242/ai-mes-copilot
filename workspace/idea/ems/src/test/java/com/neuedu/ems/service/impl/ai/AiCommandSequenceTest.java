package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.ai.AiCommandExecution;
import com.neuedu.ems.entity.ai.AiCommandSequence;
import com.neuedu.ems.entity.ai.AiCommandStep;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.ai.AiCommandExecutionMapper;
import com.neuedu.ems.mapper.ai.AiCommandSequenceMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多步序列单元测试：两步都成功、第二步被阻断、第一步成功后第二步失败、续跑、幂等重放、过期。
 */
class AiCommandSequenceTest {

    @Test
    void generatedDispatchNumberIsDeferredUntilFirstStepActuallySucceeds() {
        List<?> specs = ReflectionTestUtils.invokeMethod(service, "buildSequenceSpecs", List.of(
                new com.neuedu.ems.service.ai.AiCommandService.SequenceStepInput("GENERATE_DISPATCH_ORDER", WO_NO, Map.of()),
                new com.neuedu.ems.service.ai.AiCommandService.SequenceStepInput("RELEASE_DISPATCH_ORDER", null, Map.of())),
                "生成派工单然后下达", admin);
        assertNotNull(specs);
        assertEquals(true, ReflectionTestUtils.invokeMethod(specs.get(1), "dependsOnPrevious"));
        List<?> explicit = ReflectionTestUtils.invokeMethod(service, "buildSequenceSpecs", List.of(
                new com.neuedu.ems.service.ai.AiCommandService.SequenceStepInput("GENERATE_DISPATCH_ORDER", WO_NO, Map.of()),
                new com.neuedu.ems.service.ai.AiCommandService.SequenceStepInput("RELEASE_DISPATCH_ORDER", "DO-EXPLICIT", Map.of())),
                "下达明确指定的另一张派工单", admin);
        assertEquals(false, ReflectionTestUtils.invokeMethod(explicit.get(1), "dependsOnPrevious"));
    }

    private static final String WO_NO = "WO-202610240011";
    private static final String OUT_NO = "MI-WO13-20261024101010101";

    private AiCommandServiceImpl service;
    private AiCommandExecutionMapper executionMapper;
    private AiCommandSequenceMapper sequenceMapper;
    private MfgWorkOrderMapper workOrderMapper;
    private MfgWorkOrderMaterialMapper workOrderMaterialMapper;
    private WmsOutboundOrderMapper outboundOrderMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsLocationMapper locationMapper;
    private WmsOutboundOrderService outboundOrderService;
    private SysStatusChangeLogMapper statusChangeLogMapper;
    private WmsStockMovementMapper stockMovementMapper;
    private WmsOutboundDetailMapper outboundDetailMapper;
    private com.neuedu.ems.mapper.wms.WmsInventoryMapper inventoryMapper;
    private com.neuedu.ems.mapper.mfg.MfgSupplyAllocationMapper supplyAllocationMapper;
    /** 后置复核回查的是执行后的库内状态，随步骤推进而变化。 */
    private WmsOutboundOrder outboundState;

    private final AuthorizationContext admin = new AuthorizationContext(7L, 8L, 9L, "ALL",
            Set.of("SUPER_ADMIN"), Set.of("ai:assistant:use"), Set.of());

    /** 命令记录按确认凭据建索引，执行时按凭据回查。 */
    private final Map<String, AiCommandExecution> commandStore = new LinkedHashMap<>();
    /** 序列头只有一条，重放时按 sequence_id 与 idempotency_key 都能查到。 */
    private AiCommandSequence sequence;
    private long commandIdSeed = 5000L;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 取参数前必须先注册实体元数据，否则报 lambda cache 缺失。
        initTableInfo(WmsWarehouse.class, "wmsWarehouse");
        initTableInfo(WmsLocation.class, "wmsLocation");
        initTableInfo(MfgWorkOrder.class, "mfgWorkOrder");
        initTableInfo(WmsOutboundOrder.class, "wmsOutboundOrder");
        initTableInfo(AiCommandExecution.class, "aiCommandExecution");
        initTableInfo(AiCommandSequence.class, "aiCommandSequence");
        service = new AiCommandServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        executionMapper = mock(AiCommandExecutionMapper.class);
        sequenceMapper = mock(AiCommandSequenceMapper.class);
        workOrderMapper = mock(MfgWorkOrderMapper.class);
        workOrderMaterialMapper = mock(MfgWorkOrderMaterialMapper.class);
        outboundOrderMapper = mock(WmsOutboundOrderMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        outboundOrderService = mock(WmsOutboundOrderService.class);
        statusChangeLogMapper = mock(SysStatusChangeLogMapper.class);
        stockMovementMapper = mock(WmsStockMovementMapper.class);
        outboundDetailMapper = mock(WmsOutboundDetailMapper.class);
        inventoryMapper = mock(com.neuedu.ems.mapper.wms.WmsInventoryMapper.class);
        supplyAllocationMapper = mock(com.neuedu.ems.mapper.mfg.MfgSupplyAllocationMapper.class);

        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(service, "sequenceMapper", sequenceMapper);
        ReflectionTestUtils.setField(service, "stepMapper", mock(AiCommandStepMapper.class));
        ReflectionTestUtils.setField(service, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "productionPlanMapper", mock(MfgProductionPlanMapper.class));
        ReflectionTestUtils.setField(service, "workOrderMaterialMapper", workOrderMaterialMapper);
        ReflectionTestUtils.setField(service, "taskMapper", mock(MfgTaskMapper.class));
        ReflectionTestUtils.setField(service, "workReportMapper", mock(MfgWorkReportMapper.class));
        ReflectionTestUtils.setField(service, "outboundOrderMapper", outboundOrderMapper);
        ReflectionTestUtils.setField(service, "outboundDetailMapper", outboundDetailMapper);
        ReflectionTestUtils.setField(service, "inboundOrderMapper", mock(WmsInboundOrderMapper.class));
        ReflectionTestUtils.setField(service, "inboundDetailMapper", mock(WmsInboundDetailMapper.class));
        ReflectionTestUtils.setField(service, "warehouseMapper", warehouseMapper);
        ReflectionTestUtils.setField(service, "locationMapper", locationMapper);
        ReflectionTestUtils.setField(service, "inspectionOrderMapper", mock(QmsInspectionOrderMapper.class));
        ReflectionTestUtils.setField(service, "operationLogMapper", mock(SysOperationLogMapper.class));
        ReflectionTestUtils.setField(service, "statusChangeLogMapper", statusChangeLogMapper);
        ReflectionTestUtils.setField(service, "stockMovementMapper", stockMovementMapper);
        ReflectionTestUtils.setField(service, "inventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(service, "supplyAllocationMapper", supplyAllocationMapper);
        ReflectionTestUtils.setField(service, "workOrderService", mock(MfgWorkOrderService.class));
        ReflectionTestUtils.setField(service, "outboundOrderService", outboundOrderService);
        ReflectionTestUtils.setField(service, "inboundOrderService", mock(WmsInboundOrderService.class));
        ReflectionTestUtils.setField(service, "inspectionOrderService", mock(QmsInspectionOrderService.class));
        ReflectionTestUtils.setField(service, "workReportService", mock(MfgWorkReportService.class));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        AiCommandStepMapper stepMapper = (AiCommandStepMapper) ReflectionTestUtils.getField(service, "stepMapper");
        when(stepMapper.insert(any(AiCommandStep.class))).thenAnswer((Answer<Integer>) invocation -> {
            AiCommandStep step = invocation.getArgument(0);
            step.setStepId(9000L + commandStore.size());
            return 1;
        });
        when(stepMapper.updateById(any(AiCommandStep.class))).thenReturn(1);
        SysOperationLogMapper logMapper = (SysOperationLogMapper) ReflectionTestUtils.getField(service, "operationLogMapper");
        when(logMapper.insert(any(com.neuedu.ems.entity.sys.SysOperationLog.class))).thenReturn(1);

        when(executionMapper.insert(any(AiCommandExecution.class))).thenAnswer((Answer<Integer>) invocation -> {
            AiCommandExecution record = invocation.getArgument(0);
            record.setCommandId(++commandIdSeed);
            commandStore.put(record.getConfirmationId(), record);
            return 1;
        });
        // 挂序列号与执行时的状态更新都要落到内存里的同一条记录上。
        when(executionMapper.updateById(any(AiCommandExecution.class))).thenAnswer((Answer<Integer>) invocation -> {
            AiCommandExecution update = invocation.getArgument(0);
            AiCommandExecution stored = findCommand(update.getCommandId());
            if (stored == null) {
                return 1;
            }
            if (update.getSequenceId() != null) {
                stored.setSequenceId(update.getSequenceId());
                stored.setSequenceStepNo(update.getSequenceStepNo());
            }
            if (update.getStatus() != null) {
                stored.setStatus(update.getStatus());
            }
            if (update.getResultJson() != null) {
                stored.setResultJson(update.getResultJson());
            }
            if (update.getIdempotencyKey() != null) {
                stored.setIdempotencyKey(update.getIdempotencyKey());
            }
            if (update.getExecutedAt() != null) {
                stored.setExecutedAt(update.getExecutedAt());
            }
            return 1;
        });
        when(executionMapper.selectOne(any())).thenAnswer((Answer<AiCommandExecution>) invocation -> {
            List<Object> values = wrapperValues(invocation.getArgument(0));
            // 先按确认凭据查；查不到再按序列号 + 步骤号回读已成功步骤。
            for (Object value : values) {
                if (value instanceof String text && commandStore.containsKey(text)) {
                    return commandStore.get(text);
                }
            }
            String planId = values.stream().filter(String.class::isInstance).map(String.class::cast)
                    .findFirst().orElse(null);
            if (planId == null) {
                return null;
            }
            // 条件里还有 deleted_flag=0 这类数字，不能直接取第一个数字当步骤号。
            for (Object value : values) {
                if (!(value instanceof Number number)) {
                    continue;
                }
                int stepNo = number.intValue();
                AiCommandExecution hit = commandStore.values().stream()
                        .filter(item -> planId.equals(item.getSequenceId())
                                && item.getSequenceStepNo() != null
                                && item.getSequenceStepNo() == stepNo)
                        .reduce((first, second) -> second).orElse(null);
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        });

        when(sequenceMapper.insert(any(AiCommandSequence.class))).thenAnswer((Answer<Integer>) invocation -> {
            sequence = invocation.getArgument(0);
            return 1;
        });
        when(sequenceMapper.updateById(any(AiCommandSequence.class))).thenReturn(1);
        when(sequenceMapper.selectById(any())).thenAnswer((Answer<AiCommandSequence>) invocation -> sequence);
        when(sequenceMapper.selectOne(any())).thenAnswer((Answer<AiCommandSequence>) invocation -> {
            List<Object> values = wrapperValues(invocation.getArgument(0));
            if (sequence == null) {
                return null;
            }
            boolean hit = values.stream().anyMatch(value -> value instanceof String text
                    && (text.equals(sequence.getSequenceId()) || text.equals(sequence.getIdempotencyKey())));
            return hit ? sequence : null;
        });
    }

    @Test
    void previewMultiStepSplitsTwoStepsAndMarksDependency() {
        mockIssueStage(true);
        mockTransferStage(true);

        Map<String, Object> preview = service.previewSequence(
                "给 " + WO_NO + " 生成领料单然后转到线边仓", "conv-1", "WORKBENCH", Map.of(), admin);

        assertEquals(2, preview.get("totalSteps"));
        assertEquals(Boolean.TRUE, preview.get("overallCanExecute"));
        List<Map<String, Object>> steps = stepsOf(preview);
        assertEquals(AiCommandServiceImpl.ACTION_GENERATE_MATERIAL_ISSUE, steps.get(0).get("action"));
        assertEquals(WO_NO, steps.get(0).get("targetNo"));
        assertEquals(AiCommandServiceImpl.ACTION_TRANSFER_TO_LINE_SIDE, steps.get(1).get("action"));
        assertEquals(Boolean.TRUE, steps.get(1).get("dependsOnPrevious"));
        // 依赖上一步产出的步骤此时还没有单号，但不能判成失败。
        assertNull(steps.get(1).get("targetNo"));
        assertEquals(Boolean.TRUE, steps.get(1).get("canExecute"));
        assertNotNull(preview.get("planId"));
        assertEquals("PREVIEW", sequence.getStatus());
        assertEquals(2, sequence.getTotalSteps());
    }

    @Test
    void previewSequenceIfMultiIgnoresSingleStepCommand() {
        assertNull(service.previewSequenceIfMulti("下达工单 " + WO_NO, "conv-1", "WORKBENCH", Map.of(), admin));
    }

    @Test
    void previewBlocksOverallWhenSecondStepTargetIsNotExecutable() {
        mockIssueStage(true);
        // 第二步显式指定一张已确认过的领料单，前置条件必然不通过。
        WmsOutboundOrder approved = outbound(BusinessStatus.Document.APPROVED);
        when(outboundOrderMapper.selectOne(any())).thenReturn(approved);
        mockLineSideLocation();

        Map<String, Object> preview = service.previewSequence(
                "给 " + WO_NO + " 生成领料单，然后把 " + OUT_NO + " 转到线边仓", "conv-1", "WORKBENCH",
                Map.of(), admin);

        assertEquals(Boolean.FALSE, preview.get("overallCanExecute"));
        List<Map<String, Object>> steps = stepsOf(preview);
        assertEquals(Boolean.FALSE, steps.get(1).get("canExecute"));
        assertNotNull(steps.get(1).get("blockingReason"));
    }

    @Test
    void executeSequenceRunsBothStepsAndSucceeds() {
        mockIssueStage(true);
        mockTransferStage(true);

        Map<String, Object> preview = service.previewSequence(
                "给 " + WO_NO + " 生成领料单然后转到线边仓", "conv-1", "WORKBENCH", Map.of(), admin);
        String planId = String.valueOf(preview.get("planId"));
        String firstConfirmation = String.valueOf(stepsOf(preview).get(0).get("confirmationId"));
        Long approvedCommandId = commandStore.get(firstConfirmation).getCommandId();

        Map<String, Object> result = service.executeSequence(
                new AiCommandService.AiCommandSequenceExecuteRequest(planId, "seq-key-1", null, "多步实测"), admin);

        assertEquals("SUCCEEDED", result.get("status"), String.valueOf(result.get("errorMessage")));
        assertEquals(2, result.get("totalSteps"));
        assertEquals(2, result.get("executedSteps"), String.valueOf(stepsOf(result)));
        List<Map<String, Object>> steps = stepsOf(result);
        assertEquals(approvedCommandId, ((Number) steps.get(0).get("commandId")).longValue());
        assertEquals(Boolean.TRUE, steps.get(0).get("ok"));
        assertEquals(Boolean.TRUE, steps.get(1).get("ok"));
        // 第二步的目标单号来自第一步真实产出的领料单。
        assertEquals(OUT_NO, steps.get(1).get("targetNo"));
        assertEquals(OUT_NO, steps.get(1).get("bizNo"));
        verify(outboundOrderService).generateMaterialIssue(11L, 1L, 7L, "多步实测", null);
        verify(outboundOrderService).transferMaterialIssueToLineSide(40L, 2L, 7L, "多步实测", null);
    }

    @Test
    void executeSequenceMarksPartialFailedAndKeepsFirstStepResult() {
        mockIssueStage(true);
        mockTransferStage(false);

        Map<String, Object> preview = service.previewSequence(
                "给 " + WO_NO + " 生成领料单然后转到线边仓", "conv-1", "WORKBENCH", Map.of(), admin);
        String planId = String.valueOf(preview.get("planId"));

        Map<String, Object> result = service.executeSequence(
                new AiCommandService.AiCommandSequenceExecuteRequest(planId, "seq-key-2", null, null), admin);

        assertEquals("PARTIAL_FAILED", result.get("status"));
        assertEquals(1, result.get("executedSteps"));
        List<Map<String, Object>> steps = stepsOf(result);
        assertEquals(Boolean.TRUE, steps.get(0).get("ok"));
        assertEquals(OUT_NO, steps.get(0).get("bizNo"));
        assertEquals(Boolean.FALSE, steps.get(1).get("ok"));
        assertNotNull(result.get("errorMessage"));
        // 第一步的业务已经提交，绝不回滚。
        verify(outboundOrderService).generateMaterialIssue(11L, 1L, 7L, null, null);
    }

    @Test
    void executeSequenceResumesFromSecondStepWithoutRedoingFirst() {
        mockIssueStage(true);
        mockTransferStage(true);

        Map<String, Object> preview = service.previewSequence(
                "给 " + WO_NO + " 生成领料单然后转到线边仓", "conv-1", "WORKBENCH", Map.of(), admin);
        String planId = String.valueOf(preview.get("planId"));
        // 造出「第一步已成功、第二步没办」的部分失败现场。
        seedSucceededFirstStep(planId);

        Map<String, Object> result = service.executeSequence(
                new AiCommandService.AiCommandSequenceExecuteRequest(planId, "seq-key-3", 2, null), admin);

        assertEquals("SUCCEEDED", result.get("status"), String.valueOf(result.get("errorMessage")));
        assertEquals(2, result.get("executedSteps"));
        // 续跑从第 2 步开始，第一步的领料单不能再生成一次。
        verify(outboundOrderService, never()).generateMaterialIssue(anyLong(), anyLong(), anyLong(), any(), any());
        verify(outboundOrderService).transferMaterialIssueToLineSide(40L, 2L, 7L, null, null);
    }

    @Test
    void executeSequenceReplaysWhenSameKeyIsUsedAgain() {
        mockIssueStage(true);
        mockTransferStage(true);

        Map<String, Object> preview = service.previewSequence(
                "给 " + WO_NO + " 生成领料单然后转到线边仓", "conv-1", "WORKBENCH", Map.of(), admin);
        String planId = String.valueOf(preview.get("planId"));
        service.executeSequence(
                new AiCommandService.AiCommandSequenceExecuteRequest(planId, "seq-key-4", null, null), admin);

        Map<String, Object> replay = service.executeSequence(
                new AiCommandService.AiCommandSequenceExecuteRequest(planId, "seq-key-4", null, null), admin);

        assertEquals(Boolean.TRUE, replay.get("replayed"));
        assertEquals("SUCCEEDED", replay.get("status"));
        // 重放不再重复建单：整条链路只生成过一次领料单。
        verify(outboundOrderService, times(1)).generateMaterialIssue(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void executeSequenceRejectsExpiredPreview() {
        mockIssueStage(true);
        mockTransferStage(true);

        Map<String, Object> preview = service.previewSequence(
                "给 " + WO_NO + " 生成领料单然后转到边仓", "conv-1", "WORKBENCH", Map.of(), admin);
        String planId = String.valueOf(preview.get("planId"));
        sequence.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        BusinessException error = assertThrows(BusinessException.class, () -> service.executeSequence(
                new AiCommandService.AiCommandSequenceExecuteRequest(planId, null, null, null), admin));

        assertTrue(error.getMessage().contains("有效期"));
        assertEquals("EXPIRED", sequence.getStatus());
    }

    @Test
    void getSequenceReturnsStepStatusFromBackend() {
        mockIssueStage(true);
        mockTransferStage(true);

        Map<String, Object> preview = service.previewSequence(
                "给 " + WO_NO + " 生成领料单然后转到线边仓", "conv-1", "WORKBENCH", Map.of(), admin);
        String planId = String.valueOf(preview.get("planId"));
        service.executeSequence(
                new AiCommandService.AiCommandSequenceExecuteRequest(planId, "seq-key-5", null, null), admin);

        Map<String, Object> detail = service.getSequence(planId);

        assertEquals("SUCCEEDED", detail.get("status"));
        assertEquals(2, detail.get("executedSteps"));
        List<Map<String, Object>> steps = stepsOf(detail);
        assertEquals("SUCCEEDED", steps.get(0).get("status"));
        assertEquals(OUT_NO, steps.get(1).get("bizNo"));
        assertNotNull(steps.get(1).get("bizRef"));
    }

    /* ---------- 场景辅助 ---------- */

    /** 第一步领料：工单已下达、有缺口、原料仓可用。 */
    private void mockIssueStage(boolean generated) {
        when(workOrderMapper.selectOne(any())).thenReturn(releasedOrder());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(1L);
        // 缺料核对要真读到用料明细与可用库存，只给条数会被判成「无法核对库存」。
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of(material()));
        when(supplyAllocationMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventory(19L, "100")));
        when(outboundOrderMapper.selectCount(any())).thenReturn(0L);
        when(warehouseMapper.selectList(any())).thenReturn(List.of(rawWarehouse(), lineSideWarehouse()));
        // 按仓库ID返回对应仓库，否则第二步查线边仓会拿到原料仓。
        when(warehouseMapper.selectOne(any())).thenAnswer((Answer<WmsWarehouse>) invocation -> {
            List<Object> values = wrapperValues(invocation.getArgument(0));
            boolean lineSide = values.stream().anyMatch(value -> value instanceof Number number
                    && number.longValue() == 2L);
            return lineSide ? lineSideWarehouse() : rawWarehouse();
        });
        // 后置复核回查的库内状态：领料单刚生成时是待确认，转线边后由 mockTransferStage 改写。
        outboundState = outbound(BusinessStatus.Document.SUBMITTED);
        when(outboundOrderMapper.selectById(anyLong())).thenAnswer(
                (Answer<WmsOutboundOrder>) invocation -> outboundState);
        when(outboundDetailMapper.selectCount(any())).thenReturn(1L);
        when(stockMovementMapper.selectCount(any())).thenReturn(2L);
        when(statusChangeLogMapper.selectCount(any())).thenReturn(1L);
        if (generated) {
            when(outboundOrderService.generateMaterialIssue(anyLong(), anyLong(), anyLong(), any(), any()))
                    .thenReturn(outbound(BusinessStatus.Document.SUBMITTED));
        }
    }

    /** 第二步转线边：目标领料单待确认、关联工单已下达、线边库位可用。 */
    private void mockTransferStage(boolean ok) {
        when(outboundOrderMapper.selectOne(any())).thenReturn(outbound(BusinessStatus.Document.SUBMITTED));
        mockLineSideLocation();
        // 转线边没有布尔返回值：成功即正常返回，被拒绝时抛业务异常。
        if (ok) {
            doAnswer(invocation -> {
                // 领域动作真正生效后，库里的领料单已经是已确认。
                outboundState = outbound(BusinessStatus.Document.APPROVED);
                return null;
            }).when(outboundOrderService)
                    .transferMaterialIssueToLineSide(anyLong(), anyLong(), anyLong(), any(), any());
            return;
        }
        doThrow(new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "线边库位不可用"))
                .when(outboundOrderService)
                .transferMaterialIssueToLineSide(anyLong(), anyLong(), anyLong(), any(), any());
    }

    private void mockLineSideLocation() {
        when(locationMapper.selectList(any())).thenReturn(List.of(lineSideLocation()));
        when(locationMapper.selectOne(any())).thenReturn(lineSideLocation());
    }

    /** 造一个「第一步已成功、第二步待办」的部分失败现场。 */
    private void seedSucceededFirstStep(String planId) {
        AiCommandExecution done = new AiCommandExecution();
        done.setCommandId(1L);
        done.setConfirmationId("seed-confirmation");
        done.setAction(AiCommandServiceImpl.ACTION_GENERATE_MATERIAL_ISSUE);
        done.setTargetNo(WO_NO);
        done.setStatus("SUCCEEDED");
        done.setSequenceId(planId);
        done.setSequenceStepNo(1);
        done.setResultJson("{\"bizNo\":\"" + OUT_NO + "\",\"bizId\":40,\"summary\":\"已生成领料单\"}");
        commandStore.put("seed-confirmation", done);
        sequence.setStatus("PARTIAL_FAILED");
        sequence.setExecutedSteps(1);
        sequence.setIdempotencyKey("seq-key-3");
    }

    private MfgWorkOrder releasedOrder() {
        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderId(11L);
        order.setWorkOrderNo(WO_NO);
        order.setPlanId(6L);
        order.setBomId(3L);
        order.setRouteId(4L);
        order.setProductId(2L);
        order.setStatus(BusinessStatus.Document.RELEASED);
        order.setDeletedFlag(0);
        order.setVersion(0);
        return order;
    }

    private MfgProductionPlan releasedPlan() {
        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanId(6L);
        plan.setStatus(BusinessStatus.Document.RELEASED);
        plan.setPlannedStartTime(LocalDateTime.now().minusMinutes(1));
        plan.setPlannedEndTime(LocalDateTime.now().plusHours(6));
        plan.setVersion(0);
        return plan;
    }

    private WmsOutboundOrder outbound(String auditStatus) {
        WmsOutboundOrder outbound = new WmsOutboundOrder();
        outbound.setOutboundId(40L);
        outbound.setOutboundNo(OUT_NO);
        outbound.setOutboundType("MATERIAL_ISSUE");
        outbound.setAuditStatus(auditStatus);
        outbound.setWarehouseId(1L);
        outbound.setWorkOrderId(11L);
        outbound.setDeletedFlag(0);
        outbound.setVersion(0);
        return outbound;
    }

    private WmsWarehouse rawWarehouse() {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setWarehouseId(1L);
        warehouse.setWarehouseCode("LAMP-WH-RAW");
        warehouse.setWarehouseName("原料仓");
        warehouse.setWarehouseType("RAW");
        warehouse.setStatus(BusinessStatus.Common.ENABLE);
        warehouse.setDeletedFlag(0);
        return warehouse;
    }

    private WmsWarehouse lineSideWarehouse() {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setWarehouseId(2L);
        warehouse.setWarehouseCode("LAMP-WH-LINE");
        warehouse.setWarehouseName("线边仓");
        warehouse.setWarehouseType("LINE_SIDE");
        warehouse.setStatus(BusinessStatus.Common.ENABLE);
        warehouse.setDeletedFlag(0);
        return warehouse;
    }

    private WmsLocation lineSideLocation() {
        WmsLocation location = new WmsLocation();
        location.setLocationId(2L);
        location.setLocationCode("LAMP-LOC-LINE");
        location.setLocationName("线边库位");
        location.setWarehouseId(2L);
        location.setLocationType("NORMAL");
        location.setStatus(BusinessStatus.Common.ENABLE);
        location.setDeletedFlag(0);
        return location;
    }

    private MfgWorkOrderMaterial material() {
        MfgWorkOrderMaterial item = new MfgWorkOrderMaterial();
        item.setWorkOrderId(11L);
        item.setMaterialId(19L);
        item.setIssuedQuantity(BigDecimal.ZERO);
        item.setRequiredQuantity(new BigDecimal("10"));
        item.setDeletedFlag(0);
        return item;
    }

    /** 原料仓里该物料的可领数量。 */
    private com.neuedu.ems.entity.wms.WmsInventory inventory(Long materialId, String available) {
        com.neuedu.ems.entity.wms.WmsInventory item = new com.neuedu.ems.entity.wms.WmsInventory();
        item.setWarehouseId(1L);
        item.setMaterialId(materialId);
        item.setAvailableQuantity(new BigDecimal(available));
        item.setWorkOrderId(0L);
        item.setDeletedFlag(0);
        return item;
    }

    private AiCommandExecution findCommand(Long commandId) {
        if (commandId == null) {
            return null;
        }
        return commandStore.values().stream()
                .filter(item -> commandId.equals(item.getCommandId()))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stepsOf(Map<String, Object> data) {
        Object steps = data.get("steps");
        return steps instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
    }

    /** 注册实体元数据，让 LambdaQueryWrapper 能取出参数值。 */
    private void initTableInfo(Class<?> entityType, String resource) {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), resource),
                entityType);
    }

    /** 取出 wrapper 里的参数值；LambdaQueryWrapper 不能强行渲染 SQL，优先直接读参数表。 */
    private List<Object> wrapperValues(Object wrapper) {
        if (!(wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> abstractWrapper)) {
            return List.of();
        }
        if (!abstractWrapper.getParamNameValuePairs().isEmpty()) {
            return new ArrayList<>(abstractWrapper.getParamNameValuePairs().values());
        }
        try {
            abstractWrapper.getSqlSegment();
        } catch (RuntimeException ignored) {
            // 纯单元测试没有初始化 MyBatis-Plus 的 lambda 缓存，渲染失败时按无参数处理。
        }
        return new ArrayList<>(abstractWrapper.getParamNameValuePairs().values());
    }
}
