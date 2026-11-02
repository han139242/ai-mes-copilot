package com.neuedu.ems.controller.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.ai.AiAutomationRule;
import com.neuedu.ems.entity.ai.AiAutomationRun;
import com.neuedu.ems.entity.ai.AiInventoryRisk;
import com.neuedu.ems.service.ai.AiAutomationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 持续自动化规则接口：规则维护、试算、手工与定时执行、执行记录、风险台账。
 * 权限由 PermissionCodeResolver 对 /api/ai/** 统一要求 ai:assistant:use。
 */
@RestController
@RequestMapping("/api/ai/automation")
public class AiAutomationController {

    private final AiAutomationService automationService;

    public AiAutomationController(AiAutomationService automationService) {
        this.automationService = automationService;
    }

    /** 顶部指标。 */
    @GetMapping("/overview")
    public ResultData overview() {
        return ResultData.success(ResultCode.SUCCESS, automationService.overview());
    }

    /** 规则分页。 */
    @GetMapping("/rules")
    public ResultData rules(@RequestParam(required = false) Integer current,
                            @RequestParam(required = false) Integer size,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String keyword) {
        return ResultData.success(ResultCode.SUCCESS,
                pageData(automationService.pageRules(current, size, status, keyword)));
    }

    /** 规则详情。 */
    @GetMapping("/rules/{ruleId}")
    public ResultData rule(@PathVariable Long ruleId) {
        return ResultData.success(ResultCode.SUCCESS, automationService.getRule(ruleId));
    }

    /** 规则覆盖的物料与阈值。 */
    @GetMapping("/rules/{ruleId}/materials")
    public ResultData ruleMaterials(@PathVariable Long ruleId) {
        List<Map<String, Object>> rows = automationService.ruleMaterials(ruleId);
        return ResultData.success(ResultCode.SUCCESS, rows);
    }

    /** 新建规则。 */
    @PostMapping("/rules")
    public ResultData createRule(@RequestBody AiAutomationRule rule) {
        return ResultData.success(ResultCode.SUCCESS, automationService.saveRule(rule));
    }

    /** 修改规则。 */
    @PutMapping("/rules")
    public ResultData updateRule(@RequestBody AiAutomationRule rule) {
        return ResultData.success(ResultCode.SUCCESS, automationService.updateRule(rule));
    }

    /** 删除规则（软删除）。 */
    @DeleteMapping("/rules/{ruleId}")
    public ResultData removeRule(@PathVariable Long ruleId) {
        return ResultData.success(ResultCode.SUCCESS, automationService.removeRule(ruleId));
    }

    /** 启用 / 暂停 / 停用。 */
    @PutMapping("/rules/{ruleId}/status")
    public ResultData changeStatus(@PathVariable Long ruleId, @RequestBody Map<String, String> body) {
        String status = body == null ? null : body.get("status");
        return ResultData.success(ResultCode.SUCCESS, automationService.changeStatus(ruleId, status));
    }

    /**
     * 试算：只算出会命中哪些物料、会建几条风险与建议，不改任何数据。
     */
    @PostMapping("/rules/{ruleId}/preview")
    public ResultData preview(@PathVariable Long ruleId) {
        return ResultData.success(ResultCode.SUCCESS, automationService.preview(ruleId));
    }

    /** 立即执行一次。 */
    @PostMapping("/rules/{ruleId}/run")
    public ResultData run(@PathVariable Long ruleId) {
        return ResultData.success(ResultCode.SUCCESS, automationService.run(ruleId));
    }

    /** 执行记录分页。 */
    @GetMapping("/runs")
    public ResultData runs(@RequestParam(required = false) Integer current,
                           @RequestParam(required = false) Integer size,
                           @RequestParam(required = false) Long ruleId,
                           @RequestParam(required = false) String status) {
        return ResultData.success(ResultCode.SUCCESS,
                pageData(automationService.pageRuns(current, size, ruleId, status)));
    }

    /** 执行详情：记录 + 步骤流水。 */
    @GetMapping("/runs/{runId}")
    public ResultData runDetail(@PathVariable Long runId) {
        return ResultData.success(ResultCode.SUCCESS, automationService.getRunDetail(runId));
    }

    /** 未关闭风险台账。 */
    @GetMapping("/risks")
    public ResultData risks(@RequestParam(required = false) Integer current,
                            @RequestParam(required = false) Integer size,
                            @RequestParam(required = false) String status) {
        return ResultData.success(ResultCode.SUCCESS,
                pageData(automationService.pageRisks(current, size, status)));
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
