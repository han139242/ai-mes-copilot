package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.service.ai.AiAutomationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 自动化规则定时扫描：按各规则的 intervalMinutes 判断是否到期，到期就执行一次。
 * 同一分钟窗口的成功记录不会重复建单。上次失败或停在执行中的记录会接着跑完。
 */
@Component
@ConditionalOnProperty(name = "mes.automation.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AiAutomationScanner {

    private static final Logger log = LoggerFactory.getLogger(AiAutomationScanner.class);

    private final AiAutomationService automationService;

    public AiAutomationScanner(AiAutomationService automationService) {
        this.automationService = automationService;
    }

    @Scheduled(initialDelayString = "${mes.automation.scan-initial-delay-ms:40000}",
            fixedDelayString = "${mes.automation.scan-fixed-delay-ms:60000}")
    public void scanDueRules() {
        try {
            int executed = automationService.runScheduled();
            if (executed > 0) {
                log.info("AI 自动化规则本轮执行 {} 条", executed);
            }
        } catch (RuntimeException ex) {
            log.warn("AI 自动化规则定时扫描失败：{}", ex.getMessage());
        }
    }
}
