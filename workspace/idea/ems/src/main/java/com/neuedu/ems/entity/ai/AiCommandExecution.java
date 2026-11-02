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
 * ai_command_execution AI 写命令执行记录。
 * 一条记录对应一次"预览-确认-执行"，confirmation_id 是前端卡片确认时回传的凭据。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_command_execution")
public class AiCommandExecution {

    @TableId(value = "command_id", type = IdType.AUTO)
    private Long commandId;
    private String confirmationId;
    private String idempotencyKey;
    private String requestHash;
    private String conversationId;
    private Long userId;
    private String rawText;
    private String intent;
    private String action;
    private String actionLabel;
    private String targetType;
    private String targetNo;
    private Long targetId;
    private String paramsJson;
    private String previewJson;
    private String impactsJson;
    private String preconditionsJson;
    private String status;
    private String resultJson;
    private String errorMessage;
    private String source;
    private Long elapsedMs;
    private LocalDateTime expiresAt;
    private LocalDateTime executedAt;
    /** 所属多步序列ID，单步命令为空。 */
    private String sequenceId;
    /** 序列内步骤序号，从 1 开始，单步命令为空。 */
    private Integer sequenceStepNo;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
