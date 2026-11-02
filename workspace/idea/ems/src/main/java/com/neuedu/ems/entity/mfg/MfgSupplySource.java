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
 * 需求供给来源明细：一条工单用料需求可以同时由多张组件补产单供给。
 *
 * <p>此前只用 {@code mfg_work_order_material.source_work_order_id} 表达供给来源，
 * 一条需求只能指向一张组件工单；当组件补产量在有效日历产能窗口内排不下、
 * 必须拆成多张补产单时，单一字段无法保留拆分关系，故单独建表。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_supply_source")
public class MfgSupplySource {

    /** 已计划，尚未下达。 */
    public static final String PLANNED = "PLANNED";
    /** 对应组件工单已下达。 */
    public static final String RELEASED = "RELEASED";
    /** 对应组件工单已关闭或已撤回。 */
    public static final String CLOSED = "CLOSED";

    @TableId(value = "source_id", type = IdType.AUTO)
    private Long sourceId;
    private String sourceNo;
    private Long requirementId;
    private Long workOrderId;
    private Long planId;
    private Long materialId;
    private String supplyType;
    private BigDecimal plannedQuantity;
    private Integer sequenceNo;
    private String status;
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
