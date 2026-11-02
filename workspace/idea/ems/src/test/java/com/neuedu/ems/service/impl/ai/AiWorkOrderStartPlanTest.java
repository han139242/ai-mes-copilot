package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.entity.ai.AiBlock;
import com.neuedu.ems.entity.ai.AiQueryResult;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 开工处置方案的推演口径。
 *
 * <p>这些用例锁住四件事：方案只能来自开工核查的未通过项；工时只算工艺路线标准工时；
 * 「直接开工」在存在阻塞时结论必须是不可行而不是耗时比较；已就绪时不硬凑步骤。</p>
 */
class AiWorkOrderStartPlanTest {

    private static final String WORK_ORDER_NO = "WO-5-20261025214222723";
    private static final String COMPONENT_NO = "WO-C-6-20261025214222723";

    private final AiDataQueryMapper mapper = mock(AiDataQueryMapper.class);
    private final MdmProductMapper productMapper = mock(MdmProductMapper.class);
    private final AiDataQueryServiceImpl service = new AiDataQueryServiceImpl(
            mapper, mock(MdmMaterialMapper.class), productMapper, mock(WmsWarehouseMapper.class));

    @BeforeEach
    void header() {
        when(mapper.selectWorkOrderHeader(WORK_ORDER_NO)).thenReturn(header(5L, 6L, "RELEASED"));
        when(mapper.selectWorkOrderHeader("WO-NOT-EXIST")).thenReturn(null);
        product(6L, "ENABLE", 100L);
    }

    @Test
    void blockedWorkOrderGetsOrderedPlanAndInfeasibleDirectPath() {
        draftComponentScenario();
        when(mapper.selectComponentWorkOrders(5L)).thenReturn(List.of(
                component(COMPONENT_NO, 6L, "DRAFT", "灯头", "2.0000", "4.00", 2),
                component("WO-C-7-20261025214222723", 7L, "DRAFT", "灯臂", "2.0000", "3.00", 2)));
        when(mapper.selectWorkOrderUnitMinutes(5L)).thenReturn(new BigDecimal("10.50"));

        AiQueryResult result = service.queryWorkOrderStartPlan(WORK_ORDER_NO);

        // 主表一行，处置步骤数与工时都在上面。
        assertEquals(1, result.getRows().size());
        Map<String, Object> main = result.getRows().getFirst();
        assertEquals(WORK_ORDER_NO, main.get("workOrderNo"));
        assertEquals("已下达", main.get("currentStatus"));

        // 来源计划生效 1 步 + 两张未开始组件工单各 1 步 + 缺料领料 1 步 + 工序任务 1 步。
        List<Map<String, Object>> steps = block(result, "处置步骤").getRows();
        assertEquals(5, steps.size());
        assertTrue(text(steps.get(0).get("action")).contains("来源生产计划生效"),
                text(steps.get(0).get("action")));
        assertTrue(text(steps.get(1).get("action")).contains("下达并完成组件工单 " + COMPONENT_NO),
                text(steps.get(1).get("action")));
        assertTrue(steps.stream().anyMatch(row -> text(row.get("action")).contains("生成领料单")));
        assertTrue(steps.stream().anyMatch(row -> text(row.get("action")).contains("生成并核对工序任务")));
        // 步骤号从 1 起、连续不跳。
        for (int i = 0; i < steps.size(); i++) {
            assertEquals(i + 1, ((Number) steps.get(i).get("stepNo")).intValue());
        }

        // 两条路径：直接开工不可行，先补齐可行且工时 = 组件工时 + 本工单工时。
        List<Map<String, Object>> paths = block(result, "两条路径对比").getRows();
        assertEquals(2, paths.size());
        assertEquals("直接开工", paths.get(0).get("path"));
        assertEquals("不可行", paths.get(0).get("canProceed"));
        assertEquals("先补齐前置条件再开工", paths.get(1).get("path"));
        assertEquals("可行", paths.get(1).get("canProceed"));
        // 组件工时 (4*2 + 3*2) = 14，本工单 10.50*2 = 21，合计 35。
        assertEquals("35", num(paths.get(1).get("workMinutes")));
        assertEquals(2L, paths.get(1).get("documentCount"));

        // 组件工作量表逐张列出，草稿状态标成中文。
        List<Map<String, Object>> components = block(result, "组件工单工作量").getRows();
        assertEquals(2, components.size());
        assertEquals("新建", components.get(0).get("status"));
        assertEquals("8", num(components.get(0).get("totalMinutes")));
        assertTrue(result.getSummary().contains("处置方案共 5 步"), result.getSummary());
        assertTrue(result.getSummary().contains("不能当交期承诺"), result.getSummary());
    }

