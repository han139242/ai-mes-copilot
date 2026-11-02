package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.ai.AiConversation;
import com.neuedu.ems.entity.ai.AiConversationMessage;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.ai.AiConversationMapper;
import com.neuedu.ems.mapper.ai.AiConversationMessageMapper;
import com.neuedu.ems.service.ai.AiConversationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话历史实现：会话与消息落库，按账号隔离。
 *
 * <p>归属校验放在每个入口，而不是只靠分页条件过滤：即便有人猜到了别人的会话ID，
 * 也会在进入业务逻辑前被拒掉。删除一律走 deleted_flag，聊天记录不做物理删除。</p>
 *
 * <p>消息序号不用数据库自增，而是按会话内最大值加一在服务里算，这样同一个会话的
 * 消息序号连续、可排序；并发插入时由 (conversation_id, seq_no) 唯一索引兜底，
 * 撞上了重算一次即可。</p>
 */
@Service
public class AiConversationServiceImpl implements AiConversationService {

    /** 会话编号时间格式。 */
    private static final DateTimeFormatter CONVERSATION_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 标题最多取多少个字。 */
    private static final int TITLE_MAX_CHARS = 24;

    /** 默认标题。 */
    private static final String DEFAULT_TITLE = "新对话";

    /** 默认能力编码。 */
    private static final String DEFAULT_CAPABILITY = "general";

    /** 消息分页默认条数。 */
    private static final int DEFAULT_MESSAGE_SIZE = 50;

    /** 单页消息上限。 */
    private static final int MAX_MESSAGE_SIZE = 200;

    /** 并发插入撞唯一索引后的重试次数。 */
    private static final int SEQ_RETRY_TIMES = 3;

    private final AiConversationMapper conversationMapper;
    private final AiConversationMessageMapper messageMapper;

