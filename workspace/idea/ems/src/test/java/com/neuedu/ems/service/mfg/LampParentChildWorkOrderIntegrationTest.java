package com.neuedu.ems.service.mfg;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.factory.FactoryStation;
import com.neuedu.ems.entity.mdm.MdmBom;
import com.neuedu.ems.entity.mdm.MdmBomItem;
import com.neuedu.ems.entity.mfg.MfgDispatchOrder;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgReportMaterial;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.entity.process.ProcessRoute;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.qms.QmsInspectionResult;
import com.neuedu.ems.entity.qms.QmsInspectionScheme;
import com.neuedu.ems.entity.qms.QmsInspectionSchemeItem;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.factory.FactoryStationService;
import com.neuedu.ems.service.mdm.MdmBomItemService;
import com.neuedu.ems.service.mdm.MdmBomService;
import com.neuedu.ems.service.mdm.ProductParameterService;
import com.neuedu.ems.service.process.ProcessRouteService;
import com.neuedu.ems.service.qms.InspectionStandardReviewService;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import com.neuedu.ems.service.qms.QmsInspectionSchemeItemService;
import com.neuedu.ems.service.qms.QmsInspectionSchemeService;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import com.neuedu.ems.service.wms.WmsLocationService;
import com.neuedu.ems.service.wms.WmsMaterialBatchService;
import com.neuedu.ems.service.wms.WmsOutboundOrderService;
import com.neuedu.ems.service.wms.WmsSalesOutboundService;
import com.neuedu.ems.service.wms.WmsWarehouseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台灯订单从草稿提交排产、编制计划、生效后再生成父子工单。
 * 子件原料只记在组件工单上，重放同一请求键不另建工单。
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_[A-Za-z0-9_]+.*")
@Transactional
class LampParentChildWorkOrderIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionOrderProgressService progress;
    @Autowired MfgProductionOrderService orders;
    @Autowired MfgProductionPlanService plans;
    @Autowired MfgWorkOrderService workOrders;
    @Autowired ProcessRouteService routes;
    @Autowired ProductParameterService parameters;
    @Autowired MdmBomService boms;
    @Autowired MdmBomItemService bomItems;
    @Autowired WmsWarehouseService warehouses;
    @Autowired WmsLocationService locations;
    @Autowired WmsMaterialBatchService batches;
    @Autowired WmsInboundOrderService inbounds;
    @Autowired QmsInspectionOrderService inspections;
    @Autowired QmsInspectionSchemeService schemes;
    @Autowired QmsInspectionSchemeItemService schemeItems;
    @Autowired InspectionStandardReviewService standardReview;
    @Autowired WmsOutboundOrderService outbounds;
    @Autowired WmsSalesOutboundService sales;
    @Autowired MfgDispatchOrderService dispatches;
    @Autowired MfgTaskService tasks;
    @Autowired SupplyAllocationService supplyAllocations;
    @Autowired ComponentHandoverService componentHandover;
    @Autowired com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper materialRequirements;
    @Autowired MfgWorkReportService reports;
    @Autowired FactoryStationService stations;
    @Autowired StockComponentSupplyService stockComponentSupply;

    @Test
    void draftCannotGenerateAndChildrenDoNotRepeatParentMaterials() {
        Long actor = jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
        releaseLampRoutes();
        Long productId = jdbc.queryForObject(
                "SELECT product_id FROM mdm_product WHERE product_code='LAMP-L200' AND deleted_flag=0", Long.class);
        Long lineId = jdbc.queryForObject(
                "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-FINAL' AND deleted_flag=0", Long.class);

        MfgProductionOrder order = new MfgProductionOrder();
        order.setProductId(productId);
        order.setCustomerId(1L);
        order.setOrderQuantity(BigDecimal.ONE);
        order.setDeliveryDate(LocalDate.now().plusDays(30));
        order.setSourceType("MANUAL");
        assertTrue(orders.saveOrder(order));
        MfgProductionPlan request = planRequest(lineId);
        assertThrows(BusinessException.class, () -> plans.generatePlan(order.getProductionOrderId(), request, actor));

        orders.releaseOrder(order.getProductionOrderId(), actor, "提交排产");
        MfgProductionPlan draft = plans.generatePlan(order.getProductionOrderId(), request, actor);
        assertEquals("DRAFT", draft.getStatus());
        assertThrows(BusinessException.class,
                () -> workOrders.generateWorkOrder(draft.getPlanId(), new MfgWorkOrder(), actor));

        plans.releasePlan(draft.getPlanId(), actor, "按交期生效");
        MfgWorkOrder command = new MfgWorkOrder();
        command.setRequestKey("LAMP-L200-" + order.getProductionOrderId());
        command.setRemark("台灯父子工单");
        MfgWorkOrder root = workOrders.generateWorkOrder(draft.getPlanId(), command, actor);
        assertEquals("ROOT", root.getWorkOrderType());
        Long children = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfg_work_order WHERE parent_work_order_id=? AND deleted_flag=0",
                Long.class, root.getWorkOrderId());
        assertTrue(children != null && children > 0);
        assertScheduleDependencies(root);
        LocalDateTime past = LocalDate.now().minusDays(1).atTime(8, 0);
        jdbc.update("UPDATE mfg_production_plan SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",
                past, past.plusHours(1), root.getPlanId());
        jdbc.update("UPDATE mfg_work_order SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",
                past, past.plusHours(1), root.getPlanId());
        MfgWorkOrder rescheduled = workOrders.rescheduleWorkOrders(root.getWorkOrderId(), actor);
        assertScheduleDependencies(rescheduled);
        assertTrue(rescheduled.getPlannedStartTime().isAfter(past.plusDays(1).minusHours(8)));
        assertEquals(children + 1, (long) workOrders.listWorkOrders(null, null, root.getPlanId(), null).size());
        // M18 在总装和组件 BOM 里都有，各自只记本级用量，不能把子件用量再加进父工单。
        assertEquals(0, new BigDecimal("2").compareTo(required(root.getWorkOrderId(), "LAMP-M18")));
        assertEquals(0, new BigDecimal("2").compareTo(requiredOfChild(root.getWorkOrderId(), "LAMP-HEAD", "LAMP-M18")));
        assertEquals(0, new BigDecimal("2").compareTo(requiredOfChild(root.getWorkOrderId(), "LAMP-ARM", "LAMP-M18")));
        assertEquals(0, new BigDecimal("2").compareTo(requiredOfChild(root.getWorkOrderId(), "LAMP-BASE-L200", "LAMP-M18")));
        assertEquals(0L, countMaterial(root.getWorkOrderId(), "LAMP-M01"));
        assertEquals(0L, countChildMaterial(root.getWorkOrderId(), "LAMP-HEAD", "LAMP-M15"));

        MfgWorkOrder replayed = workOrders.generateWorkOrder(draft.getPlanId(), command, actor);
        assertEquals(root.getWorkOrderId(), replayed.getWorkOrderId());
        assertEquals(children, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfg_work_order WHERE parent_work_order_id=? AND deleted_flag=0",
                Long.class, root.getWorkOrderId()));
        MfgWorkOrder changed = new MfgWorkOrder();
        changed.setRequestKey(command.getRequestKey());
        changed.setRemark("改了摘要");
        assertThrows(BusinessException.class,
                () -> workOrders.generateWorkOrder(draft.getPlanId(), changed, actor));
    }

    @Test
    void runningPlanReschedulesOnlyDraftRemainderAndPreservesExecution() {
        Long actor = schedulingActor();
        MfgWorkOrder root = draftFamilyForReschedule(actor);
        List<MfgWorkOrder> children = workOrders.listWorkOrders(null,null,root.getPlanId(),null).stream()
                .filter(w -> root.getWorkOrderId().equals(w.getParentWorkOrderId())).toList();
        assertTrue(children.size() >= 3);
        Long running = children.get(0).getWorkOrderId(), finished = children.get(1).getWorkOrderId();
        Long pending = children.get(2).getWorkOrderId();
        LocalDateTime past = LocalDate.now().minusDays(4).atTime(8,0);
        LocalDateTime occupied = LocalDateTime.now().withNano(0).plusHours(6);
        jdbc.update("UPDATE mfg_production_plan SET status='RUNNING',planned_start_time=?,planned_end_time=? WHERE plan_id=?",past.minusDays(1),occupied,root.getPlanId());
        jdbc.update("UPDATE mfg_work_order SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",past,past.plusHours(1),root.getPlanId());
        jdbc.update("UPDATE mfg_work_order SET status='RUNNING',planned_end_time=? WHERE work_order_id=?",occupied.minusHours(1),running);
        jdbc.update("UPDATE mfg_work_order SET status='FINISHED',completed_quantity=planned_quantity WHERE work_order_id=?",finished);
        jdbc.update("INSERT INTO mfg_dispatch_order(dispatch_no,work_order_id,planned_quantity,due_time,status,version,deleted_flag) VALUES (?,?,1,?,'RELEASED',0,0)",
                "MIXED-"+root.getWorkOrderId(),running,occupied);
        var runningBefore = jdbc.queryForMap("SELECT * FROM mfg_work_order WHERE work_order_id=?",running);
        var finishedBefore = jdbc.queryForMap("SELECT * FROM mfg_work_order WHERE work_order_id=?",finished);
        var dispatchBefore = jdbc.queryForList("SELECT * FROM mfg_dispatch_order WHERE work_order_id=?",running);
        var materialsBefore = jdbc.queryForList("SELECT * FROM mfg_work_order_material WHERE work_order_id IN (?,?,?,?) ORDER BY requirement_id",root.getWorkOrderId(),running,finished,pending);
        Integer logsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE operation_type='RESCHEDULE_WORK_ORDER' AND biz_id IN (?,?)",Integer.class,running,finished);
        var result = workOrders.rescheduleWorkOrders(pending,actor);
        MfgWorkOrder shifted = workOrders.getWorkOrderById(pending);
        assertTrue(!shifted.getPlannedStartTime().isBefore(occupied),"避开在制工单的派工窗口");
        assertTrue(!result.getPlannedStartTime().isBefore(shifted.getPlannedEndTime()),"整机必须等组件完成");
        assertEquals(runningBefore,jdbc.queryForMap("SELECT * FROM mfg_work_order WHERE work_order_id=?",running));
        assertEquals(finishedBefore,jdbc.queryForMap("SELECT * FROM mfg_work_order WHERE work_order_id=?",finished));
        assertEquals(dispatchBefore,jdbc.queryForList("SELECT * FROM mfg_dispatch_order WHERE work_order_id=?",running));
        assertEquals(materialsBefore,jdbc.queryForList("SELECT * FROM mfg_work_order_material WHERE work_order_id IN (?,?,?,?) ORDER BY requirement_id",root.getWorkOrderId(),running,finished,pending));
        assertEquals("RUNNING",plans.getPlanById(root.getPlanId()).getStatus());
        assertEquals(past.minusDays(1),plans.getPlanById(root.getPlanId()).getPlannedStartTime());
        assertEquals(logsBefore,jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE operation_type='RESCHEDULE_WORK_ORDER' AND biz_id IN (?,?)",Integer.class,running,finished));
        String productCode = jdbc.queryForObject("SELECT product_code FROM mdm_product WHERE product_id=?",String.class,shifted.getProductId());
        confirmRequiredParameters(productCode);
        releaseFinalScheme(productCode);
        assertEquals("RELEASED",workOrders.releaseWorkOrder(pending,actor,"重排后继续下达").getStatus());
    }

    @Test
    void overdueRunningWindowBlocksReschedulingWithoutGuessingCapacity() {
        Long actor = schedulingActor(); MfgWorkOrder root = draftFamilyForReschedule(actor);
        MfgWorkOrder child = workOrders.listWorkOrders(null,null,root.getPlanId(),null).stream()
                .filter(w -> root.getWorkOrderId().equals(w.getParentWorkOrderId())).findFirst().orElseThrow();
        jdbc.update("UPDATE mfg_production_plan SET status='RUNNING' WHERE plan_id=?",root.getPlanId());
        jdbc.update("UPDATE mfg_work_order SET status='RUNNING',planned_end_time=? WHERE work_order_id=?",LocalDateTime.now().minusDays(1),child.getWorkOrderId());
        var before = jdbc.queryForList("SELECT * FROM mfg_work_order WHERE plan_id=? ORDER BY work_order_id",root.getPlanId());
        BusinessException error = assertThrows(BusinessException.class,()->workOrders.rescheduleWorkOrders(root.getWorkOrderId(),actor));
        assertTrue(error.getMessage().contains("执行窗口已过期"));
        assertEquals(before,jdbc.queryForList("SELECT * FROM mfg_work_order WHERE plan_id=? ORDER BY work_order_id",root.getPlanId()));
    }

    @Test
    void draftWithExecutionHistoryAndClosedPlansCannotBeRescheduled() {
        Long actor = schedulingActor(); MfgWorkOrder root = draftFamilyForReschedule(actor);
        var before = jdbc.queryForList("SELECT * FROM mfg_work_order WHERE plan_id=? ORDER BY work_order_id",root.getPlanId());
        jdbc.update("INSERT INTO mfg_task(task_no,work_order_id,planned_quantity,status,version,deleted_flag) VALUES (?,?,1,'WAIT_START',0,0)","STALE-"+root.getWorkOrderId(),root.getWorkOrderId());
        BusinessException error = assertThrows(BusinessException.class,()->workOrders.rescheduleWorkOrders(root.getWorkOrderId(),actor));
        assertTrue(error.getMessage().contains("派工或任务记录"));
        assertEquals(before,jdbc.queryForList("SELECT * FROM mfg_work_order WHERE plan_id=? ORDER BY work_order_id",root.getPlanId()));
        jdbc.update("UPDATE mfg_production_plan SET status='CLOSED' WHERE plan_id=?",root.getPlanId());
        assertThrows(BusinessException.class,()->workOrders.rescheduleWorkOrders(root.getWorkOrderId(),actor));
    }

    @Test
    void reschedulingCascadesAcrossFollowingDraftFamilies() {
        Long actor=schedulingActor();
        MfgWorkOrder first=draftFamilyForReschedule(actor), second=draftFamilyForReschedule(actor), third=draftFamilyForReschedule(actor);
        LocalDateTime past=LocalDateTime.now().minusDays(3).withNano(0), soon=LocalDateTime.now().plusMinutes(2).withNano(0);
        setFamilyWindow(first,past,past.plusHours(1));setFamilyWindow(second,soon,soon.plusMinutes(1));setFamilyWindow(third,soon.plusMinutes(1),soon.plusMinutes(2));
        workOrders.rescheduleWorkOrders(first.getWorkOrderId(),actor);
        var aPlan=plans.getPlanById(first.getPlanId());var bPlan=plans.getPlanById(second.getPlanId());var cPlan=plans.getPlanById(third.getPlanId());
        assertTrue(!bPlan.getPlannedStartTime().isBefore(aPlan.getPlannedEndTime()));
        assertTrue(!cPlan.getPlannedStartTime().isBefore(bPlan.getPlannedEndTime()));
        assertScheduleDependencies(workOrders.getWorkOrderById(second.getWorkOrderId()));
        assertScheduleDependencies(workOrders.getWorkOrderById(third.getWorkOrderId()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE operation_type='CASCADE_RESCHEDULE_PLAN' AND biz_id IN (?,?)",Integer.class,second.getPlanId(),third.getPlanId()));
        assertTrue(workOrders.listWorkOrders(null,null,second.getPlanId(),null).stream().allMatch(w->"DRAFT".equals(w.getStatus())));
    }

    @Test
    void followingPlanWithoutWorkOrdersMovesByCalendarWithoutGeneratingWorkOrders() {
        Long actor=schedulingActor();MfgWorkOrder first=draftFamilyForReschedule(actor), following=draftFamilyForReschedule(actor);
        LocalDateTime past=LocalDateTime.now().minusDays(3).withNano(0),soon=LocalDateTime.now().plusMinutes(2).withNano(0);
        setFamilyWindow(first,past,past.plusHours(1));setFamilyWindow(following,soon,soon.plusMinutes(1));
        jdbc.update("UPDATE mfg_work_order SET deleted_flag=1 WHERE plan_id=?",following.getPlanId());
        jdbc.update("UPDATE mfg_production_order SET status='RUNNING' WHERE production_order_id=?",plans.getPlanById(following.getPlanId()).getProductionOrderId());
        workOrders.rescheduleWorkOrders(first.getWorkOrderId(),actor);
        assertTrue(!plans.getPlanById(following.getPlanId()).getPlannedStartTime().isBefore(plans.getPlanById(first.getPlanId()).getPlannedEndTime()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE plan_id=? AND deleted_flag=0",Integer.class,following.getPlanId()));
    }

    @Test
    void runningFollowingPlanIsProtectedInsteadOfSilentlyShifted() {
        Long actor=schedulingActor();MfgWorkOrder first=draftFamilyForReschedule(actor), following=draftFamilyForReschedule(actor);
        LocalDateTime now=LocalDateTime.now().withNano(0);
        setFamilyWindow(first,now.minusDays(3),now.minusDays(3).plusHours(1));
        setFamilyWindow(following,now.minusMinutes(1),now.plusHours(6));
        jdbc.update("UPDATE mfg_production_plan SET status='RUNNING' WHERE plan_id=?",following.getPlanId());
        jdbc.update("UPDATE mfg_work_order SET status='RUNNING' WHERE work_order_id=?",following.getWorkOrderId());
        var before=jdbc.queryForMap("SELECT * FROM mfg_production_plan WHERE plan_id=?",following.getPlanId());
        var rowsBefore=jdbc.queryForList("SELECT * FROM mfg_work_order WHERE plan_id=? ORDER BY work_order_id",following.getPlanId());
        workOrders.rescheduleWorkOrders(first.getWorkOrderId(),actor);
        assertEquals(before,jdbc.queryForMap("SELECT * FROM mfg_production_plan WHERE plan_id=?",following.getPlanId()));
        assertEquals(rowsBefore,jdbc.queryForList("SELECT * FROM mfg_work_order WHERE plan_id=? ORDER BY work_order_id",following.getPlanId()));
        assertTrue(!plans.getPlanById(first.getPlanId()).getPlannedStartTime().isBefore(now.plusHours(6)));
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager schedulingTransactions;

    @Test
    void failedFollowingPlanRollsBackCurrentAndEntireCascade() {
        Long actor=schedulingActor();MfgWorkOrder first=draftFamilyForReschedule(actor), following=draftFamilyForReschedule(actor);
        LocalDateTime now=LocalDateTime.now().withNano(0);
        setFamilyWindow(first,now.minusDays(3),now.minusDays(3).plusHours(1));
        setFamilyWindow(following,now.plusMinutes(2),now.plusMinutes(3));
        jdbc.update("UPDATE mfg_work_order SET deleted_flag=1 WHERE plan_id=?",following.getPlanId());
        jdbc.update("UPDATE mfg_production_plan SET planned_quantity=0 WHERE plan_id=?",following.getPlanId());
        var before=jdbc.queryForList("SELECT * FROM mfg_work_order WHERE plan_id IN (?,?) ORDER BY work_order_id",first.getPlanId(),following.getPlanId());
        var plansBefore=jdbc.queryForList("SELECT * FROM mfg_production_plan WHERE plan_id IN (?,?) ORDER BY plan_id",first.getPlanId(),following.getPlanId());
        var nested=new org.springframework.transaction.support.TransactionTemplate(schedulingTransactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThrows(BusinessException.class,()->nested.execute(status->workOrders.rescheduleWorkOrders(first.getWorkOrderId(),actor)));
        assertEquals(before,jdbc.queryForList("SELECT * FROM mfg_work_order WHERE plan_id IN (?,?) ORDER BY work_order_id",first.getPlanId(),following.getPlanId()));
        assertEquals(plansBefore,jdbc.queryForList("SELECT * FROM mfg_production_plan WHERE plan_id IN (?,?) ORDER BY plan_id",first.getPlanId(),following.getPlanId()));
    }

    private void setFamilyWindow(MfgWorkOrder root,LocalDateTime start,LocalDateTime end) {
        jdbc.update("UPDATE mfg_production_plan SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",start,end,root.getPlanId());
        jdbc.update("UPDATE mfg_work_order SET planned_start_time=?,planned_end_time=? WHERE plan_id=?",start,end,root.getPlanId());
    }

    private Long schedulingActor() {
        return jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0",Long.class);
    }

    private boolean rescheduleBaselinePrepared;

    private MfgWorkOrder withdrawnFrozenAssembly(Long actor) {
        MfgWorkOrder root=draftFamilyForReschedule(actor);
        confirmRequiredParameters("LAMP-L200");releaseFinalScheme("LAMP-L200");
        workOrders.releaseWorkOrder(root.getWorkOrderId(),actor,"首次下达冻结");
        workOrders.withdrawWorkOrder(root.getWorkOrderId(),actor,"未执行撤回后调整安排");
        return workOrders.getWorkOrderById(root.getWorkOrderId());
    }

    @Test
    void withdrawnAssemblyReusesOriginalSnapshotWhenReleasedAgain() {
        Long actor=schedulingActor();MfgWorkOrder root=withdrawnFrozenAssembly(actor);
        assertEquals("DRAFT",root.getStatus());
        String snapshot=root.getSnapshotJson(),hash=root.getSnapshotHash();
        LocalDateTime frozenAt=root.getSnapshotTime();
        jdbc.update("UPDATE process_route_item SET standard_time=standard_time+1 WHERE route_id=? AND deleted_flag=0",root.getRouteId());
        MfgWorkOrder released=workOrders.releaseWorkOrder(root.getWorkOrderId(),actor,"沿用冻结依据再次下达");
        assertEquals("RELEASED",released.getStatus());
        assertEquals(snapshot,released.getSnapshotJson());assertEquals(hash,released.getSnapshotHash());
        assertEquals(frozenAt,released.getSnapshotTime());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sys_status_change_log WHERE biz_type='MFG_WORK_ORDER' AND biz_id=? AND change_reason='RELEASE_WORK_ORDER'",Integer.class,root.getWorkOrderId()));
    }

    @Test
    void withdrawnAssemblyWithChangedQuantityCannotReuseFrozenBasis() {
        Long actor=schedulingActor();MfgWorkOrder root=withdrawnFrozenAssembly(actor);
        var mapper=org.springframework.test.util.AopTestUtils.getTargetObject(workOrders);
        var orders=(com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper)org.springframework.test.util.ReflectionTestUtils.getField(mapper,"baseMapper");
        MfgWorkOrder change=new MfgWorkOrder();change.setWorkOrderId(root.getWorkOrderId());change.setVersion(root.getVersion());
        change.setPlannedQuantity(root.getPlannedQuantity().add(BigDecimal.ONE));orders.updateById(change);
        assertTrue(assertThrows(BusinessException.class,()->workOrders.releaseWorkOrder(root.getWorkOrderId(),actor,"数量变化不能沿用"))
                .getMessage().contains("冻结生产数量"));
        assertEquals("DRAFT",workOrders.getWorkOrderById(root.getWorkOrderId()).getStatus());
        assertEquals(root.getSnapshotJson(),workOrders.getWorkOrderById(root.getWorkOrderId()).getSnapshotJson());
    }

    @Test
    void withdrawnAssemblyWithBrokenHashCannotBeReleasedAgain() {
        Long actor=schedulingActor();MfgWorkOrder root=withdrawnFrozenAssembly(actor);
        var mapper=org.springframework.test.util.AopTestUtils.getTargetObject(workOrders);
        var orders=(com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper)org.springframework.test.util.ReflectionTestUtils.getField(mapper,"baseMapper");
        MfgWorkOrder change=new MfgWorkOrder();change.setWorkOrderId(root.getWorkOrderId());change.setVersion(root.getVersion());
        change.setSnapshotHash("broken-hash");orders.updateById(change);
        assertTrue(assertThrows(BusinessException.class,()->workOrders.releaseWorkOrder(root.getWorkOrderId(),actor,"损坏不能沿用"))
                .getMessage().contains("摘要不一致"));
        assertEquals("DRAFT",workOrders.getWorkOrderById(root.getWorkOrderId()).getStatus());
    }

    private MfgWorkOrder draftFamilyForReschedule(Long actor) {
        if (!rescheduleBaselinePrepared) {
            releaseLampRoutes(); prepareL200EngineeringBaseline(); rescheduleBaselinePrepared=true;
        }
        Long productId = jdbc.queryForObject("SELECT product_id FROM mdm_product WHERE product_code='LAMP-L200' AND deleted_flag=0",Long.class);
        Long lineId = jdbc.queryForObject("SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-FINAL' AND deleted_flag=0",Long.class);
        MfgProductionOrder order = new MfgProductionOrder(); order.setProductId(productId);order.setCustomerId(1L);
        order.setOrderQuantity(BigDecimal.ONE);order.setDeliveryDate(LocalDate.now().plusDays(30));order.setSourceType("MANUAL");
        assertTrue(orders.saveOrder(order));orders.releaseOrder(order.getProductionOrderId(),actor,"重排回归");
        MfgProductionPlan plan = plans.generatePlan(order.getProductionOrderId(),planRequest(lineId),actor);
        plans.releasePlan(plan.getPlanId(),actor,"重排回归");
        return workOrders.generateWorkOrder(plan.getPlanId(),new MfgWorkOrder(),actor);
    }

    private void assertScheduleDependencies(MfgWorkOrder root) {
        var family = workOrders.listWorkOrders(null, null, root.getPlanId(), null).stream()
                .sorted(java.util.Comparator.comparing(MfgWorkOrder::getPlannedStartTime)).toList();
        assertEquals(root.getWorkOrderId(), family.get(family.size() - 1).getWorkOrderId());
        for (int index = 0; index < family.size(); index++) {
            var row = family.get(index);
            assertTrue(row.getPlannedEndTime().isAfter(row.getPlannedStartTime()));
            if (index > 0) assertTrue(!row.getPlannedStartTime().isBefore(family.get(index - 1).getPlannedEndTime()),
                    "同线工单不得重叠，整机必须等待组件完成");
            if (row.getParentWorkOrderId() != null) {
                var parent = family.stream().filter(w -> w.getWorkOrderId().equals(row.getParentWorkOrderId())).findFirst().orElseThrow();
                assertTrue(!parent.getPlannedStartTime().isBefore(row.getPlannedEndTime()));
            }
        }
        var plan = plans.getPlanById(root.getPlanId());
        assertEquals(family.get(0).getPlannedStartTime(), plan.getPlannedStartTime());
        assertEquals(root.getPlannedEndTime(), plan.getPlannedEndTime());
    }

    @Test
    void assemblyOrderKeepsChildrenAndIssuesToLineSideOnlyAfterKit() {
        String tag = "ASM" + System.nanoTime();
        Long actor = jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
        releaseLampRoutes();
        prepareL200EngineeringBaseline();
        Long productId = jdbc.queryForObject(
                "SELECT product_id FROM mdm_product WHERE product_code='LAMP-L200' AND deleted_flag=0", Long.class);
        Long lineId = jdbc.queryForObject(
                "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-FINAL' AND deleted_flag=0", Long.class);
        MfgProductionOrder order = new MfgProductionOrder();
        order.setProductId(productId);
        order.setCustomerId(1L);
        order.setOrderQuantity(BigDecimal.ONE);
        order.setDeliveryDate(LocalDate.now().plusDays(30));
        order.setSourceType("MANUAL");
        assertTrue(orders.saveOrder(order));
        orders.releaseOrder(order.getProductionOrderId(), actor, "提交排产");
        MfgProductionPlan request = planRequest(lineId);
        MfgProductionPlan draft = plans.generatePlan(order.getProductionOrderId(), request, actor);
        plans.releasePlan(draft.getPlanId(), actor, "按交期生效");
        MfgWorkOrder command = new MfgWorkOrder();
        command.setRequestKey(tag);
        command.setRemark("总装齐套");
        MfgWorkOrder root = workOrders.generateWorkOrder(draft.getPlanId(), command, actor);
        Long children = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfg_work_order WHERE parent_work_order_id=? AND deleted_flag=0",
                Long.class, root.getWorkOrderId());
        assertTrue(children != null && children > 0);
        Long componentLines = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mfg_work_order_material
                WHERE work_order_id=? AND supply_type='COMPONENT_WORK_ORDER' AND deleted_flag=0
                """, Long.class, root.getWorkOrderId());
        assertTrue(componentLines != null && componentLines > 0);

        WmsWarehouse raw = warehouse(tag + "R", "RAW");
        WmsLocation rawLocation = location(raw, tag + "RL");
        workOrders.releaseWorkOrder(root.getWorkOrderId(), actor, "下达总装");
        BusinessException shortKit = assertThrows(BusinessException.class,
                () -> outbounds.generateMaterialIssue(root.getWorkOrderId(), raw.getWarehouseId(), actor, "缺料"));
        assertTrue(shortKit.getMessage().contains("未齐套"));

        stockRequirements(tag, raw, rawLocation, root.getWorkOrderId(), actor);
        WmsOutboundOrder issued = outbounds.generateMaterialIssue(
                root.getWorkOrderId(), raw.getWarehouseId(), actor, "齐套领料", tag + "-ISSUE");
        WmsWarehouse line = warehouse(tag + "L", "LINE_SIDE");
        WmsLocation lineLocation = location(line, tag + "LL");
        outbounds.transferMaterialIssueToLineSide(
                issued.getOutboundId(), lineLocation.getLocationId(), actor, "转线边", tag + "-MOVE");
        assertEquals(children, jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfg_work_order WHERE parent_work_order_id=? AND deleted_flag=0",
                Long.class, root.getWorkOrderId()));
        assertEquals(1, countIssues(root.getWorkOrderId()));
        assertTrue(movementCount(issued.getOutboundId()) >= 2);
        assertEquals(0, movementNet(issued.getOutboundId()).compareTo(BigDecimal.ZERO));
        outbounds.transferMaterialIssueToLineSide(
                issued.getOutboundId(), lineLocation.getLocationId(), actor, "转线边", tag + "-MOVE");
        assertEquals(1, countIssues(root.getWorkOrderId()));
    }

    @Test
    void headChildOutputFeedsTheAssemblyIssue() {
        String tag = "FEED" + System.nanoTime();
        Long actor = jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
        MfgWorkOrder root = openReleasedAssembly(tag, actor);
        Long headId = jdbc.queryForObject("""
                SELECT child.work_order_id FROM mfg_work_order child
                JOIN mdm_product p ON p.product_id=child.product_id
                WHERE child.parent_work_order_id=? AND p.product_code='LAMP-HEAD' AND child.deleted_flag=0
                ORDER BY child.work_order_id LIMIT 1
                """, Long.class, root.getWorkOrderId());
        confirmRequiredParameters("LAMP-HEAD");
        releaseFinalScheme("LAMP-HEAD");
        Long headPlanId = jdbc.queryForObject(
                "SELECT plan_id FROM mfg_work_order WHERE work_order_id=?", Long.class, headId);
        if ("DRAFT".equals(plans.getPlanById(headPlanId).getStatus())) { plans.releasePlan(headPlanId, actor, "下达灯头计划"); }
        workOrders.releaseWorkOrder(headId, actor, "下达灯头");
        WmsWarehouse raw = warehouse(tag + "HR", "RAW");
        WmsLocation rawLocation = location(raw, tag + "HRL");
        stockRequirements(tag + "H", raw, rawLocation, headId, actor, "STOCK");
        WmsOutboundOrder headIssue = outbounds.generateMaterialIssue(
                headId, raw.getWarehouseId(), actor, "灯头领料", tag + "-HISSUE");
        WmsWarehouse headLine = warehouse(tag + "HL", "LINE_SIDE");
        WmsLocation headLineLocation = location(headLine, tag + "HLL");
        outbounds.transferMaterialIssueToLineSide(
                headIssue.getOutboundId(), headLineLocation.getLocationId(), actor, "灯头转线边", tag + "-HMOVE");
        finishHead(tag, actor, headId);
        Long headMaterial = jdbc.queryForObject(
                "SELECT material_id FROM mdm_product WHERE product_code='LAMP-HEAD' AND deleted_flag=0", Long.class);
        stockRequirements(tag + "P", raw, rawLocation, root.getWorkOrderId(), actor, "STOCK");
        stockComponentMaterialsExcept(tag + "C", raw, rawLocation, root.getWorkOrderId(), actor, headMaterial);
        WmsOutboundOrder parentIssue = outbounds.generateMaterialIssue(
                root.getWorkOrderId(), raw.getWarehouseId(), actor, "总装领组件", tag + "-PISSUE");
        Long fromChild = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wms_outbound_detail d
                JOIN wms_material_batch b ON b.batch_id=d.batch_id
                JOIN trace_product_lot lot ON lot.product_lot_id=b.source_order_id
                WHERE d.outbound_id=? AND d.material_id=? AND d.deleted_flag=0
                  AND lot.work_order_id=? AND lot.deleted_flag=0
                """, Long.class, parentIssue.getOutboundId(), headMaterial, headId);
        assertTrue(fromChild != null && fromChild > 0);
        Integer consumed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wms_stock_movement
                WHERE work_order_id=? AND movement_type='CONSUME' AND source_order_type='WORK_REPORT' AND deleted_flag=0
                """, Integer.class, headId);
        assertTrue(consumed != null && consumed > 0);
    }

    @Test
    void componentOutputFeedsAssemblyThroughShipment() {
        String tag = "SHIP" + System.nanoTime();
        Long actor = jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
        MfgWorkOrder root = openReleasedAssembly(tag, actor);
        Long productionOrderId = jdbc.queryForObject("""
                SELECT p.production_order_id FROM mfg_production_plan p
                JOIN mfg_work_order w ON w.plan_id=p.plan_id WHERE w.work_order_id=?
                """, Long.class, root.getWorkOrderId());
        WmsWarehouse raw = warehouse(tag + "R", "RAW");
        WmsLocation rawLocation = location(raw, tag + "RL");
        Map<String, Long> children = new HashMap<>();
        for (String productCode : List.of("LAMP-HEAD", "LAMP-ARM", "LAMP-BASE-L200")) {
            children.put(productCode, releaseComponent(actor, root.getWorkOrderId(), productCode));
        }
        for (String productCode : List.of("LAMP-HEAD", "LAMP-ARM", "LAMP-BASE-L200")) {
            makeComponent(tag, actor, children.get(productCode), productCode, raw, rawLocation);
        }
        // 组件已入半成品仓且预留给整机，选原料仓领料时仍必须能使用跨仓预留，不能误报缺料。
        var componentRequirements = materialRequirements.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial>()
                        .eq(com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial::getWorkOrderId, root.getWorkOrderId())
                        .eq(com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial::getSupplyType, "COMPONENT_WORK_ORDER")
                        .eq(com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial::getDeletedFlag, 0));
        assertEquals(3, componentRequirements.size());
        for (var requirement : componentRequirements) {
            var reservation = supplyAllocations.reserve(requirement, requirement.getRequiredQuantity(),
                    tag + "-RESERVE-" + requirement.getRequirementId(), actor, java.time.LocalDateTime.now());
            assertEquals(0, reservation.shortage().signum());
            assertTrue(reservation.reserved().signum() > 0);
        }
        stockRequirements(tag + "P", raw, rawLocation, root.getWorkOrderId(), actor, "STOCK");
        WmsOutboundOrder parentIssue = outbounds.generateMaterialIssue(
                root.getWorkOrderId(), raw.getWarehouseId(), actor, "总装领组件", tag + "-PISSUE");
        for (Map.Entry<String, Long> child : children.entrySet()) {
            Long materialId = jdbc.queryForObject(
                    "SELECT material_id FROM mdm_product WHERE product_code=? AND deleted_flag=0",
                    Long.class, child.getKey());
            Long fromChild = childLotOnIssue(parentIssue.getOutboundId(), materialId, child.getValue());
            assertTrue(fromChild != null && fromChild > 0, child.getKey());
            assertTrue(consumeCount(child.getValue()) > 0, child.getKey());
        }
        WmsWarehouse line = warehouse(tag + "L", "LINE_SIDE");
        WmsLocation lineLocation = location(line, tag + "LL");
        for(Long check:jdbc.queryForList("SELECT inspection_id FROM wms_outbound_detail WHERE outbound_id=? AND inspection_id IS NOT NULL AND deleted_flag=0",Long.class,parentIssue.getOutboundId()))
            assertTrue(submitFinalInspection(check,actor,"组件仓库领用复验合格"));
        outbounds.transferMaterialIssueToLineSide(
                parentIssue.getOutboundId(), lineLocation.getLocationId(), actor, "总装转线边", tag + "-PMOVE");
        WmsLocation finishedLocation = finishExecution(
                tag + "F", actor, root.getWorkOrderId(), "LAMP-TEAM-FINAL", "FINISHED", "成品入库");
        assertTrue(consumeCount(root.getWorkOrderId()) > 0);
        var outbound = sales.generateSalesOutbound(
                productionOrderId, 1L, finishedLocation.getWarehouseId(), finishedLocation.getLocationId(),
                "总装发货", actor);
        assertEquals(root.getWorkOrderId(), outbound.getWorkOrderId(),
                "成品发货必须关联订单产品工单，不能因组件工单编号更大而关联组件");
        assertThrows(BusinessException.class,
                () -> sales.confirmSalesOutbound(outbound.getOutboundId(), actor, "未检不能发"));
        Long inspectionId = sales.createOutgoingInspection(outbound.getOutboundId(), actor);
        assertEquals(root.getWorkOrderId(), jdbc.queryForObject(
                "SELECT work_order_id FROM qms_inspection_order WHERE inspection_id=?",
                Long.class, inspectionId));
        assertTrue(submitFinalInspection(inspectionId, actor, "出货合格"));
        assertTrue(sales.confirmSalesOutbound(outbound.getOutboundId(), actor, "确认发货"));
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT audit_status FROM wms_outbound_order WHERE outbound_id=?",
                String.class, outbound.getOutboundId()));
    }

    @Test
    void sameLineComponentsFeedAssemblyDirectlyAndOnlyAccessoriesNeedWarehouseIssue() {
        String tag = "DIRECT" + System.nanoTime();
        Long actor = 1L;
        MfgWorkOrder root = openReleasedAssembly(tag, actor);
        WmsWarehouse raw = warehouse(tag + "R", "RAW");
        WmsLocation rawLocation = location(raw, tag + "RL");
        Map<String,Long> children = new HashMap<>();
        for (String code : List.of("LAMP-HEAD", "LAMP-ARM", "LAMP-BASE-L200"))
            children.put(code, releaseComponent(actor, root.getWorkOrderId(), code));
        // 组件还未完成也可以先领其他原材料，申请中不算实际到位。
        stockRequirements(tag + "P", raw, rawLocation, root.getWorkOrderId(), actor, "STOCK");
        WmsOutboundOrder issue = outbounds.generateMaterialIssue(root.getWorkOrderId(), raw.getWarehouseId(), actor, "仅领装配配件", tag + "-ISSUE");
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0", Integer.class, issue.getOutboundId()));
        assertEquals(0, jdbc.queryForObject("SELECT SUM(issued_quantity) FROM mfg_work_order_material WHERE work_order_id=? AND deleted_flag=0", BigDecimal.class, root.getWorkOrderId()).signum());
        WmsWarehouse line = warehouse(tag + "L", "LINE_SIDE");
        WmsLocation lineLocation = location(line, tag + "LL");
        outbounds.transferMaterialIssueToLineSide(issue.getOutboundId(), lineLocation.getLocationId(), actor, "配件转线边", tag + "-MOVE");
        enablePlanStations(root.getWorkOrderId());
        Long team = jdbc.queryForObject("SELECT team_id FROM org_team WHERE team_code='LAMP-TEAM-FINAL' AND deleted_flag=0", Long.class);
        com.neuedu.ems.support.DispatchScheduleTestData.coverWorkOrder(jdbc, root.getWorkOrderId(), team);
        MfgDispatchOrder premature = new MfgDispatchOrder(); premature.setTeamId(team);
        assertThrows(BusinessException.class, () -> dispatches.generateDispatchOrder(root.getWorkOrderId(), premature, actor));
        for (var child : children.entrySet()) {
            makeComponent(tag, actor, child.getValue(), child.getKey(), raw, rawLocation, true);
            assertTrue(consumeCount(child.getValue()) > 0);
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order_material WHERE work_order_id=? AND supply_type='COMPONENT_WORK_ORDER' AND issued_quantity=required_quantity AND deleted_flag=0", Integer.class, root.getWorkOrderId()));
        assertEquals(0, componentHandover.synchronize(root.getWorkOrderId(), actor).signum());
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM wms_stock_movement WHERE movement_type='COMPONENT_HANDOVER' AND work_order_id IN (?,?,?,?) AND deleted_flag=0", Integer.class, root.getWorkOrderId(), children.get("LAMP-HEAD"), children.get("LAMP-ARM"), children.get("LAMP-BASE-L200")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wms_inbound_order i JOIN trace_product_lot l ON l.product_lot_id=i.source_order_id WHERE i.source_order_type='PRODUCT_LOT' AND l.work_order_id IN (?,?,?) AND i.deleted_flag=0", Integer.class, children.get("LAMP-HEAD"), children.get("LAMP-ARM"), children.get("LAMP-BASE-L200")));
        Long lot = jdbc.queryForObject("SELECT product_lot_id FROM trace_product_lot WHERE work_order_id=? AND deleted_flag=0", Long.class, children.get("LAMP-HEAD"));
        assertThrows(BusinessException.class, () -> inbounds.generateProductionInbound(lot, raw.getWarehouseId(), rawLocation.getLocationId(), actor, "禁止重复入库"));
        finishExecution(tag + "F", actor, root.getWorkOrderId(), "LAMP-TEAM-FINAL", "FINISHED", "成品入库");
        assertTrue(consumeCount(root.getWorkOrderId()) > 0);
        assertEquals("FINISHED", workOrders.getWorkOrderById(root.getWorkOrderId()).getStatus());
    }

    @Test void storedComponentsAreInspectedAndFeedAssemblyWithoutComponentProduction() {
        String tag="STOCKALL"+System.nanoTime(); Long actor=1L;
        MfgWorkOrder root=openReleasedAssembly(tag,actor);
        WmsWarehouse semi=warehouse(tag+"S","SEMI_FINISHED");
        WmsLocation shelf=location(semi,tag+"SL");
        stockComponentRows(tag,semi,shelf,root.getWorkOrderId(),actor);
        // 半成品由各自的组件工单整份领用：出库单挂在组件工单下，整机工单只领原材料。
        Map<String,Long> components=releaseComponents(actor,root.getWorkOrderId(),
                List.of("LAMP-HEAD","LAMP-ARM","LAMP-BASE-L200"));
        WmsWarehouse line=warehouse(tag+"L","LINE_SIDE"); WmsLocation target=location(line,tag+"LL");
        int index=0;
        for (var entry:components.entrySet()) {
            String key=tag+"-"+index++;
            WmsOutboundOrder issue=outbounds.generateMaterialIssue(entry.getValue(),semi.getWarehouseId(),actor,"库存组件",key);
            assertEquals(entry.getValue(),issue.getWorkOrderId());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_detail WHERE outbound_id=? AND inspection_id IS NOT NULL AND deleted_flag=0",Integer.class,issue.getOutboundId()));
            if (index==1) {
                BigDecimal before=jdbc.queryForObject("SELECT SUM(available_quantity+locked_quantity+inspection_quantity) FROM wms_inventory WHERE deleted_flag=0",BigDecimal.class);
                assertThrows(BusinessException.class,()->outbounds.validateLineSideTransfer(issue.getOutboundId(),target.getLocationId()));
                assertEquals(0,before.compareTo(jdbc.queryForObject("SELECT SUM(available_quantity+locked_quantity+inspection_quantity) FROM wms_inventory WHERE deleted_flag=0",BigDecimal.class)));
                assertThrows(BusinessException.class,()->outbounds.transferMaterialIssueToLineSide(issue.getOutboundId(),target.getLocationId(),actor,"待检不能移库",key+"-MOVE"));
            }
            List<Long> checks=jdbc.queryForList("SELECT inspection_id FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0",Long.class,issue.getOutboundId());
            checks.forEach(id->assertTrue(submitFinalInspection(id,actor,"库存实物复验合格")));
            if (index == 1) {
                var rejected = assertThrows(BusinessException.class,
                        () -> outbounds.confirmOutboundOrder(issue.getOutboundId(), actor, "不能用普通确认代替移库"));
                assertTrue(rejected.getMessage().contains("转线边"));
                assertEquals("RELEASED", workOrders.getWorkOrderById(entry.getValue()).getStatus());
                assertEquals("SUBMITTED", outbounds.getOutboundOrderById(issue.getOutboundId()).getAuditStatus());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wms_stock_movement WHERE source_order_type='OUTBOUND_ORDER' AND source_order_id=? AND deleted_flag=0", Integer.class, issue.getOutboundId()));
            }
            outbounds.validateLineSideTransfer(issue.getOutboundId(),target.getLocationId());
            assertEquals(issue.getOutboundId(),outbounds.generateMaterialIssue(entry.getValue(),semi.getWarehouseId(),actor,"库存组件",key).getOutboundId());
            outbounds.transferMaterialIssueToLineSide(issue.getOutboundId(),target.getLocationId(),actor,"合格组件转线边",key+"-MOVE");
            // 同键重放不重复移库：成功即返回，失败只会抛业务异常。
            outbounds.transferMaterialIssueToLineSide(issue.getOutboundId(),target.getLocationId(),actor,"合格组件转线边",key+"-MOVE");
        }
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order WHERE parent_work_order_id=? AND status='CLOSED' AND deleted_flag=0",Integer.class,root.getWorkOrderId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_task WHERE work_order_id IN (SELECT work_order_id FROM mfg_work_order WHERE parent_work_order_id=?)",Integer.class,root.getWorkOrderId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trace_product_lot WHERE work_order_id IN (SELECT work_order_id FROM mfg_work_order WHERE parent_work_order_id=?)",Integer.class,root.getWorkOrderId()));
        Long production=jdbc.queryForObject("SELECT production_order_id FROM mfg_production_plan WHERE plan_id=?",Long.class,root.getPlanId());
        var snapshot=progress.inspect(production,null,new com.neuedu.ems.common.AuthorizationContext(1L,null,null,"ALL",java.util.Set.of("SUPER_ADMIN"),java.util.Set.of(),java.util.Set.of()));
        assertEquals("NA",snapshot.stages().stream().filter(s->s.code().equals("COMPONENT")).findFirst().orElseThrow().status());
        assertEquals(3,snapshot.documents().stream().filter(d->"STOCK_INSPECTION".equals(d.type())).count());
        WmsWarehouse raw=warehouse(tag+"R","RAW"); WmsLocation rawShelf=location(raw,tag+"RL");
        stockRequirements(tag+"RAW",raw,rawShelf,root.getWorkOrderId(),actor,"STOCK");
        WmsOutboundOrder accessories=outbounds.generateMaterialIssue(root.getWorkOrderId(),raw.getWarehouseId(),actor,"装配配件",tag+"RAW");
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0",Integer.class,accessories.getOutboundId()));
        outbounds.transferMaterialIssueToLineSide(accessories.getOutboundId(),target.getLocationId(),actor,"配件转线边",tag+"RAW-MOVE");
        finishExecution(tag+"ROOT",actor,root.getWorkOrderId(),"LAMP-TEAM-FINAL","FINISHED","整机装配仍生产");
        assertEquals("FINISHED",workOrders.getWorkOrderById(root.getWorkOrderId()).getStatus());
        assertEquals("FINISHED",jdbc.queryForObject("SELECT status FROM mfg_production_plan WHERE plan_id=?",String.class,root.getPlanId()));
        for(Long child:components.values()) assertTrue(stockComponentSupply.fulfilledForShipment(workOrders.getWorkOrderById(child)));
        Long finishedWarehouse=jdbc.queryForObject("SELECT warehouse_id FROM wms_warehouse WHERE warehouse_type='FINISHED' AND deleted_flag=0 ORDER BY warehouse_id DESC LIMIT 1",Long.class);
        Long finishedLocation=jdbc.queryForObject("SELECT location_id FROM wms_location WHERE warehouse_id=? AND deleted_flag=0 ORDER BY location_id DESC LIMIT 1",Long.class,finishedWarehouse);
        var shipment=sales.generateSalesOutbound(production,1L,finishedWarehouse,finishedLocation,"库存组件到成品发货",actor);
        Long outgoing=sales.createOutgoingInspection(shipment.getOutboundId(),actor);
        assertTrue(submitFinalInspection(outgoing,actor,"课程演练出货记录"));
        assertTrue(sales.confirmSalesOutbound(shipment.getOutboundId(),actor,"课程演练发货"));
        var done=progress.inspect(production,null,new com.neuedu.ems.common.AuthorizationContext(1L,null,null,"ALL",java.util.Set.of("SUPER_ADMIN"),java.util.Set.of(),java.util.Set.of()));
        assertEquals(0,BigDecimal.ONE.compareTo(done.summary().shippedQuantity()));
        // 没有真实库存替代证据的普通关闭仍须阻断。
        Long child=components.values().iterator().next();
        jdbc.update("UPDATE sys_status_change_log SET change_reason='MANUAL_CLOSE' WHERE biz_type='MFG_WORK_ORDER' AND biz_id=? AND change_reason='USE_STOCK_COMPONENT'",child);
        assertFalse(stockComponentSupply.fulfilledForShipment(workOrders.getWorkOrderById(child)));
    }

    @Test void rootWorkOrderCannotIssueFromSemiWarehouse() {
        String tag="STOCKROOT"+System.nanoTime(); Long actor=1L;
        MfgWorkOrder root=openReleasedAssembly(tag,actor);
        WmsWarehouse semi=warehouse(tag+"S","SEMI_FINISHED");
        WmsLocation shelf=location(semi,tag+"SL");
        stockComponentRows(tag,semi,shelf,root.getWorkOrderId(),actor);
        // 整机工单只能领原材料：半成品要由组件工单整份领用后自动交接。
        BusinessException rejected=assertThrows(BusinessException.class,
                ()->outbounds.generateMaterialIssue(root.getWorkOrderId(),semi.getWarehouseId(),actor,"整机领半成品",tag));
        assertTrue(rejected.getMessage().contains("整机工单只能领原材料"),rejected.getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_order WHERE work_order_id=? AND deleted_flag=0",Integer.class,root.getWorkOrderId()));
    }

    @Test void componentEntryUsesItsFinishedMaterialAndParentInsteadOfItsRawInputs() {
        String tag="STOCKCHILD"+System.nanoTime(); Long actor=1L;
        MfgWorkOrder root=openReleasedAssembly(tag,actor);
        Long head=releaseComponent(actor,root.getWorkOrderId(),"LAMP-HEAD");
        WmsWarehouse semi=warehouse(tag,"SEMI_FINISHED"); WmsLocation shelf=location(semi,tag+"L");
        stockComponentRows(tag,semi,shelf,root.getWorkOrderId(),actor);
        WmsOutboundOrder issue=outbounds.generateMaterialIssue(head,semi.getWarehouseId(),actor,"直接用灯头库存",tag);
        // 出库单挂在组件工单下，用户按组件工单查看生产领料；用料需求仍由上级整机工单承担。
        assertEquals(head,issue.getWorkOrderId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0",Integer.class,issue.getOutboundId()));
        assertEquals(jdbc.queryForObject("SELECT material_id FROM mdm_product WHERE product_code='LAMP-HEAD' AND deleted_flag=0",Long.class),
                jdbc.queryForObject("SELECT material_id FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0",Long.class,issue.getOutboundId()));
        assertThrows(BusinessException.class,()->workOrders.startWorkOrder(head,actor));
        outbounds.withdrawOutboundOrder(issue.getOutboundId(),actor,"取消库存方案");
        assertEquals("RELEASED",workOrders.getWorkOrderById(head).getStatus());
        assertEquals(0,jdbc.queryForObject("SELECT SUM(reserved_quantity) FROM mfg_supply_allocation WHERE work_order_id=? AND material_id=(SELECT material_id FROM mdm_product WHERE product_code='LAMP-HEAD' AND deleted_flag=0)",BigDecimal.class,root.getWorkOrderId()).signum());
        assertEquals("CLOSED",jdbc.queryForObject("SELECT status FROM qms_inspection_order WHERE inspection_id=(SELECT inspection_id FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0)",String.class,issue.getOutboundId()));
    }

    @Test void startedComponentAndInsufficientSelectedWarehouseCannotBeReplaced() {
        String tag="STOCKGUARD"+System.nanoTime(); Long actor=1L;
        MfgWorkOrder root=openReleasedAssembly(tag,actor);
        Long head=releaseComponent(actor,root.getWorkOrderId(),"LAMP-HEAD");
        WmsWarehouse empty=warehouse(tag+"E","SEMI_FINISHED");
        assertThrows(BusinessException.class,()->outbounds.generateMaterialIssue(head,empty.getWarehouseId(),actor,"库存不足",tag+"EMPTY"));
        WmsWarehouse semi=warehouse(tag,"SEMI_FINISHED"); WmsLocation shelf=location(semi,tag+"L");
        stockComponentRows(tag,semi,shelf,root.getWorkOrderId(),actor);
        jdbc.update("UPDATE mfg_work_order SET status='RUNNING' WHERE work_order_id=?",head);
        assertThrows(BusinessException.class,()->outbounds.generateMaterialIssue(head,semi.getWarehouseId(),actor,"已开工不得替换",tag));
        assertEquals("RUNNING",workOrders.getWorkOrderById(head).getStatus());
        assertEquals(0,countIssues(root.getWorkOrderId()));
    }

    @Test void failedStockInspectionCannotCloseComponentsOrTransferStock() {
        String tag="STOCKFAIL"+System.nanoTime(); Long actor=1L;
        MfgWorkOrder root=openReleasedAssembly(tag,actor);
        Long head=releaseComponent(actor,root.getWorkOrderId(),"LAMP-HEAD");
        WmsWarehouse semi=warehouse(tag,"SEMI_FINISHED"); WmsLocation shelf=location(semi,tag+"L");
        stockComponentRows(tag,semi,shelf,root.getWorkOrderId(),actor);
        WmsOutboundOrder issue=outbounds.generateMaterialIssue(head,semi.getWarehouseId(),actor,"不良库存",tag);
        Long check=jdbc.queryForObject("SELECT inspection_id FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0",Long.class,issue.getOutboundId());
        List<com.neuedu.ems.entity.qms.QmsInspectionResult> rows=new ArrayList<>();
        for(Long item:jdbc.queryForList("SELECT i.item_id FROM qms_inspection_scheme_item i JOIN qms_inspection_order o ON o.scheme_id=i.scheme_id WHERE o.inspection_id=? AND i.deleted_flag=0",Long.class,check)) {
            var result=new com.neuedu.ems.entity.qms.QmsInspectionResult();
            result.setItemId(item);result.setPieceNo("STOCK-001");result.setResult("FAIL");result.setMeasuredValue("隔离测试：不合格");rows.add(result);
        }
        assertTrue(inspections.submitPieceInspection(check,rows,"不合格库存不能投产",actor));
        WmsWarehouse line=warehouse(tag+"LS","LINE_SIDE");WmsLocation target=location(line,tag+"LL");
        assertThrows(BusinessException.class,()->outbounds.transferMaterialIssueToLineSide(issue.getOutboundId(),target.getLocationId(),actor,"禁止转料",tag+"MOVE"));
        assertEquals("RELEASED",workOrders.getWorkOrderById(head).getStatus());
        assertEquals("SUBMITTED",outbounds.getOutboundOrderById(issue.getOutboundId()).getAuditStatus());
    }

    @Test void partialStockIsRejectedInsteadOfPartiallyIssued() {
        String tag="STOCKMIX"+System.nanoTime();Long actor=1L;
        releaseLampRoutes();prepareL200EngineeringBaseline();releaseFinalScheme("LAMP-HEAD");
        Long material=jdbc.queryForObject("SELECT material_id FROM mdm_product WHERE product_code='LAMP-HEAD' AND deleted_flag=0",Long.class);
        WmsWarehouse semi=warehouse(tag,"SEMI_FINISHED");WmsLocation shelf=location(semi,tag+"L");
        stockRows(tag,semi,shelf,actor,List.of(Map.of("material_id",material,"required_quantity",BigDecimal.ONE)));
        MfgWorkOrder root=openReleasedAssembly(tag,actor,new BigDecimal("2"),true);
        Long head=releaseComponent(actor,root.getWorkOrderId(),"LAMP-HEAD");
        // 从 0 起算：半成品仓只有 1 件库存，组件工单仍按整份需求 2 件生成，不因库存缩量。
        assertEquals(0,new BigDecimal("2").compareTo(workOrders.getWorkOrderById(head).getPlannedQuantity()));
        // 半成品不足整份时不做部分领用，直接提示余量不足，让用户改领原材料。
        BusinessException partial=assertThrows(BusinessException.class,
                ()->outbounds.generateMaterialIssue(head,semi.getWarehouseId(),actor,"半成品不足",tag+"SHORT"));
        assertTrue(partial.getMessage().contains("半成品余量不足"),partial.getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_order WHERE work_order_id IN (?,?) AND deleted_flag=0",Integer.class,root.getWorkOrderId(),head));
        // 领料被拒后组件工单原样保留，既不缩量也不关单。
        assertEquals("RELEASED",workOrders.getWorkOrderById(head).getStatus());
        assertEquals(0,new BigDecimal("2").compareTo(workOrders.getWorkOrderById(head).getPlannedQuantity()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_supply_allocation WHERE work_order_id=? AND deleted_flag=0",Integer.class,root.getWorkOrderId()));
    }

    @Test void fullStockReplacesTheComponentOrder() {
        String tag="STOCKFULL"+System.nanoTime();Long actor=1L;
        releaseLampRoutes();prepareL200EngineeringBaseline();releaseFinalScheme("LAMP-HEAD");
        Long material=jdbc.queryForObject("SELECT material_id FROM mdm_product WHERE product_code='LAMP-HEAD' AND deleted_flag=0",Long.class);
        WmsWarehouse semi=warehouse(tag,"SEMI_FINISHED");WmsLocation shelf=location(semi,tag+"L");
        // 整份 1 件都有库存：库存可以整份替代组件工单，不留下补产。
        stockRows(tag,semi,shelf,actor,List.of(Map.of("material_id",material,"required_quantity",BigDecimal.ONE)));
        MfgWorkOrder root=openReleasedAssembly(tag,actor,BigDecimal.ONE,true);
        Long head=releaseComponent(actor,root.getWorkOrderId(),"LAMP-HEAD");
        assertEquals(0,BigDecimal.ONE.compareTo(workOrders.getWorkOrderById(head).getPlannedQuantity()));
        // 从组件工单发起半成品领用：出库单挂在组件工单下，需求仍由整机工单承担。
        WmsOutboundOrder issue=outbounds.generateMaterialIssue(head,semi.getWarehouseId(),actor,"整份领库存",tag+"ISSUE");
        assertEquals(head,issue.getWorkOrderId());
        Long check=jdbc.queryForObject("SELECT inspection_id FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0",Long.class,issue.getOutboundId());
        assertTrue(submitFinalInspection(check,actor,"库存组件检验合格"));
        WmsWarehouse line=warehouse(tag+"LS","LINE_SIDE");WmsLocation target=location(line,tag+"LL");
        outbounds.transferMaterialIssueToLineSide(issue.getOutboundId(),target.getLocationId(),actor,"库存组件到位",tag+"MOVE");
        assertEquals("CLOSED",workOrders.getWorkOrderById(head).getStatus());
        assertEquals(0,BigDecimal.ONE.compareTo(jdbc.queryForObject("SELECT issued_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=? AND deleted_flag=0",BigDecimal.class,root.getWorkOrderId(),material)));
        assertEquals("STOCK",jdbc.queryForObject("SELECT supply_type FROM mfg_work_order_material WHERE work_order_id=? AND material_id=? AND deleted_flag=0",String.class,root.getWorkOrderId(),material));
    }

    private void stockComponentRows(String tag,WmsWarehouse semi,WmsLocation shelf,Long root,Long actor) {
        for(String product:List.of("LAMP-HEAD","LAMP-ARM","LAMP-BASE-L200")) releaseFinalScheme(product);
        var rows=jdbc.queryForList("""
                SELECT r.material_id,r.required_quantity FROM mfg_work_order_material r JOIN mdm_material m ON m.material_id=r.material_id
                WHERE r.work_order_id=? AND r.deleted_flag=0 AND m.material_type IN ('SEMI','SEMI_FINISHED')
                """,root);
        stockRows(tag,semi,shelf,actor,rows);
    }

    private BigDecimal required(Long workOrderId, String materialCode) {
        return jdbc.queryForObject("""
                SELECT required_quantity FROM mfg_work_order_material r
                JOIN mdm_material m ON m.material_id=r.material_id
                WHERE r.work_order_id=? AND m.material_code=? AND r.deleted_flag=0
                """, BigDecimal.class, workOrderId, materialCode);
    }

    private BigDecimal requiredOfChild(Long rootId, String productCode, String materialCode) {
        return jdbc.queryForObject("""
                SELECT r.required_quantity FROM mfg_work_order child
                JOIN mdm_product p ON p.product_id=child.product_id
                JOIN mfg_work_order_material r ON r.work_order_id=child.work_order_id AND r.deleted_flag=0
                JOIN mdm_material m ON m.material_id=r.material_id
                WHERE child.parent_work_order_id=? AND p.product_code=? AND m.material_code=? AND child.deleted_flag=0
                """, BigDecimal.class, rootId, productCode, materialCode);
    }

    private Long countMaterial(Long workOrderId, String materialCode) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM mfg_work_order_material r
                JOIN mdm_material m ON m.material_id=r.material_id
                WHERE r.work_order_id=? AND m.material_code=? AND r.deleted_flag=0
                """, Long.class, workOrderId, materialCode);
    }

    private Long countChildMaterial(Long rootId, String productCode, String materialCode) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM mfg_work_order child
                JOIN mdm_product p ON p.product_id=child.product_id
                JOIN mfg_work_order_material r ON r.work_order_id=child.work_order_id AND r.deleted_flag=0
                JOIN mdm_material m ON m.material_id=r.material_id
                WHERE child.parent_work_order_id=? AND p.product_code=? AND m.material_code=? AND child.deleted_flag=0
                """, Long.class, rootId, productCode, materialCode);
    }

    private void releaseLampRoutes() {
        for (Long routeId : jdbc.queryForList(
                "SELECT route_id FROM process_route WHERE route_code LIKE 'LAMP-ROUTE-%' AND status='DRAFT' AND deleted_flag=0",
                Long.class)) {
            ProcessRoute route = new ProcessRoute();
            route.setRouteId(routeId);
            route.setStatus("RELEASED");
            assertTrue(routes.updateRoute(route));
        }
        enableComponentsOnAssemblyLine();
    }

    private void enableComponentsOnAssemblyLine() {
        jdbc.update("""
                INSERT INTO mfg_line_product_capability(line_id,product_id,route_id,priority_level,capacity_factor,status,version,deleted_flag)
                SELECT l.line_id,p.product_id,r.route_id,10,
                       CASE p.product_code WHEN 'LAMP-BASE-L100' THEN 2.4 WHEN 'LAMP-BASE-L200' THEN 2 ELSE 3 END,
                       'ENABLE',0,0
                FROM factory_line l JOIN mdm_product p ON p.product_code IN ('LAMP-HEAD','LAMP-ARM','LAMP-BASE-L100','LAMP-BASE-L200') AND p.deleted_flag=0
                JOIN process_route r ON r.product_id=p.product_id AND r.status='RELEASED' AND r.deleted_flag=0
                WHERE l.line_code='LAMP-LINE-FINAL' AND l.deleted_flag=0
                  AND NOT EXISTS(SELECT 1 FROM mfg_line_product_capability c WHERE c.line_id=l.line_id AND c.product_id=p.product_id AND c.deleted_flag=0)
                """);
    }

    private MfgProductionPlan planRequest(Long lineId) {
        MfgProductionPlan request = new MfgProductionPlan();
        request.setLineId(lineId);
        request.setPlannedQuantity(BigDecimal.ONE);
        request.setPlannedStartTime(LocalDateTime.now().plusHours(2));
        request.setPlannedEndTime(LocalDateTime.now().plusDays(7));
        request.setRemark("台灯总装计划");
        return request;
    }

    /** 冻结工单会连子 BOM 一起检查。先补叶子，再补父级，新版本指向新的子 BOM。 */
    private void prepareL200EngineeringBaseline() {
        Long l200 = jdbc.queryForObject(
                "SELECT product_id FROM mdm_product WHERE product_code='LAMP-L200' AND deleted_flag=0", Long.class);
        for (Long id : jdbc.queryForList("""
                SELECT parameter_id FROM mdm_product_parameter
                WHERE product_id=? AND deleted_flag=0 AND required_flag=1 AND status='DRAFT'
                """, Long.class, l200)) {
            Integer version = jdbc.queryForObject(
                    "SELECT version FROM mdm_product_parameter WHERE parameter_id=?", Integer.class, id);
            parameters.review(id, new ProductParameterService.Review(version), 1L, true);
        }
        List<Long> pending = new ArrayList<>(jdbc.queryForList("""
                SELECT b.bom_id FROM mdm_bom b
                JOIN mdm_product p ON p.product_id=b.product_id
                WHERE b.status='RELEASED' AND b.deleted_flag=0 AND p.product_code LIKE 'LAMP-%'
                  AND EXISTS (
                    SELECT 1 FROM mdm_bom_item i
                    WHERE i.bom_id=b.bom_id AND i.deleted_flag=0 AND i.consumption_operation_id IS NULL
                  )
                """, Long.class));
        Set<Long> waiting = new HashSet<>(pending);
        Map<Long, Long> replaced = new HashMap<>();
        while (!pending.isEmpty()) {
            Long oldBom = null;
            for (Long candidate : pending) {
                List<Long> children = jdbc.queryForList(
                        "SELECT child_bom_id FROM mdm_bom_item WHERE bom_id=? AND deleted_flag=0 AND child_bom_id IS NOT NULL",
                        Long.class, candidate);
                boolean ready = true;
                for (Long child : children) {
                    if (waiting.contains(child) && !replaced.containsKey(child)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    oldBom = candidate;
                    break;
                }
            }
            if (oldBom == null) {
                throw new IllegalStateException("台灯 BOM 的子件关系成环，无法补投料工序");
            }
            pending.remove(oldBom);
            Long productId = jdbc.queryForObject(
                    "SELECT product_id FROM mdm_bom WHERE bom_id=?", Long.class, oldBom);
            Long operationId = jdbc.queryForObject("""
                    SELECT ri.operation_id FROM process_route r
                    JOIN process_route_item ri ON ri.route_id=r.route_id AND ri.deleted_flag=0
                    WHERE r.product_id=? AND r.status='RELEASED' AND r.deleted_flag=0
                    ORDER BY ri.sequence_no LIMIT 1
                    """, Long.class, productId);
            MdmBom created = new MdmBom();
            created.setBomCode("LAMP-BOM-BIND-" + oldBom);
            created.setBomVersion("BIND");
            created.setProductId(productId);
            created.setEffectiveDate(LocalDate.of(2026, 10, 19));
            created.setRemark("补齐投料工序");
            assertTrue(boms.saveBom(created));
            for (Map<String, Object> row : jdbc.queryForList("""
                    SELECT material_id, child_bom_id, quantity, loss_rate, sequence_no
                    FROM mdm_bom_item WHERE bom_id=? AND deleted_flag=0 ORDER BY sequence_no
                    """, oldBom)) {
                MdmBomItem item = new MdmBomItem();
                item.setBomId(created.getBomId());
                item.setMaterialId(((Number) row.get("material_id")).longValue());
                Object child = row.get("child_bom_id");
                if (child instanceof Number number) {
                    long childBomId = number.longValue();
                    item.setChildBomId(replaced.getOrDefault(childBomId, childBomId));
                }
                item.setQuantity(new BigDecimal(row.get("quantity").toString()));
                if (row.get("loss_rate") != null) {
                    item.setLossRate(new BigDecimal(row.get("loss_rate").toString()));
                }
                item.setSequenceNo(((Number) row.get("sequence_no")).intValue());
                item.setConsumptionOperationId(operationId);
                assertTrue(bomItems.saveBomItem(item));
            }
            MdmBom publish = new MdmBom();
            publish.setBomId(created.getBomId());
            publish.setStatus("RELEASED");
            assertTrue(boms.updateBom(publish));
            replaced.put(oldBom, created.getBomId());
        }
        releaseFinalScheme("LAMP-L200");
    }

    /** 下达要求唯一终检绑在路线最后一道工序。没绑工序的已发布过程检/终检先停用，再另建一份终检。 */
    private void releaseFinalScheme(String productCode) {
        Long productId = jdbc.queryForObject(
                "SELECT product_id FROM mdm_product WHERE product_code=? AND deleted_flag=0", Long.class, productCode);
        Long operationId = jdbc.queryForObject("""
                SELECT ri.operation_id FROM process_route r
                JOIN process_route_item ri ON ri.route_id=r.route_id AND ri.deleted_flag=0
                WHERE r.product_id=? AND r.status='RELEASED' AND r.deleted_flag=0
                ORDER BY ri.sequence_no DESC LIMIT 1
                """, Long.class, productId);
        List<Map<String, Object>> released = jdbc.queryForList("""
                SELECT scheme_id, inspection_type, operation_id FROM qms_inspection_scheme
                WHERE product_id=? AND status='RELEASED' AND deleted_flag=0
                  AND inspection_type IN ('PROCESS','FINAL')
                """, productId);
        Long templateSchemeId = null;
        for (Map<String, Object> row : released) {
            String type = String.valueOf(row.get("inspection_type"));
            Object bound = row.get("operation_id");
            boolean finalOnLast = "FINAL".equals(type) && bound instanceof Number number
                    && number.longValue() == operationId;
            if (finalOnLast) {
                templateSchemeId = ((Number) row.get("scheme_id")).longValue();
                continue;
            }
            if ("PROCESS".equals(type) && bound instanceof Number) {
                continue;
            }
            QmsInspectionScheme stop = new QmsInspectionScheme();
            stop.setSchemeId(((Number) row.get("scheme_id")).longValue());
            stop.setStatus("DISABLE");
            assertTrue(schemes.updateScheme(stop));
            if (templateSchemeId == null && "FINAL".equals(type)) {
                templateSchemeId = stop.getSchemeId();
            }
        }
        Integer ready = jdbc.queryForObject("""
                SELECT COUNT(*) FROM qms_inspection_scheme
                WHERE product_id=? AND status='RELEASED' AND deleted_flag=0
                  AND inspection_type='FINAL' AND operation_id=?
                """, Integer.class, productId, operationId);
        if (ready != null && ready > 0) {
            return;
        }
        if (templateSchemeId == null) {
            templateSchemeId = jdbc.queryForObject("""
                    SELECT scheme_id FROM qms_inspection_scheme
                    WHERE product_id=? AND inspection_type='FINAL' AND deleted_flag=0
                    ORDER BY scheme_id LIMIT 1
                    """, Long.class, productId);
        }
        QmsInspectionScheme created = new QmsInspectionScheme();
        created.setSchemeCode(productCode + "-FINAL-BIND");
        created.setSchemeName("L200终检");
        created.setProductId(productId);
        created.setOperationId(operationId);
        created.setInspectionType("FINAL");
        created.setStatus("DRAFT");
        assertTrue(schemes.saveScheme(created));
        int sequence = 1;
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT item_id, standard_id, sampling_mode, sample_quantity
                FROM qms_inspection_scheme_item WHERE scheme_id=? AND deleted_flag=0 ORDER BY sequence_no
                """, templateSchemeId)) {
            Long standardId = ((Number) row.get("standard_id")).longValue();
            String status = jdbc.queryForObject(
                    "SELECT confirmation_status FROM qms_inspection_standard WHERE standard_id=?",
                    String.class, standardId);
            if ("DRAFT".equals(status)) {
                Integer version = jdbc.queryForObject(
                        "SELECT version FROM qms_inspection_standard WHERE standard_id=?", Integer.class, standardId);
                standardReview.review(standardId, version, 1L, true);
            }
            QmsInspectionSchemeItem item = new QmsInspectionSchemeItem();
            item.setSchemeId(created.getSchemeId());
            item.setItemId(((Number) row.get("item_id")).longValue());
            item.setStandardId(standardId);
            item.setSamplingMode(String.valueOf(row.get("sampling_mode")));
            if (row.get("sample_quantity") != null) {
                item.setSampleQuantity(new BigDecimal(row.get("sample_quantity").toString()));
            }
            item.setSequenceNo(sequence++);
            assertTrue(schemeItems.saveSchemeItem(item));
        }
        QmsInspectionScheme publish = new QmsInspectionScheme();
        publish.setSchemeId(created.getSchemeId());
        publish.setStatus("RELEASED");
        assertTrue(schemes.updateScheme(publish));
    }

    private MfgWorkOrder openReleasedAssembly(String tag, Long actor) {
        return openReleasedAssembly(tag,actor,BigDecimal.ONE);
    }

    private MfgWorkOrder openReleasedAssembly(String tag, Long actor, BigDecimal quantity) {
        return openReleasedAssembly(tag,actor,quantity,false);
    }

    private MfgWorkOrder openReleasedAssembly(String tag, Long actor, BigDecimal quantity, boolean prepared) {
        if (!prepared) {
            releaseLampRoutes();
            prepareL200EngineeringBaseline();
        }
        Long productId = jdbc.queryForObject(
                "SELECT product_id FROM mdm_product WHERE product_code='LAMP-L200' AND deleted_flag=0", Long.class);
        Long lineId = jdbc.queryForObject(
                "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-FINAL' AND deleted_flag=0", Long.class);
        MfgProductionOrder order = new MfgProductionOrder();
        order.setProductId(productId);
        order.setCustomerId(1L);
        order.setOrderQuantity(quantity);
        order.setDeliveryDate(LocalDate.now().plusDays(30));
        order.setSourceType("MANUAL");
        assertTrue(orders.saveOrder(order));
        orders.releaseOrder(order.getProductionOrderId(), actor, "提交排产");
        var request=planRequest(lineId);request.setPlannedQuantity(quantity);
        MfgProductionPlan draft = plans.generatePlan(order.getProductionOrderId(), request, actor);
        plans.releasePlan(draft.getPlanId(), actor, "按交期生效");
        MfgWorkOrder command = new MfgWorkOrder();
        command.setRequestKey(tag);
        command.setRemark("总装齐套");
        MfgWorkOrder root = workOrders.generateWorkOrder(draft.getPlanId(), command, actor);
        workOrders.releaseWorkOrder(root.getWorkOrderId(), actor, "下达总装");
        return root;
    }

    private void confirmRequiredParameters(String productCode) {
        Long productId = jdbc.queryForObject(
                "SELECT product_id FROM mdm_product WHERE product_code=? AND deleted_flag=0", Long.class, productCode);
        for (Long id : jdbc.queryForList("""
                SELECT parameter_id FROM mdm_product_parameter
                WHERE product_id=? AND deleted_flag=0 AND required_flag=1 AND status='DRAFT'
                """, Long.class, productId)) {
            Integer version = jdbc.queryForObject(
                    "SELECT version FROM mdm_product_parameter WHERE parameter_id=?", Integer.class, id);
            parameters.review(id, new ProductParameterService.Review(version), 1L, true);
        }
    }

    private void finishHead(String tag, Long actor, Long workOrderId) {
        finishExecution(tag + "S", actor, workOrderId, "LAMP-TEAM-COMPONENT", "SEMI", "组件入库");
    }

    private Long releaseComponent(Long actor, Long rootId, String productCode) {
        Long workOrderId = jdbc.queryForObject("""
                SELECT child.work_order_id FROM mfg_work_order child
                JOIN mdm_product p ON p.product_id=child.product_id
                WHERE child.parent_work_order_id=? AND p.product_code=? AND child.deleted_flag=0
                ORDER BY child.work_order_id LIMIT 1
                """, Long.class, rootId, productCode);
        confirmRequiredParameters(productCode);
        releaseFinalScheme(productCode);
        Long planId = jdbc.queryForObject(
                "SELECT plan_id FROM mfg_work_order WHERE work_order_id=?", Long.class, workOrderId);
        if ("DRAFT".equals(plans.getPlanById(planId).getStatus())) { plans.releasePlan(planId, actor, "下达组件计划"); }
        workOrders.releaseWorkOrder(workOrderId, actor, "下达组件");
        return workOrderId;
    }

    private Map<String,Long> releaseComponents(Long actor, Long rootId, List<String> productCodes) {
        Map<String,Long> released = new LinkedHashMap<>();
        for (String productCode : productCodes) {
            released.put(productCode, releaseComponent(actor, rootId, productCode));
        }
        return released;
    }

    private void makeComponent(String tag, Long actor, Long workOrderId, String productCode,
                               WmsWarehouse raw, WmsLocation rawLocation) {
        makeComponent(tag, actor, workOrderId, productCode, raw, rawLocation, false);
    }

    private void makeComponent(String tag, Long actor, Long workOrderId, String productCode,
                               WmsWarehouse raw, WmsLocation rawLocation, boolean direct) {
        // 仓库和领料单号要短且互不重复。HEAD、ARM、L200 最后一个短横后的首字母分别是 H、A、L。
        String mark = tag + productCode.substring(productCode.lastIndexOf('-') + 1, productCode.lastIndexOf('-') + 2);
        stockRequirements(mark, raw, rawLocation, workOrderId, actor, "STOCK");
        WmsOutboundOrder issue = outbounds.generateMaterialIssue(
                workOrderId, raw.getWarehouseId(), actor, "组件领料", mark + "-ISSUE");
        WmsWarehouse line = warehouse(mark + "L", "LINE_SIDE");
        WmsLocation lineLocation = location(line, mark + "LL");
        outbounds.transferMaterialIssueToLineSide(
                issue.getOutboundId(), lineLocation.getLocationId(), actor, "组件转线边", mark + "-MOVE");
        finishExecution(mark + "S", actor, workOrderId, "LAMP-TEAM-COMPONENT", direct ? "DIRECT" : "SEMI", "组件产出");
    }

    private WmsLocation finishExecution(String code, Long actor, Long workOrderId, String teamCode,
                                        String warehouseType, String remark) {
        LocalDateTime planEnd = jdbc.queryForObject("""
                SELECT p.planned_end_time FROM mfg_production_plan p
                JOIN mfg_work_order w ON w.plan_id=p.plan_id
                WHERE w.work_order_id=?
                """, LocalDateTime.class, workOrderId);
        Long teamId = jdbc.queryForObject(
                "SELECT team_id FROM org_team WHERE team_code=? AND deleted_flag=0", Long.class, teamCode);
        MfgDispatchOrder request = new MfgDispatchOrder();
        request.setTeamId(teamId);
        LocalDateTime due = planEnd.minusHours(1);
        if (!due.isAfter(LocalDateTime.now())) {
            due = LocalDateTime.now().plusMinutes(30);
        }
        if (!due.isBefore(planEnd)) {
            due = planEnd.minusMinutes(1);
        }
        request.setDueTime(due);
        enablePlanStations(workOrderId);
        com.neuedu.ems.support.DispatchScheduleTestData.coverWorkOrder(jdbc, workOrderId, teamId);
        MfgDispatchOrder dispatch = dispatches.generateDispatchOrder(workOrderId, request, actor);
        dispatches.releaseDispatchOrder(dispatch.getDispatchId(), actor, "派发");
        List<Long> taskIds = jdbc.queryForList(
                "SELECT task_id FROM mfg_task WHERE work_order_id=? AND deleted_flag=0 ORDER BY task_id",
                Long.class, workOrderId);
        assertTrue(taskIds.size() >= 2);
        for (int index = 0; index < taskIds.size(); index++) {
            Long taskId = taskIds.get(index);
            Long operationId = jdbc.queryForObject(
                    "SELECT operation_id FROM mfg_task WHERE task_id=?", Long.class, taskId);
            Long taskStation = runningStation(workOrderId, operationId);
            assertTrue(tasks.startTask(taskId, taskStation, actor));
            MfgWorkReport report = new MfgWorkReport();
            report.setTaskId(taskId);
            report.setStationId(taskStation);
            report.setQualifiedQuantity(BigDecimal.ONE);
            report.setDefectQuantity(BigDecimal.ZERO);
            report.setActualWorkHours(new BigDecimal("0.5"));
            boolean last = index == taskIds.size() - 1;
            Long reportId = reports.submitWorkReport(report, last ? consumedMaterials(workOrderId) : List.of(), actor);
            assertTrue(reports.auditWorkReport(reportId, "APPROVED", "审核通过", actor));
        }
        Long lotId = jdbc.queryForObject(
                "SELECT product_lot_id FROM trace_product_lot WHERE work_order_id=? AND deleted_flag=0",
                Long.class, workOrderId);
        Long inspectionId = jdbc.queryForObject(
                "SELECT inspection_id FROM qms_inspection_order WHERE product_lot_id=? AND inspection_type='FINAL' AND deleted_flag=0",
                Long.class, lotId);
        assertTrue(submitFinalInspection(inspectionId, actor, "终检合格"));
        if ("DIRECT".equals(warehouseType)) {
            Long parent = jdbc.queryForObject("SELECT parent_work_order_id FROM mfg_work_order WHERE work_order_id=?", Long.class, workOrderId);
            // 测试事务最后回滚，不触发AFTER_COMMIT；显式运行相同的交接命令校验业务。
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wms_inventory i JOIN wms_location l ON l.location_id=i.location_id
                    WHERE i.work_order_id=? AND i.deleted_flag=0 AND i.warehouse_id<>l.warehouse_id
                    """, Integer.class, workOrderId), "线边产出仓库必须与实际领料库位所属仓库一致");
            assertTrue(componentHandover.synchronize(parent, actor).signum() > 0);
            return null;
        }
        WmsWarehouse stored = warehouse(code, warehouseType);
        WmsLocation storedLocation = location(stored, code + "L");
        var inbound = inbounds.generateProductionInbound(
                lotId, stored.getWarehouseId(), storedLocation.getLocationId(), actor, remark);
        assertTrue(inbounds.confirmInboundOrder(inbound.getInboundId(), actor, "确认入库"));
        return storedLocation;
    }

    private void enablePlanStations(Long workOrderId) {
        List<Long> stationIds = jdbc.queryForList("""
                SELECT DISTINCT s.station_id
                FROM mfg_work_order w
                JOIN mfg_production_plan p ON p.plan_id=w.plan_id
                JOIN process_route_item ri ON ri.route_id=w.route_id AND ri.deleted_flag=0
                JOIN factory_station_operation so ON so.operation_id=ri.operation_id
                    AND so.deleted_flag=0 AND so.status='ENABLE'
                JOIN factory_station s ON s.station_id=so.station_id AND s.line_id=p.line_id AND s.deleted_flag=0
                WHERE w.work_order_id=?
                """, Long.class, workOrderId);
        assertTrue(!stationIds.isEmpty());
        for (Long stationId : stationIds) {
            if (!"RUNNING".equals(jdbc.queryForObject(
                    "SELECT status FROM factory_station WHERE station_id=?", String.class, stationId))) {
                FactoryStation station = new FactoryStation();
                station.setStationId(stationId);
                station.setStatus("RUNNING");
                assertTrue(stations.updateStation(station));
            }
        }
    }

    private Long runningStation(Long workOrderId, Long operationId) {
        Long stationId = jdbc.queryForObject("""
                SELECT s.station_id FROM factory_station s
                JOIN factory_station_operation so ON so.station_id=s.station_id AND so.deleted_flag=0 AND so.status='ENABLE'
                JOIN mfg_work_order w ON w.work_order_id=?
                JOIN mfg_production_plan p ON p.plan_id=w.plan_id AND p.line_id=s.line_id
                WHERE so.operation_id=? AND s.deleted_flag=0
                ORDER BY s.station_id LIMIT 1
                """, Long.class, workOrderId, operationId);
        if (!"RUNNING".equals(jdbc.queryForObject(
                "SELECT status FROM factory_station WHERE station_id=?", String.class, stationId))) {
            FactoryStation station = new FactoryStation();
            station.setStationId(stationId);
            station.setStatus("RUNNING");
            assertTrue(stations.updateStation(station));
        }
        return stationId;
    }

    private Long childLotOnIssue(Long outboundId, Long materialId, Long workOrderId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wms_outbound_detail d
                JOIN wms_material_batch b ON b.batch_id=d.batch_id
                JOIN trace_product_lot lot ON lot.product_lot_id=b.source_order_id
                WHERE d.outbound_id=? AND d.material_id=? AND d.deleted_flag=0
                  AND lot.work_order_id=? AND lot.deleted_flag=0
                """, Long.class, outboundId, materialId, workOrderId);
    }

    private int consumeCount(Long workOrderId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wms_stock_movement
                WHERE work_order_id=? AND movement_type='CONSUME' AND source_order_type='WORK_REPORT' AND deleted_flag=0
                """, Integer.class, workOrderId);
        return count == null ? 0 : count;
    }

    private List<MfgReportMaterial> consumedMaterials(Long workOrderId) {
        List<MfgReportMaterial> materials = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT material_id, batch_id, locked_quantity FROM wms_inventory
                WHERE work_order_id=? AND deleted_flag=0 AND locked_quantity>0
                """, workOrderId)) {
            MfgReportMaterial material = new MfgReportMaterial();
            material.setMaterialId(((Number) row.get("material_id")).longValue());
            material.setBatchId(((Number) row.get("batch_id")).longValue());
            material.setUsedQuantity(new BigDecimal(row.get("locked_quantity").toString()));
            material.setScrapQuantity(BigDecimal.ZERO);
            materials.add(material);
        }
        return materials;
    }

    private void stockComponentMaterialsExcept(String tag, WmsWarehouse raw, WmsLocation rawLocation,
                                               Long workOrderId, Long actor, Long exceptMaterialId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT material_id, required_quantity FROM mfg_work_order_material
                WHERE work_order_id=? AND deleted_flag=0 AND required_quantity>0
                  AND supply_type='COMPONENT_WORK_ORDER' AND material_id<>?
                """, workOrderId, exceptMaterialId);
        if (rows.isEmpty()) {
            return;
        }
        stockRows(tag, raw, rawLocation, actor, rows);
    }

    private void stockRequirements(String tag, WmsWarehouse raw, WmsLocation rawLocation, Long workOrderId, Long actor) {
        stockRequirements(tag, raw, rawLocation, workOrderId, actor, null);
    }

    private void stockRequirements(String tag, WmsWarehouse raw, WmsLocation rawLocation, Long workOrderId,
                                   Long actor, String supplyType) {
        String sql = """
                SELECT material_id, required_quantity FROM mfg_work_order_material
                WHERE work_order_id=? AND deleted_flag=0 AND required_quantity>0
                """;
        List<Map<String, Object>> rows = supplyType == null
                ? jdbc.queryForList(sql, workOrderId)
                : jdbc.queryForList(sql + " AND supply_type=?", workOrderId, supplyType);
        stockRows(tag, raw, rawLocation, actor, rows);
    }

    private void stockRows(String tag, WmsWarehouse raw, WmsLocation rawLocation, Long actor,
                           List<Map<String, Object>> rows) {
        List<WmsInboundDetail> details = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> row : rows) {
            WmsMaterialBatch batch = new WmsMaterialBatch();
            batch.setBatchNo(tag + "-" + index++);
            batch.setMaterialId(((Number) row.get("material_id")).longValue());
            assertTrue(batches.saveMaterialBatch(batch));
            QmsInspectionOrder inspection = new QmsInspectionOrder();
            inspection.setInspectionNo(batch.getBatchNo());
            inspection.setInspectionType("INCOMING");
            inspection.setBatchId(batch.getBatchId());
            inspection.setCreatedBy(actor);
            assertTrue(inspections.saveInspectionOrder(inspection));
            assertTrue(inspections.submitInspectionOrder(inspection.getInspectionId(), "PASS", "齐套放行", actor));
            WmsInboundDetail detail = new WmsInboundDetail();
            detail.setMaterialId(batch.getMaterialId());
            detail.setBatchId(batch.getBatchId());
            detail.setLocationId(rawLocation.getLocationId());
            detail.setInboundQuantity(new BigDecimal(row.get("required_quantity").toString()));
            details.add(detail);
        }
        WmsInboundOrder inbound = new WmsInboundOrder();
        inbound.setInboundNo(tag + "-IN");
        inbound.setInboundType("PURCHASE");
        inbound.setWarehouseId(raw.getWarehouseId());
        inbound.setCreatedBy(actor);
        Long inboundId = inbounds.saveInboundOrderFull(inbound, details);
        assertTrue(inbounds.confirmInboundOrder(inboundId, actor, "齐套入库"));
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

    private int countIssues(Long workOrderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_outbound_order WHERE work_order_id=? AND outbound_type='MATERIAL_ISSUE' AND deleted_flag=0",
                Integer.class, workOrderId);
        return count == null ? 0 : count;
    }

    private int movementCount(Long outboundId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_stock_movement WHERE source_order_id=? AND source_order_type='OUTBOUND_ORDER' AND deleted_flag=0",
                Integer.class, outboundId);
        return count == null ? 0 : count;
    }

    private BigDecimal movementNet(Long outboundId) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(IF(direction='IN',quantity,-quantity)),0) FROM wms_stock_movement WHERE source_order_id=? AND source_order_type='OUTBOUND_ORDER' AND deleted_flag=0",
                BigDecimal.class, outboundId);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 终检/出货检验提交：方案要求逐件全检时必须走逐件入口，普通抽样方案仍走汇总入口。
     */
    private boolean submitFinalInspection(Long inspectionId, Long actor, String remark) {
        Integer allItems = jdbc.queryForObject("""
                SELECT COUNT(*) FROM qms_inspection_scheme_item i
                JOIN qms_inspection_order o ON o.scheme_id=i.scheme_id
                WHERE o.inspection_id=? AND i.deleted_flag=0 AND i.sampling_mode='ALL'
                """, Integer.class, inspectionId);
        if (allItems == null || allItems == 0) {
            return inspections.submitInspectionOrder(inspectionId, "PASS", remark, actor);
        }
        List<Long> items = jdbc.queryForList("""
                SELECT i.item_id FROM qms_inspection_scheme_item i
                JOIN qms_inspection_order o ON o.scheme_id=i.scheme_id
                WHERE o.inspection_id=? AND i.deleted_flag=0 ORDER BY i.item_id
                """, Long.class, inspectionId);
        java.math.BigDecimal declared = jdbc.queryForObject(
                "SELECT sample_quantity FROM qms_inspection_order WHERE inspection_id=?",
                java.math.BigDecimal.class, inspectionId);
        java.math.BigDecimal quantity = declared;
        if (quantity == null || quantity.signum() <= 0) {
            Long lotId = jdbc.queryForObject(
                    "SELECT product_lot_id FROM qms_inspection_order WHERE inspection_id=?",
                    Long.class, inspectionId);
            quantity = lotId == null ? java.math.BigDecimal.ONE : jdbc.queryForObject(
                    "SELECT COALESCE(batch_quantity,1) FROM trace_product_lot WHERE product_lot_id=?",
                    java.math.BigDecimal.class, lotId);
        }
        int pieces = Math.max(1, quantity.setScale(0, java.math.RoundingMode.CEILING).intValue());
        List<com.neuedu.ems.entity.qms.QmsInspectionResult> results = new ArrayList<>();
        for (int piece = 0; piece < pieces; piece++) {
            for (Long item : items) {
                com.neuedu.ems.entity.qms.QmsInspectionResult row = new com.neuedu.ems.entity.qms.QmsInspectionResult();
                row.setPieceNo("P" + piece);
                row.setItemId(item);
                row.setMeasuredValue("集成测试模拟测量值");
                row.setResult("PASS");
                results.add(row);
            }
        }
        return inspections.submitPieceInspection(inspectionId, results, remark, actor);
    }

}
