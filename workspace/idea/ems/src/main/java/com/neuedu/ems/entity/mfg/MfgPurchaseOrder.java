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
 * mfg_purchase_order 采购订单表。
 *
 * <p>采购申请与采购订单合成一张单据：草稿时相当于申请，下达后生成采购入库单，
 * 由仓储侧走「到货 → 来料检验 → 确认入库」。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_purchase_order")
public class MfgPurchaseOrder {

    @TableId(value = "purchase_order_id", type = IdType.AUTO)
    private Long purchaseOrderId;
    private String purchaseOrderNo;
    private Long supplierId;
    /** 收货仓库，下达生成入库单时必填。 */
    private Long warehouseId;
    private LocalDate orderDate;
    private LocalDate expectedDate;
    /** DRAFT 草稿、RELEASED 已下达、CLOSED 已关闭、WITHDRAWN 已撤回。 */
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
