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

/**
 * 订单全过程视图的集成测试：只在本机验证库构造夹具，全部回滚。
 *
 * <p>断言重点不是"字段有没有返回"，而是几处容易算错的业务口径：整机产量不能被组件工单
 * 累加成多倍、缺料影响量不能超过剩余未完成量、老化批数按冻结批容量向上取整、只读接口
 * 不写任何业务数据。</p>
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_.*")
@Transactional
@AutoConfigureMockMvc
class ProductionOrderProgressRegressionTest extends ProductionOrderProgressTestFixture {
    @Test
    void linkedApprovedReworkCompletesComponentAndGuidesInboundWithoutCreatingStock() {
        var f = waitingComponent("FINISHED", BigDecimal.TEN);
        Long normal = insertTask("NORMAL", f.component(), 1L, BigDecimal.TEN, "FINISHED");
        Long report = insertReport("NORMAL", normal, new BigDecimal("9"), "APPROVED", "2026-10-25 09:00:00");
        Long lot = insertLot("NORMAL", componentProduct, f.component(), report, new BigDecimal("9"));
        insertInspection("NORMAL", "FINAL", f.component(), lot, new BigDecimal("9"), "PASS", "FINISHED", "2026-10-25 10:00:00");
        Long repair = insertTask("REPAIR", f.component(), 1L, BigDecimal.ONE, "FINISHED");
        jdbc.update("UPDATE mfg_task SET task_type='REWORK' WHERE task_id=?", repair);
        Long repaired = insertReport("REPAIR", repair, BigDecimal.ONE, "SUBMITTED", "2026-10-25 11:00:00");
        // 待审核和无来源的返工均不能凭空补足目标。
        assertEquals(0, stage(service.inspect(f.order(), LocalDateTime.of(2026,10,25,13,0), admin()), "COMPONENT")
                .reportedQualifiedQuantity().compareTo(new BigDecimal("9")));
        jdbc.update("UPDATE mfg_work_report SET audit_status='APPROVED' WHERE report_id=?", repaired);
        session.clearCache();
        assertEquals(0, stage(service.inspect(f.order(), LocalDateTime.of(2026,10,25,13,0), admin()), "COMPONENT")
                .reportedQualifiedQuantity().compareTo(new BigDecimal("9")));
        jdbc.update("UPDATE mfg_task SET source_task_id=? WHERE task_id=?", normal, repair);
        Long repairedLot = insertLot("REPAIR", componentProduct, f.component(), repaired, BigDecimal.ONE);
        insertInspection("REPAIR", "FINAL", f.component(), repairedLot, BigDecimal.ONE, "PASS", "FINISHED", "2026-10-25 11:30:00");
        session.clearCache();
        var before = counts();
        var progress = service.inspect(f.order(), LocalDateTime.of(2026,10,25,13,0), admin());
        assertEquals(0, stage(progress, "COMPONENT").reportedQualifiedQuantity().compareTo(BigDecimal.TEN));
        assertEquals("DONE", stage(progress, "COMPONENT").status());
        var assembly = stage(progress, "ASSEMBLY");
        assertEquals("RISK", assembly.status());
        assertTrue(assembly.blockers().stream().anyMatch(b -> "COMPONENT_HANDOVER".equals(b.code())));
        assertEquals(0, progress.summary().maxAssemblableQuantity().signum(), "放行未入库不能当作整机已领料");
        assertEquals(before, counts());
        // 原报工已足额时，质检返工不再把目标产量加成11件。
        jdbc.update("UPDATE mfg_work_report SET qualified_quantity=10 WHERE report_id=?", report);
        session.clearCache();
        assertEquals(0, stage(service.inspect(f.order(), LocalDateTime.of(2026,10,25,13,0), admin()), "COMPONENT")
                .reportedQualifiedQuantity().compareTo(BigDecimal.TEN));
    }

    @Test
    void plannedComponentProductionIsNormalWaitingBeforeAssemblyStart() {
        var fixture = waitingComponent("RUNNING", new BigDecimal("10"));
        var before = counts();
        var progress = service.inspect(fixture.order(), LocalDateTime.of(2026,10,25,9,0), admin());
        assertEquals("NOT_STARTED", stage(progress,"ASSEMBLY").status());
        assertEquals("COMPONENT", progress.summary().currentStageCode());
        assertEquals(0, progress.summary().blockerCount());
        assertEquals(0, progress.summary().maxAssemblableQuantity().signum(), "计划供给不算可装库存");
        assertTrue(stage(progress,"ASSEMBLY").notes().stream().anyMatch(n -> n.contains("等待组件生产")));
        assertTrue(coverageOf(progress,fixture.requirement()).shortageQuantity().signum()>0,
                "保留真实现货缺口，不虚增库存或隐藏用料需求");
        assertEquals(before,counts(),"进度检查不能写业务数据");
    }

    @Test
    void scheduledComponentNotYetStartedAlsoWaitsNormally() {
        var fixture = waitingComponent("DRAFT", new BigDecimal("10"));
        var progress = service.inspect(fixture.order(), LocalDateTime.of(2026,10,25,9,0), admin());
        assertEquals("NOT_STARTED", stage(progress,"ASSEMBLY").status());
        assertEquals("COMPONENT", progress.summary().currentStageCode());
        assertEquals(0, progress.summary().blockerCount());
    }

    @Test
    void insufficientOrClosedComponentSourceDoesNotHideShortage() {
        var fixture = waitingComponent("RUNNING", new BigDecimal("5"));
        var progress = service.inspect(fixture.order(), LocalDateTime.of(2026,10,25,9,0), admin());
        assertEquals("BLOCKED", stage(progress,"ASSEMBLY").status());
        jdbc.update("UPDATE mfg_work_order SET planned_quantity=10, status='CLOSED' WHERE work_order_id=?",fixture.component());
        session.clearCache();
        assertEquals("BLOCKED", stage(service.inspect(fixture.order(),
                LocalDateTime.of(2026,10,25,9,0), admin()),"ASSEMBLY").status());
    }

    @Test
    void assemblyAlreadyStartedOrDueCannotTreatShortageAsNormalWaiting() {
        var fixture = waitingComponent("RUNNING", new BigDecimal("10"));
        assertEquals("BLOCKED", stage(service.inspect(fixture.order(),
                LocalDateTime.of(2026,10,25,12,0), admin()),"ASSEMBLY").status());
        Long task = insertTask("EARLY-ASSEMBLY", fixture.root(),1L,new BigDecimal("10"),"RUNNING");
        insertReport("EARLY-ASSEMBLY", task,BigDecimal.ONE,"APPROVED","2026-10-25 09:00:00");
        session.clearCache();
        assertEquals("BLOCKED", stage(service.inspect(fixture.order(),
                LocalDateTime.of(2026,10,25,9,0), admin()),"ASSEMBLY").status());
    }

    private WaitingComponent waitingComponent(String componentStatus, BigDecimal componentQuantity) {
        inputMaterial = material("EXPECTED-COMPONENT");
        jdbc.update("UPDATE mdm_product SET material_id=? WHERE product_id=?",inputMaterial,componentProduct);
        Long order = insertOrder("RUNNING",new BigDecimal("10"));
        Long plan = insertPlan(order,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long root = insertWorkOrder("WAIT-ROOT",plan,product,new BigDecimal("10"),"DRAFT",null);
        attachSnapshot(root,snapshotBacking(root));
        jdbc.update("UPDATE mfg_work_order SET planned_start_time='2026-10-25 12:00:00',planned_end_time='2026-10-26 12:00:00' WHERE work_order_id=?",root);
        Long cp = insertPlan(order,componentQuantity,"RUNNING","COMPONENT",plan);
        Long component = insertWorkOrder("WAIT-COMPONENT",cp,componentProduct,componentQuantity,componentStatus,root);
        attachSnapshot(component,snapshotBacking(component));
        jdbc.update("UPDATE mfg_work_order SET planned_end_time='2026-10-25 11:00:00' WHERE work_order_id=?",component);
        Long requirement = insertRequirement(root,inputMaterial,new BigDecimal("10"),BigDecimal.ZERO,
                new BigDecimal("10"),"WAIT_READY","COMPONENT_WORK_ORDER",component,BigDecimal.ZERO);
        Long raw = material("READY-RAW");
        jdbc.update("UPDATE mdm_material SET material_type='RAW' WHERE material_id=?",raw);
        attachSnapshot(component,ExecutionSnapshotTestData.snapshot(json,orders.selectById(component),raw,inputMaterial));
        insertRequirement(component, raw,new BigDecimal("10"),new BigDecimal("10"),
                BigDecimal.ZERO,"READY","STOCK",null,BigDecimal.ZERO);
        if ("RUNNING".equals(componentStatus)) insertTask("COMPONENT",component,1L,componentQuantity,"RUNNING");
        session.clearCache();
        return new WaitingComponent(order,root,component,requirement);
    }

    private record WaitingComponent(Long order, Long root, Long component, Long requirement) {}

    @Test
    void reviewSplitRootOrdersMustAggregateAllRootOutputs() {
        Long id=insertOrder("FINISHED",new BigDecimal("10"));
        for(int i=0;i<2;i++) {
            Long plan=insertPlan(id,new BigDecimal("5"),"FINISHED","ROOT",null);
            Long wo=insertWorkOrder("ROOT"+i,plan,product,new BigDecimal("5"),"FINISHED",null);
            attachSnapshot(wo,snapshotBacking(wo));
            Long task=insertTask("OUT"+i,wo,1L,new BigDecimal("5"),"FINISHED");
            Long report=insertReport("R"+i,task,new BigDecimal("5"),"APPROVED","2026-10-25 10:00:00");
            Long lot=insertLot("R"+i,product,wo,report,new BigDecimal("5"));
            insertInspection("R"+i,"FINAL",wo,lot,new BigDecimal("5"),"PASS","FINISHED","2026-10-25 10:10:00");
            Long inbound=insertInbound("PRODUCT_LOT",lot,"APPROVED","2026-10-25 10:20:00");
            insertInboundDetail(inbound,outputMaterial,new BigDecimal("5"));
        }
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin());
        assertEquals(0,result.summary().productionPercent().compareTo(new BigDecimal("100")),
            "Two finished root WOs of 5 must represent 10/10 produced");
        assertEquals(0,result.summary().releasedQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0,result.summary().inboundQuantity().compareTo(new BigDecimal("10")));
    }

    @Test
    void reviewAssemblyMustShowCompletedBeforeAging() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,agingSnapshot(wo));
        Long assembly=insertTask("ASSEMBLY",wo,1L,new BigDecimal("10"),"FINISHED");
        insertReport("ASSEMBLY",assembly,new BigDecimal("10"),"APPROVED","2026-10-25 09:00:00");
        insertTask("AGING",wo,2L,new BigDecimal("10"),"WAIT_START");
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin());
        assertEquals(0,stage(result,"ASSEMBLY").executedQuantity().compareTo(new BigDecimal("10")),
            "Assembly completed while aging waits must not display zero assembly");
    }

    @Test
    void reviewPartialReportIsNotActualCompletion() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long task=insertTask("PART",wo,1L,new BigDecimal("10"),"RUNNING");
        insertReport("PART",task,new BigDecimal("3"),"APPROVED","2026-10-25 09:00:00");
        assertNull(service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin()).workOrders().get(0).actualEnd(),
            "RUNNING 3/10 report cannot be shown as actual completion time");
    }

    @Test
    void reviewOverdueUnfinishedOrderCannotPredictPastFinish() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        var result=service.inspect(id,LocalDateTime.of(2026,12,1,12,0),admin());
        assertNull(result.summary().predictedFinishTime(),
            "Unfinished after due date must not promise October 25 as predicted finish");
    }

    @Test
    void reviewUnrelatedOperationStationCannotReplaceBrokenStation() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,agingSnapshot(wo));
        Long broken=station("ASSEMBLY"), unrelated=station("AGING-ONLY");
        insertTaskAtStation("ASSEMBLY",wo,broken,new BigDecimal("10"),"WAIT_START");
        Long other=insertTaskAtStation("AGING",wo,unrelated,new BigDecimal("10"),"WAIT_START");
        jdbc.update("UPDATE mfg_task SET operation_id=2 WHERE task_id=?",other);
        Long eq=equipment("BROKEN",broken,"FAULT");
        insertRepair(eq,"OPEN","2026-10-25 08:00:00");
        equipment("AGING-ONLY",unrelated,"ENABLE");
        session.clearCache();
        var blocker=allBlockers(service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin())).stream()
            .filter(b->"EQUIPMENT_FAULT".equals(b.code())).findFirst().orElseThrow();
        assertEquals("RED",blocker.level(),"Different operation without matching capability cannot downgrade stoppage");
    }

    @Test
    void reviewRequirementEvidenceMustTargetWorkOrderId() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        insertRequirement(wo,material("MISS"),new BigDecimal("10"),BigDecimal.ZERO,
            new BigDecimal("10"),"WAIT_READY","STOCK",null,BigDecimal.ZERO);
        var blocker=allBlockers(service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin())).stream()
            .filter(b->"MATERIAL_SHORTAGE".equals(b.code())).findFirst().orElseThrow();
        var evidence=blocker.evidence().get(0).document();
        assertEquals(wo,((Number)evidence.query().get("focus")).longValue(),
            "Target mfg-work-order must focus work_order_id, not requirement_id");
    }

    @Test
    void reviewFailedFinalInspectionIsDispositionNotWaitingInspection() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long task=insertTask("OUT",wo,1L,new BigDecimal("10"),"FINISHED");
        Long report=insertReport("OUT",task,new BigDecimal("10"),"APPROVED","2026-10-25 09:00:00");
        Long lot=insertLot("FAIL",product,wo,report,new BigDecimal("10"));
        Long inspection=insertInspection("FAIL","FINAL",wo,lot,BigDecimal.ZERO,"FAIL","FINISHED","2026-10-25 10:00:00");
        jdbc.update("UPDATE qms_inspection_order SET sample_quantity=10, unqualified_quantity=10 WHERE inspection_id=?",inspection);
        jdbc.update("UPDATE trace_product_lot SET status='FAIL' WHERE product_lot_id=?",lot);
        session.clearCache();
        assertEquals(0,stage(service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin()),"ASSEMBLY").waitingInspectionQuantity().signum(),
            "Finished FAIL inspection of 10 is awaiting disposition, not 10 awaiting inspection");
    }

    @Test
    void reviewReservedAllocationWithoutLockedStockCannotMakeReady() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long mat=material("RES");
        Long req=insertRequirement(wo,mat,new BigDecimal("10"),BigDecimal.ZERO,new BigDecimal("10"),"WAIT_READY","STOCK",null,BigDecimal.ZERO);
        insertAllocation(req,wo,mat,"RESERVED",new BigDecimal("10"),BigDecimal.ZERO);
        session.clearCache();
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin());
        assertEquals(0,coverageOf(result,req).shortageQuantity().compareTo(new BigDecimal("10")),
            "Allocation with zero locked balance cannot be accepted as physically reserved stock");
    }

    @Test
    void reviewComponentEquipmentFaultMustNotSpreadToOtherComponent() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long root=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(root,snapshotBacking(root));
        Long broken=station("CMP");
        Long healthyWo=null;
        for(int i=0;i<2;i++) {
            Long cp=insertPlan(id,new BigDecimal("10"),"RUNNING","COMPONENT",plan);
            Long wo=insertWorkOrder("C"+i,cp,componentProduct,new BigDecimal("10"),"RUNNING",root);
            attachSnapshot(wo,snapshotBacking(wo));
            Long st=i==0?broken:station("HEALTHY");
            insertTaskAtStation("T"+i,wo,st,new BigDecimal("10"),"WAIT_START");
            if(i==1)healthyWo=wo;
        }
        Long eq=equipment("BROKEN",broken,"FAULT");
        insertRepair(eq,"OPEN","2026-10-25 08:00:00");
        session.clearCache();
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin());
        final String code="COMPONENT_"+healthyWo;
        var healthy=stage(result,"COMPONENT").children().stream().filter(x->code.equals(x.code())).findFirst().orElseThrow();
        assertTrue(healthy.blockers().stream().noneMatch(b->"EQUIPMENT_FAULT".equals(b.code())),"Fault at other component must not be attached to healthy component");
    }


    @Test
    void reviewConfirmedDefectiveReturnMustReduceIssuedCoverage() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long mat=material("RETURN");
        Long req=insertRequirement(wo,mat,new BigDecimal("10"),new BigDecimal("10"),BigDecimal.ZERO,"READY","STOCK",null,BigDecimal.ZERO);
        Long out=insertOutbound(wo,id,"APPROVED","2026-10-25 08:00:00");
        jdbc.update("UPDATE wms_outbound_order SET outbound_type='MATERIAL_ISSUE' WHERE outbound_id=?",out);
        insertOutboundDetail(out,mat,new BigDecimal("10"));
        Long detail=jdbc.queryForObject("SELECT detail_id FROM wms_outbound_detail WHERE outbound_id=?",Long.class,out);
        String number=tag();
        jdbc.update("INSERT INTO wms_return_order(return_no,return_type,work_order_id,audit_status,return_time,version,deleted_flag) VALUES (?,'DEFECTIVE',?,'APPROVED','2026-10-25 09:00:00',0,0)",number,wo);
        Long ret=jdbc.queryForObject("SELECT return_id FROM wms_return_order WHERE return_no=?",Long.class,number);
        jdbc.update("INSERT INTO wms_return_detail(return_id,outbound_detail_id,material_id,return_quantity,version,deleted_flag) VALUES (?,?,?,10,0,0)",ret,detail,mat);
        session.clearCache();
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin());
        assertEquals(0,coverageOf(result,req).shortageQuantity().compareTo(new BigDecimal("10")),
            "10 issued then 10 confirmed defective return without usable stock must leave shortage 10");
    }

    @Test
    void reviewConcessionFinalReleaseMustMatchInboundEligibility() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long task=insertTask("OUT",wo,1L,new BigDecimal("10"),"FINISHED");
        Long report=insertReport("OUT",task,new BigDecimal("10"),"APPROVED","2026-10-25 09:00:00");
        Long lot=insertLot("CONCESSION",product,wo,report,new BigDecimal("10"));
        insertInspection("CONCESSION","FINAL",wo,lot,new BigDecimal("10"),"CONCESSION","FINISHED","2026-10-25 10:00:00");
        jdbc.update("UPDATE trace_product_lot SET status='CONCESSION' WHERE product_lot_id=?",lot);
        session.clearCache();
        assertEquals(0,stage(service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin()),"ASSEMBLY").releasedQuantity().compareTo(new BigDecimal("10")),
            "Formal concession accepted for inbound must count as released, with explicit concession label");
    }



    @Test
    void finalInspectionContainsAgingWithoutAddingStandaloneAgingStage() {
        var progress=inspectAgingOrder(new BigDecimal("10"));
        assertTrue(progress.stages().stream().noneMatch(row->"AGING".equals(row.code())));
        var terminal=stage(progress,"FINAL_INSPECTION");
        assertTrue(terminal.children().stream().anyMatch(row->"AGING".equals(row.code())));
        assertEquals("REPORTED_UNVERIFIED",stage(progress,"AGING").actualTimeType());
    }

    private ProductionOrderProgressVO inspectAgingOrder(BigDecimal qty) {
        Long id=insertOrder("RUNNING",qty);
        Long plan=insertPlan(id,qty,"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,qty,"RUNNING",null);
        attachSnapshot(wo,agingSnapshot(wo));
        Long task=insertTask("AGING",wo,2L,qty,"RUNNING");
        insertLog("MFG_TASK",task,"WAIT_START","RUNNING","2026-10-25 09:00:00");
        session.clearCache();
        return service.inspect(id,LocalDateTime.of(2026,10,25,9,0),admin());
    }

    @Test
    void runningAgingUsesItsTaskStartAndCannotClaimCompletion() {
        var progress=inspectAgingOrder(new BigDecimal("10"));
        var aging=stage(progress,"AGING");
        assertEquals("RUNNING",aging.status());
        assertEquals(LocalDateTime.of(2026,10,25,9,0),aging.actualStart());
        assertNull(aging.actualEnd());
    }

    @Test
    void uncompletedExpiredPlanProducesRiskAtEvaluationClock() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        var early=service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin());
        var late=service.inspect(id,LocalDateTime.of(2026,10,25,13,0),admin());
        assertEquals(0,early.summary().riskCount());
        assertTrue(late.summary().riskCount()>0);
        assertNull(late.summary().predictedFinishTime());
    }

    @Test
    void reworkReportsDoNotProduceTheSameUnitsAgain() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long normal=insertTask("NORMAL",wo,1L,new BigDecimal("10"),"FINISHED");
        insertReport("NORMAL",normal,new BigDecimal("10"),"APPROVED","2026-10-25 09:00:00");
        Long repair=insertTask("REWORK",wo,1L,new BigDecimal("10"),"FINISHED");
        jdbc.update("UPDATE mfg_task SET task_type='REWORK' WHERE task_id=?",repair);
        insertReport("REWORK",repair,new BigDecimal("10"),"APPROVED","2026-10-25 10:00:00");
        session.clearCache();
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,10,0),admin());
        assertEquals(0,result.summary().productionPercent().compareTo(new BigDecimal("100")));
        assertEquals(2,result.documents().stream().filter(d->"WORK_REPORT".equals(d.type())).count());
    }


    @Test
    void formalConcessionDispositionReleasesPreviouslyFailedLot() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long task=insertTask("OUT",wo,1L,new BigDecimal("10"),"FINISHED");
        Long report=insertReport("OUT",task,new BigDecimal("10"),"APPROVED","2026-10-25 09:00:00");
        Long lot=insertLot("LET",product,wo,report,new BigDecimal("10"));
        Long q=insertInspection("LET","FINAL",wo,lot,BigDecimal.ZERO,"FAIL","FINISHED","2026-10-25 10:00:00");
        jdbc.update("UPDATE trace_product_lot SET status='CONCESSION' WHERE product_lot_id=?",lot);
        insertClosedDisposition(q,lot,"CONCESSION");session.clearCache();
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,10,0),admin());
        assertEquals(0,result.summary().releasedQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0,result.summary().blockerCount());
    }

    @Test
    void completedReworkDoesNotKeepOldFailureBlockingOrDoubleInspectedQuantity() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long t=insertTask("OLD",wo,1L,new BigDecimal("10"),"FINISHED");
        Long r=insertReport("OLD",t,new BigDecimal("10"),"APPROVED","2026-10-25 09:00:00");
        Long lot=insertLot("OLD",product,wo,r,new BigDecimal("10"));
        Long q=insertInspection("OLD","FINAL",wo,lot,BigDecimal.ZERO,"FAIL","FINISHED","2026-10-25 10:00:00");
        jdbc.update("UPDATE trace_product_lot SET status='REWORK' WHERE product_lot_id=?",lot);
        insertClosedDisposition(q,lot,"REWORK");
        Long rt=insertTask("NEW",wo,1L,new BigDecimal("10"),"FINISHED");
        jdbc.update("UPDATE mfg_task SET task_type='REWORK' WHERE task_id=?",rt);
        Long rr=insertReport("NEW",rt,new BigDecimal("10"),"APPROVED","2026-10-25 11:00:00");
        Long newLot=insertLot("NEW",product,wo,rr,new BigDecimal("10"));
        insertInspection("NEW","FINAL",wo,newLot,new BigDecimal("10"),"PASS","FINISHED","2026-10-25 11:30:00");
        session.clearCache();
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,11,30),admin());
        assertEquals(0,result.summary().releasedQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0,result.summary().blockerCount());
        assertEquals(0,stage(result,"FINAL_INSPECTION").executedQuantity().compareTo(new BigDecimal("10")));
        assertEquals(2,result.documents().stream().filter(d->"INSPECTION".equals(d.type())).count());
    }

    private void insertClosedDisposition(Long inspection,Long lot,String type) {
        jdbc.update("INSERT INTO qms_nonconforming_order(ncr_no,inspection_id,product_lot_id,disposition_type,approval_status,status,version,deleted_flag) VALUES (?,?,?,?,'APPROVED','CLOSED',0,0)",tag(),inspection,lot,type);
    }


    @Test
    void componentFinalFailureIsVisibleAsItsOwnBlocker() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long root=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(root,snapshotBacking(root));
        Long cp=insertPlan(id,new BigDecimal("10"),"RUNNING","COMPONENT",plan);
        Long child=insertWorkOrder("CHILD",cp,componentProduct,new BigDecimal("10"),"RUNNING",root);
        attachSnapshot(child,snapshotBacking(child));
        Long task=insertTask("OUT",child,1L,new BigDecimal("10"),"FINISHED");
        Long report=insertReport("OUT",task,new BigDecimal("10"),"APPROVED","2026-10-25 09:00:00");
        Long lot=insertLot("FAIL",componentProduct,child,report,new BigDecimal("10"));
        insertInspection("FAIL","FINAL",child,lot,BigDecimal.ZERO,"FAIL","FINISHED","2026-10-25 10:00:00");
        jdbc.update("UPDATE trace_product_lot SET status='FAIL' WHERE product_lot_id=?",lot);session.clearCache();
        var result=service.inspect(id,LocalDateTime.of(2026,10,25,10,0),admin());
        var component=stage(result,"COMPONENT");
        assertEquals("BLOCKED",component.status());
        assertTrue(component.children().stream().flatMap(row->row.blockers().stream()).anyMatch(b->"FINAL_QUALITY_FAILED".equals(b.code())));
    }


    @Test
    void pieceInspectionReallyReleasesLotAndAppearsInFinalProgress() {
        Long id=insertOrder("RUNNING",new BigDecimal("10"));
        Long plan=insertPlan(id,new BigDecimal("10"),"RUNNING","ROOT",null);
        Long wo=insertWorkOrder("ROOT",plan,product,new BigDecimal("10"),"RUNNING",null);
        attachSnapshot(wo,snapshotBacking(wo));
        Long task=insertTask("OUT",wo,1L,new BigDecimal("10"),"FINISHED");
        Long report=insertReport("OUT",task,new BigDecimal("10"),"APPROVED","2026-10-25 09:00:00");
        Long lot=insertLot("PIECE",product,wo,report,new BigDecimal("10"));
        jdbc.update("UPDATE trace_product_lot SET status='WAIT_INSPECTION' WHERE product_lot_id=?",lot);
        Long q=insertInspection("PIECE","FINAL",wo,lot,BigDecimal.ZERO,"", "DRAFT","2026-10-25 10:00:00");
        Long scheme=jdbc.queryForObject("SELECT scheme_id FROM qms_inspection_scheme WHERE product_id=? AND inspection_type='FINAL' AND status='RELEASED' AND deleted_flag=0 ORDER BY scheme_id DESC LIMIT 1",Long.class,product);
        jdbc.update("UPDATE qms_inspection_order SET scheme_id=?,task_id=?,report_id=? WHERE inspection_id=?",scheme,task,report,q);
        List<Long> items=jdbc.queryForList("SELECT DISTINCT item_id FROM qms_inspection_scheme_item WHERE scheme_id=? AND deleted_flag=0",Long.class,scheme);
        assertFalse(items.isEmpty());
        List<com.neuedu.ems.entity.qms.QmsInspectionResult> results=new java.util.ArrayList<>();
        for(int piece=0;piece<10;piece++)for(Long item:items) {
            var row=new com.neuedu.ems.entity.qms.QmsInspectionResult();row.setPieceNo("TEST-"+piece);
            row.setItemId(item);row.setMeasuredValue("课程模拟检验输入");row.setResult("PASS");results.add(row);
        }
        session.clearCache();
        assertTrue(quality.submitPieceInspection(q,results,"隔离回归夹具，非实物测量",1L));
        assertEquals("PASS",jdbc.queryForObject("SELECT status FROM trace_product_lot WHERE product_lot_id=?",String.class,lot));
        assertEquals(10*items.size(),jdbc.queryForObject("SELECT COUNT(*) FROM qms_inspection_result WHERE inspection_id=? AND deleted_flag=0",Integer.class,q));
        session.clearCache();
        var progress=service.inspect(id,LocalDateTime.of(2026,10,25,11,0),admin());
        assertEquals(0,progress.summary().releasedQuantity().compareTo(new BigDecimal("10")));
        assertEquals("DONE",stage(progress,"FINAL_INSPECTION").status());
    }


    @Test
    void draftRootPlanIsNotConfirmedScheduledQuantity() {
        Long id=insertOrder("RELEASED",new BigDecimal("10"));
        insertPlan(id,new BigDecimal("10"),"DRAFT","ROOT",null);
        var progress=service.inspect(id,LocalDateTime.of(2026,10,25,8,0),admin());
        assertEquals(0,stage(progress,"SCHEDULE").executedQuantity().signum());
        assertFalse("DONE".equals(stage(progress,"SCHEDULE").status()));
        assertEquals(0,stage(progress,"SCHEDULE").remainingQuantity().compareTo(new BigDecimal("10")));
        assertNull(stage(progress,"ORDER_RELEASE").actualEnd());
    }

}
