package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.entity.mfg.MfgTask;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.mapper.mfg.MfgTaskMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** 只读达标视图：执行结束不等于合格目标完成，不改写原报工或任务执行状态。 */
@Service
@RequiredArgsConstructor
public class TaskFulfillmentViewService {
    private final MfgTaskMapper tasks;
    private final MfgWorkReportMapper reports;

    public void enrich(List<MfgTask> requested) {
        if (requested == null || requested.isEmpty()) return;
        List<Long> workOrderIds=requested.stream().map(MfgTask::getWorkOrderId).filter(Objects::nonNull).distinct().toList();
        if (workOrderIds.isEmpty()) return;
        Map<Long,MfgTask> byId=familyOf(workOrderIds,requested);
        Map<Long,BigDecimal> approved=new HashMap<>(),pending=new HashMap<>();
        collectReported(byId,approved,pending);
        Map<Long,Set<Long>> children=new HashMap<>();
        for(MfgTask candidate:byId.values()) {
            if(!Set.of("REWORK","SUPPLEMENT").contains(String.valueOf(candidate.getTaskType()))) continue;
            addEdge(candidate.getSourceTaskId(),candidate,byId,children);
            // 再次报废时新补产可能仍挂正常任务，但实际处置来源是上次补产任务。
            addEdge(candidate.getDefectResolutionTaskId(),candidate,byId,children);
        }
        requested.forEach(task->applyFulfillment(task,byId,children,approved,pending));
    }

    /** 取同工单下的全部任务，让重工/补产的子任务也参与达标计算。 */
    private Map<Long,MfgTask> familyOf(List<Long> workOrderIds,List<MfgTask> requested) {
        List<MfgTask> family=tasks.selectList(new LambdaQueryWrapper<MfgTask>()
                .in(MfgTask::getWorkOrderId,workOrderIds).eq(MfgTask::getDeletedFlag,0));
        Map<Long,MfgTask> byId=new LinkedHashMap<>();
        family.forEach(t->byId.put(t.getTaskId(),t));
        requested.forEach(t->byId.put(t.getTaskId(),t));
        return byId;
    }

    /** 已审核的合格数算产出，待审核的合格+不良数算待复核。 */
    private void collectReported(Map<Long,MfgTask> byId,Map<Long,BigDecimal> approved,Map<Long,BigDecimal> pending) {
        for(MfgWorkReport report:reports.selectList(new LambdaQueryWrapper<MfgWorkReport>()
                .in(MfgWorkReport::getTaskId,byId.keySet()).eq(MfgWorkReport::getDeletedFlag,0)
                .in(MfgWorkReport::getAuditStatus,"APPROVED","SUBMITTED"))) {
            if("APPROVED".equals(report.getAuditStatus()))
                approved.merge(report.getTaskId(),zero(report.getQualifiedQuantity()),BigDecimal::add);
            else pending.merge(report.getTaskId(),zero(report.getQualifiedQuantity()).add(zero(report.getDefectQuantity())),BigDecimal::add);
        }
    }

    /** 把达标口径写回到任务上，不改动任务本身的执行状态。 */
    private void applyFulfillment(MfgTask task,Map<Long,MfgTask> byId,Map<Long,Set<Long>> children,
                                  Map<Long,BigDecimal> approved,Map<Long,BigDecimal> pending) {
        Set<Long> descendants=descendants(task.getTaskId(),children);
        BigDecimal own=approved.getOrDefault(task.getTaskId(),BigDecimal.ZERO);
        BigDecimal target=zero(task.getPlannedQuantity());
        BigDecimal recovery=recoveredOf(descendants,approved,target.subtract(own).max(BigDecimal.ZERO));
        BigDecimal total=own.add(recovery).min(target);
        BigDecimal remaining=target.subtract(total).max(BigDecimal.ZERO);
        BigDecimal waiting=pending.getOrDefault(task.getTaskId(),BigDecimal.ZERO);
        String status=fulfillmentStatus(task,byId,descendants,remaining,waiting,pending,approved);
        task.setApprovedQualifiedQuantity(own);
        task.setRecoveredQualifiedQuantity(recovery);
        task.setTotalQualifiedQuantity(total);
        task.setRemainingQualifiedQuantity("UNVERIFIED".equals(status)?null:remaining);
        task.setPendingReportQuantity(waiting);
        task.setFulfillmentStatus(status);
    }

