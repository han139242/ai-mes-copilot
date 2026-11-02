package com.neuedu.ems.controller.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.mfg.MfgSalesOrderDetail;
import com.neuedu.ems.mapper.mfg.MfgSalesOrderDetailMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 销售订单明细查询控制器。
 *
 * <p>只读：明细随销售订单头一起保存（/api/mfg/sales-order/full），
 * 单独开放查询是给前端子表和「这张单要了哪些产品」用。</p>
 */
@RestController
@RequestMapping("/api/mfg/sales-order-detail")
public class MfgSalesOrderDetailController {

    @Autowired
    private MfgSalesOrderDetailMapper detailMapper;

    /** 分页查询销售明细，可按销售订单、产品筛选。 */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false) Long salesOrderId,
                           @RequestParam(required = false) Long productId) {
        QueryWrapper<MfgSalesOrderDetail> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (salesOrderId != null) {
            wrapper.eq("sales_order_id", salesOrderId);
        }
        if (productId != null) {
            wrapper.eq("product_id", productId);
        }
        wrapper.orderByAsc("detail_id");
        Page<MfgSalesOrderDetail> result = detailMapper.selectPage(new Page<>(current, size), wrapper);
        return ResultData.success(ResultCode.SUCCESS, result);
    }

    /** 按销售订单查询全部明细，用于订单详情页子表。 */
    @GetMapping("/sales-order/{salesOrderId}")
    public ResultData listBySalesOrder(@PathVariable Long salesOrderId) {
        return ResultData.success(ResultCode.SUCCESS, detailMapper.selectList(
                new QueryWrapper<MfgSalesOrderDetail>()
                        .eq("sales_order_id", salesOrderId).eq("deleted_flag", 0)
                        .orderByAsc("detail_id")));
    }
}
