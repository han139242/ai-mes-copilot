package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.AiQuestion;
import com.neuedu.ems.common.AiResponse;
import com.neuedu.ems.entity.ai.AiChatRequest;

import java.util.Map;

/**
 * MES 智能助手业务接口。
 */
public interface AiService {

    Map<String, Object> getStatus();

    AiResponse chat(AiQuestion request);

    AiResponse getDashboardInsight(AiQuestion request);

    AiResponse getModuleSuggestion(AiQuestion request);

    AiResponse getScheduleScenarioAdvice(Map<String, Object> scheduleSummary);

    /**
     * 智能体对话入口：规则路由优先，命中确定性查询直接返回真实数据，未命中才调用大模型。
     * 兼容旧的单问题模式，同时支持多轮 messages 上下文。
     *
     * @param request 对话请求
     * @return 包含 answer、intent、blocks、suggestions、elapsedMs 等字段的数据
     */
    default Map<String, Object> agentChat(AiChatRequest request) {
        return agentChat(request, null);
    }

    Map<String, Object> agentChat(AiChatRequest request, com.neuedu.ems.common.AuthorizationContext authorization);

    /**
     * 带当前用户ID的对话入口：请求里带 conversationId 时，把本轮问答追加到后端会话。
     *
     * <p>新增重载而不是改原签名，既有调用与单测不受影响；userId 为空时行为与
     * {@link #agentChat(AiChatRequest, com.neuedu.ems.common.AuthorizationContext)} 完全一致。</p>
     *
     * @param request       对话请求
     * @param authorization 当前登录上下文
     * @param userId        当前用户ID，用于校验会话归属
     * @return 与两参数版本相同的返回结构
     */
    default Map<String, Object> agentChat(AiChatRequest request,
                                          com.neuedu.ems.common.AuthorizationContext authorization,
                                          Long userId) {
        return agentChat(request, authorization);
    }

    /**
     * 查询大模型调用量与 Token 估算，用于成本说明。
     *
     * @return 调用统计
     */
    Map<String, Object> getUsage();
}
