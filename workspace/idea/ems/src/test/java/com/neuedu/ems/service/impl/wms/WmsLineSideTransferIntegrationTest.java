package com.neuedu.ems.service.impl.wms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.entity.mfg.*;
import com.neuedu.ems.entity.wms.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.mapper.mfg.*;
import com.neuedu.ems.mapper.wms.*;
import com.neuedu.ems.service.wms.WmsOutboundOrderService;
import com.neuedu.ems.service.wms.WmsOutboundDetailService;
import com.neuedu.ems.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 仅在本机隔离验证库造夹具；直接验证服务事务和真实 MySQL 余额，不声称生产闭环已开放。 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_[A-Za-z0-9_]+.*")
@AutoConfigureMockMvc
class WmsLineSideTransferIntegrationTest {
    @Autowired WmsOutboundOrderService service;
    @Autowired WmsOutboundDetailService detailService;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired WmsWarehouseMapper warehouses;
    @Autowired WmsLocationMapper locations;
    @Autowired WmsMaterialBatchMapper batches;
    @Autowired WmsInventoryMapper inventories;
    @Autowired WmsOutboundOrderMapper orders;
    @Autowired WmsOutboundDetailMapper details;
    @Autowired MfgWorkOrderMapper workOrders;
    @Autowired MfgWorkOrderMaterialMapper requirements;
    @Autowired MdmMaterialMapper materials;
    @Autowired MdmProductMapper products;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    WmsWarehouse raw, line;
    WmsLocation rawLocation, lineLocation;
    MdmMaterial material;
    WmsMaterialBatch batch;
    WmsInventory source;
    MfgWorkOrder workOrder;
    WmsOutboundOrder outbound;
    String tag;

    @BeforeEach
    void fixture() throws Exception {
        tag = "LS" + System.nanoTime();
        raw = warehouse("RAW", tag + "R"); line = warehouse("LINE_SIDE", tag + "L");
        rawLocation = location(raw, tag + "RL"); lineLocation = location(line, tag + "LL");
        material = new MdmMaterial(); material.setMaterialCode(tag); material.setMaterialName("线边验证用离散物料");
        material.setMaterialType("RAW"); material.setQuantityMode("DISCRETE"); material.setIssueStep(BigDecimal.ONE);
        material.setStatus("ENABLE"); material.setDeletedFlag(0); materials.insert(material);
        batch = new WmsMaterialBatch(); batch.setBatchNo(tag); batch.setMaterialId(material.getMaterialId());
        batch.setQualityStatus("PASS"); batch.setStatus("AVAILABLE"); batch.setDeletedFlag(0); batches.insert(batch);
        source = new WmsInventory(); source.setWarehouseId(raw.getWarehouseId()); source.setLocationId(rawLocation.getLocationId());
        source.setMaterialId(material.getMaterialId()); source.setBatchId(batch.getBatchId()); source.setWorkOrderId(0L);
        source.setAvailableQuantity(new BigDecimal("20")); source.setLockedQuantity(BigDecimal.ZERO);
        source.setInspectionQuantity(BigDecimal.ZERO); source.setDeletedFlag(0); inventories.insert(source);
        MdmMaterial output = new MdmMaterial(); output.setMaterialCode(tag + "OUT"); output.setMaterialName("组件验证产出");
        output.setMaterialType("SEMI_FINISHED"); output.setQuantityMode("DISCRETE"); output.setIssueStep(BigDecimal.ONE);
        output.setStatus("ENABLE"); output.setDeletedFlag(0); materials.insert(output);
        MdmProduct product = new MdmProduct(); product.setProductCode(tag + "CMP"); product.setProductName("隔离验证组件");
        product.setMaterialId(output.getMaterialId()); product.setPreparationControlled(1); product.setStatus("ENABLE"); product.setDeletedFlag(0); products.insert(product);
        workOrder = new MfgWorkOrder(); workOrder.setWorkOrderNo(tag); workOrder.setProductId(product.getProductId());
        workOrder.setStatus("RELEASED"); workOrder.setWorkOrderType("COMPONENT"); workOrder.setPlannedQuantity(BigDecimal.TEN);
        workOrder.setBomId(10001L); workOrder.setRouteId(10002L);
        Long lineId = jdbc.queryForObject("SELECT line_id FROM factory_line WHERE deleted_flag=0 ORDER BY line_id LIMIT 1",Long.class);
        jdbc.update("INSERT INTO mfg_production_plan(plan_no,line_id,status,deleted_flag) VALUES (?,?,'RELEASED',0)",tag+"PLAN",lineId);
        workOrder.setPlanId(jdbc.queryForObject("SELECT plan_id FROM mfg_production_plan WHERE plan_no=?",Long.class,tag+"PLAN"));
        lineLocation.setLineId(lineId); locations.updateById(lineLocation);
        workOrder.setCompletedQuantity(BigDecimal.ZERO); workOrder.setDeletedFlag(0); workOrders.insert(workOrder);
        com.neuedu.ems.support.ExecutionSnapshotTestData.attach(workOrder,
                com.neuedu.ems.support.ExecutionSnapshotTestData.snapshot(json,workOrder,material.getMaterialId(),output.getMaterialId()));
        workOrders.updateById(workOrder);
        MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial(); requirement.setWorkOrderId(workOrder.getWorkOrderId());
        requirement.setMaterialId(material.getMaterialId()); requirement.setRequiredQuantity(BigDecimal.TEN);
        requirement.setIssuedQuantity(BigDecimal.ZERO); requirement.setShortageQuantity(BigDecimal.TEN);
        requirement.setStatus("WAIT_READY"); requirement.setDeletedFlag(0); requirements.insert(requirement);
        outbound = issue("8");
    }

