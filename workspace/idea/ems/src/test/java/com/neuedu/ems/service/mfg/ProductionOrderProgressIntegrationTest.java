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
class ProductionOrderProgressIntegrationTest extends ProductionOrderProgressTestFixture {

    // ------------------------------------------------------------------ 草稿订单

    @Test
    void draftOrderStillReturnsEightStagesWithoutPlans() {
        Long orderId = insertOrder("DRAFT", new BigDecimal("1"));

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        assertEquals(8, progress.stages().size());
        assertEquals(List.of("ORDER_RELEASE", "SCHEDULE", "MATERIAL_PREP", "COMPONENT", "ASSEMBLY",
                        "FINAL_INSPECTION", "INBOUND", "SHIPMENT"),
                progress.stages().stream().map(ProductionOrderProgressVO.Stage::code).toList());
        assertEquals("NOT_STARTED", progress.summary().overallStatus());
        assertEquals("ORDER_RELEASE", progress.summary().currentStageCode());
        assertEquals(0, progress.summary().blockerCount());
        assertEquals(0, progress.summary().riskCount());
        assertEquals(0, progress.workOrders().size());
        assertEquals(0, progress.materials().size());

        ProductionOrderProgressVO.Stage assembly = stage(progress, "ASSEMBLY");
        assertEquals("NOT_STARTED", assembly.status());
        assertTrue(assembly.notes().stream().anyMatch(note -> note.contains("尚未生成整机工单")));
        // 老化不适用时必须给出明确说明，而不是静默留空。
        assertTrue(stage(progress, "AGING").notes().stream()
                .anyMatch(note -> note.contains("尚未形成老化执行依据")));
    }

    @Test
    void missingCrossModulePermissionIsRejected() {
        Long orderId = insertOrder("DRAFT", BigDecimal.ONE);
        AuthorizationContext narrow = new AuthorizationContext(2L, null, null, "ALL",
                Set.of("OPERATOR"), Set.of("mfg:production-order:view"), Set.of());
        assertThrows(DataScopeAccessDeniedException.class, () -> service.inspect(orderId, null, narrow));
        assertThrows(DataScopeAccessDeniedException.class, () -> service.inspect(orderId, null, null));
    }

    // ------------------------------------------------------------------ 已完工订单的数量口径

    @Test
    void finishedOrderDoesNotSumComponentOutputsIntoAssembly() {
        Long orderId = insertOrder("FINISHED", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "FINISHED", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "FINISHED", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));

        Long componentPlan = insertPlan(orderId, new BigDecimal("10"), "FINISHED", "COMPONENT", rootPlan);
        for (int index = 0; index < 3; index++) {
            String suffix = "C" + index;
            Long workOrderId = insertWorkOrder(suffix, componentPlan, componentProduct, new BigDecimal("10"),
                    "FINISHED", rootWorkOrder);
            attachSnapshot(workOrderId, snapshotBacking(workOrderId));
            Long material = material(suffix);
            insertRequirement(workOrderId, material, new BigDecimal("10"), new BigDecimal("10"),
                    BigDecimal.ZERO, "READY", "STOCK", null, BigDecimal.ZERO);
            Long taskId = insertTask(suffix, workOrderId, 1L, new BigDecimal("10"), "FINISHED");
            Long reportId = insertReport(suffix, taskId, new BigDecimal("10"), "APPROVED", "2026-10-25 09:10:00");
            Long lotId = insertLot(suffix, componentProduct, workOrderId, reportId, new BigDecimal("10"));
            insertInspection(suffix, "FINAL", workOrderId, lotId, new BigDecimal("10"), "PASS", "FINISHED",
                    "2026-10-25 09:20:00");
        }

        // 整机三张组件需求写成 COMPONENT_WORK_ORDER 来源，与真实演练单一致。
        for (int index = 0; index < 3; index++) {
            Long requirementId = insertRequirement(rootWorkOrder, material("R" + index), new BigDecimal("10"),
                    new BigDecimal("10"), BigDecimal.ZERO, "READY", "COMPONENT_WORK_ORDER",
                    null, BigDecimal.ZERO);
            insertSupplySource(requirementId, rootWorkOrder, "COMPONENT_WORK_ORDER", new BigDecimal("10"));
        }
        Long stockRequirement = insertRequirement(rootWorkOrder, material("S"), new BigDecimal("20"),
                new BigDecimal("20"), BigDecimal.ZERO, "READY", "STOCK", null, BigDecimal.ZERO);

