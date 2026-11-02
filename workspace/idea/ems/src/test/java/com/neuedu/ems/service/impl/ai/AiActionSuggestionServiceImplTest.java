package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.service.ai.AiActionSuggestionService.ActionSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
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
 * 回答操作入口建议单元测试：只给真实可执行的动作，不给假按钮。
 */
class AiActionSuggestionServiceImplTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CANCELLED", "CLOSED"})
    void terminalOrdersDoNotOfferProductionOrShippingCommands(String status) {
        var mapper = mock(com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper.class);
        ReflectionTestUtils.setField(service, "productionOrderMapper", mapper);
        var order = new com.neuedu.ems.entity.mfg.MfgProductionOrder();
        order.setProductionOrderId(1L);
        order.setProductionOrderNo("C1-PO-20261101-001");
        order.setStatus(status);
        when(mapper.selectOne(any())).thenReturn(order);
        var actions = service.suggest("order_progress", Map.of(),
                List.of(row("productionOrderNo", order.getProductionOrderNo())), admin);
        assertTrue(actions.stream().noneMatch(a -> ActionSuggestion.KIND_COMMAND.equals(a.kind())));
    }

    private AiActionSuggestionServiceImpl service;
    private MfgWorkOrderMapper workOrderMapper;
    private MfgProductionPlanMapper productionPlanMapper;
    private QmsInspectionOrderMapper inspectionOrderMapper;
    private com.neuedu.ems.service.ai.AiDataQueryService dataQueryService;

    private final AuthorizationContext admin = new AuthorizationContext(7L, 8L, 9L, "ALL",
            Set.of("SUPER_ADMIN"), Set.of("ai:assistant:use"), Set.of());

    @BeforeEach
    void setUp() {
        service = new AiActionSuggestionServiceImpl();
        workOrderMapper = mock(MfgWorkOrderMapper.class);
        productionPlanMapper = mock(MfgProductionPlanMapper.class);
        inspectionOrderMapper = mock(QmsInspectionOrderMapper.class);
        dataQueryService = mock(com.neuedu.ems.service.ai.AiDataQueryService.class);
        ReflectionTestUtils.setField(service, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "productionPlanMapper", productionPlanMapper);
        ReflectionTestUtils.setField(service, "inspectionOrderMapper", inspectionOrderMapper);
        ReflectionTestUtils.setField(service, "dataQueryService", dataQueryService);
        // 默认按「前置条件全部通过」应答；个别用例再覆盖成未通过。
        when(dataQueryService.queryWorkOrderReadiness(any())).thenReturn(readiness(checks(true)));
    }

    @Test
    void draftWorkOrderOffersReleaseButNotStart() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-11", BusinessStatus.Document.DRAFT));

        List<ActionSuggestion> actions = service.suggest("work_order_detail", Map.of(),
                List.of(row("workOrderNo", "WO-11")), admin);

        assertTrue(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER
                .equals(item.code())));
        // 草稿状态还没下达，开始生产必然失败，不能出现在入口里。
        assertFalse(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_START_WORK_ORDER
                .equals(item.code())));
    }

    @Test
    void releasedWorkOrderOffersStartAndMaterialIssue() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-12", BusinessStatus.Document.RELEASED));

        List<ActionSuggestion> actions = service.suggest("work_order_list", Map.of(),
                List.of(row("workOrderNo", "WO-12")), admin);

        assertTrue(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_START_WORK_ORDER
                .equals(item.code())));
        assertTrue(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_GENERATE_MATERIAL_ISSUE
                .equals(item.code())));
    }

    @Test
    void finishedWorkOrderOffersNoCommandAction() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-13", BusinessStatus.Production.FINISHED));

        List<ActionSuggestion> actions = service.suggest("work_order_detail", Map.of(),
                List.of(row("workOrderNo", "WO-13")), admin);

        // 已完工的工单没有任何可执行的写动作，只剩查看入口。
        assertTrue(actions.stream().noneMatch(item -> ActionSuggestion.KIND_COMMAND.equals(item.kind())));
        assertTrue(actions.stream().anyMatch(item -> ActionSuggestion.KIND_VIEW.equals(item.kind())));
    }

    @Test
    void scheduleCapacityWithoutConfirmableOrderOffersNoConfirmButton() {
        // 排程产能查询里没有可确认的排程单，不能凭意图硬给「确认排程」。
        List<ActionSuggestion> actions = service.suggest("schedule_capacity", Map.of(),
                List.of(row("lineCode", "L1"), row("lineCode", "L2")), admin);

        assertTrue(actions.stream().noneMatch(item -> ActionSuggestion.KIND_COMMAND.equals(item.kind())));
        assertTrue(actions.stream().allMatch(item -> "schedule_capacity".equals(item.code())
                || ActionSuggestion.KIND_VIEW.equals(item.kind())));
    }

    @Test
    void inspectionActionsOnlyForDraftInspection() {
        QmsInspectionOrder draft = new QmsInspectionOrder();
        draft.setInspectionId(9L);
        draft.setInspectionNo("QI1792827230517");
        draft.setStatus(BusinessStatus.Document.DRAFT);
        when(inspectionOrderMapper.selectOne(any())).thenReturn(draft);

        List<ActionSuggestion> actions = service.suggest("inspection_pending", Map.of(),
                List.of(row("inspectionNo", "QI1792827230517")), admin);

        ActionSuggestion command = actions.stream()
                .filter(item -> ActionSuggestion.KIND_COMMAND.equals(item.kind()))
                .findFirst().orElseThrow();
        assertEquals(AiCommandServiceImpl.ACTION_SUBMIT_INSPECTION, command.code());
        assertEquals("QI1792827230517", command.params().get("targetNo"));
        assertEquals(9L, command.params().get("targetId"));
    }

    @Test
    void viewActionCarriesFocusKeyForExactRecord() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-14", BusinessStatus.Document.RELEASED));

        List<ActionSuggestion> actions = service.suggest("work_order_detail", Map.of(),
                List.of(row("workOrderNo", "WO-14")), admin);

        ActionSuggestion view = actions.stream()
                .filter(item -> ActionSuggestion.KIND_VIEW.equals(item.kind()))
                .findFirst().orElseThrow();
        assertEquals("/mfg/work-orders", view.route());
        assertEquals("11", view.params().get("focus"));
    }

    @Test
    void unknownIntentReturnsNoActions() {
        assertTrue(service.suggest("totally_unknown", Map.of(), List.of(), admin).isEmpty());
    }

    @Test
    void releasedWorkOrderWithoutKittingOffersNoStartButton() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-15", BusinessStatus.Document.RELEASED));
        when(dataQueryService.queryWorkOrderReadiness(any())).thenReturn(readiness(checks(false)));
        when(workOrderMapper.selectList(any())).thenReturn(List.of(component("WO-C-6", 61L)));

        List<ActionSuggestion> actions = service.suggest("work_order_detail", Map.of(),
                List.of(row("workOrderNo", "WO-15")), admin);

        // 齐套没过，开始生产点了必失败，不能出现。
        assertFalse(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_START_WORK_ORDER
                .equals(item.code())));
        // 该给的是解开阻塞的第一步：先下达挡住开工的组件工单。
        ActionSuggestion release = actions.stream()
                .filter(item -> AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER.equals(item.code()))
                .findFirst().orElseThrow();
        assertEquals("下达组件工单 WO-C-6", release.label());
        assertEquals("WO-C-6", release.params().get("targetNo"));
    }

    @Test
    void readinessConclusionOffersStartOnlyWhenEveryCheckPassed() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-16", BusinessStatus.Document.RELEASED));
        when(dataQueryService.queryWorkOrderReadiness(any())).thenReturn(readiness(checks(true)));

        List<ActionSuggestion> blocked = service.suggest("work_order_readiness",
                Map.of("workOrderNo", "WO-16"), checks(true), admin);
        assertTrue(blocked.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_START_WORK_ORDER
                .equals(item.code())));

        when(dataQueryService.queryWorkOrderReadiness(any())).thenReturn(readiness(checks(false)));
        when(workOrderMapper.selectList(any())).thenReturn(List.of(component("WO-C-7", 71L)));
        List<ActionSuggestion> notReady = service.suggest("work_order_readiness",
                Map.of("workOrderNo", "WO-16"), checks(false), admin);
        assertFalse(notReady.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_START_WORK_ORDER
                .equals(item.code())));
    }

    @Test
    void readinessSkipsWhenQueryServiceUnavailable() {
        ReflectionTestUtils.setField(service, "dataQueryService", null);
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-17", BusinessStatus.Document.RELEASED));

        List<ActionSuggestion> actions = service.suggest("work_order_detail", Map.of(),
                List.of(row("workOrderNo", "WO-17")), admin);

        // 拿不到判定结论时不猜，直接不给开始生产。
        assertFalse(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_START_WORK_ORDER
                .equals(item.code())));
    }

    @Test
    void startPlanRechecksReadinessBecauseItsRowsAreStepsNotChecks() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-18", BusinessStatus.Document.RELEASED));
        when(workOrderMapper.selectList(any())).thenReturn(List.of(component("WO-C-8", 81L)));
        when(dataQueryService.queryWorkOrderReadiness(any())).thenReturn(readiness(checks(false)));

        // 处置方案的主表行没有 passed 列，传 null 当 checks；此时必须自己重新核一次，不能当成全部通过。
        List<ActionSuggestion> actions = service.suggest("work_order_start_plan",
                Map.of("workOrderNo", "WO-18"), null, admin);

        assertFalse(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_START_WORK_ORDER
                .equals(item.code())));
        assertTrue(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER
                .equals(item.code())));
    }

    @Test
    void draftSourcePlanSendsUserToPlanPageInsteadOfAReleaseThatWillFail() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-19", BusinessStatus.Document.RELEASED));
        when(dataQueryService.queryWorkOrderReadiness(any())).thenReturn(readiness(checks(false)));
        MfgWorkOrder child = component("WO-C-9", 91L);
        child.setPlanId(55L);
        when(workOrderMapper.selectList(any())).thenReturn(List.of(child));
        // 来源计划还是草稿：直接点「下达组件工单」会被前置检查拦下，所以要引到计划页先把计划生效。
        when(productionPlanMapper.selectOne(any())).thenReturn(draftPlan(55L));

        List<ActionSuggestion> actions = service.suggest("work_order_detail", Map.of(),
                List.of(row("workOrderNo", "WO-19")), admin);

        assertTrue(actions.stream().noneMatch(item -> AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER
                .equals(item.code())), "计划未生效时不该给必然失败的下达按钮");
        ActionSuggestion planAction = actions.stream()
                .filter(item -> "VIEW_PRODUCTION_PLAN".equals(item.code()))
                .findFirst().orElseThrow();
        assertEquals("先下达来源生产计划 1 张", planAction.label());
        assertEquals("/mfg/production-plans", planAction.route());
        assertEquals("55", planAction.params().get("focus"));
    }

    @Test
    void releasedSourcePlanStillOffersTheComponentRelease() {
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder("WO-20", BusinessStatus.Document.RELEASED));
        when(dataQueryService.queryWorkOrderReadiness(any())).thenReturn(readiness(checks(false)));
        MfgWorkOrder child = component("WO-C-10", 101L);
        child.setPlanId(56L);
        when(workOrderMapper.selectList(any())).thenReturn(List.of(child));
        when(productionPlanMapper.selectOne(any())).thenReturn(releasedPlan(56L));

        List<ActionSuggestion> actions = service.suggest("work_order_detail", Map.of(),
                List.of(row("workOrderNo", "WO-20")), admin);

        assertTrue(actions.stream().anyMatch(item -> AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER
                .equals(item.code())), "来源计划已生效时应当直接给下达按钮");
    }

    /* ---------- 构造辅助 ---------- */

    private Map<String, Object> row(String key, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key, value);
        return row;
    }

    private MfgWorkOrder workOrder(String workOrderNo, String status) {
        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderId(11L);
        order.setWorkOrderNo(workOrderNo);
        order.setStatus(status);
        order.setDeletedFlag(0);
        return order;
    }

    private MfgWorkOrder component(String workOrderNo, Long workOrderId) {
        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderId(workOrderId);
        order.setWorkOrderNo(workOrderNo);
        order.setStatus(BusinessStatus.Document.DRAFT);
        order.setWorkOrderType("COMPONENT");
        order.setParentWorkOrderId(11L);
        order.setDeletedFlag(0);
        return order;
    }

    private MfgProductionPlan draftPlan(Long planId) {
        return plan(planId, BusinessStatus.Document.DRAFT);
    }

    private MfgProductionPlan releasedPlan(Long planId) {
        return plan(planId, BusinessStatus.Document.RELEASED);
    }

    private MfgProductionPlan plan(Long planId, String status) {
        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanId(planId);
        plan.setPlanNo("PLN-" + planId);
        plan.setStatus(status);
        plan.setDeletedFlag(0);
        return plan;
    }

    private List<Map<String, Object>> checks(boolean passed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("item", "物料齐套");
        row.put("passed", passed ? "通过" : "未通过");
        return List.<Map<String, Object>>of(row);
    }

    private com.neuedu.ems.entity.ai.AiQueryResult readiness(List<Map<String, Object>> rows) {
        return new com.neuedu.ems.entity.ai.AiQueryResult("开工条件核查", "结论",
                List.of(), rows, List.of());
    }
}
