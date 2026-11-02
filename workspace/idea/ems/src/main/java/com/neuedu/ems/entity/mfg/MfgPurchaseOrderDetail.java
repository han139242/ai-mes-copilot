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
 * mfg_purchase_order_detail 采购订单明细表。
 *
 * <p>一行一个物料。received_quantity 按采购入库流水汇总，
 * 用来回答「这张采购单到货了多少、还差多少」。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_purchase_order_detail")
public class MfgPurchaseOrderDetail {

    @TableId(value = "detail_id", type = IdType.AUTO)
    private Long detailId;
    private Long purchaseOrderId;
    private Long materialId;
    private BigDecimal orderQuantity;
    private BigDecimal receivedQuantity;
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
