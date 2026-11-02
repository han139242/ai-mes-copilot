package com.neuedu.ems.entity.mfg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * mfg_sales_order 销售订单表。
 *
 * <p>客户需求在系统里的落点。确认后按明细逐行生成生产订单，
 * 生产订单通过 source_sales_order_id / source_sales_order_detail_id 反查来源。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_sales_order")
public class MfgSalesOrder {

    @TableId(value = "sales_order_id", type = IdType.AUTO)
    private Long salesOrderId;
    private String salesOrderNo;
    private Long customerId;
    private LocalDate orderDate;
    private LocalDate deliveryDate;
    /** DRAFT 草稿、SUBMITTED 已确认、CLOSED 已关闭、WITHDRAWN 已撤回。 */
    private String status;
    private BigDecimal totalQuantity;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
