package com.neuedu.ems.vo.mfg;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 只读派工预检；提交时服务端重新计算，不信任客户端回传的候选与时间。 */
public record DispatchPreparationVO(Long workOrderId, String lineName, LocalDateTime plannedStartTime,
        LocalDateTime dueTime, BigDecimal estimatedMinutes, LocalDateTime planDeadline,
        List<TeamOption> teams, List<ComponentDependency> dependencies, List<String> blockers,
        List<String> notes) {
    public record TeamOption(Long teamId, String teamName) {}
    public record ComponentDependency(Long workOrderId, String workOrderNo, String status,
                                      LocalDateTime expectedFinishTime) {}
}
