package com.neuedu.ems.controller.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.ai.AiTaskDefinition;
import com.neuedu.ems.entity.ai.AiTaskInstance;
import com.neuedu.ems.service.ai.AiTaskService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 多步任务接口：定义维护、试算、手动/事件/定时触发、实例与步骤流水、断点续跑。
 * 权限由 PermissionCodeResolver 对 /api/ai/** 统一要求 ai:assistant:use。
 */
@RestController
@RequestMapping("/api/ai/tasks")
public class AiTaskController {

    private final AiTaskService taskService;

    public AiTaskController(AiTaskService taskService) {
        this.taskService = taskService;
    }

    /** 顶部指标。 */
    @GetMapping("/overview")
    public ResultData overview() {
        return ResultData.success(ResultCode.SUCCESS, taskService.overview());
    }

    /** 可用步骤能力清单。 */
    @GetMapping("/capabilities")
    public ResultData capabilities() {
        return ResultData.success(ResultCode.SUCCESS, taskService.stepCapabilities());
    }

    /** 定义分页。 */
    @GetMapping("/definitions")
    public ResultData definitions(@RequestParam(required = false) Integer current,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String keyword) {
        return ResultData.success(ResultCode.SUCCESS,
                pageData(taskService.pageDefinitions(current, size, status, keyword)));
    }

    /** 定义详情。 */
    @GetMapping("/definitions/{definitionId}")
    public ResultData definition(@PathVariable Long definitionId) {
        return ResultData.success(ResultCode.SUCCESS, taskService.getDefinition(definitionId));
    }

    /** 新建定义。 */
    @PostMapping("/definitions")
    public ResultData createDefinition(@RequestBody AiTaskDefinition definition) {
        return ResultData.success(ResultCode.SUCCESS, taskService.saveDefinition(definition));
    }

    /** 修改定义。 */
    @PutMapping("/definitions")
    public ResultData updateDefinition(@RequestBody AiTaskDefinition definition) {
        return ResultData.success(ResultCode.SUCCESS, taskService.updateDefinition(definition));
    }

    /** 删除定义（软删除）。 */
    @DeleteMapping("/definitions/{definitionId}")
    public ResultData removeDefinition(@PathVariable Long definitionId) {
        return ResultData.success(ResultCode.SUCCESS, taskService.removeDefinition(definitionId));
    }

    /** 启用 / 暂停 / 停用。 */
    @PutMapping("/definitions/{definitionId}/status")
    public ResultData changeStatus(@PathVariable Long definitionId, @RequestBody Map<String, String> body) {
        String status = body == null ? null : body.get("status");
        return ResultData.success(ResultCode.SUCCESS, taskService.changeStatus(definitionId, status));
    }

    /** 试算：只展示步骤与触发方式，不执行。 */
    @PostMapping("/definitions/{definitionId}/preview")
    public ResultData preview(@PathVariable Long definitionId) {
        return ResultData.success(ResultCode.SUCCESS, taskService.preview(definitionId));
    }

    /** 手动触发一次。 */
    @PostMapping("/definitions/{definitionId}/run")
    public ResultData run(@PathVariable Long definitionId,
                          @RequestAttribute(name = "userId", required = false) Long userId) {
        return ResultData.success(ResultCode.SUCCESS, taskService.run(definitionId, userId));
    }

    /** 把开工处置方案落成一条草稿任务定义，用户确认后再启用。 */
    @PostMapping("/from-start-plan")
    public ResultData fromStartPlan(@RequestBody Map<String, String> body,
                                    @RequestAttribute(name = "userId", required = false) Long userId,
                                    @RequestAttribute("authorizationContext") com.neuedu.ems.common.AuthorizationContext authorization) {
        com.neuedu.ems.service.ai.AiQueryAccessPolicy.requireIntent(authorization, "work_order_start_plan");
        String workOrderNo = body == null ? null : body.get("workOrderNo");
        return ResultData.success(ResultCode.SUCCESS,
                taskService.createDefinitionFromStartPlan(workOrderNo, userId));
    }

    /** 实例分页。 */
    @GetMapping("/instances")
    public ResultData instances(@RequestParam(required = false) Integer current,
                                @RequestParam(required = false) Integer size,
                                @RequestParam(required = false) Long definitionId,
                                @RequestParam(required = false) String status) {
        return ResultData.success(ResultCode.SUCCESS,
                pageData(taskService.pageInstances(current, size, definitionId, status)));
    }

    /** 实例详情，含步骤流水与定义快照。 */
    @GetMapping("/instances/{instanceId}")
    public ResultData instanceDetail(@PathVariable Long instanceId) {
        return ResultData.success(ResultCode.SUCCESS, taskService.getInstanceDetail(instanceId));
    }

    /** 续跑一个中断实例。 */
    @PostMapping("/instances/{instanceId}/resume")
    public ResultData resume(@PathVariable Long instanceId,
                             @RequestAttribute(name = "userId", required = false) Long userId) {
        return ResultData.success(ResultCode.SUCCESS, taskService.resume(instanceId, userId));
    }

    /** 取消一个还在跑或等人补充的实例。 */
    @PostMapping("/instances/{instanceId}/cancel")
    public ResultData cancel(@PathVariable Long instanceId,
                             @RequestBody(required = false) Map<String, Object> body,
                             @RequestAttribute(name = "userId", required = false) Long userId) {
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return ResultData.success(ResultCode.SUCCESS, taskService.cancel(instanceId, userId, reason));
    }

    /** 手动触发一次超时收敛，便于运维在扫描间隔之外立即清理卡死实例。 */
    @PostMapping("/expire-overdue")
    public ResultData expireOverdue() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expired", taskService.expireOverdue());
        return ResultData.success(ResultCode.SUCCESS, data);
    }

    private Map<String, Object> pageData(Page<?> page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", page.getRecords());
        data.put("total", page.getTotal());
        data.put("current", page.getCurrent());
        data.put("size", page.getSize());
        data.put("pages", page.getPages());
        return data;
    }
}
