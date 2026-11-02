package com.neuedu.ems.controller.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.mfg.MfgCommandRequest;
import com.neuedu.ems.mapper.mfg.MfgCommandRequestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命令幂等登记查询控制器。
 *
 * <p>只读接口：幂等凭据由领料单生成、领料转线边等命令在事务内写入，
 * 不提供增删改，避免手工补写凭据绕过请求摘要校验。</p>
 */
@RestController
@RequestMapping("/api/mfg/command-request")
public class MfgCommandRequestController {

    @Autowired
    private MfgCommandRequestMapper commandRequests;

    /** 分页查询命令幂等登记，可按命令、请求键、聚合与结果主键筛选。 */
    @GetMapping("/page")
    public ResultData page(@RequestParam(defaultValue = "1") Integer current,
                           @RequestParam(defaultValue = "10") Integer size,
                           @RequestParam(required = false) String command,
                           @RequestParam(required = false) String requestKey,
                           @RequestParam(required = false) String aggregateType,
                           @RequestParam(required = false) Long aggregateId,
                           @RequestParam(required = false) Long resultId) {
        QueryWrapper<MfgCommandRequest> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (command != null && !command.trim().isEmpty()) {
            wrapper.eq("command", command.trim());
        }
        if (requestKey != null && !requestKey.trim().isEmpty()) {
            wrapper.eq("request_key", requestKey.trim());
        }
        if (aggregateType != null && !aggregateType.trim().isEmpty()) {
            wrapper.eq("aggregate_type", aggregateType.trim());
        }
        if (aggregateId != null) {
            wrapper.eq("aggregate_id", aggregateId);
        }
        if (resultId != null) {
            wrapper.eq("result_id", resultId);
        }
        wrapper.orderByDesc("request_id");
        Page<MfgCommandRequest> result = commandRequests.selectPage(new Page<>(current, size), wrapper);
        return ResultData.success(ResultCode.SUCCESS, result);
    }
}
