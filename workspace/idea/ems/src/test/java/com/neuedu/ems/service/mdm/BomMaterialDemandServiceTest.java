package com.neuedu.ems.service.mdm;

import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BomMaterialDemandServiceTest {
    private final MdmMaterialMapper materials=mock(MdmMaterialMapper.class);
    private final MaterialQuantityPolicy policy=new MaterialQuantityPolicy();
    private final BomMaterialDemandService service=new BomMaterialDemandService(materials,policy);
    private final MaterialExecutionQuantityService execution=new MaterialExecutionQuantityService(materials,policy);

    private void material(String mode,String step){
        var m=new MdmMaterial();m.setStatus("ENABLE");m.setDeletedFlag(0);m.setQuantityMode(mode);m.setIssueStep(new BigDecimal(step));
        when(materials.selectById(1L)).thenReturn(m);
    }
    private MdmBomItem line(String qty,String loss){
        var i=new MdmBomItem();i.setMaterialId(1L);i.setQuantity(new BigDecimal(qty));i.setLossRate(new BigDecimal(loss));return i;
    }
    @Test void additionalLossUsesReportFormula(){
        material("DISCRETE","1");
        assertEquals(new BigDecimal("206"),service.calculate(List.of(line("2","0.03")),new BigDecimal("100")).get(1L));
    }
    @Test void mergesLinesBeforeRoundingAndDoesNotRoundEachIntermediateProduct(){
        material("DISCRETE","1");
        assertEquals(BigDecimal.ONE,service.calculate(List.of(line("0.0001","0.0001"),line("0.0001","0.0001")),BigDecimal.ONE).get(1L));
    }
    @Test void honorsPackagingStepWithoutChangingInputExecutionAmounts(){
        material("DISCRETE","5");
        assertEquals(new BigDecimal("210"),service.calculate(List.of(line("2","0.03")),new BigDecimal("100")).get(1L));
        assertThrows(BusinessException.class,()->execution.validate(1L,new BigDecimal("206"),false));
    }
    @Test void rejectsLossOutsideReportRangeAndMissingMaterial(){
        material("DISCRETE","1");
        assertThrows(BusinessException.class,()->service.calculate(List.of(line("2","1.01")),BigDecimal.ONE));
        assertThrows(BusinessException.class,()->service.calculate(List.of(line("2","-0.01")),BigDecimal.ONE));
        when(materials.selectById(1L)).thenReturn(null);
        assertThrows(BusinessException.class,()->service.calculate(List.of(line("2","0")),BigDecimal.ONE));
    }
    @Test void rejectsFractionalDiscreteExecutionInsteadOfRounding(){
        material("DISCRETE","1");
        assertThrows(BusinessException.class,()->execution.validate(1L,new BigDecimal("0.04"),false));
        assertThrows(BusinessException.class,()->execution.validate(1L,new BigDecimal("-1"),true));
        assertDoesNotThrow(()->execution.validate(1L,BigDecimal.ZERO,true));
        assertThrows(BusinessException.class,()->execution.validate(1L,BigDecimal.ZERO,false));
    }
    @Test void permitsContinuousMaterialAndRejectsSubPrecision(){
        material("CONTINUOUS","0.001");
        assertDoesNotThrow(()->execution.validate(1L,new BigDecimal("0.125"),false));
        assertThrows(BusinessException.class,()->execution.validate(1L,new BigDecimal("0.1251"),false));
    }
}
