package com.neuedu.ems.entity.wms;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * wms_inventory
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("wms_inventory")
public class WmsInventory {

    @TableId(value = "inventory_id", type = IdType.AUTO)
    private Long inventoryId;
    private Long warehouseId;
    private Long locationId;
    private Long materialId;
    private Long batchId;
    /** 线边库按工单占用；0 表示公共库存。 */
    private Long workOrderId;
    private BigDecimal availableQuantity;
    private BigDecimal lockedQuantity;
    private BigDecimal inspectionQuantity;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
