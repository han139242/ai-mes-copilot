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
 * ai_alert_action 预警自动动作流水。
 *
 * <p>用户要求的效果是「主动发现 → 自动做能做的步骤 → 找人处理剩下的 → 检查结果」。
 * 每一步自动动作都在这里留一条记录，前端能直接回答"这条预警系统替我做了什么、
 * 剩下什么需要我做"，而不是只给一句笼统的"已通知"。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_alert_action")
public class AiAlertAction {

    public static final String TYPE_NOTIFY = "NOTIFY";
    public static final String TYPE_REPLENISH = "REPLENISH";
    public static final String TYPE_EXCEPTION = "EXCEPTION";
    public static final String TYPE_REPAIR = "REPAIR";
    public static final String TYPE_VERIFY = "VERIFY";

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(value = "action_id", type = IdType.AUTO)
    private Long actionId;
    private Long alertId;
    private Integer stepNo;
    private String actionType;
    private String actionStatus;
    private String targetType;
    private String targetNo;
    private String message;
    private String bizNo;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
