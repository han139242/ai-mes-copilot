package com.neuedu.ems.vo.wms;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 可退明细清单展示对象。
 *
 * <p>用于「发起生产退料」时按工单一键列出全部可退来源明细，用户在该清单上删除或修改数量，
 * 不再逐条手工挑选来源领料明细。可退量的口径与
 * {@link com.neuedu.ems.service.impl.wms.WmsReturnOrderServiceImpl} 的
 * <code>validateAggregateReturnAvailability</code> 完全一致：
 * 已领（MATERIAL_ISSUE 已确认）× 已消耗（有效报工用料）× 已退（待确认或已确认的退料明细）。</p>
 */
@Data
public class WmsReturnableDetailView {

    /** 来源领料明细ID，退料明细的 outboundDetailId。 */
    private Long outboundDetailId;

    /** 来源领料单号。 */
    private String outboundNo;

    /** 来源领料单所属仓库ID。 */
    private Long warehouseId;

    /** 来源领料单所属仓库名称。 */
    private String warehouseName;

    /** 物料ID。 */
    private Long materialId;

    /** 物料编码。 */
    private String materialCode;

    /** 物料名称。 */
    private String materialName;

    /** 规格型号。 */
    private String specModel;

    /** 计量单位ID。 */
    private Long unitId;

    /** 计量单位名称。 */
    private String unitName;

    /** 物料批次ID。 */
    private Long batchId;

    /** 物料批次号。 */
    private String batchNo;

    /** 库位ID。 */
    private Long locationId;

    /** 库位名称。 */
    private String locationName;

    /** 来源领料明细的出库数量。 */
    private BigDecimal outboundQuantity;

    /** 该来源明细已发起退料的数量（待确认或已确认）。 */
    private BigDecimal returnedQuantity;

    /** 该来源明细最多还能退的数量，等于出库数量减已退数量。 */
    private BigDecimal sourceAvailableQuantity;

    /** 本工单该物料批次整体可退数量，等于已领减已消耗减退料。 */
    private BigDecimal batchAvailableQuantity;

    /** 建议退料数量，界面默认填入，取来源可退与批次可退的较小值。 */
    private BigDecimal availableQuantity;

    /** 该来源领料明细是否已转线边。 */
    private Boolean lineSide;
}
