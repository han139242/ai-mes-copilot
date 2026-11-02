package com.neuedu.ems.controller.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.mfg.MfgSupplySource;
import com.neuedu.ems.mapper.mfg.MfgSupplySourceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 需求供给来源明细查询控制器。
 *
 * <p>只读接口：补产拆分由生成工单命令在事务内写入，不提供单独增删改，
 * 避免绕过产能窗口与预留校验手工改供给关系。</p>
 */
@RestController
@RequestMapping("/api/mfg/supply-source")
public class MfgSupplySourceController {

    @Autowired
    private MfgSupplySourceMapper supplySources;

    /** 分页查询供给来源明细，可按需求、工单、补产计划、物料筛选。 */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false) Long requirementId,
                           @RequestParam(required = false) Long workOrderId,
                           @RequestParam(required = false) Long planId,
                           @RequestParam(required = false) Long materialId) {
        QueryWrapper<MfgSupplySource> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (requirementId != null) {
            wrapper.eq("requirement_id", requirementId);
        }
        if (workOrderId != null) {
            wrapper.eq("work_order_id", workOrderId);
        }
        if (planId != null) {
            wrapper.eq("plan_id", planId);
        }
        if (materialId != null) {
            wrapper.eq("material_id", materialId);
        }
        wrapper.orderByAsc("requirement_id").orderByAsc("sequence_no");
        Page<MfgSupplySource> result = supplySources.selectPage(new Page<>(current, size), wrapper);
        return ResultData.success(ResultCode.SUCCESS, result);
    }

    /** 按需求查询全部供给来源明细，用于核对一条需求被拆成了几张补产单。 */
    @GetMapping("/requirement/{requirementId}")
    public ResultData listByRequirement(@PathVariable Long requirementId) {
        List<MfgSupplySource> sources = supplySources.selectList(new QueryWrapper<MfgSupplySource>()
                .eq("requirement_id", requirementId).eq("deleted_flag", 0)
                .orderByAsc("sequence_no"));
        return ResultData.success(ResultCode.SUCCESS, sources);
    }

    /** 按组件补产计划查询供给来源明细，用于下达前核对供给口径。 */
    @GetMapping("/plan/{planId}")
    public ResultData listByPlan(@PathVariable Long planId) {
        List<MfgSupplySource> sources = supplySources.selectList(new QueryWrapper<MfgSupplySource>()
                .eq("plan_id", planId).eq("deleted_flag", 0)
                .orderByAsc("sequence_no"));
        return ResultData.success(ResultCode.SUCCESS, sources);
    }
}