    @Test void transferConservesStockAndReplayDoesNotDuplicate() {
        transfer(outbound);
        assertEquals(0, new BigDecimal("12").compareTo(inventories.selectById(source.getInventoryId()).getAvailableQuantity()));
        assertEquals(0, new BigDecimal("20").compareTo(totalPhysicalStock()));
        assertEquals(0, new BigDecimal("8").compareTo(lineLocked()));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT available_quantity FROM wms_inventory WHERE work_order_id=? AND warehouse_id=?", BigDecimal.class, workOrder.getWorkOrderId(), line.getWarehouseId())));
        assertEquals(2, movementCount(outbound));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT pair_no) FROM wms_stock_movement WHERE source_order_id=? AND source_order_type='OUTBOUND_ORDER'", Integer.class, outbound.getOutboundId()));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT SUM(IF(direction='IN',quantity,-quantity)) FROM wms_stock_movement WHERE source_order_id=? AND source_order_type='OUTBOUND_ORDER'", BigDecimal.class, outbound.getOutboundId())));
        transfer(outbound);
        assertEquals(2, movementCount(outbound)); assertEquals(0, new BigDecimal("8").compareTo(lineLocked()));
        assertThrows(BusinessException.class, () -> service.transferMaterialIssueToLineSide(outbound.getOutboundId(), rawLocation.getLocationId(), 1L, null));
    }

    @Test void oldSnapshotWithMatchingHashCannotStartNewTransfer() throws Exception {
        var old=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(workOrder.getSnapshotJson());
        old.put("formatVersion","LAMP-SNAPSHOT-1");
        com.neuedu.ems.support.ExecutionSnapshotTestData.attach(workOrder,old);workOrders.updateById(workOrder);
        assertThrows(BusinessException.class,()->transfer(outbound));
        assertEquals(0,new BigDecimal("20").compareTo(inventories.selectById(source.getInventoryId()).getAvailableQuantity()));
        assertEquals(0,movementCount(outbound));
    }

    @Test void secondIssueMergesLockedBalanceAndCannotOverIssue() {
        transfer(outbound); transfer(issue("2"));
        assertEquals(0, BigDecimal.TEN.compareTo(lineLocked()));
        WmsOutboundOrder excess = issue("1");
        assertThrows(BusinessException.class, () -> transfer(excess));
        assertEquals(0, BigDecimal.TEN.compareTo(lineLocked())); assertEquals(0, movementCount(excess));
    }

    @Test void rejectsUnreleasedBatchAndFractionalPiece() {
        jdbc.update("UPDATE wms_material_batch SET quality_status='WAIT_INSPECTION' WHERE batch_id=?", batch.getBatchId());
        assertThrows(BusinessException.class, () -> transfer(outbound));
        assertEquals(0, movementCount(outbound)); assertEquals(0, new BigDecimal("20").compareTo(totalPhysicalStock()));
        jdbc.update("UPDATE wms_material_batch SET quality_status='PASS' WHERE batch_id=?", batch.getBatchId());
        jdbc.update("UPDATE wms_outbound_detail SET outbound_quantity=0.04 WHERE outbound_id=?", outbound.getOutboundId());
        assertThrows(BusinessException.class, () -> transfer(outbound)); assertEquals(0, movementCount(outbound));
    }

    @Test void rejectsWrongLocationAndCorruptSnapshot() {
        assertThrows(BusinessException.class, () -> service.transferMaterialIssueToLineSide(outbound.getOutboundId(), rawLocation.getLocationId(), 1L, null));
        jdbc.update("UPDATE wms_location SET location_type='FROZEN' WHERE location_id=?", lineLocation.getLocationId());
        assertThrows(BusinessException.class, () -> transfer(outbound));
        jdbc.update("UPDATE wms_location SET location_type='NORMAL' WHERE location_id=?", lineLocation.getLocationId());
        jdbc.update("UPDATE mfg_work_order SET snapshot_hash='broken' WHERE work_order_id=?", workOrder.getWorkOrderId());
        assertThrows(BusinessException.class, () -> transfer(outbound)); assertEquals(0, movementCount(outbound));
    }

    @Test void insufficientStockDoesNotChangeAnyBalance() {
        jdbc.update("UPDATE wms_inventory SET available_quantity=7 WHERE inventory_id=?", source.getInventoryId());
        assertThrows(BusinessException.class, () -> transfer(outbound));
        assertEquals(0, new BigDecimal("7").compareTo(totalPhysicalStock())); assertEquals(0, movementCount(outbound));
        assertEquals("SUBMITTED", orders.selectById(outbound.getOutboundId()).getAuditStatus());
    }

    @Test void databaseFailureAfterDebitRollsBackWholeTransfer() {
        // 数量溢出发生在线边加账时，此前的来源扣账必须由事务回滚。
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,location_id,material_id,batch_id,work_order_id,available_quantity,locked_quantity,inspection_quantity,deleted_flag,version) VALUES(?,?,?,?,?,0,99999999999999.9999,0,0,0)", line.getWarehouseId(), lineLocation.getLocationId(), material.getMaterialId(), batch.getBatchId(), workOrder.getWorkOrderId());
        assertThrows(RuntimeException.class, () -> transfer(outbound));
        assertEquals(0, new BigDecimal("20").compareTo(inventories.selectById(source.getInventoryId()).getAvailableQuantity()));
        assertEquals("SUBMITTED", orders.selectById(outbound.getOutboundId()).getAuditStatus()); assertEquals(0, movementCount(outbound));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT issued_quantity FROM mfg_work_order_material WHERE work_order_id=?", BigDecimal.class, workOrder.getWorkOrderId())));
    }

    @Test void concurrentSameCommandIsAppliedOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Callable<Void> call = () -> { ready.countDown(); start.await(); transfer(outbound); return null; };
            Future<Void> first = pool.submit(call), second = pool.submit(call);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertNull(first.get(15, TimeUnit.SECONDS)); assertNull(second.get(15, TimeUnit.SECONDS));
            assertEquals(2, movementCount(outbound)); assertEquals(0, new BigDecimal("8").compareTo(lineLocked()));
            assertEquals(0, new BigDecimal("20").compareTo(totalPhysicalStock()));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void transferConsumesReservationWithoutDeductingAvailableTwice() {
        // 生成工单时已把 8 件从可用量转入锁定量，并留下预留记录。
        jdbc.update("UPDATE wms_inventory SET available_quantity=12,locked_quantity=8 WHERE inventory_id=?", source.getInventoryId());
        Long requirementId = jdbc.queryForObject("SELECT requirement_id FROM mfg_work_order_material WHERE work_order_id=?", Long.class, workOrder.getWorkOrderId());
        jdbc.update("INSERT INTO mfg_supply_allocation(allocation_no,requirement_id,work_order_id,material_id,warehouse_id,location_id,batch_id,inventory_id,"
                + "allocated_quantity,reserved_quantity,transferred_quantity,consumed_quantity,released_quantity,status,request_key,version,deleted_flag) "
                + "VALUES (?,?,?,?,?,?,?,?,8,8,0,0,0,'RESERVED',?,0,0)",
                "SA-" + tag, requirementId, workOrder.getWorkOrderId(), material.getMaterialId(),
                raw.getWarehouseId(), rawLocation.getLocationId(), batch.getBatchId(), source.getInventoryId(), "TEST-" + tag);

        transfer(outbound);

        // 预留只把锁定量转成线边占用，不再二次扣减可用量。
        assertEquals(0, new BigDecimal("12").compareTo(inventories.selectById(source.getInventoryId()).getAvailableQuantity()));
        assertEquals(0, BigDecimal.ZERO.compareTo(inventories.selectById(source.getInventoryId()).getLockedQuantity()));
        assertEquals(0, new BigDecimal("20").compareTo(totalPhysicalStock()));
        assertEquals(0, new BigDecimal("8").compareTo(lineLocked()));
        assertEquals("TRANSFERRED", jdbc.queryForObject("SELECT status FROM mfg_supply_allocation WHERE allocation_no=?", String.class, "SA-" + tag));
        assertEquals(0, new BigDecimal("8").compareTo(jdbc.queryForObject("SELECT transferred_quantity FROM mfg_supply_allocation WHERE allocation_no=?", BigDecimal.class, "SA-" + tag)));
        assertEquals(2, movementCount(outbound));
    }

    @Test void unreservedStockStillDebitsAvailableQuantity() {
        jdbc.update("UPDATE wms_inventory SET available_quantity=12,locked_quantity=8 WHERE inventory_id=?", source.getInventoryId());
        // 没有预留记录时，移库必须走公共可用量扣减，不能凭空消耗锁定量。
        transfer(outbound);
        assertEquals(0, new BigDecimal("4").compareTo(inventories.selectById(source.getInventoryId()).getAvailableQuantity()));
        assertEquals(0, new BigDecimal("8").compareTo(inventories.selectById(source.getInventoryId()).getLockedQuantity()));
        assertEquals(0, new BigDecimal("20").compareTo(totalPhysicalStock()));
    }

    @Test void transferRejectsWhenReservedQuantityExceedsLockedBalance() {
        jdbc.update("UPDATE wms_inventory SET available_quantity=12,locked_quantity=3 WHERE inventory_id=?", source.getInventoryId());
        Long requirementId = jdbc.queryForObject("SELECT requirement_id FROM mfg_work_order_material WHERE work_order_id=?", Long.class, workOrder.getWorkOrderId());
        jdbc.update("INSERT INTO mfg_supply_allocation(allocation_no,requirement_id,work_order_id,material_id,warehouse_id,location_id,batch_id,inventory_id,"
                + "allocated_quantity,reserved_quantity,transferred_quantity,consumed_quantity,released_quantity,status,request_key,version,deleted_flag) "
                + "VALUES (?,?,?,?,?,?,?,?,8,8,0,0,0,'RESERVED',?,0,0)",
                "SA-" + tag, requirementId, workOrder.getWorkOrderId(), material.getMaterialId(),
                raw.getWarehouseId(), rawLocation.getLocationId(), batch.getBatchId(), source.getInventoryId(), "TEST-" + tag);

        assertThrows(BusinessException.class, () -> transfer(outbound));

        assertEquals(0, movementCount(outbound));
        assertEquals("SUBMITTED", orders.selectById(outbound.getOutboundId()).getAuditStatus());
        assertEquals(0, new BigDecimal("12").compareTo(inventories.selectById(source.getInventoryId()).getAvailableQuantity()));
        assertEquals(0, new BigDecimal("3").compareTo(inventories.selectById(source.getInventoryId()).getLockedQuantity()));
    }

    @Test void httpCommandAuthenticatesAndPersistsPairedMovements() throws Exception {
        String path = "/api/wms/outbound-order/" + outbound.getOutboundId() + "/transfer-to-line-side";
        String body = json.writeValueAsString(Map.of("targetLocationId", lineLocation.getLocationId()));
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertEquals(0, movementCount(outbound));
        Long adminId = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
        String token = jwt.generateToken(adminId, "admin");
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        assertEquals(2, movementCount(outbound));
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        assertEquals(2, movementCount(outbound));
    }

    @Test void confirmedTransferDetailsCannotBeChangedOrDeleted() {
        transfer(outbound);
        var detail = detailService.listOutboundDetailsByOutboundId(outbound.getOutboundId()).getFirst();
        detail.setOutboundQuantity(BigDecimal.ONE);
        assertFalse(detailService.updateOutboundDetail(detail));
        assertFalse(detailService.removeOutboundDetailById(detail.getDetailId()));
        var extra = new WmsOutboundDetail(); extra.setOutboundId(outbound.getOutboundId());
        extra.setMaterialId(material.getMaterialId()); extra.setBatchId(batch.getBatchId());
        extra.setLocationId(rawLocation.getLocationId()); extra.setOutboundQuantity(BigDecimal.ONE);
        assertFalse(detailService.saveOutboundDetail(extra));
        assertEquals(2, movementCount(outbound)); assertEquals(0, new BigDecimal("8").compareTo(lineLocked()));
    }

    @Test void locationFrozenWhileWaitingForWorkOrderLockIsRejected() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<Void>>();
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT work_order_id FROM mfg_work_order WHERE work_order_id=? FOR UPDATE", Long.class, workOrder.getWorkOrderId());
                pending.set(pool.submit(() -> { transfer(outbound); return null; }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='mfg_work_order'", Integer.class) > 0;
                    if (waiting) break;
                    try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                assertTrue(waiting, "必须确认移库请求已经读取旧状态并在工单锁上等待");
                jdbc.update("UPDATE wms_location SET location_type='FROZEN' WHERE location_id=?", lineLocation.getLocationId());
            });
            var failure = assertThrows(ExecutionException.class, () -> pending.get().get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, failure.getCause());
            assertEquals(0, movementCount(outbound)); assertEquals(0, new BigDecimal("20").compareTo(totalPhysicalStock()));
        } finally { pool.shutdownNow(); }
    }

    @Test void transferCommandRequestKeyReplaysWithoutDuplicatingMovements() {
        String key = "LSKEY-" + tag;
        service.transferMaterialIssueToLineSide(outbound.getOutboundId(),
                lineLocation.getLocationId(), 1L, "幂等移库", key);
        assertEquals(2, movementCount(outbound));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mfg_command_request WHERE request_key=? AND deleted_flag=0",
                Integer.class, key));
        // 同键同参数重放：必须返回成功但不重复移库。
        service.transferMaterialIssueToLineSide(outbound.getOutboundId(),
                lineLocation.getLocationId(), 1L, "幂等移库", key);
        assertEquals(2, movementCount(outbound));
        assertEquals(0, new BigDecimal("8").compareTo(lineLocked()));
        // 同键改目标库位：必须拒绝，不能静默复用原结果。
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.transferMaterialIssueToLineSide(outbound.getOutboundId(),
                        rawLocation.getLocationId(), 1L, "幂等移库", key));
        assertTrue(error.getMessage().contains("不能复用原结果"));
    }

    @Test void transferWithoutRequestKeyKeepsLegacyReplaySemantics() {
        service.transferMaterialIssueToLineSide(outbound.getOutboundId(),
                lineLocation.getLocationId(), 1L, "无请求键");
        assertEquals(2, movementCount(outbound));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mfg_command_request WHERE request_key IS NOT NULL AND aggregate_id=? AND aggregate_type='WMS_OUTBOUND_ORDER'",
                Integer.class, outbound.getOutboundId()));
        service.transferMaterialIssueToLineSide(outbound.getOutboundId(),
                lineLocation.getLocationId(), 1L, "无请求键");
        assertEquals(2, movementCount(outbound));
    }

    private void transfer(WmsOutboundOrder order) { service.transferMaterialIssueToLineSide(order.getOutboundId(), lineLocation.getLocationId(), 1L, "隔离库移库验证"); }
    private BigDecimal lineLocked() { return jdbc.queryForObject("SELECT SUM(locked_quantity) FROM wms_inventory WHERE material_id=? AND warehouse_id=?", BigDecimal.class, material.getMaterialId(), line.getWarehouseId()); }
    private BigDecimal totalPhysicalStock() { return jdbc.queryForObject("SELECT SUM(available_quantity+locked_quantity+inspection_quantity) FROM wms_inventory WHERE material_id=?", BigDecimal.class, material.getMaterialId()); }
    private int movementCount(WmsOutboundOrder order) { return jdbc.queryForObject("SELECT COUNT(*) FROM wms_stock_movement WHERE source_order_type='OUTBOUND_ORDER' AND source_order_id=?", Integer.class, order.getOutboundId()); }
    private WmsWarehouse warehouse(String type, String code) { var w = new WmsWarehouse(); w.setWarehouseCode(code); w.setWarehouseName(code); w.setWarehouseType(type); w.setStatus("ENABLE"); w.setDeletedFlag(0); warehouses.insert(w); return w; }
    private WmsLocation location(WmsWarehouse warehouse, String code) { var l = new WmsLocation(); l.setLocationCode(code); l.setLocationName(code); l.setWarehouseId(warehouse.getWarehouseId()); l.setLocationType("NORMAL"); l.setStatus("IDLE"); l.setDeletedFlag(0); locations.insert(l); return l; }
    private WmsOutboundOrder issue(String quantity) {
        var order = new WmsOutboundOrder(); order.setOutboundNo(tag + UUID.randomUUID().toString().substring(0, 8));
        order.setOutboundType("MATERIAL_ISSUE"); order.setWorkOrderId(workOrder.getWorkOrderId()); order.setWarehouseId(raw.getWarehouseId());
        order.setAuditStatus("SUBMITTED"); order.setDeletedFlag(0); orders.insert(order);
        var d = new WmsOutboundDetail(); d.setOutboundId(order.getOutboundId()); d.setMaterialId(material.getMaterialId()); d.setBatchId(batch.getBatchId());
        d.setLocationId(rawLocation.getLocationId()); d.setOutboundQuantity(new BigDecimal(quantity)); d.setDeletedFlag(0); details.insert(d); return order;
    }
}
