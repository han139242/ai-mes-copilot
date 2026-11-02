package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.*;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.*;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import com.neuedu.ems.service.sys.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** 无检验单的工序报工不良，必须说明处理原因；有质量流程时继续使用质量处置。 */
@Service
@RequiredArgsConstructor
public class TaskDefectResolutionService {
    private final MfgTaskMapper tasks;
    private final MfgWorkOrderMapper orders;
    private final MfgWorkReportMapper reports;
    private final QmsInspectionOrderMapper inspections;
    private final ProcessRouteItemMapper routeItems;
    private final MfgTaskService taskService;
    private final MfgWorkOrderService workOrderService;
    private final ExecutionSnapshotPolicy snapshots;
    private final DataScopeService scope;

    @Transactional(rollbackFor = Exception.class)
    public List<MfgTask> resolve(Long taskId, String disposition, String reason, Long userId) {
        requireFullScope();
        requireDisposition(disposition, reason);
        var source = lockFinishedTask(taskId);
        var previous = findPreviousCompensation(taskId);
        if (!previous.isEmpty()) {
            return reusePreviousCompensation(previous, disposition);
        }
        requireNoQualityCompensation(taskId);
        BigDecimal defect = requireApprovedDefect(taskId);
        var order = requireSourceOrder(source);
        String note = buildNote(defect, disposition, reason);
        if ("REWORK".equals(disposition)) {
            return List.of(taskService.createDefectCompensationTask(taskId, taskId, defect, "REWORK", userId, note));
        }
        return createSupplementTasks(source, order, defect, userId, note);
    }

    /** 不良处置会改生产数据，只允许拥有全量生产数据权限的账号操作。 */
    private void requireFullScope() {
        if (scope.isRestricted()) {
            throw invalid("报工不良处置需要全部生产数据权限");
        }
    }

    /** 处置方式只能是返工或报废补产，且必须写明原因（不超过 500 字）。 */
    private static void requireDisposition(String disposition, String reason) {
        String value = disposition == null ? "" : disposition;
        boolean dispositionOk = Set.of("REWORK", "SCRAP").contains(value);
        boolean reasonOk = reason != null && !reason.isBlank() && reason.length() <= 500;
        if (!dispositionOk || !reasonOk) {
            throw invalid("请选择返工或报废补产并填写处理原因（最多500字）");
        }
    }

    /** 锁住任务行，且任务必须已全部报工完成。 */
    private MfgTask lockFinishedTask(Long taskId) {
        var source = tasks.selectOne(new LambdaQueryWrapper<MfgTask>().eq(MfgTask::getTaskId, taskId)
                .eq(MfgTask::getDeletedFlag, 0).last("FOR UPDATE"));
        if (source == null || !"FINISHED".equals(source.getStatus())) {
            throw invalid("请在任务全部报工审核完成后处理不良");
        }
        return source;
    }

    /** 该任务此前是否已经生成过补偿任务。 */
    private List<MfgTask> findPreviousCompensation(Long taskId) {
        return tasks.selectList(new LambdaQueryWrapper<MfgTask>().eq(MfgTask::getDefectResolutionTaskId, taskId)
                .isNull(MfgTask::getSourceNcrId).eq(MfgTask::getDeletedFlag, 0));
    }

    /** 已生成过补偿任务：处置方式一致就直接返回原单，不一致说明重复处置。 */
    private static List<MfgTask> reusePreviousCompensation(List<MfgTask> previous, String disposition) {
        String expected = "REWORK".equals(disposition) ? "REWORK" : "SUPPLEMENT";
        if (previous.stream().anyMatch(t -> !expected.equals(t.getTaskType()))) {
            throw invalid("已按另一方式生成补偿任务，不能重复处置");
        }
        return previous;
    }

    /** 已经走了质量补偿或已有未关闭检验单的，不允许再生成补产任务。 */
    private void requireNoQualityCompensation(Long taskId) {
        long qualityCompensation = tasks.selectCount(new LambdaQueryWrapper<MfgTask>().eq(MfgTask::getSourceTaskId, taskId)
                .isNotNull(MfgTask::getSourceNcrId).eq(MfgTask::getDeletedFlag, 0));
        if (qualityCompensation > 0) {
            throw invalid("本任务已有质量补偿安排，请继续原质量处置流程");
        }
        long openInspections = inspections.selectCount(new LambdaQueryWrapper<QmsInspectionOrder>()
                .eq(QmsInspectionOrder::getTaskId, taskId).eq(QmsInspectionOrder::getDeletedFlag, 0)
                .ne(QmsInspectionOrder::getStatus, "CLOSED"));
        if (openInspections > 0) {
            throw invalid("本任务已有检验单，请在质量检验及不合格处置中完成处理，不能重复生成补产");
        }
    }

