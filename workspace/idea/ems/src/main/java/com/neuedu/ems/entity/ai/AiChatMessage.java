package com.neuedu.ems.entity.ai;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多轮对话中的单条消息。
 * 只允许 user 和 assistant 两种角色，前端传入的 system 角色会被后端丢弃。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiChatMessage {

    /**
     * 角色：user 或 assistant。
     */
    @Size(max = 16, message = "消息角色长度不能超过16个字符")
    private String role;

    /**
     * 消息内容。
     */
    @Size(max = 2000, message = "单条消息长度不能超过2000字")
    private String content;
}
