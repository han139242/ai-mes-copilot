package com.neuedu.ems.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ai_task_step AI 多步任务步骤流水。
 *
 * <p>每一步都留状态、尝试次数、结果单据号和耗时，
 * 后续步骤失败时能看清哪些已生效、从哪里可以续跑。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_task_step")
public class AiTaskStep {

    @TableId(value = "step_id", type = IdType.AUTO)
    private Long stepId;
    private Long instanceId;
    private Integer stepNo;
    private String stepLabel;
    private String capability;
    private String targetType;
    private String targetNo;
    /** PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED */
    private String status;
    private Integer attemptCount;
    private String bizNo;
    private Long bizId;
    private String message;
    private Long elapsedMs;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
