package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.entity.ai.AiConversation;
import com.neuedu.ems.entity.ai.AiConversationMessage;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.ai.AiConversationMapper;
import com.neuedu.ems.mapper.ai.AiConversationMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 对话历史服务单元测试：会话归属校验、消息序号、计数与标题维护、逻辑删除。
 *
 * <p>Mapper 用内存表代替，重点是验证服务层的规则：序号在服务里算且连续，
 * 首条用户消息成为标题，非本人访问一律抛异常，删除只改 deleted_flag。</p>
 */
class AiConversationServiceImplTest {

    private static final Long OWNER = 7L;
    private static final Long OTHER = 99L;

    /** 匹配 wrapper SQL 里的「列 运算符 值」，值可以是 #{...} 占位符，也可以是字面量（如 deleted_flag = 0）。 */
    private static final java.util.regex.Pattern CONDITION_PATTERN =
            java.util.regex.Pattern.compile("(\\w+)\\s*(=|<>|>=|<=|>|<|LIKE)\\s*(#\\{[\\w.]+}|'[^']*'|[\\w.-]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 匹配 wrapper 末尾的 LIMIT n。 */
    private static final java.util.regex.Pattern LIMIT_PATTERN =
            java.util.regex.Pattern.compile("LIMIT\\s+(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private AiConversationServiceImpl service;
    private AiConversationMapper conversationMapper;
    private AiConversationMessageMapper messageMapper;

    /** 内存会话表。 */
    private final Map<Long, AiConversation> conversations = new LinkedHashMap<>();
    /** 内存消息表。 */
    private final List<AiConversationMessage> messages = new ArrayList<>();

    private long conversationSeq = 0L;
    private long messageSeq = 0L;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 取参数前要先注册实体元数据，否则报 lambda cache 缺失。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "aiConversation"),
                AiConversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "aiConversationMessage"),
                AiConversationMessage.class);
        conversations.clear();
        messages.clear();
        conversationSeq = 0L;
        messageSeq = 0L;
        conversationMapper = mock(AiConversationMapper.class);
        messageMapper = mock(AiConversationMessageMapper.class);
        stubConversationMapper();
        stubMessageMapper();
        service = new AiConversationServiceImpl(conversationMapper, messageMapper);
    }

    @Test
    void createStartsEmptyAndOwnedByUser() {
        AiConversation created = service.create(OWNER, null, null);

        assertNotNull(created.getConversationId());
        assertEquals(OWNER, created.getUserId());
        assertEquals(0, created.getMessageCount());
        assertEquals("新对话", created.getTitle());
        assertEquals("general", created.getCapability());
        assertEquals(0, created.getArchived());
        assertEquals(0, created.getDeletedFlag());
        assertTrue(created.getConversationNo().startsWith("AIC-"));
    }

    @Test
    void appendKeepsSequenceAndRefreshesCountAndTime() {
        AiConversation conversation = service.create(OWNER, null, null);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        AiConversationMessage first = service.append(conversation.getConversationId(), OWNER,
                message("user", "本周排程产能怎么样"));
        AiConversationMessage second = service.append(conversation.getConversationId(), OWNER,
                message("assistant", "本周共排产 12 张工单"));

        assertEquals(1, first.getSeqNo());
        assertEquals(2, second.getSeqNo());
        AiConversation stored = conversations.get(conversation.getConversationId());
        assertEquals(2, stored.getMessageCount());
        assertNotNull(stored.getLastMessageTime());
        assertTrue(stored.getLastMessageTime().isAfter(before));
        // 首条用户消息的前 24 字成为标题，不是默认的「新对话」。
        assertEquals("本周排程产能怎么样", stored.getTitle());
    }

    @Test
    void retryWithSameClientMessageIdDoesNotAppendTwice() {
        AiConversation conversation = service.create(OWNER, null, null);
        Long id = conversation.getConversationId();

        AiConversationMessage first = service.append(id, OWNER, message("user", "这个月控制板收发存", "msg-1"));
        AiConversationMessage retried = service.append(id, OWNER, message("user", "这个月控制板收发存", "msg-1"));

        // 重试命中已落库的那条，序号与主键都不变，内存表里只有一条。
        assertEquals(first.getMessageId(), retried.getMessageId());
        assertEquals(first.getSeqNo(), retried.getSeqNo());
        assertEquals(1, messages.size());
        assertEquals(1, conversations.get(id).getMessageCount());
    }

    @Test
    void differentClientMessageIdStillAppendsSeparately() {
        AiConversation conversation = service.create(OWNER, null, null);
        Long id = conversation.getConversationId();

        service.append(id, OWNER, message("user", "这个月控制板收发存", "msg-1"));
        service.append(id, OWNER, message("user", "这个月控制板收发存", "msg-2"));
        // 用户真的又问了同一句，没有复用标识时按两条算。
        service.append(id, OWNER, message("user", "这个月控制板收发存"));

        assertEquals(3, messages.size());
        assertEquals(3, conversations.get(id).getMessageCount());
    }

    @Test
    void titleComesFromFirstUserMessageAndStaysStable() {        AiConversation conversation = service.create(OWNER, null, null);
        service.append(conversation.getConversationId(), OWNER, message("user", "这个月控制板收发存"));
        service.append(conversation.getConversationId(), OWNER, message("user", "那上周呢"));

        assertEquals("这个月控制板收发存", conversations.get(conversation.getConversationId()).getTitle());
    }

    @Test
    void longTitleIsTruncatedToTwentyFourChars() {
        AiConversation conversation = service.create(OWNER, null, null);
        service.append(conversation.getConversationId(), OWNER,
                message("user", "请帮我查一下这个月控制板所有仓库的收发存明细和期初期末数量"));

        assertEquals(24, conversations.get(conversation.getConversationId()).getTitle().length());
    }

    @Test
    void otherUserCannotReadRenameOrRemove() {
        AiConversation conversation = service.create(OWNER, "我的会话", "general");
        Long id = conversation.getConversationId();

        assertThrows(BusinessException.class, () -> service.detail(id, OTHER));
        assertThrows(BusinessException.class, () -> service.rename(id, OTHER, "改标题"));
        assertThrows(BusinessException.class, () -> service.remove(id, OTHER));
        assertThrows(BusinessException.class, () -> service.append(id, OTHER, message("user", "偷看")));
        assertThrows(BusinessException.class, () -> service.recentMessages(id, OTHER, 10));

        // 被拒绝的写操作不能留下副作用。
        assertEquals("我的会话", conversations.get(id).getTitle());
        assertEquals(0, conversations.get(id).getDeletedFlag());
        assertTrue(messages.isEmpty());
    }

    @Test
    void missingConversationIsNotFound() {
        assertThrows(BusinessException.class, () -> service.detail(123456L, OWNER));
    }

    @Test
    void anonymousUserIsRejected() {
        assertThrows(BusinessException.class, () -> service.page(null, null, false, 1, 10));
        assertThrows(BusinessException.class, () -> service.create(null, "标题", null));
    }

    @Test
    void removeHidesConversationFromPageAndMessages() {
        AiConversation conversation = service.create(OWNER, "待删会话", null);
        Long id = conversation.getConversationId();
        service.append(id, OWNER, message("user", "库存还有多少"));

        service.remove(id, OWNER);

        assertEquals(1, conversations.get(id).getDeletedFlag());
        assertTrue(messages.stream().allMatch(item -> Objects.equals(1, item.getDeletedFlag())));
        Page<AiConversation> page = service.page(OWNER, null, false, 1, 10);
        assertEquals(0L, page.getTotal());
    }

    @Test
    void pageFiltersByOwnerAndArchiveFlag() {
        service.create(OWNER, "活跃会话", null);
        AiConversation archived = service.create(OWNER, "归档会话", null);
        service.archive(archived.getConversationId(), OWNER, true);
        service.create(OTHER, "别人的会话", null);

        Page<AiConversation> active = service.page(OWNER, null, false, 1, 10);
        Page<AiConversation> archivedPage = service.page(OWNER, null, true, 1, 10);

        assertEquals(1L, active.getTotal());
        assertEquals("活跃会话", active.getRecords().get(0).getTitle());
        assertEquals(1L, archivedPage.getTotal());
        assertEquals("归档会话", archivedPage.getRecords().get(0).getTitle());
    }

    @Test
    void pageKeywordMatchesTitle() {
        service.create(OWNER, "控制板收发存", null);
        service.create(OWNER, "排程产能", null);

        Page<AiConversation> page = service.page(OWNER, "控制板", false, 1, 10);

        assertEquals(1L, page.getTotal());
        assertEquals("控制板收发存", page.getRecords().get(0).getTitle());
    }

    @Test
    void recentMessagesReturnOldestFirstAndLimited() {
        AiConversation conversation = service.create(OWNER, null, null);
        Long id = conversation.getConversationId();
        service.append(id, OWNER, message("user", "一"));
        service.append(id, OWNER, message("assistant", "二"));
        service.append(id, OWNER, message("user", "三"));

        List<AiConversationMessage> recent = service.recentMessages(id, OWNER, 2);

        assertEquals(2, recent.size());
        assertEquals("二", recent.get(0).getContent());
        assertEquals("三", recent.get(1).getContent());
    }

    @Test
    void detailReturnsMessagesWithTotal() {
        AiConversation conversation = service.create(OWNER, null, null);
        Long id = conversation.getConversationId();
        service.append(id, OWNER, message("user", "这笔订单做到哪了 PO-2026100101"));
        service.append(id, OWNER, message("assistant", "已完工 60%"));

        Map<String, Object> detail = service.detail(id, OWNER);

        assertEquals(conversation.getConversationId(), ((AiConversation) detail.get("conversation")).getConversationId());
        assertEquals(2L, detail.get("messageTotal"));
        @SuppressWarnings("unchecked")
        List<AiConversationMessage> rows = (List<AiConversationMessage>) detail.get("messages");
        assertEquals("这笔订单做到哪了 PO-2026100101", rows.get(0).getContent());
    }

    @Test
    void renameRejectsBlankTitle() {
        AiConversation conversation = service.create(OWNER, "原标题", null);
        assertThrows(BusinessException.class,
                () -> service.rename(conversation.getConversationId(), OWNER, "   "));
        assertEquals("原标题", conversations.get(conversation.getConversationId()).getTitle());
    }

    @Test
    void renameKeepsOwnerAndUpdatesTitle() {
        AiConversation conversation = service.create(OWNER, "原标题", null);
        service.rename(conversation.getConversationId(), OWNER, "  新   标题  ");

        AiConversation stored = conversations.get(conversation.getConversationId());
        assertEquals("新 标题", stored.getTitle());
        assertEquals(OWNER, stored.getUserId());
    }

    @Test
    void appendRejectsBlankRole() {
        AiConversation conversation = service.create(OWNER, null, null);
        AiConversationMessage invalid = new AiConversationMessage();
        invalid.setContent("没有角色");
        assertThrows(BusinessException.class,
                () -> service.append(conversation.getConversationId(), OWNER, invalid));
    }

    @Test
    void appendCopiesDisplayPayload() {
        AiConversation conversation = service.create(OWNER, null, null);
        AiConversationMessage payload = message("assistant", "控制板收发存");
        payload.setBlocksJson("[{\"title\":\"控制板收发存\"}]");
        payload.setSuggestionsJson("[\"那上周呢\"]");
        payload.setIntent("inventory_flow");
        payload.setModel("RULE_ROUTER");
        payload.setElapsedMs(123L);
        payload.setQueryTime(LocalDateTime.of(2026, 10, 25, 9, 0));

        AiConversationMessage saved = service.append(conversation.getConversationId(), OWNER, payload);

        AiConversationMessage stored = messages.stream()
                .filter(item -> item.getMessageId().equals(saved.getMessageId()))
                .findFirst().orElseThrow();
        assertEquals("[{\"title\":\"控制板收发存\"}]", stored.getBlocksJson());
        assertEquals("inventory_flow", stored.getIntent());
        assertEquals("RULE_ROUTER", stored.getModel());
        assertEquals(123L, stored.getElapsedMs());
        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 0), stored.getQueryTime());
        assertNull(stored.getCommandId());
    }

    private AiConversationMessage message(String role, String content) {
        AiConversationMessage item = new AiConversationMessage();
        item.setRole(role);
        item.setContent(content);
        return item;
    }

    private AiConversationMessage message(String role, String content, String clientMessageId) {
        AiConversationMessage item = message(role, content);
        item.setClientMessageId(clientMessageId);
        return item;
    }

    private void stubConversationMapper() {
        when(conversationMapper.insert(any(AiConversation.class))).thenAnswer(invocation -> {
            AiConversation item = invocation.getArgument(0);
            item.setConversationId(++conversationSeq);
            conversations.put(item.getConversationId(), item);
            return 1;
        });
        when(conversationMapper.selectById(any())).thenAnswer(invocation ->
                conversations.get(((Number) invocation.getArgument(0)).longValue()));
        when(conversationMapper.updateById(any(AiConversation.class))).thenAnswer(invocation -> {
            AiConversation update = invocation.getArgument(0);
            AiConversation existing = conversations.get(update.getConversationId());
            if (existing == null) {
                return 0;
            }
            if (update.getTitle() != null) {
                existing.setTitle(update.getTitle());
            }
            if (update.getMessageCount() != null) {
                existing.setMessageCount(update.getMessageCount());
            }
            if (update.getLastMessageTime() != null) {
                existing.setLastMessageTime(update.getLastMessageTime());
            }
            if (update.getArchived() != null) {
                existing.setArchived(update.getArchived());
            }
            if (update.getDeletedFlag() != null) {
                existing.setDeletedFlag(update.getDeletedFlag());
            }
            return 1;
        });
        when(conversationMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<AiConversation> page = invocation.getArgument(0);
            Wrapper<AiConversation> wrapper = invocation.getArgument(1);
            List<AiConversation> rows = conversations.values().stream()
                    .filter(item -> matches(wrapper, item))
                    .sorted(Comparator.comparing(AiConversation::getConversationId))
                    .toList();
            page.setTotal(rows.size());
            page.setRecords(rows);
            return page;
        });
    }

    private void stubMessageMapper() {
        when(messageMapper.insert(any(AiConversationMessage.class))).thenAnswer(invocation -> {
            AiConversationMessage item = invocation.getArgument(0);
            item.setMessageId(++messageSeq);
            messages.add(item);
            return 1;
        });
        when(messageMapper.selectCount(any())).thenAnswer(invocation -> {
            Wrapper<AiConversationMessage> wrapper = invocation.getArgument(0);
            return (long) messages.stream().filter(item -> matches(wrapper, item)).count();
        });
        when(messageMapper.selectOne(any())).thenAnswer(invocation -> {
            Wrapper<AiConversationMessage> wrapper = invocation.getArgument(0);
            return messages.stream()
                    .filter(item -> matches(wrapper, item))
                    .max(Comparator.comparing(AiConversationMessage::getSeqNo))
                    .orElse(null);
        });
        when(messageMapper.selectList(any())).thenAnswer(invocation -> {
            Wrapper<AiConversationMessage> wrapper = invocation.getArgument(0);
            List<AiConversationMessage> rows = messages.stream()
                    .filter(item -> matches(wrapper, item))
                    .sorted(Comparator.comparing(AiConversationMessage::getSeqNo).reversed())
                    .limit(limitOf(wrapper))
                    .toList();
            return new ArrayList<>(rows);
        });
        when(messageMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<AiConversationMessage> page = invocation.getArgument(0);
            Wrapper<AiConversationMessage> wrapper = invocation.getArgument(1);
            List<AiConversationMessage> rows = messages.stream()
                    .filter(item -> matches(wrapper, item))
                    .sorted(Comparator.comparing(AiConversationMessage::getSeqNo))
                    .toList();
            page.setTotal(rows.size());
            page.setRecords(rows);
            return page;
        });
        when(messageMapper.update(any(AiConversationMessage.class), any())).thenAnswer(invocation -> {
            AiConversationMessage update = invocation.getArgument(0);
            Wrapper<AiConversationMessage> wrapper = invocation.getArgument(1);
            int affected = 0;
            for (AiConversationMessage item : messages) {
                if (matches(wrapper, item) && update.getDeletedFlag() != null) {
                    item.setDeletedFlag(update.getDeletedFlag());
                    affected++;
                }
            }
            return affected;
        });
    }

    /**
     * 内存表上的条件匹配：把 wrapper 生成的 SQL 片段按「列 运算符 占位符」拆开，
     * 再从 paramNameValuePairs 里按占位符名取真实值，逐条判定。只支持服务用到的
     * =、LIKE 与 ORDER/LIMIT，够覆盖被测路径即可。
     */
    private boolean matches(Wrapper<?> wrapper, Object row) {
        if (wrapper == null) {
            return true;
        }
        String sql = wrapper.getSqlSegment();
        if (sql == null || sql.isBlank()) {
            return true;
        }
        Map<String, Object> params = wrapper instanceof LambdaQueryWrapper<?> lambda
                ? lambda.getParamNameValuePairs()
                : Map.of();
        java.util.regex.Matcher matcher = CONDITION_PATTERN.matcher(sql);
        while (matcher.find()) {
            String column = matcher.group(1);
            String operator = matcher.group(2).toUpperCase(java.util.Locale.ROOT);
            String token = matcher.group(3);
            Object expected = token.startsWith("#{") ? paramValue(params, token) : literal(token);
            Object actual = columnOf(row, column);
            if (actual == null) {
                // 未知列（如 ORDER BY 里出现的列）不参与过滤。
                continue;
            }
            if (!satisfies(actual, operator, expected)) {
                return false;
            }
        }
        return true;
    }

    /** 从 paramNameValuePairs 里按占位符名取值；MyBatis-Plus 的键是 MPGENVAL1 这种。 */
    private Object paramValue(Map<String, Object> params, String token) {
        String name = token.substring(2, token.length() - 1);
        Object value = params.get(name);
        if (value != null) {
            return value;
        }
        // 形如 #{ew.paramNameValuePairs.MPGENVAL1} 时取最后一段。
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : params.get(name.substring(dot + 1));
    }

    /** 把 SQL 字面量转成对应类型：带引号按字符串，数字按 Long。 */
    private Object literal(String token) {
        if (token.startsWith("'") && token.endsWith("'")) {
            return token.substring(1, token.length() - 1);
        }
        try {
            return Long.valueOf(token);
        } catch (NumberFormatException e) {
            return token;
        }
    }

    /** 解析 wrapper 末尾的 LIMIT n，没有则不限。 */
    private long limitOf(Wrapper<?> wrapper) {
        String sql = wrapper == null ? null : wrapper.getSqlSegment();
        if (sql == null) {
            return Long.MAX_VALUE;
        }
        java.util.regex.Matcher matcher = LIMIT_PATTERN.matcher(sql);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : Long.MAX_VALUE;
    }

    /** 列名到内存对象字段的映射，未识别的列不参与过滤。 */
    private Object columnOf(Object row, String column) {
        if (row instanceof AiConversation conversation) {
            return switch (column) {
                case "user_id" -> conversation.getUserId();
                case "archived" -> conversation.getArchived();
                case "deleted_flag" -> conversation.getDeletedFlag();
                case "title" -> conversation.getTitle();
                case "conversation_id" -> conversation.getConversationId();
                default -> null;
            };
        }
        if (row instanceof AiConversationMessage message) {
            return switch (column) {
                case "conversation_id" -> message.getConversationId();
                case "deleted_flag" -> message.getDeletedFlag();
                case "seq_no" -> message.getSeqNo();
                case "client_message_id" -> message.getClientMessageId();
                default -> null;
            };
        }
        return null;
    }

    private boolean satisfies(Object actual, String operator, Object expected) {
        if (actual == null) {
            return false;
        }
        if ("LIKE".equals(operator)) {
            String text = String.valueOf(actual);
            // MyBatis-Plus 的 like 参数自带 % 通配符，比较前先剥掉。
            String keyword = String.valueOf(expected).replace("%", "");
            return text.contains(keyword);
        }
        if (actual instanceof Number left && expected instanceof Number right) {
            long a = left.longValue();
            long b = right.longValue();
            return switch (operator) {
                case "=" -> a == b;
                case "<>" -> a != b;
                case ">" -> a > b;
                case "<" -> a < b;
                case ">=" -> a >= b;
                case "<=" -> a <= b;
                default -> true;
            };
        }
        return "=".equals(operator) ? actual.equals(expected) : true;
    }
}
