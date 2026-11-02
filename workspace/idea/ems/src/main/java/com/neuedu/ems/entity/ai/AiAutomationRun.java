package com.neuedu.ems.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ai_automation_run AI 自动化规则的一次执行记录。
 * run_key 是幂等键（规则+触发窗口），同一键重复触发不会重复执行。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_automation_run")
public class AiAutomationRun {

    @TableId(value = "run_id", type = IdType.AUTO)
    private Long runId;
    private String runNo;
    private String runKey;
    private Long ruleId;
    private String ruleNo;
    private String ruleName;
    private String triggerType;
    private String status;
    private Integer matchedCount;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private Long elapsedMs;
    private String summary;
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
