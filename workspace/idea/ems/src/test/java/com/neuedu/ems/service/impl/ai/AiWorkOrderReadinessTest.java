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
 * 开工条件核查的判定口径。
 *
 * <p>这些用例锁住三件事：结论必须与 MfgWorkOrderServiceImpl.startWorkOrder 的校验顺序一致；
 * 组件件缺料要说清是哪些组件、对应子工单是什么状态；能开工时不能再报阻塞原因。</p>
 */
class AiWorkOrderReadinessTest {

    private static final String WORK_ORDER_NO = "WO-5-20261025214222723";

    private final AiDataQueryMapper mapper = mock(AiDataQueryMapper.class);
    private final MdmProductMapper productMapper = mock(MdmProductMapper.class);
    private final AiDataQueryServiceImpl service = new AiDataQueryServiceImpl(
            mapper, mock(MdmMaterialMapper.class), productMapper, mock(WmsWarehouseMapper.class));

    @BeforeEach
    void header() {
        when(mapper.selectWorkOrderHeader(WORK_ORDER_NO)).thenReturn(header(5L, 6L, "RELEASED"));
        when(mapper.selectWorkOrderHeader("WO-NOT-EXIST")).thenReturn(null);
    }

    @Test
    void draftComponentWorkOrdersBlockStartAndAreNamedInSummary() {
        product(6L, "ENABLE", 100L);
        when(mapper.selectWorkOrderReadinessMaterials(5L)).thenReturn(List.of(
                material("LAMP-HEAD", "灯头", "2.0000", "0.0000", "COMPONENT_WORK_ORDER", "WO-C-6-20261025214222723", "DRAFT"),
                material("LAMP-ARM", "灯臂", "2.0000", "0.0000", "COMPONENT_WORK_ORDER", "WO-C-7-20261025214222723", "DRAFT"),
                material("LAMP-BASE-L200", "控制底座", "2.0000", "0.0000", "COMPONENT_WORK_ORDER", "WO-C-8-20261025214222723", "DRAFT"),
                material("LAMP-M15", "螺丝", "2.0000", "2.0000", "STOCK", null, null)));
        when(mapper.selectWorkOrderTaskStats(5L, "WAIT_START")).thenReturn(Map.of("taskCount", 0, "nonWaitingTaskCount", 0));

        AiQueryResult result = service.queryWorkOrderReadiness(WORK_ORDER_NO);

        String summary = result.getSummary();
        assertTrue(summary.contains("当前不能直接开工"), summary);
        assertTrue(summary.contains("灯头 2"), summary);
        assertTrue(summary.contains("灯臂 2"), summary);
        assertTrue(summary.contains("控制底座 2"), summary);
        assertTrue(summary.contains("WO-C-6-20261025214222723"), summary);
        assertTrue(summary.contains("草稿"), summary);
        assertTrue(summary.contains("尚未生成工序任务"), summary);
        assertTrue(summary.contains("建议先"), summary);

        // 主表七项检查：制造准备、单据状态、用料需求、物料齐套、工序任务，任务状态因为没任务不出现。
        assertEquals(5, result.getRows().size());
        assertEquals("未通过", row(result, "物料齐套").get("passed"));
        assertEquals("未通过", row(result, "工序任务").get("passed"));
        // 组件件与库存件分两张表，处置说明不同。
        assertEquals(1, result.getBlocks().size());
        assertEquals("待组件工单产出的物料", result.getBlocks().getFirst().getTitle());
        assertEquals(3, result.getBlocks().getFirst().getRows().size());
    }

    @Test
    void stockedWorkOrderWithWaitingTasksCanStart() {
        product(6L, "ENABLE", 100L);
        when(mapper.selectWorkOrderReadinessMaterials(5L)).thenReturn(List.of(
                material("LAMP-M15", "螺丝", "2.0000", "2.0000", "STOCK", null, null),
                material("LAMP-M17", "灯罩", "8.0000", "8.0000", "STOCK", null, null)));
        when(mapper.selectWorkOrderTaskStats(5L, "WAIT_START")).thenReturn(Map.of("taskCount", 3, "nonWaitingTaskCount", 0));

        AiQueryResult result = service.queryWorkOrderReadiness(WORK_ORDER_NO);

        assertTrue(result.getSummary().contains("可以开始生产"), result.getSummary());
        assertFalse(result.getSummary().contains("不能直接开工"), result.getSummary());
        assertEquals(6, result.getRows().size());
        assertEquals("通过", row(result, "任务状态").get("passed"));
        assertTrue(result.getBlocks().isEmpty());
    }