    public AiConversationServiceImpl(AiConversationMapper conversationMapper,
                                     AiConversationMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Override
    public Page<AiConversation> page(Long userId, String keyword, boolean archived, Integer current, Integer size) {
        requireUser(userId);
        LambdaQueryWrapper<AiConversation> wrapper = new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, userId)
                .eq(AiConversation::getDeletedFlag, 0)
                .eq(AiConversation::getArchived, archived ? 1 : 0)
                .like(StringUtils.hasText(keyword), AiConversation::getTitle, keyword)
                .orderByDesc(AiConversation::getLastMessageTime)
                .orderByDesc(AiConversation::getConversationId);
        return conversationMapper.selectPage(new Page<>(page(current), pageSize(size)), wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiConversation create(Long userId, String title, String capability) {
        requireUser(userId);
        LocalDateTime now = LocalDateTime.now();
        AiConversation conversation = new AiConversation();
        conversation.setConversationNo("AIC-" + now.format(CONVERSATION_NO_FORMAT)
                + "-" + Math.abs((userId + ":" + System.nanoTime()).hashCode() % 1000));
        conversation.setUserId(userId);
        conversation.setTitle(StringUtils.hasText(title) ? trimTitle(title) : DEFAULT_TITLE);
        conversation.setCapability(StringUtils.hasText(capability) ? capability.trim() : DEFAULT_CAPABILITY);
        conversation.setMessageCount(0);
        conversation.setArchived(0);
        conversation.setVersion(0);
        conversation.setDeletedFlag(0);
        conversation.setCreatedBy(userId);
        conversation.setUpdatedBy(userId);
        conversation.setCreatedTime(now);
        conversation.setUpdatedTime(now);
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Override
    public Map<String, Object> detail(Long conversationId, Long userId) {
        return detail(conversationId, userId, null, null);
    }

    @Override
    public Map<String, Object> detail(Long conversationId, Long userId, Integer current, Integer size) {
        AiConversation conversation = requireOwned(conversationId, userId);
        int pageNo = page(current);
        int pageSize = messageSize(size);
        Page<AiConversationMessage> messages = messageMapper.selectPage(
                new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<AiConversationMessage>()
                        .eq(AiConversationMessage::getConversationId, conversationId)
                        .eq(AiConversationMessage::getDeletedFlag, 0)
                        .orderByAsc(AiConversationMessage::getSeqNo));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversation", conversation);
        data.put("messages", messages.getRecords());
        data.put("messageTotal", messages.getTotal());
        data.put("current", messages.getCurrent());
        data.put("size", messages.getSize());
        data.put("pages", messages.getPages());
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long conversationId, Long userId, String title) {
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "会话标题不能为空");
        }
        AiConversation existing = requireOwned(conversationId, userId);
        AiConversation update = new AiConversation();
        update.setConversationId(existing.getConversationId());
        update.setTitle(trimTitle(title));
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(userId);
        update.setUpdatedTime(LocalDateTime.now());
        conversationMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long conversationId, Long userId, boolean archived) {
        AiConversation existing = requireOwned(conversationId, userId);
        AiConversation update = new AiConversation();
        update.setConversationId(existing.getConversationId());
        update.setArchived(archived ? 1 : 0);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(userId);
        update.setUpdatedTime(LocalDateTime.now());
        conversationMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long conversationId, Long userId) {
        AiConversation existing = requireOwned(conversationId, userId);
        LocalDateTime now = LocalDateTime.now();
        AiConversation update = new AiConversation();
        update.setConversationId(existing.getConversationId());
        update.setDeletedFlag(1);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(userId);
        update.setUpdatedTime(now);
        conversationMapper.updateById(update);

        AiConversationMessage messageUpdate = new AiConversationMessage();
        messageUpdate.setDeletedFlag(1);
        messageUpdate.setUpdatedBy(userId);
        messageUpdate.setUpdatedTime(now);
        messageMapper.update(messageUpdate, new LambdaQueryWrapper<AiConversationMessage>()
                .eq(AiConversationMessage::getConversationId, conversationId)
                .eq(AiConversationMessage::getDeletedFlag, 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiConversationMessage append(Long conversationId, Long userId, AiConversationMessage message) {
        if (message == null || !StringUtils.hasText(message.getRole())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "消息角色不能为空");
        }
        AiConversation existing = requireOwned(conversationId, userId);
        AiConversationMessage replay = existingWithClientId(conversationId, message.getClientMessageId());
        if (replay != null) {
            // 同一条提问重试：直接回放已落库的记录，不再追加一条重复的历史。
            return replay;
        }
        LocalDateTime now = LocalDateTime.now();
        AiConversationMessage record = copyOf(message);
        record.setConversationId(conversationId);
        record.setRole(normalizeRole(message.getRole()));
        record.setVersion(0);
        record.setDeletedFlag(0);
        record.setCreatedBy(userId);
        record.setUpdatedBy(userId);
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        insertWithNextSeq(record);
        refreshConversation(existing, record, userId, now);
        return record;
    }

    @Override
    public List<AiConversationMessage> recentMessages(Long conversationId, Long userId, int limit) {
        requireOwned(conversationId, userId);
        int fetch = limit < 1 ? 1 : Math.min(limit, MAX_MESSAGE_SIZE);
        List<AiConversationMessage> rows = messageMapper.selectList(
                new LambdaQueryWrapper<AiConversationMessage>()
                        .eq(AiConversationMessage::getConversationId, conversationId)
                        .eq(AiConversationMessage::getDeletedFlag, 0)
                        .orderByDesc(AiConversationMessage::getSeqNo)
                        .last("LIMIT " + fetch));
        List<AiConversationMessage> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    /**
     * 按会话内最大序号加一插入；并发撞唯一索引时重算一次。
     */
    private void insertWithNextSeq(AiConversationMessage record) {
        for (int attempt = 1; attempt <= SEQ_RETRY_TIMES; attempt++) {
            record.setSeqNo(nextSeqNo(record.getConversationId()));
            try {
                messageMapper.insert(record);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == SEQ_RETRY_TIMES) {
                    throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(), "消息写入冲突，请重试");
                }
            }
        }
    }

    /**
     * 会话内下一个序号：取当前最大值加一，空会话从 1 开始。
     */
    private int nextSeqNo(Long conversationId) {
        AiConversationMessage last = messageMapper.selectOne(
                new LambdaQueryWrapper<AiConversationMessage>()
                        .eq(AiConversationMessage::getConversationId, conversationId)
                        .orderByDesc(AiConversationMessage::getSeqNo)
                        .last("LIMIT 1"));
        return last == null || last.getSeqNo() == null ? 1 : last.getSeqNo() + 1;
    }

    /**
     * 追加消息后维护会话计数、最后消息时间与标题。
     */
    private void refreshConversation(AiConversation existing, AiConversationMessage record,
                                     Long userId, LocalDateTime now) {
        AiConversation update = new AiConversation();
        update.setConversationId(existing.getConversationId());
        update.setMessageCount((int) recordsCount(existing.getConversationId()));
        update.setLastMessageTime(now);
        // 首条用户消息到达时，用它的前 24 字替换默认标题；用户手动改过的标题不动。
        if ("user".equals(record.getRole()) && isDefaultTitle(existing.getTitle())) {
            update.setTitle(trimTitle(record.getContent()));
        }
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(userId);
        update.setUpdatedTime(now);
        conversationMapper.updateById(update);
    }

    private long recordsCount(Long conversationId) {
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<AiConversationMessage>()
                .eq(AiConversationMessage::getConversationId, conversationId)
                .eq(AiConversationMessage::getDeletedFlag, 0));
        return count == null ? 0L : count;
    }

    private boolean isDefaultTitle(String title) {
        return !StringUtils.hasText(title) || DEFAULT_TITLE.equals(title.trim());
    }

    /**
     * 读取会话并校验归属：不存在按参数错误，非本人按业务异常拒绝。
     */
    private AiConversation requireOwned(Long conversationId, Long userId) {
        requireUser(userId);
        if (conversationId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "会话ID不能为空");
        }
        AiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || Integer.valueOf(1).equals(conversation.getDeletedFlag())) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "会话不存在或已删除");
        }
        if (!userId.equals(conversation.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该会话");
        }
        return conversation;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "未获取到当前登录用户");
        }
    }

    private String normalizeRole(String role) {
        String normalized = role.trim().toLowerCase(java.util.Locale.ROOT);
        return "assistant".equals(normalized) ? "assistant" : "user";
    }

    private String trimTitle(String text) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (!StringUtils.hasText(value)) {
            return DEFAULT_TITLE;
        }
        return value.length() <= TITLE_MAX_CHARS ? value : value.substring(0, TITLE_MAX_CHARS);
    }

    /**
     * 复制消息字段，避免调用方传入的对象被回填出库内主键以外的脏值。
     */
    private AiConversationMessage copyOf(AiConversationMessage source) {
        AiConversationMessage target = new AiConversationMessage();
        target.setContent(source.getContent());
        target.setBlocksJson(source.getBlocksJson());
        target.setSuggestionsJson(source.getSuggestionsJson());
        target.setCommandJson(source.getCommandJson());
        target.setCommandId(source.getCommandId());
        target.setSequenceJson(source.getSequenceJson());
        target.setIntent(source.getIntent());
        target.setQueryTime(source.getQueryTime());
        target.setFiltersJson(source.getFiltersJson());
        target.setModel(source.getModel());
        target.setElapsedMs(source.getElapsedMs());
        target.setClientMessageId(source.getClientMessageId());
        return target;
    }

    /**
     * 按 (会话, 客户端消息标识) 找回已落库的记录。
     *
     * <p>clientMessageId 为空时不做判重：那是没带标识的一次性调用，重发就该按新消息处理。</p>
     */
    private AiConversationMessage existingWithClientId(Long conversationId, String clientMessageId) {
        if (!StringUtils.hasText(clientMessageId)) {
            return null;
        }
        return messageMapper.selectOne(new LambdaQueryWrapper<AiConversationMessage>()
                .eq(AiConversationMessage::getConversationId, conversationId)
                .eq(AiConversationMessage::getClientMessageId, clientMessageId.trim())
                .eq(AiConversationMessage::getDeletedFlag, 0)
                .last("LIMIT 1"));
    }

    private int page(Integer current) {
        return current == null || current < 1 ? 1 : current;
    }

    private int pageSize(Integer size) {
        return size == null || size < 1 ? 10 : Math.min(size, 200);
    }

    private int messageSize(Integer size) {
        return size == null || size < 1 ? DEFAULT_MESSAGE_SIZE : Math.min(size, MAX_MESSAGE_SIZE);
    }
}
