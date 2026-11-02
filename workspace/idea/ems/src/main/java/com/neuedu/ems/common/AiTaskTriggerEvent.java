package com.neuedu.ems.common;

/**
 * AI 多步任务触发事件。
 *
 * <p>业务侧只负责发一条事件，不直接调用任务服务，避免业务服务与任务服务互相依赖。
 * 任务服务用 AFTER_COMMIT 监听，业务事务提交后才执行，失败也不会回滚业务单据。</p>
 *
 * @param eventKey      事件键，对应 ai_task_definition.trigger_event_key
 * @param aggregateType 触发源单据类型，例如 WMS_INBOUND_ORDER
 * @param aggregateId   触发源单据ID
 * @param bizNo         触发源单据号
 */
public record AiTaskTriggerEvent(String eventKey, String aggregateType, Long aggregateId, String bizNo) {
}
