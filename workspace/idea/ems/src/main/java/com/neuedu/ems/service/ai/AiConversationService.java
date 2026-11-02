package com.neuedu.ems.service.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.entity.ai.AiConversation;
import com.neuedu.ems.entity.ai.AiConversationMessage;

import java.util.List;
import java.util.Map;

/**
 * AI 对话历史服务：会话与消息落库，按账号隔离。
 *
 * <p>浏览器端原先用 localStorage 存对话，换台设备就丢，存储 key 也没带账号，
 * 同一浏览器切换账号还能看到上一个人的记录。这里把会话与消息统一放后端，
 * 所有方法都以 userId 校验归属，非本人一律拒绝，不依赖前端过滤。</p>
 */
public interface AiConversationService {

    /**
     * 会话分页。
     *
     * @param userId   当前用户ID，非空
     * @param keyword  标题模糊关键字，可空
     * @param archived 是否查归档会话
     * @param current  页码，从 1 开始
     * @param size     每页条数
     * @return 会话分页
     */
    Page<AiConversation> page(Long userId, String keyword, boolean archived, Integer current, Integer size);

    /**
     * 新建会话。
     *
     * @param userId     归属用户ID
     * @param title      标题，可空；为空时先用默认标题，首条用户消息到达后自动替换
     * @param capability 能力编码，可空，默认 general
     * @return 已落库的会话
     */
    AiConversation create(Long userId, String title, String capability);

    /**
     * 会话详情：会话信息 + 消息列表。
     *
     * @param conversationId 会话ID
     * @param userId         当前用户ID，用于校验归属
     * @return 含 conversation、messages、messageTotal 等字段的视图
     */
    Map<String, Object> detail(Long conversationId, Long userId);

    /**
     * 会话详情（消息分页）。
     *
     * @param conversationId 会话ID
     * @param userId         当前用户ID
     * @param current        消息页码，从 1 开始
     * @param size           每页消息条数
     * @return 含 conversation、messages、messageTotal 等字段的视图
     */
    Map<String, Object> detail(Long conversationId, Long userId, Integer current, Integer size);

    /**
     * 重命名会话。
     */
    void rename(Long conversationId, Long userId, String title);

    /**
     * 归档或取消归档。
     */
    void archive(Long conversationId, Long userId, boolean archived);

    /**
     * 逻辑删除会话及其消息。
     */
    void remove(Long conversationId, Long userId);

    /**
     * 追加一条消息。seq_no 在服务层按会话内最大值加一计算，
     * 追加后同步维护会话的 message_count、last_message_time，首条用户消息替换标题。
     *
     * @param conversationId 会话ID
     * @param userId         当前用户ID，用于校验归属
     * @param message        待追加消息，role/content 必填
     * @return 已落库的消息，带 messageId 与 seqNo
     */
    AiConversationMessage append(Long conversationId, Long userId, AiConversationMessage message);

    /**
     * 取最近若干条消息，供模型上下文使用。
     *
     * @param conversationId 会话ID
     * @param userId         当前用户ID，用于校验归属
     * @param limit          最多取几条，按时间正序返回
     * @return 消息列表，无权限时抛业务异常
     */
    List<AiConversationMessage> recentMessages(Long conversationId, Long userId, int limit);
}
