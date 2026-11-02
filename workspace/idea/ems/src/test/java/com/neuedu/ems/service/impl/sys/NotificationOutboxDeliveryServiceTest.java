package com.neuedu.ems.service.impl.sys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.NotificationEventPayload;
import com.neuedu.ems.entity.sys.SysEventOutbox;
import com.neuedu.ems.entity.sys.SysNotification;
import com.neuedu.ems.mapper.sys.SysEventOutboxMapper;
import com.neuedu.ems.mapper.sys.SysNotificationMapper;
import com.neuedu.ems.mapper.sys.SysNotificationRecipientMapper;
import com.neuedu.ems.mapper.sys.SysUserMapper;
import com.neuedu.ems.service.sys.NotificationRoutePolicy;
import com.neuedu.ems.vo.sys.NotificationRecipientAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxDeliveryServiceTest {

    private final SysEventOutboxMapper outboxMapper = mock(SysEventOutboxMapper.class);
    private final SysNotificationMapper notificationMapper = mock(SysNotificationMapper.class);
    private final SysNotificationRecipientMapper recipientMapper = mock(SysNotificationRecipientMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final NotificationOutboxDeliveryService service = new NotificationOutboxDeliveryService(
            outboxMapper, notificationMapper, recipientMapper, userMapper,
            new NotificationRoutePolicy(), objectMapper);

    private SysEventOutbox outbox;

    @BeforeEach
    void setUp() throws Exception {
        NotificationEventPayload payload = new NotificationEventPayload(
                "MFG", "INFO", "任务已分配", "任务 TASK-1 已分配给您", "MFG_TASK",
                1L, "TASK-1", "/mfg/tasks", 2L, 10L, LocalDateTime.now(), null, false,
                List.of(new NotificationEventPayload.RecipientTarget(11L, "ASSIGNEE", "mfg:task:view")));
        outbox = new SysEventOutbox();
        outbox.setOutboxId(5L);
        outbox.setEventKey("MFG_TASK:1:ASSIGNED:11");
        outbox.setEventType("MFG_TASK_ASSIGNED");
        outbox.setStatus("PROCESSING");
        outbox.setDeletedFlag(0);
        outbox.setOccurredTime(LocalDateTime.now());
        outbox.setPayloadJson(objectMapper.writeValueAsString(payload));
        when(outboxMapper.selectById(5L)).thenReturn(outbox);
        when(notificationMapper.insertIgnore(any())).thenReturn(1);
        SysNotification stored = new SysNotification();
        stored.setNotificationId(8L);
        when(notificationMapper.selectOne(any())).thenReturn(stored);
        when(outboxMapper.updateById(any(SysEventOutbox.class))).thenReturn(1);
    }

    @Test
    void deliversOnlyToEnabledAccountWithRequiredBusinessPermission() {
        NotificationRecipientAccount account = new NotificationRecipientAccount();
        account.setUserId(21L);
        account.setEmployeeId(11L);
        when(userMapper.listNotificationRecipients(11L, "mfg:task:view")).thenReturn(List.of(account));
        when(recipientMapper.insertIgnore(any())).thenReturn(1);

        service.deliver(5L);

        verify(userMapper).listNotificationRecipients(11L, "mfg:task:view");
        ArgumentCaptor<com.neuedu.ems.entity.sys.SysNotificationRecipient> captor =
                ArgumentCaptor.forClass(com.neuedu.ems.entity.sys.SysNotificationRecipient.class);
        verify(recipientMapper).insertIgnore(captor.capture());
        assertEquals(21L, captor.getValue().getRecipientUserId());
        assertEquals("ASSIGNEE", captor.getValue().getRecipientReason());
        assertEquals("PROCESSED", outbox.getStatus());
    }

    @Test
    void failsRoutingWhenNoAccountHasTargetPermission() {
        when(userMapper.listNotificationRecipients(11L, "mfg:task:view")).thenReturn(List.of());
        when(recipientMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.listSuperAdminRecipients()).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> service.deliver(5L));
    }

    /**
     * 业务侧解析不到责任人时载荷里的接收人列表就是空的。
     * <p>
     * 这类事件以前在校验阶段就被判成 INVALID_NOTIFICATION_EVENT_PAYLOAD，
     * 重试到上限后静默丢消息；现在要放行空列表并升级给超级管理员。
     * </p>
     */
    @Test
    void escalatesToSuperAdminWhenPayloadHasNoRecipient() throws Exception {
        NotificationEventPayload empty = new NotificationEventPayload(
                "QMS", "INFO", "检验任务已完成", "检验单 QI-1 检验结果为 PASS", "QMS_INSPECTION_ORDER",
                55L, "QI-1", "/qms/inspection-orders", 1L, null, LocalDateTime.now(), null, false,
                List.of());
        outbox.setPayloadJson(objectMapper.writeValueAsString(empty));
        NotificationRecipientAccount admin = new NotificationRecipientAccount();
        admin.setUserId(1L);
        admin.setEmployeeId(null);
        when(userMapper.listSuperAdminRecipients()).thenReturn(List.of(admin));
        when(recipientMapper.selectCount(any())).thenReturn(0L);
        when(recipientMapper.insertIgnore(any())).thenReturn(1);

        service.deliver(5L);

        ArgumentCaptor<com.neuedu.ems.entity.sys.SysNotificationRecipient> captor =
                ArgumentCaptor.forClass(com.neuedu.ems.entity.sys.SysNotificationRecipient.class);
        verify(recipientMapper).insertIgnore(captor.capture());
        assertEquals(1L, captor.getValue().getRecipientUserId());
        assertEquals("ESCALATION", captor.getValue().getRecipientReason());
        assertEquals("PROCESSED", outbox.getStatus());
    }

    @Test
    void escalatesToSuperAdminWhenResponsibleRecipientCannotBeResolved() {
        NotificationRecipientAccount admin = new NotificationRecipientAccount();
        admin.setUserId(1L);
        admin.setEmployeeId(null);
        when(userMapper.listNotificationRecipients(11L, "mfg:task:view")).thenReturn(List.of());
        when(userMapper.listSuperAdminRecipients()).thenReturn(List.of(admin));
        when(recipientMapper.selectCount(any())).thenReturn(0L);
        when(recipientMapper.insertIgnore(any())).thenReturn(1);

        service.deliver(5L);

        ArgumentCaptor<com.neuedu.ems.entity.sys.SysNotificationRecipient> captor =
                ArgumentCaptor.forClass(com.neuedu.ems.entity.sys.SysNotificationRecipient.class);
        verify(recipientMapper).insertIgnore(captor.capture());
        assertEquals(1L, captor.getValue().getRecipientUserId());
        assertEquals("ESCALATION", captor.getValue().getRecipientReason());
        assertEquals("PROCESSED", outbox.getStatus());
    }

    /**
     * 同一条预警反复命中时，冷却到期后要再提醒一次，但不能在收件箱里堆出新行。
     * <p>
     * 之前事件键带分钟时间戳，每轮冷却到期都生成一条新通知，用户看到几百条未读；
     * 现在改为就地刷新同一行，并把已读未确认的收件人重新标为未读。
     * </p>
     */
    @Test
    void repeatedNotificationRefreshesExistingRowInsteadOfAddingOne() {
        NotificationRecipientAccount account = new NotificationRecipientAccount();
        account.setUserId(21L);
        account.setEmployeeId(11L);
        when(userMapper.listNotificationRecipients(11L, "mfg:task:view")).thenReturn(List.of(account));
        when(recipientMapper.insertIgnore(any())).thenReturn(0);
        when(recipientMapper.selectCount(any())).thenReturn(1L);
        when(notificationMapper.refresh(any())).thenReturn(1);

        service.deliver(5L);

        ArgumentCaptor<SysNotification> notificationCaptor = ArgumentCaptor.forClass(SysNotification.class);
        verify(notificationMapper).refresh(notificationCaptor.capture());
        assertEquals("MFG_TASK:1:ASSIGNED:11", notificationCaptor.getValue().getEventKey(),
                "刷新必须按事件键定位同一条通知");
        verify(notificationMapper).reopenUnacknowledgedRecipients(
                org.mockito.ArgumentMatchers.eq("MFG_TASK:1:ASSIGNED:11"), any());
        assertEquals("PROCESSED", outbox.getStatus());
    }

    @Test
    void firstDeliveryDoesNotTouchRecipientReadState() {
        NotificationRecipientAccount account = new NotificationRecipientAccount();
        account.setUserId(21L);
        account.setEmployeeId(11L);
        when(userMapper.listNotificationRecipients(11L, "mfg:task:view")).thenReturn(List.of(account));
        when(recipientMapper.insertIgnore(any())).thenReturn(1);
        when(notificationMapper.refresh(any())).thenReturn(0);

        service.deliver(5L);

        verify(notificationMapper, org.mockito.Mockito.never())
                .reopenUnacknowledgedRecipients(any(), any());
    }
}
