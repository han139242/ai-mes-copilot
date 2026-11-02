package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.service.ai.AiAutomationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 可用库存变化后通知低库存规则。挂在事务提交之后，避免规则失败把入库或出库整单回滚。
 */
@Component
public class InventoryAutomationTrigger {
    private static final Logger log = LoggerFactory.getLogger(InventoryAutomationTrigger.class);

    private final AiAutomationService automation;

    public InventoryAutomationTrigger(AiAutomationService automation) {
        this.automation = automation;
    }

    public void afterChange(Long materialId) {
        if (materialId == null || automation == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeRun(materialId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeRun(materialId);
            }
        });
    }

    private void safeRun(Long materialId) {
        try {
            automation.runOnInventoryChange(materialId);
        } catch (RuntimeException ex) {
            log.warn("库存变化后的低库存规则没有跑完，物料 {}：{}", materialId, ex.getMessage());
        }
    }
}
