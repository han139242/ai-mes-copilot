package com.neuedu.ems.controller.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.entity.ai.AiConversation;
import com.neuedu.ems.entity.ai.AiConversationMessage;
import com.neuedu.ems.service.ai.AiConversationService;
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
 * AI 对话历史接口：会话增删改查与消息追加。
 *
 * <p>当前用户一律从 JWT 解析出的 userId 请求属性取，不接受请求体里的 userId，
 * 避免前端传别人的 ID 读到别人的聊天记录。权限由 PermissionCodeResolver 对
 * /api/ai/** 统一要求 ai:assistant:use。</p>
 */
@RestController
@RequestMapping("/api/ai/conversations")
public class AiConversationController {

    private final AiConversationService conversationService;

    public AiConversationController(AiConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** 会话分页：默认查未归档，keyword 按标题模糊匹配。 */
    @GetMapping
    public ResultData page(@RequestParam(required = false) Integer current,
                           @RequestParam(required = false) Integer size,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "false") boolean archived,
                           @RequestAttribute("userId") Long userId) {
        Page<AiConversation> page = conversationService.page(userId, keyword, archived, current, size);
        return ResultData.success(ResultCode.SUCCESS, pageData(page));
    }

    /** 新建会话。 */
    @PostMapping
    public ResultData create(@RequestBody(required = false) CreateRequest request,
                             @RequestAttribute("userId") Long userId) {
        String title = request == null ? null : request.getTitle();
        String capability = request == null ? null : request.getCapability();
        return ResultData.success(ResultCode.SUCCESS, conversationService.create(userId, title, capability));
    }

    /** 会话详情，含消息列表。 */
    @GetMapping("/{conversationId}")
    public ResultData detail(@PathVariable Long conversationId,
                             @RequestParam(required = false) Integer current,
                             @RequestParam(required = false) Integer size,
                             @RequestAttribute("userId") Long userId) {
        return ResultData.success(ResultCode.SUCCESS,
                conversationService.detail(conversationId, userId, current, size));
    }

    /** 重命名会话。 */
    @PutMapping("/{conversationId}/rename")
    public ResultData rename(@PathVariable Long conversationId,
                             @RequestBody(required = false) RenameRequest request,
                             @RequestAttribute("userId") Long userId) {
        conversationService.rename(conversationId, userId, request == null ? null : request.getTitle());
        return ResultData.success(ResultCode.SUCCESS, Map.of("renamed", true));
    }

    /** 归档或取消归档。 */
    @PutMapping("/{conversationId}/archive")
    public ResultData archive(@PathVariable Long conversationId,
                              @RequestBody(required = false) ArchiveRequest request,
                              @RequestAttribute("userId") Long userId) {
        boolean archived = request == null || request.getArchived() == null || request.getArchived();
        conversationService.archive(conversationId, userId, archived);
        return ResultData.success(ResultCode.SUCCESS, Map.of("archived", archived));
    }

    /** 逻辑删除会话及其消息。 */
    @DeleteMapping("/{conversationId}")
    public ResultData remove(@PathVariable Long conversationId,
                             @RequestAttribute("userId") Long userId) {
        conversationService.remove(conversationId, userId);
        return ResultData.success(ResultCode.SUCCESS, Map.of("removed", true));
    }

    /** 追加一条消息，返回落库后的消息（含 seqNo）。 */
    @PostMapping("/{conversationId}/messages")
    public ResultData append(@PathVariable Long conversationId,
                             @RequestBody AiConversationMessage message,
                             @RequestAttribute("userId") Long userId) {
        return ResultData.success(ResultCode.SUCCESS, conversationService.append(conversationId, userId, message));
    }

    /** 取最近若干条消息，供前端恢复上下文或模型续聊。 */
    @GetMapping("/{conversationId}/messages")
    public ResultData messages(@PathVariable Long conversationId,
                               @RequestParam(defaultValue = "50") Integer limit,
                               @RequestAttribute("userId") Long userId) {
        List<AiConversationMessage> messages =
                conversationService.recentMessages(conversationId, userId, limit == null ? 50 : limit);
        return ResultData.success(ResultCode.SUCCESS, messages);
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

    /** 新建会话请求体。 */
    public static class CreateRequest {
        private String title;
        private String capability;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getCapability() {
            return capability;
        }

        public void setCapability(String capability) {
            this.capability = capability;
        }
    }

    /** 重命名请求体。 */
    public static class RenameRequest {
        private String title;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    /** 归档请求体。 */
    public static class ArchiveRequest {
        private Boolean archived;

        public Boolean getArchived() {
            return archived;
        }

        public void setArchived(Boolean archived) {
            this.archived = archived;
        }
    }
}
