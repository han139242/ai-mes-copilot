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
 * ai_alert_rule 预警规则配置。
 *
 * <p>一条规则把「监控哪个指标 + 管哪些对象 + 限度是多少 + 命中后自动做什么」绑在一起。
 * 限度由使用者在前端自行填写并随时修改，不再写死在代码里；同一条规则可以只管一个物料、
 * 一类仓库、一台设备或一条产线，也可以 scopeType=ALL 覆盖该指标下全部对象。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_alert_rule")
public class AiAlertRule {

    /** 适用范围：全部对象。 */
    public static final String SCOPE_ALL = "ALL";
    /** 适用范围：单个物料（scopeValue 可填物料ID或物料编码）。 */
    public static final String SCOPE_MATERIAL = "MATERIAL";
    /** 适用范围：一类仓库（scopeValue 为仓库类型，如 RAW/SEMI_FINISHED）。 */
    public static final String SCOPE_WAREHOUSE_TYPE = "WAREHOUSE_TYPE";
    /** 适用范围：单台设备（scopeValue 为设备ID或设备编码）。 */
    public static final String SCOPE_EQUIPMENT = "EQUIPMENT";
    /** 适用范围：单条产线（scopeValue 为产线ID）。 */
    public static final String SCOPE_LINE = "LINE";

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_DISABLED = "DISABLED";

    /** 低于限度触发。 */
    public static final String COMPARATOR_LT = "LT";
    public static final String COMPARATOR_LTE = "LTE";
    /** 高于限度触发。 */
    public static final String COMPARATOR_GT = "GT";
    public static final String COMPARATOR_GTE = "GTE";

    public static final String ACTION_NOTIFY = "NOTIFY";
    public static final String ACTION_REPLENISH = "REPLENISH";
    public static final String ACTION_EXCEPTION = "EXCEPTION";
    public static final String ACTION_REPAIR = "REPAIR";

    @TableId(value = "rule_id", type = IdType.AUTO)
    private Long ruleId;
    private String ruleNo;
    private String ruleName;
    private String metricCode;
    private String scopeType;
    private String scopeValue;
    private String comparator;
    private BigDecimal warningThreshold;
    private BigDecimal criticalThreshold;
    private String thresholdUnit;
    private String autoActions;
    private Integer acknowledgmentRequired;
    private Integer intervalMinutes;
    private Integer cooldownMinutes;
    private String status;
    private LocalDateTime lastScanTime;
    private Integer hitCount;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
