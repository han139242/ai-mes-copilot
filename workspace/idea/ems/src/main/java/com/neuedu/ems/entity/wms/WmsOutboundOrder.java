package com.neuedu.ems.entity.wms;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * wms_outbound_order
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("wms_outbound_order")
public class WmsOutboundOrder {

    @TableId(value = "outbound_id", type = IdType.AUTO)
    private Long outboundId;
    private String outboundNo;
    private String outboundType;
    private Long warehouseId;
    private Long workOrderId;
    private Long applicantId;
    /** 销售出库的经销商客户。 */
    private Long customerId;
    /** 销售出库来源生产订单，用于全程追溯。 */
    private Long sourceProductionOrderId;
    /** 销售发运前出货检验单。 */
    private Long inspectionId;
    private String auditStatus;
    private LocalDateTime outboundTime;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
