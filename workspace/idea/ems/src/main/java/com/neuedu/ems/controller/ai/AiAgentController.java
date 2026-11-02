package com.neuedu.ems.controller.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.ai.AiCommandExecution;
import com.neuedu.ems.service.ai.AiCommandService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 写命令接口：预览、确认执行、执行记录、监控统计与动作目录。
 * 权限由 PermissionCodeResolver 对 /api/ai/** 统一要求 ai:assistant:use，具体动作再校验业务权限码。
 */
@RestController
@RequestMapping("/api/ai/agent")
public class AiAgentController {

    private final AiCommandService aiCommandService;

    public AiAgentController(AiCommandService aiCommandService) {
        this.aiCommandService = aiCommandService;
    }

    /**
     * 预览：只做校验并返回确认卡片数据，不改任何业务数据。
     */
    @PostMapping("/preview")
    public ResultData preview(@RequestBody AiCommandService.AiCommandPreviewRequest request,
                              @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.preview(request, authorization));
    }

    /**
     * 执行：按预览凭据调用真实领域服务。
     */
    @PostMapping("/execute")
    public ResultData execute(@RequestBody AiCommandService.AiCommandExecuteRequest request,
                              @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.execute(request, authorization));
    }

    /**
     * 结构化预览：前端表单（下拉选产品、选客户、填数量与交期）直接提交，不需要把值拼进原话。
     */
    @PostMapping("/preview-structured")
    public ResultData previewStructured(@RequestBody StructuredPreviewRequest request,
                                        @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        StructuredPreviewRequest safe = request == null
                ? new StructuredPreviewRequest(null, null, null, null, null, null, null, null, null, null, null)
                : request;
        AiCommandService.AiCommandPreviewRequest previewRequest = new AiCommandService.AiCommandPreviewRequest(
                null, safe.conversationId(), safe.source(), null);
        Map<String, Object> formParams = new LinkedHashMap<>();
        if(safe.params()!=null) {
            for(String key:List.of("warehouseId","warehouseType","warehouseCode","locationId","targetLocationId",
                    "qualifiedQuantity","defectQuantity","unqualifiedQuantity","quantity","auditResult","remark")) {
                if(safe.params().containsKey(key)) formParams.put(key,safe.params().get(key));
            }
        }
        formParams.put("productCode", safe.productCode());
        formParams.put("customerCode", safe.customerCode());
        formParams.put("orderQuantity", safe.orderQuantity());
        formParams.put("deliveryDate", safe.deliveryDate());
        formParams.put("orderNo", safe.orderNo());
        formParams.put("lineCode", safe.lineCode());
        formParams.put("targetNo", safe.targetNo());
        return ResultData.success(ResultCode.SUCCESS,
                aiCommandService.previewStructured(safe.action(), formParams, previewRequest, authorization));
    }

    /**
     * 创建生产订单表单的可选值：启用状态的产品与客户。
     */
    @GetMapping("/create-order-options")
    public ResultData createOrderOptions(@RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.createOrderOptions(authorization));
    }

    /**
     * 排产表单的可选值：不带单号给待排产订单，带单号给该订单可用的产线与最早时段。
     */
    @GetMapping("/schedule-options")
    public ResultData scheduleOptions(@RequestParam(required = false) String orderNo,
                                      @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.scheduleOptions(orderNo, authorization));
    }

    /**
     * 执行记录分页。
     */
    @GetMapping("/commands")
    public ResultData commands(@RequestParam(required = false) Integer current,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String action,
                               @RequestParam(required = false) Long userId,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false)
                               @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
                               @RequestParam(required = false)
                               @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        Page<AiCommandExecution> page = aiCommandService.pageCommands(current, size, status, action, userId,
                keyword, beginTime, endTime);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", page.getRecords());
        data.put("total", page.getTotal());
        data.put("current", page.getCurrent());
        data.put("size", page.getSize());
        data.put("pages", page.getPages());
        return ResultData.success(ResultCode.SUCCESS, data);
    }

    /**
     * 命令详情：命令记录与步骤流水。
     */
    @GetMapping("/commands/{commandId}")
    public ResultData commandDetail(@PathVariable Long commandId) {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.getCommandDetail(commandId));
    }

    /**
     * 监控统计。
     */
    @GetMapping("/stats")
    public ResultData stats() {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.stats());
    }

    /**
     * 支持的动作目录。
     */
    @GetMapping("/capabilities")
    public ResultData capabilities() {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.capabilities());
    }

    /**
     * 候选单据：一个动作当前能办哪几张单，逐张给出能否办理与阻断原因。
     */
    @PostMapping("/candidates")
    public ResultData candidates(@RequestBody CandidateRequest request,
                                 @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        String capability = request == null ? null : request.capability();
        Map<String, Object> params = request == null ? null : request.params();
        return ResultData.success(ResultCode.SUCCESS,
                aiCommandService.candidates(capability, params, authorization));
    }

    /**
     * 批量办理：逐项走完整校验与执行，一项失败不影响其它项。
     */
    @PostMapping("/execute-batch")
    public ResultData executeBatch(@RequestBody AiCommandService.AiCommandBatchRequest request,
                                   @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.executeBatch(request, authorization));
    }

    /**
     * 多步预览：一句话拆成有序步骤，逐步骤给前置检查与整体能否执行。
     */
    @PostMapping("/preview-sequence")
    public ResultData previewSequence(@RequestBody SequencePreviewRequest request,
                                      @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        SequencePreviewRequest safe = request == null ? new SequencePreviewRequest(null, null, null, null, null) : request;
        if (safe.steps() != null && !safe.steps().isEmpty()) {
            return ResultData.success(ResultCode.SUCCESS, aiCommandService.previewSequenceExplicit(
                    safe.steps(), safe.text(), safe.conversationId(), safe.source(), authorization));
        }
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.previewSequence(
                safe.text(), safe.conversationId(), safe.source(), safe.context(), authorization));
    }

    /**
     * 多步执行：按顺序逐步执行，某步失败整体置 PARTIAL_FAILED，已提交业务不回滚。
     */
    @PostMapping("/execute-sequence")
    public ResultData executeSequence(@RequestBody AiCommandService.AiCommandSequenceExecuteRequest request,
                                      @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.executeSequence(request, authorization));
    }

    /**
     * 查序列各步状态：前端刷新后按 planId 回读，卡片状态以后台为准。
     */
    @GetMapping("/sequences/{planId}")
    public ResultData getSequence(@PathVariable String planId) {
        return ResultData.success(ResultCode.SUCCESS, aiCommandService.getSequence(planId));
    }

    /**
     * 候选单据查询入参。
     *
     * @param capability 动作编码
     * @param params     附加参数，可空
     */
    public record CandidateRequest(String capability, Map<String, Object> params) {
    }

    /**
     * 结构化预览入参：表单选中的值直接提交，不经过自然语言解析。
     *
     * @param action         动作编码，如 CREATE_PRODUCTION_ORDER
     * @param productCode    产品编码
     * @param customerCode   客户编码
     * @param orderQuantity  订单数量
     * @param deliveryDate   交期 yyyy-MM-dd
     * @param orderNo        生产订单号，排产用
     * @param lineCode       产线编码，排产用，可空
     * @param targetNo       目标单据号，下达工单等直接以单号为目标的动作用
     * @param conversationId 会话ID，可空
     * @param source         来源 WORKBENCH/DOCK/API，可空
     */
    public record StructuredPreviewRequest(String action, String productCode, String customerCode,
                                           String orderQuantity, String deliveryDate,
                                           String orderNo, String lineCode, String targetNo,
                                           String conversationId, String source, Map<String,Object> params) {
    }

    /**
     * 多步预览入参。
     *
     * @param text           用户原话
     * @param conversationId 会话ID，可空
     * @param source         来源 WORKBENCH/DOCK/API，可空
     * @param context        附加上下文，可空
     * @param steps          显式步骤（大模型直接下发时用），可空
     */
    public record SequencePreviewRequest(String text, String conversationId, String source,
                                         Map<String, Object> context,
                                         List<AiCommandService.SequenceStepInput> steps) {
    }
}
