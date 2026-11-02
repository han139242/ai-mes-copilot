package com.neuedu.ems.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ai_conversation AI 对话会话。
 *
 * <p>一个会话属于一个用户，切换账号后互相看不到。会话列表按最后消息时间倒序，
 * 归档只是把会话从常用列表里挪走，不删数据。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_conversation")
public class AiConversation {

    @TableId(value = "conversation_id", type = IdType.AUTO)
    private Long conversationId;
    private String conversationNo;
    /** 归属用户ID，所有读写都按它校验，不信任前端传值。 */
    private Long userId;
    /** 会话标题，新建时取首条用户消息前 24 字。 */
    private String title;
    /** 所属能力：general/dashboard/scheduling/maintenance/quality/inventory/writing */
    private String capability;
    private Integer messageCount;
    private LocalDateTime lastMessageTime;
    /** 0 正常 / 1 已归档 */
    private Integer archived;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
