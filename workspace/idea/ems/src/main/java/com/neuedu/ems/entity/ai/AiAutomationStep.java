package com.neuedu.ems.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ai_automation_step AI 自动化执行步骤流水，记录每一步做了什么、结果单据是什么。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_automation_step")
public class AiAutomationStep {

    @TableId(value = "step_id", type = IdType.AUTO)
    private Long stepId;
    private Long runId;
    private Integer stepNo;
    private String actionType;
    private String targetType;
    private String targetNo;
    private String status;
    private String message;
    private String bizNo;
    private Long elapsedMs;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
