package com.neuedu.ems.controller.wms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.dto.wms.WmsSalesOutboundGenerateRequest;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.wms.WmsSalesOutboundService;
import com.neuedu.ems.vo.wms.WmsSalesOutboundView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 销售发货出库控制器。
 */
@RestController
@RequestMapping("/api/wms/sales-outbound")
public class WmsSalesOutboundController {

    @Autowired
    private WmsSalesOutboundService wmsSalesOutboundService;

    /**
     * 根据生产订单生成销售出库单。
     * @param request 生成请求体，包含生产订单、客户、成品仓和库位
     * @param userId JWT 中的当前用户ID
     * @return 生成的销售出库单
     */
    @PostMapping("/generate")
    public ResultData generate(@RequestBody WmsSalesOutboundGenerateRequest request,
                               @RequestAttribute("userId") Long userId) {
        WmsOutboundOrder order = wmsSalesOutboundService.generateSalesOutbound(
                request.getProductionOrderId(), request.getCustomerId(), request.getWarehouseId(),
                request.getLocationId(), request.getRemark(), userId);
        return ResultData.success(ResultCode.SUCCESS, order);
    }

    /**
     * 分页查询销售出库单，返回时带客户名称。
     * @param current 当前页码，默认1
     * @param size 每页记录数，默认10
     * @param customerId 客户ID，可选
     * @param auditStatus 审核状态，可选
     * @return 销售出库单分页结果
     */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false) Long customerId,
                           @RequestParam(required = false) String auditStatus) {
        Page<WmsSalesOutboundView> result = wmsSalesOutboundService
                .pageSalesOutbound(current, size, customerId, auditStatus);
        return ResultData.success(ResultCode.SUCCESS, result);
    }

    /** 查看销售发货单详情，拒绝读取其他类型的出库单。 */
    @GetMapping("/{outboundId}")
    public ResultData get(@PathVariable("outboundId") Long outboundId) {
        WmsOutboundOrder order = wmsSalesOutboundService.getById(outboundId);
        if (order == null || !Integer.valueOf(0).equals(order.getDeletedFlag())
                || !"SALE".equals(order.getOutboundType())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "销售发货单不存在");
        }
        return ResultData.success(ResultCode.SUCCESS, order);
    }

    /**
     * 确认发货，校验出货检验结果后扣减成品仓库存并写入库存流水。
     * @param outboundId 出库单ID
     * @param userId JWT 中的当前用户ID
     * @param request 请求体，支持remark参数，可为空
     * @return 操作结果
     */
    @PutMapping("/{outboundId}/confirm")
    public ResultData confirm(@PathVariable("outboundId") Long outboundId,
                              @RequestAttribute("userId") Long userId,
                              @RequestBody(required = false) Map<String, Object> request) {
        boolean flag = wmsSalesOutboundService.confirmSalesOutbound(outboundId, userId, getString(request, "remark"));
        return flag ? ResultData.success(ResultCode.SUCCESS) : ResultData.fail(ResultCode.FAILED);
    }

    /**
     * 为销售出库单生成出货检验单。
     * @param outboundId 出库单ID
     * @param userId JWT 中的当前用户ID
     * @return 出货检验单ID
     */
    @PostMapping("/{outboundId}/inspection")
    public ResultData inspection(@PathVariable("outboundId") Long outboundId,
                                 @RequestAttribute("userId") Long userId) {
        Long inspectionId = wmsSalesOutboundService.createOutgoingInspection(outboundId, userId);
        return ResultData.success(ResultCode.SUCCESS, Map.of("inspectionId", inspectionId));
    }

    /**
     * 从请求体中读取String类型参数。
     * @param request 请求体Map
     * @param key 参数名
     * @return String类型参数值
     */
    private String getString(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? null : value.toString();
    }
}
