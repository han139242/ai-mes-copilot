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
 * ai_automation_rule AI 自动化规则配置。
 * config_json 保存适用范围：warehouseType 与 materials（空数组表示该仓库类型下全部物料用默认阈值）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_automation_rule")
public class AiAutomationRule {

    @TableId(value = "rule_id", type = IdType.AUTO)
    private Long ruleId;
    private String ruleNo;
    private String ruleName;
    private String ruleType;
    private String status;
    private BigDecimal defaultThreshold;
    private String configJson;
    private Integer intervalMinutes;
    private LocalDateTime lastRunTime;
    private LocalDateTime nextRunTime;
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
