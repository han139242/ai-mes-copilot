package com.neuedu.ems.service.wms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.vo.wms.WmsSalesOutboundView;

/**
 * 销售发货出库 Service 接口。
 */
public interface WmsSalesOutboundService extends IService<WmsOutboundOrder> {

    /**
     * 根据生产订单生成销售出库单，按成品仓可用库存 FIFO 预分配批次。
     * @param productionOrderId 生产订单ID
     * @param customerId 经销商客户ID
     * @param warehouseId 成品仓仓库ID
     * @param locationId 默认发货库位ID，可为空，为空时按库存实际库位分配
     * @param remark 备注
     * @param operatorId 操作人ID
     * @return 生成的销售出库单
     */
    WmsOutboundOrder generateSalesOutbound(Long productionOrderId, Long customerId, Long warehouseId,
                                           Long locationId, String remark, Long operatorId);

    /**
     * 分页查询销售出库单，返回客户名称。
     * @param current 当前页码
     * @param size 每页记录数
     * @param customerId 客户ID，可选
     * @param auditStatus 审核状态，可选
     * @return 销售出库单分页结果
     */
    Page<WmsSalesOutboundView> pageSalesOutbound(Integer current, Integer size, Long customerId, String auditStatus);

    /**
     * 确认发货：校验出货检验结果，扣减成品仓可用库存并写入库存流水。
     * @param outboundId 出库单ID
     * @param operatorId 操作人ID
     * @param remark 备注
     * @return 是否成功
     */
    boolean confirmSalesOutbound(Long outboundId, Long operatorId, String remark);

    /**
     * 为销售出库单生成出货检验单并回写 inspection_id。
     * @param outboundId 出库单ID
     * @param operatorId 操作人ID
     * @return 出货检验单ID
     */
    Long createOutgoingInspection(Long outboundId, Long operatorId);
}
