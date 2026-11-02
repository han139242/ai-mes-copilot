package com.neuedu.ems.entity.mfg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * mfg_task
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_task")
public class MfgTask {

    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;
    private String taskNo;
    private Long dispatchId;
    private Long workOrderId;
    private Long operationId;
    private Long stationId;
    private BigDecimal plannedQuantity;
    private BigDecimal completedQuantity;
    private BigDecimal defectQuantity;
    private String taskType;
    private Long sourceTaskId;
    private Long sourceNcrId;
    /** 本次工序报工不良处置的来源任务，区分不同工序的多次补产。 */
    private Long defectResolutionTaskId;
    @TableField(exist = false)
    private BigDecimal reportedQualifiedQuantity;
    @TableField(exist = false)
    private BigDecimal reportedDefectQuantity;
    @TableField(exist = false)
    private Boolean assignedToCurrentUser;
    @TableField(exist = false)
    private String fulfillmentStatus;
    @TableField(exist = false)
    private BigDecimal approvedQualifiedQuantity;
    @TableField(exist = false)
    private BigDecimal recoveredQualifiedQuantity;
    @TableField(exist = false)
    private BigDecimal totalQualifiedQuantity;
    @TableField(exist = false)
    private BigDecimal remainingQualifiedQuantity;
    @TableField(exist = false)
    private BigDecimal pendingReportQuantity;
    /** 所属工单类型（ROOT/COMPONENT），仅供前端按工单类型过滤领料仓库。 */
    @TableField(exist = false)
    private String workOrderType;
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
