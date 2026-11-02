package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.ai.AiAutomationRule;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.service.ai.AiAutomationService;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import com.neuedu.ems.service.wms.WmsLocationService;
import com.neuedu.ems.service.wms.WmsMaterialBatchService;
import com.neuedu.ems.service.wms.WmsWarehouseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 低库存规则：预览、修改、启用后登记风险和补货通知，重复执行不重复登记，库存恢复后关闭。
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_[A-Za-z0-9_]+.*")
@Transactional
class LampLowStockAutomationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MdmMaterialMapper materials;
    @Autowired WmsMaterialBatchService batches;
    @Autowired WmsWarehouseService warehouses;
    @Autowired WmsLocationService locations;
    @Autowired WmsInboundOrderService inbounds;
    @Autowired QmsInspectionOrderService inspections;
    @Autowired AiAutomationService automation;

    @Test
    void lowStockRegistersRiskOnceAndClosesAfterRecovery() {
        String tag = "AUTO" + System.nanoTime();
        MdmMaterial material = new MdmMaterial();
        material.setMaterialCode(tag);
        material.setMaterialName("低库存自动化原料");
        material.setMaterialType("RAW");
        material.setQuantityMode("DISCRETE");
        material.setIssueStep(BigDecimal.ONE);
        material.setStatus("ENABLE");
        material.setDeletedFlag(0);
        materials.insert(material);

        AiAutomationRule rule = new AiAutomationRule();
        rule.setRuleNo(tag);
        rule.setRuleName("灯头原料低库存");
        rule.setRuleType("INVENTORY_BELOW_SAFETY");
        rule.setStatus("DRAFT");
        rule.setDefaultThreshold(new BigDecimal("5"));
        rule.setIntervalMinutes(10);
        rule.setConfigJson("{\"warehouseType\":\"RAW\",\"materials\":[{\"materialId\":"
                + material.getMaterialId() + ",\"safetyStock\":5}]}");
        Long ruleId = automation.saveRule(rule);

        Map<String, Object> preview = automation.preview(ruleId);
        assertEquals(1, preview.get("wouldCreateRisk"));

        AiAutomationRule edited = new AiAutomationRule();
        edited.setRuleId(ruleId);
        edited.setRuleName("灯头原料低库存-已改阈值");
        edited.setDefaultThreshold(new BigDecimal("8"));
        edited.setConfigJson("{\"warehouseType\":\"RAW\",\"materials\":[{\"materialId\":"
                + material.getMaterialId() + ",\"safetyStock\":8}]}");
        edited.setVersion(0);
        assertTrue(automation.updateRule(edited));
        assertTrue(automation.changeStatus(ruleId, "ENABLED"));
        Map<String, Object> raised = automation.preview(ruleId);
        assertEquals(1, raised.get("wouldCreateRisk"));

        Map<String, Object> first = automation.run(ruleId);
        assertEquals("SUCCEEDED", first.get("status"));
        assertEquals(Boolean.FALSE, first.get("idempotent"));
        assertEquals(1L, openRisks(ruleId));
        Long suggestionId = jdbc.queryForObject(
                "SELECT suggestion_id FROM ai_inventory_risk WHERE rule_id=? AND status='OPEN' AND deleted_flag=0",
                Long.class, ruleId);
        assertTrue(suggestionId != null && suggestionId > 0);
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_event_outbox WHERE aggregate_type='WMS_REPLENISHMENT' AND aggregate_id=? AND event_type='WMS_REPLENISHMENT_PENDING'",
                Integer.class, suggestionId);
        assertTrue(notices != null && notices > 0);

        automation.run(ruleId);
        assertEquals(1L, openRisks(ruleId));

        stock(tag, material.getMaterialId(), new BigDecimal("20"));
        automation.run(ruleId);
        assertEquals(0L, openRisks(ruleId));
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM ai_inventory_risk WHERE rule_id=? AND deleted_flag=0", String.class, ruleId));

        jdbc.update("UPDATE ai_automation_rule SET status='PAUSED', last_run_time=NULL WHERE rule_id=?", ruleId);
        int before = runs(ruleId);
        automation.runScheduled();
        assertEquals(before, runs(ruleId));
    }

    @Test
    void scheduledScanFinishesALeftoverRunAndDoesNotOpenASecondRisk() {
        String tag = "RETRY" + System.nanoTime();
        MdmMaterial material = new MdmMaterial();
        material.setMaterialCode(tag);
        material.setMaterialName("重启续跑原料");
        material.setMaterialType("RAW");
        material.setQuantityMode("DISCRETE");
        material.setIssueStep(BigDecimal.ONE);
        material.setStatus("ENABLE");
        material.setDeletedFlag(0);
        materials.insert(material);

        AiAutomationRule rule = new AiAutomationRule();
        rule.setRuleNo(tag);
        rule.setRuleName("重启后续跑");
        rule.setRuleType("INVENTORY_BELOW_SAFETY");
        rule.setStatus("ENABLED");
        rule.setDefaultThreshold(new BigDecimal("5"));
        rule.setIntervalMinutes(10);
        rule.setConfigJson("{\"warehouseType\":\"RAW\",\"materials\":[{\"materialId\":"
                + material.getMaterialId() + ",\"safetyStock\":5}]}");
        Long ruleId = automation.saveRule(rule);
        assertTrue(automation.changeStatus(ruleId, "ENABLED"));
        jdbc.update("UPDATE ai_automation_rule SET last_run_time=NULL WHERE rule_id=?", ruleId);

        String window = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String runKey = "SCHEDULE:" + ruleId + ":" + window;
        jdbc.update("""
                INSERT INTO ai_automation_run(run_no, run_key, rule_id, rule_no, rule_name, trigger_type,
                    status, matched_count, started_time, error_message, deleted_flag)
                VALUES (?,?,?,?,?,'SCHEDULE','RUNNING',0,?, '进程中断', 0)
                """, "RUN" + tag, runKey, ruleId, tag, "重启后续跑", LocalDateTime.now().minusMinutes(3));

        automation.runScheduled();
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT status FROM ai_automation_run WHERE run_key=? AND deleted_flag=0", String.class, runKey));
        assertEquals(1, runs(ruleId));
        assertEquals(1L, openRisks(ruleId));

        automation.runScheduled();
        assertEquals(1, runs(ruleId));
        assertEquals(1L, openRisks(ruleId));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void inventoryChangeOpensAndClosesRiskWithoutWaitingForSchedule() {
        String tag = "EVT" + System.nanoTime();
        Long materialId = null;
        Long ruleId = null;
        try {
            MdmMaterial material = new MdmMaterial();
            material.setMaterialCode(tag);
            material.setMaterialName("库存变化触发原料");
            material.setMaterialType("RAW");
            material.setQuantityMode("DISCRETE");
            material.setIssueStep(BigDecimal.ONE);
            material.setStatus("ENABLE");
            material.setDeletedFlag(0);
            materials.insert(material);
            materialId = material.getMaterialId();

            AiAutomationRule rule = new AiAutomationRule();
            rule.setRuleNo(tag);
            rule.setRuleName("入库后立刻检查");
            rule.setRuleType("INVENTORY_BELOW_SAFETY");
            rule.setStatus("ENABLED");
            rule.setDefaultThreshold(new BigDecimal("5"));
            rule.setIntervalMinutes(10);
            rule.setConfigJson("{\"warehouseType\":\"RAW\",\"materials\":[{\"materialId\":"
                    + materialId + ",\"safetyStock\":5}]}");
            ruleId = automation.saveRule(rule);
            assertTrue(automation.changeStatus(ruleId, "ENABLED"));
            jdbc.update("UPDATE ai_automation_rule SET last_run_time=? WHERE rule_id=?", LocalDateTime.now(), ruleId);

            Long actor = jdbc.queryForObject(
                    "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
            WmsWarehouse raw = warehouse(tag, "RAW");
            WmsLocation rawLocation = location(raw, tag + "L");
            receive(tag + "-A", actor, materialId, BigDecimal.ONE, raw, rawLocation);

            assertEquals(1L, openRisks(ruleId));
            assertEquals("EVENT", jdbc.queryForObject(
                    "SELECT trigger_type FROM ai_automation_run WHERE rule_id=? AND deleted_flag=0",
                    String.class, ruleId));
            assertEquals(1, runs(ruleId));
            automation.runScheduled();
            assertEquals(1, runs(ruleId));

            receive(tag + "-B", actor, materialId, new BigDecimal("20"), raw, rawLocation);
            assertEquals(0L, openRisks(ruleId));
            assertEquals(2, runs(ruleId));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_automation_run WHERE rule_id=? AND trigger_type='EVENT' AND deleted_flag=0",
                    Integer.class, ruleId));
        } finally {
            cleanupEvent(tag, materialId, ruleId);
        }
    }

    private void receive(String inboundNo, Long actor, Long materialId, BigDecimal quantity,
                         WmsWarehouse raw, WmsLocation rawLocation) {
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchNo(inboundNo);
        batch.setMaterialId(materialId);
        assertTrue(batches.saveMaterialBatch(batch));
        QmsInspectionOrder inspection = new QmsInspectionOrder();
        inspection.setInspectionNo(inboundNo);
        inspection.setInspectionType("INCOMING");
        inspection.setBatchId(batch.getBatchId());
        inspection.setCreatedBy(actor);
        assertTrue(inspections.saveInspectionOrder(inspection));
        assertTrue(inspections.submitInspectionOrder(inspection.getInspectionId(), "PASS", "放行", actor));
        WmsInboundDetail detail = new WmsInboundDetail();
        detail.setMaterialId(materialId);
        detail.setBatchId(batch.getBatchId());
        detail.setLocationId(rawLocation.getLocationId());
        detail.setInboundQuantity(quantity);
        WmsInboundOrder inbound = new WmsInboundOrder();
        inbound.setInboundNo(inboundNo);
        inbound.setInboundType("PURCHASE");
        inbound.setWarehouseId(raw.getWarehouseId());
        inbound.setCreatedBy(actor);
        Long inboundId = inbounds.saveInboundOrderFull(inbound, List.of(detail));
        assertTrue(inbounds.confirmInboundOrder(inboundId, actor, "入库"));
    }

    private WmsWarehouse warehouse(String code, String type) {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setWarehouseCode(code);
        warehouse.setWarehouseName(code);
        warehouse.setWarehouseType(type);
        warehouse.setStatus("ENABLE");
        assertTrue(warehouses.saveWarehouse(warehouse));
        return warehouse;
    }

    private WmsLocation location(WmsWarehouse warehouse, String code) {
        WmsLocation location = new WmsLocation();
        location.setWarehouseId(warehouse.getWarehouseId());
        location.setLocationCode(code);
        location.setLocationName(code);
        location.setLocationType("NORMAL");
        location.setStatus("ENABLE");
        assertTrue(locations.saveLocation(location));
        return location;
    }

    private void cleanupEvent(String tag, Long materialId, Long ruleId) {
        if (ruleId != null) {
            jdbc.update("""
                    DELETE s FROM ai_automation_step s
                    JOIN ai_automation_run r ON r.run_id=s.run_id WHERE r.rule_id=?
                    """, ruleId);
            jdbc.update("""
                    DELETE e FROM sys_event_outbox e
                    JOIN ai_inventory_risk k ON k.suggestion_id=e.aggregate_id
                    WHERE k.rule_id=? AND e.aggregate_type='WMS_REPLENISHMENT'
                    """, ruleId);
            jdbc.update("""
                    DELETE sug FROM wms_replenishment_suggestion sug
                    JOIN ai_inventory_risk k ON k.suggestion_id=sug.suggestion_id WHERE k.rule_id=?
                    """, ruleId);
            jdbc.update("DELETE FROM ai_automation_run WHERE rule_id=?", ruleId);
            jdbc.update("DELETE FROM ai_inventory_risk WHERE rule_id=?", ruleId);
            jdbc.update("DELETE FROM ai_automation_rule WHERE rule_id=?", ruleId);
        }
        if (materialId != null) {
            jdbc.update("""
                    DELETE m FROM wms_stock_movement m
                    JOIN wms_material_batch b ON b.batch_id=m.batch_id WHERE b.material_id=?
                    """, materialId);
            jdbc.update("""
                    DELETE d FROM wms_inbound_detail d
                    JOIN wms_material_batch b ON b.batch_id=d.batch_id WHERE b.material_id=?
                    """, materialId);
            jdbc.update("DELETE FROM wms_inventory WHERE material_id=?", materialId);
            jdbc.update("DELETE FROM qms_inspection_order WHERE inspection_no LIKE ?", tag + "%");
            jdbc.update("DELETE FROM wms_material_batch WHERE material_id=?", materialId);
            jdbc.update("DELETE FROM mdm_material WHERE material_id=?", materialId);
        }
        jdbc.update("DELETE FROM wms_inbound_order WHERE inbound_no LIKE ?", tag + "%");
        jdbc.update("DELETE FROM wms_location WHERE location_code LIKE ?", tag + "%");
        jdbc.update("DELETE FROM wms_warehouse WHERE warehouse_code LIKE ?", tag + "%");
    }

    private void stock(String tag, Long materialId, BigDecimal quantity) {
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchNo(tag);
        batch.setMaterialId(materialId);
        assertTrue(batches.saveMaterialBatch(batch));
        Long warehouseId = jdbc.queryForObject(
                "SELECT warehouse_id FROM wms_warehouse WHERE warehouse_type='RAW' AND deleted_flag=0 ORDER BY warehouse_id LIMIT 1",
                Long.class);
        Long locationId = jdbc.queryForObject(
                "SELECT location_id FROM wms_location WHERE warehouse_id=? AND deleted_flag=0 ORDER BY location_id LIMIT 1",
                Long.class, warehouseId);
        jdbc.update("""
                INSERT INTO wms_inventory(warehouse_id, location_id, material_id, batch_id, work_order_id,
                    available_quantity, locked_quantity, inspection_quantity, version, deleted_flag)
                VALUES (?,?,?,?,0,?,0,0,0,0)
                """, warehouseId, locationId, materialId, batch.getBatchId(), quantity);
    }

    private long openRisks(Long ruleId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_inventory_risk WHERE rule_id=? AND status='OPEN' AND deleted_flag=0",
                Long.class, ruleId);
        return count == null ? 0 : count;
    }

    private int runs(Long ruleId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_automation_run WHERE rule_id=? AND deleted_flag=0",
                Integer.class, ruleId);
        return count == null ? 0 : count;
    }
}
