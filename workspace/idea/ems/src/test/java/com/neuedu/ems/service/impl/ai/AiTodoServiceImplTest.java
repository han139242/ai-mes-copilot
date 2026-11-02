package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.eam.EamEquipment;
import com.neuedu.ems.entity.ai.AiAutomationRun;
import com.neuedu.ems.entity.mfg.MfgException;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.mapper.ai.AiAutomationRunMapper;
import com.neuedu.ems.mapper.eam.EamEquipmentMapper;
import com.neuedu.ems.mapper.mfg.MfgExceptionMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 我的待办聚合单元测试：九类来源都来自业务表真实状态，动作按权限过滤。
 */
class AiTodoServiceImplTest {

    private AiTodoServiceImpl service;
    private WmsOutboundOrderMapper outboundOrderMapper;
    private WmsInboundOrderMapper inboundOrderMapper;
    private MfgWorkReportMapper workReportMapper;
    private QmsInspectionOrderMapper inspectionOrderMapper;
    private MfgWorkOrderMapper workOrderMapper;
    private MfgWorkOrderMaterialMapper workOrderMaterialMapper;
    private MfgProductionPlanMapper productionPlanMapper;
    private EamEquipmentMapper equipmentMapper;
    private AiAutomationRunMapper automationRunMapper;
    private MfgExceptionMapper exceptionMapper;

    private final AuthorizationContext admin = new AuthorizationContext(7L, 8L, 9L, "ALL",
            Set.of("SUPER_ADMIN"), Set.of("ai:assistant:use"), Set.of());

    private final AuthorizationContext viewer = new AuthorizationContext(11L, 12L, 13L, "ALL",
            Set.of("WORKER"), Set.of("ai:assistant:use"), Set.of());

    @BeforeEach
    void setUp() {
        service = new AiTodoServiceImpl();
        outboundOrderMapper = mock(WmsOutboundOrderMapper.class);
        inboundOrderMapper = mock(WmsInboundOrderMapper.class);
        workReportMapper = mock(MfgWorkReportMapper.class);
        inspectionOrderMapper = mock(QmsInspectionOrderMapper.class);
        workOrderMapper = mock(MfgWorkOrderMapper.class);
        workOrderMaterialMapper = mock(MfgWorkOrderMaterialMapper.class);
        productionPlanMapper = mock(MfgProductionPlanMapper.class);
        equipmentMapper = mock(EamEquipmentMapper.class);
        automationRunMapper = mock(AiAutomationRunMapper.class);
        exceptionMapper = mock(MfgExceptionMapper.class);

        ReflectionTestUtils.setField(service, "outboundOrderMapper", outboundOrderMapper);
        ReflectionTestUtils.setField(service, "inboundOrderMapper", inboundOrderMapper);
        ReflectionTestUtils.setField(service, "workReportMapper", workReportMapper);
        ReflectionTestUtils.setField(service, "inspectionOrderMapper", inspectionOrderMapper);
        ReflectionTestUtils.setField(service, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "workOrderMaterialMapper", workOrderMaterialMapper);
        ReflectionTestUtils.setField(service, "productionPlanMapper", productionPlanMapper);
        ReflectionTestUtils.setField(service, "equipmentMapper", equipmentMapper);
        ReflectionTestUtils.setField(service, "automationRunMapper", automationRunMapper);
        ReflectionTestUtils.setField(service, "exceptionMapper", exceptionMapper);
    }

