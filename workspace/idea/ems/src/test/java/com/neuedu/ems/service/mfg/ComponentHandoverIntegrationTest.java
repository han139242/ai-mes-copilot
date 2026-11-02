package com.neuedu.ems.service.mfg;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"mes.notification.scheduling-enabled=false","mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY,PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_.*")
@Transactional
@AutoConfigureMockMvc
class ComponentHandoverIntegrationTest extends ProductionOrderProgressTestFixture {
    @Autowired ComponentHandoverService handover;
    @Autowired com.neuedu.ems.service.wms.WmsInboundOrderService inbounds;
    @Autowired com.neuedu.ems.service.trace.TraceProductLotService productLots;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired MfgWorkOrderService workOrders;
    Long parent, child, requirement, childPlan, lineWarehouse, location;

    @BeforeEach void production() {
        Long order = insertOrder("RUNNING", BigDecimal.TEN);
        Long plan = insertPlan(order, BigDecimal.TEN, "RUNNING", "ROOT", null);
        parent = insertWorkOrder("PARENT", plan, product, BigDecimal.TEN, "RELEASED", null);
        childPlan = insertPlan(order, BigDecimal.TEN, "RUNNING", "COMPONENT", plan);
        child = insertWorkOrder("CHILD", childPlan, componentProduct, BigDecimal.TEN, "RUNNING", parent);
        requirement = insertRequirement(parent, inputMaterial, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN,
                "WAIT_READY", "COMPONENT_WORK_ORDER", child, BigDecimal.ZERO);
        jdbc.update("UPDATE mfg_work_order SET source_requirement_id=? WHERE work_order_id=?", requirement, child);
        insertSupplySource(requirement, child, "COMPONENT_WORK_ORDER", BigDecimal.TEN);
        String code = tag();
        jdbc.update("INSERT INTO wms_warehouse(warehouse_code,warehouse_name,warehouse_type,status,version,deleted_flag) VALUES(?,?,'LINE_SIDE','ENABLE',0,0)",code,code);
        lineWarehouse = jdbc.queryForObject("SELECT warehouse_id FROM wms_warehouse WHERE warehouse_code=?",Long.class,code);
        jdbc.update("INSERT INTO wms_location(location_code,location_name,warehouse_id,line_id,location_type,status,version,deleted_flag) VALUES(?,?,?,?,'NORMAL','IDLE',0,0)",code,code,lineWarehouse,lineId);
        location = jdbc.queryForObject("SELECT location_id FROM wms_location WHERE location_code=?",Long.class,code);
        session.clearCache();
    }

