package com.neuedu.ems.controller.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.ai.AiAlertRule;
import com.neuedu.ems.service.ai.AiAlertService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主动预警中心接口：指标目录、规则（限度）维护、试算、扫描、事件处置。
 *
 * <p>权限由 {@code PermissionCodeResolver} 对 /api/ai/** 统一要求 ai:assistant:use；
 * 规则配置与事件处置另配 ai:alert:configure / ai:alert:handle 两个按钮权限，
 * 用于区分"谁能改限度"和"谁只能确认处理"。</p>
 */
@RestController
@RequestMapping("/api/ai/alert")
public class AiAlertController {

    private final AiAlertService alertService;

    public AiAlertController(AiAlertService alertService) {
        this.alertService = alertService;
    }

    /** 指标目录：系统能盯哪些事、默认限度、默认自动动作与口径说明。 */
    @GetMapping("/metrics")
    public ResultData metrics() {
        return ResultData.success(ResultCode.SUCCESS, alertService.metrics());
    }

    /** 顶部概览。 */
    @GetMapping("/overview")
    public ResultData overview() {
        return ResultData.success(ResultCode.SUCCESS, alertService.overview());
    }

    /** 铃铛角标用的待处置汇总。 */
    @GetMapping("/pending")
    public ResultData pending() {
        return ResultData.success(ResultCode.SUCCESS, alertService.pendingSummary());
    }

    /** 规则分页。 */
    @GetMapping("/rules")
    public ResultData rules(@RequestParam(required = false) Integer current,
                            @RequestParam(required = false) Integer size,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String metricCode,
                            @RequestParam(required = false) String keyword) {
        return ResultData.success(ResultCode.SUCCESS,
                pageData(alertService.pageRules(current, size, status, metricCode, keyword)));
    }

    /** 规则详情。 */
    @GetMapping("/rules/{ruleId}")
    public ResultData rule(@PathVariable Long ruleId) {
        return ResultData.success(ResultCode.SUCCESS, alertService.getRule(ruleId));
    }

    /** 新建规则。 */
    @PostMapping("/rules")
    public ResultData createRule(@RequestBody AiAlertRule rule,
                                 @RequestAttribute("userId") Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", alertService.saveRule(rule, userId));
        return ResultData.success(ResultCode.SUCCESS, data);
    }

    /** 修改规则，含调整预警限度。 */
    @PutMapping("/rules")
    public ResultData updateRule(@RequestBody AiAlertRule rule,
                                 @RequestAttribute("userId") Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated", alertService.updateRule(rule, userId));
        return ResultData.success(ResultCode.SUCCESS, data);
    }

    /** 删除规则。 */
    @DeleteMapping("/rules/{ruleId}")
    public ResultData removeRule(@PathVariable Long ruleId,
                                 @RequestAttribute("userId") Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("removed", alertService.removeRule(ruleId, userId));
        return ResultData.success(ResultCode.SUCCESS, data);
    }

    /** 启用 / 暂停规则。 */
    @PutMapping("/rules/{ruleId}/status")
    public ResultData changeStatus(@PathVariable Long ruleId,
                                   @RequestBody StatusRequest request,
                                   @RequestAttribute("userId") Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("changed", alertService.changeStatus(ruleId, request == null ? null : request.getStatus(), userId));
        return ResultData.success(ResultCode.SUCCESS, data);
    }

    /** 试算：按当前规则跑一遍，只看命中结果，不写库、不发通知。 */
    @PostMapping("/rules/{ruleId}/preview")
    public ResultData preview(@PathVariable Long ruleId) {
        return ResultData.success(ResultCode.SUCCESS, alertService.preview(ruleId));
    }

    /** 手工触发一轮扫描。 */
    @PostMapping("/scan")
    public ResultData scan(@RequestAttribute("userId") Long userId) {
        return ResultData.success(ResultCode.SUCCESS, alertService.scan(userId));
    }

    /** 扫描记录分页。 */
    @GetMapping("/scans")
    public ResultData scans(@RequestParam(required = false) Integer current,
                            @RequestParam(required = false) Integer size,
                            @RequestParam(required = false) String status) {
        return ResultData.success(ResultCode.SUCCESS, pageData(alertService.pageScans(current, size, status)));
    }

    /** 预警事件分页。 */
    @GetMapping("/events")
    public ResultData events(@RequestParam(required = false) Integer current,
                             @RequestParam(required = false) Integer size,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String severity,
                             @RequestParam(required = false) String metricCode,
                             @RequestParam(required = false) String keyword) {
        return ResultData.success(ResultCode.SUCCESS,
                pageData(alertService.pageEvents(current, size, status, severity, metricCode, keyword)));
    }

    /** 事件详情。 */
    @GetMapping("/events/{alertId}")
    public ResultData event(@PathVariable Long alertId) {
        return ResultData.success(ResultCode.SUCCESS, alertService.getEvent(alertId));
    }

    /** 事件已执行的自动动作明细。 */
    @GetMapping("/events/{alertId}/actions")
    public ResultData actions(@PathVariable Long alertId) {
        return ResultData.success(ResultCode.SUCCESS, alertService.actions(alertId));
    }

    /** 确认收到。 */
    @PutMapping("/events/{alertId}/acknowledge")
    public ResultData acknowledge(@PathVariable Long alertId,
                                  @RequestBody(required = false) HandleRequest request,
                                  @RequestAttribute("userId") Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("acknowledged", alertService.acknowledge(alertId, request == null ? null : request.getNote(), userId));
        return ResultData.success(ResultCode.SUCCESS, data);
    }

    /** 人工关闭。 */
    @PutMapping("/events/{alertId}/close")
    public ResultData close(@PathVariable Long alertId,
                            @RequestBody(required = false) HandleRequest request,
                            @RequestAttribute("userId") Long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("closed", alertService.close(alertId, request == null ? null : request.getNote(), userId));
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

    /** 状态变更请求体。 */
    public static class StatusRequest {
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /** 处置请求体。 */
    public static class HandleRequest {
        private String note;

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
