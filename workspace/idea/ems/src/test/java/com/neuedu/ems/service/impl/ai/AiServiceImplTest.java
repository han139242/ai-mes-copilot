package com.neuedu.ems.service.impl.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neuedu.ems.common.AiQuestion;
import com.neuedu.ems.common.AiResponse;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.config.AiProperties;
import com.neuedu.ems.entity.ai.AiChatRequest;
import com.neuedu.ems.entity.ai.AiChatMessage;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.exception.AiServiceException;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.service.ai.AiCommandService;
import com.neuedu.ems.service.ai.AiIntentRouter;
import com.neuedu.ems.service.ai.AiPlan;
import com.neuedu.ems.service.ai.AiPlannerService;
import com.neuedu.ems.service.dashboard.DashboardService;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiServiceImplTest {
    @Test
    void finishedProductionStillExplainsTheRealPendingDeliveryAction() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var router = mock(AiIntentRouter.class);
        var suggestions = mock(com.neuedu.ems.service.ai.AiActionSuggestionService.class);
        var planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        service.setIntentRouter(router); service.setAiActionSuggestionService(suggestions);
        service.setAiPlannerService(planner);
        when(router.execute(any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "订单进度", "已完工1，已入库1，已发货0。", List.of(),
                List.of(Map.of("productionOrderNo", "C1-PO-20261101-002")), List.of()));
        when(suggestions.suggest(any(), any(), any(), any())).thenReturn(List.of(
                new com.neuedu.ems.service.ai.AiActionSuggestionService.ActionSuggestion(
                        "SUBMIT_INSPECTION", "提交出货检验结果 C1-QI-20261101-006", "command",
                        Map.of("targetNo", "C1-QI-20261101-006"), null)));
        var request = chatRequest("这张订单下一步怎么办？");
        request.setMessages(List.of(new AiChatMessage("user", "订单 C1-PO-20261101-002")));
        var answer = service.agentChat(request, admin());
        assertTrue(String.valueOf(answer.get("answer")).startsWith("下一步：提交出货检验结果 C1-QI-20261101-006"));
        assertTrue(String.valueOf(answer.get("answer")).contains("已发货0"));
        assertNull(answer.get("command"));
        verify(planner, never()).plan(any(), any());
    }

    @Test
    void longWorkflowResolvesTheCurrentWorkOrdersRealOrderInsteadOfAnOlderOrder() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var queries = mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        var router = mock(AiIntentRouter.class);
        service.setAiReadOnlyQueryService(queries); service.setIntentRouter(router);
        when(queries.query(any(), any(), any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "工单归属", "", List.of(), List.of(Map.of("workOrderNo", "C1-WO-20261101-005",
                "productionOrderNo", "C1-PO-20261101-002")), List.of()));
        when(router.execute(any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "订单进度", "C1-PO-20261101-002 仍待终检", List.of(),
                List.of(Map.of("productionOrderNo", "C1-PO-20261101-002")), List.of()));
        var request = chatRequest("这张订单接下来怎么做？");
        request.setMessages(List.of(new AiChatMessage("user", "订单 C1-PO-20261031-001"),
                new AiChatMessage("user", "查询 C1-WO-20261101-005 关联的待检验单")));
        var answer = service.agentChat(request, admin());
        assertTrue(String.valueOf(answer.get("answer")).contains("C1-PO-20261101-002"));
        var route = org.mockito.ArgumentCaptor.forClass(AiIntentRouter.RouteResult.class);
        verify(router).execute(route.capture());
        assertEquals("C1-PO-20261101-002", route.getValue().string("orderNo"));
        verify(queries).query(org.mockito.ArgumentMatchers.eq("mfg_work_order"),
                org.mockito.ArgumentMatchers.eq(Map.of("workOrderNo", "C1-WO-20261101-005")), any());

        when(queries.query(any(), any(), any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "工单归属", "无匹配", List.of(), List.of(), List.of()));
        var missing = service.agentChat(request, admin());
        assertTrue(String.valueOf(missing.get("answer")).contains("未能确认"));
        assertNull(missing.get("command"));
        verify(router, org.mockito.Mockito.times(1)).execute(any());
    }
    @Test
    void dispatchFailureFollowupReadsActualDispatchBlockerInsteadOfRepeatingOrderSummary() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var commands = mock(AiCommandService.class);
        var router = mock(AiIntentRouter.class);
        service.setAiCommandService(commands); service.setIntentRouter(router);
        when(commands.previewExplicit(any(), any(), any(), any(), any())).thenReturn(Map.of(
                "actionLabel", "生成派工单", "targetNo", "C1-WO-20261101-008", "canExecute", false,
                "preconditions", List.of(Map.of("code", "DISPATCH", "passed", false,
                        "detail", "预计完成时间超过计划截止时间，请先调整排程"))));
        var request = chatRequest("刚才这张底座工单派工失败了，具体卡在哪，下一步怎么办？");
        request.setMessages(List.of(new AiChatMessage("user", "给 C1-WO-20261101-008 生成派工单")));
        var answer = service.agentChat(request, admin());
        assertTrue(String.valueOf(answer.get("answer")).contains("超过计划截止时间"));
        assertTrue(String.valueOf(answer.get("answer")).contains("没有执行派工"));
        assertNotNull(answer.get("command"));
        verifyNoInteractions(router);
        verify(commands, never()).execute(any(), any());
    }
    @Test
    void unanchoredWorkOrderReferenceMustClarifyInsteadOfReturningAllWorkOrders() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var router = mock(AiIntentRouter.class);
        var queries = mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        service.setIntentRouter(router);
        service.setAiReadOnlyQueryService(queries);
        var answer = service.agentChat(chatRequest("帮我查一下这个工单的进度"), admin());
        assertTrue(String.valueOf(answer.get("answer")).contains("哪张工单"));
        assertNull(answer.get("command"));
        verifyNoInteractions(router, queries);
        var repeated = chatRequest("帮我查一下这个工单的进度");
        repeated.setMessages(List.of(new AiChatMessage("user", "你好"),
                new AiChatMessage("assistant", "你好，有什么可以帮你"),
                new AiChatMessage("user", "帮我查一下这个工单的进度")));
        assertTrue(String.valueOf(service.agentChat(repeated, admin()).get("answer")).contains("哪张工单"));
        verifyNoInteractions(router, queries);
    }

    @Test
    void taskLookupDoesNotInterceptAnExplicitStartCommand() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var queries = mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        var commands = mock(AiCommandService.class);
        service.setAiReadOnlyQueryService(queries);
        service.setAiCommandService(commands);
        when(commands.parseIntent(any())).thenReturn("START_TASK");
        when(commands.previewSequenceIfMulti(any(), any(), any(), any(), any())).thenReturn(null);
        when(commands.preview(any(), any())).thenReturn(Map.of("actionLabel", "开工工序任务",
                "targetNo", "C1-TK-20261101-001", "canExecute", true, "preconditions", List.of()));
        var request = chatRequest("开工工序任务 C1-TK-20261101-001");
        request.setMessages(List.of(new AiChatMessage("user", "工单 C1-WO-20261101-002")));
        var answer = service.agentChat(request, admin());
        assertNotNull(answer.get("command"));
        verify(queries, never()).query(any(), any(), any());
        verify(commands).preview(any(), any());
        verify(commands, never()).execute(any(), any());
    }

    @Test
    void finishedTasksGiveAConcreteReadOnlyNextStepWithoutModelInventingRework() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var queries = mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        var commands = mock(AiCommandService.class);
        var planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        service.setAiReadOnlyQueryService(queries); service.setAiCommandService(commands);
        service.setAiPlannerService(planner);
        when(queries.query(any(), any(), any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "工序任务", "", List.of(), List.of(Map.of("taskNo", "C1-TK-20261101-010",
                "status", "FINISHED")), List.of()));
        var answer = service.agentChat(chatRequest("查询 C1-WO-20261101-005 的工序任务"), admin());
        assertTrue(String.valueOf(answer.get("answer")).contains("均已完成，无需再次开工"));
        assertTrue(String.valueOf(answer.get("answer")).contains("核对当前订单进度"));
        assertEquals(List.of(), answer.get("actions"));
        assertNull(answer.get("command"));
        verify(commands).parseIntent("查询 C1-WO-20261101-005 的工序任务");
        org.mockito.Mockito.verifyNoMoreInteractions(commands);
        verify(planner, never()).revise(any(), any());
    }

    @Test
    void taskFollowupReadsActualTasksAndPreflightsFirstStartableTask() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var queries = mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        var commands = mock(AiCommandService.class);
        var router = mock(AiIntentRouter.class);
        when(queries.query(any(), any(), any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "工序任务", "查到两道任务", List.of(), List.of(
                        Map.of("taskNo", "C1-TK-20261101-001", "operationName", "灯头装配", "status", "WAIT_START"),
                        Map.of("taskNo", "C1-TK-20261101-002", "operationName", "灯头自检", "status", "WAIT_START")), List.of()));
        when(commands.previewExplicit(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("canExecute", true, "preconditions", List.of()));
        service.setAiReadOnlyQueryService(queries);
        service.setAiCommandService(commands);
        service.setIntentRouter(router);
        var request = chatRequest("列出这张工单的工序任务，下一步先开哪一个？");
        request.setMessages(List.of(new AiChatMessage("user", "给 C1-WO-20261101-002 生成派工单")));
        var answer = service.agentChat(request, admin());
        assertEquals("work_order_tasks", answer.get("intent"));
        assertTrue(String.valueOf(answer.get("answer")).contains("C1-TK-20261101-001"));
        verify(queries).query(org.mockito.ArgumentMatchers.eq("mfg_task"),
                org.mockito.ArgumentMatchers.eq(Map.of("workOrderNo", "C1-WO-20261101-002")), any());
        verify(commands).previewExplicit(org.mockito.ArgumentMatchers.eq("START_TASK"),
                org.mockito.ArgumentMatchers.eq("C1-TK-20261101-001"), any(), any(), any());
        verify(commands, never()).execute(any(), any());
    }

    @Test
    void viewActionDeduplicationPreservesDifferentWorkOrderTargets() {
        var first = Map.<String, Object>of("code", "VIEW_LIST", "route", "/mfg/work-orders", "params", Map.of("focus", "11"));
        var second = Map.<String, Object>of("code", "VIEW_LIST", "route", "/mfg/work-orders", "params", Map.of("focus", "12"));
        List<?> result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                AiServiceImpl.class, "dedupeActions", List.of(first, second, first));
        assertNotNull(result);
        assertEquals(List.of(first, second), result);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"领料完了，然后应该干什么？", "派单派不下去应该怎么办"})
    void workflowFollowupChecksActualWorkOrderBeforeGivingAdvice(String question) {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var router = mock(AiIntentRouter.class);
        var planner = mock(AiPlannerService.class);
        var commands = mock(AiCommandService.class);
        when(commands.previewExplicit(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("canExecute", false, "preconditions", List.of(
                        Map.of("label", "实际用料", "passed", false, "detail", "线边物料不足"))));
        service.setAiCommandService(commands);
        when(planner.available()).thenReturn(true);
        when(router.execute(any())).thenAnswer(invocation -> {
            AiIntentRouter.RouteResult route = invocation.getArgument(0);
            if ("order_progress".equals(route.intent())) {
                return new com.neuedu.ems.entity.ai.AiQueryResult("订单进度", "先完成组件", List.of(),
                        List.of(Map.of("productionOrderNo", "C1-PO-20261031-010")),
                        List.of(new com.neuedu.ems.entity.ai.AiBlock("组件工单", List.of(),
                                List.of(Map.of("workOrderNo", "C1-WO-20261031-019", "status", "已下达")), null)));
            }
            return new com.neuedu.ems.entity.ai.AiQueryResult("处置方案", "线边已齐套；下一步生成派工单。", List.of(), List.of(), List.of());
        });
        when(planner.revise(any(), any())).thenReturn(new AiPlan("chat", "", Map.of(), "",
                "领料已到线边，接下来为 C1-WO-20261031-019 生成派工单。", List.of(), "真实观测"));
        service.setIntentRouter(router);
        service.setAiPlannerService(planner);
        var request = chatRequest(question);
        request.setMessages(List.of(new AiChatMessage("user", "订单 C1-PO-20261031-010")));
        Map<String, Object> answer = service.agentChat(request, admin());
        assertTrue(String.valueOf(answer.get("answer")).contains("生成派工单"));
        assertEquals(question.contains("派") ? 3 : 2, answer.get("toolRounds"));
        var routes = org.mockito.ArgumentCaptor.forClass(AiIntentRouter.RouteResult.class);
        verify(router, org.mockito.Mockito.times(2)).execute(routes.capture());
        assertEquals("work_order_start_plan", routes.getAllValues().get(1).intent());
        assertEquals("C1-WO-20261031-019", routes.getAllValues().get(1).string("workOrderNo"));
        verify(planner, never()).plan(any(), any());
        if (question.contains("派")) {
            verify(commands).previewExplicit(org.mockito.ArgumentMatchers.eq("GENERATE_DISPATCH_ORDER"),
                    org.mockito.ArgumentMatchers.eq("C1-WO-20261031-019"), any(), any(), any());
            verify(commands, never()).execute(any(), any());
        }
    }

    /**
     * 组件工单已完工时不能再推开工：查询层给的 status 是中文「已完成」，
     * 只认 FINISHED 会把它们当待办，进而去查「开工处置方案」给出「尚未生成工序任务」的错答。
     */
    @Test
    void finishedComponentWorkOrdersAreNotTreatedAsPending() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var router = mock(AiIntentRouter.class);
        var planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(planner.revise(any(), any())).thenReturn(new AiPlan("chat", "", Map.of(), "",
                "组件已全部完工，接下来做整机装配。", List.of(), "真实观测"));
        when(router.execute(any())).thenAnswer(invocation -> {
            AiIntentRouter.RouteResult route = invocation.getArgument(0);
            if ("order_progress".equals(route.intent())) {
                return new com.neuedu.ems.entity.ai.AiQueryResult("订单进度", "组件已完工", List.of(),
                        List.of(Map.of("productionOrderNo", "C1-PO-20261031-011")),
                        List.of(
                                new com.neuedu.ems.entity.ai.AiBlock("组件工单", List.of(), List.of(
                                        Map.of("workOrderNo", "C1-WO-20261031-023", "status", "已完成"),
                                        Map.of("workOrderNo", "C1-WO-20261031-024", "status", "已完成")), null),
                                new com.neuedu.ems.entity.ai.AiBlock("计划与工单", List.of(), List.of(
                                        Map.of("workOrderNo", "C1-WO-20261031-022",
                                                "workOrderStatus", "新建")), null)));
            }
            return new com.neuedu.ems.entity.ai.AiQueryResult("处置方案", "整机工单待下达。", List.of(),
                    List.of(), List.of());
        });
        service.setIntentRouter(router);
        service.setAiPlannerService(planner);
        var request = chatRequest("C1-PO-20261031-011 下一步该干什么");
        request.setMessages(List.of(new AiChatMessage("user", "订单 C1-PO-20261031-011")));

        service.agentChat(request, admin());

        var routes = org.mockito.ArgumentCaptor.forClass(AiIntentRouter.RouteResult.class);
        verify(router, org.mockito.Mockito.atLeast(2)).execute(routes.capture());
        var planned = routes.getAllValues().stream()
                .filter(r -> "work_order_start_plan".equals(r.intent()))
                .map(r -> r.string("workOrderNo")).toList();
        // 已完工的两张组件工单不该被当成待办去查开工方案，只应核对根工单。
        assertTrue(planned.contains("C1-WO-20261031-022"));
        assertTrue(!planned.contains("C1-WO-20261031-023"));
        assertTrue(!planned.contains("C1-WO-20261031-024"));
    }

    @Test
    void explicitOrderStatusQueriesDatabaseBeforeWaitingForPlanner() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var router = mock(AiIntentRouter.class);
        var planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(router.route(any(), any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AiIntentRouter.RouteResult("order_progress",
                        Map.of("orderNo", "C1-PO-20261031-006"), true));
        when(router.execute(any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "订单进度", "已发货 1 台，流程结束。", List.of(),
                List.of(Map.of("productionOrderNo", "C1-PO-20261031-006")), List.of()));
        service.setIntentRouter(router);
        service.setAiPlannerService(planner);

        Map<String, Object> answer = service.agentChat(
                chatRequest("C1-PO-20261031-006 现在到哪一步了？"), admin());

        assertEquals("已发货 1 台，流程结束。", answer.get("answer"));
        assertEquals("order_progress", answer.get("intent"));
        verify(planner, never()).plan(any(), any());
    }

    @Test
    void explicitOrderQueryFailureDoesNotAskModelToInventCurrentStatus() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var router = mock(AiIntentRouter.class);
        var planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(router.route(any(), any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AiIntentRouter.RouteResult("order_progress",
                        Map.of("orderNo", "C1-PO-20261031-006"), true));
        when(router.execute(any())).thenThrow(new IllegalStateException("database unavailable"));
        service.setIntentRouter(router);
        service.setAiPlannerService(planner);

        Map<String, Object> answer = service.agentChat(
                chatRequest("C1-PO-20261031-006 现在到哪一步了？"), admin());

        assertEquals("DATABASE_UNAVAILABLE", answer.get("source"));
        assertTrue(String.valueOf(answer.get("answer")).contains("没有读到单据当前状态"));
        verify(planner, never()).plan(any(), any());
    }

    @Test
    void adminFollowUpUsesCurrentOrderProgressInsteadOfStalePlannerAnswer() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var router = mock(AiIntentRouter.class);
        var planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(router.execute(any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "订单进度", "下一步：先生产三种组件。", List.of(),
                List.of(Map.of("productionOrderNo", "C1-PO-20261031-006")), List.of()));
        service.setIntentRouter(router);
        service.setAiPlannerService(planner);
        var request = chatRequest("我已经点击了，然后呢？");
        request.setMessages(List.of(new AiChatMessage("user", "创建订单 C1-PO-20261031-006"),
                new AiChatMessage("assistant", "先去排产")));
        var unrestrictedAdmin = new AuthorizationContext(7L, 8L, 9L, "ALL",
                Set.of("SUPER_ADMIN"), Set.of(), Set.of());

        Map<String, Object> answer = service.agentChat(request, unrestrictedAdmin);

        assertEquals("order_progress", answer.get("intent"));
        assertEquals("下一步：先生产三种组件。", answer.get("answer"));
        var route = org.mockito.ArgumentCaptor.forClass(AiIntentRouter.RouteResult.class);
        verify(router).execute(route.capture());
        assertEquals("C1-PO-20261031-006", route.getValue().string("orderNo"));
        verify(planner, never()).plan(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"把这些组件工单都下达", "把这几个工单都下达下去", "把这几张工单全部下达", "这三张工单都下达"})
    void adminCanPreviewReleaseOfCurrentOrdersThreeComponentsWithoutRepeatingNumbers(String question) {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var router = mock(AiIntentRouter.class);
        var commands = mock(AiCommandService.class);
        var components = List.<Map<String, Object>>of(
                Map.of("workOrderNo", "C1-WO-20261031-011", "status", "新建"),
                Map.of("workOrderNo", "C1-WO-20261031-012", "status", "已审核"),
                Map.of("workOrderNo", "C1-WO-20261031-013", "status", "已下达"));
        when(router.execute(any())).thenReturn(new com.neuedu.ems.entity.ai.AiQueryResult(
                "订单进度", "组件待生产", List.of(),
                List.of(Map.of("productionOrderNo", "C1-PO-20261031-006")),
                List.of(new com.neuedu.ems.entity.ai.AiBlock("组件工单", List.of(), components, null))));
        when(commands.previewSequenceExplicit(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("planId", "preview-1", "steps", List.of(
                        Map.of("stepNo", 1, "actionLabel", "下达生产工单", "targetNo", "C1-WO-20261031-011", "canExecute", true),
                        Map.of("stepNo", 2, "actionLabel", "下达生产工单", "targetNo", "C1-WO-20261031-012", "canExecute", true))));
        service.setIntentRouter(router);
        service.setAiCommandService(commands);
        var request = chatRequest(question);
        request.setMessages(List.of(new AiChatMessage("assistant", "订单 C1-PO-20261031-006 有三张组件工单")));
        var unrestrictedAdmin = new AuthorizationContext(7L, 8L, 9L, "ALL",
                Set.of("SUPER_ADMIN"), Set.of(), Set.of());

        Map<String, Object> answer = service.agentChat(request, unrestrictedAdmin);

        assertEquals("command_sequence_preview", answer.get("intent"));
        assertNotNull(answer.get("sequence"));
        assertTrue(String.valueOf(answer.get("answer")).contains("3 张组件工单"));
        assertTrue(String.valueOf(answer.get("answer")).contains("本次只办理其余 2 张"));
        @SuppressWarnings("unchecked")
        var steps = (org.mockito.ArgumentCaptor<List<AiCommandService.SequenceStepInput>>) (Object)
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(commands).previewSequenceExplicit(steps.capture(), any(), any(), any(), any());
        assertEquals(List.of("C1-WO-20261031-011", "C1-WO-20261031-012"),
                steps.getValue().stream().map(AiCommandService.SequenceStepInput::targetNo).toList());
    }

    @Test
    void orderProgressOffersComponentActionsOnlyWithinAllowedDataScope() {
        var service = createService(enabledProperties(), new OkHttpClient(), mock(DashboardService.class));
        var suggestions = mock(com.neuedu.ems.service.ai.AiActionSuggestionService.class);
        service.setAiActionSuggestionService(suggestions);
        var component = Map.<String, Object>of("workOrderNo", "WO-2", "productName", "灯头组件");
        var result = new com.neuedu.ems.entity.ai.AiQueryResult("订单", "状态", List.of(),
                List.of(Map.<String, Object>of("productionOrderNo", "PO-1")), List.of(
                new com.neuedu.ems.entity.ai.AiBlock("组件工单", List.of(), List.of(component), null)));
        var admin = new AuthorizationContext(7L, 8L, 9L, "ALL", Set.of("SUPER_ADMIN"), Set.of(), Set.of());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "suggestActions",
                "order_progress", Map.of(), result, admin);
        @SuppressWarnings("unchecked")
        var rows = (org.mockito.ArgumentCaptor<List<Map<String, Object>>>) (Object) org.mockito.ArgumentCaptor.forClass(List.class);
        verify(suggestions).suggest(org.mockito.ArgumentMatchers.eq("order_progress"), any(), rows.capture(), org.mockito.ArgumentMatchers.eq(admin));
        assertTrue(rows.getValue().contains(component));

        var restricted = new AuthorizationContext(8L, 9L, 10L, "SELF", Set.of(), Set.of("mfg:work-order:view"), Set.of());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "suggestActions",
                "order_progress", Map.of(), result, restricted);
        verify(suggestions).suggest(org.mockito.ArgumentMatchers.eq("order_progress"), any(), rows.capture(), org.mockito.ArgumentMatchers.eq(restricted));
        assertFalse(rows.getValue().contains(component));
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void statusDoesNotExposeApiKey() {
        AiProperties properties = enabledProperties();
        AiServiceImpl service = createService(properties, responseClient(200, upstreamResponse("测试"), null),
                mock(DashboardService.class));

        Map<String, Object> status = service.getStatus();

        assertEquals(true, status.get("available"));
        assertEquals("READY", status.get("state"));
        assertEquals("DEEPSEEK", status.get("provider"));
        assertFalse(status.containsKey("reasoningEffort"));
        assertFalse(status.containsKey("apiKey"));
        assertFalse(status.containsValue(properties.getApiKey()));
    }

    @Test
    void defaultPropertiesRequireApiKeyFromEnvironment() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        AiServiceImpl service = createService(properties, new OkHttpClient(), mock(DashboardService.class));

        Map<String, Object> status = service.getStatus();

        assertEquals(false, status.get("configured"));
        assertEquals(false, status.get("available"));
        assertEquals("MISCONFIGURED", status.get("state"));
    }

    @Test
    void disabledServiceRejectsChatWithoutCallingUpstream() {
        AiProperties properties = enabledProperties();
        properties.setEnabled(false);
        AiServiceImpl service = createService(properties, new OkHttpClient(), mock(DashboardService.class));

        AiServiceException exception = assertThrows(AiServiceException.class,
                () -> service.chat(question("系统怎么使用？", "general", null)));

        assertEquals("AI服务尚未启用", exception.getMessage());
    }

    @Test
    void chatParsesJsonAndFiltersSensitiveContext() throws Exception {
        String modelContent = "{\"answer\":\"库存预警需要关注\",\"suggestions\":[\"核对安全库存\",\"检查补货建议\"]}";
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        OkHttpClient client = responseClient(200, upstreamResponse(modelContent), request -> {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            requestBody.set(buffer.readUtf8());
            authorization.set(request.header("Authorization"));
        });
        AiServiceImpl service = createService(enabledProperties(), client, mock(DashboardService.class));
        AiQuestion request = question("库存情况如何？", "general", Map.of(
                "workOrderId", 12,
                "token", "never-send-token-123",
                "nested", Map.of("password", "never-send-password-456", "batchId", 8)));

        AiResponse result = service.chat(request);

        assertEquals("库存预警需要关注", result.getAnswer());
        assertEquals(List.of("核对安全库存", "检查补货建议"), result.getSuggestions());
        assertEquals("test-model", result.getModel());
        assertNotNull(result.getGeneratedAt());
        assertTrue(requestBody.get().contains("workOrderId"));
        assertTrue(requestBody.get().contains("batchId"));
        assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
        assertFalse(requestBody.get().contains("reasoning_effort"));
        assertFalse(requestBody.get().contains("temperature"));
        assertFalse(requestBody.get().contains("max_tokens"));
        assertFalse(requestBody.get().contains("never-send-token-123"));
        assertFalse(requestBody.get().contains("never-send-password-456"));
        assertEquals("Bearer test-api-key", authorization.get());
    }

    @Test
    void baseApiUrlIsExpandedToChatCompletionsEndpoint() {
        AtomicReference<String> requestUrl = new AtomicReference<>();
        OkHttpClient client = responseClient(200, upstreamResponse("测试"), request ->
                requestUrl.set(request.url().toString()));
        AiProperties properties = enabledProperties();
        properties.setApiUrl("https://api.deepseek.com");
        AiServiceImpl service = createService(properties, client, mock(DashboardService.class));

        service.chat(question("系统怎么使用？", "general", null));

        assertEquals("https://api.deepseek.com/v1/chat/completions", requestUrl.get());
    }

    @Test
    void generalChatIncludesPendingProductionOrderSummary() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        MfgProductionOrderMapper orderMapper = mock(MfgProductionOrderMapper.class);
        MfgProductionPlanMapper planMapper = mock(MfgProductionPlanMapper.class);
        MfgProductionOrder pendingOrder = new MfgProductionOrder();
        pendingOrder.setProductionOrderId(101L);
        pendingOrder.setProductionOrderNo("PO-DRAFT-001");
        pendingOrder.setOrderQuantity(new BigDecimal("300"));
        pendingOrder.setDeliveryDate(LocalDate.of(2026, 7, 20));
        pendingOrder.setStatus("DRAFT");
        when(orderMapper.selectList(any())).thenReturn(List.of(pendingOrder));
        when(planMapper.selectList(any())).thenReturn(List.of());
        AiServiceImpl service = createService(enabledProperties(), responseClient(200,
                upstreamResponse("{\"answer\":\"当前有1个待排订单\",\"suggestions\":[]}"), request -> {
                    Buffer buffer = new Buffer();
                    request.body().writeTo(buffer);
                    requestBody.set(buffer.readUtf8());
                }), mock(DashboardService.class), orderMapper, planMapper);

        AiResponse result = service.chat(question("现在有多少待排订单？", "general", null));

        assertEquals("当前有1个待排订单", result.getAnswer());
        assertTrue(requestBody.get().contains("pendingProductionOrders"));
        assertTrue(requestBody.get().contains("orderCount"));
        assertTrue(requestBody.get().contains("PO-DRAFT-001"));
    }

    /**
     * 前端没传日期时，模型问答分支必须自己补一个默认区间。
     *
     * <p>这是线上真踩到的：计划器偶发调用失败（上游 TLS 握手被切断）时，请求落到模型自由问答分支，
     * 而 {@code dateRange} 因为前端没传日期是 null，{@code dateRange.beginDate()} 直接空指针，
     * 被包成「读取业务汇总数据失败」返回。用户看到的是同一句话时而能答时而报错。</p>
     */
    @Test
    void modelAnswerWithoutDateRangeUsesDefaultWindowInsteadOfFailing() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        DashboardService dashboard = mock(DashboardService.class);
        when(dashboard.getSummary(any(), any())).thenReturn(Map.of("orderCount", 3));
        when(dashboard.getEquipmentStatus()).thenReturn(Map.of("running", 2));
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("{\"answer\":\"当前有 3 个待排订单\",\"suggestions\":[]}"),
                        request -> {
                            Buffer buffer = new Buffer();
                            request.body().writeTo(buffer);
                            requestBody.set(buffer.readUtf8());
                        }),
                dashboard);

        AiResponse result = service.chat(question("现在整体什么情况？", "general", null));

        assertEquals("当前有 3 个待排订单", result.getAnswer());
        // 默认窗口是最近 7 天，必须真的带上日期区间去查，而不是跳过查询。
        verify(dashboard).getSummary(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertTrue(requestBody.get().contains("systemSummary"), requestBody.get());
    }

    @Test
    void plainTextModelResponseFallsBackToAnswer() {
        AiServiceImpl service = createService(enabledProperties(), responseClient(200,
                upstreamResponse("请先检查设备状态。"), null), mock(DashboardService.class));

        AiResponse result = service.chat(question("设备为什么停机？", "general", null));

        assertEquals("请先检查设备状态。", result.getAnswer());
        assertTrue(result.getSuggestions().isEmpty());
    }

    /**
     * 有业务数据没读到时，回答里要明确带出缺了哪一项。
     *
     * <p>只把缺口塞进喂给模型的数据是不够的：用户看到一段听起来正常的结论，
     * 却不知道里面少了质量统计，会把不完整当成全量。</p>
     */
    @Test
    void modelAnswerCarriesBusinessDataGapsWhenAnAggregateCannotBeRead() {
        DashboardService dashboard = mock(DashboardService.class);
        when(dashboard.getSummary(any(), any())).thenThrow(new IllegalStateException("连接池已耗尽"));
        when(dashboard.getEquipmentStatus()).thenReturn(Map.of("running", 2));
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("{\"answer\":\"设备总体正常\",\"suggestions\":[]}"), null),
                dashboard);

        Map<String, Object> answer = service.agentChat(chatRequest("现在整体什么情况？"), admin());

        assertEquals("设备总体正常", answer.get("answer"));
        assertEquals(Boolean.FALSE, answer.get("businessDataAvailable"));
        assertEquals(List.of("系统汇总"), answer.get("businessDataGaps"));
    }

    @Test
    void modelAnswerOmitsBusinessDataGapsWhenEverythingWasRead() {
        DashboardService dashboard = mock(DashboardService.class);
        when(dashboard.getSummary(any(), any())).thenReturn(Map.of("orderCount", 3));
        when(dashboard.getEquipmentStatus()).thenReturn(Map.of("running", 2));
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("{\"answer\":\"一切正常\",\"suggestions\":[]}"), null),
                dashboard);

        Map<String, Object> answer = service.agentChat(chatRequest("现在整体什么情况？"), admin());

        assertFalse(answer.containsKey("businessDataGaps"), "全部读到就不该出现缺口字段：" + answer.keySet());
    }

    @Test
    void upstreamFailureReturnsSafeMessage() {
        AiServiceImpl service = createService(enabledProperties(), responseClient(500,
                "{\"error\":\"internal provider detail\"}", null), mock(DashboardService.class));

        AiServiceException exception = assertThrows(AiServiceException.class,
                () -> service.chat(question("分析生产情况", "general", null)));

        assertEquals("AI服务暂时不可用，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("provider"));
    }

    /**
     * 上游挂了但配置看着正常：这是最常见的线上故障（网关重启、限流、断网）。
     * 这时候不能把整轮问答判成业务错误，要降级成规则回答并说明「模型这次没连上」。
     */
    @Test
    void modelOutageDegradesToRuleAnswerInsteadOfFailing() {
        AiServiceImpl service = createService(enabledProperties(), responseClient(500,
                "{\"error\":\"internal provider detail\"}", null), mock(DashboardService.class));

        Map<String, Object> answer = service.agentChat(chatRequest("帮我看看最近的生产情况"), admin());

        assertEquals("RULE_ROUTER", answer.get("model"));
        assertEquals(Boolean.TRUE, answer.get("modelDegraded"));
        String text = String.valueOf(answer.get("answer"));
        assertTrue(text.contains("大模型暂时连不上"), text);
        assertFalse(text.contains("provider"), text);
        assertNotNull(answer.get("elapsedMs"));
    }

    /**
     * 环境本来就没配模型时说的话不一样：说「暂时连不上」会让人以为系统出了故障。
     */
    @Test
    void unconfiguredModelSaysNotConnectedRatherThanOutage() {
        AiProperties disabled = new AiProperties();
        disabled.setEnabled(false);
        AiServiceImpl service = createService(disabled, responseClient(200, "{}", null),
                mock(DashboardService.class));

        Map<String, Object> answer = service.agentChat(chatRequest("帮我看看最近的生产情况"), admin());

        assertEquals("RULE_ROUTER", answer.get("model"));
        assertEquals(Boolean.FALSE, answer.get("modelDegraded"));
        assertTrue(String.valueOf(answer.get("answer")).contains("未接入大模型"), answer.toString());
    }

    /**
     * 问的是说明书口径、但库里没有索引：不能落回数据查询去猜物料名。
     * 之前「需求规格说明书里对库存流水是怎么规定的」会被「库存」抓成物料查询，
     * 答「未找到与《需求规格说明书》匹配的物料」，用户会以为系统答错了。
     */
    @Test
    void knowledgeQuestionWithoutIndexSaysNoSectionInsteadOfGuessingMaterial() {
        AiServiceImpl service = createService(enabledProperties(), responseClient(200, "{}", null),
                mock(DashboardService.class));
        com.neuedu.ems.service.ai.AiKnowledgeService knowledge =
                mock(com.neuedu.ems.service.ai.AiKnowledgeService.class);
        when(knowledge.search(any(), anyInt())).thenReturn(List.of());
        when(knowledge.sectionCount()).thenReturn(0L);
        service.setAiKnowledgeService(knowledge);

        Map<String, Object> answer = service.agentChat(chatRequest("需求规格说明书里对库存流水是怎么规定的"), admin());

        assertEquals("knowledge", answer.get("intent"));
        String text = String.valueOf(answer.get("answer"));
        assertTrue(text.contains("还没有导入说明书索引"), text);
        assertFalse(text.contains("未找到与"), text);
        assertEquals("DOCUMENT", answer.get("source"));
    }

    @Test
    void dashboardInsightLoadsRealDashboardAggregates() {        DashboardService dashboardService = mock(DashboardService.class);
        LocalDate beginDate = LocalDate.of(2026, 7, 1);
        LocalDate endDate = LocalDate.of(2026, 7, 7);
        when(dashboardService.getSummary(beginDate, endDate)).thenReturn(Map.of("runningTaskCount", 2));
        when(dashboardService.getProductionTrend(beginDate, endDate)).thenReturn(List.of(Map.of("date", "2026-07-01")));
        when(dashboardService.getQualityStatistics(beginDate, endDate)).thenReturn(Map.of("qualifiedRate", 96));
        when(dashboardService.getEquipmentStatus()).thenReturn(Map.of("fault", 1));
        AiServiceImpl service = createService(enabledProperties(), responseClient(200,
                upstreamResponse("{\"answer\":\"整体稳定\",\"suggestions\":[]}"), null), dashboardService);
        AiQuestion request = question("解读本周看板", "dashboard",
                Map.of("beginDate", "2026-07-01", "endDate", "2026-07-07"));

        AiResponse result = service.getDashboardInsight(request);

        assertEquals("整体稳定", result.getAnswer());
        verify(dashboardService).getSummary(beginDate, endDate);
        verify(dashboardService).getProductionTrend(beginDate, endDate);
        verify(dashboardService).getQualityStatistics(beginDate, endDate);
        verify(dashboardService).getEquipmentStatus();
    }

    @Test
    void scheduleScenarioAdviceUsesOnlyProvidedSummary() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        DashboardService dashboardService = mock(DashboardService.class);
        AiServiceImpl service = createService(enabledProperties(), responseClient(200,
                upstreamResponse("{\"answer\":\"产线存在瓶颈\",\"suggestions\":[\"人工复核冲突订单\"]}"), request -> {
                    Buffer buffer = new Buffer();
                    request.body().writeTo(buffer);
                    requestBody.set(buffer.readUtf8());
                }), dashboardService);

        AiResponse result = service.getScheduleScenarioAdvice(Map.of(
                "scenario", Map.of("orderCount", 2, "conflictOrderCount", 1),
                "conflictReasons", Map.of("排程窗口有效产能不足", 1)));

        assertEquals("产线存在瓶颈", result.getAnswer());
        assertEquals(List.of("人工复核冲突订单"), result.getSuggestions());
        assertTrue(requestBody.get().contains("scheduleScenario"));
        assertTrue(requestBody.get().contains("排程窗口有效产能不足"));
        verifyNoInteractions(dashboardService);
    }

    @Test
    void manualBoardAdviceRequiresCurrentStateAndPendingPlacementSections() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AiServiceImpl service = createService(enabledProperties(), responseClient(200,
                upstreamResponse("{\"answer\":\"建议先安排 P1\",\"suggestions\":[]}"), request -> {
                    Buffer buffer = new Buffer();
                    request.body().writeTo(buffer);
                    requestBody.set(buffer.readUtf8());
                }), mock(DashboardService.class));

        service.getScheduleScenarioAdvice(Map.of(
                "analysisType", "MANUAL_SCHEDULE_BOARD_ADVICE",
                "pendingPlacementOptions", List.of(Map.of(
                        "reference", "P1",
                        "candidateLineOptions", "LINE-A：最早可排 07-18 08:00，预计完成 07-18 14:00，建议预留 360 分钟产线工时，优先级 1"))));

        assertTrue(requestBody.get().contains("当前排产状态"));
        assertTrue(requestBody.get().contains("待排订单安排建议"));
        assertTrue(requestBody.get().contains("P1"));
        assertTrue(requestBody.get().contains("LINE-A：最早可排 07-18 08:00"));
        assertTrue(requestBody.get().contains("每个待排订单及风险与优化建议必须各自独立成段，并以空行分隔"));
        assertTrue(requestBody.get().contains("不得描述字段、脱敏、上下文、接口、系统错误或内部处理"));
        assertFalse(requestBody.get().contains("[内容过深，已省略]"));
        assertTrue(requestBody.get().contains("不得声称已创建、调整或确认任何计划"));
    }

    /**
     * 模型判「要补问」、但用户原话里已经带了完整单号时，必须按关键词规则出预览卡片。
     * 否则同一句「下达工单 WO-xxx」会时而出卡片、时而回补问。
     */
    @Test
    void clarifyFallsBackToDeterministicPreviewWhenQuestionCarriesCode() {
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("不该走到这里"), null), mock(DashboardService.class));
        service.setAiPlannerService(clarifyPlanner());
        AiCommandService commands = mock(AiCommandService.class);
        when(commands.parseIntent(any())).thenReturn("RELEASE_WORK_ORDER");
        when(commands.preview(any(), any())).thenReturn(releasePreview());
        service.setAiCommandService(commands);

        Map<String, Object> answer = service.agentChat(
                chatRequest("把工单 WO-9-20261025214348845 下达"), admin());

        assertEquals("command_preview", answer.get("intent"));
        assertNotNull(answer.get("command"));
        String text = String.valueOf(answer.get("answer"));
        assertTrue(text.contains("下达生产工单"), text);
        assertTrue(text.contains("WO-9-20261025214348845"), text);
        verify(commands).preview(any(), any());
    }

    /**
     * 计划器给了 command 但没抄单据号时，同样先用关键词规则兜底，不能直接回补问。
     */
    @Test
    void plannedCommandWithoutTargetFallsBackToDeterministicPreview() {
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("不该走到这里"), null), mock(DashboardService.class));
        AiPlannerService planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(planner.plan(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_COMMAND, "RELEASE_WORK_ORDER",
                Map.of(), "", "", List.of(), "test"));
        service.setAiPlannerService(planner);
        AiCommandService commands = mock(AiCommandService.class);
        when(commands.parseIntent(any())).thenReturn("RELEASE_WORK_ORDER");
        when(commands.preview(any(), any())).thenReturn(releasePreview());
        service.setAiCommandService(commands);

        Map<String, Object> answer = service.agentChat(
                chatRequest("下达工单 WO-9-20261025214348845"), admin());

        assertEquals("command_preview", answer.get("intent"));
        assertNotNull(answer.get("command"));
    }

    /**
     * 关键词也认不出来时必须老实补问，不能猜单号。
     */
    @Test
    void clarifyStaysClarifyWhenDeterministicParsingFindsNothing() {
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("不该走到这里"), null), mock(DashboardService.class));
        service.setAiPlannerService(clarifyPlanner());
        AiCommandService commands = mock(AiCommandService.class);
        when(commands.parseIntent(any())).thenReturn(null);
        service.setAiCommandService(commands);

        Map<String, Object> answer = service.agentChat(chatRequest("帮我下一张生产工单"), admin());

        assertEquals("clarify", answer.get("intent"));
        assertNull(answer.get("command"));
        verify(commands, never()).preview(any(), any());
    }

    /**
     * 规则能定出动作但单据查不到时，要把「没找到这张单」说清楚，而不是回一句「请补单据号」。
     */
    @Test
    void deterministicFallbackReportsMissingDocumentInsteadOfAskingAgain() {
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("不该走到这里"), null), mock(DashboardService.class));
        service.setAiPlannerService(clarifyPlanner());
        AiCommandService commands = mock(AiCommandService.class);
        when(commands.parseIntent(any())).thenReturn("RELEASE_WORK_ORDER");
        when(commands.preview(any(), any()))
                .thenThrow(new BusinessException(404, "没有找到单号为 WO-202610240001 的工单，请核对单号后再试"));
        service.setAiCommandService(commands);

        Map<String, Object> answer = service.agentChat(
                chatRequest("下达工单 WO-202610240001"), admin());

        assertEquals("command_preview", answer.get("intent"));
        assertEquals("没有找到单号为 WO-202610240001 的工单，请核对单号后再试", answer.get("answer"));
    }

    /**
     * 查到的数据不够答时，模型应该拿着真实结果再查一项，最后用自己的话收尾。
     * 这里验证：循环真的转了两轮、第二次查询的条件来自模型复审、最终回答是模型的措辞。
     */
    @Test
    void resourceQueryKeepsLookingUntilModelCanAnswer() {
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("不该走到这里"), null), mock(DashboardService.class));
        AiPlannerService planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(planner.plan(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_QUERY, "data_query",
                Map.of("resource", "material"), "", "", List.of(), "test"));
        when(planner.revise(any(), any()))
                .thenReturn(new AiPlan(AiPlan.MODE_QUERY, "data_query",
                        Map.of("resource", "material", "filters", Map.of("keyword", "灯珠")),
                        "", "", List.of(), "test"))
                .thenReturn(new AiPlan(AiPlan.MODE_CHAT, "",
                        Map.of(), "", "灯珠库存还有 120 个，够本周使用。", List.of("看下采购在途"), "test"));
        service.setAiPlannerService(planner);
        com.neuedu.ems.service.ai.AiReadOnlyQueryService queries =
                mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        when(queries.query(any(), any(), any()))
                .thenReturn(queryResult("查询物料，命中 1 条。", "物料"))
                .thenReturn(queryResult("查询物料，命中 1 条（条件：灯珠）。", "灯珠"));
        service.setAiReadOnlyQueryService(queries);

        Map<String, Object> answer = service.agentChat(chatRequest("物料还够不够用"), admin());

        assertEquals("data_query", answer.get("intent"));
        assertEquals("灯珠库存还有 120 个，够本周使用。", answer.get("answer"));
        assertEquals(2, answer.get("toolRounds"));
        assertEquals("PLANNER+DATABASE+TOOL", answer.get("model"));
        assertEquals(List.of("看下采购在途"), answer.get("suggestions"));
        verify(queries, org.mockito.Mockito.times(2)).query(any(), any(), any());
    }

    /**
     * 模型说「再查一项」但每次都给同一个资源同一条件时，不能在原地打转。
     * 这里让复审永远返回同一条查询，断言只查一次就收手并退回查询摘要。
     */
    @Test
    void resourceQueryStopsAtRoundLimitInsteadOfLoopingForever() {
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("不该走到这里"), null), mock(DashboardService.class));
        AiPlannerService planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(planner.plan(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_QUERY, "data_query",
                Map.of("resource", "material"), "", "", List.of(), "test"));
        when(planner.revise(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_QUERY, "data_query",
                Map.of("resource", "material"), "", "", List.of(), "test"));
        service.setAiPlannerService(planner);
        com.neuedu.ems.service.ai.AiReadOnlyQueryService queries =
                mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        when(queries.query(any(), any(), any())).thenReturn(queryResult("查询物料，命中 1 条。", "物料"));
        service.setAiReadOnlyQueryService(queries);

        Map<String, Object> answer = service.agentChat(chatRequest("物料还够不够用"), admin());

        // 上界由 MAX_TOOL_ROUNDS 兜住，不会无限循环；回答退回数据库给的摘要。
        assertEquals("查询物料，命中 1 条。", answer.get("answer"));
        assertEquals(3, answer.get("toolRounds"));
        verify(queries, org.mockito.Mockito.times(3)).query(any(), any(), any());
    }

    /**
     * 模型复审不可用（未配置或报错）时必须保留已有查询结果，不能因为「想不出下一步」就丢数据。
     */
    @Test
    void resourceQueryFallsBackToSummaryWhenRevisionUnavailable() {
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("不该走到这里"), null), mock(DashboardService.class));
        AiPlannerService planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(planner.plan(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_QUERY, "data_query",
                Map.of("resource", "material"), "", "", List.of(), "test"));
        when(planner.revise(any(), any())).thenThrow(new AiServiceException("复审调用失败"));
        service.setAiPlannerService(planner);
        com.neuedu.ems.service.ai.AiReadOnlyQueryService queries =
                mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        when(queries.query(any(), any(), any())).thenReturn(queryResult("查询物料，命中 1 条。", "物料"));
        service.setAiReadOnlyQueryService(queries);

        Map<String, Object> answer = service.agentChat(chatRequest("物料还够不够用"), admin());

        assertEquals("查询物料，命中 1 条。", answer.get("answer"));
        assertEquals(1, answer.get("toolRounds"));
        assertEquals("PLANNER+DATABASE", answer.get("model"));
    }

    /**
     * 观测里只放资源、条件、条数、列名与前几行，不能把表名、SQL、主键带出去。
     */
    @Test
    void observationsGivenToModelCarryNoSchemaOrKeys() {
        AiServiceImpl service = createService(enabledProperties(),
                responseClient(200, upstreamResponse("不该走到这里"), null), mock(DashboardService.class));
        AiPlannerService planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(planner.plan(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_QUERY, "data_query",
                Map.of("resource", "material"), "", "", List.of(), "test"));
        AtomicReference<List<Map<String, Object>>> seen = new AtomicReference<>(List.of());
        when(planner.revise(any(), any())).thenAnswer(invocation -> {
            seen.set(invocation.getArgument(1));
            return null;
        });
        service.setAiPlannerService(planner);
        com.neuedu.ems.service.ai.AiReadOnlyQueryService queries =
                mock(com.neuedu.ems.service.ai.AiReadOnlyQueryService.class);
        when(queries.query(any(), any(), any())).thenReturn(queryResult("查询物料，命中 1 条。", "物料"));
        service.setAiReadOnlyQueryService(queries);

        service.agentChat(chatRequest("物料还够不够用"), admin());

        assertEquals(1, seen.get().size());
        Map<String, Object> observation = seen.get().get(0);
        assertEquals("material", observation.get("resource"));
        assertEquals(1, observation.get("rowCount"));
        assertTrue(String.valueOf(observation.get("columns")).contains("物料编码"));
        String serialized = String.valueOf(observation);
        assertFalse(serialized.contains("mdm_material"), serialized);
        assertFalse(serialized.toLowerCase(java.util.Locale.ROOT).contains("select "), serialized);
        assertFalse(serialized.toLowerCase(java.util.Locale.ROOT).contains("password"), serialized);
    }

    /** 一条最小的查询结果，用来驱动工具循环。 */
    @Test
    void plannedQueryKeepsChineseWarehouseNameEvenWhenModelOmitsIt() {
        AiProperties properties = enabledProperties();
        AiServiceImpl service = createService(properties, new OkHttpClient(), mock(DashboardService.class));
        AiPlannerService planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        // 模型只判出「问库存」，没把「线边仓」带进参数，这是线上真实表现。
        when(planner.plan(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_QUERY, "inventory_stock",
                Map.of(), "", "", List.of(), "test"));
        service.setAiPlannerService(planner);
        AiIntentRouter router = mock(AiIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(new AiIntentRouter.RouteResult(
                "inventory_stock", Map.of("warehouseType", "LINE_SIDE"), true));
        when(router.execute(any())).thenReturn(queryResult("共 64 条库存记录", "LAMP-M01"));
        service.setIntentRouter(router);

        Map<String, Object> answer = service.agentChat(chatRequest("线边仓还有多少"), admin());

        assertEquals("PLANNER+DATABASE", answer.get("model"));
        verify(router).execute(org.mockito.ArgumentMatchers.argThat(
                route -> "LINE_SIDE".equals(route.string("warehouseType"))));
    }

    @Test
    void plannedQueryDoesNotLetRuleExtractionOverrideModelParams() {
        AiProperties properties = enabledProperties();
        AiServiceImpl service = createService(properties, new OkHttpClient(), mock(DashboardService.class));
        AiPlannerService planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(planner.plan(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_QUERY, "inventory_stock",
                Map.of("warehouseCode", "LAMP-WH-LINE"), "", "", List.of(), "test"));
        service.setAiPlannerService(planner);
        AiIntentRouter router = mock(AiIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(new AiIntentRouter.RouteResult(
                "inventory_stock", Map.of("warehouseType", "RAW"), true));
        when(router.execute(any())).thenReturn(queryResult("共 64 条库存记录", "LAMP-M01"));
        service.setIntentRouter(router);

        service.agentChat(chatRequest("线边仓还有多少"), admin());

        verify(router).execute(org.mockito.ArgumentMatchers.argThat(route ->
                "LAMP-WH-LINE".equals(route.string("warehouseCode"))
                        && "RAW".equals(route.string("warehouseType"))));
    }

    private com.neuedu.ems.entity.ai.AiQueryResult queryResult(String summary, String firstCell) {
        return new com.neuedu.ems.entity.ai.AiQueryResult(
                "物料清单", summary,
                List.of(new com.neuedu.ems.entity.ai.AiColumn("materialCode", "物料编码")),
                List.of(Map.of("materialCode", firstCell)), List.of());
    }

    /** 一个稳定的「请补问」计划：复现模型这次没把单号抄进 JSON 的情况。 */
    private AiPlannerService clarifyPlanner() {
        AiPlannerService planner = mock(AiPlannerService.class);
        when(planner.available()).thenReturn(true);
        when(planner.plan(any(), any())).thenReturn(new AiPlan(AiPlan.MODE_CLARIFY,
                "RELEASE_WORK_ORDER", Map.of(), "请告诉我具体是哪一张单据。", "", List.of(), "test"));
        return planner;
    }

    private Map<String, Object> releasePreview() {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("actionLabel", "下达生产工单");
        preview.put("targetNo", "WO-9-20261025214348845");
        preview.put("targetId", 9L);
        preview.put("canExecute", true);
        preview.put("preconditions", List.of(Map.of("code", "WORK_ORDER_STATUS", "passed", true)));
        preview.put("confirmationId", "abc123");
        return preview;
    }

    private AiChatRequest chatRequest(String text) {
        AiChatRequest request = new AiChatRequest();
        request.setQuestion(text);
        request.setModule("general");
        return request;
    }

    private AuthorizationContext admin() {
        return new AuthorizationContext(7L, 8L, 9L, "ALL", Set.of("SUPER_ADMIN"),
                Set.of("ai:assistant:use"), Set.of());
    }

    private AiServiceImpl createService(AiProperties properties, OkHttpClient client,
                                        DashboardService dashboardService) {
        return createService(properties, client, dashboardService,
                mock(MfgProductionOrderMapper.class), mock(MfgProductionPlanMapper.class));
    }

    private AiServiceImpl createService(AiProperties properties, OkHttpClient client,
                                        DashboardService dashboardService, MfgProductionOrderMapper orderMapper,
                                        MfgProductionPlanMapper planMapper) {
        return new AiServiceImpl(properties, client, objectMapper, dashboardService, orderMapper, planMapper);
    }

    private AiProperties enabledProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiUrl("https://example.test/chat/completions");
        properties.setApiKey("test-api-key");
        properties.setModel("test-model");
        return properties;
    }

    private AiQuestion question(String text, String module, Map<String, Object> context) {
        AiQuestion question = new AiQuestion();
        question.setQuestion(text);
        question.setModule(module);
        question.setContext(context);
        return question;
    }

    private String upstreamResponse(String content) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "test-model");
        root.putArray("choices").addObject().putObject("message").put("content", content);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OkHttpClient responseClient(int code, String body, RequestInspector inspector) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            if (inspector != null) {
                inspector.inspect(chain.request());
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(ResponseBody.create(body, JSON))
                    .build();
        }).build();
    }

    @FunctionalInterface
    private interface RequestInspector {
        void inspect(okhttp3.Request request) throws IOException;
    }
}
