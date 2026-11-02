package com.neuedu.ems.service.mfg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.support.ExecutionSnapshotTestData;
import com.neuedu.ems.util.JwtUtil;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared rollback-only fixtures for progress and quality regressions. */
abstract class ProductionOrderProgressTestFixture {
    @Autowired JdbcTemplate jdbc;
    @Autowired com.neuedu.ems.service.qms.QmsInspectionOrderService quality;
    @Autowired ProductionOrderProgressService service;
    @Autowired MfgWorkOrderMapper orders;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired SqlSessionTemplate session;

    Long product;
    Long componentProduct;
    Long outputMaterial;
    Long inputMaterial;
    Long bomId;
    Long routeId;
    Long lineId;
    Long customerId;
    Long warehouseId;
    Long unitId;
    Long rawWarehouseId;
    Long rawLocationId;

    @BeforeEach
    void masterData() {
        product = jdbc.queryForObject("SELECT product_id FROM mdm_product WHERE product_code='LAMP-L200' AND deleted_flag=0", Long.class);
        componentProduct = jdbc.queryForObject("SELECT product_id FROM mdm_product WHERE product_code='LAMP-HEAD' AND deleted_flag=0", Long.class);
        outputMaterial = jdbc.queryForObject("SELECT material_id FROM mdm_product WHERE product_id=?", Long.class, product);
        inputMaterial = jdbc.queryForObject("SELECT material_id FROM mdm_product WHERE product_id=?", Long.class, componentProduct);
        bomId = jdbc.queryForObject("SELECT bom_id FROM mdm_bom WHERE deleted_flag=0 ORDER BY bom_id DESC LIMIT 1", Long.class);
        routeId = jdbc.queryForObject("SELECT route_id FROM process_route WHERE deleted_flag=0 ORDER BY route_id DESC LIMIT 1", Long.class);
        lineId = jdbc.queryForObject("SELECT line_id FROM factory_line WHERE deleted_flag=0 ORDER BY line_id DESC LIMIT 1", Long.class);
        warehouseId = jdbc.queryForObject("SELECT warehouse_id FROM wms_warehouse WHERE deleted_flag=0 ORDER BY warehouse_id LIMIT 1", Long.class);
        unitId = jdbc.queryForObject("SELECT unit_id FROM mdm_unit WHERE unit_code='LAMP-SET' AND deleted_flag=0", Long.class);
    }

    protected Map<String, Long> counts() {
        return Map.of(
                "statusLog", jdbc.queryForObject("SELECT COUNT(*) FROM sys_status_change_log", Long.class),
                "workOrder", jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order", Long.class),
                "plan", jdbc.queryForObject("SELECT COUNT(*) FROM mfg_production_plan", Long.class),
                "allocation", jdbc.queryForObject("SELECT COUNT(*) FROM mfg_supply_allocation", Long.class),
                "inventory", jdbc.queryForObject("SELECT COUNT(*) FROM wms_inventory", Long.class));
    }

    // ------------------------------------------------------------------ 夹具

    protected List<ProductionOrderProgressVO.Stage> flat(List<ProductionOrderProgressVO.Stage> rows) {
        var result = new java.util.ArrayList<ProductionOrderProgressVO.Stage>();
        for (var row: rows) { result.add(row); result.addAll(flat(row.children())); }
        return result;
    }

    protected AuthorizationContext admin() {
        return new AuthorizationContext(1L, null, null, "ALL", Set.of("SUPER_ADMIN"), Set.of(), Set.of());
    }

    protected ProductionOrderProgressVO.Stage stage(ProductionOrderProgressVO progress, String code) {
        return flat(progress.stages()).stream().filter(row -> code.equals(row.code())).findFirst().orElseThrow();
    }

