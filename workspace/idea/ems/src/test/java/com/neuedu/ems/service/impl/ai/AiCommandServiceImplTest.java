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
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.exception.BusinessException;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 写命令服务单元测试：解析、预览前置条件、确认执行、幂等重放、过期与参数摘要校验。
 */
class AiCommandServiceImplTest {
    @Test void lineSideDefaultUsesConsumptionOwnerRecommendationAndDoesNotGuessWhenAmbiguous() {
        var location=new WmsLocation();location.setLocationId(77L);
        when(outboundOrderService.prepareLineSideTransfer(30L)).thenReturn(
                new com.neuedu.ems.service.wms.LineSideLocationService.Preparation(10L,"WO-ROOT",1L,2L,
                        "总装线",77L,List.of(location),"本产线库位",Map.of()));
        Map<String,Object> params=new LinkedHashMap<>(Map.of("outboundId",30L));
        ReflectionTestUtils.invokeMethod(service,"fillLineSideLocation",params);
        assertEquals(77L,params.get("targetLocationId"));
        when(outboundOrderService.prepareLineSideTransfer(30L)).thenReturn(
                new com.neuedu.ems.service.wms.LineSideLocationService.Preparation(10L,"WO-ROOT",1L,2L,
                        "总装线",null,List.of(location),"多个可选库位，请选择",Map.of()));
        Map<String,Object> ambiguous=new LinkedHashMap<>(Map.of("outboundId",30L));
        ReflectionTestUtils.invokeMethod(service,"fillLineSideLocation",ambiguous);
        assertNull(ambiguous.get("targetLocationId"));
        assertNotNull(ambiguous.get("targetLocationProblem"));
    }

