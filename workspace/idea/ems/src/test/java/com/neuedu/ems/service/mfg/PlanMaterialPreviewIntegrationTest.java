package com.neuedu.ems.service.mfg;

import com.neuedu.ems.PhoneCryptoTestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_.*")
@Transactional
@AutoConfigureMockMvc
class PlanMaterialPreviewIntegrationTest extends ProductionOrderProgressTestFixture {
    private static final LocalDateTime BEFORE_PLAN=LocalDateTime.of(2026,10,25,7,0);

    private void clearPublicSemiStock() {
        jdbc.update("UPDATE wms_inventory SET available_quantity=0 WHERE work_order_id=0 AND material_id IN (SELECT material_id FROM mdm_material WHERE material_type IN ('SEMI','SEMI_FINISHED'))");
        session.clearCache();
    }

    private void addStock(Long materialId,BigDecimal quantity) {
        Long location=publicLocation();
        Long inventory=inventoryRow(rawWarehouseId,location,materialId,batch(materialId));
        jdbc.update("UPDATE wms_inventory SET available_quantity=? WHERE inventory_id=?",quantity,inventory);
        session.clearCache();
    }

    private void coverRawMaterials(Long order) {
        var preview=service.inspect(order,BEFORE_PLAN,admin());
        preview.preparationMaterials().stream().map(m->m.materialId()).distinct()
                .forEach(id->addStock(id,new BigDecimal("10000")));
        session.clearCache();
    }

    @Test void componentDemandBeforeWorkOrdersMustNotBeSkippedAsNotApplicable() {
        clearPublicSemiStock();
        Long order=insertOrder("RELEASED",BigDecimal.TEN);
        insertPlan(order,BigDecimal.TEN,"RELEASED","ROOT",null);
        coverRawMaterials(order);
        var before=counts();
        var progress=service.inspect(order,BEFORE_PLAN,admin());
        assertEquals("DONE",stage(progress,"MATERIAL_PREP").status());
        var component=stage(progress,"COMPONENT");
        assertEquals("NOT_STARTED",component.status());
        BigDecimal needed=progress.semiFinishedMaterials().stream().map(m->m.shortageQuantity())
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        assertTrue(needed.signum()>0);
        assertEquals(0,needed.compareTo(component.plannedQuantity()));
        assertEquals(0,needed.compareTo(component.remainingQuantity()));
        assertEquals("COMPONENT",progress.summary().currentStageCode());
        assertTrue(component.notes().stream().anyMatch(n->n.contains("尚未生成")));
        assertTrue(progress.workOrders().isEmpty());
        assertEquals(before,counts(),"进度预览不能替用户生成工单或预留库存");
    }

    @Test void sufficientExistingComponentsCanStillSkipComponentProduction() {
        clearPublicSemiStock();
        Long order=insertOrder("RELEASED",BigDecimal.TEN);
        insertPlan(order,BigDecimal.TEN,"RELEASED","ROOT",null);
        coverRawMaterials(order);
        var first=service.inspect(order,BEFORE_PLAN,admin());
        first.semiFinishedMaterials().stream().map(m->m.materialId()).distinct()
                .forEach(id->addStock(id,new BigDecimal("10000")));
        var progress=service.inspect(order,BEFORE_PLAN,admin());
        assertTrue(progress.semiFinishedMaterials().stream().allMatch(m->m.shortageQuantity().signum()==0));
        assertEquals("NA",stage(progress,"COMPONENT").status());
        assertEquals("ASSEMBLY",progress.summary().currentStageCode());
    }