    private Long output(BigDecimal good, String verdict) {
        Long task = insertTask("OUT",child,1L,good,"FINISHED");
        Long report = insertReport("OUT",task,good,"APPROVED","2026-10-30 08:00:00");
        Long lot = insertLot("OUT",componentProduct,child,report,good);
        jdbc.update("UPDATE trace_product_lot SET status='PASS' WHERE product_lot_id=?",lot);
        insertInspection("OUT","FINAL",child,lot,good,verdict,"FINISHED","2026-10-30 09:00:00");
        String code = tag();
        jdbc.update("INSERT INTO wms_material_batch(batch_no,material_id,quality_status,status,source_order_type,source_order_id,source_product_lot_id,version,deleted_flag) VALUES(?,?,'WAIT_INSPECTION','AVAILABLE','PRODUCT_LOT',?,?,0,0)",code,inputMaterial,lot,lot);
        Long batch = jdbc.queryForObject("SELECT batch_id FROM wms_material_batch WHERE batch_no=?",Long.class,code);
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,location_id,material_id,batch_id,work_order_id,available_quantity,locked_quantity,inspection_quantity,version,deleted_flag) VALUES(?,?,?,?,?,0,?,0,0,0)",lineWarehouse,location,inputMaterial,batch,child,good);
        session.clearCache(); return lot;
    }

    @Test void partialReleaseAndReworkRecoverOnlyAcceptedQuantityAndReplayDoesNothing() {
        Long original = output(new BigDecimal("9"),"FAIL");
        assertEquals(0,handover.synchronize(parent,1L).signum(),"未处置的失败检验不能直接交接");
        Long inspection = jdbc.queryForObject("SELECT inspection_id FROM qms_inspection_order WHERE product_lot_id=?",Long.class,original);
        jdbc.update("INSERT INTO qms_nonconforming_order(ncr_no,inspection_id,product_lot_id,disposition_type,approval_status,status,version,deleted_flag) VALUES(?,?,?,'REWORK','APPROVED','CLOSED',0,0)",tag(),inspection,original);
        session.clearCache();
        assertEquals(0,handover.synchronize(parent,1L).compareTo(new BigDecimal("9")));
        assertEquals(0,jdbc.queryForObject("SELECT issued_quantity FROM mfg_work_order_material WHERE requirement_id=?",BigDecimal.class,requirement).compareTo(new BigDecimal("9")));
        assertEquals(0,jdbc.queryForObject("SELECT locked_quantity FROM wms_inventory WHERE work_order_id=? AND material_id=?",BigDecimal.class,child,inputMaterial).signum());
        Long repair = output(BigDecimal.ONE,"PASS");
        jdbc.update("UPDATE mfg_task t JOIN mfg_work_report r ON r.task_id=t.task_id JOIN trace_product_lot l ON l.source_report_id=r.report_id SET t.task_type='REWORK' WHERE l.product_lot_id=?",repair);
        session.clearCache();
        assertEquals(0,handover.synchronize(parent,1L).compareTo(BigDecimal.ONE));
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM wms_stock_movement WHERE movement_type='COMPONENT_HANDOVER' AND work_order_id IN (?,?)",Integer.class,parent,child);
        assertEquals(4,count);
        assertEquals(0,handover.synchronize(parent,1L).signum());
        jdbc.update("UPDATE mfg_work_order SET status='DRAFT' WHERE work_order_id=?",parent);
        session.clearCache();
        assertThrows(BusinessException.class,()->workOrders.removeWorkOrderById(parent,1L));
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM wms_stock_movement WHERE movement_type='COMPONENT_HANDOVER' AND work_order_id IN (?,?)",Integer.class,parent,child));
        assertEquals(0,productLots.getProductLotById(original).getHandedOverQuantity().compareTo(new BigDecimal("9")));
        assertEquals(0,handover.deliveredLot(repair).compareTo(BigDecimal.ONE));
        assertThrows(BusinessException.class,()->inbounds.generateProductionInbound(original,lineWarehouse,location,1L,"不能再次入库"));
    }

    @Test void differentLineAndMismatchedRequirementNeverAutoReceive() {
        output(BigDecimal.TEN,"PASS");
        Long other = jdbc.queryForObject("SELECT line_id FROM factory_line WHERE line_id<>? AND deleted_flag=0 LIMIT 1",Long.class,lineId);
        jdbc.update("UPDATE mfg_production_plan SET line_id=? WHERE plan_id=?",other,childPlan);
        session.clearCache(); assertEquals(0,handover.synchronize(parent,1L).signum());
        jdbc.update("UPDATE mfg_production_plan SET line_id=? WHERE plan_id=?",lineId,childPlan);
        jdbc.update("UPDATE mfg_work_order SET source_requirement_id=0 WHERE work_order_id=?",child);
        session.clearCache(); assertEquals(0,handover.synchronize(parent,1L).signum());
        assertEquals(0,jdbc.queryForObject("SELECT issued_quantity FROM mfg_work_order_material WHERE requirement_id=?",BigDecimal.class,requirement).signum());
    }

    @Test void inspectionFailureCannotHandoverOrWarehouseUnreleasedPiecesFromAnOlderReportTotal() {
        Long lot = output(BigDecimal.TEN,"FAIL");
        Long inspection = jdbc.queryForObject("SELECT inspection_id FROM qms_inspection_order WHERE product_lot_id=?",Long.class,lot);
        jdbc.update("UPDATE qms_inspection_order SET qualified_quantity=9,unqualified_quantity=1 WHERE inspection_id=?",inspection);
        jdbc.update("INSERT INTO qms_nonconforming_order(ncr_no,inspection_id,product_lot_id,disposition_type,approval_status,status,version,deleted_flag) VALUES(?,?,?,'REWORK','APPROVED','CLOSED',0,0)",tag(),inspection,lot);
        session.clearCache();
        assertEquals(0,handover.synchronize(parent,1L).compareTo(new BigDecimal("9")));
        assertEquals(0,jdbc.queryForObject("SELECT locked_quantity FROM wms_inventory WHERE work_order_id=? AND material_id=?",BigDecimal.class,child,inputMaterial).compareTo(BigDecimal.ONE));
        assertThrows(BusinessException.class,()->inbounds.generateProductionInbound(lot,lineWarehouse,location,1L,"未放行件不得入库"));
    }

    @Test void submittedWarehouseReceiptRetainsItsQuantityAndOnlyRemainderIsHandedOver() {
        Long lot = output(BigDecimal.TEN,"PASS");
        Long inbound = insertInbound("PRODUCT_LOT",lot,"SUBMITTED","2026-10-30 10:00:00");
        insertInboundDetail(inbound,inputMaterial,new BigDecimal("4"));
        session.clearCache();
        assertEquals(0,handover.synchronize(parent,1L).compareTo(new BigDecimal("6")));
        var demand = new com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial();
        demand.setRequirementId(requirement); demand.setWorkOrderId(parent); demand.setMaterialId(inputMaterial);
        demand.setSourceWorkOrderId(child); demand.setRequiredQuantity(BigDecimal.TEN); demand.setIssuedQuantity(new BigDecimal("6"));
        assertEquals(0,handover.warehouseOutstanding(orders.selectById(parent),demand).compareTo(new BigDecimal("4")));
        assertEquals(0,handover.synchronize(parent,1L).signum());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedQualityEventReallyTransfersAndRollbackEventDoesNot() {
        // 单独验证 AFTER_COMMIT + REQUIRES_NEW；只在隔离库提交，finally按本次主键清理夹具。
        Long lot = null;
        try {
            lot = output(BigDecimal.TEN,"PASS");
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
            tx.executeWithoutResult(status -> {
                events.publishEvent(new ComponentHandoverService.Released(child,1L));
                status.setRollbackOnly();
            });
            assertEquals(0,jdbc.queryForObject("SELECT issued_quantity FROM mfg_work_order_material WHERE requirement_id=?",BigDecimal.class,requirement).signum());
            tx.executeWithoutResult(status -> events.publishEvent(new ComponentHandoverService.Released(child,1L)));
            assertEquals(0,jdbc.queryForObject("SELECT issued_quantity FROM mfg_work_order_material WHERE requirement_id=?",BigDecimal.class,requirement).compareTo(BigDecimal.TEN));
            assertEquals(0,handover.deliveredLot(lot).compareTo(BigDecimal.TEN));
            tx.executeWithoutResult(status -> events.publishEvent(new ComponentHandoverService.Released(child,1L)));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM wms_stock_movement WHERE movement_type='COMPONENT_HANDOVER' AND work_order_id IN (?,?)",Integer.class,parent,child));
        } finally {
            Long parentPlan = jdbc.queryForObject("SELECT plan_id FROM mfg_work_order WHERE work_order_id=?",Long.class,parent);
            Long orderId = jdbc.queryForObject("SELECT production_order_id FROM mfg_production_plan WHERE plan_id=?",Long.class,parentPlan);
            jdbc.update("DELETE FROM wms_stock_movement WHERE work_order_id IN (?,?)",parent,child);
            jdbc.update("DELETE FROM wms_inventory WHERE work_order_id IN (?,?)",parent,child);
            if (lot != null) jdbc.update("DELETE FROM wms_material_batch WHERE source_product_lot_id=?",lot);
            jdbc.update("DELETE FROM qms_inspection_order WHERE work_order_id=?",child);
            jdbc.update("DELETE FROM trace_product_lot WHERE work_order_id=?",child);
            jdbc.update("DELETE FROM mfg_work_report WHERE task_id IN (SELECT task_id FROM mfg_task WHERE work_order_id=?)",child);
            jdbc.update("DELETE FROM mfg_task WHERE work_order_id=?",child);
            jdbc.update("DELETE FROM mfg_supply_source WHERE requirement_id=?",requirement);
            jdbc.update("DELETE FROM mfg_work_order_material WHERE work_order_id IN (?,?)",parent,child);
            jdbc.update("DELETE FROM mfg_work_order WHERE work_order_id IN (?,?)",parent,child);
            jdbc.update("DELETE FROM mfg_production_plan WHERE plan_id IN (?,?)",parentPlan,childPlan);
            jdbc.update("DELETE FROM mfg_production_order WHERE production_order_id=?",orderId);
            jdbc.update("DELETE FROM mdm_customer WHERE customer_id=?",customerId);
            jdbc.update("DELETE FROM wms_location WHERE location_id=?",location);
            jdbc.update("DELETE FROM wms_warehouse WHERE warehouse_id=?",lineWarehouse);
        }
    }
}
