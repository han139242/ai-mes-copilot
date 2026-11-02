package com.neuedu.ems.controller.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.mfg.MfgSupplyAllocation;
import com.neuedu.ems.mapper.mfg.MfgSupplyAllocationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存预留分配明细查询控制器。
 *
 * <p>只读接口：预留由生成工单命令在事务内按 FIFO 写入，转线边由领料移库命令消耗，
 * 释放由撤回命令退回；不提供单独增删改，避免绕过库存余额一致性校验手工改预留。</p>
 */
@RestController
@RequestMapping("/api/mfg/supply-allocation")
public class MfgSupplyAllocationController {

    @Autowired
    private MfgSupplyAllocationMapper allocations;

    /** 分页查询预留分配明细，可按需求、工单、物料、状态筛选。 */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false) Long requirementId,
                           @RequestParam(required = false) Long workOrderId,
                           @RequestParam(required = false) Long materialId,
                           @RequestParam(required = false) String status) {
        QueryWrapper<MfgSupplyAllocation> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (requirementId != null) {
            wrapper.eq("requirement_id", requirementId);
        }
        if (workOrderId != null) {
            wrapper.eq("work_order_id", workOrderId);
        }
        if (materialId != null) {
            wrapper.eq("material_id", materialId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        wrapper.orderByAsc("material_id").orderByAsc("allocation_id");
        Page<MfgSupplyAllocation> result = allocations.selectPage(new Page<>(current, size), wrapper);
        return ResultData.success(ResultCode.SUCCESS, result);
    }

    /** 按工单查询全部预留分配明细，用于核对一张工单占了哪些库存余额行。 */
    @GetMapping("/work-order/{workOrderId}")
    public ResultData listByWorkOrder(@PathVariable Long workOrderId) {
        List<MfgSupplyAllocation> rows = allocations.selectList(new QueryWrapper<MfgSupplyAllocation>()
                .eq("work_order_id", workOrderId).eq("deleted_flag", 0)
                .orderByAsc("material_id").orderByAsc("allocation_id"));
        return ResultData.success(ResultCode.SUCCESS, rows);
    }
}
