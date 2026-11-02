package com.neuedu.ems.controller.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrderDetail;
import com.neuedu.ems.mapper.mfg.MfgPurchaseOrderDetailMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采购订单明细查询控制器。
 *
 * <p>只读：明细随采购订单头一起保存（/api/mfg/purchase-order/full），
 * 单独开放查询是给前端子表和「这张单买了哪些料」用。</p>
 */
@RestController
@RequestMapping("/api/mfg/purchase-order-detail")
public class MfgPurchaseOrderDetailController {

    @Autowired
    private MfgPurchaseOrderDetailMapper detailMapper;

    /** 分页查询采购明细，可按采购订单、物料筛选。 */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false) Long purchaseOrderId,
                           @RequestParam(required = false) Long materialId) {
        QueryWrapper<MfgPurchaseOrderDetail> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (purchaseOrderId != null) {
            wrapper.eq("purchase_order_id", purchaseOrderId);
        }
        if (materialId != null) {
            wrapper.eq("material_id", materialId);
        }
        wrapper.orderByAsc("detail_id");
        Page<MfgPurchaseOrderDetail> result = detailMapper.selectPage(new Page<>(current, size), wrapper);
        return ResultData.success(ResultCode.SUCCESS, result);
    }

    /** 按采购订单查询全部明细，用于订单详情页子表。 */
    @GetMapping("/purchase-order/{purchaseOrderId}")
    public ResultData listByPurchaseOrder(@PathVariable Long purchaseOrderId) {
        return ResultData.success(ResultCode.SUCCESS, detailMapper.selectList(
                new QueryWrapper<MfgPurchaseOrderDetail>()
                        .eq("purchase_order_id", purchaseOrderId).eq("deleted_flag", 0)
                        .orderByAsc("detail_id")));
    }
}
