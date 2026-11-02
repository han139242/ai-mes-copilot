package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.common.AiTaskTriggerEvent;
import com.neuedu.ems.service.ai.AiTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AI 多步任务的事件监听：业务单据进入待处理时发一条 AiTaskTriggerEvent，
 * 这里在业务事务提交后按事件键找已启用的定义并执行。
 *
 * <p>放在 AFTER_COMMIT 的理由：任务里的确认、提交动作可能反过来改同一批单据，
 * 在业务事务里同步执行容易造成锁等待或把任务失败算到业务单上。</p>
 */
@Component
public class AiTaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(AiTaskEventListener.class);

    private final AiTaskService taskService;

    public AiTaskEventListener(AiTaskService taskService) {
        this.taskService = taskService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTrigger(AiTaskTriggerEvent event) {
        if (event == null || event.eventKey() == null || event.aggregateId() == null) {
            return;
        }
        try {
            int executed = taskService.runByEvent(event.eventKey(), event.aggregateId(),
                    event.aggregateType(), event.bizNo());
            if (executed > 0) {
                log.info("事件 {} 触发 {} 个多步任务实例", event.eventKey(), executed);
            }
        } catch (RuntimeException ex) {
            // 任务失败不回滚业务单据，只留日志，任务实例里已有失败原因
            log.warn("事件 {} 触发多步任务失败：{}", event.eventKey(), ex.getMessage());
        }
    }

}