        Long rootTask = insertTask("ROOT", rootWorkOrder, 1L, new BigDecimal("10"), "FINISHED");
        Long rootReport = insertReport("ROOT", rootTask, new BigDecimal("10"), "APPROVED", "2026-10-25 10:00:00");
        Long rootLot = insertLot("ROOT", product, rootWorkOrder, rootReport, new BigDecimal("10"));
        insertInspection("ROOT", "FINAL", rootWorkOrder, rootLot, new BigDecimal("10"), "PASS", "FINISHED",
                "2026-10-25 10:10:00");
        Long inboundId = insertInbound("PRODUCT_LOT", rootLot, "APPROVED", "2026-10-25 10:20:00");
        insertInboundDetail(inboundId, outputMaterial, new BigDecimal("10"));
        Long outboundId = insertOutbound(rootWorkOrder, orderId, "APPROVED", "2026-10-25 10:30:00");
        insertOutboundDetail(outboundId, outputMaterial, new BigDecimal("10"));

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.Stage assembly = stage(progress, "ASSEMBLY");
        assertEquals(0, assembly.plannedQuantity().compareTo(new BigDecimal("10")));
        // 关键口径：10 台不能被三张组件工单汇总成 30 台或 40 台。
        assertEquals(0, assembly.executedQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0, assembly.releasedQuantity().compareTo(new BigDecimal("10")));
        assertEquals("DONE", assembly.status());

        ProductionOrderProgressVO.Stage component = stage(progress, "COMPONENT");
        assertEquals(3, component.children().size());
        // 组件阶段可以按套汇总（三张工单各 10 套），但整机阶段只看整机产出工序报工。
        assertEquals(0, component.plannedQuantity().compareTo(new BigDecimal("30")));
        assertEquals(0, component.executedQuantity().compareTo(new BigDecimal("30")));

        assertEquals(0, progress.summary().productionPercent().compareTo(new BigDecimal("100")));
        assertEquals(0, progress.summary().shipmentPercent().compareTo(new BigDecimal("100")));
        assertEquals(0, progress.summary().inboundQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0, progress.summary().shippedQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0, progress.summary().maxAssemblableQuantity().compareTo(BigDecimal.ZERO));
        assertEquals("DONE", progress.summary().overallStatus());
        assertEquals(0, progress.summary().blockerCount());

