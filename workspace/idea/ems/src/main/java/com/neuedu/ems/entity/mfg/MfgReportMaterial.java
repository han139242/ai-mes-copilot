package com.neuedu.ems.entity.mfg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * mfg_report_material
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_report_material")
public class MfgReportMaterial {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long reportId;
    private Long materialId;
    private Long batchId;
    private BigDecimal usedQuantity;
    private BigDecimal scrapQuantity;
    /** 对外使用总量含报废；历史used_quantity仍保存非报废部分，避免改写既有库存流水。 */
    @TableField(exist = false)
    private BigDecimal totalUsedQuantity;

    public BigDecimal getTotalUsedQuantity() {
        if (totalUsedQuantity != null) return totalUsedQuantity;
        return (usedQuantity == null ? BigDecimal.ZERO : usedQuantity)
                .add(scrapQuantity == null ? BigDecimal.ZERO : scrapQuantity);
    }

    public boolean hasDeclaredTotalUsedQuantity() {
        return totalUsedQuantity != null;
    }
    private String remark;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
