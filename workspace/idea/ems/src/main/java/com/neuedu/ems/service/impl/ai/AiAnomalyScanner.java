package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.service.ai.AiAnomalyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 主动异常定时扫描：默认每 5 分钟跑一轮，按分钟窗口幂等。
 */
@Component
@ConditionalOnProperty(name = "mes.anomaly.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AiAnomalyScanner {

    private static final Logger log = LoggerFactory.getLogger(AiAnomalyScanner.class);

    private final AiAnomalyService anomalyService;

    public AiAnomalyScanner(AiAnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    @Scheduled(initialDelayString = "${mes.anomaly.scan-initial-delay-ms:40000}",
            fixedDelayString = "${mes.anomaly.scan-fixed-delay-ms:300000}")
    public void scan() {
        try {
            int opened = anomalyService.scanScheduled();
            if (opened > 0) {
                log.info("主动异常扫描新登记 {} 条异常", opened);
            }
        } catch (RuntimeException ex) {
            log.warn("主动异常扫描失败：{}", ex.getMessage());
        }
    }
}
