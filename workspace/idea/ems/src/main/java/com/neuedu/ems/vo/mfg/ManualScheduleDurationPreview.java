package com.neuedu.ems.vo.mfg;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 按产线班次、产能和工艺工时推算出的排产时段预览。
 * <p>
 * 供排产工作台在用户改计划开始时间后自动回填结束时间，避免凭感觉填一个凑不上的结束时间。
 * 时间是"最早可开工—按产能做完"的口径，与实际确认后的生产计划一致。
 */
@Data
public class ManualScheduleDurationPreview {
    /** 实际可开工时间；若填的开始时间落在非工作时段，会顺延到下一个班次起点。 */
    private LocalDateTime startTime;
    /** 按产能做完该订单的结束时间。 */
    private LocalDateTime endTime;
    /** 区间内的产线有效工作分钟数（已扣除下班与停工时段）。 */
    private Long workingMinutes;
    /** 工艺路线折算的单件工时合计，用于向用户解释"这个订单大概要多久"。 */
    private BigDecimal estimatedMinutes;
    /** 依据说明，例如产能折算或批次老化约束。 */
    private String explanation;
}
