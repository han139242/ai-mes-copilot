package com.neuedu.ems.service.wms;

import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在台灯验证库上走采购入库：批次服务强制待检，不合格不能入可用库存，放行后确认记账，重复确认不重复记账。
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310)/mes_lamp_lineside_[A-Za-z0-9_]+.*")
@Transactional
class LampPurchaseReleaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MdmMaterialMapper materials;
    @Autowired WmsWarehouseService warehouses;
    @Autowired WmsLocationService locations;
    @Autowired WmsMaterialBatchService batches;
    @Autowired WmsInboundOrderService inbounds;
    @Autowired QmsInspectionOrderService inspections;

    @Test
    void unreleasedBatchCannotEnterStockAndRepeatConfirmDoesNotDoublePost() {
        String tag = "PR" + System.nanoTime();
        Long actor = jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE username='admin' AND deleted_flag=0", Long.class);
        MdmMaterial material = new MdmMaterial();
        material.setMaterialCode(tag);
        material.setMaterialName("采购放行验证原料");
        material.setMaterialType("RAW");
        material.setQuantityMode("DISCRETE");
        material.setIssueStep(BigDecimal.ONE);
        material.setStatus("ENABLE");
        material.setDeletedFlag(0);
        materials.insert(material);

        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setWarehouseCode(tag);
        warehouse.setWarehouseName("采购放行原料仓");
        warehouse.setWarehouseType("RAW");
        warehouse.setStatus("ENABLE");
        assertTrue(warehouses.saveWarehouse(warehouse));
        WmsLocation location = new WmsLocation();
        location.setWarehouseId(warehouse.getWarehouseId());
        location.setLocationCode(tag);
        location.setLocationName("采购放行库位");
        location.setStatus("ENABLE");
        assertTrue(locations.saveLocation(location));

        WmsMaterialBatch rejected = openBatch(tag + "-FAIL", material.getMaterialId());
        Long rejectedInbound = inbound(tag + "-IN-FAIL", warehouse.getWarehouseId(), location.getLocationId(), rejected, actor);
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> inbounds.confirmInboundOrder(rejectedInbound, actor, "未放行不能入库"));
        assertTrue(blocked.getMessage().contains("质检放行"));
        assertEquals(0, available(rejected.getBatchId()).compareTo(BigDecimal.ZERO));

        assertTrue(inspections.submitInspectionOrder(inspection(tag + "-IQC-FAIL", rejected.getBatchId(), actor),
                "FAIL", "抽检不合格", actor));
        BusinessException stillBlocked = assertThrows(BusinessException.class,
                () -> inbounds.confirmInboundOrder(rejectedInbound, actor, "不合格不能入库"));
        assertTrue(stillBlocked.getMessage().contains("质检放行"));
        assertEquals(0, movementCount(rejectedInbound));

        WmsMaterialBatch released = openBatch(tag + "-PASS", material.getMaterialId());
        Long releasedInbound = inbound(tag + "-IN-PASS", warehouse.getWarehouseId(), location.getLocationId(), released, actor);
        assertTrue(inspections.submitInspectionOrder(inspection(tag + "-IQC-PASS", released.getBatchId(), actor),
                "PASS", "抽检合格", actor));
        assertTrue(inbounds.confirmInboundOrder(releasedInbound, actor, "放行后入库"));
        assertEquals(0, new BigDecimal("4").compareTo(available(released.getBatchId())));
        assertEquals(0, new BigDecimal("4").compareTo(movementNet(releasedInbound)));
        assertEquals(1, movementCount(releasedInbound));

        assertThrows(BusinessException.class,
                () -> inbounds.confirmInboundOrder(releasedInbound, actor, "重复确认"));
        assertEquals(0, new BigDecimal("4").compareTo(available(released.getBatchId())));
        assertEquals(1, movementCount(releasedInbound));
    }

    private WmsMaterialBatch openBatch(String batchNo, Long materialId) {
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchNo(batchNo);
        batch.setMaterialId(materialId);
        batch.setQualityStatus("PASS");
        batch.setStatus("AVAILABLE");
        assertTrue(batches.saveMaterialBatch(batch));
        WmsMaterialBatch stored = batches.getMaterialBatchById(batch.getBatchId());
        assertEquals("WAIT_INSPECTION", stored.getQualityStatus());
        assertEquals("FROZEN", stored.getStatus());
        return stored;
    }

    private Long inbound(String inboundNo, Long warehouseId, Long locationId, WmsMaterialBatch batch, Long actor) {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setInboundNo(inboundNo);
        order.setInboundType("PURCHASE");
        order.setWarehouseId(warehouseId);
        order.setCreatedBy(actor);
        WmsInboundDetail detail = new WmsInboundDetail();
        detail.setMaterialId(batch.getMaterialId());
        detail.setBatchId(batch.getBatchId());
        detail.setLocationId(locationId);
        detail.setInboundQuantity(new BigDecimal("4"));
        return inbounds.saveInboundOrderFull(order, List.of(detail));
    }

    private Long inspection(String inspectionNo, Long batchId, Long actor) {
        QmsInspectionOrder order = new QmsInspectionOrder();
        order.setInspectionNo(inspectionNo);
        order.setInspectionType("INCOMING");
        order.setBatchId(batchId);
        order.setCreatedBy(actor);
        assertTrue(inspections.saveInspectionOrder(order));
        return order.getInspectionId();
    }

    private BigDecimal available(Long batchId) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(available_quantity),0) FROM wms_inventory WHERE batch_id=? AND deleted_flag=0",
                BigDecimal.class, batchId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private int movementCount(Long inboundId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_stock_movement WHERE source_order_id=? AND source_order_type='INBOUND_ORDER' AND deleted_flag=0",
                Integer.class, inboundId);
        return count == null ? 0 : count;
    }

    private BigDecimal movementNet(Long inboundId) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(IF(direction='IN',quantity,-quantity)),0) FROM wms_stock_movement WHERE source_order_id=? AND source_order_type='INBOUND_ORDER' AND deleted_flag=0",
                BigDecimal.class, inboundId);
        return value == null ? BigDecimal.ZERO : value;
    }
}
