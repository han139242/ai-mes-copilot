package com.neuedu.ems.mapper.mfg;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 采购订单 Mapper。 */
@Mapper
public interface MfgPurchaseOrderMapper extends BaseMapper<MfgPurchaseOrder> {

    /**
     * 按物料汇总某张采购订单已确认入库的数量。
     *
     * <p>口径：采购入库单（inbound_type='PURCHASE'，来源单据为采购订单）中
     * audit_status='APPROVED' 的明细数量之和，按物料分组。
     * 未确认的入库单不计入，避免「货到了但还没入库」被算成已收。</p>
     *
     * @param purchaseOrderId 采购订单 ID
     * @return 每个物料一行：materialId、receivedQuantity
     */
    @Select("""
            SELECT d.material_id AS materialId,
                   COALESCE(SUM(d.inbound_quantity), 0) AS receivedQuantity
            FROM wms_inbound_detail d
            JOIN wms_inbound_order o ON o.inbound_id = d.inbound_id
                AND o.deleted_flag = 0 AND o.inbound_type = 'PURCHASE'
                AND o.source_order_type = 'PURCHASE_ORDER'
                AND o.source_order_id = #{purchaseOrderId}
                AND o.audit_status = 'APPROVED'
            WHERE d.deleted_flag = 0
            GROUP BY d.material_id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<Map<String, Object>> receivedQuantityByMaterial(@Param("purchaseOrderId") Long purchaseOrderId);
}
