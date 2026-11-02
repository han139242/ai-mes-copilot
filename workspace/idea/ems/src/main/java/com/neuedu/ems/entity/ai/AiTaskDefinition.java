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
 * ai_task_definition AI 多步任务定义。
 *
 * <p>一条定义描述「什么触发、按顺序做哪几步、失败重试几次、每小时最多跑几次」。
 * 步骤本身写成 JSON 数组，避免为每种业务链再加一张表。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_task_definition")
public class AiTaskDefinition {

    @TableId(value = "definition_id", type = IdType.AUTO)
    private Long definitionId;
    private String definitionNo;
    private String definitionName;
    /** EVENT 事件触发 / SCHEDULE 定时触发 / MANUAL 手动触发 */
    private String triggerType;
    /** 事件触发时监听的事件键，例如 WMS_INBOUND_PENDING */
    private String triggerEventKey;
    private Integer intervalMinutes;
    /** DRAFT / ENABLED / PAUSED / DISABLED */
    private String status;
    private Integer maxAttempts;
    private Integer maxInstancesPerHour;
    /** 单实例最长执行分钟数，超时收敛为 TIMEOUT */
    private Integer timeoutMinutes;
    /** 单个定义每日最多执行的步骤次数，作为调用量上限 */
    private Integer maxStepCallsPerDay;
    private String stepsJson;
    private LocalDateTime lastRunTime;
    private Integer successCount;
    private Integer failCount;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