    @Test void multiplePlansUseOnlyTheRemainingComponentShortage() {
        clearPublicSemiStock();
        Long order=insertOrder("RELEASED",new BigDecimal("12"));
        insertPlan(order,new BigDecimal("6"),"RELEASED","ROOT",null);
        insertPlan(order,new BigDecimal("6"),"RELEASED","ROOT",null);
        coverRawMaterials(order);
        var first=service.inspect(order,BEFORE_PLAN,admin());
        first.semiFinishedMaterials().stream().map(m->m.materialId()).distinct()
                .forEach(id->addStock(id,new BigDecimal("10")));
        var progress=service.inspect(order,BEFORE_PLAN,admin());
        BigDecimal shortage=progress.semiFinishedMaterials().stream().map(m->m.shortageQuantity())
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        assertTrue(shortage.signum()>0);
        assertEquals(0,shortage.compareTo(stage(progress,"COMPONENT").plannedQuantity()));
        assertEquals("COMPONENT",progress.summary().currentStageCode());
        assertTrue(progress.workOrders().isEmpty());
    }
    @Test void releasedPlanHasKitPreviewBeforeAnyWorkOrderAndInspectionDoesNotWrite() {
        Long order = insertOrder("RELEASED", BigDecimal.TEN);
        insertPlan(order, BigDecimal.TEN, "RELEASED", "ROOT", null);
        var before = counts();
        var progress = service.inspect(order, null, admin());
        assertEquals(before, counts());
        assertTrue(progress.workOrders().isEmpty());
        assertTrue(progress.materials().isEmpty());
        assertFalse(progress.preparationMaterials().isEmpty(), progress.dataQuality().missing().toString());
        assertFalse(progress.semiFinishedMaterials().isEmpty());
        assertTrue(progress.preparationMaterials().stream().allMatch(m->m.requiredQuantity().compareTo(m.baseRequiredQuantity())==0
                &&m.supplementRequiredQuantity().signum()==0));
        assertTrue(progress.preparationMaterials().stream()
                .allMatch(m -> !Set.of("SEMI", "SEMI_FINISHED").contains(m.materialType()) && m.workOrderId() == null));
        assertEquals(progress.preparationMaterials().size(), stage(progress, "MATERIAL_PREP").plannedQuantity().intValueExact());
    }

    @Test void missingPublishedBomIsExplainedAndNotReportedAsKitReady() {
        Long order = insertOrder("RELEASED", BigDecimal.TEN);
        insertPlan(order, BigDecimal.TEN, "RELEASED", "ROOT", null);
        jdbc.update("UPDATE mdm_bom SET status='DRAFT' WHERE product_id=?", product);
        var progress = service.inspect(order, null, admin());
        assertTrue(progress.preparationMaterials().isEmpty());
        assertTrue(progress.dataQuality().missing().stream().anyMatch(s -> s.contains("齐套预览未完成")));
        assertNotEquals("DONE", stage(progress, "MATERIAL_PREP").status());
    }

    @Test void initialDraftHasAnOriginalDemandButExecutionWithoutSnapshotDoesNotGuessIt() {
        Long order=insertOrder("RELEASED",BigDecimal.TEN);
        Long plan=insertPlan(order,BigDecimal.TEN,"RELEASED","ROOT",null);
        Long wo=insertWorkOrder("INITIAL",plan,product,BigDecimal.TEN,"DRAFT",null);
        insertRequirement(wo,inputMaterial,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.TEN,"WAIT_READY","STOCK",null,BigDecimal.ZERO);
        var initial=service.inspect(order,BEFORE_PLAN,admin()).materials().getFirst();
        assertEquals(0,BigDecimal.TEN.compareTo(initial.baseRequiredQuantity()));
        assertEquals(0,initial.supplementRequiredQuantity().signum());
        jdbc.update("UPDATE mfg_work_order SET status='RELEASED' WHERE work_order_id=?",wo);
        session.clearCache();
        var missing=service.inspect(order,BEFORE_PLAN,admin());
        assertNull(missing.materials().getFirst().baseRequiredQuantity());
        assertNull(missing.materials().getFirst().supplementRequiredQuantity());
        assertFalse(missing.dataQuality().missing().isEmpty());
    }
}
