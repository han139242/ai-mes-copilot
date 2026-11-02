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
 * ai_alert_scan 预警扫描台账。
 *
 * <p>每一轮扫描落一条：评估了几条规则、看了几个对象、新开/升级/重复/自动关闭各多少。
 * 用于排查"为什么这次没弹窗"——是没扫、扫了没命中、还是命中但被冷却压住了。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_alert_scan")
public class AiAlertScan {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_MANUAL = "MANUAL";

    @TableId(value = "scan_id", type = IdType.AUTO)
    private Long scanId;
    private String scanNo;
    private String triggerType;
    private String status;
    private Integer ruleCount;
    private Integer evaluatedCount;
    private Integer openedCount;
    private Integer escalatedCount;
    private Integer repeatedCount;
    private Integer closedCount;
    private Long elapsedMs;
    private String summary;
    private String errorMessage;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
