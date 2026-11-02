package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.mfg.MfgDispatchOrder;
import com.neuedu.ems.entity.mfg.MfgTask;
import com.neuedu.ems.entity.mfg.MfgTaskOperator;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.entity.org.OrgTeam;
import com.neuedu.ems.mapper.mfg.MfgDispatchOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskOperatorMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.mapper.org.OrgTeamMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "mes.notification.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class MfgResponsibilityNotificationScanner {

    private final MfgTaskMapper taskMapper;
    private final MfgTaskOperatorMapper taskOperatorMapper;
    private final MfgDispatchOrderMapper dispatchMapper;
    private final MfgWorkReportMapper workReportMapper;
    private final OrgTeamMapper teamMapper;
    private final MfgResponsibilityNotificationService notificationService;

    public MfgResponsibilityNotificationScanner(MfgTaskMapper taskMapper,
                                                MfgTaskOperatorMapper taskOperatorMapper,
                                                MfgDispatchOrderMapper dispatchMapper,
                                                MfgWorkReportMapper workReportMapper,
                                                OrgTeamMapper teamMapper,
                                                MfgResponsibilityNotificationService notificationService) {
        this.taskMapper = taskMapper;
        this.taskOperatorMapper = taskOperatorMapper;
        this.dispatchMapper = dispatchMapper;
        this.workReportMapper = workReportMapper;
        this.teamMapper = teamMapper;
        this.notificationService = notificationService;
    }

    @Scheduled(initialDelayString = "${mes.notification.mfg-scan-initial-delay-ms:15000}",
            fixedDelayString = "${mes.notification.mfg-scan-fixed-delay-ms:60000}")
    public void synchronizeOpenResponsibilities() {
        List<MfgTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getDeletedFlag, 0)
                .in(MfgTask::getStatus, BusinessStatus.Production.WAIT_START,
                        BusinessStatus.Production.RUNNING, BusinessStatus.Production.PAUSED)
                .orderByAsc(MfgTask::getTaskId)
                .last("LIMIT 500"));
        if (tasks.isEmpty()) {
            synchronizeSubmittedReports(Map.of());
            return;
        }

        Set<Long> taskIds = tasks.stream().map(MfgTask::getTaskId).collect(Collectors.toSet());
        Map<Long, List<Long>> operators = taskOperatorMapper.selectList(new LambdaQueryWrapper<MfgTaskOperator>()
                        .in(MfgTaskOperator::getTaskId, taskIds)
                        .eq(MfgTaskOperator::getDeletedFlag, 0))
                .stream().collect(Collectors.groupingBy(MfgTaskOperator::getTaskId,
                        Collectors.mapping(MfgTaskOperator::getEmployeeId, Collectors.toList())));

        Set<Long> dispatchIds = tasks.stream().map(MfgTask::getDispatchId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MfgDispatchOrder> dispatches = dispatchIds.isEmpty() ? Collections.emptyMap()
                : activeById(dispatchMapper.selectBatchIds(dispatchIds),
                MfgDispatchOrder::getDispatchId, MfgDispatchOrder::getDeletedFlag);
        Set<Long> teamIds = dispatches.values().stream().map(MfgDispatchOrder::getTeamId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, OrgTeam> teams = teamIds.isEmpty() ? Collections.emptyMap()
                : activeById(teamMapper.selectBatchIds(teamIds), OrgTeam::getTeamId, OrgTeam::getDeletedFlag);

        LocalDateTime now = LocalDateTime.now();
        for (MfgTask task : tasks) {
            // 未派工的任务 dispatchId 为 null；Map.of() 得到的不可变 Map 在 get(null) 时直接抛 NPE，
            // 这里必须先挡掉空键，否则整轮扫描中断、后面的任务全不通知。
            MfgDispatchOrder dispatch = task.getDispatchId() == null ? null : dispatches.get(task.getDispatchId());
            OrgTeam team = dispatch == null || dispatch.getTeamId() == null ? null : teams.get(dispatch.getTeamId());
            Long leaderId = team == null ? null : team.getLeaderId();
            List<Long> assignedEmployees = operators.getOrDefault(task.getTaskId(), List.of());
            publishWaitingTask(task, dispatch, leaderId, assignedEmployees);
            publishPausedTask(task, leaderId, assignedEmployees);
            publishOverdueTask(task, dispatch, leaderId, now);
        }
        synchronizeSubmittedReports(tasks.stream().collect(Collectors.toMap(MfgTask::getTaskId, Function.identity())));
    }
    /** 待开工任务：没有派工时通知班组认领，已派工时逐个通知被指派的员工。 */
    private void publishWaitingTask(MfgTask task, MfgDispatchOrder dispatch, Long leaderId,
                                    List<Long> assignedEmployees) {
        if (!BusinessStatus.Production.WAIT_START.equals(task.getStatus())) {
            return;
        }
        if (assignedEmployees.isEmpty()) {
            notificationService.publishTaskUnassigned(task, dispatch == null ? null : dispatch.getTeamId(), leaderId);
            return;
        }
        assignedEmployees.forEach(employeeId ->
                notificationService.publishTaskAssigned(task.getTaskId(), employeeId, null));
    }

    private void publishPausedTask(MfgTask task, Long leaderId, List<Long> assignedEmployees) {
        if (BusinessStatus.Production.PAUSED.equals(task.getStatus())) {
            notificationService.publishTaskPaused(task, leaderId, assignedEmployees);
        }
    }

    private void publishOverdueTask(MfgTask task, MfgDispatchOrder dispatch, Long leaderId, LocalDateTime now) {
        if (dispatch != null && dispatch.getDueTime() != null && dispatch.getDueTime().isBefore(now)) {
            notificationService.publishTaskOverdue(task, dispatch, leaderId);
        }
    }

    private void synchronizeSubmittedReports(Map<Long, MfgTask> knownTasks) {
        List<MfgWorkReport> reports = workReportMapper.selectList(new LambdaQueryWrapper<MfgWorkReport>()
                .eq(MfgWorkReport::getDeletedFlag, 0)
                .eq(MfgWorkReport::getAuditStatus, BusinessStatus.Document.SUBMITTED)
                .orderByAsc(MfgWorkReport::getReportId)
                .last("LIMIT 500"));
        for (MfgWorkReport report : reports) {
            // 报工单没挂任务时 taskId 为 null，同样不能拿 null 当键去查（不可变 Map 会抛 NPE）。
            MfgTask knownTask = report.getTaskId() == null ? null : knownTasks.get(report.getTaskId());
            Long leaderId = reportLeaderId(knownTask, report.getTaskId());
            notificationService.publishWorkReportSubmitted(report, null, leaderId);
        }
    }

    /** 顺着报工 → 任务 → 派工 → 班组查班组长；任一环节已删除都按「无班组长」处理。 */
    private Long reportLeaderId(MfgTask knownTask, Long taskId) {
        MfgTask task = knownTask == null && taskId != null ? taskMapper.selectById(taskId) : knownTask;
        Long leaderId = null;
        if (!isDeleted(task == null ? null : task.getDeletedFlag())) {
            leaderId = leaderOfTask(task);
        }
        return leaderId;
    }

    /** 任务对应的派工班组长；任务或派工已删除时返回 null。 */
    private Long leaderOfTask(MfgTask task) {
        MfgDispatchOrder dispatch = task == null || task.getDispatchId() == null
                ? null : dispatchMapper.selectById(task.getDispatchId());
        Long leaderId = null;
        if (!isDeleted(dispatch == null ? null : dispatch.getDeletedFlag())) {
            leaderId = leaderOfDispatch(dispatch);
        }
        return leaderId;
    }

    /** 派工对应班组的班组长；派工或班组已删除时返回 null。 */
    private Long leaderOfDispatch(MfgDispatchOrder dispatch) {
        OrgTeam team = dispatch == null || dispatch.getTeamId() == null
                ? null : teamMapper.selectById(dispatch.getTeamId());
        Long leaderId = null;
        if (!isDeleted(team == null ? null : team.getDeletedFlag())) {
            leaderId = team.getLeaderId();
        }
        return leaderId;
    }

    private boolean isDeleted(Integer deletedFlag) {
        return deletedFlag != null && deletedFlag != 0;
    }

    private <T> Map<Long, T> activeById(Collection<T> rows, Function<T, Long> id,
                                         Function<T, Integer> deletedFlag) {
        if (rows == null) {
            return Map.of();
        }
        return rows.stream().filter(row -> row != null && (deletedFlag.apply(row) == null || deletedFlag.apply(row) == 0))
                .collect(Collectors.toMap(id, Function.identity(), (left, right) -> left));
    }
}
