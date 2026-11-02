package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.service.ai.AiTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 多步任务定时入口：
 * 1. 启动后先把上次进程遗留的 RUNNING 实例收敛为可续跑的 PARTIAL_FAILED；
 * 2. 之后按定义间隔执行到期的 SCHEDULE 任务。
 * 幂等由 instance_key（定义 + 分钟窗口）保证，重启后重复扫描不会重复建单。
 */
@Component
@ConditionalOnProperty(name = "mes.task.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AiTaskScanner {

    private static final Logger log = LoggerFactory.getLogger(AiTaskScanner.class);

    private final AiTaskService taskService;

    public AiTaskScanner(AiTaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(initialDelayString = "${mes.task.recover-initial-delay-ms:45000}",
            fixedDelayString = "${mes.task.recover-fixed-delay-ms:600000}")
    public void recover() {
        try {
            int recovered = taskService.recoverInterrupted();
            if (recovered > 0) {
                log.warn("AI 多步任务恢复 {} 个中断实例", recovered);
            }
        } catch (RuntimeException ex) {
            log.warn("AI 多步任务恢复失败：{}", ex.getMessage());
        }
    }

    @Scheduled(initialDelayString = "${mes.task.scan-initial-delay-ms:60000}",
            fixedDelayString = "${mes.task.scan-fixed-delay-ms:60000}")
    public void scan() {
        try {
            int executed = taskService.runScheduled();
            if (executed > 0) {
                log.info("AI 多步任务本轮执行 {} 条", executed);
            }
        } catch (RuntimeException ex) {
            log.warn("AI 多步任务定时扫描失败：{}", ex.getMessage());
        }
    }

    /**
     * 超时收敛：卡在 RUNNING 超过各自 deadline_time 的实例不能永远挂着。
     *
     * <p>和 scan 分开成独立定时器，是为了让「执行到期任务」和「清理卡死实例」互不拖累——
     * 某条定义执行慢不该影响别的实例被及时收敛。</p>
     */
    @Scheduled(initialDelayString = "${mes.task.expire-initial-delay-ms:120000}",
            fixedDelayString = "${mes.task.expire-fixed-delay-ms:120000}")
    public void expire() {
        try {
            int expired = taskService.expireOverdue();
            if (expired > 0) {
                log.warn("AI 多步任务超时收敛 {} 个实例", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("AI 多步任务超时收敛失败：{}", ex.getMessage());
        }
    }
}
