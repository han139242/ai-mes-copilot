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
 * mfg_supply_allocation
 *
 * <p>一条工单用料需求可以拆成多条供给分配：同一需求可能同时存在多条库存预留
 * （按仓库/库位/批次/余额行）和一条组件补产工单。这是对"单一 source_work_order_id
 * 承担完整分配"的替代。</p>
 *
 * <p>分配数量在成功后不再变化，预留/转移/消耗/释放各自累计，用于解释一条余额行
 * 的去向，也用于防止重复分配同一份库存。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_supply_allocation")
public class MfgSupplyAllocation {

    public static final String RESERVED = "RESERVED";
    public static final String TRANSFERRED = "TRANSFERRED";
    public static final String CONSUMED = "CONSUMED";
    public static final String RELEASED = "RELEASED";

    @TableId(value = "allocation_id", type = IdType.AUTO)
    private Long allocationId;
    /** 分配编号：需求+库存余额唯一，重复请求复用同一行。 */
    private String allocationNo;
    private Long requirementId;
    private Long workOrderId;
    private Long materialId;
    private Long warehouseId;
    private Long locationId;
    private Long batchId;
    private Long inventoryId;
    /** 分配数量，成功后不再变化。 */
    private BigDecimal allocatedQuantity;
    /** 仍处于预留状态的数量。 */
    private BigDecimal reservedQuantity;
    /** 已转为线边工单占用的数量。 */
    private BigDecimal transferredQuantity;
    /** 已实际消耗的数量。 */
    private BigDecimal consumedQuantity;
    /** 已释放回公共可用量的数量。 */
    private BigDecimal releasedQuantity;
    /** RESERVED:预留中，TRANSFERRED:已转线边，CONSUMED:已消耗，RELEASED:已释放。 */
    private String status;
    /** 预留业务幂等键：需求+请求键。 */
    private String requestKey;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
