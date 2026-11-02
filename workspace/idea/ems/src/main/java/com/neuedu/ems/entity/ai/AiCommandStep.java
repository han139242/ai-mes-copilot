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
 * ai_command_step AI 写命令步骤流水。
 * 至少两步：校验前置条件、调用领域服务；每步记录开始结束时间与产出单据。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_command_step")
public class AiCommandStep {

    @TableId(value = "step_id", type = IdType.AUTO)
    private Long stepId;
    private Long commandId;
    private Integer stepNo;
    private String capability;
    private String inputDigest;
    private String status;
    private String bizType;
    private String bizNo;
    private Long bizId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long elapsedMs;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