    @Test
    void myTodosCollectsNineTypesFromBusinessTables() {
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of(materialIssue()));
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(workReportMapper.selectList(any())).thenReturn(List.of());
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of(draftInspection()));
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of(shortage()));
        when(workOrderMapper.selectList(any())).thenReturn(List.of(releasedOrder()));
        when(workOrderMapper.selectById(any())).thenReturn(releasedOrder());
        when(productionPlanMapper.selectById(any())).thenReturn(overduePlan());
        when(equipmentMapper.selectList(any())).thenReturn(List.of(faultEquipment()));
        when(automationRunMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectList(any())).thenReturn(List.of(autoException()));

        Map<String, Object> data = service.myTodos(admin);

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) data.get("counts");
        // 九类都返回，便于前端直接绑定角标。
        assertEquals(9, counts.size());
        assertEquals(1, counts.get("PENDING_MATERIAL_ISSUE"));
        assertEquals(1, counts.get("PENDING_INBOUND"));
        assertEquals(1, counts.get("PENDING_INSPECTION"));
        assertEquals(1, counts.get("MATERIAL_SHORTAGE"));
        assertEquals(1, counts.get("WORK_ORDER_DELAY"));
        assertEquals(1, counts.get("EQUIPMENT_FAULT"));
        assertEquals(1, counts.get("EXCEPTION_PENDING"));
        assertEquals(0, counts.get("PENDING_WORK_REPORT"));
        assertEquals(0, counts.get("AUTOMATION_FAILED"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertFalse(items.isEmpty());
        assertEquals(items.size(), data.get("total"));
        assertEquals(Boolean.FALSE, data.get("truncated"));
        // 高优先级（缺料、延期、设备故障）排在前面。
        assertEquals("HIGH", items.get(0).get("severity"));
    }

    @Test
    void countsAndTotalUseEveryTodoEvenWhenItemsAreTruncated() {
        // 251 条待确认领料单：明细最多返回 200 条，但条数必须按全量算。
        List<WmsOutboundOrder> many = new ArrayList<>();
        for (int index = 0; index < 251; index++) {
            many.add(materialIssue(100L + index));
        }
        when(outboundOrderMapper.selectList(any())).thenReturn(many);
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(workReportMapper.selectList(any())).thenReturn(List.of());
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        when(automationRunMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> data = service.myTodos(admin);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) data.get("counts");
        assertEquals(200, items.size());
        assertEquals(251, data.get("total"));
        assertEquals(Boolean.TRUE, data.get("truncated"));
        assertEquals(251, counts.get("PENDING_MATERIAL_ISSUE"));
    }

    @Test
    void actionsDisappearWhenAccountLacksActionPermission() {
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of(materialIssue()));
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of(inbound()));
        when(workReportMapper.selectList(any())).thenReturn(List.of());
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of(draftInspection()));
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        // 设备故障与异常闭环依赖页面查看权限，只给了 ai:assistant:use 的账号看不到按钮。
        when(equipmentMapper.selectList(any())).thenReturn(List.of(faultEquipment()));
        when(automationRunMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectList(any())).thenReturn(List.of(autoException()));

        Map<String, Object> data = service.myTodos(viewer);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertFalse(items.isEmpty());
        for (Map<String, Object> item : items) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> actions = (List<Map<String, Object>>) item.get("actions");
            // 没有动作权限时不能给出按钮，避免点了必然报无权限。
            assertTrue(actions.isEmpty(), "无权限账号不应出现动作：" + item.get("type"));
        }
    }

    /**
     * 同一张工单缺几种料只应出一条待办，并且组件子工单还没下达时，
     * 推荐的必须是「下达组件工单」而不是必然失败的「生成领料单」。
     */
    @Test
    void shortageItemsMergePerWorkOrderAndAdviseReleasingComponentsFirst() {
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(workReportMapper.selectList(any())).thenReturn(List.of());
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of(
                shortageOf(19L, "6"), shortageOf(20L, "2"), shortageOf(22L, "2")));
        when(workOrderMapper.selectById(any())).thenReturn(releasedOrder());
        when(workOrderMapper.selectList(any())).thenReturn(List.of(draftComponent()));
        when(productionPlanMapper.selectById(any())).thenReturn(null);
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        when(automationRunMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> data = service.myTodos(admin);

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) data.get("counts");
        assertEquals(1, counts.get("MATERIAL_SHORTAGE"), "三行缺料应合并成一条待办");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        Map<String, Object> shortageItem = items.stream()
                .filter(item -> "MATERIAL_SHORTAGE".equals(item.get("type")))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(shortageItem.get("summary")).contains("组件工单"),
                "组件未下达时应提示先下达组件：" + shortageItem.get("summary"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) shortageItem.get("actions");
        assertEquals(1, actions.size());
        assertEquals("RELEASE_WORK_ORDER", actions.get(0).get("code"),
                "推荐动作应是下达组件工单，不是生成领料单");
    }

    /** 组件都下达完、只剩原料缺口时，才推「生成领料单」。 */
    @Test
    void shortageAdvisesMaterialIssueWhenNoDraftComponentRemains() {
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(workReportMapper.selectList(any())).thenReturn(List.of());
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of(shortageOf(18L, "4")));
        when(workOrderMapper.selectById(any())).thenReturn(releasedOrder());
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        when(productionPlanMapper.selectById(any())).thenReturn(null);
        when(equipmentMapper.selectList(any())).thenReturn(List.of());
        when(automationRunMapper.selectList(any())).thenReturn(List.of());
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> data = service.myTodos(admin);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        Map<String, Object> shortageItem = items.stream()
                .filter(item -> "MATERIAL_SHORTAGE".equals(item.get("type")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) shortageItem.get("actions");
        assertEquals(1, actions.size());
        assertEquals("GENERATE_MATERIAL_ISSUE", actions.get(0).get("code"));
    }

    /**
     * 设备故障、异常闭环、自动化失败这三类没有可预览的写动作，只能给人指路，
     * 所以要给跳转动作并带上定位参数，否则页面上是「看得见点不动」的死待办。
     */
    @Test
    void deadEndTodosCarryViewActionsWithFocus() {
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(workReportMapper.selectList(any())).thenReturn(List.of());
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        when(equipmentMapper.selectList(any())).thenReturn(List.of(faultEquipment()));
        when(automationRunMapper.selectList(any())).thenReturn(List.of(failedAutomationRun()));
        when(exceptionMapper.selectList(any())).thenReturn(List.of(autoException()));

        Map<String, Object> data = service.myTodos(admin);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");

        assertEquals("view", firstAction(items, "EQUIPMENT_FAULT").get("kind"));
        assertEquals("/eam/equipment", firstAction(items, "EQUIPMENT_FAULT").get("route"));
        assertEquals("5", focusOf(firstAction(items, "EQUIPMENT_FAULT")));

        assertEquals("/mfg/exceptions", firstAction(items, "EXCEPTION_PENDING").get("route"));
        assertEquals("2", focusOf(firstAction(items, "EXCEPTION_PENDING")));

        assertEquals("/ai/automation", firstAction(items, "AUTOMATION_FAILED").get("route"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runQuery = (Map<String, Object>) firstAction(items, "AUTOMATION_FAILED").get("query");
        assertEquals("runs", runQuery.get("tab"));
        assertEquals("77", runQuery.get("runId"));
    }

    /** 没有目标页面查看权限时不给跳转按钮，避免点进去必然 403。 */
    @Test
    void viewActionsSkipPagesTheAccountCannotOpen() {
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(inboundOrderMapper.selectList(any())).thenReturn(List.of());
        when(workReportMapper.selectList(any())).thenReturn(List.of());
        when(inspectionOrderMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMaterialMapper.selectList(any())).thenReturn(List.of());
        when(workOrderMapper.selectList(any())).thenReturn(List.of());
        when(equipmentMapper.selectList(any())).thenReturn(List.of(faultEquipment()));
        when(automationRunMapper.selectList(any())).thenReturn(List.of(failedAutomationRun()));
        when(exceptionMapper.selectList(any())).thenReturn(List.of(autoException()));

        // 只有设备台账查看权限，没有生产异常查看权限。
        AuthorizationContext equipmentReader = new AuthorizationContext(31L, 32L, 33L, "ALL",
                Set.of("EQUIPMENT_ENGINEER"), Set.of("ai:assistant:use", "eam:equipment:view"), Set.of());
        Map<String, Object> data = service.myTodos(equipmentReader);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");

        // 事项本身照旧出现，只是异常闭环那条没有按钮。
        assertFalse(actionsOf(items, "EQUIPMENT_FAULT").isEmpty());
        assertFalse(actionsOf(items, "AUTOMATION_FAILED").isEmpty());
        assertTrue(actionsOf(items, "EXCEPTION_PENDING").isEmpty());
    }

    /* ---------- 构造辅助 ---------- */

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> actionsOf(List<Map<String, Object>> items, String type) {
        return (List<Map<String, Object>>) items.stream()
                .filter(item -> type.equals(item.get("type")))
                .findFirst().orElseThrow().get("actions");
    }

    private Map<String, Object> firstAction(List<Map<String, Object>> items, String type) {
        List<Map<String, Object>> actions = actionsOf(items, type);
        assertEquals(1, actions.size(), "应当有且只有一条动作：" + type);
        return actions.get(0);
    }

    @SuppressWarnings("unchecked")
    private Object focusOf(Map<String, Object> action) {
        return ((Map<String, Object>) action.get("query")).get("focus");
    }

    private MfgWorkOrderMaterial shortageOf(Long materialId, String shortage) {
        MfgWorkOrderMaterial material = new MfgWorkOrderMaterial();
        material.setRequirementId(materialId);
        material.setWorkOrderId(13L);
        material.setMaterialId(materialId);
        material.setRequiredQuantity(new BigDecimal(shortage).add(new BigDecimal("4")));
        material.setIssuedQuantity(new BigDecimal("4"));
        material.setShortageQuantity(new BigDecimal(shortage));
        return material;
    }

    private MfgWorkOrder draftComponent() {
        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderId(21L);
        order.setWorkOrderNo("WO-C-21-202610250001");
        order.setStatus(BusinessStatus.Document.DRAFT);
        order.setWorkOrderType("COMPONENT");
        order.setRootWorkOrderId(13L);
        order.setDeletedFlag(0);
        return order;
    }

    private WmsOutboundOrder materialIssue() {
        return materialIssue(6L);
    }

    private WmsOutboundOrder materialIssue(Long outboundId) {
        WmsOutboundOrder order = new WmsOutboundOrder();
        order.setOutboundId(outboundId);
        order.setOutboundNo("MI-WO13-202610250001");
        order.setOutboundType("MATERIAL_ISSUE");
        order.setAuditStatus(BusinessStatus.Document.SUBMITTED);
        order.setWorkOrderId(13L);
        order.setCreatedTime(LocalDateTime.now().minusHours(2));
        return order;
    }

    private WmsInboundOrder inbound() {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setInboundId(53L);
        order.setInboundNo("IN-AITASK-20261025031057");
        order.setInboundType("PURCHASE");
        order.setAuditStatus(BusinessStatus.Document.SUBMITTED);
        order.setCreatedTime(LocalDateTime.now().minusMinutes(30));
        return order;
    }

    private QmsInspectionOrder draftInspection() {
        QmsInspectionOrder order = new QmsInspectionOrder();
        order.setInspectionId(9L);
        order.setInspectionNo("QI1792827230517");
        order.setInspectionType("INCOMING");
        order.setStatus(BusinessStatus.Document.DRAFT);
        order.setSampleQuantity(new BigDecimal("5"));
        order.setCreatedTime(LocalDateTime.now().minusHours(1));
        return order;
    }

    private MfgWorkOrderMaterial shortage() {
        MfgWorkOrderMaterial material = new MfgWorkOrderMaterial();
        material.setRequirementId(1L);
        material.setWorkOrderId(13L);
        material.setMaterialId(19L);
        material.setRequiredQuantity(new BigDecimal("10"));
        material.setIssuedQuantity(new BigDecimal("4"));
        material.setShortageQuantity(new BigDecimal("6"));
        return material;
    }

    private MfgWorkOrder releasedOrder() {
        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderId(13L);
        order.setWorkOrderNo("WO-13-20261024201042380");
        order.setPlanId(8L);
        order.setStatus(BusinessStatus.Document.RELEASED);
        order.setPlannedQuantity(new BigDecimal("100"));
        order.setCompletedQuantity(new BigDecimal("20"));
        order.setDeletedFlag(0);
        order.setCreatedTime(LocalDateTime.now().minusDays(1));
        return order;
    }

    private MfgProductionPlan overduePlan() {
        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanId(8L);
        plan.setPlanNo("PLN-13-20261024201042380");
        plan.setStatus(BusinessStatus.Document.RELEASED);
        plan.setPlannedStartTime(LocalDateTime.now().minusDays(2));
        plan.setPlannedEndTime(LocalDateTime.now().minusHours(3));
        return plan;
    }

    private EamEquipment faultEquipment() {
        EamEquipment equipment = new EamEquipment();
        equipment.setEquipmentId(5L);
        equipment.setEquipmentCode("EQ-005");
        equipment.setEquipmentName("贴片机 5 号");
        equipment.setModel("SMT-500");
        equipment.setStatus(BusinessStatus.Equipment.FAULT);
        equipment.setUpdatedTime(LocalDateTime.now().minusMinutes(20));
        return equipment;
    }

    private MfgException autoException() {
        MfgException exception = new MfgException();
        exception.setExceptionId(2L);
        exception.setExceptionNo("EXC-AUTO-202610250001");
        exception.setExceptionType("INSPECTION_OVERDUE");
        exception.setDescription("检验超期未处理");
        exception.setStatus(BusinessStatus.Document.SUBMITTED);
        exception.setCreatedTime(LocalDateTime.now().minusHours(4));
        return exception;
    }

    private AiAutomationRun failedAutomationRun() {
        AiAutomationRun run = new AiAutomationRun();
        run.setRunId(77L);
        run.setRunNo("RUN-202610250001");
        run.setRuleId(3L);
        run.setRuleNo("RULE-003");
        run.setRuleName("安全库存预警");
        run.setTriggerType("SCHEDULED");
        run.setStatus("FAILED");
        run.setErrorMessage("库存服务超时");
        run.setStartedTime(LocalDateTime.now().minusHours(1));
        return run;
    }
}
