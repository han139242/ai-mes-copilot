package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.common.AiResponse;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgScheduleItem;
import com.neuedu.ems.entity.mfg.MfgScheduleScenario;
import com.neuedu.ems.vo.mfg.ManualScheduleBoard;
import com.neuedu.ems.vo.mfg.ManualScheduleCandidateLine;
import com.neuedu.ems.vo.mfg.ManualScheduleDurationPreview;
import com.neuedu.ems.vo.mfg.ScheduleSimulationComparison;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能排程方案计算服务。
 */
public interface MfgScheduleScenarioService extends IService<MfgScheduleScenario> {

    Page<MfgScheduleScenario> pageScenarios(Integer current, Integer size, String keyword, String status);

    MfgScheduleScenario getScenarioById(Long scenarioId);

    List<MfgScheduleItem> listScenarioItems(Long scenarioId);

    MfgScheduleScenario openManualDraft(Long operatorId);

    ManualScheduleBoard getManualScheduleBoard(Long operatorId);

    AiResponse generateManualScheduleBoardAdvice(Long operatorId);

    MfgScheduleScenario generateManualAiBatch(Long operatorId);

    MfgScheduleScenario withdrawManualAiBatch(Long scenarioId, Integer version, Long operatorId);

    MfgScheduleScenario confirmManualAiBatch(Long scenarioId, Integer version, String remark, Long operatorId);

    List<ManualScheduleCandidateLine> listEligibleLines(Long scenarioId, Long productionOrderId);

    List<ManualScheduleCandidateLine> listEligibleLinesForOrder(Long productionOrderId);

    ManualScheduleCandidateLine validatePlanningPlacement(Long productionOrderId, Long lineId,
                                                           LocalDateTime plannedStartTime,
                                                           LocalDateTime plannedEndTime);

    /**
     * 按产线班次、产能和工艺工时，从给定的计划开始时间推算结束时间。
     * 只往后推，不会返回早于开始时间的结束时间。
     */
    ManualScheduleDurationPreview previewManualDuration(Long scenarioId, Long scheduleItemId, Long lineId,
                                                        LocalDateTime plannedStartTime);

    /**
     * 执行前排程推演：只读比较几笔口径下这笔订单的开工时间与完工时间，不写排程明细也不生成计划。
     * 结论是「按现有班次与产能最早能开工、按产能做完」，不含排队、换型、待料等待，不能当交期承诺。
     */
    ScheduleSimulationComparison simulateOrderSchedule(Long productionOrderId);

    MfgScheduleItem addManualItem(Long scenarioId, Integer scenarioVersion, Long productionOrderId,
                                  Long lineId, Long operatorId);

    MfgScheduleItem updateManualPlacement(Long scenarioId, Long scheduleItemId, Integer scenarioVersion,
                                          Integer itemVersion, Long lineId, LocalDateTime plannedStartTime,
                                          LocalDateTime plannedEndTime, String remark, Long operatorId);

    MfgScheduleScenario removeManualItem(Long scenarioId, Long scheduleItemId, Integer scenarioVersion,
                                         Integer itemVersion, Long operatorId);

    MfgProductionPlan confirmManualItem(Long scenarioId, Long scheduleItemId, Integer scenarioVersion,
                                        Integer itemVersion, String remark, Long operatorId);

    MfgScheduleScenario validateManualDraft(Long scenarioId, Integer scenarioVersion, Long operatorId);

    AiResponse generateOrderAiAdvice(Long scenarioId, Long productionOrderId, Integer scenarioVersion);

    MfgScheduleScenario calculateScenario(MfgScheduleScenario request);

    MfgScheduleScenario confirmScenario(Long scenarioId, Integer version, Long operatorId, String remark);

    AiResponse generateAiAdvice(Long scenarioId, Integer version);
}