    protected Long insertOrder(String status, BigDecimal quantity) {
        String tag = tag();
        jdbc.update("INSERT INTO mdm_customer(customer_code,customer_name,status,version,deleted_flag) "
                + "VALUES (?,?,'ENABLE',0,0)", tag, "全过程测试客户" + tag);
        customerId = jdbc.queryForObject("SELECT customer_id FROM mdm_customer WHERE customer_code=?", Long.class, tag);
        jdbc.update("INSERT INTO mfg_production_order(production_order_no,customer_id,product_id,order_quantity,"
                        + "delivery_date,source_type,status,remark,version,created_time,deleted_flag) "
                        + "VALUES (?,?,?,?,?,'MANUAL',?,?,0,?,0)",
                tag, customerId, product, quantity, Date.valueOf(LocalDate.of(2026, 11, 30)), status,
                "progress-test", Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 8, 0)));
        return jdbc.queryForObject("SELECT production_order_id FROM mfg_production_order WHERE production_order_no=?",
                Long.class, tag);
    }

    protected Long insertPlan(Long orderId, BigDecimal quantity, String status, String type, Long parentPlanId) {
        String tag = tag();
        jdbc.update("INSERT INTO mfg_production_plan(plan_no,production_order_id,line_id,planned_start_time,"
                        + "planned_end_time,planned_quantity,status,plan_type,parent_plan_id,version,deleted_flag) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0,0)",
                tag, orderId, lineId, Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 8, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 12, 0)), quantity, status, type, parentPlanId);
        return jdbc.queryForObject("SELECT plan_id FROM mfg_production_plan WHERE plan_no=?", Long.class, tag);
    }

    protected Long insertWorkOrder(String suffix, Long planId, Long productId, BigDecimal quantity, String status,
            Long parentWorkOrderId) {
        MfgWorkOrder order = new MfgWorkOrder();
        order.setWorkOrderNo(tag() + "-" + suffix);
        order.setPlanId(planId);
        order.setProductId(productId);
        order.setBomId(bomId);
        order.setRouteId(routeId);
        order.setPlannedQuantity(quantity);
        order.setCompletedQuantity("FINISHED".equals(status) ? quantity : BigDecimal.ZERO);
        order.setStatus(status);
        order.setWorkOrderType(parentWorkOrderId == null ? "ROOT" : "COMPONENT");
        order.setParentWorkOrderId(parentWorkOrderId);
        order.setRootWorkOrderId(parentWorkOrderId);
        order.setVersion(0);
        order.setDeletedFlag(0);
        orders.insert(order);
        return order.getWorkOrderId();
    }

    protected ObjectNode snapshotBacking(Long workOrderId) {
        MfgWorkOrder order = orders.selectById(workOrderId);
        return ExecutionSnapshotTestData.snapshot(json, order, inputMaterial, outputMaterial);
    }

    /** 在标准单工序快照上追加一道批次设备工序，用于验证老化批数与时长口径。 */
    protected ObjectNode agingSnapshot(Long workOrderId) {
        MfgWorkOrder order = orders.selectById(workOrderId);
        ObjectNode root = ExecutionSnapshotTestData.snapshot(json, order, inputMaterial, outputMaterial);
        ArrayNode steps = (ArrayNode) root.path("routeItems");
        ObjectNode first = (ObjectNode) steps.get(0);
        first.put("qcRequired", 0);
        first.put("outputNode", false);
        ObjectNode aging = steps.addObject();
        aging.put("routeItemId", 2);
        aging.put("operationId", 2);
        aging.put("operationCode", "TEST-AGING");
        aging.put("operationName", "测试老化");
        aging.put("sequenceNo", 2);
        aging.put("version", 0);
        aging.put("operationVersion", 0);
        aging.put("standardTime", 0);
        aging.put("resourceType", "BATCH_EQUIPMENT");
        aging.put("batchCapacity", 50);
        aging.put("batchDurationMinutes", 60);
        aging.put("timeSourceReference", "TEST-ONLY");
        aging.put("qcRequired", 1);
        aging.put("outputNode", true);
        ObjectNode scheme = (ObjectNode) root.path("inspectionSchemes").get(0);
        scheme.put("operationId", 2);
        ((ObjectNode) scheme.path("standards").get(0)).put("standardOperationId", 2);
        return root;
    }

    protected void attachSnapshot(Long workOrderId, ObjectNode snapshot) {
        jdbc.update("UPDATE mfg_work_order SET snapshot_json=?, snapshot_hash=?, snapshot_time=? WHERE work_order_id=?",
                snapshot.toString(), com.neuedu.ems.service.mfg.ExecutionSnapshotPolicy.hash(snapshot.toString()),
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 7, 0)), workOrderId);
        Long materialId = snapshot.path("outputMaterial").path("materialId").longValue();
        if (materialId <= 0) throw new IllegalStateException("夹具缺少产出物料");
    }

    protected Long material(String suffix) {
        String tag = tag() + "-" + suffix;
        jdbc.update("INSERT INTO mdm_material(material_code,material_name,unit_id,material_type,"
                        + "quantity_mode,issue_step,status,version,deleted_flag) VALUES (?,?,?,'SEMI_FINISHED',"
                        + "'DISCRETE',1,'ENABLE',0,0)",
                tag, "全过程测试物料" + suffix, unitId);
        return jdbc.queryForObject("SELECT material_id FROM mdm_material WHERE material_code=?", Long.class, tag);
    }

    protected Long insertRequirement(Long workOrderId, Long materialId, BigDecimal required, BigDecimal issued,
            BigDecimal shortage, String status, String supplyType, Long sourceWorkOrderId, BigDecimal reserved) {
        jdbc.update("INSERT INTO mfg_work_order_material(work_order_id,material_id,required_quantity,issued_quantity,"
                        + "reserved_quantity,shortage_quantity,status,supply_type,source_work_order_id,version,deleted_flag) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0,0)",
                workOrderId, materialId, required, issued, reserved, shortage, status, supplyType, sourceWorkOrderId);
        return jdbc.queryForObject("SELECT requirement_id FROM mfg_work_order_material WHERE work_order_id=? "
                + "AND material_id=? ORDER BY requirement_id DESC LIMIT 1", Long.class, workOrderId, materialId);
    }

    protected void insertSupplySource(Long requirementId, Long workOrderId, String supplyType, BigDecimal quantity) {
        jdbc.update("INSERT INTO mfg_supply_source(source_no,requirement_id,work_order_id,material_id,supply_type,"
                        + "planned_quantity,sequence_no,status,version,deleted_flag) "
                        + "VALUES (?,?,?,?,?,?,1,'PLANNED',0,0)",
                tag(), requirementId, workOrderId, inputMaterial, supplyType, quantity);
    }

    protected Long insertTask(String suffix, Long workOrderId, Long operationId, BigDecimal quantity, String status) {
        String tag = tag() + "-" + suffix;
        jdbc.update("INSERT INTO mfg_task(task_no,work_order_id,operation_id,planned_quantity,completed_quantity,"
                        + "defect_quantity,task_type,status,version,created_time,deleted_flag) "
                        + "VALUES (?,?,?,?,?,0,'NORMAL',?,0,?,0)",
                tag, workOrderId, operationId, quantity, quantity, status,
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 8, 30)));
        return jdbc.queryForObject("SELECT task_id FROM mfg_task WHERE task_no=?", Long.class, tag);
    }

    protected Long insertReport(String suffix, Long taskId, BigDecimal quantity, String audit, String time) {
        String tag = tag() + "-" + suffix;
        jdbc.update("INSERT INTO mfg_work_report(report_no,task_id,qualified_quantity,defect_quantity,"
                        + "actual_work_hours,report_time,audit_status,version,deleted_flag) "
                        + "VALUES (?,?,?,0,1,?,?,0,0)",
                tag, taskId, quantity, Timestamp.valueOf(LocalDateTime.parse(time.replace(' ', 'T'))), audit);
        return jdbc.queryForObject("SELECT report_id FROM mfg_work_report WHERE report_no=?", Long.class, tag);
    }

    protected Long insertLot(String suffix, Long productId, Long workOrderId, Long reportId, BigDecimal quantity) {
        String tag = tag() + "-" + suffix;
        jdbc.update("INSERT INTO trace_product_lot(lot_no,product_id,work_order_id,source_report_id,"
                        + "batch_quantity,qualified_quantity,defect_quantity,status,version,created_time,deleted_flag) "
                        + "VALUES (?,?,?,?,?,?,0,'STORED',0,?,0)",
                tag, productId, workOrderId, reportId, quantity, quantity,
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 9, 30)));
        return jdbc.queryForObject("SELECT product_lot_id FROM trace_product_lot WHERE lot_no=?", Long.class, tag);
    }

    protected Long insertInbound(String sourceType, Long sourceOrderId, String audit, String time) {
        String tag = tag() + "-IN";
        jdbc.update("INSERT INTO wms_inbound_order(inbound_no,inbound_type,warehouse_id,source_order_type,"
                        + "source_order_id,audit_status,inbound_time,version,deleted_flag) "
                        + "VALUES (?,'PRODUCTION',?,'PRODUCT_LOT',?,?,?,0,0)",
                tag, warehouseId, sourceOrderId, audit,
                Timestamp.valueOf(LocalDateTime.parse(time.replace(' ', 'T'))));
        return jdbc.queryForObject("SELECT inbound_id FROM wms_inbound_order WHERE inbound_no=?", Long.class, tag);
    }

    protected void insertInboundDetail(Long inboundId, Long materialId, BigDecimal quantity) {
        jdbc.update("INSERT INTO wms_inbound_detail(inbound_id,material_id,inbound_quantity,version,deleted_flag) "
                + "VALUES (?,?,?,0,0)", inboundId, materialId, quantity);
    }

    protected Long insertOutbound(Long workOrderId, Long orderId, String audit, String time) {
        String tag = tag() + "-OUT";
        jdbc.update("INSERT INTO wms_outbound_order(outbound_no,outbound_type,warehouse_id,work_order_id,"
                        + "customer_id,source_production_order_id,audit_status,outbound_time,version,deleted_flag) "
                        + "VALUES (?,'SALE',?,?,?,?,?,?,0,0)",
                tag, warehouseId, workOrderId, customerId, orderId, audit,
                Timestamp.valueOf(LocalDateTime.parse(time.replace(' ', 'T'))));
        return jdbc.queryForObject("SELECT outbound_id FROM wms_outbound_order WHERE outbound_no=?", Long.class, tag);
    }

    protected void insertOutboundDetail(Long outboundId, Long materialId, BigDecimal quantity) {
        jdbc.update("INSERT INTO wms_outbound_detail(outbound_id,material_id,outbound_quantity,version,deleted_flag) "
                + "VALUES (?,?,?,0,0)", outboundId, materialId, quantity);
    }

    protected Long insertInspection(String suffix, String type, Long workOrderId, Long productLotId,
            BigDecimal quantity, String result, String status, String time) {
        String tag = tag() + "-QI-" + suffix;
        jdbc.update("INSERT INTO qms_inspection_order(inspection_no,inspection_type,work_order_id,product_lot_id,"
                        + "sample_quantity,qualified_quantity,unqualified_quantity,inspection_time,"
                        + "inspection_result,status,version,deleted_flag) "
                        + "VALUES (?,?,?,?,?,?,0,?,?,?,0,0)",
                tag, type, workOrderId, productLotId, quantity, quantity,
                Timestamp.valueOf(LocalDateTime.parse(time.replace(' ', 'T'))), result, status);
        return jdbc.queryForObject("SELECT inspection_id FROM qms_inspection_order WHERE inspection_no=?",
                Long.class, tag);
    }

    protected void insertLog(String bizType, Long bizId, String from, String to, String time) {
        jdbc.update("INSERT INTO sys_status_change_log(biz_type,biz_id,old_status,new_status,change_reason,"
                        + "changed_time,version,deleted_flag) VALUES (?,?,?,?,'progress-test',?,0,0)",
                bizType, bizId, from, to,
                Timestamp.valueOf(LocalDateTime.parse(time.replace(' ', 'T'))));
    }

    // ------------------------------------------------------------------ 库存与设备夹具

    protected ProductionOrderProgressVO.MaterialCoverage coverageOf(ProductionOrderProgressVO progress,
            Long requirementId) {
        return progress.materials().stream()
                .filter(item -> item.requirementId().equals(requirementId))
                .findFirst().orElseThrow();
    }

    protected List<ProductionOrderProgressVO.Blocker> allBlockers(ProductionOrderProgressVO progress) {
        List<ProductionOrderProgressVO.Blocker> rows = new java.util.ArrayList<>();
        for (ProductionOrderProgressVO.Stage item : progress.stages()) {
            rows.addAll(item.blockers());
            for (ProductionOrderProgressVO.Stage child : item.children()) rows.addAll(child.blockers());
        }
        return rows;
    }

    /** 一条公共合格库存：仓库类型与库位状态必须满足线边移库资格口径。 */
    protected void insertPublicStock(Long materialId, BigDecimal available) {
        insertStock(materialId, 0L, available, BigDecimal.ZERO);
    }

    /**
     * 造一条库存余额。{@code workOrderId > 0} 表示线边占用，对别的订单不是公共可用量；
     * {@code inspectionQuantity > 0} 表示在检，不能抵缺口。
     */
    protected void insertStock(Long materialId, Long workOrderId, BigDecimal available, BigDecimal inspection) {
        Long locationId = publicLocation();
        Long batchId = batch(materialId);
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,location_id,material_id,batch_id,"
                        + "available_quantity,locked_quantity,inspection_quantity,work_order_id,version,deleted_flag) "
                        + "VALUES (?,?,?,?,?,0,?,?,0,0)",
                rawWarehouseId, locationId, materialId, batchId, available, inspection, workOrderId);
    }

    /** 取一个 RAW 仓库下 NORMAL 且 IDLE 的库位；没有就新造一个。 */
    protected Long publicLocation() {
        if (rawLocationId != null) return rawLocationId;
        rawWarehouseId = jdbc.queryForObject("SELECT warehouse_id FROM wms_warehouse "
                + "WHERE warehouse_type='RAW' AND status='ENABLE' AND deleted_flag=0 ORDER BY warehouse_id LIMIT 1",
                Long.class);
        List<Long> existing = jdbc.queryForList("SELECT location_id FROM wms_location WHERE warehouse_id=? "
                + "AND location_type='NORMAL' AND status='IDLE' AND deleted_flag=0 ORDER BY location_id LIMIT 1",
                Long.class, rawWarehouseId);
        if (!existing.isEmpty()) {
            rawLocationId = existing.get(0);
            return rawLocationId;
        }
        String tag = tag();
        jdbc.update("INSERT INTO wms_location(location_code,location_name,warehouse_id,location_type,status,"
                + "version,deleted_flag) VALUES (?,?,?,'NORMAL','IDLE',0,0)",
                tag, "全过程测试库位" + tag, rawWarehouseId);
        rawLocationId = jdbc.queryForObject("SELECT location_id FROM wms_location WHERE location_code=?",
                Long.class, tag);
        return rawLocationId;
    }

    protected Long batch(Long materialId) {
        String tag = tag();
        jdbc.update("INSERT INTO wms_material_batch(batch_no,material_id,quality_status,status,version,deleted_flag) "
                + "VALUES (?,?,'PASS','AVAILABLE',0,0)", tag, materialId);
        return jdbc.queryForObject("SELECT batch_id FROM wms_material_batch WHERE batch_no=?", Long.class, tag);
    }

    protected void insertAllocation(Long requirementId, Long workOrderId, Long materialId, String status,
            BigDecimal reserved, BigDecimal transferred) {
        Long locationId = publicLocation();
        Long batchId = batch(materialId);
        Long inventoryId = inventoryRow(rawWarehouseId, locationId, materialId, batchId);
        jdbc.update("INSERT INTO mfg_supply_allocation(allocation_no,requirement_id,work_order_id,material_id,"
                        + "warehouse_id,location_id,batch_id,inventory_id,allocated_quantity,reserved_quantity,"
                        + "transferred_quantity,consumed_quantity,released_quantity,status,version,deleted_flag) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,0,0,?,0,0)",
                tag(), requirementId, workOrderId, materialId, rawWarehouseId, locationId, batchId, inventoryId,
                reserved.add(transferred), reserved, transferred, status);
    }

    protected Long inventoryRow(Long warehouseId, Long locationId, Long materialId, Long batchId) {
        String tag = tag();
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,location_id,material_id,batch_id,"
                        + "available_quantity,locked_quantity,inspection_quantity,work_order_id,version,"
                        + "deleted_flag,remark) VALUES (?,?,?,?,0,0,0,0,0,0,?)",
                warehouseId, locationId, materialId, batchId, "allocation-" + tag);
        return jdbc.queryForObject("SELECT inventory_id FROM wms_inventory WHERE remark=?", Long.class,
                "allocation-" + tag);
    }

    protected Long station(String suffix) {
        String tag = tag() + "-" + suffix;
        jdbc.update("INSERT INTO factory_station(station_code,station_name,line_id,station_type,status,"
                + "version,deleted_flag) VALUES (?,?,?,'ASSEMBLY','ENABLE',0,0)",
                tag, "全过程测试工位" + suffix, lineId);
        return jdbc.queryForObject("SELECT station_id FROM factory_station WHERE station_code=?", Long.class, tag);
    }

    protected Long insertTaskAtStation(String suffix, Long workOrderId, Long stationId, BigDecimal quantity,
            String status) {
        String tag = tag() + "-" + suffix;
        jdbc.update("INSERT INTO mfg_task(task_no,work_order_id,operation_id,station_id,planned_quantity,"
                        + "completed_quantity,defect_quantity,task_type,status,version,created_time,deleted_flag) "
                        + "VALUES (?,?,1,?,?,0,0,'NORMAL',?,0,?,0)",
                tag, workOrderId, stationId, quantity, status,
                Timestamp.valueOf(LocalDateTime.of(2026, 10, 25, 8, 30)));
        return jdbc.queryForObject("SELECT task_id FROM mfg_task WHERE task_no=?", Long.class, tag);
    }

    protected Long equipment(String code, Long stationId, String status) {
        String tag = code + "-" + tag();
        jdbc.update("INSERT INTO eam_equipment(equipment_code,equipment_name,station_id,status,version,deleted_flag) "
                + "VALUES (?,?,?,?,0,0)", tag, "全过程测试设备" + code, stationId, status);
        return jdbc.queryForObject("SELECT equipment_id FROM eam_equipment WHERE equipment_code=?", Long.class, tag);
    }

    protected void insertRepair(Long equipmentId, String status, String time) {
        jdbc.update("INSERT INTO eam_repair_order(repair_no,equipment_id,reported_time,fault_description,"
                        + "status,version,deleted_flag) VALUES (?,?,?,?,?,0,0)",
                tag(), equipmentId,
                Timestamp.valueOf(LocalDateTime.parse(time.replace(' ', 'T'))),
                "全过程测试设备故障", status);
    }

    protected static long sequence;

    protected static String tag() {
        return "POP" + System.nanoTime() + "-" + (++sequence);
    }

}
