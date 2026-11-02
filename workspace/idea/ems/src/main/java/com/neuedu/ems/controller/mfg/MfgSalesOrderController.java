package com.neuedu.ems.controller.mfg;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.mfg.MfgSalesOrder;
import com.neuedu.ems.entity.mfg.MfgSalesOrderDetail;
import com.neuedu.ems.service.mfg.MfgSalesOrderService;
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
 * 销售订单控制器。
 *
 * <p>提供销售订单的增删改查，以及「确认生成生产订单」「撤回」两个业务动作。
 * 确认后按明细逐行生成生产订单，生产订单可通过 sourceSalesOrderId 反查来源。</p>
 */
@RestController
@RequestMapping("/api/mfg/sales-order")
public class MfgSalesOrderController {

    @Autowired
    private MfgSalesOrderService salesOrderService;

    @Autowired
    private ExcelExportUtil excelExportUtil;

    /** 分页查询销售订单，支持订单号、状态、客户筛选。 */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Long customerId) {
        Page<MfgSalesOrder> result = salesOrderService.pageSalesOrders(current, size, keyword, status, customerId);
        return ResultData.success(ResultCode.SUCCESS, result);
    }

    /** 按 ID 查询销售订单详情。 */
    @GetMapping("/{salesOrderId}")
    public ResultData get(@PathVariable Long salesOrderId) {
        return ResultData.success(ResultCode.SUCCESS, salesOrderService.getSalesOrderById(salesOrderId));
    }

    /** 查询某张销售订单的全部明细。 */
    @GetMapping("/{salesOrderId}/details")
    public ResultData details(@PathVariable Long salesOrderId) {
        return ResultData.success(ResultCode.SUCCESS, salesOrderService.listDetails(salesOrderId));
    }

    /** 新增销售订单及明细。 */
    @PostMapping("/full")
    public ResultData saveFull(@RequestBody SalesOrderFullRequest request,
                               @RequestAttribute("userId") Long userId) {
        Long id = salesOrderService.saveSalesOrderFull(request.getSalesOrder(), request.getDetails(), userId);
        return ResultData.success(ResultCode.SUCCESS, id);
    }

    /** 更新销售订单及明细，只有草稿可以改。 */
    @PutMapping("/full")
    public ResultData updateFull(@RequestBody SalesOrderFullRequest request,
                                 @RequestAttribute("userId") Long userId) {
        boolean flag = salesOrderService.updateSalesOrderFull(request.getSalesOrder(), request.getDetails(), userId);
        return flag ? ResultData.success(ResultCode.SUCCESS) : ResultData.fail(ResultCode.FAILED);
    }

    /** 删除销售订单（软删除），只有草稿可以删。 */
    @DeleteMapping("/{salesOrderId}")
    public ResultData delete(@PathVariable Long salesOrderId, @RequestAttribute("userId") Long userId) {
        boolean flag = salesOrderService.removeSalesOrderById(salesOrderId, userId);
        return flag ? ResultData.success(ResultCode.SUCCESS) : ResultData.fail(ResultCode.FAILED);
    }

    /** 确认销售订单：草稿转已确认，按明细逐行生成生产订单。 */
    @PutMapping("/{salesOrderId}/confirm")
    public ResultData confirm(@PathVariable Long salesOrderId,
                              @RequestAttribute("userId") Long userId,
                              @RequestBody(required = false) RemarkRequest request) {
        List<Long> productionOrderIds = salesOrderService.confirmSalesOrder(
                salesOrderId, userId, request == null ? null : request.getRemark());
        return ResultData.success(ResultCode.SUCCESS, productionOrderIds);
    }

    /** 撤回销售订单：已确认转草稿，同步撤回由它生成且尚未执行的生产订单。 */
    @PutMapping("/{salesOrderId}/withdraw")
    public ResultData withdraw(@PathVariable Long salesOrderId,
                               @RequestAttribute("userId") Long userId,
                               @RequestBody(required = false) RemarkRequest request) {
        salesOrderService.withdrawSalesOrder(salesOrderId, userId, request == null ? null : request.getRemark());
        return ResultData.success(ResultCode.SUCCESS);
    }

    /** 导出销售订单列表。 */
    @GetMapping("/excel")
    public void excel(HttpServletResponse response,
                      @RequestParam(required = false) String keyword,
                      @RequestParam(required = false) String status,
                      @RequestParam(required = false) Long customerId) {
        List<MfgSalesOrder> orders = salesOrderService.listSalesOrders(keyword, status, customerId);
        excelExportUtil.exportExcel(response, orders, MfgSalesOrder.class, "销售订单列表", "销售订单列表");
    }

    @lombok.Data
    public static class SalesOrderFullRequest {
        private MfgSalesOrder salesOrder;
        private List<MfgSalesOrderDetail> details;
    }

    @lombok.Data
    public static class RemarkRequest {
        private String remark;
    }
}