    /** 下游补产已审核的合格数最多补到本任务还差的量，不能超过目标。 */
    private static BigDecimal recoveredOf(Set<Long> descendants,Map<Long,BigDecimal> approved,BigDecimal ceiling) {
        BigDecimal recovered=descendants.stream().map(id->approved.getOrDefault(id,BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        return recovered.min(ceiling);
    }

    private String fulfillmentStatus(MfgTask task,Map<Long,MfgTask> byId,Set<Long> descendants,BigDecimal remaining,
                                     BigDecimal waiting,Map<Long,BigDecimal> pending,Map<Long,BigDecimal> approved) {
        boolean quantityDone=zero(task.getPlannedQuantity()).signum()>0 && remaining.signum()==0;
        boolean recoveryPending=descendants.stream().anyMatch(id->pending.getOrDefault(id,BigDecimal.ZERO).signum()>0);
        boolean unverified=reportedButUnapproved(task,approved);
        String status;
        if(quantityDone) {
            status="QUALIFIED";
        }else if(waiting.signum()>0 || recoveryPending) {
            status="PENDING_REVIEW";
        }else if(recovering(descendants,byId)) {
            status="RECOVERING";
        }else if(!"FINISHED".equals(task.getStatus())) {
            status=task.getStatus();
        }else{
            status=unverified ? "UNVERIFIED" : "WAIT_RECOVERY";
        }
        return status;
    }

    /** 仍有下游补产任务没走完，说明合格数还在回升中。 */
    private static boolean recovering(Set<Long> descendants,Map<Long,MfgTask> byId) {
        return descendants.stream().map(byId::get)
                .anyMatch(t->t!=null && Set.of("WAIT_START","RUNNING","PAUSED").contains(String.valueOf(t.getStatus())));
    }

    private boolean reportedButUnapproved(MfgTask task,Map<Long,BigDecimal> approved) {
        boolean hasUntouchedReport=!approved.containsKey(task.getTaskId())
                && zero(task.getCompletedQuantity()).add(zero(task.getDefectQuantity())).signum()>0;
        return hasUntouchedReport;
    }

    private void addEdge(Long sourceId,MfgTask child,Map<Long,MfgTask> byId,Map<Long,Set<Long>> children) {
        MfgTask source=byId.get(sourceId);
        if(!linkable(source,sourceId,child)) return;
        children.computeIfAbsent(sourceId,k->new LinkedHashSet<>()).add(child.getTaskId());
    }

    /** 只有同一工单、同一派工、同一工序上的来源任务才构成重工/补产链。 */
    private static boolean linkable(MfgTask source,Long sourceId,MfgTask child) {
        boolean distinct=source!=null && !Objects.equals(sourceId,child.getTaskId());
        boolean sameOrder=distinct && Objects.equals(source.getWorkOrderId(),child.getWorkOrderId());
        boolean sameDispatch=sameOrder && Objects.equals(source.getDispatchId(),child.getDispatchId());
        return sameDispatch && Objects.equals(source.getOperationId(),child.getOperationId());
    }

    private Set<Long> descendants(Long id,Map<Long,Set<Long>> children) {
        Set<Long> visited=new LinkedHashSet<>();
        Deque<Long> queue=new ArrayDeque<>();queue.add(id);
        while(!queue.isEmpty()) {
            Long current=queue.removeFirst();
            if(!visited.add(current)) continue;
            queue.addAll(children.getOrDefault(current,Set.of()));
        }
        visited.remove(id);
        return visited;
    }

    private static BigDecimal zero(BigDecimal value) { return value==null?BigDecimal.ZERO:value; }
}
