package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.neuedu.ems.common.NotificationEventPayload;
import com.neuedu.ems.entity.mfg.MfgDispatchOrder;
import com.neuedu.ems.entity.mfg.MfgTask;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.entity.org.OrgTeam;
import com.neuedu.ems.entity.sys.SysUser;
import com.neuedu.ems.entity.sys.SysEventOutbox;
import com.neuedu.ems.entity.sys.SysNotification;
import com.neuedu.ems.mapper.mfg.MfgDispatchOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskMapper;
import com.neuedu.ems.mapper.org.OrgTeamMapper;
import com.neuedu.ems.mapper.sys.SysUserMapper;
import com.neuedu.ems.mapper.sys.SysEventOutboxMapper;
import com.neuedu.ems.mapper.sys.SysNotificationMapper;
import com.neuedu.ems.service.sys.NotificationOutboxService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MfgResponsibilityNotificationService {

    private static final String TASK_ROUTE = "/mfg/tasks";
    private static final String REPORT_ROUTE = "/mfg/work-reports";
    private static final String TASK_VIEW = "mfg:task:view";
    private static final String REPORT_VIEW = "mfg:work-report:view";

    private final NotificationOutboxService outboxService;
    private final MfgTaskMapper taskMapper;
    private final MfgDispatchOrderMapper dispatchMapper;
    private final OrgTeamMapper teamMapper;
    private final SysUserMapper userMapper;
    private final SysNotificationMapper notificationMapper;
    private final SysEventOutboxMapper outboxMapper;

    public MfgResponsibilityNotificationService(NotificationOutboxService outboxService,
                                                MfgTaskMapper taskMapper,
                                                MfgDispatchOrderMapper dispatchMapper,
                                                OrgTeamMapper teamMapper,
                                                SysUserMapper userMapper,
                                                SysNotificationMapper notificationMapper,
                                                SysEventOutboxMapper outboxMapper) {
        this.outboxService = outboxService;
        this.taskMapper = taskMapper;
        this.dispatchMapper = dispatchMapper;
        this.teamMapper = teamMapper;
        this.userMapper = userMapper;
        this.notificationMapper = notificationMapper;
        this.outboxMapper = outboxMapper;
    }

    public void publishTaskAssigned(Long taskId, Long employeeId, Long sourceUserId) {
        MfgTask task = activeTask(taskId);
        if (task == null || employeeId == null) {
            return;
        }
        String eventType = "MFG_TASK_ASSIGNED";
        String eventKey = "MFG_TASK:" + taskId + ":ASSIGNED:" + employeeId;
        publish(eventKey, "MFG_TASK", taskId, eventType,
                payload("MFG", "INFO", "新生产任务已分配",
                        "生产任务 " + displayNo(task.getTaskNo(), taskId) + " 已分配给您",
                        "MFG_TASK", taskId, task.getTaskNo(), TASK_ROUTE, sourceUserId, false,
                        List.of(target(employeeId, "ASSIGNEE", TASK_VIEW))));
    }

    public void publishTaskUnassigned(MfgTask task, Long teamId, Long leaderEmployeeId) {
        if (task == null || task.getTaskId() == null || teamId == null || leaderEmployeeId == null) {
            return;
        }
        String eventType = "MFG_TASK_UNASSIGNED";
        String eventKey = "MFG_TASK:" + task.getTaskId() + ":UNASSIGNED:" + teamId;
        publish(eventKey, "MFG_TASK", task.getTaskId(), eventType,
                payload("MFG", "WARNING", "班组任务尚未分配人员",
                        "生产任务 " + displayNo(task.getTaskNo(), task.getTaskId()) + " 等待班组分配",
                        "MFG_TASK", task.getTaskId(), task.getTaskNo(), TASK_ROUTE, null, false,
                        List.of(target(leaderEmployeeId, "TEAM_LEADER", TASK_VIEW))));
    }

    public void publishTaskPaused(MfgTask task, Long leaderEmployeeId, Collection<Long> operatorEmployeeIds) {
        if (task == null || task.getTaskId() == null) {
            return;
        }
        List<NotificationEventPayload.RecipientTarget> targets = new ArrayList<>();
        if (operatorEmployeeIds != null) {
            operatorEmployeeIds.forEach(employeeId -> targets.add(target(employeeId, "ASSIGNEE", TASK_VIEW)));
        }
        if (leaderEmployeeId != null) {
            targets.add(target(leaderEmployeeId, "TEAM_LEADER", TASK_VIEW));
        }
        if (targets.isEmpty()) {
            return;
        }
        String eventType = "MFG_TASK_PAUSED";
        String eventKey = "MFG_TASK:" + task.getTaskId() + ":PAUSED:" + value(task.getVersion());
        publish(eventKey, "MFG_TASK", task.getTaskId(), eventType,
                payload("MFG", "HIGH", "生产任务已暂停",
                        "生产任务 " + displayNo(task.getTaskNo(), task.getTaskId()) + " 需要关注",
                        "MFG_TASK", task.getTaskId(), task.getTaskNo(), TASK_ROUTE, null, true, targets));
    }

    public void publishTaskOverdue(MfgTask task, MfgDispatchOrder dispatch, Long leaderEmployeeId) {
        if (!canPublishOverdue(task, dispatch, leaderEmployeeId)) {
            return;
        }
        String eventType = "MFG_TASK_OVERDUE";
        String eventKey = "MFG_TASK:" + task.getTaskId() + ":OVERDUE:" + dispatch.getDueTime();
        publish(eventKey, "MFG_TASK", task.getTaskId(), eventType,
                payload("MFG", "HIGH", "生产任务已逾期",
                        "生产任务 " + displayNo(task.getTaskNo(), task.getTaskId()) + " 已超过派工交期",
                        "MFG_TASK", task.getTaskId(), task.getTaskNo(), TASK_ROUTE, null, true,
                        List.of(target(leaderEmployeeId, "TEAM_LEADER", TASK_VIEW))));
    }

    /** 任务、派工交期和班组长都齐备时才发逾期提醒。 */
    private boolean canPublishOverdue(MfgTask task, MfgDispatchOrder dispatch, Long leaderEmployeeId) {
        return hasTaskId(task) && hasDueTime(dispatch) && leaderEmployeeId != null;
    }

    private boolean hasTaskId(MfgTask task) {
        return task != null && task.getTaskId() != null;
    }

    private boolean hasDueTime(MfgDispatchOrder dispatch) {
        return dispatch != null && dispatch.getDueTime() != null;
    }

    public void publishWorkReportSubmitted(MfgWorkReport report, Long sourceUserId, Long leaderEmployeeId) {
        if (report == null || report.getReportId() == null || leaderEmployeeId == null) {
            return;
        }
        String eventType = "MFG_WORK_REPORT_SUBMITTED";
        String eventKey = "MFG_WORK_REPORT:" + report.getReportId() + ":SUBMITTED";
        publish(eventKey, "MFG_WORK_REPORT", report.getReportId(), eventType,
                payload("MFG", "INFO", "生产报工待审核",
                        "报工单 " + displayNo(report.getReportNo(), report.getReportId()) + " 等待审核",
                        "MFG_WORK_REPORT", report.getReportId(), report.getReportNo(), REPORT_ROUTE,
                        sourceUserId, false, List.of(target(leaderEmployeeId, "TEAM_LEADER", REPORT_VIEW))));
    }

    public void publishWorkReportSubmitted(MfgWorkReport report, Long sourceUserId) {
        publishWorkReportSubmitted(report, sourceUserId, resolveTeamLeader(report == null ? null : report.getTaskId()));
    }

    public void publishWorkReportAudited(MfgWorkReport report, String auditResult, Long sourceUserId) {
        if (report == null || report.getReportId() == null || report.getOperatorId() == null || auditResult == null) {
            return;
        }
        String eventType = "MFG_WORK_REPORT_AUDITED";
        String eventKey = "MFG_WORK_REPORT:" + report.getReportId() + ":AUDITED:" + auditResult;
        boolean approved = "APPROVED".equals(auditResult);
        publish(eventKey, "MFG_WORK_REPORT", report.getReportId(), eventType,
                payload("MFG", approved ? "INFO" : "WARNING", approved ? "生产报工审核通过" : "生产报工被驳回",
                        "报工单 " + displayNo(report.getReportNo(), report.getReportId())
                                + (approved ? " 已审核通过" : " 需要修改后重新处理"),
                        "MFG_WORK_REPORT", report.getReportId(), report.getReportNo(), REPORT_ROUTE,
                        sourceUserId, false, List.of(target(report.getOperatorId(), "ASSIGNEE", REPORT_VIEW))));
    }

    public Long resolveTeamLeader(Long taskId) {
        MfgTask task = activeTask(taskId);
        Long leaderId = null;
        if (task != null && task.getDispatchId() != null) {
            leaderId = leaderOfDispatch(dispatchMapper.selectById(task.getDispatchId()));
        }
        return leaderId;
    }

    /** 沿派工找班组班组长；派工或班组已删除时返回 null。 */
    private Long leaderOfDispatch(MfgDispatchOrder dispatch) {
        Long leaderId = null;
        if (dispatch != null && !isDeleted(dispatch.getDeletedFlag()) && dispatch.getTeamId() != null) {
            OrgTeam team = teamMapper.selectById(dispatch.getTeamId());
            if (team != null && !isDeleted(team.getDeletedFlag())) {
                leaderId = team.getLeaderId();
            }
        }
        return leaderId;
    }

    private boolean isDeleted(Integer deletedFlag) {
        return deletedFlag != null && deletedFlag != 0;
    }

    public void cancelTaskUnassigned(Long taskId, Long sourceUserId) {
        cancelEvents("MFG_TASK", taskId, List.of("MFG_TASK_UNASSIGNED"), sourceUserId);
    }

    public void cancelTaskAssignment(Long taskId, Long employeeId, Long sourceUserId) {
        if (taskId == null || employeeId == null) {
            return;
        }
        cancelEventKey("MFG_TASK:" + taskId + ":ASSIGNED:" + employeeId, sourceUserId);
    }

    public void cancelOpenTaskNotifications(Long taskId, Long sourceUserId) {
        cancelEvents("MFG_TASK", taskId, List.of(
                "MFG_TASK_ASSIGNED", "MFG_TASK_UNASSIGNED", "MFG_TASK_PAUSED", "MFG_TASK_OVERDUE"), sourceUserId);
    }

    public void cancelWorkReportSubmitted(Long reportId, Long sourceUserId) {
        cancelEvents("MFG_WORK_REPORT", reportId, List.of("MFG_WORK_REPORT_SUBMITTED"), sourceUserId);
    }

    private void publish(String eventKey, String aggregateType, Long aggregateId, String eventType,
                         NotificationEventPayload payload) {
        outboxService.enqueue(eventKey, aggregateType, aggregateId, eventType, payload);
    }

    private void cancelEvents(String aggregateType, Long aggregateId, Collection<String> eventTypes,
                              Long sourceUserId) {
        if (aggregateId == null || eventTypes == null || eventTypes.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        SysNotification notification = new SysNotification();
        notification.setStatus("CANCELLED");
        notification.setUpdatedBy(sourceUserId);
        notification.setUpdatedTime(now);
        notificationMapper.update(notification, new LambdaUpdateWrapper<SysNotification>()
                .eq(SysNotification::getBizType, aggregateType)
                .eq(SysNotification::getBizId, aggregateId)
                .in(SysNotification::getEventType, eventTypes)
                .eq(SysNotification::getStatus, "ACTIVE")
                .eq(SysNotification::getDeletedFlag, 0));

        SysEventOutbox outbox = new SysEventOutbox();
        outbox.setStatus("CANCELLED");
        outbox.setProcessedTime(now);
        outbox.setUpdatedBy(sourceUserId);
        outbox.setUpdatedTime(now);
        outboxMapper.update(outbox, new LambdaUpdateWrapper<SysEventOutbox>()
                .eq(SysEventOutbox::getAggregateType, aggregateType)
                .eq(SysEventOutbox::getAggregateId, aggregateId)
                .in(SysEventOutbox::getEventType, eventTypes)
                .in(SysEventOutbox::getStatus, "PENDING", "FAILED", "PROCESSING")
                .eq(SysEventOutbox::getDeletedFlag, 0));
    }

    private void cancelEventKey(String eventKey, Long sourceUserId) {
        LocalDateTime now = LocalDateTime.now();
        SysNotification notification = new SysNotification();
        notification.setStatus("CANCELLED");
        notification.setUpdatedBy(sourceUserId);
        notification.setUpdatedTime(now);
        notificationMapper.update(notification, new LambdaUpdateWrapper<SysNotification>()
                .eq(SysNotification::getEventKey, eventKey)
                .eq(SysNotification::getStatus, "ACTIVE")
                .eq(SysNotification::getDeletedFlag, 0));

        SysEventOutbox outbox = new SysEventOutbox();
        outbox.setStatus("CANCELLED");
        outbox.setProcessedTime(now);
        outbox.setUpdatedBy(sourceUserId);
        outbox.setUpdatedTime(now);
        outboxMapper.update(outbox, new LambdaUpdateWrapper<SysEventOutbox>()
                .eq(SysEventOutbox::getEventKey, eventKey)
                .in(SysEventOutbox::getStatus, "PENDING", "FAILED", "PROCESSING")
                .eq(SysEventOutbox::getDeletedFlag, 0));
    }

    private NotificationEventPayload payload(String category, String severity, String title, String summary,
                                             String bizType, Long bizId, String bizNo, String routePath,
                                             Long sourceUserId, boolean ackRequired,
                                             Collection<NotificationEventPayload.RecipientTarget> rawTargets) {
        Map<String, NotificationEventPayload.RecipientTarget> targets = dedupeTargets(rawTargets);
        return new NotificationEventPayload(category, severity, title, summary, bizType, bizId, bizNo,
                routePath, sourceUserId, sourceEmployeeId(sourceUserId), LocalDateTime.now(), null,
                ackRequired, List.copyOf(targets.values()));
    }

    /** 按「员工 + 原因」去重收件人，空的或不带员工的收件人直接丢弃。 */
    private Map<String, NotificationEventPayload.RecipientTarget> dedupeTargets(
            Collection<NotificationEventPayload.RecipientTarget> rawTargets) {
        Map<String, NotificationEventPayload.RecipientTarget> targets = new LinkedHashMap<>();
        if (rawTargets == null) {
            return targets;
        }
        for (NotificationEventPayload.RecipientTarget target : rawTargets) {
            if (isUsableTarget(target)) {
                targets.putIfAbsent(target.employeeId() + ":" + target.reason(), target);
            }
        }
        return targets;
    }

    private boolean isUsableTarget(NotificationEventPayload.RecipientTarget target) {
        return target != null && target.employeeId() != null;
    }

    private NotificationEventPayload.RecipientTarget target(Long employeeId, String reason, String permission) {
        return employeeId == null ? null : new NotificationEventPayload.RecipientTarget(employeeId, reason, permission);
    }

    private MfgTask activeTask(Long taskId) {
        MfgTask task = taskId == null ? null : taskMapper.selectById(taskId);
        return task == null || task.getDeletedFlag() != null && task.getDeletedFlag() != 0 ? null : task;
    }

    private Long sourceEmployeeId(Long userId) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        return user == null ? null : user.getEmployeeId();
    }

    private String displayNo(String number, Long id) {
        return number == null || number.isBlank() ? "#" + id : number;
    }

    private int value(Integer version) {
        return version == null ? 0 : version;
    }
}
