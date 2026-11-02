package com.neuedu.ems.service.impl.sys;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.NotificationEventPayload;
import com.neuedu.ems.entity.sys.SysEventOutbox;
import com.neuedu.ems.entity.sys.SysNotification;
import com.neuedu.ems.entity.sys.SysNotificationRecipient;
import com.neuedu.ems.mapper.sys.SysEventOutboxMapper;
import com.neuedu.ems.mapper.sys.SysNotificationMapper;
import com.neuedu.ems.mapper.sys.SysNotificationRecipientMapper;
import com.neuedu.ems.mapper.sys.SysUserMapper;
import com.neuedu.ems.service.sys.NotificationRoutePolicy;
import com.neuedu.ems.vo.sys.NotificationRecipientAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationOutboxDeliveryService {

    /** 责任收件人为空时兜底投递给超级管理员，收件原因记为升级处理。 */
    private static final String RECIPIENT_REASON_ESCALATION = "ESCALATION";

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxDeliveryService.class);

    private final SysEventOutboxMapper outboxMapper;
    private final SysNotificationMapper notificationMapper;
    private final SysNotificationRecipientMapper recipientMapper;
    private final SysUserMapper userMapper;
    private final NotificationRoutePolicy routePolicy;
    private final ObjectMapper objectMapper;

    public NotificationOutboxDeliveryService(SysEventOutboxMapper outboxMapper,
                                             SysNotificationMapper notificationMapper,
                                             SysNotificationRecipientMapper recipientMapper,
                                             SysUserMapper userMapper,
                                             NotificationRoutePolicy routePolicy,
                                             ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.notificationMapper = notificationMapper;
        this.recipientMapper = recipientMapper;
        this.userMapper = userMapper;
        this.routePolicy = routePolicy;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void deliver(Long outboxId) {
        SysEventOutbox outbox = outboxMapper.selectById(outboxId);
        if (outbox == null || !"PROCESSING".equals(outbox.getStatus())
                || Integer.valueOf(1).equals(outbox.getDeletedFlag())) {
            return;
        }
        NotificationEventPayload payload = deserialize(outbox.getPayloadJson());
        validatePayload(payload);
        LocalDateTime now = LocalDateTime.now();

        SysNotification notification = buildNotification(outbox, payload, now);
        boolean repeated = refreshExistingNotification(notification, now);
        notificationMapper.insertIgnore(notification);
        notification = notificationMapper.selectOne(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getEventKey, outbox.getEventKey())
                .eq(SysNotification::getDeletedFlag, 0));
        if (notification == null) {
            throw new IllegalStateException("NOTIFICATION_EVENT_NOT_PERSISTED");
        }
        if (repeated) {
            notificationMapper.reopenUnacknowledgedRecipients(outbox.getEventKey(), now);
        }

        int delivered = 0;
        for (NotificationEventPayload.RecipientTarget target : payload.recipients()) {
            List<NotificationRecipientAccount> accounts = userMapper.listNotificationRecipients(
                    target.employeeId(), target.requiredPermission());
            for (NotificationRecipientAccount account : accounts) {
                delivered += insertRecipient(notification.getNotificationId(), account, target.reason(),
                        payload.acknowledgmentRequired(), payload.sourceUserId(), now);
            }
        }
        if (delivered == 0 && recipientMapper.selectCount(
                new LambdaQueryWrapper<SysNotificationRecipient>()
                        .eq(SysNotificationRecipient::getNotificationId, notification.getNotificationId())
                        .eq(SysNotificationRecipient::getDeletedFlag, 0)) == 0) {
            delivered = deliverToSuperAdmins(notification, payload, now);
        }
        if (delivered == 0 && recipientMapper.selectCount(
                new LambdaQueryWrapper<SysNotificationRecipient>()
                        .eq(SysNotificationRecipient::getNotificationId, notification.getNotificationId())
                        .eq(SysNotificationRecipient::getDeletedFlag, 0)) == 0) {
            throw new IllegalStateException("NO_VALID_NOTIFICATION_RECIPIENT");
        }

        outbox.setStatus("PROCESSED");
        outbox.setProcessedTime(now);
        outbox.setNextRetryTime(null);
        outbox.setLastError(null);
        outbox.setUpdatedBy(payload.sourceUserId());
        outbox.setUpdatedTime(now);
        if (outboxMapper.updateById(outbox) == 0) {
            throw new IllegalStateException("OUTBOX_PROCESS_STATE_CONFLICT");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailure(Long outboxId, String error, int maxRetries) {
        SysEventOutbox outbox = outboxMapper.selectById(outboxId);
        if (outbox == null || "PROCESSED".equals(outbox.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int retries = (outbox.getRetryCount() == null ? 0 : outbox.getRetryCount()) + 1;
        outbox.setStatus("FAILED");
        outbox.setRetryCount(retries);
        outbox.setNextRetryTime(retries >= maxRetries ? null : now.plusMinutes(Math.min(60, 1L << Math.min(retries, 5))));
        outbox.setLastError(abbreviate(error));
        outbox.setUpdatedTime(now);
        outboxMapper.updateById(outbox);
    }

    /**
     * 责任收件人解析为空时的兜底投递。
     * <p>
     * 责任部门没有主管、主管没有登录账号或缺少业务权限时，事件原本会以
     * {@code NO_VALID_NOTIFICATION_RECIPIENT} 失败并最终停止重试。这里改为
     * 投递给全部有效的超级管理员，收件原因记 {@code ESCALATION}，
     * 让通知至少能被看到和转派，而不是静默丢失。
     * </p>
     *
     * @param notification 已落库的通知事件
     * @param payload      事件载荷，用于取确认要求与来源用户
     * @param now          本次投递时间
     * @return 实际新增的接收记录条数
     */
    private int deliverToSuperAdmins(SysNotification notification, NotificationEventPayload payload,
                                     LocalDateTime now) {
        int delivered = 0;
        for (NotificationRecipientAccount account : userMapper.listSuperAdminRecipients()) {
            delivered += insertRecipient(notification.getNotificationId(), account, RECIPIENT_REASON_ESCALATION,
                    payload.acknowledgmentRequired(), payload.sourceUserId(), now);
        }
        if (delivered > 0) {
            log.info("Notification routed to super administrators because no responsible recipient matched: "
                    + "notificationId={}, eventType={}", notification.getNotificationId(), notification.getEventType());
        }
        return delivered;
    }

    /**
     * 同一条业务事件的重复通知就地刷新，返回是否命中了已有通知。
     * <p>
     * 预警、超期这类事件会周期性重复提醒。如果每次都新增一行，收件箱会按提醒次数
     * 线性膨胀——同一条预警在列表里出现十几次，用户看到的未读数远大于真实待办数。
     * 这里改为按 {@code event_key} 覆盖摘要、级别与发生时间，让一条事件在收件箱里
     * 始终只占一行；「最近一次提醒时间」通过 occurred_time 体现，收件人仍能看出它还在恶化。
     * </p>
     */
    private boolean refreshExistingNotification(SysNotification notification, LocalDateTime now) {
        notification.setUpdatedTime(now);
        return notificationMapper.refresh(notification) > 0;
    }

    private SysNotification buildNotification(SysEventOutbox outbox, NotificationEventPayload payload,
                                              LocalDateTime now) {
        SysNotification notification = new SysNotification();
        notification.setEventKey(outbox.getEventKey());
        notification.setEventType(outbox.getEventType());
        notification.setCategory(payload.category());
        notification.setSeverity(payload.severity());
        notification.setTitle(payload.title());
        notification.setSummary(payload.summary());
        notification.setBizType(payload.bizType());
        notification.setBizId(payload.bizId());
        notification.setBizNo(payload.bizNo());
        notification.setRoutePath(payload.routePath());
        notification.setSourceUserId(payload.sourceUserId());
        notification.setSourceEmployeeId(payload.sourceEmployeeId());
        notification.setOccurredTime(payload.occurredTime() == null ? outbox.getOccurredTime() : payload.occurredTime());
        notification.setExpireTime(payload.expireTime());
        notification.setStatus("ACTIVE");
        notification.setVersion(0);
        notification.setCreatedBy(payload.sourceUserId());
        notification.setCreatedTime(now);
        notification.setUpdatedBy(payload.sourceUserId());
        notification.setUpdatedTime(now);
        notification.setDeletedFlag(0);
        return notification;
    }

    private int insertRecipient(Long notificationId, NotificationRecipientAccount account, String reason,
                                boolean acknowledgmentRequired, Long sourceUserId, LocalDateTime now) {
        SysNotificationRecipient recipient = new SysNotificationRecipient();
        recipient.setNotificationId(notificationId);
        recipient.setRecipientUserId(account.getUserId());
        recipient.setRecipientEmployeeId(account.getEmployeeId());
        recipient.setRecipientReason(reason);
        recipient.setReadStatus("UNREAD");
        recipient.setAckStatus(acknowledgmentRequired ? "PENDING" : "NOT_REQUIRED");
        recipient.setDeliveryStatus("DELIVERED");
        recipient.setDeliveredTime(now);
        recipient.setVersion(0);
        recipient.setCreatedBy(sourceUserId);
        recipient.setCreatedTime(now);
        recipient.setUpdatedBy(sourceUserId);
        recipient.setUpdatedTime(now);
        recipient.setDeletedFlag(0);
        return recipientMapper.insertIgnore(recipient);
    }

    private NotificationEventPayload deserialize(String json) {
        try {
            return objectMapper.readValue(json, NotificationEventPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("INVALID_NOTIFICATION_EVENT_PAYLOAD", e);
        }
    }

    private void validatePayload(NotificationEventPayload payload) {
        if (!hasValidHeader(payload) || !hasValidRecipients(payload)) {
            throw new IllegalStateException("INVALID_NOTIFICATION_EVENT_PAYLOAD");
        }
        for (NotificationEventPayload.RecipientTarget target : payload.recipients()) {
            if (target.employeeId() == null || !StringUtils.hasText(target.reason())
                    || !StringUtils.hasText(target.requiredPermission())) {
                throw new IllegalStateException("INVALID_NOTIFICATION_RECIPIENT_TARGET");
            }
        }
    }

    /** 事件头字段必须齐全，且路由地址必须在允许清单内。 */
    private boolean hasValidHeader(NotificationEventPayload payload) {
        if (payload == null) {
            return false;
        }
        boolean hasText = StringUtils.hasText(payload.category()) && StringUtils.hasText(payload.severity())
                && StringUtils.hasText(payload.title()) && StringUtils.hasText(payload.bizType());
        return hasText && routePolicy.isAllowed(payload.routePath());
    }

    /**
     * 接收人列表允许为空，逐个接收人的必填字段在 validatePayload 的循环里校验。
     * <p>
     * 业务侧解析不到责任人时（单据创建人没有绑定员工、部门没有主管等），
     * {@code ResponsibilityNotificationSupport.dispatch} 会丢弃空目标，最终生成
     * {@code recipients=[]}。这类事件以前在校验阶段就被判成
     * {@code INVALID_NOTIFICATION_EVENT_PAYLOAD} 并原地重试到失败上限，等于静默丢消息；
     * 现在放行空列表，交给投递流程后面的超管兜底（原因记 {@code ESCALATION}），
     * 至少保证事件能被看到和转派。
     * </p>
     */
    private boolean hasValidRecipients(NotificationEventPayload payload) {
        return payload.recipients() != null;
    }

    private String abbreviate(String error) {
        String value = StringUtils.hasText(error) ? error.replaceAll("[\\r\\n]+", " ") : "UNKNOWN_ERROR";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
