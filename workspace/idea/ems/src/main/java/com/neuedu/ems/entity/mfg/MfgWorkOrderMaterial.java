package com.neuedu.ems.entity.mfg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * mfg_work_order_material
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_work_order_material")
public class MfgWorkOrderMaterial {

    @TableId(value = "requirement_id", type = IdType.AUTO)
    private Long requirementId;
    private Long workOrderId;
    private Long materialId;
    private BigDecimal requiredQuantity;
    private BigDecimal issuedQuantity;
    @TableField(exist = false)
    private BigDecimal pendingArrivalQuantity;
    @TableField(exist = false)
    private String arrivalStatus;

    public BigDecimal getPendingArrivalQuantity() {
        return (requiredQuantity == null ? BigDecimal.ZERO : requiredQuantity)
                .subtract(issuedQuantity == null ? BigDecimal.ZERO : issuedQuantity).max(BigDecimal.ZERO);
    }

    public String getArrivalStatus() {
        if (getPendingArrivalQuantity().signum() == 0) {
            return "READY";
        }
        boolean partiallyIssued = issuedQuantity != null && issuedQuantity.signum() > 0;
        return partiallyIssued ? "PARTIAL_READY" : "WAIT_READY";
    }
    /** 已承诺的库存预留数量；不等于已发料。 */
    private BigDecimal reservedQuantity;
    /** 尚未被有效供给覆盖的数量（已扣除预留与已发料）。 */
    private BigDecimal shortageQuantity;
    private String status;
    /** STOCK 库存领用，COMPONENT_WORK_ORDER 组件工单补产，PURCHASE 采购补货。 */
    private String supplyType;
    /** 该需求对应的组件工单。 */
    private Long sourceWorkOrderId;
    private String remark;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
