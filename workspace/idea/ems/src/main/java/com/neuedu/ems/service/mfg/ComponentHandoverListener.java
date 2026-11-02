package com.neuedu.ems.service.mfg;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class ComponentHandoverListener {
    private final ComponentHandoverService handover;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void released(ComponentHandoverService.Released event) {
        try {
            handover.afterRelease(event.workOrderId(), event.operatorId());
        } catch (RuntimeException e) {
            // 质量事实已经提交，不向客户端误报“检验失败”；后续领料/派工命令会再次核对补齐。
            log.warn("组件交接待重试：来源工单 {}，原因 {}", event.workOrderId(), e.getMessage());
        }
    }
}
