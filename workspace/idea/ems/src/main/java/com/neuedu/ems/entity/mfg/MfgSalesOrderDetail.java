package com.neuedu.ems.entity.mfg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * mfg_sales_order_detail 销售订单明细表。
 *
 * <p>一行一个产品。delivered_quantity 由销售出库流水汇总回写，
 * 用来回答「这张销售单发了多少、还剩多少」。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_sales_order_detail")
public class MfgSalesOrderDetail {

    @TableId(value = "detail_id", type = IdType.AUTO)
    private Long detailId;
    private Long salesOrderId;
    private Long productId;
    private BigDecimal orderQuantity;
    private BigDecimal deliveredQuantity;
    private BigDecimal unitPrice;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
