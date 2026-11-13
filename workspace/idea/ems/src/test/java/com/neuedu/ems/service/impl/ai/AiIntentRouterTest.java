package com.neuedu.ems.service.impl.ai;
import com.neuedu.ems.service.ai.AiDataQueryService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiIntentRouterTest {
    private final AiIntentRouterImpl router=new AiIntentRouterImpl(mock(AiDataQueryService.class));
    @Test void followupKeepsMaterialDateAndIntent() {
        var first=router.route("这个月控制板收发存",List.of(),null);
        var next=router.route("那出库呢",List.of("这个月控制板收发存"),null);
        assertEquals("inventory_flow",next.intent()); assertEquals("控制板",next.params().get("keyword"));
        assertEquals(first.date("dateFrom"),next.date("dateFrom"));
    }
    @Test void newTopicDoesNotInheritBusinessIntent() {
        assertFalse(router.route("今天天气怎么样",List.of("这个月控制板收发存"),null).matched());
    }
    @Test void followupKeepsScheduleAndNarrowsToLine() {
        var next = router.route("只看二号线", List.of("本周排程和产能怎么样"), null);
        assertEquals("schedule_capacity", next.intent());
        assertEquals("二号线", next.params().get("lineKeyword"));
    }
    @Test void followupKeepsScheduleAndNarrowsToNamedLine() {
        var next = router.route("只看整机总装测试线", List.of("本周排程和产能怎么样"), null);
        assertEquals("schedule_capacity", next.intent());
        assertEquals("整机总装测试线", next.params().get("lineKeyword"));
    }
    @Test void scheduleQuestionCanNameTheLineDirectly() {
        var route = router.route("本周二号线排程怎么样", List.of(), null);
        assertEquals("schedule_capacity", route.intent());
        assertEquals("二号线", route.params().get("lineKeyword"));
    }
    @Test void delayedOrderFollowupKeepsWeekLineAndDoesNotDropTheFilter() {
        var first = router.route("本周排程和产能怎么样", List.of(), null);
        var late = router.route("哪些订单延期", List.of("本周排程和产能怎么样", "只看二号线"), null);
        assertEquals("schedule_capacity", late.intent());
        assertEquals(Boolean.TRUE, late.params().get("lateOnly"));
        assertEquals("二号线", late.params().get("lineKeyword"));
        assertEquals(first.date("dateFrom"), late.date("dateFrom"));
        assertEquals(first.date("dateTo"), late.date("dateTo"));
    }
    @Test void missingProductDoesNotSilentlyBecomeL200() {
        var next=router.route("追加500台能不能按期",List.of(),null);
        assertNull(next.params().get("productKeyword"));
    }
    @Test void canAskWhetherWorkOrderCanStart() {
        var route = router.route("WO-5-20261025214222723 这个工单能不能开工", List.of(), null);
        assertEquals("work_order_readiness", route.intent());
        assertEquals("WO-5-20261025214222723", route.params().get("workOrderNo"));
    }
    @Test void askingHowToUnblockWorkOrderGetsStartPlanInsteadOfReadiness() {
        // 「怎么才能开工」要的是有序方案，不是一张核查表；两类意图的判别顺序不能颠倒。
        var route = router.route("WO-5-20261025214222723 怎么才能开工，先做什么", List.of(), null);
        assertEquals("work_order_start_plan", route.intent());
        assertEquals("WO-5-20261025214222723", route.params().get("workOrderNo"));
    }
    @Test void lowStockQuestionBeatsPlainInventoryStock() {
        // 「低于安全库存」带「库存」二字，不能被库存现有量抢走。
        var route = router.route("哪些物料低于安全库存", List.of(), null);
        assertEquals("inventory_below_safety", route.intent());
    }
    @Test void lowStockRouteKeepsNamedWarehouseTypeAndKeyword() {
        var route = router.route("线边仓哪些物料低于安全库存", List.of(), null);
        assertEquals("inventory_below_safety", route.intent());
        assertEquals("LINE_SIDE", route.params().get("warehouseType"));
    }
    @Test void lowStockOverviewDoesNotFilterByTheQuestionOrPreviousMaterial() {
        var route = router.route("哪些物料低于安全库存？缺口是多少？",
                List.of("LAMP-M01 的库存还有多少"), null);
        assertEquals("inventory_below_safety", route.intent());
        assertNull(route.params().get("materialKeyword"));
    }
    @Test void lowStockQuestionKeepsExplicitMaterialAndSupportsFollowup() {
        var route = router.route("线边仓 LAMP-M01 是否低于安全库存？缺口是多少？", List.of(), null);
        assertEquals("LINE_SIDE", route.params().get("warehouseType"));
        assertEquals("LAMP-M01", route.params().get("materialKeyword"));
        var next = router.route("那低于安全库存吗", List.of("LAMP-M01 的库存还有多少"), null);
        assertEquals("LAMP-M01", next.params().get("materialKeyword"));
    }
    @Test void plainStockQuestionStaysOnInventoryStock() {
        var route = router.route("L200 现在库存还有多少", List.of(), null);
        assertEquals("inventory_stock", route.intent());
    }
    @Test void flowQuestionIsNotStolenByLowStockRoute() {
        var route = router.route("这个月控制板收发存", List.of(), null);
        assertEquals("inventory_flow", route.intent());
    }
    @Test void plainStockQuestionKeepsChineseWarehouseName() {
        // 「线边仓还有多少」不带仓库编码，但口径必须锁到线边仓，否则报的是全仓合计数。
        var route = router.route("线边仓 LAMP-M01 的可用量", List.of(), null);
        assertEquals("inventory_stock", route.intent());
        assertEquals("LINE_SIDE", route.params().get("warehouseType"));
    }
    @Test void workOrderNotStartedIsNotTreatedAsUnfinished() {
        // 「没开工」是一个工序都没动，「没完工」是已经在做，两者筛选的工单不是同一批。
        var notStarted = router.route("还有哪些工单没有开工", List.of(), null);
        assertEquals("work_order_list", notStarted.intent());
        assertEquals("NOT_STARTED", notStarted.params().get("status"));
        var unfinished = router.route("还有哪些工单没完工", List.of(), null);
        assertEquals("UNFINISHED", unfinished.params().get("status"));
    }
    @Test void defectQuestionKeepsTheNamedProduct() {
        var route = router.route("LAMP-L100 触控调光台灯的不良情况", List.of(), null);
        assertEquals("defect_analysis", route.intent());
        // 保留完整编码：只留 L100 会把 LAMP-BASE-L100 控制底座的不良也算进来，
        // 用户问整灯不良却看到底座数据，会以为系统算错。
        assertEquals("LAMP-L100", route.params().get("productKeyword"));
    }
    @Test void ordinalFollowupPicksTheRowInsteadOfRepeatingTheList() {
        var next = router.route("那第一个呢", List.of("这个月控制板收发存"), null);
        assertEquals("inventory_flow", next.intent());
        assertEquals(1, next.params().get("pickIndex"));
        var second = router.route("再看第二条", List.of("这个月控制板收发存"), null);
        assertEquals(2, second.params().get("pickIndex"));
        var last = router.route("最后一条", List.of("这个月控制板收发存"), null);
        assertEquals(-1, last.params().get("pickIndex"));
    }
    @Test void ordinalFollowupNarrowsRowsButKeepsBlocks() {
        var result = new com.neuedu.ems.entity.ai.AiQueryResult("工单清单", "共 2 张",
                List.of(new com.neuedu.ems.entity.ai.AiColumn("workOrderNo", "工单号"),
                        new com.neuedu.ems.entity.ai.AiColumn("status", "状态")),
                List.of(java.util.Map.of("workOrderNo", "WO-A", "status", "已下达"),
                        java.util.Map.of("workOrderNo", "WO-B", "status", "已完成")),
                List.of());
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("pickIndex", 1);
        var queryService = mock(AiDataQueryService.class);
        when(queryService.queryWorkOrdersByProduct(any(), any())).thenReturn(result);
        var pickRouter = new AiIntentRouterImpl(queryService);
        var picked = pickRouter.execute(new AiIntentRouterImpl.RouteResult(
                AiIntentRouterImpl.INTENT_WORK_ORDER_LIST, params, true));
        assertNotNull(picked);
        assertEquals(1, picked.getRows().size());
        assertEquals("WO-A", picked.getRows().get(0).get("workOrderNo"));
        assertTrue(picked.getSummary().contains("WO-A"));
        // 追问的回答要念中文列名，不能把 workOrderNo 这种字段名直接抛给用户。
        assertTrue(picked.getSummary().contains("工单号"));
        assertFalse(picked.getSummary().contains("workOrderNo"));
    }
    @Test void fullProductCodeIsNotTruncatedToTheModelNumber() {
        var route = router.route("LAMP-L200 相关的工单有哪些", List.of(), null);
        assertEquals("work_order_list", route.intent());
        // 只留 L200 会把 LAMP-BASE-L200 控制底座工单一起带出来，用户会以为筛选没生效。
        assertEquals("LAMP-L200", route.params().get("productKeyword"));
    }
    @Test void ordinalBeyondResultSizeSaysSoInsteadOfInventingRow() {        var result = new com.neuedu.ems.entity.ai.AiQueryResult("工单清单", "共 1 张",
                List.of(new com.neuedu.ems.entity.ai.AiColumn("workOrderNo", "工单号")),
                List.of(java.util.Map.of("workOrderNo", "WO-A")), List.of());
        var queryService = mock(AiDataQueryService.class);
        when(queryService.queryWorkOrdersByProduct(any(), any())).thenReturn(result);
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("pickIndex", 5);
        var picked = new AiIntentRouterImpl(queryService).execute(
                new AiIntentRouterImpl.RouteResult(AiIntentRouterImpl.INTENT_WORK_ORDER_LIST, params, true));
        assertNotNull(picked);
        assertTrue(picked.getRows().isEmpty());
        assertTrue(picked.getSummary().contains("没有第 5 条"));
    }
    @Test void bomUsageQuestionGoesToBomRequirementInsteadOfCountingRows() {
        var route = router.route("LAMP-L200 用的螺钉一共要多少颗", List.of(), null);
        // 以前落到 data_query 只回「BOM 命中 1 条」，用户要的数量一个都没答。
        assertEquals("bom_requirement", route.intent());
        assertEquals("LAMP-L200", route.params().get("productKeyword"));
        assertEquals("螺钉", route.params().get("materialKeyword"));
    }
    @Test void bomUsageQuestionKeepsTheProduceQuantity() {
        var route = router.route("做 10 台 LAMP-L200 一共要多少套线束", List.of(), null);
        assertEquals("bom_requirement", route.intent());
        assertEquals("线束", route.params().get("materialKeyword"));
        assertEquals(0, new java.math.BigDecimal("10").compareTo(route.decimal("quantity")));
    }
    @Test void bomUsageQuestionWorksWithMaterialCodeOnly() {
        var route = router.route("LAMP-L200 的 LAMP-M18 用量是多少", List.of(), null);
        assertEquals("bom_requirement", route.intent());
        assertEquals("LAMP-M18", route.params().get("materialKeyword"));
        // 没说台数就按 1 台算，不能默认成 0 台算出全 0。
        assertNull(route.decimal("quantity"));
    }
    @Test void materialIssueQuestionIsNotStolenByBomUsage() {
        // 「领料还差多少」问的是缺口，不是 BOM 用量，不能被用量路由抢走。
        var route = router.route("LAMP-L200 的工单领料还差多少", List.of(), null);
        assertNotEquals("bom_requirement", route.intent());
    }
    @Test void bomUsageQuestionWithoutProductIsNotGuessed() {
        // 没说哪个产品就不该硬猜一个，交给通用问答去反问。
        var route = router.route("螺钉一共要多少颗", List.of(), null);
        assertNotEquals("bom_requirement", route.intent());
    }
    @Test void yieldRateQuestionGoesToQualityNotProduction() {
        // 良率是质量口径，用报工不良率顶替会漏掉来料与过程检验判的不合格。
        var route = router.route("这个月良率多少", List.of(), null);
        assertEquals("quality_yield", route.intent());
        var rate = router.route("本周合格率是多少", List.of(), null);
        assertEquals("quality_yield", rate.intent());
    }
    @Test void defectRateQuestionStaysOnDefectAnalysis() {
        // 「不良率」问的是不良数量分布，不是合格率。
        var route = router.route("这个月不良率多少", List.of(), null);
        assertEquals("defect_analysis", route.intent());
    }
}