    @Test void currentOrderCandidateScopeIsAppliedInSqlBeforeTheGlobalLimit() {
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration,"scope-test"),
                MfgWorkOrder.class);
        when(workOrderMapper.selectList(any())).thenAnswer(call->{
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MfgWorkOrder> wrapper=call.getArgument(0);
            String sql=wrapper.getSqlSegment();
            assertTrue(sql.contains("work_order_no IN"),sql);
            assertTrue(sql.contains("limit 50"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("WO-CURRENT"));
            return List.of();
        });
        var scope=List.of("WO-CURRENT");
        ReflectionTestUtils.invokeMethod(service,"workOrderCandidates",List.of("DRAFT"),scope);
        var empty=service.candidates("RELEASE_WORK_ORDER",Map.of("targetNos",List.of()),admin);
        assertEquals(0,empty.get("total"));
    }
    @Test
    void explicitAndSpokenSemiWarehouseAreNotReplacedByRawDefault() {
        var raw=rawWarehouse(1L);var semi=rawWarehouse(3L);semi.setWarehouseType("SEMI_FINISHED");semi.setWarehouseCode("LAMP-WH-SEMI");
        when(warehouseMapper.selectList(any())).thenReturn(List.of(raw,semi));
        Map<String,Object> explicit=new LinkedHashMap<>(Map.of("warehouseId",3L));
        ReflectionTestUtils.invokeMethod(service,"fillActionParams","GENERATE_MATERIAL_ISSUE","从半成品仓领组件",explicit);
        assertEquals(3L,explicit.get("warehouseId"));
        Map<String,Object> spoken=new LinkedHashMap<>();
        ReflectionTestUtils.invokeMethod(service,"fillActionParams","GENERATE_MATERIAL_ISSUE","从半成品仓领组件",spoken);
        assertEquals(3L,spoken.get("warehouseId"));
        when(warehouseMapper.selectList(any())).thenReturn(List.of(raw));
        Map<String,Object> missing=new LinkedHashMap<>();
        ReflectionTestUtils.invokeMethod(service,"fillActionParams","GENERATE_MATERIAL_ISSUE","从半成品仓领组件",missing);
        assertNull(missing.get("warehouseId"));assertNotNull(missing.get("warehouseProblem"));
    }

    @Test
    void transferPreviewUsesDomainInspectionBlockAndExplainsHowToRecover() {
        var order=new WmsOutboundOrder();order.setOutboundId(30L);order.setOutboundNo("C1-MI-20261101-099");
        order.setWorkOrderId(11L);order.setOutboundType("MATERIAL_ISSUE");order.setAuditStatus("SUBMITTED");
        when(outboundOrderMapper.selectOne(any())).thenReturn(order);
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(11L,"WO-202610240011","RELEASED"));
        org.mockito.Mockito.doThrow(new BusinessException(400,"领用检验单 C1-QI-20261101-099 尚未提交，请完成逐件检验后再转线边"))
                .when(outboundOrderService).validateLineSideTransfer(eq(30L),any());
        var loc=new WmsLocation();loc.setLocationId(5L);loc.setWarehouseId(2L);loc.setLocationType("NORMAL");loc.setStatus("ENABLE");
        var wh=rawWarehouse(2L);wh.setWarehouseType("LINE_SIDE");
        when(locationMapper.selectList(any())).thenReturn(List.of(loc));when(locationMapper.selectOne(any())).thenReturn(loc);
        when(warehouseMapper.selectList(any())).thenReturn(List.of(wh));when(warehouseMapper.selectOne(any())).thenReturn(wh);
        var preview=service.previewExplicit("TRANSFER_TO_LINE_SIDE",order.getOutboundNo(),Map.of("targetLocationId",5L),previewRequest("领料转线边"),admin);
        assertFalse((Boolean)preview.get("canExecute"));
        assertTrue(preview.get("recoverySteps").toString().contains("提交检验 C1-QI-20261101-099"));
        verify(outboundOrderService,org.mockito.Mockito.never()).transferMaterialIssueToLineSide(any(),any(),any(),any(),any());
    }
    @Test
    void transferPreviewSpeaksTheRealReasonWhenNoLineSideLocationIsRecommended() {
        // 推荐为空不等于现场没有线边库位：多半是本产线有多个候选要人来挑。
        // 原来这里只显示「没有可用的线边仓库位」，会把人误导去建库位。
        var order=new WmsOutboundOrder();order.setOutboundId(30L);order.setOutboundNo("C1-MI-20261101-098");
        order.setWorkOrderId(11L);order.setOutboundType("MATERIAL_ISSUE");order.setAuditStatus("SUBMITTED");
        when(outboundOrderMapper.selectOne(any())).thenReturn(order);
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(11L,"WO-202610240011","RELEASED"));
        when(locationMapper.selectOne(any())).thenReturn(null);
        when(outboundOrderService.prepareLineSideTransfer(30L)).thenReturn(
                new com.neuedu.ems.service.wms.LineSideLocationService.Preparation(11L,"WO-202610240011",1L,2L,
                        "灯头线",null,List.of(),
                        "没有本产线或共享线边库位，请先在库位管理维护所属产线",Map.of()));
        var preview=service.previewExplicit("TRANSFER_TO_LINE_SIDE",order.getOutboundNo(),Map.of(),
                previewRequest("领料转线边"),admin);
        var target=preconditionsOf(preview).stream()
                .filter(row -> "TARGET_LOCATION".equals(row.get("code"))).findFirst().orElseThrow();
        assertEquals(false,target.get("passed"));
        assertTrue(String.valueOf(target.get("detail")).contains("维护所属产线"),String.valueOf(target));
        assertFalse((Boolean)preview.get("canExecute"));
        verify(outboundOrderService,org.mockito.Mockito.never()).transferMaterialIssueToLineSide(any(),any(),any(),any(),any());
    }

    @Test
    void transferPreviewKeepsChosenLocationDetailWhenLocationAlreadySelected() {
        var order=new WmsOutboundOrder();order.setOutboundId(30L);order.setOutboundNo("C1-MI-20261101-097");
        order.setWorkOrderId(11L);order.setOutboundType("MATERIAL_ISSUE");order.setAuditStatus("SUBMITTED");
        when(outboundOrderMapper.selectOne(any())).thenReturn(order);
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(11L,"WO-202610240011","RELEASED"));
        var loc=new WmsLocation();loc.setLocationId(5L);loc.setWarehouseId(2L);loc.setLocationType("NORMAL");loc.setStatus("ENABLE");
        var wh=rawWarehouse(2L);wh.setWarehouseType("LINE_SIDE");
        when(locationMapper.selectOne(any())).thenReturn(loc);
        when(warehouseMapper.selectOne(any())).thenReturn(wh);
        when(warehouseMapper.selectList(any())).thenReturn(List.of(wh));
        when(locationMapper.selectList(any())).thenReturn(List.of(loc));
        Map<String,Object> params=new LinkedHashMap<>(Map.of("targetLocationId",5L));
        ReflectionTestUtils.invokeMethod(service,"fillLineSideLocation",params);
        var preview=service.previewExplicit("TRANSFER_TO_LINE_SIDE",order.getOutboundNo(),params,
                previewRequest("领料转线边"),admin);
        var target=preconditionsOf(preview).stream()
                .filter(row -> "TARGET_LOCATION".equals(row.get("code"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(target.get("detail")).contains("LINE_SIDE"),String.valueOf(target));
        assertFalse(String.valueOf(target.get("detail")).contains("未指定目标库位"),String.valueOf(target));
    }

    @Test
    void taskPreviewKeepsActualPredecessorFailureAndDoesNotStartAnything() {
        var tasks = mock(com.neuedu.ems.service.mfg.MfgTaskService.class);
        ReflectionTestUtils.setField(service, "aiTaskService", tasks);
        var task = new com.neuedu.ems.entity.mfg.MfgTask();
        task.setTaskId(17L); task.setTaskNo("C1-TK-20261101-004");
        task.setStationId(17L); task.setStatus("WAIT_START");
        when(taskMapper.selectOne(any())).thenReturn(task);
        org.mockito.Mockito.doThrow(new BusinessException(400, "前置工序尚未完成，不能越序开工"))
                .when(tasks).checkTaskPredecessors(17L);
        var preview = service.previewExplicit("START_TASK", task.getTaskNo(), Map.of(),
                previewRequest("开工工序任务 C1-TK-20261101-004"), admin);
        assertFalse((Boolean) preview.get("canExecute"));
        assertTrue(String.valueOf(preconditionsOf(preview)).contains("前置工序尚未完成"));
        verify(tasks, org.mockito.Mockito.never()).startTask(any(), any(), any());
    }

    @Test
    void changedReportMaterialBatchRequiresNewPreview() {
        var preparation = mock(com.neuedu.ems.service.mfg.WorkReportPreparationService.class);
        ReflectionTestUtils.setField(service, "workReportPreparationService", preparation);
        var task = new com.neuedu.ems.entity.mfg.MfgTask();
        task.setTaskId(17L); task.setTaskNo("C1-TK-20261031-017");
        task.setStatus("RUNNING"); task.setPlannedQuantity(new BigDecimal("2"));
        when(taskMapper.selectOne(any())).thenReturn(task);
        var prepared = mock(com.neuedu.ems.service.mfg.WorkReportPreparationService.Preparation.class);
        var line1 = new com.neuedu.ems.service.mfg.WorkReportPreparationService.MaterialLine(
                1L, "灯珠", 10L, "A", new BigDecimal("2"), new BigDecimal("2"));
        var line2 = new com.neuedu.ems.service.mfg.WorkReportPreparationService.MaterialLine(
                1L, "灯珠", 11L, "B", new BigDecimal("2"), new BigDecimal("2"));
        when(prepared.materials()).thenReturn(List.of(line1), List.of(line2));
        when(prepared.warnings()).thenReturn(List.of());
        when(preparation.prepare(eq(17L), eq(null), any())).thenReturn(prepared);
        var preview = service.previewExplicit("SUBMIT_WORK_REPORT", task.getTaskNo(),
                Map.of("qualifiedQuantity", 2, "defectQuantity", 0),
                previewRequest("给工序任务 C1-TK-20261031-017 报工合格 2 件"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        var record = capturedRecord();
        when(executionMapper.selectOne(any())).thenReturn(record);
        assertThrows(BusinessException.class, () -> service.execute(
                new AiCommandService.AiCommandExecuteRequest(record.getConfirmationId(), null, null, null), admin));
    }

    @Test
    void reportQuantityMustComeFromTheUserAndSeparatesDefects() {
        Map<String, Object> absent = new LinkedHashMap<>();
        ReflectionTestUtils.invokeMethod(service, "fillReportQuantities", "给任务报工", absent);
        assertFalse(absent.containsKey("qualifiedQuantity"));
        Map<String, Object> actual = new LinkedHashMap<>();
        ReflectionTestUtils.invokeMethod(service, "fillReportQuantities", "报工合格 1 件，不良 1 件", actual);
        assertEquals(new BigDecimal("1"), actual.get("qualifiedQuantity"));
        assertEquals(new BigDecimal("1"), actual.get("defectQuantity"));
    }

    @Test
    void reportMaterialPreparationFailureOrShortageDoesNotBecomeEmptyConsumption() {
        var preparation = mock(com.neuedu.ems.service.mfg.WorkReportPreparationService.class);
        ReflectionTestUtils.setField(service, "workReportPreparationService", preparation);
        when(preparation.prepare(eq(17L), eq(null), eq(new BigDecimal("2"))))
                .thenThrow(new BusinessException(400, "线边库存读取失败"));
        assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "reportMaterials", 17L, new BigDecimal("2")));
        var result = mock(com.neuedu.ems.service.mfg.WorkReportPreparationService.Preparation.class);
        when(result.warnings()).thenReturn(List.of("灯头缺少可用领料 1；请先补料转线边，再确认实际用料"));
        org.mockito.Mockito.doReturn(result).when(preparation)
                .prepare(eq(17L), eq(null), eq(new BigDecimal("2")));
        assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "reportMaterials", 17L, new BigDecimal("2")));
    }

    @Test
    void startTaskUsesTaskPermissionAndDelegatesToDomainService() {
        var taskService = mock(com.neuedu.ems.service.mfg.MfgTaskService.class);
        ReflectionTestUtils.setField(service, "aiTaskService", taskService);
        var task = new com.neuedu.ems.entity.mfg.MfgTask();
        task.setTaskId(17L);
        task.setTaskNo("C1-TK-20261031-017");
        task.setStationId(17L);
        task.setStatus("WAIT_START");
        when(taskMapper.selectOne(any())).thenReturn(task);
        var preview = service.previewExplicit("START_TASK", task.getTaskNo(), Map.of(),
                previewRequest("开工工序任务 C1-TK-20261031-017"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        when(taskService.startTask(17L, null, 7L)).thenAnswer(invocation -> {
            task.setStatus("RUNNING");
            return true;
        });
        when(taskMapper.selectById(17L)).thenReturn(task);
        var record = capturedRecord();
        when(executionMapper.selectOne(any())).thenReturn(record);
        var result = service.execute(new AiCommandService.AiCommandExecuteRequest(
                record.getConfirmationId(), null, null, null), admin);
        assertEquals("SUCCEEDED", result.get("status"));
        verify(taskService).startTask(17L, null, 7L);
    }

    @Test
    void taskStartPreviewBlocksAnOccupiedStation() {
        var task = new com.neuedu.ems.entity.mfg.MfgTask();
        task.setTaskId(19L); task.setTaskNo("C1-TK-20261031-019");
        task.setStationId(17L); task.setStatus("WAIT_START");
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(taskMapper.selectCount(any())).thenReturn(1L);
        var preview = service.previewExplicit("START_TASK", task.getTaskNo(), Map.of(),
                previewRequest("开工工序任务 C1-TK-20261031-019"), admin);
        assertFalse((Boolean) preview.get("canExecute"));
        assertTrue(String.valueOf(preconditionsOf(preview)).contains("工位被其他生产任务占用"));
    }

    @Test
    void dispatchUsesTheOnlyEligibleTeamAndCallsRealDomainService() {
        var dispatchService = mock(com.neuedu.ems.service.mfg.MfgDispatchOrderService.class);
        var dispatchMapper = mock(com.neuedu.ems.mapper.mfg.MfgDispatchOrderMapper.class);
        ReflectionTestUtils.setField(service, "aiDispatchService", dispatchService);
        ReflectionTestUtils.setField(service, "aiDispatchMapper", dispatchMapper);
        var wo = new MfgWorkOrder(); wo.setWorkOrderId(11L); wo.setWorkOrderNo("WO-202610240001"); wo.setStatus("RELEASED");
        when(workOrderMapper.selectOne(any())).thenReturn(wo);
        var team = new com.neuedu.ems.vo.mfg.DispatchPreparationVO.TeamOption(3L, "装配一班");
        var preparation = new com.neuedu.ems.vo.mfg.DispatchPreparationVO(11L, "一号线", null, null,
                BigDecimal.ONE, null, List.of(team), List.of(), List.of(), List.of());
        when(dispatchService.previewDispatch(11L)).thenReturn(preparation);
        when(dispatchMapper.selectCount(any())).thenReturn(0L);
        var preview = service.previewExplicit("GENERATE_DISPATCH_ORDER", "WO-202610240001", Map.of(),
                previewRequest("为 WO-202610240001 生成派工单"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        var dispatch = new com.neuedu.ems.entity.mfg.MfgDispatchOrder();
        dispatch.setDispatchId(30L); dispatch.setDispatchNo("DO-30"); dispatch.setStatus("WAIT_DISPATCH"); dispatch.setDeletedFlag(0);
        when(dispatchService.generateDispatchOrder(eq(11L), any(), eq(7L))).thenReturn(dispatch);
        when(dispatchMapper.selectById(30L)).thenReturn(dispatch);
        var record = capturedRecord(); when(executionMapper.selectOne(any())).thenReturn(record);
        var result = service.execute(new AiCommandService.AiCommandExecuteRequest(record.getConfirmationId(), null, null, null), admin);
        assertEquals("SUCCEEDED", result.get("status"));
        verify(dispatchService).generateDispatchOrder(eq(11L), org.mockito.ArgumentMatchers.argThat(value -> Long.valueOf(3L).equals(value.getTeamId())), eq(7L));
    }

    @Test
    void dispatchBlocksWhenMultipleTeamsNeedSelectionOrMaterialsAreNotReady() {
        var dispatchService = mock(com.neuedu.ems.service.mfg.MfgDispatchOrderService.class);
        var dispatchMapper = mock(com.neuedu.ems.mapper.mfg.MfgDispatchOrderMapper.class);
        ReflectionTestUtils.setField(service, "aiDispatchService", dispatchService);
        ReflectionTestUtils.setField(service, "aiDispatchMapper", dispatchMapper);
        var wo = new MfgWorkOrder(); wo.setWorkOrderId(11L); wo.setWorkOrderNo("WO-202610240001"); wo.setStatus("RELEASED");
        when(workOrderMapper.selectOne(any())).thenReturn(wo);
        var team1 = new com.neuedu.ems.vo.mfg.DispatchPreparationVO.TeamOption(3L, "一班");
        var team2 = new com.neuedu.ems.vo.mfg.DispatchPreparationVO.TeamOption(4L, "二班");
        var preparation = new com.neuedu.ems.vo.mfg.DispatchPreparationVO(11L, "一号线", null, null,
                BigDecimal.ONE, null, List.of(team1, team2), List.of(), List.of(), List.of());
        when(dispatchService.previewDispatch(11L)).thenReturn(preparation);
        when(dispatchMapper.selectCount(any())).thenReturn(0L);
        var preview = service.previewExplicit("GENERATE_DISPATCH_ORDER", "WO-202610240001", Map.of(),
                previewRequest("为 WO-202610240001 生成派工单"), admin);
        assertFalse((Boolean) preview.get("canExecute"));
        assertTrue(String.valueOf(preconditionsOf(preview)).contains("请选择班组"));
        var selected = service.previewExplicit("GENERATE_DISPATCH_ORDER", "WO-202610240001", Map.of("teamName", "一班"),
                previewRequest("为 WO-202610240001 生成派工单，班组一班"), admin);
        assertTrue((Boolean) selected.get("canExecute"), String.valueOf(preconditionsOf(selected)));
        assertEquals("一班", paramsOf(selected).get("dispatchTeamName"));
        org.mockito.Mockito.doThrow(new BusinessException(400, "用料尚未实际到位"))
                .when(dispatchService).validateDispatchMaterials(11L);
        var shortage = service.previewExplicit("GENERATE_DISPATCH_ORDER", "WO-202610240001", Map.of("teamName", "一班"),
                previewRequest("为 WO-202610240001 生成派工单，班组一班"), admin);
        assertFalse((Boolean) shortage.get("canExecute"));
        assertTrue(String.valueOf(preconditionsOf(shortage)).contains("用料尚未实际到位"));
        org.mockito.Mockito.doNothing().when(dispatchService).validateDispatchMaterials(11L);
        when(dispatchMapper.selectCount(any())).thenReturn(1L);
        var duplicate = service.previewExplicit("GENERATE_DISPATCH_ORDER", "WO-202610240001", Map.of("teamName", "一班"),
                previewRequest("为 WO-202610240001 生成派工单，班组一班"), admin);
        assertFalse((Boolean) duplicate.get("canExecute"));
        assertTrue(String.valueOf(preconditionsOf(duplicate)).contains("已有有效派工单"));
        verify(dispatchService, org.mockito.Mockito.never()).generateDispatchOrder(any(), any(), any());
    }
    @Test
    void generateWorkOrderPreviewsOnlyOneUnoccupiedReleasedPlanAndExecutesDomainService() {
        var orders = mock(com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper.class);
        ReflectionTestUtils.setField(service, "aiProductionOrderMapper", orders);
        var productionOrder = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        productionOrder.setProductionOrderId(41L); productionOrder.setProductionOrderNo("C1-PO-20261031-006");
        when(orders.selectOne(any())).thenReturn(productionOrder);
        var plan = new MfgProductionPlan();
        plan.setPlanId(22L); plan.setPlanNo("PL-22"); plan.setProductionOrderId(41L);
        plan.setStatus("RELEASED"); plan.setPlanType("ROOT"); plan.setDeletedFlag(0);
        when(productionPlanMapper.selectList(any())).thenReturn(List.of(plan));
        when(productionPlanMapper.selectById(22L)).thenReturn(plan);
        when(workOrderMapper.selectCount(any())).thenReturn(0L);
        var preview = service.previewExplicit("GENERATE_WORK_ORDER", "C1-PO-20261031-006", Map.of(),
                previewRequest("给订单 C1-PO-20261031-006 生成工单"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        var generated = new MfgWorkOrder(); generated.setWorkOrderId(55L); generated.setWorkOrderNo("WO-55"); generated.setStatus("DRAFT");
        when(workOrderService.generateWorkOrder(eq(22L), any(MfgWorkOrder.class), eq(7L))).thenReturn(generated);
        when(workOrderMapper.selectById(55L)).thenReturn(generated);
        var record = capturedRecord();
        when(executionMapper.selectOne(any())).thenReturn(record);
        var result = service.execute(new AiCommandService.AiCommandExecuteRequest(record.getConfirmationId(), null, null, null), admin);
        assertEquals("SUCCEEDED", result.get("status"));
        verify(workOrderService).generateWorkOrder(eq(22L), org.mockito.ArgumentMatchers.argThat(
                request -> record.getConfirmationId().equals(request.getRequestKey())), eq(7L));
    }

    @Test
    void generateWorkOrderDoesNotGuessWhenMultiplePlansExist() {
        var orders = mock(com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper.class);
        ReflectionTestUtils.setField(service, "aiProductionOrderMapper", orders);
        var order = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        order.setProductionOrderId(41L); order.setProductionOrderNo("C1-PO-20261031-006");
        when(orders.selectOne(any())).thenReturn(order);
        var first = new MfgProductionPlan(); first.setPlanId(1L); first.setPlanType("ROOT");
        var second = new MfgProductionPlan(); second.setPlanId(2L); second.setPlanType("ROOT");
        when(productionPlanMapper.selectList(any())).thenReturn(List.of(first, second));
        var preview = service.previewExplicit("GENERATE_WORK_ORDER", "C1-PO-20261031-006", Map.of(),
                previewRequest("为 C1-PO-20261031-006 生成工单"), admin);
        assertFalse((Boolean) preview.get("canExecute"));
        verify(workOrderService, org.mockito.Mockito.never()).generateWorkOrder(any(), any(), any());
    }

    @Test
    void generateWorkOrderRejectsUserWithoutWorkOrderGeneratePermission() {
        var restricted = new AuthorizationContext(7L, 8L, 9L, "ALL", Set.of(),
                Set.of("ai:assistant:use"), Set.of());
        assertThrows(com.neuedu.ems.exception.DataScopeAccessDeniedException.class, () -> service.previewExplicit("GENERATE_WORK_ORDER",
                "C1-PO-20261031-006", Map.of(), previewRequest("生成工单"), restricted));
        verify(workOrderService, org.mockito.Mockito.never()).generateWorkOrder(any(), any(), any());
    }
    @Test
    void customerPrefixedNumbersKeepTheirDocumentType() {
        assertEquals(AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER,service.parseIntent("下达 B-WO-20261027-001"));
        assertEquals(AiCommandServiceImpl.ACTION_GENERATE_MATERIAL_ISSUE,service.parseIntent("给 B-WO-20261027-001 生成领料单"));
        assertEquals(AiCommandServiceImpl.ACTION_CONFIRM_OUTBOUND,service.parseIntent("确认出库 B-SI-20261027-002"));
        assertEquals(AiCommandServiceImpl.ACTION_CONFIRM_INBOUND,service.parseIntent("确认入库 SYS-IN-20261027-001"));
        assertEquals(AiCommandServiceImpl.ACTION_AUDIT_WORK_REPORT,service.parseIntent("审核通过报工 B-WR-20261027-001"));
    }

    private AiCommandServiceImpl service;
    private AiCommandExecutionMapper executionMapper;
    private AiCommandStepMapper stepMapper;
    private MfgWorkOrderMapper workOrderMapper;
    private MfgProductionPlanMapper productionPlanMapper;
    private MfgWorkOrderMaterialMapper workOrderMaterialMapper;
    private MfgTaskMapper taskMapper;
    private MfgWorkReportMapper workReportMapper;
    private WmsOutboundOrderMapper outboundOrderMapper;
    private WmsOutboundDetailMapper outboundDetailMapper;
    private WmsInboundOrderMapper inboundOrderMapper;
    private WmsInboundDetailMapper inboundDetailMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsLocationMapper locationMapper;
    private com.neuedu.ems.mapper.wms.WmsInventoryMapper inventoryMapper;
    private com.neuedu.ems.mapper.mfg.MfgSupplyAllocationMapper supplyAllocationMapper;
    private QmsInspectionOrderMapper inspectionOrderMapper;
    private SysOperationLogMapper operationLogMapper;
    private SysStatusChangeLogMapper statusChangeLogMapper;
    private WmsStockMovementMapper stockMovementMapper;
    private MfgWorkOrderService workOrderService;
    private WmsOutboundOrderService outboundOrderService;
    private WmsInboundOrderService inboundOrderService;
    private QmsInspectionOrderService inspectionOrderService;
    private MfgWorkReportService workReportService;

    private final AuthorizationContext admin = new AuthorizationContext(7L, 8L, 9L, "ALL",
            Set.of("SUPER_ADMIN"), Set.of("ai:assistant.use"), Set.of());

    @BeforeEach
    void setUp() {
        service = new AiCommandServiceImpl();
        com.neuedu.ems.support.BusinessNumberTestData.inject(service);
        executionMapper = mock(AiCommandExecutionMapper.class);
        stepMapper = mock(AiCommandStepMapper.class);
        workOrderMapper = mock(MfgWorkOrderMapper.class);
        productionPlanMapper = mock(MfgProductionPlanMapper.class);
        workOrderMaterialMapper = mock(MfgWorkOrderMaterialMapper.class);
        taskMapper = mock(MfgTaskMapper.class);
        workReportMapper = mock(MfgWorkReportMapper.class);
        outboundOrderMapper = mock(WmsOutboundOrderMapper.class);
        outboundDetailMapper = mock(WmsOutboundDetailMapper.class);
        inboundOrderMapper = mock(WmsInboundOrderMapper.class);
        inboundDetailMapper = mock(WmsInboundDetailMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        inventoryMapper = mock(com.neuedu.ems.mapper.wms.WmsInventoryMapper.class);
        supplyAllocationMapper = mock(com.neuedu.ems.mapper.mfg.MfgSupplyAllocationMapper.class);
        inspectionOrderMapper = mock(QmsInspectionOrderMapper.class);
        operationLogMapper = mock(SysOperationLogMapper.class);
        statusChangeLogMapper = mock(SysStatusChangeLogMapper.class);
        stockMovementMapper = mock(WmsStockMovementMapper.class);
        workOrderService = mock(MfgWorkOrderService.class);
        outboundOrderService = mock(WmsOutboundOrderService.class);
        inboundOrderService = mock(WmsInboundOrderService.class);
        inspectionOrderService = mock(QmsInspectionOrderService.class);
        workReportService = mock(MfgWorkReportService.class);

        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(service, "stepMapper", stepMapper);
        ReflectionTestUtils.setField(service, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "productionPlanMapper", productionPlanMapper);
        ReflectionTestUtils.setField(service, "workOrderMaterialMapper", workOrderMaterialMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "workReportMapper", workReportMapper);
        ReflectionTestUtils.setField(service, "outboundOrderMapper", outboundOrderMapper);
        ReflectionTestUtils.setField(service, "outboundDetailMapper", outboundDetailMapper);
        ReflectionTestUtils.setField(service, "inboundOrderMapper", inboundOrderMapper);
        ReflectionTestUtils.setField(service, "inboundDetailMapper", inboundDetailMapper);
        ReflectionTestUtils.setField(service, "warehouseMapper", warehouseMapper);
        ReflectionTestUtils.setField(service, "locationMapper", locationMapper);
        ReflectionTestUtils.setField(service, "inventoryMapper", inventoryMapper);
        ReflectionTestUtils.setField(service, "supplyAllocationMapper", supplyAllocationMapper);
        ReflectionTestUtils.setField(service, "inspectionOrderMapper", inspectionOrderMapper);
        ReflectionTestUtils.setField(service, "operationLogMapper", operationLogMapper);
        ReflectionTestUtils.setField(service, "statusChangeLogMapper", statusChangeLogMapper);
        ReflectionTestUtils.setField(service, "stockMovementMapper", stockMovementMapper);
        ReflectionTestUtils.setField(service, "workOrderService", workOrderService);
        ReflectionTestUtils.setField(service, "outboundOrderService", outboundOrderService);
        ReflectionTestUtils.setField(service, "inboundOrderService", inboundOrderService);
        ReflectionTestUtils.setField(service, "inspectionOrderService", inspectionOrderService);
        ReflectionTestUtils.setField(service, "workReportService", workReportService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        // 插入命令记录时回填自增主键，模拟数据库行为。
        doAnswer(invocation -> {
            AiCommandExecution record = invocation.getArgument(0);
            record.setCommandId(1001L);
            return 1;
        }).when(executionMapper).insert(any(AiCommandExecution.class));
        doAnswer(invocation -> {
            AiCommandStep step = invocation.getArgument(0);
            step.setStepId(2001L);
            return 1;
        }).when(stepMapper).insert(any(AiCommandStep.class));
        when(executionMapper.updateById(any(AiCommandExecution.class))).thenReturn(1);
        when(stepMapper.updateById(any(AiCommandStep.class))).thenReturn(1);
        when(operationLogMapper.insert(any(com.neuedu.ems.entity.sys.SysOperationLog.class))).thenReturn(1);
        // 后置复核默认通过：真实环境里领域服务会写状态流水，这里模拟「流水确实存在」。
        when(statusChangeLogMapper.selectCount(any())).thenReturn(1L);
    }

    @Test
    void parseIntentRecognizesAllEightWriteActions() {
        assertEquals(AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER, service.parseIntent("下达工单 WO-202610240001"));
        assertEquals(AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER, service.parseIntent("把 WO-202610240001 下达"));
        assertEquals(AiCommandServiceImpl.ACTION_START_WORK_ORDER, service.parseIntent("开始生产 WO-202610240001"));
        assertEquals(AiCommandServiceImpl.ACTION_START_WORK_ORDER, service.parseIntent("启动工单 WO-202610240001"));
        assertEquals(AiCommandServiceImpl.ACTION_GENERATE_MATERIAL_ISSUE,
                service.parseIntent("给 WO-202610240001 生成领料单"));
        assertEquals(AiCommandServiceImpl.ACTION_GENERATE_MATERIAL_ISSUE, service.parseIntent("WO-202610240001 领料"));
        assertEquals(AiCommandServiceImpl.ACTION_TRANSFER_TO_LINE_SIDE,
                service.parseIntent("把领料单 OUT-202610240002 转到线边仓"));
        assertEquals(AiCommandServiceImpl.ACTION_TRANSFER_TO_LINE_SIDE,
                service.parseIntent("OUT-202610240002 移库到线边"));
        assertEquals(AiCommandServiceImpl.ACTION_CONFIRM_OUTBOUND, service.parseIntent("确认出库 OUT-202610240002"));
        assertEquals(AiCommandServiceImpl.ACTION_CONFIRM_INBOUND, service.parseIntent("确认入库 IN-202610240003"));
        assertEquals(AiCommandServiceImpl.ACTION_SUBMIT_INSPECTION,
                service.parseIntent("把检验单 QI202610240004 判为合格"));
        assertEquals(AiCommandServiceImpl.ACTION_SUBMIT_INSPECTION,
                service.parseIntent("QI202610240004 合格 90 不合格 10"));
        assertEquals(AiCommandServiceImpl.ACTION_AUDIT_WORK_REPORT, service.parseIntent("审核通过报工 WR202610240005"));
        assertEquals(AiCommandServiceImpl.ACTION_AUDIT_WORK_REPORT, service.parseIntent("报工 ID 12 审核合格"));
    }

    @Test
    void parseIntentAcceptsRealOutboundNumberPrefixes() {
        // 演示库出库单实际用 MI-（领料）与 SO-（销售）前缀，只认 OUT- 会漏判。
        assertEquals(AiCommandServiceImpl.ACTION_CONFIRM_OUTBOUND, service.parseIntent("确认出库 MI-WO1-20261024144214245"));
        assertEquals(AiCommandServiceImpl.ACTION_CONFIRM_OUTBOUND, service.parseIntent("确认出库 SO-4-20261024144348209"));
        assertEquals(AiCommandServiceImpl.ACTION_TRANSFER_TO_LINE_SIDE,
                service.parseIntent("把领料单 MI-WO1-20261024144214245 转到线边仓"));
    }

    @Test
    void previewExtractsWholeOutboundNumberInsteadOfInnerWorkOrderFragment() {
        WmsOutboundOrder outbound = new WmsOutboundOrder();
        outbound.setOutboundId(4L);
        outbound.setOutboundNo("MI-WO1-20261024144214245");
        outbound.setOutboundType("MATERIAL_ISSUE");
        outbound.setAuditStatus(BusinessStatus.Document.SUBMITTED);
        outbound.setWarehouseId(1L);
        outbound.setWorkOrderId(1L);
        when(outboundOrderMapper.selectOne(any())).thenReturn(outbound);

        Map<String, Object> preview = service.preview(
                previewRequest("确认出库 MI-WO1-20261024144214245"), admin);

        // MI-WO1-... 必须整体识别，不能从中间的 WO 片段开始截断成 WO1-...
        assertEquals("MI-WO1-20261024144214245", preview.get("targetNo"));
        assertEquals(4L, preview.get("targetId"));
    }

    @Test
    void parseIntentIgnoresReadOnlyQuestions() {
        assertNull(service.parseIntent("查询 WO-202610240001 的领料情况"));
        assertNull(service.parseIntent("看看有哪些工单"));
        assertNull(service.parseIntent("今天生产了多少"));
    }

    @Test
    void previewBlocksWhenWorkOrderStatusIsNotReleasable() {
        MfgWorkOrder finished = workOrder(11L, "WO-202610240011", BusinessStatus.Production.FINISHED);
        when(workOrderMapper.selectOne(any())).thenReturn(finished);
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(2L);

        Map<String, Object> preview = service.preview(previewRequest("下达工单 WO-202610240011"), admin);

        assertFalse((Boolean) preview.get("canExecute"));
        assertEquals("RELEASE_WORK_ORDER", preview.get("action"));
        List<Map<String, Object>> preconditions = preconditionsOf(preview);
        assertTrue(preconditions.stream().anyMatch(item -> "WORK_ORDER_STATUS".equals(item.get("code"))
                && Boolean.FALSE.equals(item.get("passed"))));
        // 预览阶段不允许改动任何业务单据。
        verify(workOrderService, org.mockito.Mockito.never()).releaseWorkOrder(any(), any(), any());
    }

    /**
     * 预览必须按真实库存判可执行：只判「还有东西要领」会让卡片全绿、点确认必然失败。
     */
    @Test
    void previewBlocksMaterialIssueWhenStockCannotCoverTheWorkOrder() {
        when(workOrderMapper.selectOne(any()))
                .thenReturn(workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectList(any())).thenReturn(List.of(rawWarehouse(1L)));
        when(warehouseMapper.selectOne(any())).thenReturn(rawWarehouse(1L));
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of(outstanding(19L, "10", "4")));
        when(supplyAllocationMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new BusinessException(400, "物料 19 缺 6.0000"))
                .when(outboundOrderService).validateMaterialIssueAvailability(11L, 1L);

        Map<String, Object> preview = service.preview(
                previewRequest("给 WO-202610240011 生成领料单"), admin);

        assertFalse((Boolean) preview.get("canExecute"), "库存不够时不能显示可执行");
        Map<String, Object> stockItem = preconditionsOf(preview).stream()
                .filter(item -> "MATERIAL_AVAILABLE".equals(item.get("code")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, stockItem.get("passed"));
        assertTrue(String.valueOf(stockItem.get("detail")).contains("物料 19 缺 6.0000"),
                String.valueOf(stockItem.get("detail")));
    }

    @Test
    void previewBlocksWhenDomainRejectsUnreleasedStock() {
        when(workOrderMapper.selectOne(any()))
                .thenReturn(workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectList(any())).thenReturn(List.of(rawWarehouse(1L)));
        when(warehouseMapper.selectOne(any())).thenReturn(rawWarehouse(1L));
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of(outstanding(19L, "10", "4")));
        when(supplyAllocationMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventoryOf(19L, "6")));
        org.mockito.Mockito.doThrow(new BusinessException(400, "批次未放行"))
                .when(outboundOrderService).validateMaterialIssueAvailability(11L, 1L);

        Map<String, Object> preview = service.preview(previewRequest("给 WO-202610240011 生成领料单"), admin);
        Map<String, Object> stockItem = preconditionsOf(preview).stream()
                .filter(item -> "MATERIAL_AVAILABLE".equals(item.get("code"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, stockItem.get("passed"));
        assertTrue(String.valueOf(stockItem.get("detail")).contains("批次未放行"));
        assertFalse((Boolean) preview.get("canExecute"));
    }

    @Test
    void previewDoesNotClaimStockIsAvailableWhenInventoryCannotBeChecked() {
        when(workOrderMapper.selectOne(any()))
                .thenReturn(workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectList(any())).thenReturn(List.of(rawWarehouse(1L)));
        when(warehouseMapper.selectOne(any())).thenReturn(rawWarehouse(1L));
        ReflectionTestUtils.setField(service, "outboundOrderService", null);

        Map<String, Object> preview = service.preview(previewRequest("给 WO-202610240011 生成领料单"), admin);

        Map<String, Object> stockItem = preconditionsOf(preview).stream()
                .filter(item -> "MATERIAL_AVAILABLE".equals(item.get("code"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, stockItem.get("passed"));
        assertTrue(String.valueOf(stockItem.get("detail")).contains("无法核对库存"));
        assertFalse((Boolean) preview.get("canExecute"));
    }

    /** 预留加公共可用量够用时，预览应当放行。 */
    @Test
    void previewPassesMaterialIssueWhenReservedPlusAvailableCovers() {
        when(workOrderMapper.selectOne(any()))
                .thenReturn(workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectList(any())).thenReturn(List.of(rawWarehouse(1L)));
        when(warehouseMapper.selectOne(any())).thenReturn(rawWarehouse(1L));
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of(outstanding(19L, "10", "4")));
        when(supplyAllocationMapper.selectList(any())).thenReturn(List.of(reservedOf(19L, "2")));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(inventoryOf(19L, "4")));

        Map<String, Object> preview = service.preview(
                previewRequest("给 WO-202610240011 生成领料单"), admin);

        Map<String, Object> stockItem = preconditionsOf(preview).stream()
                .filter(item -> "MATERIAL_AVAILABLE".equals(item.get("code")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, stockItem.get("passed"), String.valueOf(stockItem.get("detail")));
        verify(outboundOrderService).validateMaterialIssueAvailability(11L, 1L);
    }

    @Test
    void previewRespectsDomainAllocationWhenComponentsComeFromChildWorkOrders() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        when(warehouseMapper.selectList(any())).thenReturn(List.of(rawWarehouse(1L)));
        when(warehouseMapper.selectOne(any())).thenReturn(rawWarehouse(1L));
        // 本级需求含组件，但领域服务已排除同线工单交接份额；不能再把该组件当仓库缺料。
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of(outstanding(19L, "1", "0")));
        when(inventoryMapper.selectList(any())).thenReturn(List.of());
        when(supplyAllocationMapper.selectList(any())).thenReturn(List.of());
        var preview = service.preview(previewRequest("给 WO-202610240011 生成领料单"), admin);
        var item = preconditionsOf(preview).stream().filter(row -> "MATERIAL_AVAILABLE".equals(row.get("code"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, item.get("passed"), String.valueOf(item.get("detail")));
        verify(outboundOrderService).validateMaterialIssueAvailability(11L, 1L);
    }

    @Test
    void previewPassesAndExecuteSucceeds() {
        MfgWorkOrder draft = workOrder(11L, "WO-202610240011", BusinessStatus.Document.DRAFT);
        when(workOrderMapper.selectOne(any())).thenReturn(draft);
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);
        when(workOrderService.releaseWorkOrder(11L, 7L, "确认下达")).thenReturn(
                workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        // 后置复核回查的是执行后的库内状态，必须是已下达。
        when(workOrderMapper.selectById(11L)).thenReturn(
                workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));

        Map<String, Object> preview = service.preview(previewRequest("下达工单 WO-202610240011"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        assertEquals(6, preconditionsOf(preview).size());

        AiCommandExecution record = capturedRecord();
        when(executionMapper.selectOne(any())).thenReturn(record);

        Map<String, Object> result = service.execute(
                new AiCommandService.AiCommandExecuteRequest(record.getConfirmationId(), null, null, "确认下达"), admin);

        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals("WO-202610240011", result.get("bizNo"));
        assertEquals("SUCCEEDED", record.getStatus());
        assertNotNull(record.getResultJson());
        verify(operationLogMapper).insert(any(com.neuedu.ems.entity.sys.SysOperationLog.class));
        verify(workOrderService).releaseWorkOrder(11L, 7L, "确认下达");
        // 三步：校验前置条件、调用领域服务、执行后复核。
        ArgumentCaptor<AiCommandStep> captor = ArgumentCaptor.forClass(AiCommandStep.class);
        verify(stepMapper, org.mockito.Mockito.atLeast(3)).insert(captor.capture());
        List<AiCommandStep> steps = captor.getAllValues();
        assertEquals("PRECHECK", steps.get(0).getCapability());
        assertEquals("DOMAIN_CALL", steps.get(1).getCapability());
        assertEquals("POSTCHECK", steps.get(2).getCapability());
        assertEquals("SUCCEEDED", steps.get(2).getStatus());
    }

    @Test
    void executeFailsWhenDomainSaysOkButWorkOrderStatusDidNotChange() {
        MfgWorkOrder draft = workOrder(11L, "WO-202610240011", BusinessStatus.Document.DRAFT);
        when(workOrderMapper.selectOne(any())).thenReturn(draft);
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);
        // 领域服务声称成功，但库里的工单仍是草稿：接口返回成功不能算业务达成。
        when(workOrderService.releaseWorkOrder(11L, 7L, null)).thenReturn(
                workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        when(workOrderMapper.selectById(11L)).thenReturn(draft);

        Map<String, Object> preview = service.preview(previewRequest("下达工单 WO-202610240011"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        AiCommandExecution record = capturedRecord();
        when(executionMapper.selectOne(any())).thenReturn(record);

        BusinessException error = assertThrows(BusinessException.class, () -> service.execute(
                new AiCommandService.AiCommandExecuteRequest(record.getConfirmationId(), null, null, null), admin));

        assertTrue(error.getMessage().contains("后置复核未通过"), error.getMessage());
        assertTrue(error.getMessage().contains("DRAFT"), error.getMessage());
        assertEquals("FAILED", record.getStatus());
        assertNotNull(record.getErrorMessage());
    }

    @Test
    void executeFailsWhenStatusLogIsMissingEvenIfStatusLooksRight() {
        MfgWorkOrder draft = workOrder(11L, "WO-202610240011", BusinessStatus.Document.DRAFT);
        when(workOrderMapper.selectOne(any())).thenReturn(draft);
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);
        when(workOrderService.releaseWorkOrder(11L, 7L, null)).thenReturn(
                workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        when(workOrderMapper.selectById(11L)).thenReturn(
                workOrder(11L, "WO-202610240011", BusinessStatus.Document.RELEASED));
        // 状态对得上，但没有任何状态流水：说明变更没有走受审计的业务入口。
        when(statusChangeLogMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> preview = service.preview(previewRequest("下达工单 WO-202610240011"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        AiCommandExecution record = capturedRecord();
        when(executionMapper.selectOne(any())).thenReturn(record);

        BusinessException error = assertThrows(BusinessException.class, () -> service.execute(
                new AiCommandService.AiCommandExecuteRequest(record.getConfirmationId(), null, null, null), admin));

        assertTrue(error.getMessage().contains("状态流水"), error.getMessage());
        assertEquals("FAILED", record.getStatus());
    }

    @Test
    void executeReplaysExistingResultForSameIdempotencyKey() {
        MfgWorkOrder draft = workOrder(11L, "WO-202610240011", BusinessStatus.Document.DRAFT);
        when(workOrderMapper.selectOne(any())).thenReturn(draft);
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);

        Map<String, Object> preview = service.preview(previewRequest("下达工单 WO-202610240011"), admin);
        AiCommandExecution record = capturedRecord();
        String hash = record.getRequestHash();

        AiCommandExecution previous = new AiCommandExecution();
        previous.setCommandId(900L);
        previous.setConfirmationId("previous-confirmation");
        previous.setAction("RELEASE_WORK_ORDER");
        previous.setActionLabel("下达生产工单");
        previous.setRequestHash(hash);
        previous.setIdempotencyKey("idem-1");
        previous.setStatus("SUCCEEDED");
        previous.setResultJson("{\"bizNo\":\"WO-202610240011\",\"bizId\":11}");
        previous.setElapsedMs(12L);
        previous.setTargetNo("WO-202610240011");

        when(executionMapper.selectOne(any())).thenAnswer(invocation -> {
            QueryWrapper<?> wrapper = invocation.getArgument(0);
            return wrapper.getSqlSegment().contains("idempotency_key") ? previous : record;
        });

        Map<String, Object> result = service.execute(new AiCommandService.AiCommandExecuteRequest(
                record.getConfirmationId(), "idem-1", null, "重复提交"), admin);

        assertEquals(Boolean.TRUE, result.get("replayed"));
        assertEquals(900L, result.get("commandId"));
        verify(workOrderService, org.mockito.Mockito.never()).releaseWorkOrder(any(), any(), any());
        assertNotNull(preview.get("confirmationId"));
    }

    @Test
    void executeRejectsExpiredPreview() {
        MfgWorkOrder draft = workOrder(11L, "WO-202610240011", BusinessStatus.Document.DRAFT);
        when(workOrderMapper.selectOne(any())).thenReturn(draft);
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);
        service.preview(previewRequest("下达工单 WO-202610240011"), admin);

        AiCommandExecution expired = capturedRecord();
        expired.setStatus("PREVIEW");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        expired.setVersion(0);
        when(executionMapper.selectOne(any())).thenReturn(expired);

        BusinessException error = assertThrows(BusinessException.class, () -> service.execute(
                new AiCommandService.AiCommandExecuteRequest(expired.getConfirmationId(), null, null, null), admin));

        assertTrue(error.getMessage().contains("有效期"));
        assertEquals("EXPIRED", expired.getStatus());
    }

    @Test
    void executeReplaysSameConfirmationWhenClientRetriesWithSameKey() {
        MfgWorkOrder draft = workOrder(11L, "WO-202610240011", BusinessStatus.Document.DRAFT);
        when(workOrderMapper.selectOne(any())).thenReturn(draft);
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);
        service.preview(previewRequest("下达工单 WO-202610240011"), admin);

        // 同一条记录：第一次执行成功后状态已是 SUCCEEDED，客户端带同一幂等键重试应回放而不是报错。
        AiCommandExecution executed = capturedRecord();
        executed.setStatus("SUCCEEDED");
        executed.setIdempotencyKey("idem-retry");
        executed.setResultJson("{\"bizNo\":\"WO-202610240011\",\"bizId\":11,\"summary\":\"已下达\"}");
        executed.setElapsedMs(8L);
        executed.setVersion(1);
        when(executionMapper.selectOne(any())).thenReturn(executed);

        Map<String, Object> result = service.execute(new AiCommandService.AiCommandExecuteRequest(
                executed.getConfirmationId(), "idem-retry", null, null), admin);

        assertEquals(Boolean.TRUE, result.get("replayed"));
        assertEquals("SUCCEEDED", result.get("status"));
        verify(workOrderService, org.mockito.Mockito.never()).releaseWorkOrder(any(), any(), any());
    }

    @Test
    void executeRejectsRequestHashMismatch() {
        MfgWorkOrder draft = workOrder(11L, "WO-202610240011", BusinessStatus.Document.DRAFT);
        when(workOrderMapper.selectOne(any())).thenReturn(draft);
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan());
        when(workOrderMaterialMapper.selectCount(any())).thenReturn(3L);
        service.preview(previewRequest("下达工单 WO-202610240011"), admin);

        AiCommandExecution tampered = capturedRecord();
        tampered.setRequestHash("0".repeat(64));
        when(executionMapper.selectOne(any())).thenReturn(tampered);

        BusinessException error = assertThrows(BusinessException.class, () -> service.execute(
                new AiCommandService.AiCommandExecuteRequest(tampered.getConfirmationId(), null, null, null), admin));

        assertTrue(error.getMessage().contains("参数已被改动"));
        assertEquals("REJECTED", tampered.getStatus());
        verify(workOrderService, org.mockito.Mockito.never()).releaseWorkOrder(any(), any(), any());
    }

    @Test
    void previewWithQuantitiesDoesNotLetKeywordOverrideAutoVerdict() {
        com.neuedu.ems.entity.qms.QmsInspectionOrder draft = new com.neuedu.ems.entity.qms.QmsInspectionOrder();
        draft.setInspectionId(9L);
        draft.setInspectionNo("QI1792827230517");
        draft.setStatus(BusinessStatus.Document.DRAFT);
        when(inspectionOrderMapper.selectOne(any())).thenReturn(draft);

        Map<String, Object> preview = service.preview(
                previewRequest("把检验单 QI1792827230517 判为合格，样本5 合格5 不合格0"), admin);

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) preview.get("params");
        // 数量齐全时由领域服务按不合格率判定，关键词「不合格 0」不能把结论改成 FAIL。
        assertNull(params.get("inspectionResult"));
        assertEquals(0, new BigDecimal("0").compareTo((BigDecimal) params.get("unqualifiedQuantity")));
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) params.get("sampleQuantity")));
        assertTrue((Boolean) preview.get("canExecute"));
    }

    @Test
    void schedulePreviewUsesDomainCandidateAndConfirmsTheSameSlot() {
        var orders = mock(com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper.class);
        var scheduling = mock(com.neuedu.ems.service.ai.AiProductionScheduleService.class);
        ReflectionTestUtils.setField(service, "aiProductionOrderMapper", orders);
        ReflectionTestUtils.setField(service, "aiProductionSchedule", scheduling);
        var order = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        order.setProductionOrderId(11L); order.setProductionOrderNo("PO-202610240011"); order.setStatus("DRAFT");
        when(orders.selectOne(any())).thenReturn(order);
        var line = new com.neuedu.ems.vo.mfg.ManualScheduleCandidateLine();
        line.setLineId(2L); line.setLineCode("LINE-2"); line.setLineName("二号线");
        var start = LocalDateTime.of(2026, 11, 5, 8, 0);
        var end = LocalDateTime.of(2026, 11, 5, 10, 0);
        line.setEarliestStartTime(start); line.setEarliestEndTime(end);
        when(scheduling.preview(11L)).thenReturn(List.of(line));
        var plan = new MfgProductionPlan(); plan.setPlanId(90L); plan.setPlanNo("PL-90");
        plan.setStatus("RELEASED"); plan.setDeletedFlag(0);
        when(scheduling.confirm(eq(11L), eq(2L), eq(start), eq(end), eq(7L), any())).thenReturn(plan);
        when(productionPlanMapper.selectById(90L)).thenReturn(plan);
        var preview = service.previewExplicit("SCHEDULE_PRODUCTION_ORDER", "PO-202610240011", Map.of(),
                previewRequest("给生产订单 PO-202610240011 排产"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        var record = capturedRecord();
        when(executionMapper.selectOne(any())).thenReturn(record);
        var result = service.execute(new AiCommandService.AiCommandExecuteRequest(
                record.getConfirmationId(), null, null, null), admin);
        assertEquals("SUCCEEDED", result.get("status"));
        verify(scheduling).confirm(11L, 2L, start, end, 7L, record.getConfirmationId());
    }

    @Test
    void schedulingDoesNotUseModelInventedLine() {
        var orders = mock(com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper.class);
        var scheduling = mock(com.neuedu.ems.service.ai.AiProductionScheduleService.class);
        ReflectionTestUtils.setField(service, "aiProductionOrderMapper", orders);
        ReflectionTestUtils.setField(service, "aiProductionSchedule", scheduling);
        var order = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        order.setProductionOrderId(11L); order.setProductionOrderNo("PO-202610240011"); order.setStatus("DRAFT");
        when(orders.selectOne(any())).thenReturn(order);
        var preview = service.previewExplicit("SCHEDULE_PRODUCTION_ORDER", "PO-202610240011",
                Map.of("lineCode", "FAKE-LINE"), previewRequest("给生产订单 PO-202610240011 排产"), admin);
        assertFalse((Boolean) preview.get("canExecute"));
        verify(scheduling, org.mockito.Mockito.never()).preview(any());
    }

    @Test
    void creationDoesNotTrustCustomerOrQuantityInventedByPlanner() {
        var products = mock(com.neuedu.ems.mapper.mdm.MdmProductMapper.class);
        var customers = mock(com.neuedu.ems.mapper.mdm.MdmCustomerMapper.class);
        ReflectionTestUtils.setField(service, "aiProductMapper", products);
        ReflectionTestUtils.setField(service, "aiCustomerMapper", customers);
        var product = new com.neuedu.ems.entity.mdm.MdmProduct();
        product.setProductId(1L); product.setProductCode("LAMP-L200"); product.setStatus("ENABLE");
        when(products.selectList(any())).thenReturn(List.of(product));
        var preview = service.previewExplicit("CREATE_PRODUCTION_ORDER", "LAMP-L200",
                Map.of("customerCode", "C001", "orderQuantity", 2, "deliveryDate", "2026-11-10"),
                previewRequest("创建 LAMP-L200 生产订单，交期 2026-11-10"), admin);
        assertFalse((Boolean) preview.get("canExecute"));
        assertTrue(preconditionsOf(preview).stream().anyMatch(p -> "CUSTOMER".equals(p.get("code"))
                && Boolean.FALSE.equals(p.get("passed"))));
        assertTrue(preconditionsOf(preview).stream().anyMatch(p -> "QUANTITY".equals(p.get("code"))
                && Boolean.FALSE.equals(p.get("passed"))));
        verify(customers, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    void creationRequiresOrderPermissionBeforeResolvingProduct() {
        var user = new AuthorizationContext(7L, 8L, 9L, "ALL", Set.of(), Set.of("ai:assistant:use"), Set.of());
        assertThrows(com.neuedu.ems.exception.DataScopeAccessDeniedException.class,
                () -> service.previewExplicit("CREATE_PRODUCTION_ORDER", "LAMP-L200",
                        Map.of("customerCode", "C001", "orderQuantity", 2, "deliveryDate", "2026-11-10"),
                        previewRequest("为客户 C001 创建 LAMP-L200 生产订单 2台 交期 2026-11-10"), user));
    }

    @Test
    void createProductionOrderRequiresRealInputsAndExecutesOnce() {
        var products = mock(com.neuedu.ems.mapper.mdm.MdmProductMapper.class);
        var customers = mock(com.neuedu.ems.mapper.mdm.MdmCustomerMapper.class);
        var orders = mock(com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper.class);
        var creator = mock(com.neuedu.ems.service.ai.AiProductionOrderCreationService.class);
        ReflectionTestUtils.setField(service, "aiProductMapper", products);
        ReflectionTestUtils.setField(service, "aiCustomerMapper", customers);
        ReflectionTestUtils.setField(service, "aiProductionOrderMapper", orders);
        ReflectionTestUtils.setField(service, "aiProductionOrderCreation", creator);
        var product = new com.neuedu.ems.entity.mdm.MdmProduct();
        product.setProductId(1L); product.setProductCode("LAMP-L200"); product.setStatus("ENABLE"); product.setDeletedFlag(0);
        var customer = new com.neuedu.ems.entity.mdm.MdmCustomer();
        customer.setCustomerId(2L); customer.setCustomerCode("C001"); customer.setCustomerName("客户A");
        customer.setStatus("ENABLE"); customer.setDeletedFlag(0);
        when(products.selectList(any())).thenReturn(List.of(product));
        when(customers.selectList(any())).thenReturn(List.of(customer));
        when(customers.selectById(2L)).thenReturn(customer);
        var order = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        order.setProductionOrderId(88L); order.setProductionOrderNo("PO-88"); order.setStatus("DRAFT"); order.setDeletedFlag(0);
        when(orders.selectById(88L)).thenReturn(order);
        when(creator.create(eq(1L), eq(2L), any(), any(), eq(7L), any())).thenReturn(order);
        String text = "为客户 C001 创建 LAMP-L200 生产订单 2台 交期 2026-11-10";
        var preview = service.previewExplicit("CREATE_PRODUCTION_ORDER", "LAMP-L200",
                Map.of("customerCode", "C001", "orderQuantity", 2, "deliveryDate", "2026-11-10"),
                previewRequest(text), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        var record = capturedRecord();
        when(executionMapper.selectOne(any())).thenReturn(record);
        var result = service.execute(new AiCommandService.AiCommandExecuteRequest(
                record.getConfirmationId(), null, null, null), admin);
        assertEquals("SUCCEEDED", result.get("status"));
        verify(creator).create(eq(1L), eq(2L), any(), any(), eq(7L), eq(record.getConfirmationId()));
    }

    @Test
    void structuredCreationAcceptsFormValuesWithoutThemAppearingInText() {
        var products = mock(com.neuedu.ems.mapper.mdm.MdmProductMapper.class);
        var customers = mock(com.neuedu.ems.mapper.mdm.MdmCustomerMapper.class);
        ReflectionTestUtils.setField(service, "aiProductMapper", products);
        ReflectionTestUtils.setField(service, "aiCustomerMapper", customers);
        var product = new com.neuedu.ems.entity.mdm.MdmProduct();
        product.setProductId(1L); product.setProductCode("LAMP-L200"); product.setStatus("ENABLE"); product.setDeletedFlag(0);
        var customer = new com.neuedu.ems.entity.mdm.MdmCustomer();
        customer.setCustomerId(2L); customer.setCustomerCode("C001"); customer.setCustomerName("客户A");
        customer.setStatus("ENABLE"); customer.setDeletedFlag(0);
        when(products.selectList(any())).thenReturn(List.of(product));
        when(customers.selectList(any())).thenReturn(List.of(customer));
        when(customers.selectById(2L)).thenReturn(customer);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("productCode", "LAMP-L200");
        form.put("customerCode", "C001");
        form.put("orderQuantity", "2");
        form.put("deliveryDate", "2026-11-10");
        var preview = service.previewStructured("CREATE_PRODUCTION_ORDER", form,
                previewRequest("创建一个生产订单"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        assertEquals("LAMP-L200", preview.get("targetNo"));
        var params = paramsOf(preview);
        assertEquals(2L, params.get("customerId"));
    }

    @Test
    void structuredCreationRejectsIncompleteForm() {
        Map<String, Object> missingCustomer = new LinkedHashMap<>();
        missingCustomer.put("productCode", "LAMP-L200");
        missingCustomer.put("orderQuantity", "2");
        missingCustomer.put("deliveryDate", "2026-11-10");
        assertThrows(BusinessException.class, () -> service.previewStructured("CREATE_PRODUCTION_ORDER",
                missingCustomer, previewRequest("创建一个生产订单"), admin));

        Map<String, Object> badQuantity = new LinkedHashMap<>();
        badQuantity.put("productCode", "LAMP-L200");
        badQuantity.put("customerCode", "C001");
        badQuantity.put("orderQuantity", "0");
        badQuantity.put("deliveryDate", "2026-11-10");
        assertThrows(BusinessException.class, () -> service.previewStructured("CREATE_PRODUCTION_ORDER",
                badQuantity, previewRequest("创建一个生产订单"), admin));
    }

    @Test
    void createOrderOptionsListsEnabledProductsAndCustomers() {
        var products = mock(com.neuedu.ems.mapper.mdm.MdmProductMapper.class);
        var customers = mock(com.neuedu.ems.mapper.mdm.MdmCustomerMapper.class);
        ReflectionTestUtils.setField(service, "aiProductMapper", products);
        ReflectionTestUtils.setField(service, "aiCustomerMapper", customers);
        var product = new com.neuedu.ems.entity.mdm.MdmProduct();
        product.setProductId(1L); product.setProductCode("LAMP-L200"); product.setProductName("智能调光台灯 L200");
        product.setStatus("ENABLE"); product.setDeletedFlag(0);
        var customer = new com.neuedu.ems.entity.mdm.MdmCustomer();
        customer.setCustomerId(2L); customer.setCustomerCode("C001"); customer.setCustomerName("客户A");
        customer.setStatus("ENABLE"); customer.setDeletedFlag(0);
        when(products.selectList(any())).thenReturn(List.of(product));
        when(customers.selectList(any())).thenReturn(List.of(customer));
        Map<String, Object> options = service.createOrderOptions(admin);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> productsOption = (List<Map<String, Object>>) options.get("products");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> customersOption = (List<Map<String, Object>>) options.get("customers");
        assertEquals(1, productsOption.size());
        assertEquals("LAMP-L200", productsOption.get(0).get("productCode"));
        assertEquals(1, customersOption.size());
        assertEquals("C001", customersOption.get(0).get("customerCode"));
    }

    @Test
    void structuredScheduleAcceptsOrderAndLinePickedFromDropdown() {
        var orders = mock(com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper.class);
        var scheduling = mock(com.neuedu.ems.service.ai.AiProductionScheduleService.class);
        ReflectionTestUtils.setField(service, "aiProductionOrderMapper", orders);
        ReflectionTestUtils.setField(service, "aiProductionSchedule", scheduling);
        var order = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        order.setProductionOrderId(11L); order.setProductionOrderNo("C1-PO-20261031-005");
        order.setStatus("DRAFT"); order.setDeletedFlag(0);
        when(orders.selectOne(any())).thenReturn(order);
        var line = new com.neuedu.ems.vo.mfg.ManualScheduleCandidateLine();
        line.setLineId(2L); line.setLineCode("LINE-2"); line.setLineName("二号线");
        line.setEarliestStartTime(LocalDateTime.of(2026, 11, 5, 8, 0));
        line.setEarliestEndTime(LocalDateTime.of(2026, 11, 5, 10, 0));
        when(scheduling.preview(11L)).thenReturn(List.of(line));
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("orderNo", "C1-PO-20261031-005");
        form.put("lineCode", "LINE-2");
        var preview = service.previewStructured("SCHEDULE_PRODUCTION_ORDER", form,
                previewRequest("把订单下发下去并且进行排产"), admin);
        assertTrue((Boolean) preview.get("canExecute"), String.valueOf(preconditionsOf(preview)));
        assertEquals("C1-PO-20261031-005", preview.get("targetNo"));
        assertEquals(2L, paramsOf(preview).get("lineId"));
    }

    @Test
    void structuredScheduleRejectsEmptyOrderSelection() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("lineCode", "LINE-2");
        assertThrows(BusinessException.class, () -> service.previewStructured("SCHEDULE_PRODUCTION_ORDER",
                form, previewRequest("排产"), admin));
    }

    @Test
    void scheduleOptionsListsOnlyDraftOrdersWithoutPlanAndTheirLines() {
        var orders = mock(com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper.class);
        var scheduling = mock(com.neuedu.ems.service.ai.AiProductionScheduleService.class);
        ReflectionTestUtils.setField(service, "aiProductionOrderMapper", orders);
        ReflectionTestUtils.setField(service, "aiProductionSchedule", scheduling);
        var schedulable = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        schedulable.setProductionOrderId(6L); schedulable.setProductionOrderNo("C1-PO-20261031-005");
        schedulable.setOrderQuantity(new BigDecimal("4")); schedulable.setStatus("DRAFT"); schedulable.setDeletedFlag(0);
        var alreadyPlanned = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        alreadyPlanned.setProductionOrderId(7L); alreadyPlanned.setProductionOrderNo("C1-PO-20261031-006");
        alreadyPlanned.setStatus("DRAFT"); alreadyPlanned.setDeletedFlag(0);
        when(orders.selectList(any())).thenReturn(List.of(schedulable, alreadyPlanned));
        when(orders.selectOne(any())).thenReturn(schedulable);
        // 6 号还没有计划，7 号已经排过了：列表里只该出现 6 号。
        when(productionPlanMapper.selectCount(any())).thenReturn(0L, 1L);
        var line = new com.neuedu.ems.vo.mfg.ManualScheduleCandidateLine();
        line.setLineId(2L); line.setLineCode("LINE-2"); line.setLineName("二号线");
        line.setEarliestStartTime(LocalDateTime.of(2026, 11, 5, 8, 0));
        line.setEarliestEndTime(LocalDateTime.of(2026, 11, 5, 10, 0));
        when(scheduling.preview(6L)).thenReturn(List.of(line));

        Map<String, Object> options = service.scheduleOptions(null, admin);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orderOptions = (List<Map<String, Object>>) options.get("orders");
        assertEquals(1, orderOptions.size());
        assertEquals("C1-PO-20261031-005", orderOptions.get(0).get("productionOrderNo"));

        Map<String, Object> picked = service.scheduleOptions("C1-PO-20261031-005", admin);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lineOptions = (List<Map<String, Object>>) picked.get("lines");
        assertEquals(1, lineOptions.size());
        assertEquals("LINE-2", lineOptions.get(0).get("lineCode"));
    }

    @Test
    void capabilitiesExposeNineteenActions() {
        List<Map<String, Object>> capabilities = service.capabilities();
        assertEquals(19, capabilities.size());
        assertTrue(capabilities.stream().anyMatch(item -> "START_TASK".equals(item.get("action"))));
        // 订单创建到发货必须首尾打通：缺任一段，AI 就办不完完整流程。
        assertTrue(capabilities.stream().anyMatch(item -> "CREATE_PRODUCTION_ORDER".equals(item.get("action"))));
        assertTrue(capabilities.stream().anyMatch(item -> "GENERATE_PRODUCTION_INBOUND".equals(item.get("action"))));
        assertTrue(capabilities.stream().anyMatch(item -> "GENERATE_SALES_OUTBOUND".equals(item.get("action"))));
        assertTrue(capabilities.stream().anyMatch(item -> "CREATE_OUTGOING_INSPECTION".equals(item.get("action"))));
        assertTrue(capabilities.stream().anyMatch(item -> "CONFIRM_SALES_OUTBOUND".equals(item.get("action"))));
        assertTrue(capabilities.stream().anyMatch(item -> "GENERATE_WORK_ORDER".equals(item.get("action"))));
        assertTrue(capabilities.stream().anyMatch(item -> "GENERATE_DISPATCH_ORDER".equals(item.get("action"))));
        assertTrue(capabilities.stream().allMatch(item -> item.get("permission") != null
                && item.get("example") != null && item.get("actionLabel") != null));
    }

    /* ---------- 构造辅助 ---------- */

    private AiCommandService.AiCommandPreviewRequest previewRequest(String text) {
        return new AiCommandService.AiCommandPreviewRequest(text, "conv-1", "WORKBENCH", new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> preconditionsOf(Map<String, Object> preview) {
        return (List<Map<String, Object>>) preview.get("preconditions");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramsOf(Map<String, Object> preview) {
        return (Map<String, Object>) preview.get("params");
    }

    private AiCommandExecution capturedRecord() {
        ArgumentCaptor<AiCommandExecution> captor = ArgumentCaptor.forClass(AiCommandExecution.class);
        verify(executionMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }

    private com.neuedu.ems.entity.wms.WmsWarehouse rawWarehouse(Long id) {
        com.neuedu.ems.entity.wms.WmsWarehouse warehouse = new com.neuedu.ems.entity.wms.WmsWarehouse();
        warehouse.setWarehouseId(id);
        warehouse.setWarehouseName("台灯原料仓");
        warehouse.setWarehouseType("RAW");
        warehouse.setStatus(BusinessStatus.Common.ENABLE);
        warehouse.setDeletedFlag(0);
        return warehouse;
    }

    private MfgWorkOrderMaterial outstanding(Long materialId, String required, String issued) {
        MfgWorkOrderMaterial material = new MfgWorkOrderMaterial();
        material.setRequirementId(materialId);
        material.setWorkOrderId(11L);
        material.setMaterialId(materialId);
        material.setRequiredQuantity(new BigDecimal(required));
        material.setIssuedQuantity(new BigDecimal(issued));
        material.setShortageQuantity(new BigDecimal(required).subtract(new BigDecimal(issued)));
        return material;
    }

    private com.neuedu.ems.entity.mfg.MfgSupplyAllocation reservedOf(Long materialId, String quantity) {
        com.neuedu.ems.entity.mfg.MfgSupplyAllocation allocation = new com.neuedu.ems.entity.mfg.MfgSupplyAllocation();
        allocation.setWorkOrderId(11L);
        allocation.setMaterialId(materialId);
        allocation.setReservedQuantity(new BigDecimal(quantity));
        allocation.setStatus("RESERVED");
        allocation.setDeletedFlag(0);
        return allocation;
    }

    private com.neuedu.ems.entity.wms.WmsInventory inventoryOf(Long materialId, String available) {
        com.neuedu.ems.entity.wms.WmsInventory inventory = new com.neuedu.ems.entity.wms.WmsInventory();
        inventory.setWarehouseId(1L);
        inventory.setMaterialId(materialId);
        inventory.setAvailableQuantity(new BigDecimal(available));
        inventory.setWorkOrderId(0L);
        inventory.setDeletedFlag(0);
        return inventory;
    }

    private MfgWorkOrder workOrder(Long id, String no, String status) {
        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderId(id);
        order.setWorkOrderNo(no);
        order.setStatus(status);
        order.setPlanId(6L);
        order.setBomId(3L);
        order.setRouteId(4L);
        order.setProductId(2L);
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

    private MfgWorkOrderMaterial material(Long workOrderId, String issued, String required) {
        MfgWorkOrderMaterial item = new MfgWorkOrderMaterial();
        item.setWorkOrderId(workOrderId);
        item.setIssuedQuantity(new BigDecimal(issued));
        item.setRequiredQuantity(new BigDecimal(required));
        return item;
    }
}
