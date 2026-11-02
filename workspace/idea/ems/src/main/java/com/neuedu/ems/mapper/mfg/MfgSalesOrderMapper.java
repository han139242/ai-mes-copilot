package com.neuedu.ems.mapper.mfg;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mfg.MfgSalesOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/** 销售订单 Mapper。 */
@Mapper
public interface MfgSalesOrderMapper extends BaseMapper<MfgSalesOrder> {

    /**
     * 汇总一条销售明细对应的实际已发货数量。
     *
     * <p>口径：已确认销售出库单（outbound_type='SALE'、audit_status='APPROVED'）未删除明细的数量之和，
     * 按「销售明细 → 生产订单 → 销售出库单」反查，保证发货记录作废后数量会跟着回落。</p>
     *
     * @param detailId 销售订单明细 ID
     * @return 已发货数量，无记录时返回 0
     */
    @Select("""
            SELECT COALESCE(SUM(d.outbound_quantity), 0)
            FROM wms_outbound_detail d
            JOIN wms_outbound_order o ON o.outbound_id = d.outbound_id
                AND o.deleted_flag = 0 AND o.outbound_type = 'SALE' AND o.audit_status = 'APPROVED'
            JOIN mfg_production_order p ON p.production_order_id = o.source_production_order_id
                AND p.deleted_flag = 0
            WHERE d.deleted_flag = 0 AND p.source_sales_order_detail_id = #{detailId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    BigDecimal deliveredQuantityOfDetail(@Param("detailId") Long detailId);
}