    @Test
    void alreadyReadyWorkOrderSaysNoPlanNeeded() {
        when(mapper.selectWorkOrderReadinessMaterials(5L)).thenReturn(List.of(
                material("LAMP-M15", "螺丝", "2.0000", "2.0000", "STOCK", null, null)));
        when(mapper.selectWorkOrderTaskStats(5L, "WAIT_START"))
                .thenReturn(Map.of("taskCount", 3, "nonWaitingTaskCount", 0));
        when(mapper.selectComponentWorkOrders(5L)).thenReturn(List.of());
        when(mapper.selectWorkOrderUnitMinutes(5L)).thenReturn(new BigDecimal("10.50"));

        AiQueryResult result = service.queryWorkOrderStartPlan(WORK_ORDER_NO);

        assertTrue(result.getSummary().contains("不需要额外的处置方案"), result.getSummary());
        assertEquals(0L, result.getRows().getFirst().get("stepCount"));
        assertTrue(block(result, "处置步骤").getRows().isEmpty());
        assertEquals("可行", block(result, "两条路径对比").getRows().getFirst().get("canProceed"));
    }

    @Test
    void missingRouteReportsZeroMinutesInsteadOfFailing() {
        draftComponentScenario();
        when(mapper.selectComponentWorkOrders(5L)).thenReturn(List.of());
        when(mapper.selectWorkOrderUnitMinutes(5L)).thenReturn(null);

        AiQueryResult result = service.queryWorkOrderStartPlan(WORK_ORDER_NO);

        assertEquals("0", num(result.getRows().getFirst().get("rootMinutes")));
        assertFalse(result.getSummary().contains("null"), result.getSummary());
    }

    /**
     * 物料全齐、组件全完、只差工序任务没生成时，「直接开工」同样不可行。
     *
     * <p>这条用例盯的是一个真实缺陷：早先的判定只看组件工单，物料齐套但工序任务未生成的工单
     * 会被写成「前置条件已满足，可直接点开始生产」，与同一份回答里的开工核查结论自相矛盾。</p>
     */
    @Test
    void directPathIsBlockedWhenOnlyRouteTasksAreMissing() {
        // 物料全部足额发到本工单，没有组件缺口。
        when(mapper.selectWorkOrderReadinessMaterials(5L)).thenReturn(List.of(
                material("LAMP-M15", "连接线束", "2.0000", "2.0000", "STOCK", null, null),
                material("LAMP-M17", "防滑脚垫", "8.0000", "8.0000", "STOCK", null, null)));
        // 工序任务还没生成：taskCount 0。
        when(mapper.selectWorkOrderTaskStats(5L, "WAIT_START"))
                .thenReturn(Map.of("taskCount", 0, "nonWaitingTaskCount", 0));
        when(mapper.selectComponentWorkOrders(5L)).thenReturn(List.of());
        when(mapper.selectWorkOrderUnitMinutes(5L)).thenReturn(new BigDecimal("10.50"));

        AiQueryResult result = service.queryWorkOrderStartPlan(WORK_ORDER_NO);

        // 核查里只有「工序任务」一项未通过，直接开工必须判不可行并点出是哪一项。
        List<Map<String, Object>> paths = block(result, "两条路径对比").getRows();
        assertEquals("直接开工", paths.get(0).get("path"));
        assertEquals("不可行", paths.get(0).get("canProceed"));
        assertTrue(text(paths.get(0).get("conclusion")).contains("工序任务"),
                text(paths.get(0).get("conclusion")));
        assertFalse(text(paths.get(0).get("conclusion")).contains("可直接点开始生产"),
                text(paths.get(0).get("conclusion")));
        // 没有组件工单时，「先补齐」这条路径的结论不能写成「推进 0 张组件工单」。
        assertFalse(text(paths.get(1).get("conclusion")).contains("0 张"),
                text(paths.get(1).get("conclusion")));
    }

    @Test
    void unknownWorkOrderReturnsTheReadinessNotFoundResult() {
        AiQueryResult result = service.queryWorkOrderStartPlan("WO-NOT-EXIST");

        assertTrue(result.getSummary().contains("未找到工单"), result.getSummary());
        assertTrue(result.getRows().isEmpty());
    }

