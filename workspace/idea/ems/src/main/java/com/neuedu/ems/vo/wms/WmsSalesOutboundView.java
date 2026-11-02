package com.neuedu.ems.vo.wms;

import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 销售出库单展示对象，在出库单基础上补充客户名称。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WmsSalesOutboundView extends WmsOutboundOrder {

    /** 客户名称，来自 mdm_customer 联表补充。 */
    private String customerName;
}
