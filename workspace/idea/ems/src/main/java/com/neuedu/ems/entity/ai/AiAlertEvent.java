package com.neuedu.ems.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ai_alert_event 预警事件。
 *
 * <p>同一个规则命中的同一个对象只保留一条未关闭事件：再次命中只更新当前值、最近命中时间和
 * 命中轮次，不重复建事件；条件恢复后由扫描自动置 CLOSED 并写关闭原因。这样前端弹窗不会
 * 因为每轮扫描而重复轰炸，同时又能看到"这件事持续了多久、反复命中多少次"。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_alert_event")
public class AiAlertEvent {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String SEVERITY_INFO = "INFO";
    public static final String SEVERITY_WARNING = "WARNING";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    /** 条件恢复后由扫描自动关闭时写入的关闭原因。 */
    public static final String CLOSE_REASON_RECOVERED = "指标已回到限度内，系统自动复核关闭";

    @TableId(value = "alert_id", type = IdType.AUTO)
    private Long alertId;
    private String alertNo;
    private Long ruleId;
    private String metricCode;
    private String scopeType;
    private String scopeKey;
    private String severity;
    private String status;
    private String title;
    private String summary;
    private BigDecimal currentValue;
    private BigDecimal thresholdValue;
    private String thresholdUnit;
    private String bizType;
    private Long bizId;
    private String bizNo;
    private String routePath;
    private LocalDateTime firstSeenTime;
    private LocalDateTime lastSeenTime;
    /** 最近一次真正发出提醒的时间，用于按规则冷却去重，避免每轮扫描都弹窗。 */
    private LocalDateTime lastNotifiedTime;
    private Integer occurrenceCount;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedTime;
    private Long closedBy;
    private LocalDateTime closedTime;
    private String closeReason;
    private String handledNote;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
