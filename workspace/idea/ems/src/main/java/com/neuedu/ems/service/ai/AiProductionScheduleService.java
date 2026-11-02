package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.mfg.CommandRequestService;
import com.neuedu.ems.service.mfg.MfgProductionPlanService;
import com.neuedu.ems.service.mfg.MfgScheduleScenarioService;
import com.neuedu.ems.vo.mfg.ManualScheduleCandidateLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 复用手工排程规则，为对话办理提供单订单推演和确认入口；调用方负责权限校验。 */
@Service
public class AiProductionScheduleService {
    private static final String COMMAND = "AI_SCHEDULE_PRODUCTION_ORDER";
    private final MfgScheduleScenarioService scenarios;
    private final MfgProductionPlanService plans;
    private final CommandRequestService requests;

    public AiProductionScheduleService(MfgScheduleScenarioService scenarios, MfgProductionPlanService plans,
                                       CommandRequestService requests) {
        this.scenarios = scenarios;
        this.plans = plans;
        this.requests = requests;
    }

    /** 只读给出各产线可用时间；不创建排程草稿。 */
    public List<ManualScheduleCandidateLine> preview(Long productionOrderId) {
        if (productionOrderId == null) throw invalid("请指定生产订单");
        return scenarios.listEligibleLinesForOrder(productionOrderId);
    }

    public void validate(Long orderId, Long lineId, LocalDateTime start, LocalDateTime end) {
        scenarios.validatePlanningPlacement(orderId, lineId, start, end);
    }

    /** 确认所展示的具体时段；状态变化或资源冲突由原领域服务拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public MfgProductionPlan confirm(Long orderId, Long lineId, LocalDateTime start, LocalDateTime end,
                                     Long operatorId, String requestKey) {
        requireSchedulable(orderId, lineId, start, end, operatorId, requestKey);
        String digest = requests.digest(orderId + "|" + lineId + "|" + start + "|" + end + "|" + operatorId);
        Long priorId = requests.replayed(COMMAND, requestKey, digest);
        if (priorId != null) {
            MfgProductionPlan prior = plans.getPlanById(priorId);
            if (prior == null) throw invalid("原生产计划不存在，请人工核对");
            return prior;
        }
        scenarios.validatePlanningPlacement(orderId, lineId, start, end);
        var draft = scenarios.openManualDraft(operatorId);
        var item = scenarios.addManualItem(draft.getScenarioId(), draft.getVersion(), orderId, lineId, operatorId);
        var updatedDraft = scenarios.getScenarioById(draft.getScenarioId());
        if (!Objects.equals(start, item.getPlannedStartTime()) || !Objects.equals(end, item.getPlannedEndTime())) {
            item = scenarios.updateManualPlacement(draft.getScenarioId(), item.getScheduleItemId(),
                    updatedDraft.getVersion(), item.getVersion(), lineId, start, end, "AI 工作台确认排程", operatorId);
            updatedDraft = scenarios.getScenarioById(draft.getScenarioId());
        }
        MfgProductionPlan plan = scenarios.confirmManualItem(draft.getScenarioId(), item.getScheduleItemId(),
                updatedDraft.getVersion(), item.getVersion(), "AI 工作台确认排程", operatorId);
        if (plan == null || plan.getPlanId() == null) throw invalid("排程未生成生产计划");
        requests.record(COMMAND, requestKey, "PRODUCTION_ORDER", orderId, digest, plan.getPlanId(),
                operatorId, null, LocalDateTime.now());
        return plan;
    }

    /** 排程条件：订单、产线、时段、操作人、请求键齐全，且结束时间晚于开始时间。 */
    private void requireSchedulable(Long orderId, Long lineId, LocalDateTime start, LocalDateTime end,
                                    Long operatorId, String requestKey) {
        if (anyMissing(orderId, lineId, start, end, operatorId, requestKey) || !end.isAfter(start)) {
            throw invalid("排程条件不完整");
        }
    }

    /** 时段、归属、请求键三组条件逐组判断，任何一组缺项都不能排程。 */
    private static boolean anyMissing(Long orderId, Long lineId, LocalDateTime start, LocalDateTime end,
                                      Long operatorId, String requestKey) {
        boolean missingSlot = lineId == null || start == null || end == null;
        boolean missingOwner = orderId == null || operatorId == null || !StringUtils.hasText(requestKey);
        return missingSlot || missingOwner;
    }

    private BusinessException invalid(String reason) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), reason);
    }
}
