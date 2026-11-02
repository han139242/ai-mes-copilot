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
 * ai_task_instance AI 多步任务实例。
 *
 * <p>instance_key 是幂等键：同一触发源（同一条事件、同一分钟窗口）只允许一个实例，
 * 靠库层唯一索引拦住重复执行，不依赖内存状态。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_task_instance")
public class AiTaskInstance {

    @TableId(value = "instance_id", type = IdType.AUTO)
    private Long instanceId;
    private String instanceNo;
    /** 幂等键：同一触发源只允许一个实例 */
    private String instanceKey;
    private Long definitionId;
    private String definitionNo;
    private String definitionName;
    private String triggerType;
    private String triggerSource;
    /** RUNNING / SUCCEEDED / PARTIAL_FAILED / FAILED / SKIPPED / WAITING_INPUT / CANCELLED / TIMEOUT */
    private String status;
    private Integer currentStepNo;
    /** 断点续跑起始步骤 */
    private Integer resumeStepNo;
    private Integer totalSteps;
    private Integer attemptCount;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private Long elapsedMs;
    private String summary;
    private String errorMessage;
    /** 等待补充的具体原因，状态为 WAITING_INPUT 时填写 */
    private String waitingReason;
    /** 本实例的超时截止时间 */
    private LocalDateTime deadlineTime;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
