package com.neuedu.ems.entity.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI 对话请求，兼容旧的单问题模式和新的多轮 messages 模式。
 * question 与 messages 至少有一个非空：messages 取最后一条 user 消息作为本轮问题。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiChatRequest {

    /**
     * 本轮问题，兼容旧版单轮调用。
     */
    @Size(max = 500, message = "问题长度不能超过500字")
    private String question;

    /**
     * 模块编码，可空，默认 general。
     */
    @Size(max = 64, message = "模块编码长度不能超过64个字符")
    private String module;

    /**
     * 前端上下文，仅作筛选参考。
     */
    @Size(max = 20, message = "上下文字段不能超过20项")
    private Map<String, Object> context;

    /**
     * 多轮对话历史，最多取最近 10 轮（20 条），超过部分由后端截断。
     */
    @Valid
    @Size(max = 40, message = "对话消息不能超过40条")
    private List<AiChatMessage> messages;

    /**
     * 后端会话ID，可空。传了就说明这轮对话要落库到该会话，
     * 不传时行为与改造前完全一致，只做一次性问答。
     */
    private Long conversationId;

    /**
     * 客户端消息标识，可空。前端在同一条提问重试时必须复用同一个值，
     * 后端据此判断这条提问是否已经落过库，避免网络重试把同一句话写两遍。
     */
    @Size(max = 64, message = "客户端消息标识长度不能超过64个字符")
    private String clientMessageId;
}
