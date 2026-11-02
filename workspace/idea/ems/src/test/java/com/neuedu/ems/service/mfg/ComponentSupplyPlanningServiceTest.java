package com.neuedu.ems.service.mfg;

import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.entity.mfg.MfgLineProductCapability;
import com.neuedu.ems.entity.process.ProcessRoute;
import com.neuedu.ems.entity.wms.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.factory.FactoryLineMapper;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.mapper.mfg.MfgLineProductCapabilityMapper;
import com.neuedu.ems.mapper.process.ProcessRouteMapper;
import com.neuedu.ems.mapper.wms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ComponentSupplyPlanningServiceTest {
    WmsInventoryMapper inventories=mock(WmsInventoryMapper.class);
    WmsWarehouseMapper warehouses=mock(WmsWarehouseMapper.class);
    WmsLocationMapper locations=mock(WmsLocationMapper.class);
    WmsMaterialBatchMapper batches=mock(WmsMaterialBatchMapper.class);
    MdmBomMapper boms=mock(MdmBomMapper.class);
    MdmBomItemMapper items=mock(MdmBomItemMapper.class);
    MdmProductMapper products=mock(MdmProductMapper.class);
    MfgLineProductCapabilityMapper capabilities=mock(MfgLineProductCapabilityMapper.class);
    FactoryLineMapper lines=mock(FactoryLineMapper.class);
    ProcessRouteMapper routes=mock(ProcessRouteMapper.class);
    ComponentSupplyPlanningService service;
    WmsInventory inventory; WmsWarehouse warehouse; WmsLocation location; WmsMaterialBatch batch;
    MdmBom bom; MdmProduct product; FactoryLine line; ProcessRoute route; MfgLineProductCapability capability;
    LocalDate date=LocalDate.of(2026,10,24);

    @BeforeEach void fixture() {
        service=new ComponentSupplyPlanningService(inventories,warehouses,locations,batches,boms,items,products,capabilities,lines,routes);
        inventory=new WmsInventory();inventory.setInventoryId(1L);inventory.setWarehouseId(2L);inventory.setLocationId(3L);
        inventory.setMaterialId(4L);inventory.setBatchId(5L);inventory.setWorkOrderId(0L);inventory.setDeletedFlag(0);
        inventory.setAvailableQuantity(BigDecimal.TEN);inventory.setLockedQuantity(new BigDecimal("90"));
        inventory.setInspectionQuantity(new BigDecimal("100"));
        warehouse=new WmsWarehouse();warehouse.setWarehouseId(2L);warehouse.setWarehouseType("SEMI");warehouse.setStatus("ENABLE");warehouse.setDeletedFlag(0);
        location=new WmsLocation();location.setLocationId(3L);location.setWarehouseId(2L);location.setLocationType("NORMAL");location.setStatus("IDLE");location.setDeletedFlag(0);
        batch=new WmsMaterialBatch();batch.setBatchId(5L);batch.setMaterialId(4L);batch.setQualityStatus("PASS");batch.setStatus("AVAILABLE");batch.setDeletedFlag(0);
        when(inventories.selectList(any())).thenReturn(List.of(inventory));
        when(warehouses.selectById(2L)).thenReturn(warehouse);when(locations.selectById(3L)).thenReturn(location);when(batches.selectById(5L)).thenReturn(batch);
        bom=new MdmBom();bom.setBomId(20L);bom.setProductId(30L);bom.setStatus("RELEASED");bom.setDeletedFlag(0);bom.setEffectiveDate(date.minusDays(1));
        product=new MdmProduct();product.setProductId(30L);product.setMaterialId(4L);product.setStatus("ENABLE");product.setDeletedFlag(0);
        line=new FactoryLine();line.setLineId(40L);line.setStatus("ENABLE");line.setDeletedFlag(0);line.setDailyCapacity(new BigDecimal("200"));
        route=new ProcessRoute();route.setRouteId(50L);route.setProductId(30L);route.setStatus("RELEASED");route.setDeletedFlag(0);
        capability=new MfgLineProductCapability();capability.setCapabilityId(60L);capability.setProductId(30L);capability.setLineId(40L);
        capability.setRouteId(50L);capability.setStatus("ENABLE");capability.setDeletedFlag(0);capability.setPriorityLevel(1);capability.setCapacityFactor(BigDecimal.ONE);
        when(items.selectList(any())).thenReturn(List.of(item(20L)));
        when(boms.selectById(20L)).thenReturn(bom);when(products.selectById(30L)).thenReturn(product);
        when(capabilities.selectList(any())).thenReturn(List.of(capability));when(lines.selectById(40L)).thenReturn(line);when(routes.selectById(50L)).thenReturn(route);
    }
    @Test void onlyAvailableQuantityCountsAndConcessionMatchesTransferPolicy() {
        assertEquals(BigDecimal.TEN,service.qualifiedAvailable(List.of(4L)).get(4L));
        batch.setQualityStatus("CONCESSION");
        assertEquals(BigDecimal.TEN,service.qualifiedAvailable(List.of(4L)).get(4L));
    }
    @ParameterizedTest @ValueSource(strings={"pending","failed","frozen","deletedBatch","wrongMaterial","lineSide","disabledWarehouse","wrongWarehouse","quarantine","disabledLocation","deletedLocation","owned","negative","deletedInventory","nullQuality"})
    void unusableStockDoesNotReduceComponentShortage(String kind) {
        switch(kind) {
            case "pending" -> batch.setQualityStatus("WAIT_INSPECTION");
            case "failed" -> batch.setQualityStatus("FAIL");
            case "frozen" -> batch.setStatus("FROZEN");
            case "deletedBatch" -> batch.setDeletedFlag(1);
            case "wrongMaterial" -> batch.setMaterialId(999L);
            case "lineSide" -> warehouse.setWarehouseType("LINE_SIDE");
            case "disabledWarehouse" -> warehouse.setStatus("DISABLE");
            case "wrongWarehouse" -> location.setWarehouseId(999L);
            case "quarantine" -> location.setLocationType("QUARANTINE");
            case "disabledLocation" -> location.setStatus("DISABLE");
            case "deletedLocation" -> location.setDeletedFlag(1);
            case "owned" -> inventory.setWorkOrderId(999L);
            case "negative" -> inventory.setAvailableQuantity(BigDecimal.ONE.negate());
            case "deletedInventory" -> inventory.setDeletedFlag(1);
            case "nullQuality" -> batch.setQualityStatus(null);
        }
        assertTrue(service.qualifiedAvailable(List.of(4L)).isEmpty());
    }
    @Test void distinctQualifiedBatchesAreSummedWithoutCountingLockedOrInspection() {
        WmsInventory second=new WmsInventory();org.springframework.beans.BeanUtils.copyProperties(inventory,second);
        second.setInventoryId(8L);second.setBatchId(6L);second.setAvailableQuantity(new BigDecimal("7"));
        WmsMaterialBatch other=new WmsMaterialBatch();org.springframework.beans.BeanUtils.copyProperties(batch,other);other.setBatchId(6L);
        when(batches.selectById(6L)).thenReturn(other);when(inventories.selectList(any())).thenReturn(List.of(inventory,second));
        assertEquals(new BigDecimal("17"),service.qualifiedAvailable(List.of(4L)).get(4L));
        verify(warehouses,times(1)).selectById(2L);
    }
    @Test void exactParentChildVersionAndOwnResourcesAreSelected() {
        var result=service.resolve(10L,4L,date);
        assertEquals(20L,result.bom().getBomId());assertEquals(40L,result.line().getLineId());assertEquals(50L,result.route().getRouteId());
        verify(boms,never()).selectList(any());
    }
    @ParameterizedTest @ValueSource(strings={"draftBom","futureBom","expiredBom","deletedBom","disabledProduct","wrongProductMaterial","missingCapability","zeroFactor","invalidFactor","zeroCapacity","disabledLine","deletedLine","wrongRouteProduct","draftRoute","deletedRoute"})
    void incompleteComponentResourcesFailWithoutParentFallback(String kind) {
        switch(kind) {
            case "draftBom" -> bom.setStatus("DRAFT");
            case "futureBom" -> bom.setEffectiveDate(date.plusDays(1));
            case "expiredBom" -> bom.setEffectiveTo(date.minusDays(1));
            case "deletedBom" -> bom.setDeletedFlag(1);
            case "disabledProduct" -> product.setStatus("DISABLE");
            case "wrongProductMaterial" -> product.setMaterialId(999L);
            case "missingCapability" -> when(capabilities.selectList(any())).thenReturn(List.of());
            case "zeroFactor" -> capability.setCapacityFactor(BigDecimal.ZERO);
            case "invalidFactor" -> capability.setCapacityFactor(new BigDecimal("13"));
            case "zeroCapacity" -> line.setDailyCapacity(BigDecimal.ZERO);
            case "disabledLine" -> line.setStatus("DISABLE");
            case "deletedLine" -> line.setDeletedFlag(1);
            case "wrongRouteProduct" -> route.setProductId(999L);
            case "draftRoute" -> route.setStatus("DRAFT");
            case "deletedRoute" -> route.setDeletedFlag(1);
        }
        assertThrows(BusinessException.class,()->service.resolve(10L,4L,date));
    }
    @Test void prioritySelectionIsStableAndTiedDistinctResourcesAreRejected() {
        FactoryLine other=new FactoryLine();org.springframework.beans.BeanUtils.copyProperties(line,other);other.setLineId(41L);
        when(lines.selectById(41L)).thenReturn(other);
        MfgLineProductCapability second=new MfgLineProductCapability();org.springframework.beans.BeanUtils.copyProperties(capability,second);
        second.setLineId(41L);second.setPriorityLevel(2);
        when(capabilities.selectList(any())).thenReturn(List.of(second,capability));
        assertEquals(40L,service.resolve(10L,4L,date).line().getLineId());
        second.setPriorityLevel(1);
        assertThrows(BusinessException.class,()->service.resolve(10L,4L,date));
    }
    @Test void mixedChildVersionsAndMixedStockProductionSourcesCannotBeAggregated() {
        assertThrows(BusinessException.class,()->service.childBomReferences(List.of(item(20L),item(21L))));
        assertThrows(BusinessException.class,()->service.childBomReferences(List.of(item(null),item(20L))));
        assertEquals(Map.of(4L,20L),service.childBomReferences(List.of(item(20L),item(20L))));
    }
    private MdmBomItem item(Long child) {var item=new MdmBomItem();item.setBomId(10L);item.setMaterialId(4L);item.setChildBomId(child);item.setDeletedFlag(0);return item;}
}
