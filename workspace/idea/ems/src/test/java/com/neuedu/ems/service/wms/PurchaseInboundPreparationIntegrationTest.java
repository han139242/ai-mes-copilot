package com.neuedu.ems.service.wms;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_[A-Za-z0-9_]+.*")
@Transactional
class PurchaseInboundPreparationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WmsInboundOrderService inbounds;

    @Test
    void blankBatchCreatesPendingInspectionWithoutIncreasingAvailableStock() {
        var location = jdbc.queryForMap("SELECT l.location_id,l.warehouse_id FROM wms_location l JOIN wms_warehouse w "
                + "ON w.warehouse_id=l.warehouse_id WHERE l.deleted_flag=0 AND w.deleted_flag=0 "
                + "AND w.warehouse_type='RAW' LIMIT 1");
        Long material = jdbc.queryForObject("SELECT material_id FROM mdm_material WHERE deleted_flag=0 "
                + "AND status='ENABLE' AND material_type='RAW' LIMIT 1", Long.class);
        WmsInboundOrder order = new WmsInboundOrder();
        order.setInboundType("PURCHASE");
        order.setWarehouseId(((Number) location.get("warehouse_id")).longValue());
        order.setCreatedBy(1L);
        WmsInboundDetail first = detail(material, ((Number) location.get("location_id")).longValue());
        WmsInboundDetail second = detail(material, first.getLocationId());
        Long id = inbounds.saveInboundOrderFull(order, List.of(first, second));
        assertNotNull(first.getBatchId());
        assertEquals(first.getBatchId(), second.getBatchId());
        assertEquals("WAIT_INSPECTION", jdbc.queryForObject(
                "SELECT quality_status FROM wms_material_batch WHERE batch_id=?", String.class, first.getBatchId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wms_inventory WHERE batch_id=?",
                Integer.class, first.getBatchId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM qms_inspection_order WHERE batch_id=? "
                + "AND inspection_type='INCOMING' AND deleted_flag=0", Integer.class, first.getBatchId()));
        assertEquals(0, new BigDecimal("2").compareTo(jdbc.queryForObject(
                "SELECT sample_quantity FROM qms_inspection_order WHERE batch_id=? AND deleted_flag=0",
                BigDecimal.class, first.getBatchId())));
        assertThrows(BusinessException.class, () -> inbounds.confirmInboundOrder(id, 1L, "not released"));
    }

    @Test
    void locationFromAnotherWarehouseIsRejected() {
        Long warehouse = jdbc.queryForObject("SELECT MIN(warehouse_id) FROM wms_warehouse WHERE deleted_flag=0", Long.class);
        Long location = jdbc.queryForObject("SELECT MIN(location_id) FROM wms_location WHERE deleted_flag=0 AND warehouse_id<>?",
                Long.class, warehouse);
        Long material = jdbc.queryForObject("SELECT MIN(material_id) FROM mdm_material WHERE deleted_flag=0 AND status='ENABLE'",
                Long.class);
        WmsInboundOrder order = new WmsInboundOrder();
        order.setInboundType("PURCHASE"); order.setWarehouseId(warehouse); order.setCreatedBy(1L);
        assertThrows(BusinessException.class, () -> inbounds.saveInboundOrderFull(order, List.of(detail(material, location))));
    }

    private WmsInboundDetail detail(Long material, Long location) {
        WmsInboundDetail detail = new WmsInboundDetail();
        detail.setMaterialId(material); detail.setLocationId(location); detail.setInboundQuantity(BigDecimal.ONE);
        return detail;
    }
}
