package com.neuedu.ems.service.mdm;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.mapper.mdm.BomStockMapper;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.mybatis.spring.SqlSessionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"mes.notification.scheduling-enabled=false",PhoneCryptoTestProperties.ACTIVE_KEY,
        PhoneCryptoTestProperties.KEY_IDS,PhoneCryptoTestProperties.ENCRYPTION_KEY,PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@Transactional
@EnabledIfEnvironmentVariable(named="DB_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:(?:3306|3307|3310)/mes_lamp_.*")
class LampFoundationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BomStockMapper stocks;
    @Autowired BomExpansionService expansion;
    @Autowired SqlSessionTemplate session;
    @Test void restoredAndMigratedSchemaCalculatesLampDesign() {
        Long id=jdbc.queryForObject("SELECT bom_id FROM mdm_bom WHERE bom_code='LAMP-BOM-L200-V1'",Long.class);
        var result=expansion.expand(id,new BigDecimal("100"),LocalDate.of(2026,10,18),false);
        assertEquals(18,result.distinctMaterialCount());assertEquals(16,result.rawMaterialCount());
        assertEquals(0,new BigDecimal("800").compareTo(result.grossMaterials().stream().filter(r->r.materialCode().equals("LAMP-M18")).findFirst().orElseThrow().executionQuantity()));
        assertThrows(BusinessException.class,()->expansion.expand(id,new BigDecimal("1.5"),LocalDate.of(2026,10,18),false));
    }
    @Test void stockDeductionExcludesBlockedLocationsAndUnreleasedBatches() {
        Long material=jdbc.queryForObject("SELECT material_id FROM mdm_material WHERE material_code='LAMP-HEAD'",Long.class);
        // 演练库里 LAMP-HEAD 已有真实产出库存，而且共用库位被锁会连带影响那些真实行。
        // 因此本用例自建一套「半成品仓 + 正常库位」，只在这套隔离数据上验证抵扣口径，
        // 断言一律用「相对基线的增量」，避免每跑一次端到端演示就把断言推翻。
        String suffix=String.valueOf(System.nanoTime());
        BigDecimal baseline=stocks.availableComponents(material);
        jdbc.update("INSERT INTO wms_warehouse(warehouse_code,warehouse_name,warehouse_type,status,deleted_flag) VALUES (?,?,'SEMI_FINISHED','ENABLE',0)","TEST-WH-"+suffix,"用例半成品仓");
        Long warehouse=jdbc.queryForObject("SELECT warehouse_id FROM wms_warehouse WHERE warehouse_code=?",Long.class,"TEST-WH-"+suffix);
        jdbc.update("INSERT INTO wms_location(location_code,location_name,warehouse_id,location_type,status,deleted_flag) VALUES (?,?,?,'NORMAL','ENABLE',0)","TEST-LOC-"+suffix,"用例正常库位",warehouse);
        Long location=jdbc.queryForObject("SELECT location_id FROM wms_location WHERE location_code=?",Long.class,"TEST-LOC-"+suffix);
        String batchNo="LAMP-TEST-"+suffix;
        jdbc.update("INSERT INTO wms_material_batch(batch_no,material_id,quality_status,status) VALUES (?,?,'PASS','AVAILABLE')",batchNo,material);
        Long batch=jdbc.queryForObject("SELECT batch_id FROM wms_material_batch WHERE batch_no=?",Long.class,batchNo);
        jdbc.update("INSERT INTO wms_inventory(warehouse_id,location_id,material_id,batch_id,available_quantity,locked_quantity,inspection_quantity) VALUES (?,?,?,?,4,3,2)",warehouse,location,material,batch);
        // 上面全是 JDBC 直写，绕过 MyBatis 一级缓存，读之前必须先清，否则拿到的是插入前的旧值。
        session.clearCache();
        assertEquals(0,baseline.add(new BigDecimal("4")).compareTo(stocks.availableComponents(material)));
        jdbc.update("UPDATE wms_material_batch SET quality_status='FAIL' WHERE batch_id=?",batch);
        // JDBC fixture updates bypass MyBatis' session-cache invalidation.
        session.clearCache();
        assertEquals(0,baseline.compareTo(stocks.availableComponents(material)));
        jdbc.update("UPDATE wms_material_batch SET quality_status='PASS' WHERE batch_id=?",batch);
        jdbc.update("UPDATE wms_location SET status='LOCKED' WHERE location_id=?",location);
        session.clearCache();
        assertEquals(0,baseline.compareTo(stocks.availableComponents(material)));
    }
}
