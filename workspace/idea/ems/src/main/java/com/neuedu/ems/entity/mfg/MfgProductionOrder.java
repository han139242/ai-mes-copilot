package com.neuedu.ems.entity.mfg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * mfg_production_order 生产订单表
 * 用于管理生产订单信息，包括订单编号、客户、产品、订单数量、交付日期和状态等
 * 来源类型：ERP同步、手工创建
 * 订单状态：新建、已排产、生产中、已完成、已关闭
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_production_order")
public class MfgProductionOrder {

    @TableId(value = "production_order_id", type = IdType.AUTO)
    private Long productionOrderId;
    private String productionOrderNo;
    private Long customerId;
    private Long productId;
    private BigDecimal orderQuantity;
    private LocalDate deliveryDate;
    private String sourceType;
    /** 来源销售订单 ID，销售订单确认后生成的生产订单会回填，便于反向追溯客户需求。 */
    private Long sourceSalesOrderId;
    /** 来源销售订单明细 ID，一行销售明细对应一张生产订单。 */
    private Long sourceSalesOrderDetailId;
    private String status;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
