package com.neuedu.ems.service.mdm;

import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class MaterialQuantityPolicyTest {
    private final MaterialQuantityPolicy policy=new MaterialQuantityPolicy();
    private MdmMaterial material(String mode,String step) {
        MdmMaterial m=new MdmMaterial(); m.setQuantityMode(mode);m.setIssueStep(new BigDecimal(step));return m;
    }
    @Test void rejectsFractionalDiscreteExecution() {
        assertThrows(BusinessException.class,()->policy.validateExecution(material("DISCRETE","1"),new BigDecimal("1.5")));
    }
    @Test void permitsContinuousMeasurements() {
        assertDoesNotThrow(()->policy.validateExecution(material("CONTINUOUS","0.001"),new BigDecimal("1.125")));
    }
    @Test void aggregatesThenRoundsToIssuePack() {
        assertEquals(0,new BigDecimal("10").compareTo(policy.executionDemand(material("DISCRETE","5"),new BigDecimal("5.01"))));
    }
    @Test void rejectsFractionalDiscreteStepAndInvalidModes() {
        assertThrows(BusinessException.class,()->policy.validateDefinition(material("DISCRETE","0.1")));
        assertThrows(BusinessException.class,()->policy.validateDefinition(material("BAD","1")));
    }
    @Test void keepsZeroDemandZeroAndRejectsOverflow() {
        assertEquals(0,policy.executionDemand(material("DISCRETE","1"),BigDecimal.ZERO).signum());
        assertThrows(BusinessException.class,()->policy.executionDemand(material("DISCRETE","1"),new BigDecimal("99999999999999.1")));
    }
    @Test void rejectsSubPrecisionAndNonPositiveExecution() {
        assertThrows(BusinessException.class,()->policy.validateDefinition(material("CONTINUOUS","0.00001")));
        assertThrows(BusinessException.class,()->policy.validateExecution(material("DISCRETE","1"),BigDecimal.ZERO));
    }
}
