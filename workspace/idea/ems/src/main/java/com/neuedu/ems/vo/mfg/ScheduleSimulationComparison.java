package com.neuedu.ems.vo.mfg;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行前排程推演对比：同一笔生产订单在几种口径下的排程结果，只读，不写排程明细与计划。
 *
 * <p>推演只复用排程草案同一套班次、产能与工时模型，因此给出的时间是
 * 「按现有班次与产能最早能开工、按产能做完」的口径，不包含排队、换型、待料等待，
 * 不能当作对外交期承诺。缺工时模型、周历或产能系数时在 missing 里说明，不做估算。</p>
 */
@Data
public class ScheduleSimulationComparison {

    private Long productionOrderId;
    private String productionOrderNo;
    private String productName;
    private BigDecimal quantity;
    private LocalDate deliveryDate;
    private String orderStatus;
    /** 推演时刻，说明结果对应哪个时间点的数据。 */
    private LocalDateTime simulatedAt;
    /** 一句话结论，例如「现状口径可在交期内完成，换线可再提前 3 小时」。 */
    private String conclusion;
    private List<Plan> plans;
    /** 推演前提，例如「按冻结工艺路线标准工时折算」「占用当前已排明细与在制计划」。 */
    private List<String> assumptions;
    /** 缺数据说明，例如「产线未配置周历，只按日产能平摊」。 */
    private List<String> missing;

    /**
     * 一种排程口径的结果。feasible 为 false 时 startTime/endTime 为空，blockers 说明卡在哪里。
     */
    @Data
    public static class Plan {
        /** CURRENT / COMPONENT_FIRST / RESOURCE_SHIFT。 */
        private String code;
        private String label;
        private String description;
        private boolean feasible;
        private Long lineId;
        private String lineCode;
        private String lineName;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long workingMinutes;
        private BigDecimal estimatedMinutes;
        /** 是否能在订单交期前完成；交期为空时为 null。 */
        private Boolean meetsDelivery;
        private List<String> blockers;
        private List<String> notes;
    }
}
