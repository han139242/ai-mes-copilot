package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.neuedu.ems.PhoneCryptoTestProperties;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrder;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrderDetail;
import com.neuedu.ems.entity.mfg.MfgSalesOrder;
import com.neuedu.ems.entity.mfg.MfgSalesOrderDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在台灯演练库上验证销售订单与采购订单真正能跑通主流程链。
 *
 * <p>销售侧：确认后逐行生成生产订单并回填来源，已发货数量从销售出库流水回写；
 * 采购侧：下达后生成采购入库单，已收数量从已确认的入库流水回写。
 * 两条回写都直接用真实 SQL 验证，因为这里是唯一在真库上能证明汇总口径正确的地方。</p>
 */
@SpringBootTest(properties = {"mes.notification.scheduling-enabled=false", "mes.ai.enabled=false",
        PhoneCryptoTestProperties.ACTIVE_KEY, PhoneCryptoTestProperties.KEY_IDS,
        PhoneCryptoTestProperties.ENCRYPTION_KEY, PhoneCryptoTestProperties.LOOKUP_HMAC_KEY})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(3307|3310|3311)/mes_lamp_[A-Za-z0-9_]+.*")
@Transactional
class LampSalesPurchaseOrderFlowIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MfgSalesOrderService salesOrders;
    @Autowired MfgPurchaseOrderService purchaseOrders;
    @Autowired MfgProductionOrderService productionOrders;

    @Test
    void salesOrderConfirmCreatesProductionOrdersAndWithdrawIsRefusedOnceReleased() {
        Long actor = actor();
        Long salesOrderId = salesOrders.saveSalesOrderFull(
                salesOrder(1L, LocalDate.now().plusDays(10)),
                List.of(detail(1L, "2"), detail(6L, "1")),
                actor);
        assertNotNull(salesOrderId);

        MfgSalesOrder saved = salesOrders.getSalesOrderById(salesOrderId);
        assertEquals(BusinessStatus.Document.DRAFT, saved.getStatus());
        assertEquals(0, new BigDecimal("3").compareTo(saved.getTotalQuantity()));

        List<Long> productionOrderIds = salesOrders.confirmSalesOrder(salesOrderId, actor, "确认排产");
        assertEquals(2, productionOrderIds.size());
        assertEquals(BusinessStatus.Document.SUBMITTED, salesOrders.getSalesOrderById(salesOrderId).getStatus());

        List<MfgSalesOrderDetail> details = salesOrders.listDetails(salesOrderId);
        List<MfgProductionOrder> orders = productionOrders.list(new QueryWrapper<MfgProductionOrder>()
                .eq("source_sales_order_id", salesOrderId).eq("deleted_flag", 0));
        assertEquals(2, orders.size());
        for (MfgProductionOrder order : orders) {
            assertEquals("SALES_ORDER", order.getSourceType());
            assertEquals(salesOrderId, order.getSourceSalesOrderId());
            assertNotNull(order.getSourceSalesOrderDetailId());
            assertTrue(details.stream().anyMatch(d -> d.getDetailId().equals(order.getSourceSalesOrderDetailId())),
                    "生产订单必须指回本单的某一行明细");
        }

        productionOrders.releaseOrder(orders.get(0).getProductionOrderId(), actor, "下达");
        BusinessException refused = assertThrows(BusinessException.class,
                () -> salesOrders.withdrawSalesOrder(salesOrderId, actor, "测试撤回"));
        assertTrue(refused.getMessage().contains("不能撤回"));
    }

    @Test
    void deliveredQuantityComesFromConfirmedSalesOutbound() {
        Long actor = actor();
        Long salesOrderId = salesOrders.saveSalesOrderFull(
                salesOrder(2L, LocalDate.now().plusDays(10)),
                List.of(detail(6L, "4")),
                actor);
        salesOrders.confirmSalesOrder(salesOrderId, actor, "确认排产");
        MfgSalesOrderDetail detail = salesOrders.listDetails(salesOrderId).get(0);

        MfgProductionOrder order = productionOrders.list(new QueryWrapper<MfgProductionOrder>()
                .eq("source_sales_order_detail_id", detail.getDetailId()).eq("deleted_flag", 0)).get(0);

        // 造一张已确认的销售出库单：发货 3 台，另外造一张作废单应当不计入。
        Long outboundId = insertSalesOutbound("SO-OUT-" + System.nanoTime(), order.getProductionOrderId(), 0);
        insertOutboundDetail(outboundId, detail.getProductId(), "3");
        Long voidedId = insertSalesOutbound("SO-OUT-" + System.nanoTime(), order.getProductionOrderId(), 1);
        insertOutboundDetail(voidedId, detail.getProductId(), "9");
        Long pendingId = insertSalesOutbound("SO-OUT-" + System.nanoTime(), order.getProductionOrderId(), 0);
        jdbc.update("UPDATE wms_outbound_order SET audit_status='SUBMITTED' WHERE outbound_id=?", pendingId);
        insertOutboundDetail(pendingId, detail.getProductId(), "4");
        Long closedId = insertSalesOutbound("SO-OUT-" + System.nanoTime(), order.getProductionOrderId(), 0);
        jdbc.update("UPDATE wms_outbound_order SET audit_status='CLOSED' WHERE outbound_id=?", closedId);
        insertOutboundDetail(closedId, detail.getProductId(), "7");

        salesOrders.refreshDeliveredQuantity(salesOrderId, actor);

        BigDecimal delivered = salesOrders.listDetails(salesOrderId).get(0).getDeliveredQuantity();
        assertEquals(0, new BigDecimal("3").compareTo(delivered));
        assertEquals(BusinessStatus.Document.SUBMITTED, salesOrders.getSalesOrderById(salesOrderId).getStatus());
    }

    @Test
    void purchaseOrderReleaseCreatesInboundAndReceivedQuantityFollowsConfirmedInbound() {
        Long actor = actor();
        MfgPurchaseOrder order = new MfgPurchaseOrder();
        order.setSupplierId(1L);
        order.setWarehouseId(1L);
        order.setOrderDate(LocalDate.now());
        order.setExpectedDate(LocalDate.now().plusDays(5));
        MfgPurchaseOrderDetail line = new MfgPurchaseOrderDetail();
        line.setMaterialId(1L);
        line.setOrderQuantity(new BigDecimal("10"));

        Long purchaseOrderId = purchaseOrders.savePurchaseOrderFull(order, List.of(line), actor);
        assertEquals(BusinessStatus.Document.DRAFT, purchaseOrders.getPurchaseOrderById(purchaseOrderId).getStatus());

        Long inboundId = purchaseOrders.releasePurchaseOrder(purchaseOrderId, actor, "下单");
        assertNotNull(inboundId);
        MfgPurchaseOrder released = purchaseOrders.getPurchaseOrderById(purchaseOrderId);
        assertEquals(BusinessStatus.Document.RELEASED, released.getStatus());

        WmsInboundOrder inbound = jdbc.queryForObject(
                "SELECT inbound_id, inbound_no, inbound_type, source_order_type, source_order_id, audit_status "
                        + "FROM wms_inbound_order WHERE inbound_id = ?",
                (rs, rowNum) -> {
                    WmsInboundOrder item = new WmsInboundOrder();
                    item.setInboundId(rs.getLong("inbound_id"));
                    item.setInboundNo(rs.getString("inbound_no"));
                    item.setInboundType(rs.getString("inbound_type"));
                    item.setSourceOrderType(rs.getString("source_order_type"));
                    item.setSourceOrderId(rs.getLong("source_order_id"));
                    item.setAuditStatus(rs.getString("audit_status"));
                    return item;
                }, inboundId);
        assertNotNull(inbound);
        assertEquals("PURCHASE", inbound.getInboundType());
        assertEquals("PURCHASE_ORDER", inbound.getSourceOrderType());
        assertEquals(purchaseOrderId, inbound.getSourceOrderId());

        // 未确认的入库单不计入已收数量。
        purchaseOrders.refreshReceivedQuantity(purchaseOrderId, actor);
        assertEquals(0, BigDecimal.ZERO.compareTo(
                purchaseOrders.listDetails(purchaseOrderId).get(0).getReceivedQuantity()));

        // 收货确认后按入库流水回写：本次到货 6 个。
        jdbc.update("UPDATE wms_inbound_order SET audit_status = ? WHERE inbound_id = ?",
                BusinessStatus.Document.APPROVED, inboundId);
        jdbc.update("UPDATE wms_inbound_detail SET inbound_quantity = ? WHERE inbound_id = ?",
                new BigDecimal("6"), inboundId);
        purchaseOrders.refreshReceivedQuantity(purchaseOrderId, actor);
        assertEquals(0, new BigDecimal("6").compareTo(
                purchaseOrders.listDetails(purchaseOrderId).get(0).getReceivedQuantity()));

        // 已确认入库后不允许撤回采购订单。
        BusinessException refused = assertThrows(BusinessException.class,
                () -> purchaseOrders.withdrawPurchaseOrder(purchaseOrderId, actor, "测试撤回"));
        assertTrue(refused.getMessage().contains("不能撤回"));
    }

    @Test
    void purchaseOrderRejectsSameMaterialTwice() {
        Long actor = actor();
        MfgPurchaseOrder order = new MfgPurchaseOrder();
        order.setSupplierId(1L);
        order.setWarehouseId(1L);
        order.setOrderDate(LocalDate.now());
        MfgPurchaseOrderDetail first = new MfgPurchaseOrderDetail();
        first.setMaterialId(2L);
        first.setOrderQuantity(new BigDecimal("1"));
        MfgPurchaseOrderDetail second = new MfgPurchaseOrderDetail();
        second.setMaterialId(2L);
        second.setOrderQuantity(new BigDecimal("2"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> purchaseOrders.savePurchaseOrderFull(order, List.of(first, second), actor));
        assertTrue(error.getMessage().contains("同一个物料"));
    }

    /**
     * 取一个可用操作人。
     *
     * <p>不写死 admin：从 schema.sql 加 lamp_* 迁移全新装出来的库没有 admin 账号（那是企业基线
     * 灌进去的），只有 lamp_demo_seed_v1.sql 建的演示账号。这里取第一个启用的账号，
     * 既能在企业基线上跑，也能在干净的台灯库上跑。</p>
     */
    private Long actor() {
        return jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE status='ENABLE' AND deleted_flag=0 "
                        + "ORDER BY user_id LIMIT 1", Long.class);
    }

    private MfgSalesOrder salesOrder(Long customerId, LocalDate deliveryDate) {
        MfgSalesOrder order = new MfgSalesOrder();
        order.setCustomerId(customerId);
        order.setOrderDate(LocalDate.now());
        order.setDeliveryDate(deliveryDate);
        return order;
    }

    private MfgSalesOrderDetail detail(Long productId, String quantity) {
        MfgSalesOrderDetail detail = new MfgSalesOrderDetail();
        detail.setProductId(productId);
        detail.setOrderQuantity(new BigDecimal(quantity));
        return detail;
    }

    private Long insertSalesOutbound(String outboundNo, Long productionOrderId, int deletedFlag) {
        jdbc.update("INSERT INTO wms_outbound_order (outbound_no, outbound_type, warehouse_id, audit_status, "
                        + "source_production_order_id, version, created_by, created_time, updated_by, updated_time, "
                        + "deleted_flag) VALUES (?, 'SALE', 4, ?, ?, 0, 1, NOW(), 1, NOW(), ?)",
                outboundNo, BusinessStatus.Document.APPROVED, productionOrderId, deletedFlag);
        return jdbc.queryForObject("SELECT outbound_id FROM wms_outbound_order WHERE outbound_no = ?",
                Long.class, outboundNo);
    }

    private void insertOutboundDetail(Long outboundId, Long productId, String quantity) {
        jdbc.update("INSERT INTO wms_outbound_detail (outbound_id, material_id, outbound_quantity, version, "
                        + "created_by, created_time, updated_by, updated_time, deleted_flag) "
                        + "VALUES (?, ?, ?, 0, 1, NOW(), 1, NOW(), 0)",
                outboundId, productId, new BigDecimal(quantity));
    }
}
