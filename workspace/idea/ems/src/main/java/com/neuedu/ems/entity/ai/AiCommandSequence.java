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
 * ai_command_sequence 一句话多步办业务的序列头。
 *
 * <p>每个步骤仍是一条 {@link AiCommandExecution} 记录，用 sequenceId + sequenceStepNo 串起来；
 * 单步命令不写这张表，所以既有单步链路完全不受影响。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_command_sequence")
public class AiCommandSequence {

    @TableId(value = "sequence_id", type = IdType.INPUT)
    private String sequenceId;
    private String idempotencyKey;
    private String requestHash;
    private String conversationId;
    private Long userId;
    private String rawText;
    private String status;
    private Integer totalSteps;
    private Integer executedSteps;
    private Integer currentStepNo;
    private String planJson;
    private String resultJson;
    private String errorMessage;
    private String source;
    private Long elapsedMs;
    private LocalDateTime expiresAt;
    private LocalDateTime executedAt;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
