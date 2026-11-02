package com.neuedu.ems.service.mfg;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.argThat;

/** 本机隔离库中的非受控合成产品夹具；不绕过台灯门禁，不作为真实生产验收。 */
@SpringBootTest(properties={"mes.notification.scheduling-enabled=false","mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY,PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_.*")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class ComponentSupplyPlanningIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MfgWorkOrderService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MfgProductionPlanService plans;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.neuedu.ems.util.JwtUtil jwt;
    @MockitoSpyBean SysStatusChangeLogMapper statusLogs;
    String tag;
    Long rootMaterial,componentMaterial,rawMaterial,rootProduct,componentProduct,rootBom,childBom;
    Long rootRoute,childRoute,rootLine,childLine,orderId,planId,warehouse,location,batch,inventory;
    Long fixtureShift;
    LocalDateTime planStart;
    /** 多层组件用例临时追加的夹具，按用例登记后由 cleanup 一并清理。 */
    final List<Long> extraProducts=new ArrayList<>(),extraMaterials=new ArrayList<>(),extraLines=new ArrayList<>();

    @BeforeEach void fixture() {
        tag="CP"+System.nanoTime();
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            // 班次由用例自己创建，不依赖旧演示库中固定的shift_id=1。
            jdbc.update("INSERT INTO org_shift(shift_code,shift_name,start_time,end_time,version,deleted_flag) VALUES (?,?,'08:00:00','16:00:00',0,0)",tag,"供给验证班次");
            fixtureShift=id("org_shift","shift_id","shift_code",tag);
            planStart=java.time.LocalDate.now().plusDays(1).atTime(8,0);
            rootMaterial=material("OUT");componentMaterial=material("CMP");rawMaterial=material("RAW");
            rootProduct=product("ROOT",rootMaterial);componentProduct=product("CHILD",componentMaterial);
            rootBom=bom("ROOT",rootProduct,"2026-01-01");childBom=bom("CHILD",componentProduct,"2026-01-01");
            jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,?,1,0,1,0,0)",rootBom,componentMaterial,childBom);
            jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,2,0,1,0,0)",childBom,rawMaterial);
            rootRoute=route("ROOT",rootProduct);childRoute=route("CHILD",componentProduct);
            rootLine=line("ROOT");childLine=line("CHILD");
            capability(rootProduct,rootLine,rootRoute);
            // 组件自身专线给更高优先级，父产线备份一条；同一组件的多条能力必须能按优先级区分，
            // 否则会被判成"同优先级歧义"，掩盖真正要验证的组件供给逻辑。
            capability(componentProduct,childLine,childRoute,1);capability(componentProduct,rootLine,childRoute,2);
            jdbc.update("INSERT INTO mfg_production_order(production_order_no,product_id,order_quantity,status,version,deleted_flag) VALUES (?,?,10,'RELEASED',0,0)",tag,rootProduct);
            orderId=id("mfg_production_order","production_order_id","production_order_no",tag);
            jdbc.update("INSERT INTO mfg_production_plan(plan_no,production_order_id,line_id,planned_start_time,planned_end_time,planned_quantity,status,plan_type,version,deleted_flag) VALUES (?,?,?,?,?,10,'RELEASED','ROOT',0,0)",tag,orderId,rootLine,planStart,planStart.plusHours(8));
            planId=id("mfg_production_plan","plan_id","plan_no",tag);
            jdbc.update("INSERT INTO wms_warehouse(warehouse_code,warehouse_type,status,version,deleted_flag) VALUES (?,'SEMI','ENABLE',0,0)",tag);
            warehouse=id("wms_warehouse","warehouse_id","warehouse_code",tag);
            jdbc.update("INSERT INTO wms_location(location_code,warehouse_id,location_type,status,version,deleted_flag) VALUES (?,?,'NORMAL','IDLE',0,0)",tag,warehouse);
            location=id("wms_location","location_id","location_code",tag);
            jdbc.update("INSERT INTO wms_material_batch(batch_no,material_id,quality_status,status,version,deleted_flag) VALUES (?,?,'PASS','AVAILABLE',0,0)",tag,componentMaterial);
            batch=id("wms_material_batch","batch_id","batch_no",tag);
            jdbc.update("INSERT INTO wms_inventory(warehouse_id,location_id,material_id,batch_id,work_order_id,available_quantity,locked_quantity,inspection_quantity,version,deleted_flag) VALUES (?,?,?,?,0,4,30,40,0,0)",warehouse,location,componentMaterial,batch);
            inventory=jdbc.queryForObject("SELECT inventory_id FROM wms_inventory WHERE batch_id=?",Long.class,batch);
        });
    }

    @AfterEach void cleanup() {
        // 只删除本测试独有前缀的合成夹具，不处理业务数据。
        if (tag==null) return;
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            // 先按"属于本用例的计划"收集工单，避免多层组件用例漏删孙层工单。
            List<Long> workOrders=new ArrayList<>(jdbc.queryForList(
                    "SELECT work_order_id FROM mfg_work_order WHERE plan_id IN (SELECT plan_id FROM mfg_production_plan WHERE production_order_id=?)",
                    Long.class,orderId));
            for (Long workOrder:workOrders) {
                jdbc.update("DELETE FROM sys_status_change_log WHERE biz_type='MFG_WORK_ORDER' AND biz_id=?",workOrder);
                jdbc.update("DELETE FROM sys_operation_log WHERE module_code='mfg_work_order' AND biz_id=?",workOrder);
                jdbc.update("DELETE FROM mfg_work_order_material WHERE work_order_id=?",workOrder);
                jdbc.update("DELETE FROM mfg_supply_allocation WHERE work_order_id=?",workOrder);
                jdbc.update("DELETE FROM mfg_supply_source WHERE work_order_id=?",workOrder);
                jdbc.update("DELETE FROM mfg_work_order WHERE work_order_id=?",workOrder);
            }
            for (Long plan:jdbc.queryForList("SELECT plan_id FROM mfg_production_plan WHERE production_order_id=?",Long.class,orderId)) {
                jdbc.update("DELETE FROM sys_status_change_log WHERE biz_type='MFG_PRODUCTION_PLAN' AND biz_id=?",plan);
                jdbc.update("DELETE FROM sys_operation_log WHERE module_code='mfg_production_plan' AND biz_id=?",plan);
            }
            jdbc.update("DELETE FROM mfg_generate_request WHERE plan_id IN (SELECT plan_id FROM mfg_production_plan WHERE production_order_id=?)",orderId);
            jdbc.update("DELETE FROM mfg_production_plan WHERE production_order_id=?",orderId);
            jdbc.update("DELETE FROM mfg_production_order WHERE production_order_id=?",orderId);
            jdbc.update("DELETE FROM wms_inventory WHERE inventory_id=?",inventory);
            jdbc.update("DELETE FROM wms_material_batch WHERE batch_id=?",batch);
            jdbc.update("DELETE FROM wms_location WHERE location_id=?",location);
            jdbc.update("DELETE FROM wms_warehouse WHERE warehouse_id=?",warehouse);
            List<Long> products=new ArrayList<>(List.of(rootProduct,componentProduct));products.addAll(extraProducts);
            List<Long> lines=new ArrayList<>(List.of(rootLine,childLine));lines.addAll(extraLines);
            List<Long> materials=new ArrayList<>(List.of(rootMaterial,componentMaterial,rawMaterial));materials.addAll(extraMaterials);
            jdbc.update("DELETE FROM mfg_line_product_capability WHERE product_id IN ("+placeholders(products)+")",products.toArray());
            jdbc.update("DELETE FROM mfg_line_calendar_rule WHERE line_id IN ("+placeholders(lines)+")",lines.toArray());
            jdbc.update("DELETE FROM org_shift WHERE shift_id=?",fixtureShift);
            jdbc.update("DELETE FROM factory_line WHERE line_id IN ("+placeholders(lines)+")",lines.toArray());
            jdbc.update("DELETE FROM process_route_item WHERE route_id IN (SELECT route_id FROM process_route WHERE product_id IN ("+placeholders(products)+"))",products.toArray());
            jdbc.update("DELETE FROM process_route WHERE product_id IN ("+placeholders(products)+")",products.toArray());
            jdbc.update("DELETE FROM mdm_bom_item WHERE bom_id IN (SELECT bom_id FROM mdm_bom WHERE product_id IN ("+placeholders(products)+"))",products.toArray());
            jdbc.update("DELETE FROM mdm_bom WHERE product_id IN ("+placeholders(products)+")",products.toArray());
            jdbc.update("DELETE FROM mdm_product WHERE product_id IN ("+placeholders(products)+")",products.toArray());
            jdbc.update("DELETE FROM mdm_material WHERE material_id IN ("+placeholders(materials)+")",materials.toArray());
        });
    }
    private static String placeholders(List<?> values) {
        return String.join(",", java.util.Collections.nCopies(values.size(),"?"));
    }

    @Test void componentRequirementKeepsFullShortageAndChildSharesParentPlanWithExactVersion() {
        Long newer=bom("NEWER",componentProduct,"2026-02-01");
        var root=service.generateWorkOrder(planId,new MfgWorkOrder(),1L);
        Map<String,Object> child=jdbc.queryForMap("SELECT * FROM mfg_work_order WHERE parent_work_order_id=?",root.getWorkOrderId());
        assertEquals(childBom,((Number)child.get("bom_id")).longValue());assertNotEquals(newer,childBom);
        // 组件工单从 0 起算：库存有货也照样按整份需求量建工单，半成品是否直供放到领料环节决定。
        assertEquals(new BigDecimal("10.0000"),child.get("planned_quantity"));assertEquals("DRAFT",child.get("status"));
        assertEquals(root.getWorkOrderId(),((Number)child.get("root_work_order_id")).longValue());
        Map<String,Object> plan=jdbc.queryForMap("SELECT * FROM mfg_production_plan WHERE plan_id=?",child.get("plan_id"));
        // 一张工单一条线做到底：组件补产计划跟随父计划产线，而不是组件自己的专线。
        assertEquals(rootLine,((Number)plan.get("line_id")).longValue());assertEquals("RELEASED",plan.get("status"));
        assertEquals(planId,((Number)child.get("plan_id")).longValue());
        assertNotNull(child.get("planned_start_time"));assertNotNull(child.get("planned_end_time"));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",Long.class,root.getWorkOrderId(),componentMaterial));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",Long.class,root.getWorkOrderId(),rawMaterial));
        // 组件工单按整份 10 件生成，其 BOM 每件耗 2，所以原材料需求是 20。
        assertEquals(new BigDecimal("20.0000"),jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,child.get("work_order_id"),rawMaterial));
        // 组件需求不再在生成阶段占用半成品库存：4 件合格库存原样留在可用量里，
        // 由组件工单领料时再决定"直接领半成品"还是"领原材料自己生产"。
        assertEquals(new BigDecimal("4.0000"),jdbc.queryForObject("SELECT available_quantity FROM wms_inventory WHERE inventory_id=?",BigDecimal.class,inventory));
        assertEquals(new BigDecimal("30.0000"),jdbc.queryForObject("SELECT locked_quantity FROM wms_inventory WHERE inventory_id=?",BigDecimal.class,inventory));
        assertEquals(new BigDecimal("0.0000"),jdbc.queryForObject("SELECT reserved_quantity FROM mfg_work_order_material WHERE work_order_id=?",BigDecimal.class,root.getWorkOrderId()));
        assertEquals("WAIT_READY",jdbc.queryForObject("SELECT status FROM mfg_work_order_material WHERE work_order_id=?",String.class,root.getWorkOrderId()));
        assertEquals(new BigDecimal("10.0000"),jdbc.queryForObject("SELECT shortage_quantity FROM mfg_work_order_material WHERE work_order_id=?",BigDecimal.class,root.getWorkOrderId()));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_supply_allocation WHERE work_order_id=?",Long.class,root.getWorkOrderId()));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM sys_status_change_log WHERE biz_type='MFG_WORK_ORDER' AND biz_id=? AND change_reason='GENERATE_COMPONENT_WORK_ORDER'",Long.class,child.get("work_order_id")));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE module_code='mfg_work_order' AND biz_id=? AND operation_type='GENERATE_COMPONENT_WORK_ORDER'",Long.class,child.get("work_order_id")));
    }
    @Test void unqualifiedStockCannotSuppressComponentProduction() {
        jdbc.update("UPDATE wms_material_batch SET quality_status='WAIT_INSPECTION' WHERE batch_id=?",batch);
        var root=service.generateWorkOrder(planId,null,1L);
        assertEquals(new BigDecimal("10.0000"),jdbc.queryForObject("SELECT planned_quantity FROM mfg_work_order WHERE parent_work_order_id=?",BigDecimal.class,root.getWorkOrderId()));
        // 未放行批次不进入预留，可用量原样保留。
        assertEquals(new BigDecimal("4.0000"),jdbc.queryForObject("SELECT available_quantity FROM wms_inventory WHERE inventory_id=?",BigDecimal.class,inventory));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_supply_allocation WHERE work_order_id=?",Long.class,root.getWorkOrderId()));
    }
    @Test void fullyCoveredStockStillCreatesComponentWorkOrderWithFullQuantity() {
        // 从 0 起算：半成品仓有足量库存也照样生成组件工单，只是这张工单开工时可以直接领半成品。
        jdbc.update("UPDATE wms_inventory SET available_quantity=10 WHERE inventory_id=?",inventory);
        var root=service.generateWorkOrder(planId,null,1L);
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE parent_work_order_id=?",Long.class,root.getWorkOrderId()));
        assertEquals(new BigDecimal("10.0000"),jdbc.queryForObject("SELECT planned_quantity FROM mfg_work_order WHERE parent_work_order_id=?",BigDecimal.class,root.getWorkOrderId()));
        // 组件工单与父计划共享同一张计划，不会另建组件计划。
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_production_plan WHERE parent_plan_id=?",Long.class,planId));
        // 组件需求不预留库存，缺口保持整份需求量，库存留到领料环节再决定用不用。
        assertEquals("WAIT_READY",jdbc.queryForObject("SELECT status FROM mfg_work_order_material WHERE work_order_id=?",String.class,root.getWorkOrderId()));
        assertEquals(new BigDecimal("0.0000"),jdbc.queryForObject("SELECT reserved_quantity FROM mfg_work_order_material WHERE work_order_id=?",BigDecimal.class,root.getWorkOrderId()));
        assertEquals(new BigDecimal("10.0000"),jdbc.queryForObject("SELECT available_quantity FROM wms_inventory WHERE inventory_id=?",BigDecimal.class,inventory));
    }
    @Test void reservedStockCannotBeAllocatedByASecondPlan() {
        // 计划一不再占用半成品库存，计划二同样按整份缺口补产，两份计划都不会重复分配同一份库存。
        jdbc.update("UPDATE wms_inventory SET available_quantity=4 WHERE inventory_id=?",inventory);
        service.generateWorkOrder(planId,null,1L);
        Long secondOrder=jdbc.queryForObject("SELECT production_order_id FROM mfg_production_order WHERE production_order_id=?",Long.class,orderId);
        jdbc.update("INSERT INTO mfg_production_plan(plan_no,production_order_id,line_id,planned_start_time,planned_end_time,planned_quantity,status,plan_type,version,deleted_flag) VALUES (?,?,?,?,?,10,'RELEASED','ROOT',0,0)",
                tag+"-2",secondOrder,rootLine,LocalDateTime.now().plusHours(6),LocalDateTime.now().plusHours(9));
        Long secondPlan=jdbc.queryForObject("SELECT plan_id FROM mfg_production_plan WHERE plan_no=?",Long.class,tag+"-2");
        var second=service.generateWorkOrder(secondPlan,null,1L);
        assertEquals(new BigDecimal("10.0000"),jdbc.queryForObject("SELECT planned_quantity FROM mfg_work_order WHERE parent_work_order_id=?",BigDecimal.class,second.getWorkOrderId()));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_supply_allocation WHERE work_order_id=?",Long.class,second.getWorkOrderId()));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_supply_allocation WHERE material_id=?",Long.class,componentMaterial));
        assertEquals(new BigDecimal("4.0000"),jdbc.queryForObject("SELECT available_quantity FROM wms_inventory WHERE inventory_id=?",BigDecimal.class,inventory));
    }
    @Test void missingComponentCapabilityRollsBackRootRequirementsAndPlans() {
        jdbc.update("DELETE FROM mfg_line_product_capability WHERE product_id=?",componentProduct);
        assertThrows(BusinessException.class,()->service.generateWorkOrder(planId,null,1L));
        assertNoGeneratedRows();
    }
    @Test void lateAuditFailureRollsBackRootAndChildTogether() {
        Long statusCount=jdbc.queryForObject("SELECT COUNT(*) FROM sys_status_change_log",Long.class);
        Long operationCount=jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log",Long.class);
        doThrow(new IllegalStateException("模拟末端审计失败")).when(statusLogs)
                .insert(argThat((SysStatusChangeLog log)->"MFG_WORK_ORDER".equals(log.getBizType())));
        assertThrows(IllegalStateException.class,()->service.generateWorkOrder(planId,null,1L));
        assertNoGeneratedRows();
        assertEquals(statusCount,jdbc.queryForObject("SELECT COUNT(*) FROM sys_status_change_log",Long.class));
        assertEquals(operationCount,jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log",Long.class));
    }
    @Test void laterComponentFailureRollsBackPreviouslyGeneratedSibling() {
        jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,?,1,0,2,0,0)",rootBom,rawMaterial,childBom);
        assertThrows(BusinessException.class,()->service.generateWorkOrder(planId,null,1L));
        assertNoGeneratedRows();
    }
    @Test void concurrentGenerationProducesOnlyOneRootAndOneChild() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Boolean> command=()->{start.await();try{service.generateWorkOrder(planId,null,1L);return true;}catch(BusinessException expected){return false;}};
            Future<Boolean> one=pool.submit(command),two=pool.submit(command);start.countDown();
            assertNotEquals(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
            assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE plan_id=? AND work_order_type='ROOT'",Long.class,planId));
            assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE product_id=?",Long.class,componentProduct));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void disabledProductIsBlockedWithoutAnyGeneratedRows() {
        // 执行链贯通后受控产品不再在生成阶段被拦截，门禁只剩"产品可用"这一层：
        // 未启用或未关联成品物料的产品仍然必须阻断，且不留任何生成痕迹。
        jdbc.update("UPDATE mdm_product SET status='DISABLE' WHERE product_id=?",rootProduct);
        var error=assertThrows(BusinessException.class,()->service.generateWorkOrder(planId,null,1L));
        assertTrue(error.getMessage().contains("未启用"));assertNoGeneratedRows();
    }
    @Test void componentPlansDoNotConsumeFinishedProductOrderQuota() {
        jdbc.update("UPDATE mfg_production_order SET order_quantity=20 WHERE production_order_id=?",orderId);
        service.generateWorkOrder(planId,null,1L);
        var request=new com.neuedu.ems.entity.mfg.MfgProductionPlan();request.setLineId(rootLine);
        request.setPlannedQuantity(BigDecimal.TEN);request.setPlannedStartTime(LocalDateTime.now().plusDays(1));
        request.setPlannedEndTime(LocalDateTime.now().plusDays(1).plusHours(4));
        var second=plans.generatePlan(orderId,request,1L);
        assertEquals("RELEASED",plans.releasePlan(second.getPlanId(),1L,null).getStatus());
    }
    @Test void componentDraftCanReleaseOnlyWithMatchingOwnResourcesAndQuantity() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        assertEquals("RELEASED",plans.releasePlan(componentPlan,1L,null).getStatus());
    }
    @Test void componentDraftCanReleaseAfterSiblingStartedTheOrder() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        jdbc.update("UPDATE mfg_production_order SET status='RUNNING' WHERE production_order_id=?",orderId);
        assertEquals("RELEASED",plans.releasePlan(componentPlan,1L,null).getStatus());
        assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM mfg_production_order WHERE production_order_id=?",String.class,orderId));
    }
    @Test void runningOrderDoesNotBypassComponentSourceValidation() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        jdbc.update("UPDATE mfg_production_order SET status='RUNNING' WHERE production_order_id=?",orderId);
        // 换成一条对组件完全没有产品能力的产线：能力解析只能退回组件自身专线，与计划产线不一致，必须拒绝生效。
        Long incapable=line("NOCAP");
        extraLines.add(incapable);
        jdbc.update("UPDATE mfg_production_plan SET line_id=? WHERE plan_id=?",incapable,componentPlan);
        assertThrows(BusinessException.class,()->plans.releasePlan(componentPlan,1L,null));
        assertEquals("DRAFT",plans.getPlanById(componentPlan).getStatus());
    }
    @Test void closedOrderStillRejectsComponentRelease() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        jdbc.update("UPDATE mfg_production_order SET status='CLOSED' WHERE production_order_id=?",orderId);
        assertThrows(BusinessException.class,()->plans.releasePlan(componentPlan,1L,null));
        assertEquals("DRAFT",plans.getPlanById(componentPlan).getStatus());
    }
    @Test void componentPlanOnIncapableLineCannotBypassResourceChecks() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        // 组件现在默认与父计划同线，改到一条没有任何产品能力的产线上仍然必须被拒绝。
        Long incapable=line("NOCAP");
        extraLines.add(incapable);
        jdbc.update("UPDATE mfg_production_plan SET line_id=? WHERE plan_id=?",incapable,componentPlan);
        assertThrows(BusinessException.class,()->plans.releasePlan(componentPlan,1L,null));
        assertEquals("DRAFT",plans.getPlanById(componentPlan).getStatus());
    }
    @Test void componentPlanCanReleaseOnParentLine() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        assertEquals(rootLine,jdbc.queryForObject("SELECT line_id FROM mfg_production_plan WHERE plan_id=?",Long.class,componentPlan));
        assertEquals("RELEASED",plans.releasePlan(componentPlan,1L,null).getStatus());
    }
    @Test void componentCapacityMustStillBeValidWhenDraftPlanReleases() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        // 组件现在跑在父计划这条线上，产能校验的对象也换成这条线。
        jdbc.update("UPDATE factory_line SET daily_capacity=0 WHERE line_id=?",rootLine);
        assertThrows(BusinessException.class,()->plans.releasePlan(componentPlan,1L,null));
        assertEquals("DRAFT",plans.getPlanById(componentPlan).getStatus());
    }
    @Test void siblingComponentPlansOnTheSameLineCanAllRelease() {
        // 同父的两个自制件都排在父计划这条线上，生成时整族首尾相接，彼此必然咬住窗口。
        // 生效复核必须与生成一样把同族其他计划排除出占用，否则后一张会被前一张挤到次日而判成排不下。
        Long secondMaterial=material("CMP2");
        Long secondProduct=product("CHILD2",secondMaterial);
        Long secondBom=bom("CHILD2",secondProduct,"2026-01-01");
        jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,2,0,1,0,0)",secondBom,rawMaterial);
        jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,?,1,0,2,0,0)",rootBom,secondMaterial,secondBom);
        Long secondRoute=route("CHILD2",secondProduct);
        capability(secondProduct,rootLine,secondRoute);
        extraProducts.add(secondProduct);extraMaterials.add(secondMaterial);
        // 日产能压到 20 件（一个 8 小时班次）：整份 10 件占 4 小时，两张各自都排得下，
        // 于是"前一张占用是否被排除"直接决定后一张能不能按原计划结束——
        // 若同族计划被算成互相占用，后一张就会被挤到次日，与这里写死的窗口对不上。
        jdbc.update("UPDATE factory_line SET daily_capacity=20 WHERE line_id=?",rootLine);
        service.generateWorkOrder(planId,null,1L);
        Long firstPlan=legacyComponentPlan(0);
        Long secondPlan=legacyComponentPlan(1);
        assertEquals(rootLine,jdbc.queryForObject("SELECT line_id FROM mfg_production_plan WHERE plan_id=?",Long.class,firstPlan));
        assertEquals(rootLine,jdbc.queryForObject("SELECT line_id FROM mfg_production_plan WHERE plan_id=?",Long.class,secondPlan));
        // 锚到下一个周一 08:00 的班次起点；整份 10 件在日产能 20 下占 4 小时。
        LocalDateTime firstStart=java.time.LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).atTime(8,0);
        jdbc.update("UPDATE mfg_production_plan SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",
                firstStart,firstStart.plusHours(4),firstPlan);
        jdbc.update("UPDATE mfg_production_plan SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",
                firstStart,firstStart.plusHours(4),secondPlan);
        assertEquals("RELEASED",plans.releasePlan(firstPlan,1L,null).getStatus());
        assertEquals("RELEASED",plans.releasePlan(secondPlan,1L,null).getStatus());
    }
    @Test void changingComponentPlanQuantityCannotDetachItFromItsWorkOrder() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        jdbc.update("UPDATE mfg_production_plan SET planned_quantity=7 WHERE plan_id=?",componentPlan);
        assertThrows(BusinessException.class,()->plans.releasePlan(componentPlan,1L,null));
        assertEquals("DRAFT",plans.getPlanById(componentPlan).getStatus());
    }
    @Test void genericPlanUpdateCannotChangeComponentIdentityToRoot() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        var command=new com.neuedu.ems.entity.mfg.MfgProductionPlan();command.setPlanId(componentPlan);
        command.setPlanType("ROOT");command.setParentPlanId(999L);command.setRemark("验证身份不可变");
        assertTrue(plans.updatePlan(command));
        var saved=plans.getPlanById(componentPlan);
        assertEquals("COMPONENT",saved.getPlanType());assertEquals(planId,saved.getParentPlanId());
    }
    @Test void httpGenerationPreservesContractAndRejectsDuplicateWithoutExtraRows() throws Exception {
        Long actor=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0",Long.class);
        String token=jwt.generateToken(actor,"admin");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mfg/work-order/generate/"+planId)
                .header("Authorization","Bearer "+token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.workOrderType").value("ROOT"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mfg/work-order/generate/"+planId)
                .header("Authorization","Bearer "+token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE product_id IN (?,?)",Long.class,rootProduct,componentProduct));
    }
    @Test void grandchildComponentIsExpandedAndReservedLevelByLevel() {
        // 第三层：组件 BOM 里再挂一个自制件，验证递归展开与逐层预留。
        Long subMaterial=material("SUB");
        Long subProduct=product("SUB",subMaterial);
        Long subBom=bom("SUB",subProduct,"2026-01-01");
        jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,?,2,0,2,0,0)",childBom,subMaterial,subBom);
        jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,3,0,1,0,0)",subBom,rawMaterial);
        Long subRoute=route("SUB",subProduct);Long subLine=line("SUB");
        capability(subProduct,subLine,subRoute,1);capability(subProduct,rootLine,subRoute,2);
        extraProducts.add(subProduct);extraMaterials.add(subMaterial);extraLines.add(subLine);

        var root=service.generateWorkOrder(planId,null,1L);
        var component=jdbc.queryForMap("SELECT * FROM mfg_work_order WHERE parent_work_order_id=?",root.getWorkOrderId());
        assertEquals(new BigDecimal("10.0000"),component.get("planned_quantity"));
        // 孙层工单挂在组件工单下面，但根工单仍是同一棵树的根。
        var grandchild=jdbc.queryForMap("SELECT * FROM mfg_work_order WHERE parent_work_order_id=?",component.get("work_order_id"));
        assertEquals(new BigDecimal("20.0000"),grandchild.get("planned_quantity"));
        assertEquals(root.getWorkOrderId(),((Number)grandchild.get("root_work_order_id")).longValue());
        assertEquals(3L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE plan_id IN (SELECT plan_id FROM mfg_production_plan WHERE production_order_id=?)",Long.class,orderId));
        // 组件层：自制件没有库存，缺口 20 全部由孙层工单补产；原材料 2×10=20。
        assertEquals(new BigDecimal("20.0000"),jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,component.get("work_order_id"),subMaterial));
        assertEquals(new BigDecimal("0.0000"),jdbc.queryForObject("SELECT reserved_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,component.get("work_order_id"),subMaterial));
        assertEquals(new BigDecimal("20.0000"),jdbc.queryForObject("SELECT shortage_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,component.get("work_order_id"),subMaterial));
        assertEquals(new BigDecimal("20.0000"),jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,component.get("work_order_id"),rawMaterial));
        // 孙层：3×20=60，且它自己的缺口不会再被上一层重复计算。
        assertEquals(new BigDecimal("60.0000"),jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,grandchild.get("work_order_id"),rawMaterial));
        // 组件需求不在生成阶段占用半成品库存，4 件合格库存原样留在可用量里。
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_supply_allocation WHERE material_id=?",Long.class,componentMaterial));
        assertEquals(new BigDecimal("4.0000"),jdbc.queryForObject("SELECT available_quantity FROM wms_inventory WHERE inventory_id=?",BigDecimal.class,inventory));
        assertEquals(new BigDecimal("30.0000"),jdbc.queryForObject("SELECT locked_quantity FROM wms_inventory WHERE inventory_id=?",BigDecimal.class,inventory));
    }
    @Test void cyclicComponentBomIsRejectedAndRollsBack() {
        // 构造真实环：根BOM → 自制件SUB(subBom) → 组件CMP(childBom) → 又回到SUB(subBom)。
        Long subMaterial=material("CYC");
        Long subProduct=product("CYC",subMaterial);
        Long subBom=bom("CYC",subProduct,"2026-01-01");
        jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,?,2,0,2,0,0)",childBom,subMaterial,subBom);
        jdbc.update("INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,version,deleted_flag) VALUES (?,?,?,1,0,1,0,0)",subBom,componentMaterial,childBom);
        Long subRoute=route("CYC",subProduct);Long subLine=line("CYC");
        capability(subProduct,subLine,subRoute,1);capability(subProduct,rootLine,subRoute,2);
        extraProducts.add(subProduct);extraMaterials.add(subMaterial);extraLines.add(subLine);
        BusinessException error=assertThrows(BusinessException.class,()->service.generateWorkOrder(planId,null,1L));
        assertTrue(error.getMessage().contains("循环引用"),error.getMessage());
        assertNoGeneratedRows();
    }
    @Test void componentShortageIsSplitAcrossCapacityWindows() {
        // 组件与父计划同线生产，所以拆单受这条线的产能约束：日产能只有 2 件、
        // 每周只有一个班次日（周一），从 0 起算的整份缺口 10 件必须拆成五张补产单。
        // 父计划锚定到"下一个周一 08:00"这种班次边界上，否则窗口会从当前时刻被裁掉一截，
        // 拆单张数随执行时刻漂移（曾出现同样夹具跑出 3 张、4 张、5 张）。
        LocalDateTime anchor=LocalDateTime.now().withNano(0)
                .toLocalDate().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
                .atTime(8,0);
        jdbc.update("UPDATE mfg_production_plan SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",
                anchor,anchor.plusHours(9),planId);
        jdbc.update("UPDATE factory_line SET daily_capacity=2 WHERE line_id=?",rootLine);
        jdbc.update("DELETE FROM mfg_line_calendar_rule WHERE line_id=?",rootLine);
        jdbc.update("INSERT INTO mfg_line_calendar_rule(line_id,day_of_week,shift_id,effective_from,effective_to,capacity_factor,status,version,deleted_flag) VALUES (?,?,?,'2026-01-01',NULL,1.0000,'ENABLE',0,0)",
                rootLine,anchor.getDayOfWeek().getValue(),fixtureShift);
        var root=service.generateWorkOrder(planId,null,1L);
        List<Map<String,Object>> componentPlans=jdbc.queryForList(
                "SELECT w.*,p.line_id FROM mfg_work_order w JOIN mfg_production_plan p ON p.plan_id=w.plan_id WHERE w.parent_work_order_id=? ORDER BY w.work_order_id",root.getWorkOrderId());
        assertEquals(5,componentPlans.size());
        BigDecimal total=BigDecimal.ZERO;
        for (Map<String,Object> componentPlan:componentPlans) {
            assertEquals(rootLine,((Number)componentPlan.get("line_id")).longValue());
            assertEquals(planId,((Number)componentPlan.get("plan_id")).longValue());
            total=total.add((BigDecimal)componentPlan.get("planned_quantity"));
            // 每张补产单都要有自己的供给来源明细，单一 source_work_order_id 只保留首张。
            assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_supply_source WHERE work_order_id=? AND deleted_flag=0",Long.class,componentPlan.get("work_order_id")));
            assertNotNull(jdbc.queryForObject("SELECT planned_start_time FROM mfg_work_order WHERE work_order_id=?",LocalDateTime.class,componentPlan.get("work_order_id")));
        }
        assertEquals(0,total.compareTo(new BigDecimal("10.0000")));
        assertEquals(5L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE root_work_order_id=? AND work_order_type='COMPONENT'",Long.class,root.getWorkOrderId()));
        // 各补产单不共享同一段产能窗口，后一张从前一张结束之后继续排。
        LocalDateTime previous=null;
        for (Map<String,Object> componentPlan:componentPlans) {
            LocalDateTime start=jdbc.queryForObject("SELECT planned_start_time FROM mfg_work_order WHERE work_order_id=?",LocalDateTime.class,componentPlan.get("work_order_id"));
            if (previous!=null) {
                assertTrue(start.isAfter(previous),"补产单窗口必须依次向后接续");
            }
            previous=start;
        }
        // 组件计划时间不再复制父计划时间。
        LocalDateTime parentStart=jdbc.queryForObject("SELECT planned_start_time FROM mfg_production_plan WHERE plan_id=?",LocalDateTime.class,planId);
        assertNotEquals(parentStart,previous);
    }
    @Test void componentPlanRejectsWhenCalendarDisabledAfterGeneration() {
        service.generateWorkOrder(planId,null,1L);
        Long componentPlan=legacyComponentPlan(0);
        jdbc.update("UPDATE mfg_line_calendar_rule SET status='DISABLE' WHERE line_id=?",rootLine);
        BusinessException error=assertThrows(BusinessException.class,()->plans.releasePlan(componentPlan,1L,null));
        assertTrue(error.getMessage().contains("周历"),error.getMessage());
        assertEquals("DRAFT",plans.getPlanById(componentPlan).getStatus());
    }
    /**
     * 显式构造升级前的独立组件计划，保留历史计划生效校验的兼容回归。新生成默认共享根计划。
     * 组件工单现在一律按整份需求量生成，靠序号区分同一父工单下的多张组件工单。
     */
    private Long legacyComponentPlan(int index) {
        Map<String,Object> child=jdbc.queryForMap("SELECT w.* FROM mfg_work_order w JOIN mfg_production_plan p ON p.plan_id=w.plan_id"
                +" WHERE p.production_order_id=? AND w.work_order_type='COMPONENT' ORDER BY w.work_order_id LIMIT 1 OFFSET ?",orderId,index);
        String no=tag+"-LEGACY-"+index;
        BigDecimal quantity=(BigDecimal)child.get("planned_quantity");
        jdbc.update("INSERT INTO mfg_production_plan(plan_no,production_order_id,line_id,planned_start_time,planned_end_time,planned_quantity,status,plan_type,parent_plan_id,capacity_basis,version,deleted_flag) VALUES (?,?,?,?,?,?,'DRAFT','COMPONENT',?,'legacy compatibility fixture',0,0)",
                no,orderId,rootLine,child.get("planned_start_time"),child.get("planned_end_time"),quantity,planId);
        Long legacy=id("mfg_production_plan","plan_id","plan_no",no);
        jdbc.update("UPDATE mfg_work_order SET plan_id=? WHERE work_order_id=?",legacy,child.get("work_order_id"));
        jdbc.update("UPDATE mfg_supply_source SET plan_id=? WHERE work_order_id=?",legacy,child.get("work_order_id"));
        return legacy;
    }
    private Long generatedChild() {
        service.generateWorkOrder(planId,null,1L);
        return jdbc.queryForObject("SELECT work_order_id FROM mfg_work_order WHERE plan_id=? AND work_order_type='COMPONENT'",Long.class,planId);
    }
    @Test void componentGenerationCannotSilentlyUseAnotherLine() {
        jdbc.update("DELETE FROM mfg_line_product_capability WHERE product_id=? AND line_id=?",componentProduct,rootLine);
        BusinessException e=assertThrows(BusinessException.class,()->service.generateWorkOrder(planId,null,1L));
        assertTrue(e.getMessage().contains("当前计划产线"),e.getMessage());
        assertNoGeneratedRows();
    }
    @Test void sharedPlanComponentReleaseRechecksItsCapacity() {
        Long child=generatedChild();
        jdbc.update("UPDATE factory_line SET daily_capacity=0 WHERE line_id=?",rootLine);
        assertThrows(BusinessException.class,()->service.releaseWorkOrder(child,1L,null));
        assertEquals("DRAFT",service.getWorkOrderById(child).getStatus());
    }
    @Test void sharedPlanComponentReleaseRejectsMissingCalendar() {
        Long child=generatedChild();
        jdbc.update("UPDATE mfg_line_calendar_rule SET status='DISABLE' WHERE line_id=?",rootLine);
        BusinessException e=assertThrows(BusinessException.class,()->service.releaseWorkOrder(child,1L,null));
        assertTrue(e.getMessage().contains("周历"),e.getMessage());
    }
    @Test void sharedPlanComponentReleaseRejectsChangedSupplyQuantity() {
        Long child=generatedChild();
        jdbc.update("UPDATE mfg_work_order SET planned_quantity=7 WHERE work_order_id=?",child);
        assertThrows(BusinessException.class,()->service.releaseWorkOrder(child,1L,null));
    }
    @Test void sharedPlanComponentReleaseRejectsClosedOrder() {
        Long child=generatedChild();
        jdbc.update("UPDATE mfg_production_order SET status='CLOSED' WHERE production_order_id=?",orderId);
        assertThrows(BusinessException.class,()->service.releaseWorkOrder(child,1L,null));
    }
    @Test void sharedPlanComponentCanReleaseWhileItsPlanAndOrderAreRunning() {
        Long child=generatedChild();
        jdbc.update("UPDATE mfg_production_plan SET status='RUNNING' WHERE plan_id=?",planId);
        jdbc.update("UPDATE mfg_production_order SET status='RUNNING' WHERE production_order_id=?",orderId);
        assertEquals("RELEASED",service.releaseWorkOrder(child,1L,null).getStatus());
    }
    private void assertNoGeneratedRows() {
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE product_id IN (?,?)",Long.class,rootProduct,componentProduct));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_production_plan WHERE parent_plan_id=?",Long.class,planId));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order_material WHERE material_id IN (?,?)",Long.class,componentMaterial,rawMaterial));
    }
    private Long material(String suffix) {
        jdbc.update("INSERT INTO mdm_material(material_code,material_name,material_type,quantity_mode,issue_step,status,version,deleted_flag) VALUES (?,?,'SEMI_FINISHED','DISCRETE',1,'ENABLE',0,0)",tag+suffix,"供给验证"+suffix);
        return id("mdm_material","material_id","material_code",tag+suffix);
    }
    private Long product(String suffix,Long material) {
        jdbc.update("INSERT INTO mdm_product(product_code,product_name,material_id,preparation_controlled,status,version,deleted_flag) VALUES (?,?,?,0,'ENABLE',0,0)",tag+suffix,"合成非受控验证产品",material);
        return id("mdm_product","product_id","product_code",tag+suffix);
    }
    private Long bom(String suffix,Long product,String effective) {
        jdbc.update("INSERT INTO mdm_bom(bom_code,product_id,bom_version,effective_date,status,version,deleted_flag) VALUES (?,?,'TEST',?,'RELEASED',0,0)",tag+suffix,product,effective);
        return id("mdm_bom","bom_id","bom_code",tag+suffix);
    }
    private Long route(String suffix,Long product) {
        jdbc.update("INSERT INTO process_route(route_code,route_name,product_id,status,version,deleted_flag) VALUES (?,?,?,'RELEASED',0,0)",tag+suffix,"组件供给验证路线",product);
        Long created=id("process_route","route_id","route_code",tag+suffix);
        // 组件补产要按工序标准工时排窗口，夹具路线必须至少有一道有效工序。
        jdbc.update("INSERT INTO process_route_item(route_id,operation_id,sequence_no,standard_time,resource_type,qc_required,version,deleted_flag) VALUES (?,NULL,1,1.00,'LABOR',0,0,0)",created);
        return created;
    }
    private Long line(String suffix) {
        jdbc.update("INSERT INTO factory_line(line_code,line_name,daily_capacity,status,version,deleted_flag) VALUES (?,?,200,'ENABLE',0,0)",tag+suffix,"组件供给验证产线");
        Long created=id("factory_line","line_id","line_code",tag+suffix);
        // 组件补产要按产线自身周历算可执行窗口，夹具必须给出启用中的班次周历，否则会被明确阻断。
        calendar(created);
        return created;
    }
    /** 给夹具产线配置整周单班周历：每天 08:00-16:00 一个班次，产能系数 1。 */
    private void calendar(Long line) {
        for (int day=1;day<=7;day++) {
            jdbc.update("INSERT INTO mfg_line_calendar_rule(line_id,day_of_week,shift_id,effective_from,effective_to,capacity_factor,status,version,deleted_flag) VALUES (?,?,?,'2026-01-01',NULL,1.0000,'ENABLE',0,0)",line,day,fixtureShift);
        }
    }
    private void capability(Long product,Long line,Long route) {
        capability(product,line,route,1);
    }
    /** 同一组件在多条线上有能力时，优先级必须可区分，否则会被判成"同优先级歧义"。 */
    private void capability(Long product,Long line,Long route,int priority) {
        jdbc.update("INSERT INTO mfg_line_product_capability(product_id,line_id,route_id,priority_level,capacity_factor,status,version,deleted_flag) VALUES (?,?,?,?,1,'ENABLE',0,0)",product,line,route,priority);
    }
    private Long id(String table,String column,String codeColumn,String code) {
        return jdbc.queryForObject("SELECT "+column+" FROM "+table+" WHERE "+codeColumn+"=?",Long.class,code);
    }
}
