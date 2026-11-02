package com.neuedu.ems.service.mfg;

import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.service.mdm.BomMaterialDemandService;
import com.neuedu.ems.service.mdm.MaterialQuantityPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanMaterialPreviewServiceTest {
    MdmBomMapper boms = mock(MdmBomMapper.class);
    MdmBomItemMapper items = mock(MdmBomItemMapper.class);
    MdmMaterialMapper materials = mock(MdmMaterialMapper.class);
    MdmUnitMapper units = mock(MdmUnitMapper.class);
    MdmProductMapper products = mock(MdmProductMapper.class);
    ComponentSupplyPlanningService supply = mock(ComponentSupplyPlanningService.class);
    PlanMaterialPreviewService service;
    List<MdmBomItem> root, child;
    Map<Long, BigDecimal> available = new HashMap<>();

    @BeforeEach void setup() {
        service = new PlanMaterialPreviewService(boms, items, materials, units, products,
                new BomMaterialDemandService(materials, new MaterialQuantityPolicy()), supply);
        root = List.of(item(2L, "1", 20L), item(3L, "2", null));
        child = List.of(item(3L, "4", null));
        when(boms.selectList(any())).thenReturn(List.of(bom(10L)));
        when(boms.selectById(anyLong())).thenAnswer(i -> bom(i.getArgument(0)));
        when(products.selectById(anyLong())).thenAnswer(i -> {
            var product = new MdmProduct(); product.setProductId(i.getArgument(0));
            product.setMaterialId(2L); product.setDeletedFlag(0); product.setStatus("ENABLE"); return product;
        });
        when(items.selectList(any())).thenReturn(root, child);
        when(materials.selectById(anyLong())).thenAnswer(i -> material(i.getArgument(0)));
        when(supply.childBomReferences(anyList())).thenAnswer(i -> {
            Map<Long, Long> result = new LinkedHashMap<>();
            for (MdmBomItem row : i.<List<MdmBomItem>>getArgument(0))
                if (row.getChildBomId() != null) result.put(row.getMaterialId(), row.getChildBomId());
            return result;
        });
        when(supply.qualifiedAvailable(anyCollection())).thenAnswer(i -> {
            Map<Long, BigDecimal> result = new HashMap<>();
            for (Long id : i.<Collection<Long>>getArgument(0)) result.put(id, available.getOrDefault(id, BigDecimal.ZERO));
            return result;
        });
        available.put(2L, new BigDecimal("6"));
        available.put(3L, new BigDecimal("1000"));
    }

    @Test void planChecksBeforeWorkOrdersAndUnfoldsOnlySemiFinishedShortage() {
        var result = preview(List.of(plan(1L, "10")), List.of());
        assertTrue(result.missing().isEmpty());
        assertEquals(1, result.semiFinishedMaterials().size());
        assertEquals(0, new BigDecimal("6").compareTo(result.semiFinishedMaterials().get(0).freeAllocatedQuantity()));
        assertEquals(0, new BigDecimal("36").compareTo(result.basicMaterials().stream()
                .map(m -> m.requiredQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertTrue(result.basicMaterials().stream().allMatch(m -> "RAW".equals(m.materialType()) && m.workOrderId() == null));
        verify(boms, never()).insert(any(MdmBom.class));
    }

    @Test void fullyStockedSemiFinishedDoesNotRequireItsRawMaterials() {
        available.put(2L, BigDecimal.TEN);
        var result = preview(List.of(plan(1L, "10")), List.of());
        assertEquals(1, result.basicMaterials().size());
        assertEquals(0, new BigDecimal("20").compareTo(result.basicMaterials().get(0).requiredQuantity()));
        verify(items, times(1)).selectList(any());
    }

    @Test void multiplePlansShareOneInventoryPool() {
        when(items.selectList(any())).thenReturn(root, child, root, child);
        available.put(3L, new BigDecimal("40"));
        var result = preview(List.of(plan(1L, "10"), plan(2L, "10")), List.of());
        assertEquals(0, new BigDecimal("6").compareTo(result.semiFinishedMaterials().stream()
                .map(m -> m.freeAllocatedQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(0, new BigDecimal("40").compareTo(result.basicMaterials().stream()
                .map(m -> m.freeAllocatedQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(0, new BigDecimal("56").compareTo(result.basicMaterials().stream()
                .map(m -> m.shortageQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Test void generatedRootPlanIsNotCountedAgain() {
        var result = preview(List.of(plan(1L, "10")), List.of(Map.of("workOrderType", "ROOT", "planId", 1L)));
        assertTrue(result.basicMaterials().isEmpty());
        verifyNoInteractions(boms);
    }

    @Test void missingOrCyclicBomDoesNotExposePartialKitSuccess() {
        when(boms.selectList(any())).thenReturn(List.of());
        var absent = preview(List.of(plan(1L, "10")), List.of());
        assertFalse(absent.missing().isEmpty());
        when(boms.selectList(any())).thenReturn(List.of(bom(10L)));
        when(items.selectList(any())).thenReturn(List.of(item(2L, "1", 10L)));
        var cyclic = preview(List.of(plan(1L, "10")), List.of());
        assertFalse(cyclic.missing().isEmpty());
        assertTrue(cyclic.basicMaterials().isEmpty());
        assertTrue(cyclic.semiFinishedMaterials().isEmpty());
    }

    @Test void fractionalTheoreticalUsageRoundsWithExecutionPolicy() {
        available.put(2L, BigDecimal.TEN);
        when(items.selectList(any())).thenReturn(List.of(item(3L, "1.01", null)));
        var result = preview(List.of(plan(1L, "10")), List.of());
        assertEquals(0, new BigDecimal("11").compareTo(result.basicMaterials().get(0).requiredQuantity()));
    }

    private PlanMaterialPreviewService.Preview preview(List<Map<String,Object>> plans, List<Map<String,Object>> workOrders) {
        return service.preview(1L, plans, workOrders, List.of(), LocalDateTime.now());
    }
    private Map<String,Object> plan(Long id, String quantity) {
        return Map.of("planId", id, "planNo", "PL-" + id, "planType", "ROOT", "status", "RELEASED", "plannedQuantity", new BigDecimal(quantity));
    }
    private MdmBom bom(Long id) { var b = new MdmBom(); b.setBomId(id); b.setProductId(id); b.setStatus("RELEASED"); b.setDeletedFlag(0); return b; }
    private MdmBomItem item(Long materialId, String quantity, Long childId) {
        var r = new MdmBomItem(); r.setMaterialId(materialId); r.setQuantity(new BigDecimal(quantity)); r.setChildBomId(childId); return r;
    }
    private MdmMaterial material(Long id) {
        var m = new MdmMaterial(); m.setMaterialId(id); m.setMaterialCode("M-" + id); m.setMaterialName("物料" + id);
        m.setMaterialType(id == 2L ? "SEMI_FINISHED" : "RAW"); m.setStatus("ENABLE"); m.setDeletedFlag(0);
        m.setQuantityMode("DISCRETE"); m.setIssueStep(BigDecimal.ONE); return m;
    }
}
