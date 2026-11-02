package com.neuedu.ems.entity.mfg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * mfg_work_order 生产工单表
 * 用于管理生产工单信息，包括工单编号、生产计划、产品、BOM、工艺路线、计划数量、完成数量和状态等
 * 工单状态：新建、已审核、已下达、生产中、已完成、已关闭
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_work_order")
public class MfgWorkOrder {

    @TableId(value = "work_order_id", type = IdType.AUTO)
    private Long workOrderId;
    private String workOrderNo;
    private Long planId;
    private Long productId;
    private Long bomId;
    private Long routeId;
    /** 工单计划开始时间：根工单取自生产计划，组件工单是自己在同一条产线上的窗口。 */
    private LocalDateTime plannedStartTime;
    /** 工单计划结束时间：根工单取自生产计划，组件工单是自己在同一条产线上的窗口。 */
    private LocalDateTime plannedEndTime;
    private BigDecimal plannedQuantity;
    private BigDecimal completedQuantity;
    private String status;
    /** ROOT 整机根工单，COMPONENT 组件补产工单。 */
    private String workOrderType;
    /** 组件工单指向父工单。 */
    private Long parentWorkOrderId;
    /** 生产树汇总口径的根工单。 */
    private Long rootWorkOrderId;
    /** 组件工单来源的父工单用料需求行。 */
    private Long sourceRequirementId;
    /** 下达时冻结的 BOM、路线、参数与检验依据快照。 */
    private String snapshotJson;
    /** 快照摘要哈希。 */
    private String snapshotHash;
    /** 快照冻结时间。 */
    private LocalDateTime snapshotTime;
    private String remark;
    /**
     * 生成命令幂等键，仅作为入参使用，不落库。
     * 同键同摘要重放返回原结果；同键不同摘要必须拒绝，不能静默复用。
     */
    @TableField(exist = false)
    private String requestKey;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
