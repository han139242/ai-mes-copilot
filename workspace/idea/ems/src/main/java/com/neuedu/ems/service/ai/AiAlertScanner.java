package com.neuedu.ems.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预警定时扫描。
 *
 * <p>默认每 60 秒跳一次心跳，与异常闭环的 5 分钟错开：预警要"快到限度就先弹"，节奏比事后异常更快。
 * 心跳只是"来不来看看"，真正取不取数由每条规则自己的 {@code interval_minutes} 决定：
 * 一条规则 10 分钟扫一次，意味着 60 秒心跳里有 9 次是空转，空转轮既不执行 SQL 也不落扫描台账
 * （见 {@code AiAlertServiceImpl#execute}），所以台账里留下的都是真正取过数的轮次。</p>
 *
 * <p>心跳必须小于规则间隔下限（{@code interval_minutes} 最小 1 分钟），否则界面上
 * "每 N 分钟扫一次"就成了一个存下来没人读的字段。可通过
 * {@code mes.alert.scan-fixed-delay-ms} / {@code mes.alert.scan-initial-delay-ms} 调整，
 * 或用 {@code mes.alert.scheduling-enabled=false} 关掉（自动化测试里关掉，避免干扰断言）。</p>
 */
@Component
@ConditionalOnProperty(name = "mes.alert.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AiAlertScanner {

    private static final Logger log = LoggerFactory.getLogger(AiAlertScanner.class);

    private final AiAlertService alertService;

    @Autowired
    public AiAlertScanner(AiAlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(initialDelayString = "${mes.alert.scan-initial-delay-ms:15000}",
            fixedDelayString = "${mes.alert.scan-fixed-delay-ms:60000}")
    public void scan() {
        try {
            int opened = alertService.scanScheduled();
            if (opened > 0) {
                log.info("主动预警扫描完成，新预警 {} 条", opened);
            }
        } catch (RuntimeException ex) {
            log.warn("主动预警扫描失败：{}", ex.getMessage());
        }
    }
}
