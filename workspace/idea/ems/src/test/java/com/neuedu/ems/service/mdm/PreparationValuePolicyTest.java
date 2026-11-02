package com.neuedu.ems.service.mdm;

import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparationValuePolicyTest {
    private final PreparationValuePolicy policy=new PreparationValuePolicy();
    @Test void draftPlaceholderCannotBeConfirmed(){
        for(String text:new String[]{"", "待确认", "TBD", "项目参数待填写"})
            assertThrows(BusinessException.class,()->policy.validateParameter("TEXT",text,"Q/LAMP-001-V0.1"));
    }
    @Test void validDesignValueNeedsAnEvidenceReference(){
        assertThrows(BusinessException.class,()->policy.validateParameter("DECIMAL","12",""));
        assertDoesNotThrow(()->policy.validateParameter("DECIMAL","12","Q/LAMP-001-V0.1 §2 设计值"));
    }
    @Test void integersAndDecimalPrecisionAreNotInterchangeable(){
        assertThrows(BusinessException.class,()->policy.validateParameter("INTEGER","1.5","R1初版"));
        assertThrows(BusinessException.class,()->policy.validateParameter("DECIMAL","0.00001","R1初版"));
        assertThrows(BusinessException.class,()->policy.validateParameter("DECIMAL","NaN","R1初版"));
        assertThrows(BusinessException.class,()->policy.validateParameter("DECIMAL","1e99999","R1初版"));
        assertThrows(BusinessException.class,()->policy.validateParameter("DECIMAL","100000000000000","R1初版"));
    }
    @Test void invalidRangesAndNominalValuesAreRejected(){
        assertThrows(BusinessException.class,()->policy.validateStandard("12","13","11","上下限判定","R1初版"));
        assertThrows(BusinessException.class,()->policy.validateStandard("15","11","13","上下限判定","R1初版"));
        assertDoesNotThrow(()->policy.validateStandard("12","11.64","12.36","含边界","R1初版"));
    }
    @Test void appearanceCanUseExplicitTextWithoutInventedNumericBounds(){
        assertDoesNotThrow(()->policy.validateStandard("外观无可见裂纹",null,null,"对照封样逐项核查","Q/LAMP-001-V0.1"));
    }
}