        assertTrue(progress.materials().stream().anyMatch(row ->
                row.requirementId().equals(stockRequirement) && "COVERED".equals(row.status())));
        assertTrue(progress.documents().stream().anyMatch(doc -> "PRODUCTION_INBOUND".equals(doc.type())));
        assertTrue(progress.documents().stream().anyMatch(doc -> "SALES_OUTBOUND".equals(doc.type())));
        assertTrue(progress.dependencies().stream().anyMatch(row -> row.label().startsWith("补产来源")));
    }

    // ------------------------------------------------------------------ 缺料阻塞

    @Test
    void shortageBlocksAssemblyAndImpactStaysWithinRemainingQuantity() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));

        // 需求 10、发料 4，公共可用为 0，因此缺口 6 台份。
        insertRequirement(rootWorkOrder, material("SHORT"), new BigDecimal("10"), new BigDecimal("4"),
                new BigDecimal("6"), "WAIT_READY", "STOCK", null, BigDecimal.ZERO);

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.Stage assembly = stage(progress, "ASSEMBLY");
        assertEquals("BLOCKED", assembly.status());
        ProductionOrderProgressVO.Blocker blocker = assembly.blockers().stream()
                .filter(row -> "MATERIAL_SHORTAGE".equals(row.code()))
                .findFirst().orElseThrow();
        assertEquals("RED", blocker.level());
        assertEquals("ASSEMBLY", blocker.stageCode());
        assertEquals(0, blocker.impact().blockedQuantity().compareTo(new BigDecimal("6")));
        // 影响量不得超过整机工单剩余未完成量。
        assertTrue(blocker.impact().blockedQuantity().compareTo(assembly.remainingQuantity()) <= 0);
        // 红灯必须挂得上可核对的单据证据。
        assertFalse(blocker.evidence().isEmpty());
        assertNotNull(blocker.evidence().get(0).document());
        // 光说缺料不够，还要给出下一步能做什么：动作必须带目标页面与定位参数，
        // 否则前端渲染出按钮也点不到具体单据。
        assertFalse(blocker.actions().isEmpty());
        ProductionOrderProgressVO.Action inventoryAction = blocker.actions().stream()
                .filter(row -> "OPEN_INVENTORY".equals(row.code()))
                .findFirst().orElseThrow();
        assertEquals("wms-inventory", inventoryAction.targetResource());
        assertNotNull(inventoryAction.query().get("materialId"));
        ProductionOrderProgressVO.Action workOrderAction = blocker.actions().stream()
                .filter(row -> "OPEN_WORK_ORDER".equals(row.code()))
                .findFirst().orElseThrow();
        assertEquals("mfg-work-order", workOrderAction.targetResource());
        assertEquals(rootWorkOrder, ((Number) workOrderAction.query().get("focus")).longValue());

        assertEquals(1, progress.summary().blockerCount());
        assertEquals("BLOCKED", progress.summary().overallStatus());
        assertEquals(0, progress.summary().productionPercent().compareTo(BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ 老化批数

    @Test
    void agingUsesCeilingBatchesFromFrozenCapacity() {
        ProductionOrderProgressVO fiftyOne = inspectAgingOrder(new BigDecimal("51"));
        ProductionOrderProgressVO.Stage aging = stage(fiftyOne, "AGING");
        assertEquals(0, aging.plannedQuantity().compareTo(new BigDecimal("51")));
        assertEquals(0, aging.executedQuantity().compareTo(new BigDecimal("51")));
        assertEquals("REPORTED_UNVERIFIED", aging.actualTimeType());
        // 51 台按 50 台/批是 2 批，2 × 60 分钟 = 120 分钟，不能线性折算成 61.2 分钟。
        assertTrue(aging.notes().stream().anyMatch(note -> note.contains("2 批 × 60 分钟")),
                () -> "老化说明缺少批数依据：" + aging.notes());
        assertTrue(aging.notes().stream().anyMatch(note -> note.contains("人工报工，未验证连续运行")));
        assertTrue(aging.notes().stream().anyMatch(note -> note.contains("可配置值")));

        ProductionOrderProgressVO fifty = inspectAgingOrder(new BigDecimal("50"));
        ProductionOrderProgressVO.Stage oneBatch = stage(fifty, "AGING");
        assertTrue(oneBatch.notes().stream().anyMatch(note -> note.contains("1 批 × 60 分钟")),
                () -> "50 台应恰好一批：" + oneBatch.notes());
    }

    private ProductionOrderProgressVO inspectAgingOrder(BigDecimal quantity) {
        Long orderId = insertOrder("FINISHED", quantity);
        Long rootPlan = insertPlan(orderId, quantity, "FINISHED", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, quantity, "FINISHED", null);
        attachSnapshot(rootWorkOrder, agingSnapshot(rootWorkOrder));
        Long taskId = insertTask("AGING", rootWorkOrder, 2L, quantity, "FINISHED");
        insertReport("AGING", taskId, quantity, "APPROVED", "2026-10-25 11:00:00");
        // 实际只用 0 分钟：系统没有设备接口，不能把报工工时当作真实通电时长。
        insertLog("MFG_TASK", taskId, "WAIT_START", "RUNNING", "2026-10-25 11:00:00");
        insertLog("MFG_TASK", taskId, "RUNNING", "FINISHED", "2026-10-25 11:00:00");
        session.clearCache();
        return service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());
    }

    // ------------------------------------------------------------------ 只读无副作用

    @Test
    void httpGetDoesNotWriteBusinessData() throws Exception {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        insertRequirement(rootWorkOrder, material("IO"), new BigDecimal("10"), BigDecimal.ZERO,
                new BigDecimal("10"), "WAIT_READY", "STOCK", null, BigDecimal.ZERO);
        session.clearCache();

        Map<String, Long> before = counts();
        Long actor = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
        String token = jwt.generateToken(actor, "admin");
        mvc.perform(get("/api/mfg/production-order/" + orderId + "/progress")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stages.length()").value(8))
                .andExpect(jsonPath("$.data.stages[4].code").value("ASSEMBLY"))
                .andExpect(jsonPath("$.data.stages[4].status").value("BLOCKED"));
        mvc.perform(get("/api/mfg/production-order/" + orderId + "/progress")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(before, counts());
    }

    // ------------------------------------------------------------------ 齐套口径：部分覆盖与公共库存不重复承诺

    @Test
    void partialCoverageSeparatesIssuedFromPublicStock() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));

        Long shortMaterial = material("PART");
        // 需求 10、已发料 4、公共合格可用 3 → 仍缺 3，属于部分覆盖而不是完全缺料。
        insertRequirement(rootWorkOrder, shortMaterial, new BigDecimal("10"), new BigDecimal("4"),
                new BigDecimal("3"), "PARTIAL_READY", "STOCK", null, BigDecimal.ZERO);
        insertPublicStock(shortMaterial, new BigDecimal("3"));

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.MaterialCoverage row = progress.materials().stream()
                .filter(item -> item.materialId().equals(shortMaterial)).findFirst().orElseThrow();
        assertEquals("PARTIAL", row.status());
        assertEquals(0, row.issuedQuantity().compareTo(new BigDecimal("4")));
        assertEquals(0, row.freeAllocatedQuantity().compareTo(new BigDecimal("3")));
        assertEquals(0, row.shortageQuantity().compareTo(new BigDecimal("3")));
        // 公共池被本次需求用掉后，剩余量必须归零，不能对同一物料重复承诺。
        assertEquals(0, row.publicAvailableQuantity().compareTo(BigDecimal.ZERO));

        ProductionOrderProgressVO.Blocker blocker = stage(progress, "ASSEMBLY").blockers().stream()
                .filter(item -> "MATERIAL_SHORTAGE".equals(item.code())).findFirst().orElseThrow();
        assertEquals(0, blocker.impact().blockedQuantity().compareTo(new BigDecimal("3")));
    }

    @Test
    void onlyReservedAllocationsCountTowardCoverage() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));

        Long target = material("ALLOC");
        BigDecimal reportedReserved = new BigDecimal("9");
        Long requirementId = insertRequirement(rootWorkOrder, target, new BigDecimal("10"), BigDecimal.ZERO,
                BigDecimal.ONE, "WAIT_READY", "STOCK", null, reportedReserved);
        // 汇总列写的 reserved_quantity 不能直接采信：只有 status=RESERVED 的行才算本单占用。
        insertAllocation(requirementId, rootWorkOrder, target, "RESERVED", new BigDecimal("6"),
                BigDecimal.ZERO);
        jdbc.update("UPDATE wms_inventory i JOIN mfg_supply_allocation a ON a.inventory_id=i.inventory_id SET i.locked_quantity=a.reserved_quantity WHERE a.requirement_id=? AND a.status='RESERVED'",requirementId);
        insertAllocation(requirementId, rootWorkOrder, target, "TRANSFERRED", BigDecimal.ZERO,
                new BigDecimal("10"));

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.MaterialCoverage row = progress.materials().stream()
                .filter(item -> item.materialId().equals(target)).findFirst().orElseThrow();
        // 6 来自 RESERVED 分配行；TRANSFERRED 已经转成线边占用，不能再叠加一次。
        assertEquals(0, row.reservedQuantity().compareTo(new BigDecimal("6")));
        assertEquals(0, row.shortageQuantity().compareTo(new BigDecimal("4")));
        assertEquals("PARTIAL", row.status());
    }

    @Test
    void publicStockIsPromisedOnceAcrossRequirementsOfSameMaterial() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("20"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("20"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("20"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long componentPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "COMPONENT", rootPlan);
        Long componentWorkOrder = insertWorkOrder("C0", componentPlan, componentProduct, new BigDecimal("10"),
                "RUNNING", rootWorkOrder);
        attachSnapshot(componentWorkOrder, snapshotBacking(componentWorkOrder));

        Long shared = material("SHARED");
        Long firstRequirement = insertRequirement(componentWorkOrder, shared, new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("10"), "WAIT_READY", "STOCK", null, BigDecimal.ZERO);
        Long secondRequirement = insertRequirement(rootWorkOrder, shared, new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("10"), "WAIT_READY", "STOCK", null, BigDecimal.ZERO);
        insertPublicStock(shared, new BigDecimal("5"));

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.MaterialCoverage first = coverageOf(progress, firstRequirement);
        ProductionOrderProgressVO.MaterialCoverage second = coverageOf(progress, secondRequirement);
        // 公共库存只有 5，先满足较小的 requirement_id，后一条拿不到，不能各自都算 5。
        assertEquals(0, first.freeAllocatedQuantity().compareTo(new BigDecimal("5")));
        assertEquals(0, second.freeAllocatedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, second.shortageQuantity().compareTo(new BigDecimal("10")));
        assertEquals("SHORT", second.status());
    }

    @Test
    void lineSideStockOfOtherWorkOrderIsNotPubliclyAvailable() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long otherWorkOrder = insertWorkOrder("OTHER", rootPlan, componentProduct, new BigDecimal("10"),
                "RUNNING", rootWorkOrder);

        Long target = material("LINE");
        insertRequirement(rootWorkOrder, target, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"),
                "WAIT_READY", "STOCK", null, BigDecimal.ZERO);
        // 余额仍挂在别的工单名下（线边占用），对本次订单不是公共可用量。
        insertStock(target, otherWorkOrder, new BigDecimal("8"), BigDecimal.ZERO);

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.MaterialCoverage row = progress.materials().stream()
                .filter(item -> item.materialId().equals(target)).findFirst().orElseThrow();
        assertEquals(0, row.freeAllocatedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, row.publicAvailableQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, row.shortageQuantity().compareTo(new BigDecimal("10")));
    }

    @Test
    void incomingInspectionStockIsExplainedButNotUsable() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));

        Long target = material("INSPECT");
        insertRequirement(rootWorkOrder, target, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"),
                "WAIT_READY", "STOCK", null, BigDecimal.ZERO);
        // 有货但还在检验中：只解释"有货不能马上用"，不参与齐套、不抵缺口。
        insertStock(target, 0L, BigDecimal.ZERO, new BigDecimal("10"));

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.MaterialCoverage row = progress.materials().stream()
                .filter(item -> item.materialId().equals(target)).findFirst().orElseThrow();
        assertEquals(0, row.waitingInspectionQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0, row.freeAllocatedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, row.shortageQuantity().compareTo(new BigDecimal("10")));
        assertEquals("SHORT", row.status());
    }

    @Test
    void multipleSupplySourcesAreListedAsSeparateProducers() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long componentPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "COMPONENT", rootPlan);
        Long firstProducer = insertWorkOrder("P1", componentPlan, componentProduct, new BigDecimal("6"),
                "RUNNING", rootWorkOrder);
        Long secondProducer = insertWorkOrder("P2", componentPlan, componentProduct, new BigDecimal("4"),
                "RUNNING", rootWorkOrder);
        attachSnapshot(firstProducer, snapshotBacking(firstProducer));
        attachSnapshot(secondProducer, snapshotBacking(secondProducer));

        Long target = material("MULTI");
        Long requirementId = insertRequirement(rootWorkOrder, target, new BigDecimal("10"), BigDecimal.ZERO,
                new BigDecimal("10"), "WAIT_READY", "COMPONENT_WORK_ORDER", null, BigDecimal.ZERO);
        insertSupplySource(requirementId, firstProducer, "COMPONENT_WORK_ORDER", new BigDecimal("6"));
        insertSupplySource(requirementId, secondProducer, "COMPONENT_WORK_ORDER", new BigDecimal("4"));

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.MaterialCoverage row = coverageOf(progress, requirementId);
        // 两张补产单都要能被看见，不能合并成一个来源或只留最后一条。
        assertEquals(0, row.plannedSupplyQuantity().compareTo(new BigDecimal("10")));
        assertEquals(2, row.sourceWorkOrderIds().size());
        assertTrue(row.sourceWorkOrderIds().containsAll(List.of(firstProducer, secondProducer)));
        assertTrue(progress.dependencies().stream()
                .anyMatch(item -> item.label().startsWith("补产来源")));
    }

    // ------------------------------------------------------------------ 报工与检验口径

    @Test
    void submittedReportIsWaitingAuditAndRejectedAddsNoOutput() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));

        Long taskId = insertTask("AUDIT", rootWorkOrder, 1L, new BigDecimal("10"), "RUNNING");
        insertReport("SUB", taskId, new BigDecimal("4"), "SUBMITTED", "2026-10-25 09:00:00");
        insertReport("REJ", taskId, new BigDecimal("3"), "REJECTED", "2026-10-25 09:05:00");
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());
        ProductionOrderProgressVO.Stage assembly = stage(progress, "ASSEMBLY");

        // 未审核只是待审核，不能算产出；驳回更不是产出，也不该被当成不合格数。
        assertEquals(0, assembly.executedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, assembly.waitingAuditQuantity().compareTo(new BigDecimal("4")));
        assertEquals(0, assembly.defectQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, progress.summary().productionPercent().compareTo(BigDecimal.ZERO));
    }

    @Test
    void awaitingFinalInspectionIsWaitingInspectionNotReleased() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));

        Long taskId = insertTask("REL", rootWorkOrder, 1L, new BigDecimal("10"), "FINISHED");
        Long reportId = insertReport("REL", taskId, new BigDecimal("10"), "APPROVED", "2026-10-25 10:00:00");
        insertLot("REL", product, rootWorkOrder, reportId, new BigDecimal("10"));
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());
        ProductionOrderProgressVO.Stage assembly = stage(progress, "ASSEMBLY");

        // 报工审核通过不等于质量放行：批次还在等终检，放行量必须为 0。
        assertEquals(0, assembly.executedQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0, assembly.releasedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, assembly.waitingInspectionQuantity().compareTo(new BigDecimal("10")));
        assertEquals(0, progress.summary().releasedQuantity().compareTo(BigDecimal.ZERO));
    }

    @Test
    void partialInboundAndUnapprovedShipmentStayInWaitingQuantities() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));

        Long taskId = insertTask("SHIP", rootWorkOrder, 1L, new BigDecimal("10"), "FINISHED");
        Long reportId = insertReport("SHIP", taskId, new BigDecimal("10"), "APPROVED", "2026-10-25 10:00:00");
        Long lotId = insertLot("SHIP", product, rootWorkOrder, reportId, new BigDecimal("10"));
        insertInspection("SHIP", "FINAL", rootWorkOrder, lotId, new BigDecimal("10"), "PASS", "FINISHED",
                "2026-10-25 10:10:00");
        // 只入库 4 台，发货里 4 台已确认、2 台还在待审核。
        Long inboundId = insertInbound("PRODUCT_LOT", lotId, "APPROVED", "2026-10-25 10:20:00");
        insertInboundDetail(inboundId, outputMaterial, new BigDecimal("4"));
        Long approvedOutbound = insertOutbound(rootWorkOrder, orderId, "APPROVED", "2026-10-25 10:30:00");
        insertOutboundDetail(approvedOutbound, outputMaterial, new BigDecimal("4"));
        Long pendingOutbound = insertOutbound(rootWorkOrder, orderId, "SUBMITTED", "2026-10-25 10:40:00");
        insertOutboundDetail(pendingOutbound, outputMaterial, new BigDecimal("2"));
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        assertEquals(0, progress.summary().inboundQuantity().compareTo(new BigDecimal("4")));
        assertEquals(0, progress.summary().shippedQuantity().compareTo(new BigDecimal("4")));
        // 待审核发货只进待审核量，不能计入已发货。
        assertEquals(0, stage(progress, "SHIPMENT").waitingAuditQuantity().compareTo(new BigDecimal("2")));
        assertEquals("RUNNING", stage(progress, "INBOUND").status());
        assertEquals(0, progress.summary().shipmentPercent().compareTo(new BigDecimal("40")));
    }

    // ------------------------------------------------------------------ 设备故障与数据质量

    @Test
    void equipmentFaultOnFinishedStationIsNotReportedAsStoppage() {
        Long orderId = insertOrder("FINISHED", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "FINISHED", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "FINISHED", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long stationId = station("DONE");
        insertTaskAtStation("DONE", rootWorkOrder, stationId, new BigDecimal("10"), "FINISHED");
        Long equipmentId = equipment("EQ-DONE", stationId, "ENABLE");
        insertRepair(equipmentId, "OPEN", "2026-10-25 11:00:00");
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        // 该工位任务已全部结束，不能拿事后报修倒判成停工。
        assertTrue(progress.stages().stream().flatMap(item -> item.blockers().stream())
                .noneMatch(item -> "EQUIPMENT_FAULT".equals(item.code())));
        assertTrue(progress.dataQuality().notes().stream()
                .anyMatch(note -> note.contains("不倒判为停工")));
        assertEquals(0, progress.summary().blockerCount());
    }

    @Test
    void equipmentFaultWithAlternativeStationIsYellowRisk() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long brokenStation = station("BROKEN");
        Long spareStation = station("SPARE");
        insertTaskAtStation("BROKEN", rootWorkOrder, brokenStation, new BigDecimal("10"), "WAIT_START");
        insertTaskAtStation("SPARE", rootWorkOrder, spareStation, new BigDecimal("10"), "WAIT_START");
        Long brokenEquipment = equipment("EQ-BROKEN", brokenStation, "FAULT");
        insertRepair(brokenEquipment, "OPEN", "2026-10-25 11:00:00");
        Long spareEquipment=equipment("EQ-SPARE", spareStation, "ENABLE");
        jdbc.update("UPDATE eam_equipment SET category_id=1 WHERE equipment_id IN (?,?)",brokenEquipment,spareEquipment);
        jdbc.update("INSERT INTO factory_station_operation(station_id,operation_id,status,deleted_flag) VALUES (?,1,'ENABLE',0)",spareStation);
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.Blocker blocker = allBlockers(progress).stream()
                .filter(item -> "EQUIPMENT_FAULT".equals(item.code())).findFirst().orElseThrow();
        // 有可替代工位时只降级为风险，不判完全停工。
        assertEquals("YELLOW", blocker.level());
        assertEquals("EQUIPMENT", blocker.type());
        assertTrue(blocker.impact().explanation().contains("可切换工位"));
        assertFalse(blocker.evidence().isEmpty());
        assertEquals(1, progress.summary().riskCount());
        assertEquals(0, progress.summary().blockerCount());
        assertEquals("RISK", progress.summary().overallStatus());
    }

    @Test
    void equipmentFaultWithoutAlternativeIsRedBlocker() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long brokenStation = station("ONLY");
        insertTaskAtStation("ONLY", rootWorkOrder, brokenStation, new BigDecimal("10"), "WAIT_START");
        Long equipmentId = equipment("EQ-ONLY", brokenStation, "REPAIR");
        insertRepair(equipmentId, "OPEN", "2026-10-25 11:00:00");
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.Blocker blocker = allBlockers(progress).stream()
                .filter(item -> "EQUIPMENT_FAULT".equals(item.code())).findFirst().orElseThrow();
        assertEquals("RED", blocker.level());
        assertTrue(blocker.reason().contains("未发现可替代设备"), () -> "红灯原因未说明替代资源：" + blocker.reason());
        assertTrue(blocker.impact().explanation().contains("被阻塞"));
        assertEquals(1, progress.summary().blockerCount());
        assertEquals("BLOCKED", progress.summary().overallStatus());
    }

    @Test
    void componentStationFaultIsReportedOnComponentStage() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long componentPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "COMPONENT", rootPlan);
        Long componentWorkOrder = insertWorkOrder("C1", componentPlan, componentProduct, new BigDecimal("10"),
                "RUNNING", rootWorkOrder);
        attachSnapshot(componentWorkOrder, snapshotBacking(componentWorkOrder));

        Long brokenStation = station("CMP");
        insertTaskAtStation("CMP", componentWorkOrder, brokenStation, new BigDecimal("10"), "WAIT_START");
        Long equipmentId = equipment("EQ-CMP", brokenStation, "FAULT");
        insertRepair(equipmentId, "OPEN", "2026-10-25 11:00:00");
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        // 组件工位报修要标在组件阶段，不能错标成整机装配卡住。
        ProductionOrderProgressVO.Stage component = stage(progress, "COMPONENT");
        assertTrue(component.children().stream().flatMap(item -> item.blockers().stream())
                        .anyMatch(item -> "EQUIPMENT_FAULT".equals(item.code())),
                () -> "组件阶段未携带设备故障：" + component.children().stream()
                        .map(ProductionOrderProgressVO.Stage::blockers).toList());
        assertTrue(stage(progress, "ASSEMBLY").blockers().stream()
                .noneMatch(item -> "EQUIPMENT_FAULT".equals(item.code())));
        assertEquals("BLOCKED", component.status());
    }

    // ------------------------------------------------------------------ 组件延迟与库存覆盖

    @Test
    void componentDelayCoveredByStockIsYellowWithoutWaiting() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long componentPlan = insertPlan(orderId, new BigDecimal("10"), "WAIT_START", "COMPONENT", rootPlan);
        Long componentWorkOrder = insertWorkOrder("C1", componentPlan, componentProduct, new BigDecimal("10"),
                "WAIT_START", rootWorkOrder);
        attachSnapshot(componentWorkOrder, snapshotBacking(componentWorkOrder));

        // 整机对这张组件工单的需求已经全部由已发料覆盖，攒够 10 套，装配其实不必等它完工。
        Long componentMaterial = material("CMP");
        insertRequirement(rootWorkOrder, componentMaterial, new BigDecimal("10"), new BigDecimal("10"),
                BigDecimal.ZERO, "READY", "COMPONENT_WORK_ORDER", componentWorkOrder, BigDecimal.ZERO);
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.Blocker delay = allBlockers(progress).stream()
                .filter(item -> "COMPONENT_DELAY".equals(item.code()))
                .findFirst().orElseThrow(() -> new AssertionError("组件计划晚于整机计划开始却没有任何延迟提示"));
        // 库存能覆盖时只能是黄色进度提示：不能说成缺料，也不能把整批剩余量都算成受影响。
        assertEquals("YELLOW", delay.level());
        assertTrue(delay.title().contains("库存可覆盖"), () -> "未说明库存已覆盖：" + delay.title());
        assertEquals(0, delay.impact().blockedQuantity().compareTo(BigDecimal.ZERO));
        assertTrue(delay.impact().explanation().contains("不必实际等待"), () -> delay.impact().explanation());
        assertTrue(allBlockers(progress).stream().noneMatch(item -> "MATERIAL_SHORTAGE".equals(item.code())));
        assertEquals("RISK", progress.summary().overallStatus());
        assertEquals(0, progress.summary().blockerCount());
        assertEquals(1, progress.summary().riskCount());
    }

    @Test
    void componentDelayWithoutStockStillCountsRemainingQuantity() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        Long componentPlan = insertPlan(orderId, new BigDecimal("10"), "WAIT_START", "COMPONENT", rootPlan);
        Long componentWorkOrder = insertWorkOrder("C1", componentPlan, componentProduct, new BigDecimal("10"),
                "WAIT_START", rootWorkOrder);
        attachSnapshot(componentWorkOrder, snapshotBacking(componentWorkOrder));

        // 同一种组件需求这次一点也没发料，装配确实要等，影响量应等于工单剩余量。
        Long componentMaterial = material("CMP");
        insertRequirement(rootWorkOrder, componentMaterial, new BigDecimal("10"), BigDecimal.ZERO,
                new BigDecimal("10"), "WAIT_READY", "COMPONENT_WORK_ORDER", componentWorkOrder, BigDecimal.ZERO);
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.Blocker delay = allBlockers(progress).stream()
                .filter(item -> "COMPONENT_DELAY".equals(item.code()))
                .findFirst().orElseThrow();
        assertTrue(delay.title().contains("库存可覆盖") == false, () -> "无库存覆盖却写成可覆盖：" + delay.title());
        assertEquals(0, delay.impact().blockedQuantity().compareTo(new BigDecimal("10")));
        assertTrue(delay.impact().explanation().contains("无库存覆盖"), () -> delay.impact().explanation());
    }

    // ------------------------------------------------------------------ 时间依据与预测条件

    @Test
    void missingActualTimeIsReportedAsMissingNotGuessed() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        ProductionOrderProgressVO.PlanActual line = progress.planActual().stream()
                .filter(item -> item.key().equals("WO:" + rootWorkOrder)).findFirst().orElseThrow();
        // 既无状态日志也无报工：必须明说没有时间证据，不能用工单的更新时间凑数。
        assertEquals("MISSING", line.actualTimeType());
        assertNull(line.actualStart());
        assertNull(line.actualEnd());
    }

    @Test
    void predictionWithoutRootPlanHasNoCommitmentBasis() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        assertNull(progress.summary().predictedFinishTime());
        assertNull(progress.summary().plannedFinishTime());
        assertTrue(progress.summary().predictionBasis().contains("缺少可承诺的完成依据"));
    }

    @Test
    void predictionUnderBlockerIsNullWithReason() {
        Long orderId = insertOrder("RUNNING", new BigDecimal("10"));
        Long rootPlan = insertPlan(orderId, new BigDecimal("10"), "RUNNING", "ROOT", null);
        Long rootWorkOrder = insertWorkOrder("ROOT", rootPlan, product, new BigDecimal("10"), "RUNNING", null);
        attachSnapshot(rootWorkOrder, snapshotBacking(rootWorkOrder));
        insertRequirement(rootWorkOrder, material("BLK"), new BigDecimal("10"), BigDecimal.ZERO,
                new BigDecimal("10"), "WAIT_READY", "STOCK", null, BigDecimal.ZERO);
        session.clearCache();

        ProductionOrderProgressVO progress = service.inspect(orderId, LocalDateTime.of(2026,10,25,8,0), admin());

        // 有红灯时不能给出完成时间，只能说明被什么阻塞。
        assertNull(progress.summary().predictedFinishTime());
        assertTrue(progress.summary().predictionBasis().contains("MATERIAL_SHORTAGE"),
                () -> "预测依据未点名阻塞项：" + progress.summary().predictionBasis());
        assertNotNull(progress.summary().plannedFinishTime());
    }

}
