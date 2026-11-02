package com.neuedu.ems.service.mfg;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.wms.LineSideLocationService;
import com.neuedu.ems.support.ExecutionSnapshotTestData;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"mes.notification.scheduling-enabled=false","mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY,PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_.*")
@Transactional
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class ExecutionUsabilityIntegrationTest extends ProductionOrderProgressTestFixture {
    @Autowired WorkReportPreparationService preparation;
    @Autowired LineSideLocationService lineSide;
    @Autowired TaskDefectResolutionService resolution;
    @Autowired MfgTaskService taskService;
    @Autowired MfgWorkReportService reportService;
    @Autowired MfgReportMaterialService reportMaterials;
    @Autowired com.neuedu.ems.service.wms.WmsOutboundOrderService outbounds;
    @Autowired com.neuedu.ems.service.qms.QmsInspectionOrderService inspections;
    @Autowired com.neuedu.ems.service.qms.QmsNonconformingOrderService nonconforming;
    Long wo,task,dispatch,batch,location,lineWarehouse;

    @BeforeEach void execution() {
        Long production=insertOrder("RELEASED",BigDecimal.TEN);
        Long plan=insertPlan(production,BigDecimal.TEN,"RELEASED","ROOT",null);
        wo=insertWorkOrder("USABILITY",plan,product,BigDecimal.TEN,"RUNNING",null);
        var order=orders.selectById(wo);
        ExecutionSnapshotTestData.attach(order,snapshotBacking(wo));orders.updateById(order);
        insertRequirement(wo,inputMaterial,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ZERO,"READY","STOCK",null,BigDecimal.ZERO);
        String code=tag();
        jdbc.update("INSERT INTO mfg_dispatch_order(dispatch_no,work_order_id,planned_quantity,status,deleted_flag) VALUES(?,?,10,'RELEASED',0)",code,wo);
        dispatch=jdbc.queryForObject("SELECT dispatch_id FROM mfg_dispatch_order WHERE dispatch_no=?",Long.class,code);
        task=insertTask("USABILITY",wo,1L,BigDecimal.TEN,"RUNNING");
        jdbc.update("UPDATE mfg_task SET dispatch_id=?,completed_quantity=0 WHERE task_id=?",dispatch,task);
        jdbc.update("INSERT INTO wms_warehouse(warehouse_code,warehouse_name,warehouse_type,status,deleted_flag) VALUES(?,?,'LINE_SIDE','ENABLE',0)",code,code);
        lineWarehouse=jdbc.queryForObject("SELECT warehouse_id FROM wms_warehouse WHERE warehouse_code=?",Long.class,code);
        location=location("-ONE",lineId);
        batch=batch(inputMaterial);
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,location_id,material_id,batch_id,work_order_id,available_quantity,locked_quantity,inspection_quantity,version,deleted_flag) VALUES(?,?,?,?,?,0,6,0,0,0)",lineWarehouse,location,inputMaterial,batch,wo);
        Long second=location("-TWO",lineId);
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,location_id,material_id,batch_id,work_order_id,available_quantity,locked_quantity,inspection_quantity,version,deleted_flag) VALUES(?,?,?,?,?,0,4,0,0,0)",lineWarehouse,second,inputMaterial,batch,wo);
        session.clearCache();
    }

    Long location(String suffix,Long line) {
        String code=tag()+suffix;
        jdbc.update("INSERT INTO wms_location(location_code,location_name,warehouse_id,line_id,location_type,status,deleted_flag) VALUES(?,?,?,?,'NORMAL','IDLE',0)",code,code,lineWarehouse,line);
        return jdbc.queryForObject("SELECT location_id FROM wms_location WHERE location_code=?",Long.class,code);
    }

    @Test void contextAggregatesBatchesAndOnlySuggestsCurrentOperation() {
        var prepared=preparation.prepare(task,null,new BigDecimal("7"));
        assertEquals(0,BigDecimal.TEN.compareTo(prepared.remainingQuantity()));
        assertEquals(1,prepared.materials().size());
        assertEquals(0,BigDecimal.TEN.compareTo(prepared.materials().getFirst().availableQuantity()));
        assertEquals(0,new BigDecimal("7").compareTo(prepared.materials().getFirst().suggestedQuantity()));
        jdbc.update("UPDATE mfg_task SET operation_id=999999 WHERE task_id=?",task);session.clearCache();
        assertTrue(preparation.prepare(task,null,BigDecimal.ONE).materials().isEmpty());
    }

    @Test void pendingReportsReserveQuantityAndMaterialsAndEditingExcludesItself() {
        Long report=insertReport("PENDING",task,new BigDecimal("3"),"SUBMITTED","2026-10-27 08:00:00");
        jdbc.update("UPDATE mfg_work_report SET defect_quantity=1 WHERE report_id=?",report);
        jdbc.update("INSERT INTO mfg_report_material(report_id,material_id,batch_id,used_quantity,scrap_quantity,deleted_flag) VALUES(?,?,?,4,0,0)",report,inputMaterial,batch);
        session.clearCache();
        var prepared=preparation.prepare(task,null,null);
        assertEquals(0,new BigDecimal("6").compareTo(prepared.remainingQuantity()));
        assertEquals(0,new BigDecimal("6").compareTo(prepared.materials().getFirst().availableQuantity()));
        assertThrows(BusinessException.class,()->preparation.prepare(task,null,new BigDecimal("7")));
        assertEquals(0,BigDecimal.TEN.compareTo(preparation.prepare(task,report,null).remainingQuantity()));
    }

    @Test void dedicatedAndSharedLocationsExcludeOtherLines() {
        Long shared=location("SHARED",null);
        Long anotherLine=jdbc.queryForObject("SELECT line_id FROM factory_line WHERE deleted_flag=0 AND line_id<>? LIMIT 1",Long.class,lineId);
        Long foreign=location("FOREIGN",anotherLine);
        var prepared=lineSide.prepare(wo);
        assertTrue(prepared.locations().stream().allMatch(l->l.getLineId()==null || Objects.equals(lineId,l.getLineId())));
        assertThrows(BusinessException.class,()->lineSide.requireAllowed(wo,foreign));
        assertDoesNotThrow(()->lineSide.requireAllowed(wo,shared));
        assertDoesNotThrow(()->lineSide.requireAllowed(wo,location));
    }

    void finishedDefect() {
        Long report=insertReport("BAD",task,new BigDecimal("8"),"APPROVED","2026-10-27 08:00:00");
        jdbc.update("UPDATE mfg_work_report SET defect_quantity=2 WHERE report_id=?",report);
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=8,defect_quantity=2 WHERE task_id=?",task);
        session.clearCache();
    }

    @Test void scrapCreatesSupplementAndMaterialShortageOnce() {
        finishedDefect();
        var created=resolution.resolve(task,"SCRAP","外壳损坏无法修复",1L);
        assertEquals(1,created.size());assertEquals("SUPPLEMENT",created.getFirst().getTaskType());
        assertEquals(0,new BigDecimal("2").compareTo(created.getFirst().getPlannedQuantity()));
        assertEquals(0,new BigDecimal("12").compareTo(jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,wo,inputMaterial)));
        resolution.resolve(task,"SCRAP","重试",1L);
        assertEquals(0,new BigDecimal("12").compareTo(jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,wo,inputMaterial)));
        assertThrows(BusinessException.class,()->resolution.resolve(task,"REWORK","改用另一方式",1L));
    }

    @Test void progressSeparatesOriginalDemandFromScrapSupplementWithoutChangingCoverage() {
        finishedDefect();
        resolution.resolve(task,"SCRAP","两件报废增加补料",1L);
        Long productionOrder=jdbc.queryForObject("SELECT production_order_id FROM mfg_production_plan WHERE plan_id=(SELECT plan_id FROM mfg_work_order WHERE work_order_id=?)",Long.class,wo);
        // 当前主BOM变动不能改写已下达工单的原始需求。
        jdbc.update("UPDATE mdm_bom_item SET quantity=quantity*2 WHERE bom_id=(SELECT bom_id FROM mfg_work_order WHERE work_order_id=?)",wo);
        jdbc.update("UPDATE wms_inventory SET available_quantity=0 WHERE work_order_id=0 AND material_id=?",inputMaterial);
        session.clearCache();
        var progress=service.inspect(productionOrder,null,admin());
        var material=progress.materials().stream().filter(m->m.workOrderId().equals(wo)&&m.materialId().equals(inputMaterial)).findFirst().orElseThrow();
        assertEquals(0,BigDecimal.TEN.compareTo(material.baseRequiredQuantity()));
        assertEquals(0,new BigDecimal("2").compareTo(material.supplementRequiredQuantity()));
        assertEquals(0,new BigDecimal("12").compareTo(material.requiredQuantity()));
        assertEquals(0,BigDecimal.TEN.compareTo(material.issuedQuantity()));
        assertEquals(0,new BigDecimal("2").compareTo(material.shortageQuantity()));
        var preparationMaterial=java.util.stream.Stream.concat(progress.preparationMaterials().stream(),progress.semiFinishedMaterials().stream())
                .filter(m->Objects.equals(m.workOrderId(),wo)&&m.materialId().equals(inputMaterial)).findFirst().orElseThrow();
        assertEquals(material.baseRequiredQuantity(),preparationMaterial.baseRequiredQuantity());
        assertEquals(material.supplementRequiredQuantity(),preparationMaterial.supplementRequiredQuantity());
    }

    @Test void reworkReusesMaterialAndNeverAddsFullBomConsumption() {
        finishedDefect();
        var created=resolution.resolve(task,"REWORK","重新压紧接插件",1L);
        assertEquals("REWORK",created.getFirst().getTaskType());
        assertEquals(0,BigDecimal.TEN.compareTo(jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,wo,inputMaterial)));
        assertTrue(preparation.prepare(created.getFirst().getTaskId(),null,null).materials().isEmpty());
    }

    @Test void finishedExecutionIsNotFulfilledUntilApprovedRecoveryMakesEnoughGoodPieces() {
        finishedDefect();
        var initial=taskService.getTaskById(task);
        assertEquals("FINISHED",initial.getStatus());
        assertEquals("WAIT_RECOVERY",initial.getFulfillmentStatus());
        assertEquals(0,new BigDecimal("2").compareTo(initial.getRemainingQualifiedQuantity()));
        var recovery=resolution.resolve(task,"REWORK","补足两件合格品",1L).getFirst();
        assertEquals("RECOVERING",taskService.getTaskById(task).getFulfillmentStatus());
        Long report=insertReport("RECOVERY-PENDING",recovery.getTaskId(),new BigDecimal("2"),"SUBMITTED","2026-10-27 09:00:00");
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=2 WHERE task_id=?",recovery.getTaskId());session.clearCache();
        var pending=taskService.getTaskById(task);
        assertEquals("PENDING_REVIEW",pending.getFulfillmentStatus());
        assertEquals(0,BigDecimal.ZERO.compareTo(pending.getRecoveredQualifiedQuantity()));
        jdbc.update("UPDATE mfg_work_report SET audit_status='APPROVED' WHERE report_id=?",report);session.clearCache();
        var before=counts();
        var fulfilled=taskService.getTaskById(task);
        assertEquals("QUALIFIED",fulfilled.getFulfillmentStatus());
        assertEquals(0,new BigDecimal("2").compareTo(fulfilled.getRecoveredQualifiedQuantity()));
        assertEquals(0,BigDecimal.TEN.compareTo(fulfilled.getTotalQualifiedQuantity()));
        assertEquals(0,BigDecimal.ZERO.compareTo(fulfilled.getRemainingQualifiedQuantity()));
        assertEquals(0,new BigDecimal("8").compareTo(fulfilled.getCompletedQuantity()),"原报工结果不得被补偿覆盖");
        assertEquals(before,counts(),"达标视图不能修改业务数据");
    }

    @Test void repeatedScrapRecoveryUsesResolutionLinkWithoutCountingAnyTaskTwice() {
        finishedDefect();
        var first=resolution.resolve(task,"SCRAP","先补产两件",1L).getFirst();
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=1,defect_quantity=1 WHERE task_id=?",first.getTaskId());
        Long report=insertReport("FIRST-RECOVERY",first.getTaskId(),BigDecimal.ONE,"APPROVED","2026-10-27 09:00:00");
        jdbc.update("UPDATE mfg_work_report SET defect_quantity=1 WHERE report_id=?",report);session.clearCache();
        var second=resolution.resolve(first.getTaskId(),"SCRAP","再补一件",1L).getFirst();
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=1 WHERE task_id=?",second.getTaskId());
        insertReport("SECOND-RECOVERY",second.getTaskId(),BigDecimal.ONE,"APPROVED","2026-10-27 10:00:00");session.clearCache();
        assertEquals("QUALIFIED",taskService.getTaskById(first.getTaskId()).getFulfillmentStatus());
        String number=taskService.getTaskById(task).getTaskNo();
        var offPage=taskService.pageTasks(1,1,number,null,null,wo,null,null,null,null).getRecords().getFirst();
        assertEquals(task,offPage.getTaskId());
        assertEquals("QUALIFIED",offPage.getFulfillmentStatus());
        assertEquals(0,new BigDecimal("2").compareTo(offPage.getRecoveredQualifiedQuantity()));
    }

    @Test void otherOperationOrUnlinkedGoodReportsCannotCoverThisTaskDeficit() {
        finishedDefect();
        Long other=insertTask("OTHER-OP",wo,2L,new BigDecimal("2"),"FINISHED");
        jdbc.update("UPDATE mfg_task SET task_type='SUPPLEMENT',source_task_id=?,defect_resolution_task_id=?,dispatch_id=? WHERE task_id=?",task,task,dispatch,other);
        insertReport("OTHER-OP-GOOD",other,new BigDecimal("2"),"APPROVED","2026-10-27 09:00:00");
        Long unlinked=insertTask("UNLINKED",wo,1L,new BigDecimal("2"),"FINISHED");
        jdbc.update("UPDATE mfg_task SET dispatch_id=? WHERE task_id=?",dispatch,unlinked);
        insertReport("UNLINKED-GOOD",unlinked,new BigDecimal("2"),"APPROVED","2026-10-27 10:00:00");session.clearCache();
        var view=taskService.getTaskById(task);
        assertEquals("WAIT_RECOVERY",view.getFulfillmentStatus());
        assertEquals(0,new BigDecimal("2").compareTo(view.getRemainingQualifiedQuantity()));
    }

    @Test void fullyDefectiveApprovedReportStillNeedsAllGoodPieces() {
        Long report=insertReport("ALL-BAD",task,BigDecimal.ZERO,"APPROVED","2026-10-27 08:00:00");
        jdbc.update("UPDATE mfg_work_report SET defect_quantity=10 WHERE report_id=?",report);
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=0,defect_quantity=10 WHERE task_id=?",task);session.clearCache();
        var view=taskService.getTaskById(task);
        assertEquals("WAIT_RECOVERY",view.getFulfillmentStatus());
        assertEquals(0,BigDecimal.TEN.compareTo(view.getRemainingQualifiedQuantity()));
    }

    @Test void scrapSupplementIssuesOnlyTheDifferenceAndClearsMaterialGateAfterTransfer() {
        finishedDefect();
        var recovery=resolution.resolve(task,"SCRAP","报废两件后补领料",1L).getFirst();
        assertEquals(0,new BigDecimal("2").compareTo(recovery.getPlannedQuantity()));
        var target=org.springframework.test.util.AopTestUtils.getTargetObject(taskService);
        assertTrue(assertThrows(BusinessException.class,()->org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(target,"requireMaterialReady",wo)).getMessage().contains("领料"));
        Long rawLocation=publicLocation();
        Long inventory=inventoryRow(rawWarehouseId,rawLocation,inputMaterial,batch);
        jdbc.update("UPDATE wms_inventory SET available_quantity=2 WHERE inventory_id=?",inventory);
        session.clearCache();
        var issue=outbounds.generateMaterialIssue(wo,rawWarehouseId,1L,"补领两件");
        assertEquals(0,new BigDecimal("2").compareTo(jdbc.queryForObject(
                "SELECT SUM(outbound_quantity) FROM wms_outbound_detail WHERE outbound_id=? AND deleted_flag=0",
                BigDecimal.class,issue.getOutboundId())));
        assertEquals(issue.getOutboundId(),outbounds.generateMaterialIssue(wo,rawWarehouseId,1L,"重试补领").getOutboundId());
        outbounds.transferMaterialIssueToLineSide(issue.getOutboundId(),location,1L,"补料转线边");
        session.clearCache();
        assertDoesNotThrow(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"requireMaterialReady",wo));
        assertEquals(0,new BigDecimal("12").compareTo(jdbc.queryForObject(
                "SELECT issued_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",
                BigDecimal.class,wo,inputMaterial)));
        assertThrows(BusinessException.class,()->outbounds.generateMaterialIssue(wo,rawWarehouseId,1L,"不得重复多领"));
    }

    @Test void existingInspectionMustUseQualityDisposition() {
        finishedDefect();
        jdbc.update("INSERT INTO qms_inspection_order(inspection_no,inspection_type,work_order_id,task_id,status,deleted_flag) VALUES(?,'PROCESS',?,?,'DRAFT',0)",tag(),wo,task);
        assertTrue(assertThrows(BusinessException.class,()->resolution.resolve(task,"SCRAP","不能重复处置",1L)).getMessage().contains("检验单"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_task WHERE source_task_id=?",Integer.class,task));
    }

    @Test void defectsInARecoveryTaskCanBeResolvedAgainWithoutRepeatingOriginalDemand() {
        finishedDefect();
        var first=resolution.resolve(task,"SCRAP","首次补产两件",1L).getFirst();
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=1,defect_quantity=1 WHERE task_id=?",first.getTaskId());
        Long report=insertReport("RECOVERY-BAD",first.getTaskId(),BigDecimal.ONE,"APPROVED","2026-10-27 09:00:00");
        jdbc.update("UPDATE mfg_work_report SET defect_quantity=1 WHERE report_id=?",report);session.clearCache();
        var second=resolution.resolve(first.getTaskId(),"SCRAP","补产中一件仍无法修复",1L).getFirst();
        assertEquals(first.getTaskId(),second.getDefectResolutionTaskId());
        assertEquals(0,BigDecimal.ONE.compareTo(second.getPlannedQuantity()));
        assertEquals(0,new BigDecimal("13").compareTo(jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,wo,inputMaterial)));
    }

    @Test void stockConsumptionSpansEveryLocationAndWritesMatchingMovements() {
        Long report=insertReport("CONSUME",task,BigDecimal.TEN,"SUBMITTED","2026-10-27 08:00:00");
        jdbc.update("INSERT INTO mfg_report_material(report_id,material_id,batch_id,used_quantity,scrap_quantity,deleted_flag) VALUES(?,?,?,10,0,0)",report,inputMaterial,batch);
        session.clearCache();
        assertTrue(reportService.auditWorkReport(report,"APPROVED","核对两库位消耗",1L));
        assertEquals(0,BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT SUM(locked_quantity) FROM wms_inventory WHERE work_order_id=? AND material_id=?",BigDecimal.class,wo,inputMaterial)));
        assertEquals(0,BigDecimal.TEN.compareTo(jdbc.queryForObject("SELECT SUM(quantity) FROM wms_stock_movement WHERE source_order_type='WORK_REPORT' AND source_order_id=? AND movement_type='CONSUME'",BigDecimal.class,report)));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM wms_stock_movement WHERE source_order_type='WORK_REPORT' AND source_order_id=? AND movement_type='CONSUME'",Integer.class,report));
    }

    @Test void inclusiveUsageHttpSubmissionAndAuditConsumeTotalOnlyOnce() throws Exception {
        Long actor=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0",Long.class);
        Long station=jdbc.queryForObject("SELECT station_id FROM factory_station WHERE deleted_flag=0 LIMIT 1",Long.class);
        jdbc.update("UPDATE mfg_task SET station_id=? WHERE task_id=?",station,task);session.clearCache();
        var body=json.createObjectNode().put("taskId",task).put("qualifiedQuantity",9).put("defectQuantity",1)
                .put("defectDescription","演练：一件外壳破损，无法修复");
        body.putArray("materials").addObject().put("materialId",inputMaterial).put("batchId",batch)
                .put("totalUsedQuantity",10).put("scrapQuantity",1);
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mfg/work-report/full")
                        .header("Authorization","Bearer "+jwt.generateToken(actor,"admin"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200)).andReturn();
        Long report=json.readTree(response.getResponse().getContentAsString()).path("data").path("reportId").longValue();
        assertEquals("演练：一件外壳破损，无法修复",reportService.getWorkReportById(report).getDefectDescription());
        var stored=reportMaterials.listReportMaterialsByReportId(report).getFirst();
        assertEquals(0,new BigDecimal("9").compareTo(stored.getUsedQuantity()));
        assertEquals(0,BigDecimal.ONE.compareTo(stored.getScrapQuantity()));
        assertEquals(0,BigDecimal.TEN.compareTo(stored.getTotalUsedQuantity()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mfg/report-material/report/"+report)
                        .header("Authorization","Bearer "+jwt.generateToken(actor,"admin")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].totalUsedQuantity").value(10));
        assertTrue(reportService.auditWorkReport(report,"APPROVED","总用料10其中报废1",actor));
        assertEquals(0,BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT SUM(locked_quantity) FROM wms_inventory WHERE work_order_id=? AND material_id=?",BigDecimal.class,wo,inputMaterial)));
        assertEquals(0,BigDecimal.TEN.compareTo(jdbc.queryForObject("SELECT SUM(quantity) FROM wms_stock_movement WHERE source_order_type='WORK_REPORT' AND source_order_id=? AND movement_type='CONSUME'",BigDecimal.class,report)));
        assertEquals(0,BigDecimal.TEN.compareTo(jdbc.queryForObject("SELECT SUM(used_quantity) FROM trace_material_usage WHERE work_order_id=? AND material_id=? AND deleted_flag=0",BigDecimal.class,wo,inputMaterial)));
    }

    @Test void scrapAboveInclusiveTotalIsRejectedBeforeCreatingReport() throws Exception {
        Long actor=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0",Long.class);
        Long station=jdbc.queryForObject("SELECT station_id FROM factory_station WHERE deleted_flag=0 LIMIT 1",Long.class);
        jdbc.update("UPDATE mfg_task SET station_id=? WHERE task_id=?",station,task);session.clearCache();
        var body=json.createObjectNode().put("taskId",task).put("qualifiedQuantity",9).put("defectQuantity",1)
                .put("defectDescription","演练：一件损坏");
        body.putArray("materials").addObject().put("materialId",inputMaterial).put("batchId",batch)
                .put("totalUsedQuantity",10).put("scrapQuantity",11);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mfg/work-report/full")
                        .header("Authorization","Bearer "+jwt.generateToken(actor,"admin"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_report WHERE task_id=?",Integer.class,task));
    }

    @Test void legacyReportMaterialDisplaysInclusiveTotalAndCanBeEditedWithoutDoubleCounting() {
        Long report=insertReport("LEGACY-MATERIAL",task,new BigDecimal("8"),"SUBMITTED","2026-10-27 08:00:00");
        jdbc.update("INSERT INTO mfg_report_material(report_id,material_id,batch_id,used_quantity,scrap_quantity,deleted_flag) VALUES(?,?,?,8,2,0)",report,inputMaterial,batch);
        var stored=reportMaterials.listReportMaterialsByReportId(report).getFirst();
        assertEquals(0,BigDecimal.TEN.compareTo(stored.getTotalUsedQuantity()));
        assertEquals(0,new BigDecimal("8").compareTo(stored.getUsedQuantity()));
        var update=new com.neuedu.ems.entity.mfg.MfgReportMaterial();
        update.setId(stored.getId());update.setTotalUsedQuantity(BigDecimal.TEN);update.setScrapQuantity(new BigDecimal("3"));
        assertTrue(reportMaterials.updateReportMaterial(update));session.clearCache();
        var edited=reportMaterials.getReportMaterialById(stored.getId());
        assertEquals(0,BigDecimal.TEN.compareTo(edited.getTotalUsedQuantity()));
        assertEquals(0,new BigDecimal("7").compareTo(edited.getUsedQuantity()));
    }

    @Test void reportingDefectsWithoutDescriptionIsRejectedWithoutPersisting() throws Exception {
        Long actor=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0",Long.class);
        Long station=jdbc.queryForObject("SELECT station_id FROM factory_station WHERE deleted_flag=0 LIMIT 1",Long.class);
        jdbc.update("UPDATE mfg_task SET station_id=? WHERE task_id=?",station,task);session.clearCache();
        var body=json.createObjectNode().put("taskId",task).put("qualifiedQuantity",9).put("defectQuantity",1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mfg/work-report/full")
                        .header("Authorization","Bearer "+jwt.generateToken(actor,"admin"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(com.neuedu.ems.common.ResultCode.PARAM_ERROR.getCode()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("不良情况")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_report WHERE task_id=?",Integer.class,task));
    }

    @Test void taskMaterialsFollowFrozenInputOperationAndDoNotRepeatPredecessorMaterials() {
        var order=orders.selectById(wo);ExecutionSnapshotTestData.attach(order,agingSnapshot(wo));orders.updateById(order);
        Long next=insertTask("NO-DIRECT-INPUT",wo,2L,BigDecimal.TEN,"WAIT_START");session.clearCache();
        assertEquals(List.of(inputMaterial),preparation.materialRequirements(task).stream().map(r->r.getMaterialId()).toList());
        assertTrue(preparation.materialRequirements(next).isEmpty());
        jdbc.update("UPDATE mfg_task SET deleted_flag=1 WHERE task_id=?",next);session.clearCache();
        assertThrows(BusinessException.class,()->preparation.materialRequirements(next));
    }

    private Long conflictingFinalInspection() {
        Long report=insertReport("SOURCE-DEFECT",task,new BigDecimal("8"),"APPROVED","2026-10-27 08:00:00");
        jdbc.update("UPDATE mfg_work_report SET defect_quantity=2,defect_description='演练：两件封装破损' WHERE report_id=?",report);
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=8,defect_quantity=2 WHERE task_id=?",task);
        Long lot=insertLot("DEFECT-LOT",product,wo,report,BigDecimal.TEN);
        jdbc.update("UPDATE trace_product_lot SET qualified_quantity=8,defect_quantity=2,status='PASS' WHERE product_lot_id=?",lot);
        Long inspection=insertInspection("CONFLICT","FINAL",wo,lot,BigDecimal.TEN,"PASS","FINISHED","2026-10-27 09:00:00");
        jdbc.update("UPDATE qms_inspection_order SET task_id=?,report_id=? WHERE inspection_id=?",task,report,inspection);
        jdbc.update("INSERT INTO qms_inspection_result(inspection_id,item_id,piece_no,result,deleted_flag) VALUES(?,1,'TEST-PIECE-1','PASS',0)",inspection);
        session.clearCache();return inspection;
    }

    @Test void historicalConflictCreatesReinspectionAndKeepsOriginalEvidenceIntact() {
        Long original=conflictingFinalInspection();
        var before=inspections.getInspectionOrderById(original);assertTrue(before.getProductionDefectConflict());
        var reopened=inspections.reopenProductionDefects(original,1L,"按生产记录核对两件不良");session.clearCache();
        assertNotEquals(original,reopened.getInspectionId());assertEquals("DRAFT",reopened.getStatus());
        assertEquals(0,new BigDecimal("2").compareTo(reopened.getSourceDefectQuantity()));
        assertEquals("演练：两件封装破损",reopened.getSourceDefectDescription());
        var old=inspections.getInspectionOrderById(original);
        assertEquals("CLOSED",old.getStatus());assertEquals("PASS",old.getInspectionResult());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qms_inspection_result WHERE inspection_id=? AND deleted_flag=0 AND piece_no='TEST-PIECE-1' AND result='PASS'",Integer.class,original));
        assertEquals("WAIT_INSPECTION",jdbc.queryForObject("SELECT status FROM trace_product_lot WHERE product_lot_id=?",String.class,reopened.getProductLotId()));
        assertThrows(BusinessException.class,()->inspections.reopenProductionDefects(original,1L,"重复点击"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM qms_inspection_order WHERE report_id=?",Integer.class,before.getReportId()));
    }

    @Test void historicalConflictCannotBeReopenedAfterInboundDocumentExists() {
        Long original=conflictingFinalInspection();var inspection=inspections.getInspectionOrderById(original);
        insertInbound("PRODUCT_LOT",inspection.getProductLotId(),"SUBMITTED","2026-10-27 09:30:00");session.clearCache();
        assertTrue(assertThrows(BusinessException.class,()->inspections.reopenProductionDefects(original,1L,"已有下游"))
                .getMessage().contains("入库"));
        assertEquals("FINISHED",inspections.getInspectionOrderById(original).getStatus());
    }

    @Test void qualityScrapRemakesPredecessorsOnceAndRetainsOriginalGoodQuantity() {
        var order=orders.selectById(wo);ExecutionSnapshotTestData.attach(order,agingSnapshot(wo));orders.updateById(order);
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=10 WHERE task_id=?",task);
        Long next=insertTask("SCRAP-FINAL",wo,2L,BigDecimal.TEN,"FINISHED");
        jdbc.update("UPDATE mfg_task SET dispatch_id=?,completed_quantity=8,defect_quantity=2 WHERE task_id=?",dispatch,next);
        Long report=insertReport("LAST-BAD",next,new BigDecimal("8"),"APPROVED","2026-10-27 08:00:00");
        jdbc.update("UPDATE mfg_work_report SET defect_quantity=2,defect_description='演练：两件损坏' WHERE report_id=?",report);
        Long lot=insertLot("PARTIAL",product,wo,report,BigDecimal.TEN);
        jdbc.update("UPDATE trace_product_lot SET qualified_quantity=8,defect_quantity=2,status='FAIL' WHERE product_lot_id=?",lot);
        Long qi=insertInspection("BAD-FINAL","FINAL",wo,lot,BigDecimal.TEN,"FAIL","FINISHED","2026-10-27 09:00:00");
        jdbc.update("UPDATE qms_inspection_order SET task_id=?,report_id=?,qualified_quantity=8,unqualified_quantity=2 WHERE inspection_id=?",next,report,qi);
        session.clearCache();
        var ncr=nonconforming.generateFromInspection(qi,"SCRAP","封装损坏，必须重做",new BigDecimal("2"),1L,"演练");
        assertTrue(nonconforming.approveNonconformingOrder(ncr.getNcrId(),"APPROVED","核对不良",1L));
        Long first=nonconforming.completeDisposition(ncr.getNcrId(),false,1L,"两件报废，重新装配");session.clearCache();
        var recovery=jdbc.queryForList("SELECT task_id,operation_id,planned_quantity FROM mfg_task WHERE source_ncr_id=? AND deleted_flag=0 ORDER BY task_id",ncr.getNcrId());
        assertEquals(2,recovery.size());assertEquals(first,((Number)recovery.get(0).get("task_id")).longValue());
        assertTrue(recovery.stream().allMatch(r->new BigDecimal("2").compareTo((BigDecimal)r.get("planned_quantity"))==0));
        assertEquals("PASS",jdbc.queryForObject("SELECT status FROM trace_product_lot WHERE product_lot_id=?",String.class,lot));
        assertEquals(0,new BigDecimal("8").compareTo(jdbc.queryForObject("SELECT qualified_quantity FROM trace_product_lot WHERE product_lot_id=?",BigDecimal.class,lot)));
        assertEquals(0,new BigDecimal("12").compareTo(jdbc.queryForObject("SELECT required_quantity FROM mfg_work_order_material WHERE work_order_id=? AND material_id=?",BigDecimal.class,wo,inputMaterial)));
        assertEquals(first,nonconforming.completeDisposition(ncr.getNcrId(),false,1L,"重试不能重复补产"));
        session.clearCache();
        var withDisposition = inspections.getInspectionOrderById(qi);
        assertEquals(ncr.getNcrId(), withDisposition.getNonconformingOrderId());
        assertEquals("CLOSED", withDisposition.getNonconformingOrderStatus());
        Long last=((Number)recovery.get(1).get("task_id")).longValue();
        var target=org.springframework.test.util.AopTestUtils.getTargetObject(taskService);
        assertTrue(assertThrows(BusinessException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,
                "requirePredecessorsFinished",taskService.getTaskById(last),orders.selectById(wo))).getMessage().contains("前置"));
        assertEquals("RUNNING",orders.selectById(wo).getStatus());
        assertEquals(0,new BigDecimal("8").compareTo(orders.selectById(wo).getCompletedQuantity()));
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=2 WHERE task_id IN (?,?)",first,last);
        insertReport("REMAKE-FIRST",first,new BigDecimal("2"),"APPROVED","2026-10-27 10:00:00");
        Long recoveredReport=insertReport("REMAKE-LAST",last,new BigDecimal("2"),"APPROVED","2026-10-27 11:00:00");
        Long recoveredLot=insertLot("REMADE",product,wo,recoveredReport,new BigDecimal("2"));
        jdbc.update("UPDATE trace_product_lot SET status='PASS' WHERE product_lot_id=?",recoveredLot);
        insertInspection("REMADE","FINAL",wo,recoveredLot,new BigDecimal("2"),"PASS","FINISHED","2026-10-27 11:30:00");
        session.clearCache();reportService.recalculateWorkOrderCompletion(wo,1L,"原合格8加补产合格2");session.clearCache();
        assertEquals("FINISHED",orders.selectById(wo).getStatus());
        assertEquals(0,BigDecimal.TEN.compareTo(orders.selectById(wo).getCompletedQuantity()));
    }

    @Test void upstreamDefectsBlockNextOperationUntilGoodQuantityIsRecovered() {
        var order=orders.selectById(wo);
        ExecutionSnapshotTestData.attach(order,agingSnapshot(wo));orders.updateById(order);
        finishedDefect();
        Long next=insertTask("NEXT",wo,2L,BigDecimal.TEN,"WAIT_START");
        jdbc.update("UPDATE mfg_task SET dispatch_id=? WHERE task_id=?",dispatch,next);session.clearCache();
        var nextTask=taskService.getTaskById(next);
        var target=org.springframework.test.util.AopTestUtils.getTargetObject(taskService);
        assertTrue(assertThrows(BusinessException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"requirePredecessorsFinished",nextTask,orders.selectById(wo))).getMessage().contains("合格数量不足"));
        var recovery=resolution.resolve(task,"REWORK","修复两件在制品",1L).getFirst();
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=2 WHERE task_id=?",recovery.getTaskId());
        insertReport("RECOVERY",recovery.getTaskId(),new BigDecimal("2"),"APPROVED","2026-10-27 09:00:00");session.clearCache();
        assertDoesNotThrow(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"requirePredecessorsFinished",nextTask,orders.selectById(wo)));
        // A later operation's scrap must be a separate resolution, including upstream remanufacture.
        jdbc.update("UPDATE mfg_task SET status='FINISHED',completed_quantity=8,defect_quantity=2 WHERE task_id=?",next);
        Long last=insertReport("LATER",next,new BigDecimal("8"),"APPROVED","2026-10-27 10:00:00");
        jdbc.update("UPDATE mfg_work_report SET defect_quantity=2 WHERE report_id=?",last);session.clearCache();
        var remanufacture=resolution.resolve(next,"SCRAP","后道损坏需重新生产",1L);
        assertEquals(2,remanufacture.size());
        assertTrue(remanufacture.stream().allMatch(t->Objects.equals(next,t.getDefectResolutionTaskId())));
        assertEquals(2,resolution.resolve(next,"SCRAP","重试",1L).size());
    }
}
