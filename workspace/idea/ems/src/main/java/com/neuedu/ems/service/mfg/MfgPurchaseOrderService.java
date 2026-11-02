package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrder;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrderDetail;

import java.util.List;

/**
 * 采购订单 Service 接口。
 *
 * <p>采购申请和采购订单合成一张单据：草稿即申请，下达后自动生成采购入库单，
 * 由仓储侧走「到货 → 来料检验 → 确认入库」，这样主流程链的采购环节才有单据可查。</p>
 */
public interface MfgPurchaseOrderService extends IService<MfgPurchaseOrder> {

    /**
     * 分页查询采购订单。
     */
    Page<MfgPurchaseOrder> pagePurchaseOrders(Integer current, Integer size, String keyword, String status,
                                              Long supplierId);

    /**
     * 查询采购订单列表，用于导出。
     */
    List<MfgPurchaseOrder> listPurchaseOrders(String keyword, String status, Long supplierId);

    /**
     * 按 ID 查询未删除的采购订单。
     */
    MfgPurchaseOrder getPurchaseOrderById(Long purchaseOrderId);

    /**
     * 新增采购订单及明细，订单号自动生成，状态固定为草稿。
     */
    Long savePurchaseOrderFull(MfgPurchaseOrder purchaseOrder, List<MfgPurchaseOrderDetail> details,
                               Long operatorId);

    /**
     * 更新采购订单及明细，只有草稿可以改。
     */
    boolean updatePurchaseOrderFull(MfgPurchaseOrder purchaseOrder, List<MfgPurchaseOrderDetail> details,
                                    Long operatorId);

    /**
     * 软删除采购订单，只有草稿可以删。
     */
    boolean removePurchaseOrderById(Long purchaseOrderId, Long operatorId);

    /**
     * 下达采购订单：草稿转已下达，并生成一张采购入库单供仓库收货。
     *
     * @return 生成的采购入库单 ID
     */
    Long releasePurchaseOrder(Long purchaseOrderId, Long operatorId, String remark);

    /**
     * 撤回采购订单：已下达转草稿，并作废由它生成、尚未确认的采购入库单。
     */
    void withdrawPurchaseOrder(Long purchaseOrderId, Long operatorId, String remark);

    /**
     * 查询指定采购订单的全部明细。
     */
    List<MfgPurchaseOrderDetail> listDetails(Long purchaseOrderId);

    /**
     * 按采购入库流水回写明细的已收数量。
     */
    void refreshReceivedQuantity(Long purchaseOrderId, Long operatorId);
}