    @Test
    void draftSourcePlanBecomesTheFirstStepSoReleaseWillNotBeRejected() {
        draftComponentScenario();
        when(mapper.selectComponentWorkOrders(5L)).thenReturn(List.of(
                component(COMPONENT_NO, 6L, "DRAFT", "灯头", "2.0000", "4.00", 2)));
        when(mapper.selectWorkOrderUnitMinutes(5L)).thenReturn(new BigDecimal("10.50"));

        AiQueryResult result = service.queryWorkOrderStartPlan(WORK_ORDER_NO);

        // 组件工单下达要求来源计划已生效，计划还是草稿时这一步必须排在最前，否则点了必失败。
        List<Map<String, Object>> steps = block(result, "处置步骤").getRows();
        String first = text(steps.getFirst().get("action"));
        assertTrue(first.contains("来源生产计划生效"), first);
        assertEquals("PLN-C-5-19-1-20261025214222723", steps.getFirst().get("document"));
        // 组件工作量的表要把计划状态一起列出来，用户才知道卡在哪一环。
        List<Map<String, Object>> components = block(result, "组件工单工作量").getRows();
        assertEquals("草稿", components.getFirst().get("planStatus"));
        assertEquals("PLN-C-5-19-1-20261025214222723", components.getFirst().get("planNo"));
    }

    /* ==================== 造数据 ==================== */

    /** 三张组件件未齐套 + 一张库存件未齐套 + 没有工序任务，就是 WO-5 的真实形态。 */
    private void draftComponentScenario() {
        when(mapper.selectWorkOrderReadinessMaterials(5L)).thenReturn(List.of(
                material("LAMP-HEAD", "灯头", "2.0000", "0.0000", "COMPONENT_WORK_ORDER",
                        COMPONENT_NO, "DRAFT"),
                material("LAMP-M17", "灯罩", "8.0000", "4.0000", "STOCK", null, null)));
        when(mapper.selectWorkOrderTaskStats(5L, "WAIT_START"))
                .thenReturn(Map.of("taskCount", 0, "nonWaitingTaskCount", 0));
    }

    private void product(Long productId, String status, Long materialId) {
        MdmProduct product = new MdmProduct();
        product.setProductId(productId);
        product.setProductName("LAMP-L200 智能调光台灯");
        product.setStatus(status);
        product.setMaterialId(materialId);
        product.setDeletedFlag(0);
        when(productMapper.selectById(productId)).thenReturn(product);
    }

    private Map<String, Object> header(Long workOrderId, Long productId, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workOrderId", workOrderId);
        row.put("workOrderNo", WORK_ORDER_NO);
        row.put("productId", productId);
        row.put("productCode", "LAMP-L200");
        row.put("productName", "LAMP-L200 智能调光台灯");
        row.put("plannedQuantity", new BigDecimal("2.0000"));
        row.put("completedQuantity", BigDecimal.ZERO);
        row.put("status", status);
        row.put("workOrderType", "ROOT");
        return row;
    }

    private Map<String, Object> material(String code, String name, String required, String issued,
                                         String supplyType, String sourceNo, String sourceStatus) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("materialCode", code);
        row.put("materialName", name);
        row.put("unitName", "个");
        row.put("requiredQuantity", new BigDecimal(required));
        row.put("issuedQuantity", new BigDecimal(issued));
        row.put("shortageQuantity", new BigDecimal(required).subtract(new BigDecimal(issued)));
        row.put("status", "WAIT_READY");
        row.put("supplyType", supplyType);
        row.put("sourceWorkOrderNo", sourceNo);
        row.put("sourceWorkOrderStatus", sourceStatus);
        return row;
    }

    private Map<String, Object> component(String workOrderNo, Long workOrderId, String status,
                                          String productName, String planned, String unitMinutes,
                                          int stepCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workOrderNo", workOrderNo);
        row.put("workOrderId", workOrderId);
        row.put("status", status);
        row.put("productName", productName);
        row.put("productCode", productName);
        row.put("productId", workOrderId);
        row.put("plannedQuantity", new BigDecimal(planned));
        row.put("unitMinutes", new BigDecimal(unitMinutes));
        row.put("stepCount", stepCount);
        row.put("planId", workOrderId);
        row.put("planNo", "PLN-C-5-19-1-20261025214222723");
        row.put("planStatus", "DRAFT");
        return row;
    }

    private AiBlock block(AiQueryResult result, String title) {
        for (AiBlock block : result.getBlocks()) {
            if (title.equals(block.getTitle())) {
                return block;
            }
        }
        throw new AssertionError("没有找到名为「" + title + "」的区块");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String num(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
