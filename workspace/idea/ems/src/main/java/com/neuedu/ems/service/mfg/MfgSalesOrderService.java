package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.entity.mfg.MfgSalesOrder;
import com.neuedu.ems.entity.mfg.MfgSalesOrderDetail;

import java.util.List;

/**
 * 销售订单 Service 接口。
 *
 * <p>销售订单是客户需求在系统里的落点。草稿可改；确认后按明细逐行生成生产订单，
 * 生产订单带上 sourceSalesOrderId / sourceSalesOrderDetailId 反查来源，
 * 这样「客户要了多少台」和「排产了多少台」能对上。</p>
 */
public interface MfgSalesOrderService extends IService<MfgSalesOrder> {

    /**
     * 分页查询销售订单。
     *
     * @param current    当前页码
     * @param size       每页记录数
     * @param keyword    销售订单号关键字
     * @param status     订单状态
     * @param customerId 客户 ID
     * @return 分页结果
     */
    Page<MfgSalesOrder> pageSalesOrders(Integer current, Integer size, String keyword, String status, Long customerId);

    /**
     * 查询销售订单列表，用于导出。
     */
    List<MfgSalesOrder> listSalesOrders(String keyword, String status, Long customerId);

    /**
     * 按 ID 查询未删除的销售订单。
     */
    MfgSalesOrder getSalesOrderById(Long salesOrderId);

    /**
     * 新增销售订单及明细，订单号自动生成，状态固定为草稿。
     *
     * @param salesOrder 销售订单头
     * @param details    明细列表
     * @param operatorId 操作人
     * @return 销售订单 ID
     */
    Long saveSalesOrderFull(MfgSalesOrder salesOrder, List<MfgSalesOrderDetail> details, Long operatorId);

    /**
     * 更新销售订单及明细，只有草稿可以改。
     */
    boolean updateSalesOrderFull(MfgSalesOrder salesOrder, List<MfgSalesOrderDetail> details, Long operatorId);

    /**
     * 软删除销售订单，只有草稿可以删。
     */
    boolean removeSalesOrderById(Long salesOrderId, Long operatorId);

    /**
     * 确认销售订单：草稿转已确认，按明细逐行生成生产订单。
     *
     * @param salesOrderId 销售订单 ID
     * @param operatorId   操作人
     * @param remark       备注
     * @return 生成的生产订单列表
     */
    List<Long> confirmSalesOrder(Long salesOrderId, Long operatorId, String remark);

    /**
     * 撤回销售订单：已确认转草稿，并撤回由它生成、尚未执行的生产订单。
     */
    void withdrawSalesOrder(Long salesOrderId, Long operatorId, String remark);

    /**
     * 查询指定销售订单的全部明细。
     */
    List<MfgSalesOrderDetail> listDetails(Long salesOrderId);

    /**
     * 按销售订单汇总实际已发货数量，回写明细的 deliveredQuantity。
     * 由销售出库确认后调用，避免两处口径打架。
     */
    void refreshDeliveredQuantity(Long salesOrderId, Long operatorId);
}