    /** 汇总已审核的不良数量；还有未审核报工或没有不良时都不允许处置。 */
    private BigDecimal requireApprovedDefect(Long taskId) {
        long submitted = reports.selectCount(new LambdaQueryWrapper<MfgWorkReport>().eq(MfgWorkReport::getTaskId, taskId)
                .eq(MfgWorkReport::getDeletedFlag, 0).eq(MfgWorkReport::getAuditStatus, "SUBMITTED"));
        if (submitted > 0) {
            throw invalid("请先审核完本任务所有报工，再处理不良");
        }
        BigDecimal defect = reports.selectList(new LambdaQueryWrapper<MfgWorkReport>().eq(MfgWorkReport::getTaskId, taskId)
                .eq(MfgWorkReport::getDeletedFlag, 0).eq(MfgWorkReport::getAuditStatus, "APPROVED"))
                .stream().map(r -> r.getDefectQuantity() == null ? BigDecimal.ZERO : r.getDefectQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (defect.signum() <= 0) {
            throw invalid("任务没有已审核的不良数量");
        }
        return defect;
    }

    /** 取来源工单，缺了就没法补产。 */
    private MfgWorkOrder requireSourceOrder(MfgTask source) {
        var order = orders.selectById(source.getWorkOrderId());
        if (order == null) {
            throw invalid("来源工单不存在");
        }
        return order;
    }

    /** 补偿任务的备注，写清不良数量、处置方式与原因。 */
    private static String buildNote(BigDecimal defect, String disposition, String reason) {
        return "工序报工不良" + defect.stripTrailingZeros().toPlainString() + "，" + disposition + "：" + reason.trim();
    }

    /**
     * 报废补产：从当前工序往前，为每道工序找出唯一的正常任务并各生成一条补产任务。
     *
     * <p>补齐到当前工序即停，然后把新增用料需求一并写回工单。</p>
     */
    private List<MfgTask> createSupplementTasks(MfgTask source, MfgWorkOrder order, BigDecimal defect,
                                                Long userId, String note) {
        List<MfgTask> created = new ArrayList<>();
        Set<Long> usedOperations = new LinkedHashSet<>();
        boolean found = false;
        for (var step : resolveRouteSteps(order)) {
            usedOperations.add(step.getOperationId());
            var original = requireUniqueNormalTask(source, step);
            created.add(taskService.createDefectCompensationTask(
                    original.getTaskId(), source.getTaskId(), defect, "SUPPLEMENT", userId, note));
            if (Objects.equals(step.getOperationId(), source.getOperationId())) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw invalid("工艺路线没有当前任务工序");
        }
        workOrderService.addSupplementMaterialRequirements(order.getWorkOrderId(), defect, userId, note, usedOperations);
        return List.copyOf(created);
    }

    /** 工艺路线明细：快照优先，历史工单回落到路线明细表。 */
    private List<com.neuedu.ems.entity.process.ProcessRouteItem> resolveRouteSteps(MfgWorkOrder order) {
        if (snapshots.isCurrentFormat(order)) {
            return snapshots.routeItems(order);
        }
        return routeItems.selectList(new LambdaQueryWrapper<com.neuedu.ems.entity.process.ProcessRouteItem>()
                .eq(com.neuedu.ems.entity.process.ProcessRouteItem::getRouteId, order.getRouteId())
                .eq(com.neuedu.ems.entity.process.ProcessRouteItem::getDeletedFlag, 0)
                .orderByAsc(com.neuedu.ems.entity.process.ProcessRouteItem::getSequenceNo));
    }

    /** 同一派工下某工序的正常任务必须唯一，否则补产无法定位前序。 */
    private MfgTask requireUniqueNormalTask(MfgTask source, com.neuedu.ems.entity.process.ProcessRouteItem step) {
        var candidates = tasks.selectList(new LambdaQueryWrapper<MfgTask>().eq(MfgTask::getDispatchId, source.getDispatchId())
                .eq(MfgTask::getOperationId, step.getOperationId()).eq(MfgTask::getDeletedFlag, 0)
                .and(w -> w.isNull(MfgTask::getTaskType).or().eq(MfgTask::getTaskType, "NORMAL")));
        if (candidates.size() != 1) {
            throw invalid("无法唯一定位报废补产的前序正常任务，请先核对工单工艺");
        }
        return candidates.get(0);
    }

    private static BusinessException invalid(String message) { return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),message); }
}
