package com.neuedu.ems.service.mdm;

import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.vo.mdm.BomExpansion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BomExpansionServiceTest {
    private final MdmBomMapper boms=mock(MdmBomMapper.class);
    private final MdmBomItemMapper items=mock(MdmBomItemMapper.class);
    private final MdmMaterialMapper materials=mock(MdmMaterialMapper.class);
    private final MdmProductMapper products=mock(MdmProductMapper.class);
    private final MdmUnitMapper units=mock(MdmUnitMapper.class);
    private final BomStockMapper stocks=mock(BomStockMapper.class);
    private final Map<Long,MdmBom> bomData=new HashMap<>();
    private final Map<Long,List<MdmBomItem>> itemData=new HashMap<>();
    private final Map<Long,MdmMaterial> materialData=new HashMap<>();
    private final Map<Long,MdmProduct> productData=new HashMap<>();
    private BomExpansionService service;
    private final LocalDate date=LocalDate.of(2026,10,18);
    private long sequence;

    @BeforeEach void setup() {
        service=new BomExpansionService(boms,items,materials,products,units,stocks,new MaterialQuantityPolicy());
        when(boms.selectById(any())).thenAnswer(i->bomData.get(i.getArgument(0)));
        when(items.activeItems(any())).thenAnswer(i->itemData.get(i.getArgument(0)));
        when(materials.selectById(any())).thenAnswer(i->materialData.get(i.getArgument(0)));
        when(products.selectById(any())).thenAnswer(i->productData.get(i.getArgument(0)));
        MdmUnit unit=new MdmUnit();unit.setUnitId(1L);unit.setUnitName("件");unit.setDeletedFlag(0);unit.setStatus("ENABLE");
        when(units.selectById(1L)).thenReturn(unit);
        when(stocks.availableComponents(any())).thenReturn(BigDecimal.ZERO);
        for(long n=1;n<=18;n++) material(n,n<=16?"RAW":"AUXILIARY");
        for(long n=101;n<=104;n++) material(n,"SEMI_FINISHED");
        material(201,"FINISHED");material(202,"FINISHED");
        bom(10,101);bom(20,102);bom(30,103);bom(40,104);bom(100,201);bom(200,202);
        for(long n:new long[]{1,2,3,4}) line(10,n,"1",null);
        line(10,18,"2",null);
        line(20,12,"1",null);line(20,13,"1",null);line(20,14,"2",null);line(20,18,"2",null);
        for(long n:new long[]{5,6,8,9,10,11}) line(30,n,"1",null);
        line(30,18,"2",null);
        for(long n:new long[]{5,6,7,8,9,10,11}) line(40,n,"1",null);
        line(40,18,"2",null);
        for(long root:new long[]{100,200}) {
            line(root,101,"1",10L);line(root,102,"1",20L);line(root,root==100?103:104,"1",root==100?30L:40L);
            line(root,15,"1",null);line(root,16,"1",null);line(root,17,"4",null);line(root,18,"2",null);
        }
    }
    private void material(long id,String type) {
        MdmMaterial m=new MdmMaterial();m.setMaterialId(id);m.setMaterialCode("M"+id);m.setMaterialName("物料"+id);
        m.setMaterialType(type);m.setStatus("ENABLE");m.setDeletedFlag(0);m.setUnitId(1L);m.setQuantityMode("DISCRETE");m.setIssueStep(BigDecimal.ONE);materialData.put(id,m);
    }
    private void bom(long id,long output) {
        MdmBom b=new MdmBom();b.setBomId(id);b.setProductId(id);b.setBomCode("B"+id);b.setBomVersion("V1");b.setEffectiveDate(date);b.setStatus("RELEASED");b.setDeletedFlag(0);bomData.put(id,b);itemData.put(id,new ArrayList<>());
        MdmProduct p=new MdmProduct();p.setProductId(id);p.setMaterialId(output);p.setStatus("ENABLE");p.setDeletedFlag(0);productData.put(id,p);
    }
    private void line(long bom,long material,String qty,Long child) {
        MdmBomItem i=new MdmBomItem();i.setBomItemId(++sequence);i.setBomId(bom);i.setMaterialId(material);i.setQuantity(new BigDecimal(qty));i.setLossRate(BigDecimal.ZERO);i.setChildBomId(child);itemData.get(bom).add(i);
    }
    private BomExpansion.Demand demand(List<BomExpansion.Demand> rows,long id) { return rows.stream().filter(x->x.materialId()==id).findFirst().orElseThrow(); }
    @Test void l200MergesScrewsAndCountsOnlyDistinctLeaves() {
        var result=service.expand(200L,new BigDecimal("100"),date,false);
        assertEquals(18,result.distinctMaterialCount());assertEquals(16,result.rawMaterialCount());
        assertEquals(7,result.directItems().size());
        assertEquals(0,new BigDecimal("800").compareTo(demand(result.grossMaterials(),18).executionQuantity()));
        assertEquals(0,new BigDecimal("100").compareTo(demand(result.grossMaterials(),16).executionQuantity()));
        assertEquals(0,new BigDecimal("200").compareTo(demand(result.directItems(),18).executionQuantity()));
        verify(stocks,never()).availableComponents(any());
    }
    @Test void l100ExcludesSensor() {
        var r=service.expand(100L,BigDecimal.ONE,date,false);
        assertEquals(17,r.distinctMaterialCount());assertEquals(15,r.rawMaterialCount());
        assertTrue(r.grossMaterials().stream().noneMatch(x->x.materialId()==7));
    }
    @Test void deductsComponentsBeforeExpandingTheirPurchaseMaterials() {
        when(stocks.availableComponents(101L)).thenReturn(new BigDecimal("5"));
        var r=service.expand(200L,BigDecimal.TEN,date,true);
        assertEquals(0,new BigDecimal("5").compareTo(demand(r.netMaterials(),1).executionQuantity()));
        assertEquals(0,new BigDecimal("70").compareTo(demand(r.netMaterials(),18).executionQuantity()));
        verify(stocks,times(1)).availableComponents(101L);
    }
    @Test void roundsEachWorkOrderBeforeAggregatingCommonPurchaseMaterials() {
        for(long id:new long[]{10,20,40,200}) itemData.get(id).stream().filter(x->x.getMaterialId()==18).forEach(x->x.setQuantity(new BigDecimal("0.01")));
        var d=demand(service.expand(200L,BigDecimal.ONE,date,false).grossMaterials(),18);
        assertEquals(0,new BigDecimal("0.04").compareTo(d.theoreticalQuantity()));assertEquals(new BigDecimal("4"),d.executionQuantity());
    }
    @Test void mergesRepeatedLinesWithinOneWorkOrderBeforeRounding() {
        itemData.get(200L).removeIf(x->x.getMaterialId()==18);
        line(200,18,"0.01",null);line(200,18,"0.03",null);
        var d=demand(service.expand(200L,BigDecimal.ONE,date,false).directItems(),18);
        assertEquals(0,new BigDecimal("0.04").compareTo(d.theoreticalQuantity()));
        assertEquals(BigDecimal.ONE,d.executionQuantity());
    }
    @Test void roundsComponentsBeforeExpandingChildWorkOrderInputs() {
        itemData.get(200L).stream().filter(x->x.getMaterialId()==101).forEach(x->x.setQuantity(new BigDecimal("0.1")));
        var r=service.expand(200L,BigDecimal.ONE,date,false);
        assertEquals(BigDecimal.ONE,demand(r.netMaterials(),1).executionQuantity());
        assertEquals(BigDecimal.ONE,demand(r.grossMaterials(),1).executionQuantity());
    }
    @Test void rejectsCycles() {
        materialData.get(202L).setMaterialType("SEMI_FINISHED");line(10,202,"1",200L);
        assertTrue(assertThrows(BusinessException.class,()->service.expand(200L,BigDecimal.ONE,date,false)).getMessage().contains("循环"));
    }
    @Test void rejectsInvalidChildVersionAndDates() {
        bomData.get(10L).setStatus("DRAFT");
        assertThrows(BusinessException.class,()->service.expand(200L,BigDecimal.ONE,date,false));
        bomData.get(10L).setStatus("RELEASED");bomData.get(10L).setEffectiveTo(date.minusDays(1));
        assertThrows(BusinessException.class,()->service.expand(200L,BigDecimal.ONE,date,false));
    }
    @Test void rejectsComponentOutputMismatch() {
        productData.get(10L).setMaterialId(102L);
        assertThrows(BusinessException.class,()->service.expand(200L,BigDecimal.ONE,date,false));
    }
    @Test void rejectsMixedVersionsOfSameComponent() {
        bom(11,101);line(11,1,"1",null);line(200,101,"1",11L);
        assertTrue(assertThrows(BusinessException.class,()->service.expand(200L,BigDecimal.ONE,date,false)).getMessage().contains("多个 BOM"));
    }
    @Test void rejectsFractionalLampQuantityAndMissingChildReference() {
        assertThrows(BusinessException.class,()->service.expand(200L,new BigDecimal("1.5"),date,false));
        itemData.get(200L).getFirst().setChildBomId(null);
        assertThrows(BusinessException.class,()->service.expand(200L,BigDecimal.ONE,date,false));
    }
    @Test void supportsFractionalContinuousComponents() {
        MdmMaterial m=materialData.get(15L);m.setQuantityMode("CONTINUOUS");m.setIssueStep(new BigDecimal("0.001"));
        itemData.get(200L).stream().filter(x->x.getMaterialId()==15).forEach(x->x.setQuantity(new BigDecimal("0.125")));
        assertEquals(0,new BigDecimal("0.125").compareTo(demand(service.expand(200L,BigDecimal.ONE,date,false).grossMaterials(),15).executionQuantity()));
    }
}
