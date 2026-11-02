package com.neuedu.ems.dto.wms;

import lombok.Data;

/**
 * 销售出库生成请求体。
 */
@Data
public class WmsSalesOutboundGenerateRequest {

    /** 生产订单ID。 */
    private Long productionOrderId;
    /** 经销商客户ID。 */
    private Long customerId;
    /** 成品仓仓库ID。 */
    private Long warehouseId;
    /** 默认发货库位ID。 */
    private Long locationId;
    /** 备注。 */
    private String remark;
}
