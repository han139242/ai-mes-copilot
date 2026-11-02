package com.neuedu.ems.controller.mfg;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrder;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrderDetail;
import com.neuedu.ems.service.mfg.MfgPurchaseOrderService;
import com.neuedu.ems.util.ExcelExportUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 采购订单控制器。
 *
 * <p>草稿即采购申请；「下达」后自动生成一张采购入库单，交给仓储走收货、
 * 来料检验和确认入库，这样主流程链的采购环节才有单据可追溯。</p>
 */
@RestController
@RequestMapping("/api/mfg/purchase-order")
public class MfgPurchaseOrderController {

    @Autowired
    private MfgPurchaseOrderService purchaseOrderService;

    @Autowired
    private ExcelExportUtil excelExportUtil;

    /** 分页查询采购订单，支持订单号、状态、供应商筛选。 */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Long supplierId) {
        Page<MfgPurchaseOrder> result = purchaseOrderService.pagePurchaseOrders(current, size, keyword, status,
                supplierId);
        return ResultData.success(ResultCode.SUCCESS, result);
    }

    /** 按 ID 查询采购订单详情。 */
    @GetMapping("/{purchaseOrderId}")
    public ResultData get(@PathVariable Long purchaseOrderId) {
        return ResultData.success(ResultCode.SUCCESS, purchaseOrderService.getPurchaseOrderById(purchaseOrderId));
    }

    /** 查询某张采购订单的全部明细。 */
    @GetMapping("/{purchaseOrderId}/details")
    public ResultData details(@PathVariable Long purchaseOrderId) {
        return ResultData.success(ResultCode.SUCCESS, purchaseOrderService.listDetails(purchaseOrderId));
    }

    /** 新增采购订单及明细。 */
    @PostMapping("/full")
    public ResultData saveFull(@RequestBody PurchaseOrderFullRequest request,
                               @RequestAttribute("userId") Long userId) {
        Long id = purchaseOrderService.savePurchaseOrderFull(request.getPurchaseOrder(), request.getDetails(), userId);
        return ResultData.success(ResultCode.SUCCESS, id);
    }

    /** 更新采购订单及明细，只有草稿可以改。 */
    @PutMapping("/full")
    public ResultData updateFull(@RequestBody PurchaseOrderFullRequest request,
                                 @RequestAttribute("userId") Long userId) {
        boolean flag = purchaseOrderService.updatePurchaseOrderFull(request.getPurchaseOrder(),
                request.getDetails(), userId);
        return flag ? ResultData.success(ResultCode.SUCCESS) : ResultData.fail(ResultCode.FAILED);
    }

    /** 删除采购订单（软删除），只有草稿可以删。 */
    @DeleteMapping("/{purchaseOrderId}")
    public ResultData delete(@PathVariable Long purchaseOrderId, @RequestAttribute("userId") Long userId) {
        boolean flag = purchaseOrderService.removePurchaseOrderById(purchaseOrderId, userId);
        return flag ? ResultData.success(ResultCode.SUCCESS) : ResultData.fail(ResultCode.FAILED);
    }

    /** 下达采购订单：草稿转已下达，并生成采购入库单。 */
    @PutMapping("/{purchaseOrderId}/release")
    public ResultData release(@PathVariable Long purchaseOrderId,
                              @RequestAttribute("userId") Long userId,
                              @RequestBody(required = false) RemarkRequest request) {
        Long inboundId = purchaseOrderService.releasePurchaseOrder(
                purchaseOrderId, userId, request == null ? null : request.getRemark());
        return ResultData.success(ResultCode.SUCCESS, inboundId);
    }

    /** 撤回采购订单：已下达转草稿，同步作废尚未确认的采购入库单。 */
    @PutMapping("/{purchaseOrderId}/withdraw")
    public ResultData withdraw(@PathVariable Long purchaseOrderId,
                               @RequestAttribute("userId") Long userId,
                               @RequestBody(required = false) RemarkRequest request) {
        purchaseOrderService.withdrawPurchaseOrder(purchaseOrderId, userId,
                request == null ? null : request.getRemark());
        return ResultData.success(ResultCode.SUCCESS);
    }

    /** 导出采购订单列表。 */
    @GetMapping("/excel")
    public void excel(HttpServletResponse response,
                      @RequestParam(required = false) String keyword,
                      @RequestParam(required = false) String status,
                      @RequestParam(required = false) Long supplierId) {
        List<MfgPurchaseOrder> orders = purchaseOrderService.listPurchaseOrders(keyword, status, supplierId);
        excelExportUtil.exportExcel(response, orders, MfgPurchaseOrder.class, "采购订单列表", "采购订单列表");
    }

    @lombok.Data
    public static class PurchaseOrderFullRequest {
        private MfgPurchaseOrder purchaseOrder;
        private List<MfgPurchaseOrderDetail> details;
    }

    @lombok.Data
    public static class RemarkRequest {
        private String remark;
    }
}
