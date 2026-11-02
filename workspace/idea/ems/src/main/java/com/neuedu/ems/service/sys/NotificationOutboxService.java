package com.neuedu.ems.service.sys;

import com.neuedu.ems.common.NotificationEventPayload;

public interface NotificationOutboxService {
    boolean enqueue(String eventKey, String aggregateType, Long aggregateId,
                    String eventType, NotificationEventPayload payload);

    /**
     * 周期性重复事件入队：同一事件键已存在时刷新载荷并重新投递一次。
     * <p>
     * 一次性事件（任务指派、单据审批）用 {@link #enqueue}，重复投递应被唯一键挡掉；
     * 预警、超期提醒这类事件则会反复触发，需要把已有记录重新放回队列，
     * 否则"冷却到期后再提醒一次"会因为唯一键静默失效。
     * </p>
     *
     * @return 是否成功入队或刷新
     */
    boolean reenqueue(String eventKey, String aggregateType, Long aggregateId,
                      String eventType, NotificationEventPayload payload);
}
