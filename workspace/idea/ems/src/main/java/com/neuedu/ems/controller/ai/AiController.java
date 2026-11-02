package com.neuedu.ems.controller.ai;

import com.neuedu.ems.common.AiQuestion;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.ai.AiChatRequest;
import com.neuedu.ems.service.ai.AiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.service.ai.AiCapabilityCatalog;
import com.neuedu.ems.service.ai.AiKnowledgeService;
import com.neuedu.ems.service.ai.AiQueryAccessPolicy;
import com.neuedu.ems.service.ai.AiTodoService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MES 智能助手控制器。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;
    private final AiKnowledgeService aiKnowledgeService;
    private final AiTodoService aiTodoService;

    public AiController(AiService aiService, AiKnowledgeService aiKnowledgeService,
                        AiTodoService aiTodoService) {
        this.aiService = aiService;
        this.aiKnowledgeService = aiKnowledgeService;
        this.aiTodoService = aiTodoService;
    }

    /**
     * 查询 AI 是否启用和配置完整，不会向第三方发起请求。
     */
    @GetMapping("/status")
    public ResultData status() {
        return ResultData.success(ResultCode.SUCCESS, aiService.getStatus());
    }

    /**
     * 智能体对话入口。
     * 兼容旧的单问题入参（question/module/context），同时支持多轮 messages 与结构化 blocks 返回。
     * 请求带 conversationId 时，本轮问答会追加到后端会话，换设备登录也能看到历史。
     */
    @PostMapping("/chat")
    public ResultData chat(@Valid @RequestBody AiChatRequest request,
                           @RequestAttribute("authorizationContext") AuthorizationContext authorization,
                           @RequestAttribute(value = "userId", required = false) Long userId) {
        return ResultData.success(ResultCode.SUCCESS, aiService.agentChat(request, authorization, userId));
    }

    /**
     * 业务能力目录：查询、图表、单次办理共用同一份清单，前端不再各自硬编码。
     * 只返回能力编码、类型、名称、说明与参数定义，不含任何实现细节。
     */
    @GetMapping("/capabilities")
    public ResultData capabilities(@RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "general");
        return ResultData.success(ResultCode.SUCCESS, AiCapabilityCatalog.capabilities(authorization));
    }

    /**
     * 大模型调用量与 Token 估算，用于成本说明。
     */
    @GetMapping("/usage")
    public ResultData usage() {
        return ResultData.success(ResultCode.SUCCESS, aiService.getUsage());
    }

    /**
     * 说明书章节检索：回答带文档名、章节与页码，供知识助手和答辩核对出处使用。
     * 只返回章节元数据与原文摘要，不返回整篇文档。
     */
    @GetMapping("/knowledge/search")
    public ResultData knowledgeSearch(@RequestParam(value = "q", required = false) String question,
                                      @RequestParam(value = "limit", defaultValue = "3") Integer limit,
                                      @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "general");
        List<Map<String, Object>> hits = aiKnowledgeService.search(question, limit == null ? 3 : limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("hitCount", hits.size());
        data.put("sections", hits);
        data.put("totalSections", aiKnowledgeService.sectionCount());
        data.put("answer", aiKnowledgeService.composeAnswer(question, hits));
        return ResultData.success(ResultCode.SUCCESS, data);
    }

    /**
     * 我的待办：按当前账号可办理的业务动作聚合真实待处理事项。
     * 来源是单据状态、缺料数量、计划时间、设备状态等业务事实，不是通知已读标记。
     */
    @GetMapping("/todo/my")
    public ResultData myTodos(@RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireIntent(authorization, "general");
        return ResultData.success(ResultCode.SUCCESS, aiTodoService.myTodos(authorization));
    }

    /**
     * 使用后端真实看板汇总生成数据解读。
     */
    @PostMapping("/dashboard-insight")
    public ResultData dashboardInsight(@Valid @RequestBody AiQuestion request,
                                      @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireModule(authorization, "dashboard");
        return ResultData.success(ResultCode.SUCCESS, aiService.getDashboardInsight(request));
    }

    /**
     * 根据模块和后端汇总数据生成只读业务建议。
     */
    @PostMapping("/module-suggestion")
    public ResultData moduleSuggestion(@Valid @RequestBody AiQuestion request,
                                      @RequestAttribute("authorizationContext") AuthorizationContext authorization) {
        AiQueryAccessPolicy.requireModule(authorization, request.getModule());
        return ResultData.success(ResultCode.SUCCESS, aiService.getModuleSuggestion(request));
    }
}
