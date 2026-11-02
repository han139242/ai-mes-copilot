package com.neuedu.ems.service.mdm;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.wms.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.impl.wms.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"mes.notification.scheduling-enabled=false",PhoneCryptoTestProperties.ACTIVE_KEY,
        PhoneCryptoTestProperties.KEY_IDS,PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@Transactional
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:(?:3306|3307|3310)/mes_lamp_.*")
class LampReportAlignmentIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WmsInboundOrderServiceImpl inbound;
    @Autowired WmsOutboundOrderServiceImpl outbound;
    @Autowired WmsStocktakeOrderServiceImpl stocktake;

    private Long material(){return jdbc.queryForObject("SELECT material_id FROM mdm_material WHERE material_code='LAMP-M18'",Long.class);}
    @Test void sourceDataAndRoutesMatchReportWithoutOpeningExecution(){
        assertEquals(9,jdbc.queryForObject("SELECT COUNT(*) FROM process_operation WHERE remark='LAMP_REPORT_V18'",Integer.class));
        assertEquals(14,jdbc.queryForObject("SELECT COUNT(*) FROM process_route_item WHERE remark='LAMP_REPORT_V18' AND deleted_flag=0",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM process_route_item WHERE remark='LAMP_REPORT_V18' AND resource_type='BATCH_EQUIPMENT' AND batch_capacity=50 AND batch_duration_minutes=60 AND standard_time=0",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mdm_product_parameter WHERE parameter_code IN ('MAX_POWER','CCT','CENTER_ILLUMINANCE') AND required_flag=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qms_inspection_scheme_item x JOIN qms_inspection_scheme s ON s.scheme_id=x.scheme_id JOIN qms_inspection_standard v ON v.standard_id=x.standard_id WHERE s.remark='LAMP_REPORT_V18' AND v.source_reference NOT LIKE 'QST-LAMP-SPEC-01 V1.0%'",Integer.class));
    }
    @Test void realInboundAndOutboundValidatorsRejectFractionalScrewsBeforeStockMutation(){
        var i=new WmsInboundDetail();i.setMaterialId(material());i.setBatchId(1L);i.setLocationId(1L);i.setInboundQuantity(new BigDecimal("0.04"));
        assertTrue(assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(inbound,"validateInboundDetails",List.of(i))).getMessage().contains("整数"));
        var o=new WmsOutboundDetail();o.setDetailId(1L);o.setMaterialId(material());o.setBatchId(1L);o.setLocationId(1L);o.setOutboundQuantity(new BigDecimal("0.04"));
        assertTrue(assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(outbound,"prepareInventoryDeductions",new WmsOutboundOrder(),List.of(o))).getMessage().contains("整数"));
    }
    @Test void stocktakeRejectsFractionalCountButAllowsZero(){
        var d=new WmsStocktakeDetail();d.setMaterialId(material());d.setActualQuantity(new BigDecimal("0.04"));
        assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(stocktake,"calculateDifference",d));
        d.setActualQuantity(BigDecimal.ZERO);
        assertEquals(BigDecimal.ZERO,ReflectionTestUtils.<BigDecimal>invokeMethod(stocktake,"calculateDifference",d));
    }
}
