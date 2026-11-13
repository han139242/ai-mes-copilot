package com.neuedu.ems.service.impl.ai;
import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.entity.ai.AiChatRequest;
import com.neuedu.ems.service.ai.AiDataQueryService;
import com.neuedu.ems.service.ai.AiIntentRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 在显式指定的本机台灯库验证真实SQL；模型关闭，不调用外部API。 */
@SpringBootTest(properties={"mes.notification.scheduling-enabled=false","mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY,PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127[.]0[.]0[.]1:(?:3306|3307|3310)/mes_lamp_.*")
class AiQuerySmokeTest {
    @Autowired AiIntentRouter router;
    @Autowired AiDataQueryService queries;
    @Autowired AiServiceImpl service;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.neuedu.ems.mapper.ai.AiDataQueryMapper mapper;
    private final AuthorizationContext admin=new AuthorizationContext(1L,null,null,"ALL",Set.of("SUPER_ADMIN"),Set.of(),Set.of());
    @Test void weeklyScheduleReturnsStructuredDataWithoutCallingModel() {
        var request=new AiChatRequest();request.setQuestion("本周排程和产能怎么样");
        var answer=service.agentChat(request,admin);
        assertEquals("schedule_capacity",answer.get("intent"));assertEquals("DATABASE",answer.get("source"));
        assertEquals(0,answer.get("tokenCost"));assertFalse(((List<?>)answer.get("blocks")).isEmpty());
        assertEquals(0L,service.getUsage().get("totalCalls"));
    }
    @Test void followupKeepsMaterialAndMonth() {
        var first=router.route("这个月控制板收发存",List.of(),null);
        var next=router.route("那出库呢",List.of("这个月控制板收发存"),null);
        assertEquals("inventory_flow",next.intent());assertEquals("控制板",next.params().get("keyword"));
        assertEquals(first.date("dateFrom"),next.date("dateFrom"));assertNotNull(router.execute(next));
    }
    @Test void businessSqlExecutesAgainstLampSchema() {
        var from=LocalDate.of(2026,10,19);var to=LocalDate.of(2026,10,25);
        var results=List.of(queries.queryInventoryByMaterial(null,null,from,to),
                queries.queryInventoryByWarehouse(null,null,null),
                queries.queryProductionByDate(from,to),queries.queryDefectByPeriod(from,to,null),
                queries.queryScheduleCapacity(from,to),
                queries.queryCapacityFeasibility("LAMP-L200",100,from,to),queries.queryEquipmentImpact(null,from,to),
                queries.queryTraceByBatch("NOT-A-REAL-BATCH"));
        for(var result:results){assertNotNull(result.getTitle());assertNotNull(result.getColumns());assertNotNull(result.getRows());}
    }
    @Test void missingProductAndPermissionsCannotReadAnotherDataset() {
        var request=new AiChatRequest();request.setQuestion("库存还有多少");
        assertThrows(com.neuedu.ems.exception.DataScopeAccessDeniedException.class,()->service.agentChat(request,null));
        assertThrows(com.neuedu.ems.exception.AiServiceException.class,()->queries.queryCapacityFeasibility(null,100,LocalDate.now(),LocalDate.now()));
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void batchTraceUsesTheSameConsumptionRecordsInBothDirections() {
        String tag = Long.toString(System.nanoTime()).replace("-", "");
        String input = "LAMP-TRACE-" + tag + "-A";
        String second = "LAMP-TRACE-" + tag + "-B";
        String finished = "FG-TRACE-" + tag;
        String noLink = "LAMP-TRACE-" + tag + "-EMPTY";
        String lot = "C3-LP-20261030-" + tag;
        Long material = jdbc.queryForObject("SELECT m.material_id FROM mdm_material m JOIN mdm_unit u"
                + " ON u.unit_id=m.unit_id AND u.deleted_flag=0 WHERE m.deleted_flag=0 LIMIT 1", Long.class);
        for (String number : List.of(input, second, finished, noLink)) {
            jdbc.update("INSERT INTO wms_material_batch(batch_no,material_id,deleted_flag) VALUES (?,?,0)", number, material);
        }
        jdbc.update("INSERT INTO trace_product_lot(lot_no,deleted_flag) VALUES (?,0)", lot);
        Long lotId = jdbc.queryForObject("SELECT product_lot_id FROM trace_product_lot WHERE lot_no=?", Long.class, lot);
        jdbc.update("UPDATE wms_material_batch SET source_product_lot_id=? WHERE batch_no=?", lotId, finished);
        Long inputId = jdbc.queryForObject("SELECT batch_id FROM wms_material_batch WHERE batch_no=?", Long.class, input);
        Long secondId = jdbc.queryForObject("SELECT batch_id FROM wms_material_batch WHERE batch_no=?", Long.class, second);
        jdbc.update("INSERT INTO trace_material_usage(product_lot_id,material_id,batch_id,used_quantity,deleted_flag)"
                + " VALUES (?,?,?,2.5000,0),(?,?,?,1,0),(?,?,?,12,1),(99999999,?,?,5,0)",
                lotId, material, inputId, lotId, material, secondId, lotId, material, inputId, material, inputId);

        var forward = traceBlock(queries.queryTraceByBatch(input), "正查关联产品批");
        var reverse = traceBlock(queries.queryTraceByBatch(lot), "反查来源物料批");
        assertEquals(1, forward.getRows().size());
        assertEquals(2, reverse.getRows().size());
        var edge = forward.getRows().getFirst();
        assertEquals(edge, reverse.getRows().stream().filter(row -> input.equals(row.get("inputBatchNo"))).findFirst().orElseThrow());
        assertEquals(lot, edge.get("outputLotNo"));
        assertEquals(0, new java.math.BigDecimal("2.5").compareTo(new java.math.BigDecimal(edge.get("usedQuantity").toString())));
        assertFalse(edge.get("unitName").toString().isBlank());
        var warehouse = queries.queryTraceByBatch(finished);
        assertTrue(warehouse.getSummary().contains(lot));
        assertEquals(reverse.getRows(), traceBlock(warehouse, "反查来源物料批").getRows());
        var missing = queries.queryTraceByBatch(noLink);
        assertTrue(missing.getSummary().contains("未找到有效的直接用料记录"));
        assertTrue(missing.getBlocks().stream().noneMatch(block -> "正查关联产品批".equals(block.getTitle())));

        var request = new AiChatRequest();
        request.setQuestion("追溯成品批次 " + lot + " 用了哪些物料批次");
        var answer = service.agentChat(request, admin);
        assertEquals("batch_trace", answer.get("intent"));
        assertEquals("DATABASE", answer.get("source"));
        assertTrue(answer.get("answer").toString().contains("2 个来源物料批"));
        assertEquals(0, answer.get("tokenCost"));
    }

    private com.neuedu.ems.entity.ai.AiBlock traceBlock(com.neuedu.ems.entity.ai.AiQueryResult result, String title) {
        return result.getBlocks().stream().filter(block -> title.equals(block.getTitle())).findFirst().orElseThrow();
    }
    @Test
    @EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/mes_lamp_stage2_[0-9]{8}_trace[?].*")
    void lampRehearsalReportsAgreeWithTheDemoPrompts() {
        String input = "LAMP-REH-20261025-M3";
        String lot = "C3-LP-20261030-001";
        var forward = traceBlock(queries.queryTraceByBatch(input), "正查关联产品批");
        var reverse = traceBlock(queries.queryTraceByBatch(lot), "反查来源物料批");
        var edge = forward.getRows().stream().filter(row -> lot.equals(row.get("outputLotNo"))).findFirst().orElseThrow();
        assertTrue(reverse.getRows().contains(edge));
        assertEquals("C3-WR-20261030-002", edge.get("reportNo"));
        assertEquals("C3-WO-20261030-002", edge.get("workOrderNo"));
        assertEquals(0, new java.math.BigDecimal("10").compareTo(new java.math.BigDecimal(edge.get("usedQuantity").toString())));
        assertEquals("片", edge.get("unitName"));
        for (String question : List.of("正查原料批次 " + input + " 的去向", "反查成品批次 " + lot + " 用了哪些原料批次")) {
            var request = new AiChatRequest();
            request.setQuestion(question);
            var answer = service.agentChat(request, admin);
            assertEquals("batch_trace", answer.get("intent"));
            assertEquals("DATABASE", answer.get("source"));
            assertEquals(0, answer.get("tokenCost"));
        }
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void equipmentQuerySeparatesCurrentFaultsFromDatedRepairReports() {
        String code = "EQ-AI-" + System.nanoTime();
        jdbc.update("INSERT INTO eam_equipment(equipment_code,equipment_name,status,deleted_flag) VALUES (?,?,'FAULT',0)",
                code, code);
        Long id = jdbc.queryForObject("SELECT equipment_id FROM eam_equipment WHERE equipment_code=?", Long.class, code);
        jdbc.update("INSERT INTO eam_repair_order(equipment_id,repair_no,status,reported_time,deleted_flag) VALUES "
                + "(?,NULL,'SUBMITTED','2026-10-12 00:00:00',0),"
                + "(?,'CLOSED-12','CLOSED','2026-10-12 23:59:59',0),"
                + "(?,'CLOSED-13','CLOSED','2026-10-13 00:00:00',0)", id, id, id);

        var live = queries.queryEquipmentImpact(code, null, null);
        assertEquals(1, live.getRows().size());
        assertTrue(live.getSummary().contains("1 台设备、1 张报修单"));
        assertTrue(live.getSummary().contains("未关联有效工位"));
        assertFalse(live.getSummary().contains("0 个未完成任务"));

        var request = new AiChatRequest();
        request.setQuestion("查看 " + code + " 设备 2026-10-12 报修记录");
        var history = service.agentChat(request, admin);
        assertEquals("equipment_impact", history.get("intent"));
        assertEquals("DATABASE", history.get("source"));
        assertTrue(history.get("answer").toString().contains("1 台设备、2 张报修单"));
        var past = queries.queryEquipmentImpact(code, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12));
        assertEquals(List.of("CLOSED-12", ""), past.getRows().stream().map(row -> row.get("repairNo")).toList());
        assertTrue(past.getBlocks().isEmpty());
        assertEquals(0L, service.getUsage().get("totalCalls"));
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void capacityQueryExcludesStockAssignedToAnotherWorkOrder() {
        Long materialId=jdbc.queryForObject("SELECT material_id FROM mdm_material WHERE material_code LIKE 'LAMP-%' AND deleted_flag=0 LIMIT 1",Long.class);
        Long warehouseId=jdbc.queryForObject("SELECT warehouse_id FROM wms_warehouse WHERE status='ENABLE' AND deleted_flag=0 LIMIT 1",Long.class);
        java.math.BigDecimal before=stock(materialId);
        String batchNo="TEST-AI-SCOPED-"+System.nanoTime();
        jdbc.update("INSERT INTO wms_material_batch(batch_no,material_id,quality_status,status,deleted_flag) VALUES (?,?,'PASS','AVAILABLE',0)",batchNo,materialId);
        Long batchId=jdbc.queryForObject("SELECT batch_id FROM wms_material_batch WHERE batch_no=?",Long.class,batchNo);
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,material_id,batch_id,available_quantity,work_order_id,deleted_flag) VALUES (?,?,?,12345,987654321,0)",warehouseId,materialId,batchId);
        assertEquals(0,before.compareTo(stock(materialId)));
    }
    private java.math.BigDecimal stock(Long materialId) {
        return mapper.selectAvailableStock(List.of(materialId)).stream()
                .map(row->new java.math.BigDecimal(row.get("availableQuantity").toString()))
                .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void capacityQueryRoundsDiscreteDemandInsteadOfShowingFractionalPieces() {
        var item=jdbc.queryForMap("SELECT bi.bom_item_id AS id,m.material_code AS code FROM mdm_bom_item bi JOIN mdm_bom b ON b.bom_id=bi.bom_id JOIN mdm_product p ON p.product_id=b.product_id JOIN mdm_material m ON m.material_id=bi.material_id WHERE p.product_code='LAMP-L200' AND b.status='RELEASED' AND bi.deleted_flag=0 AND m.quantity_mode='DISCRETE' AND m.issue_step=1 LIMIT 1");
        jdbc.update("UPDATE mdm_bom_item SET quantity=0.04,loss_rate=0 WHERE bom_item_id=?",item.get("id"));
        var result=queries.queryCapacityFeasibility("LAMP-L200",1,LocalDate.of(2026,10,19),LocalDate.of(2026,10,25));
        var row=result.getBlocks().getFirst().getRows().stream().filter(value->item.get("code").equals(value.get("materialCode"))).findFirst().orElseThrow();
        assertEquals(0,java.math.BigDecimal.ONE.compareTo(new java.math.BigDecimal(row.get("requiredQuantity").toString())));
        assertFalse(result.getSummary().contains("可按期完成"));
    }
}
