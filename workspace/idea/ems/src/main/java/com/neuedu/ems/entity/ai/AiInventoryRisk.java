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
 * ai_inventory_risk 库存风险台账。
 * 自动化规则命中后落一条 OPEN 风险，库存恢复后由同一规则回写 CLOSED，形成闭环。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_inventory_risk")
public class AiInventoryRisk {

    @TableId(value = "risk_id", type = IdType.AUTO)
    private Long riskId;
    private String riskNo;
    private Long ruleId;
    private String ruleNo;
    private Long materialId;
    private String materialCode;
    private String materialName;
    private String warehouseType;
    private BigDecimal availableQuantity;
    private BigDecimal thresholdQuantity;
    private BigDecimal shortageQuantity;
    private Long suggestionId;
    private String status;
    private LocalDateTime detectedTime;
    private LocalDateTime closedTime;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