    @Test
    void stockShortageIsReportedAsWarehouseIssue() {
        product(6L, "ENABLE", 100L);
        when(mapper.selectWorkOrderReadinessMaterials(5L)).thenReturn(List.of(
                material("LAMP-M17", "灯罩", "8.0000", "4.0000", "STOCK", null, null)));
        when(mapper.selectWorkOrderTaskStats(5L, "WAIT_START")).thenReturn(Map.of("taskCount", 2, "nonWaitingTaskCount", 0));

        AiQueryResult result = service.queryWorkOrderReadiness(WORK_ORDER_NO);

        assertTrue(result.getSummary().contains("当前不能直接开工"), result.getSummary());
        assertTrue(result.getSummary().contains("灯罩 缺 4"), result.getSummary());
        assertTrue(result.getSummary().contains("需要仓库发料"), result.getSummary());
        assertEquals("等待仓库发料的物料", result.getBlocks().getFirst().getTitle());
        assertEquals("等待仓库发料", result.getBlocks().getFirst().getRows().getFirst().get("status"));
    }

    @Test
    void disabledProductBlocksBeforeAnythingElse() {
        product(6L, "DISABLE", 100L);

        AiQueryResult result = service.queryWorkOrderReadiness(WORK_ORDER_NO);

        assertEquals("未通过", row(result, "制造准备").get("passed"));
        assertTrue(result.getSummary().contains("产品未启用"), result.getSummary());
    }

    @Test
    void productWithoutFinishedMaterialBlocksStart() {
        product(6L, "ENABLE", null);

        AiQueryResult result = service.queryWorkOrderReadiness(WORK_ORDER_NO);

        assertEquals("未通过", row(result, "制造准备").get("passed"));
        assertTrue(result.getSummary().contains("未关联成品物料"), result.getSummary());
    }

    @Test
    void runningWorkOrderCannotStartAgain() {
        product(6L, "ENABLE", 100L);
        when(mapper.selectWorkOrderHeader(WORK_ORDER_NO)).thenReturn(header(5L, 6L, "RUNNING"));
        when(mapper.selectWorkOrderReadinessMaterials(5L)).thenReturn(List.of(
                material("LAMP-M15", "螺丝", "2.0000", "2.0000", "STOCK", null, null)));
        when(mapper.selectWorkOrderTaskStats(5L, "WAIT_START")).thenReturn(Map.of("taskCount", 2, "nonWaitingTaskCount", 2));

        AiQueryResult result = service.queryWorkOrderReadiness(WORK_ORDER_NO);

        assertEquals("未通过", row(result, "单据状态").get("passed"));
        assertEquals("未通过", row(result, "任务状态").get("passed"));
        assertTrue(result.getSummary().contains("生产中"), result.getSummary());
    }

    @Test
    void unknownWorkOrderReturnsEmptyResultWithHints() {
        AiQueryResult result = service.queryWorkOrderReadiness("WO-NOT-EXIST");

        assertTrue(result.getSummary().contains("未找到工单"), result.getSummary());
        assertTrue(result.getRows().isEmpty());
        assertFalse(result.getColumns().isEmpty());
    }

    /* ==================== 造数据 ==================== */

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

    private Map<String, Object> row(AiQueryResult result, String item) {
        for (Map<String, Object> row : result.getRows()) {
            if (item.equals(row.get("item"))) {
                return row;
            }
        }
        throw new AssertionError("没有找到检查项：" + item + "，实际为 " + result.getRows());
    }

    @SuppressWarnings("unused")
    private List<AiBlock> blocks(AiQueryResult result) {
        return result.getBlocks();
    }
}
