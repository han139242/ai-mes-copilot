package com.neuedu.ems.service.mfg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.service.ai.AiCommandService;
import com.neuedu.ems.entity.mdm.MdmBom;
import com.neuedu.ems.entity.mdm.MdmBomItem;
import com.neuedu.ems.entity.factory.FactoryStation;
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
import com.neuedu.ems.service.wms.WmsSalesOutboundService;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import com.neuedu.ems.service.wms.WmsLocationService;
import com.neuedu.ems.service.wms.WmsMaterialBatchService;
import com.neuedu.ems.service.wms.WmsOutboundOrderService;
import com.neuedu.ems.service.wms.WmsWarehouseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灯头工单：缺料不能领，齐套后领料并转线边，重复请求不重复建单、不重复扣账。
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_[A-Za-z0-9_]+.*")
@Transactional
class LampKittingIssueIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductParameterService parameters;
    @Autowired ProcessRouteService routes;
    @Autowired MdmBomService boms;
    @Autowired MdmBomItemService bomItems;
    @Autowired MfgProductionOrderService orders;
    @Autowired MfgProductionPlanService plans;
    @Autowired MfgWorkOrderService workOrders;
    @Autowired WmsWarehouseService warehouses;
    @Autowired WmsLocationService locations;
    @Autowired WmsMaterialBatchService batches;
    @Autowired WmsInboundOrderService inbounds;
    @Autowired QmsInspectionOrderService inspections;
    @Autowired QmsInspectionSchemeService schemes;
    @Autowired QmsInspectionSchemeItemService schemeItems;
    @Autowired InspectionStandardReviewService standardReview;
    @Autowired WmsSalesOutboundService sales;
    @Autowired WmsOutboundOrderService outbounds;
    @Autowired AiCommandService commands;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MfgDispatchOrderService dispatches;
    @Autowired MfgTaskService tasks;
    @Autowired MfgWorkReportService reports;
    @Autowired FactoryStationService stations;

    @Test
    void shortageBlocksIssueAndCompleteKitTransfersOnce() {
        String tag = "KIT" + System.nanoTime();
        Long actor = jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
        prepareHead();
        Long productId = productId("LAMP-HEAD");
        Long lineId = jdbc.queryForObject(
                "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-HEAD' AND deleted_flag=0", Long.class);
        MfgProductionOrder order = new MfgProductionOrder();
        order.setProductId(productId);
        order.setCustomerId(1L);
        order.setOrderQuantity(BigDecimal.ONE);
        order.setDeliveryDate(LocalDate.now().plusDays(30));
        order.setSourceType("MANUAL");
        assertTrue(orders.saveOrder(order));
        orders.releaseOrder(order.getProductionOrderId(), actor, "灯头排产");
        MfgProductionPlan request = new MfgProductionPlan();
        request.setLineId(lineId);
        request.setPlannedQuantity(BigDecimal.ONE);
        request.setPlannedStartTime(LocalDateTime.now().plusHours(2));
        // 本用例验证领料与幂等；周五下午运行时须为下周工作日留出窗口。
        request.setPlannedEndTime(LocalDateTime.now().plusDays(7));
        MfgProductionPlan plan = plans.generatePlan(order.getProductionOrderId(), request, actor);
        plans.releasePlan(plan.getPlanId(), actor, "灯头计划生效");
        MfgWorkOrder root = workOrders.generateWorkOrder(plan.getPlanId(), new MfgWorkOrder(), actor);
        WmsWarehouse raw = warehouse(tag + "R", "RAW");
        WmsLocation rawLocation = location(raw, tag + "RL");
        assertThrows(BusinessException.class,
                () -> outbounds.generateMaterialIssue(root.getWorkOrderId(), raw.getWarehouseId(), actor, "草稿不能领"));
        workOrders.releaseWorkOrder(root.getWorkOrderId(), actor, "下达灯头工单");
        BusinessException shortKit = assertThrows(BusinessException.class,
                () -> outbounds.generateMaterialIssue(root.getWorkOrderId(), raw.getWarehouseId(), actor, "缺料"));
        assertTrue(shortKit.getMessage().contains("未齐套"));

        stockRequirements(tag, raw, rawLocation, root.getWorkOrderId(), actor);
        String issueKey = tag + "-ISSUE";
        WmsOutboundOrder issued = outbounds.generateMaterialIssue(
                root.getWorkOrderId(), raw.getWarehouseId(), actor, "齐套领料", issueKey);
        WmsOutboundOrder replayed = outbounds.generateMaterialIssue(
                root.getWorkOrderId(), raw.getWarehouseId(), actor, "齐套领料", issueKey);
        assertEquals(issued.getOutboundId(), replayed.getOutboundId());
        assertEquals(1, countIssues(root.getWorkOrderId()));

        WmsWarehouse line = warehouse(tag + "L", "LINE_SIDE");
        WmsLocation lineLocation = location(line, tag + "LL");
        String transferKey = tag + "-MOVE";
        outbounds.transferMaterialIssueToLineSide(
                issued.getOutboundId(), lineLocation.getLocationId(), actor, "转线边", transferKey);
        int movements = movementCount(issued.getOutboundId());
        assertTrue(movements >= 2);
        assertEquals(0, movementNet(issued.getOutboundId()).compareTo(BigDecimal.ZERO));
        BigDecimal lineSide = lineSideQuantity(root.getWorkOrderId());
        assertTrue(lineSide.signum() > 0);
        outbounds.transferMaterialIssueToLineSide(
                issued.getOutboundId(), lineLocation.getLocationId(), actor, "转线边", transferKey);
        assertEquals(movements, movementCount(issued.getOutboundId()));
        assertEquals(0, lineSide.compareTo(lineSideQuantity(root.getWorkOrderId())));

        finishAndStore(tag, actor, order.getProductionOrderId(), root.getWorkOrderId(), plan.getPlannedEndTime());
    }

    /**
     * 多步命令的状态写在独立事务里。业务数据和预览必须先提交，执行才能更新序列，不能和调用方挤在同一个未提交事务里。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void spokenIssueThenLineSideCommitsOnce() {
        String tag = "NL" + System.nanoTime();
        Long headBomId = jdbc.queryForObject("""
                SELECT b.bom_id FROM mdm_bom b JOIN mdm_product p ON p.product_id=b.product_id
                WHERE p.product_code='LAMP-HEAD' AND b.status='RELEASED' AND b.deleted_flag=0
                ORDER BY b.bom_id LIMIT 1
                """, Long.class);
        java.sql.Date previousEffectiveTo = jdbc.queryForObject(
                "SELECT effective_to FROM mdm_bom WHERE bom_id=?", java.sql.Date.class, headBomId);
        Long orderId = null;
        Long workOrderId = null;
        List<Long> displacedLineLocations = new ArrayList<>();
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            long[] created = new long[2];
            tx.executeWithoutResult(status -> {
                Long actor = jdbc.queryForObject(
                        "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
                prepareHead();
                Long productId = productId("LAMP-HEAD");
                Long lineId = jdbc.queryForObject(
                        "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-HEAD' AND deleted_flag=0", Long.class);
                MfgProductionOrder order = new MfgProductionOrder();
                order.setProductId(productId);
                order.setCustomerId(1L);
                order.setOrderQuantity(BigDecimal.ONE);
                order.setDeliveryDate(java.time.LocalDate.now().plusDays(30));
                order.setSourceType("MANUAL");
                assertTrue(orders.saveOrder(order));
                orders.releaseOrder(order.getProductionOrderId(), actor, "灯头排产");
                MfgProductionPlan request = new MfgProductionPlan();
                request.setLineId(lineId);
                request.setPlannedQuantity(BigDecimal.ONE);
                request.setPlannedStartTime(java.time.LocalDateTime.now().plusHours(2));
                request.setPlannedEndTime(java.time.LocalDateTime.now().plusDays(7));
                MfgProductionPlan plan = plans.generatePlan(order.getProductionOrderId(), request, actor);
                plans.releasePlan(plan.getPlanId(), actor, "灯头计划生效");
                MfgWorkOrder root = workOrders.generateWorkOrder(plan.getPlanId(), new MfgWorkOrder(), actor);
                workOrders.releaseWorkOrder(root.getWorkOrderId(), actor, "下达灯头工单");
                WmsWarehouse raw = warehouse(tag + "R", "RAW");
                stockRequirements(tag, raw, location(raw, tag + "RL"), root.getWorkOrderId(), actor);
                WmsLocation ownLine=location(warehouse(tag + "L", "LINE_SIDE"), tag + "LL");
                // 自然语言未指定库位时只能使用唯一产线推荐，不能从多个共享库位中猜选。
                jdbc.update("UPDATE wms_location SET line_id=? WHERE location_id=?",lineId,ownLine.getLocationId());
                // 验证库里这条产线可能已有历史专线库位。测这一步时先挪开，结束后放回。
                displacedLineLocations.addAll(claimOnlyDedicatedLocation(lineId, ownLine.getLocationId()));
                created[0] = order.getProductionOrderId();
                created[1] = root.getWorkOrderId();
            });
            orderId = created[0];
            workOrderId = created[1];
            String workOrderNo = jdbc.queryForObject(
                    "SELECT work_order_no FROM mfg_work_order WHERE work_order_id=?", String.class, workOrderId);
            Long actor = jdbc.queryForObject(
                    "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
            AuthorizationContext auth = new AuthorizationContext(actor, null, null, "ALL",
                    Set.of("SUPER_ADMIN"), Set.of(), Set.of());
            String sentence = "给 " + workOrderNo + " 生成领料单然后转到线边仓";
            Map<String, Object> preview = commands.previewSequence(sentence, null, "API", Map.of(), auth);
            assertEquals(2, preview.get("totalSteps"));
            String planId = String.valueOf(preview.get("planId"));
            Map<String, Object> done = commands.executeSequence(
                    new AiCommandService.AiCommandSequenceExecuteRequest(planId, tag, null, "齐套办理"), auth);
            assertEquals("SUCCEEDED", done.get("status"),String.valueOf(done));
            assertEquals(2, done.get("executedSteps"));
            assertEquals(1, countIssues(workOrderId));
            Long outboundId = jdbc.queryForObject(
                    "SELECT outbound_id FROM wms_outbound_order WHERE work_order_id=? AND outbound_type='MATERIAL_ISSUE' AND deleted_flag=0",
                    Long.class, workOrderId);
            int movements = movementCount(outboundId);
            assertTrue(movements >= 2);
            assertEquals(0, movementNet(outboundId).compareTo(BigDecimal.ZERO));
            Map<String, Object> replayed = commands.executeSequence(
                    new AiCommandService.AiCommandSequenceExecuteRequest(planId, tag, null, "齐套办理"), auth);
            assertEquals("SUCCEEDED", replayed.get("status"));
            assertEquals(1, countIssues(workOrderId));
            assertEquals(movements, movementCount(outboundId));
        } finally {
            restoreDedicatedLocations(displacedLineLocations);
            if (orderId != null) {
                forgetSpokenRun(orderId, tag);
            }
            jdbc.update("UPDATE mdm_bom SET effective_to=? WHERE bom_id=?", previousEffectiveTo, headBomId);
            jdbc.update("DELETE FROM mdm_bom_item WHERE bom_id IN (SELECT bom_id FROM (SELECT bom_id FROM mdm_bom WHERE bom_code='LAMP-BOM-HEAD-V2') x)");
            jdbc.update("DELETE FROM mdm_bom WHERE bom_code='LAMP-BOM-HEAD-V2'");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void spokenIssueResumesAfterTheLineSideStepFails() throws Exception {
        String tag = "RESUME" + System.nanoTime();
        Long headBomId = jdbc.queryForObject("""
                SELECT b.bom_id FROM mdm_bom b JOIN mdm_product p ON p.product_id=b.product_id
                WHERE p.product_code='LAMP-HEAD' AND b.status='RELEASED' AND b.deleted_flag=0
                ORDER BY b.bom_id LIMIT 1
                """, Long.class);
        java.sql.Date previousEffectiveTo = jdbc.queryForObject(
                "SELECT effective_to FROM mdm_bom WHERE bom_id=?", java.sql.Date.class, headBomId);
        Long orderId = null;
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            long[] created = new long[3];
            tx.executeWithoutResult(status -> {
                Long actor = jdbc.queryForObject(
                        "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
                prepareHead();
                Long productId = productId("LAMP-HEAD");
                Long lineId = jdbc.queryForObject(
                        "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-HEAD' AND deleted_flag=0", Long.class);
                MfgProductionOrder order = new MfgProductionOrder();
                order.setProductId(productId);
                order.setCustomerId(1L);
                order.setOrderQuantity(BigDecimal.ONE);
                order.setDeliveryDate(LocalDate.now().plusDays(30));
                order.setSourceType("MANUAL");
                assertTrue(orders.saveOrder(order));
                orders.releaseOrder(order.getProductionOrderId(), actor, "灯头排产");
                MfgProductionPlan request = new MfgProductionPlan();
                request.setLineId(lineId);
                request.setPlannedQuantity(BigDecimal.ONE);
                request.setPlannedStartTime(LocalDateTime.now().plusHours(2));
                request.setPlannedEndTime(LocalDateTime.now().plusDays(7));
                MfgProductionPlan plan = plans.generatePlan(order.getProductionOrderId(), request, actor);
                plans.releasePlan(plan.getPlanId(), actor, "灯头计划生效");
                MfgWorkOrder root = workOrders.generateWorkOrder(plan.getPlanId(), new MfgWorkOrder(), actor);
                workOrders.releaseWorkOrder(root.getWorkOrderId(), actor, "下达灯头工单");
                WmsWarehouse raw = warehouse(tag + "R", "RAW");
                stockRequirements(tag, raw, location(raw, tag + "RL"), root.getWorkOrderId(), actor);
                created[0] = order.getProductionOrderId();
                created[1] = root.getWorkOrderId();
                created[2] = location(raw, tag + "BAD").getLocationId();
                location(warehouse(tag + "L", "LINE_SIDE"), tag + "LL");
            });
            orderId = created[0];
            long workOrderId = created[1];
            long badLocationId = created[2];
            long goodLocationId = jdbc.queryForObject(
                    "SELECT location_id FROM wms_location WHERE location_code=? AND deleted_flag=0",
                    Long.class, tag + "LL");
            String workOrderNo = jdbc.queryForObject(
                    "SELECT work_order_no FROM mfg_work_order WHERE work_order_id=?", String.class, workOrderId);
            Long actor = jdbc.queryForObject(
                    "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
            AuthorizationContext auth = new AuthorizationContext(actor, null, null, "ALL",
                    Set.of("SUPER_ADMIN"), Set.of(), Set.of());
            String sentence = "给 " + workOrderNo + " 生成领料单然后转到线边仓";
            Map<String, Object> preview = commands.previewSequence(sentence, null, "API", Map.of(), auth);
            String planId = String.valueOf(preview.get("planId"));
            pointTransferAt(planId, badLocationId);
            Map<String, Object> failed = commands.executeSequence(
                    new AiCommandService.AiCommandSequenceExecuteRequest(planId, tag, null, "先领料"), auth);
            assertEquals("PARTIAL_FAILED", failed.get("status"));
            assertEquals(1, failed.get("executedSteps"));
            assertEquals(1, countIssues(workOrderId));

            pointTransferAt(planId, goodLocationId);
            Map<String, Object> resumed = commands.executeSequence(
                    new AiCommandService.AiCommandSequenceExecuteRequest(planId, tag, null, "续跑转线边"), auth);
            assertEquals("SUCCEEDED", resumed.get("status"), String.valueOf(resumed.get("errorMessage")));
            assertEquals(2, resumed.get("executedSteps"));
            assertEquals(1, countIssues(workOrderId));
            Long outboundId = jdbc.queryForObject(
                    "SELECT outbound_id FROM wms_outbound_order WHERE work_order_id=? AND outbound_type='MATERIAL_ISSUE' AND deleted_flag=0",
                    Long.class, workOrderId);
            assertTrue(movementCount(outboundId) >= 2);
            assertEquals(0, movementNet(outboundId).compareTo(BigDecimal.ZERO));
        } finally {
            if (orderId != null) {
                forgetSpokenRun(orderId, tag);
            }
            jdbc.update("UPDATE mdm_bom SET effective_to=? WHERE bom_id=?", previousEffectiveTo, headBomId);
            jdbc.update("DELETE FROM mdm_bom_item WHERE bom_id IN (SELECT bom_id FROM (SELECT bom_id FROM mdm_bom WHERE bom_code='LAMP-BOM-HEAD-V2') x)");
            jdbc.update("DELETE FROM mdm_bom WHERE bom_code='LAMP-BOM-HEAD-V2'");
        }
    }

    private void pointTransferAt(String planId, long locationId) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = jdbc.queryForObject(
                "SELECT plan_json FROM ai_command_sequence WHERE sequence_id=?", String.class, planId);
        List<Map<String, Object>> steps = mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
        });
        for (Map<String, Object> step : steps) {
            if (!"TRANSFER_TO_LINE_SIDE".equals(step.get("action"))) {
                continue;
            }
            Object params = step.get("params");
            if (params instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> writable = (Map<String, Object>) map;
                writable.put("targetLocationId", locationId);
            }
        }
        jdbc.update("UPDATE ai_command_sequence SET plan_json=? WHERE sequence_id=?",
                mapper.writeValueAsString(steps), planId);
    }

    private void forgetSpokenRun(Long orderId, String tag) {
        jdbc.update("DELETE FROM ai_command_execution WHERE sequence_id IN (SELECT sequence_id FROM (SELECT sequence_id FROM ai_command_sequence WHERE idempotency_key=?) s)", tag);
        jdbc.update("DELETE FROM ai_command_sequence WHERE idempotency_key=?", tag);
        jdbc.update("DELETE m FROM wms_stock_movement m JOIN mfg_work_order w ON w.work_order_id=m.work_order_id JOIN mfg_production_plan p ON p.plan_id=w.plan_id WHERE p.production_order_id=?", orderId);
        jdbc.update("DELETE d FROM wms_outbound_detail d JOIN wms_outbound_order o ON o.outbound_id=d.outbound_id JOIN mfg_work_order w ON w.work_order_id=o.work_order_id JOIN mfg_production_plan p ON p.plan_id=w.plan_id WHERE p.production_order_id=?", orderId);
        jdbc.update("DELETE o FROM wms_outbound_order o JOIN mfg_work_order w ON w.work_order_id=o.work_order_id JOIN mfg_production_plan p ON p.plan_id=w.plan_id WHERE p.production_order_id=?", orderId);
        jdbc.update("DELETE d FROM wms_inbound_detail d JOIN wms_inbound_order o ON o.inbound_id=d.inbound_id WHERE o.inbound_no LIKE ?", tag + "%");
        jdbc.update("DELETE FROM wms_inbound_order WHERE inbound_no LIKE ?", tag + "%");
        jdbc.update("DELETE i FROM wms_inventory i JOIN wms_material_batch b ON b.batch_id=i.batch_id WHERE b.batch_no LIKE ?", tag + "%");
        jdbc.update("DELETE FROM wms_material_batch WHERE batch_no LIKE ?", tag + "%");
        jdbc.update("DELETE FROM wms_location WHERE location_code LIKE ?", tag + "%");
        jdbc.update("DELETE FROM wms_warehouse WHERE warehouse_code LIKE ?", tag + "%");
        jdbc.update("DELETE r FROM mfg_work_order_material r JOIN mfg_work_order w ON w.work_order_id=r.work_order_id JOIN mfg_production_plan p ON p.plan_id=w.plan_id WHERE p.production_order_id=?", orderId);
        jdbc.update("DELETE w FROM mfg_work_order w JOIN mfg_production_plan p ON p.plan_id=w.plan_id WHERE p.production_order_id=?", orderId);
        jdbc.update("DELETE FROM mfg_production_plan WHERE production_order_id=?", orderId);
        jdbc.update("DELETE FROM mfg_production_order WHERE production_order_id=?", orderId);
    }

    private void finishAndStore(String tag, Long actor, Long productionOrderId, Long workOrderId, LocalDateTime planEnd) {
        Long teamId = jdbc.queryForObject(
                "SELECT team_id FROM org_team WHERE team_code='LAMP-TEAM-COMPONENT' AND deleted_flag=0", Long.class);
        MfgDispatchOrder request = new MfgDispatchOrder();
        request.setTeamId(teamId);
        request.setDueTime(planEnd.minusHours(1));
        enableHeadStation();
        com.neuedu.ems.support.DispatchScheduleTestData.coverWorkOrder(jdbc, workOrderId, teamId);
        MfgDispatchOrder dispatch = dispatches.generateDispatchOrder(workOrderId, request, actor);
        dispatches.releaseDispatchOrder(dispatch.getDispatchId(), actor, "派发灯头");
        List<Long> taskIds = jdbc.queryForList(
                "SELECT task_id FROM mfg_task WHERE work_order_id=? AND deleted_flag=0 ORDER BY task_id",
                Long.class, workOrderId);
        assertTrue(taskIds.size() >= 2);
        for (int index = 0; index < taskIds.size(); index++) {
            Long taskId = taskIds.get(index);
            Long operationId = jdbc.queryForObject(
                    "SELECT operation_id FROM mfg_task WHERE task_id=?", Long.class, taskId);
            Long stationId = jdbc.queryForObject("""
                    SELECT s.station_id FROM factory_station s
                    JOIN factory_station_operation so ON so.station_id=s.station_id AND so.deleted_flag=0 AND so.status='ENABLE'
                    WHERE s.deleted_flag=0 AND s.status='RUNNING' AND so.operation_id=? LIMIT 1
                    """, Long.class, operationId);
            assertTrue(tasks.startTask(taskId, stationId, actor));
            MfgWorkReport report = new MfgWorkReport();
            report.setTaskId(taskId);
            report.setStationId(stationId);
            report.setQualifiedQuantity(BigDecimal.ONE);
            report.setDefectQuantity(BigDecimal.ZERO);
            report.setActualWorkHours(new BigDecimal("0.5"));
            boolean last = index == taskIds.size() - 1;
            Long reportId = reports.submitWorkReport(report, last ? consumedMaterials(workOrderId) : List.of(), actor);
            assertTrue(reports.auditWorkReport(reportId, "APPROVED", "审核通过", actor));
            assertFalse(reports.auditWorkReport(reportId, "APPROVED", "重复审核", actor));
        }
        Long lotId = jdbc.queryForObject(
                "SELECT product_lot_id FROM trace_product_lot WHERE work_order_id=? AND deleted_flag=0",
                Long.class, workOrderId);
        WmsWarehouse finished = warehouse(tag + "F", "FINISHED");
        WmsLocation finishedLocation = location(finished, tag + "FL");
        assertThrows(BusinessException.class, () -> inbounds.generateProductionInbound(
                lotId, finished.getWarehouseId(), finishedLocation.getLocationId(), actor, "未终检"));
        Long inspectionId = jdbc.queryForObject(
                "SELECT inspection_id FROM qms_inspection_order WHERE product_lot_id=? AND inspection_type='FINAL' AND deleted_flag=0",
                Long.class, lotId);
        assertTrue(submitFinalInspection(inspectionId, actor, "终检合格"));
        var inbound = inbounds.generateProductionInbound(
                lotId, finished.getWarehouseId(), finishedLocation.getLocationId(), actor, "成品入库");
        assertTrue(inbounds.confirmInboundOrder(inbound.getInboundId(), actor, "确认成品入库"));
        assertThrows(BusinessException.class,
                () -> inbounds.confirmInboundOrder(inbound.getInboundId(), actor, "重复确认"));
        assertEquals(0, BigDecimal.ONE.compareTo(finishedQuantity(workOrderId)));
        ship(tag, actor, productionOrderId, finished, finishedLocation);
    }

    /** 灯头原来没有出货检验。先建草稿、挂上已确认标准再发布，未检不能发货。 */
    private void ship(String tag, Long actor, Long productionOrderId, WmsWarehouse finished, WmsLocation finishedLocation) {
        Long productId = productId("LAMP-HEAD");
        QmsInspectionScheme scheme = new QmsInspectionScheme();
        scheme.setSchemeCode(tag + "-OQC");
        scheme.setSchemeName("灯头出货检验");
        scheme.setProductId(productId);
        scheme.setInspectionType("OUTGOING");
        scheme.setStatus("DRAFT");
        assertTrue(schemes.saveScheme(scheme));
        int sequence = 1;
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT standard_id, item_id FROM qms_inspection_standard
                WHERE product_id=? AND deleted_flag=0 AND confirmation_status='CONFIRMED'
                ORDER BY standard_id
                """, productId)) {
            QmsInspectionSchemeItem item = new QmsInspectionSchemeItem();
            item.setSchemeId(scheme.getSchemeId());
            item.setStandardId(((Number) row.get("standard_id")).longValue());
            item.setItemId(((Number) row.get("item_id")).longValue());
            item.setSequenceNo(sequence++);
            item.setSamplingMode("ALL");
            item.setSampleQuantity(BigDecimal.ZERO);
            assertTrue(schemeItems.saveSchemeItem(item));
        }
        QmsInspectionScheme publish = new QmsInspectionScheme();
        publish.setSchemeId(scheme.getSchemeId());
        publish.setStatus("RELEASED");
        assertTrue(schemes.updateScheme(publish));

        var outbound = sales.generateSalesOutbound(
                productionOrderId, 1L, finished.getWarehouseId(), finishedLocation.getLocationId(), "灯头发货", actor);
        assertThrows(BusinessException.class, () -> sales.confirmSalesOutbound(outbound.getOutboundId(), actor, "未检不能发"));
        Long inspectionId = sales.createOutgoingInspection(outbound.getOutboundId(), actor);
        assertTrue(submitFinalInspection(inspectionId, actor, "出货合格"));
        assertTrue(sales.confirmSalesOutbound(outbound.getOutboundId(), actor, "确认发货"));
        assertThrows(BusinessException.class, () -> sales.confirmSalesOutbound(outbound.getOutboundId(), actor, "重复发货"));
        var replayed = sales.generateSalesOutbound(
                productionOrderId, 1L, finished.getWarehouseId(), finishedLocation.getLocationId(), "灯头发货", actor);
        assertEquals(outbound.getOutboundId(), replayed.getOutboundId());
        assertEquals(0, BigDecimal.ZERO.compareTo(finishedQuantity(
                jdbc.queryForObject("SELECT work_order_id FROM wms_outbound_order WHERE outbound_id=?", Long.class, outbound.getOutboundId()))));
    }

    private void enableHeadStation() {
        Long stationId = jdbc.queryForObject(
                "SELECT station_id FROM factory_station WHERE station_code='WS-HEAD-01' AND deleted_flag=0", Long.class);
        if (!"RUNNING".equals(jdbc.queryForObject(
                "SELECT status FROM factory_station WHERE station_id=?", String.class, stationId))) {
            FactoryStation station = new FactoryStation();
            station.setStationId(stationId);
            station.setStatus("RUNNING");
            assertTrue(stations.updateStation(station));
        }
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

    private BigDecimal finishedQuantity(Long workOrderId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(i.available_quantity),0) FROM wms_inventory i
                JOIN wms_warehouse w ON w.warehouse_id=i.warehouse_id
                JOIN trace_product_lot lot ON lot.work_order_id=?
                JOIN mdm_product p ON p.product_id=lot.product_id
                WHERE i.material_id=p.material_id AND w.warehouse_type='FINISHED' AND i.deleted_flag=0
                """, BigDecimal.class, workOrderId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private void prepareHead() {
        Long productId = productId("LAMP-HEAD");
        for (Long id : jdbc.queryForList(
                "SELECT parameter_id FROM mdm_product_parameter WHERE product_id=? AND deleted_flag=0 AND required_flag=1 AND status='DRAFT'",
                Long.class, productId)) {
            Integer version = jdbc.queryForObject(
                    "SELECT version FROM mdm_product_parameter WHERE parameter_id=?", Integer.class, id);
            parameters.review(id, new ProductParameterService.Review(version), 1L, true);
        }
        Long routeId = jdbc.queryForObject(
                "SELECT route_id FROM process_route WHERE route_code='LAMP-ROUTE-HEAD-V01' AND deleted_flag=0", Long.class);
        if ("DRAFT".equals(jdbc.queryForObject("SELECT status FROM process_route WHERE route_id=?", String.class, routeId))) {
            ProcessRoute route = new ProcessRoute();
            route.setRouteId(routeId);
            route.setStatus("RELEASED");
            assertTrue(routes.updateRoute(route));
        }
        Integer unbound = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mdm_bom_item i JOIN mdm_bom b ON b.bom_id=i.bom_id
                WHERE b.product_id=? AND b.status='RELEASED' AND b.deleted_flag=0 AND i.deleted_flag=0
                  AND i.consumption_operation_id IS NULL
                """, Integer.class, productId);
        if (unbound == null || unbound == 0) {
            releaseHeadFinalScheme();
            return;
        }
        Long oldBom = jdbc.queryForObject(
                "SELECT bom_id FROM mdm_bom WHERE product_id=? AND status='RELEASED' AND deleted_flag=0 ORDER BY bom_id LIMIT 1",
                Long.class, productId);
        Long operationId = jdbc.queryForObject(
                "SELECT operation_id FROM process_operation WHERE operation_code='LAMP-OP-H10' AND deleted_flag=0", Long.class);
        MdmBom created = new MdmBom();
        created.setBomCode("LAMP-BOM-HEAD-V2");
        created.setBomVersion("V2");
        created.setProductId(productId);
        created.setEffectiveDate(LocalDate.of(2026, 10, 19));
        created.setRemark("灯头补齐投料工序");
        assertTrue(boms.saveBom(created));
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT material_id,quantity,loss_rate,sequence_no FROM mdm_bom_item WHERE bom_id=? AND deleted_flag=0 ORDER BY sequence_no",
                oldBom)) {
            MdmBomItem item = new MdmBomItem();
            item.setBomId(created.getBomId());
            item.setMaterialId(((Number) row.get("material_id")).longValue());
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
        releaseHeadFinalScheme();
    }

    /** 确认灯头终检标准，并把终检方案绑到路线最后一道工序后发布。 */
    private void releaseHeadFinalScheme() {
        Long operationId = jdbc.queryForObject(
                "SELECT operation_id FROM process_operation WHERE operation_code='LAMP-OP-H20' AND deleted_flag=0",
                Long.class);
        Long schemeId = jdbc.queryForObject(
                "SELECT scheme_id FROM qms_inspection_scheme WHERE scheme_code='LAMP-V18-QS-HEAD-FINAL' AND deleted_flag=0",
                Long.class);
        for (Long standardId : jdbc.queryForList("""
                SELECT standard_id FROM qms_inspection_scheme_item
                WHERE scheme_id=? AND deleted_flag=0
                """, Long.class, schemeId)) {
            Integer version = jdbc.queryForObject(
                    "SELECT version FROM qms_inspection_standard WHERE standard_id=?", Integer.class, standardId);
            String status = jdbc.queryForObject(
                    "SELECT confirmation_status FROM qms_inspection_standard WHERE standard_id=?", String.class, standardId);
            if ("DRAFT".equals(status)) {
                standardReview.review(standardId, version, 1L, true);
            }
        }
        String schemeStatus = jdbc.queryForObject(
                "SELECT status FROM qms_inspection_scheme WHERE scheme_id=?", String.class, schemeId);
        if (!"RELEASED".equals(schemeStatus)) {
            QmsInspectionScheme publish = new QmsInspectionScheme();
            publish.setSchemeId(schemeId);
            publish.setOperationId(operationId);
            publish.setStatus("RELEASED");
            assertTrue(schemes.updateScheme(publish));
        }
    }

    private void stockRequirements(String tag, WmsWarehouse raw, WmsLocation rawLocation, Long workOrderId, Long actor) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.material_id, m.material_code, r.required_quantity
                FROM mfg_work_order_material r JOIN mdm_material m ON m.material_id=r.material_id
                WHERE r.work_order_id=? AND r.deleted_flag=0 AND r.required_quantity>0
                """, workOrderId);
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

    /** 把同产线的其他线边库位临时改成无产线，让本条库位成为唯一推荐。 */
    private List<Long> claimOnlyDedicatedLocation(Long lineId, Long keepLocationId) {
        List<Long> others = jdbc.queryForList("""
                SELECT l.location_id FROM wms_location l
                JOIN wms_warehouse w ON w.warehouse_id=l.warehouse_id
                WHERE l.deleted_flag=0 AND w.deleted_flag=0 AND w.warehouse_type='LINE_SIDE'
                  AND l.line_id=? AND l.location_id<>?
                """, Long.class, lineId, keepLocationId);
        if (!others.isEmpty()) {
            jdbc.update("""
                    UPDATE wms_location SET line_id=NULL
                    WHERE deleted_flag=0 AND line_id=? AND location_id<>?
                    """, lineId, keepLocationId);
        }
        return others;
    }

    private void restoreDedicatedLocations(List<Long> locationIds) {
        if (locationIds == null || locationIds.isEmpty()) {
            return;
        }
        Long lineId = jdbc.queryForObject(
                "SELECT line_id FROM factory_line WHERE line_code='LAMP-LINE-HEAD' AND deleted_flag=0", Long.class);
        for (Long locationId : locationIds) {
            jdbc.update("UPDATE wms_location SET line_id=? WHERE location_id=?", lineId, locationId);
        }
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

    private Long productId(String code) {
        return jdbc.queryForObject(
                "SELECT product_id FROM mdm_product WHERE product_code=? AND deleted_flag=0", Long.class, code);
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

    private BigDecimal lineSideQuantity(Long workOrderId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(available_quantity+locked_quantity),0) FROM wms_inventory i
                JOIN wms_warehouse w ON w.warehouse_id=i.warehouse_id
                WHERE i.work_order_id=? AND w.warehouse_type='LINE_SIDE' AND i.deleted_flag=0
                """, BigDecimal.class, workOrderId);
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
