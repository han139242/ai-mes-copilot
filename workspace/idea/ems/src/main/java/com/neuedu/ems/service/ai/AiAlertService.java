package com.neuedu.ems.service.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.entity.ai.AiAlertAction;
import com.neuedu.ems.entity.ai.AiAlertEvent;
import com.neuedu.ems.entity.ai.AiAlertRule;
import com.neuedu.ems.entity.ai.AiAlertScan;

import java.util.List;
import java.util.Map;

/**
 * 主动预警中心服务。
 *
 * <p>和"异常闭环"的区别：异常闭环盯的是已经发生的三类硬异常；预警中心盯的是
 * <b>还没出事但正在逼近限度的指标</b>——库存快见底、批次快过期、工单快超期、
 * 设备故障拖太久、排程快排不下……每个指标的限度由使用者自己设定，可以随时改。</p>
 *
 * <p>一轮扫描的完整闭环：发现命中 → 按规则自动做能做的动作（补货建议、异常台账、
 * 维修单、通知）→ 指派到具体责任人并要求确认 → 下一轮再扫时若指标已回到限度内，
 * 自动复核关闭并留痕。</p>
 */
public interface AiAlertService {

    /** 指标目录：系统能盯哪些事、默认限度、默认自动动作。 */
    List<AlertMetricCatalog.Metric> metrics();

    /** 顶部概览。 */
    Map<String, Object> overview();

    /** 规则分页。 */
    Page<AiAlertRule> pageRules(Integer current, Integer size, String status, String metricCode, String keyword);

    /** 规则详情。 */
    AiAlertRule getRule(Long ruleId);

    /** 新建规则。 */
    Long saveRule(AiAlertRule rule, Long operatorId);

    /** 修改规则（含限度）。 */
    boolean updateRule(AiAlertRule rule, Long operatorId);

    /** 删除规则。 */
    boolean removeRule(Long ruleId, Long operatorId);

    /** 启用/暂停规则。 */
    boolean changeStatus(Long ruleId, String status, Long operatorId);

    /** 试算：按当前规则跑一遍，只返回命中结果，不写库、不发通知。 */
    Map<String, Object> preview(Long ruleId);

    /** 手工触发一轮扫描。 */
    Map<String, Object> scan(Long operatorId);

    /** 定时扫描：按时间窗幂等。 */
    int scanScheduled();

    /** 扫描记录分页。 */
    Page<AiAlertScan> pageScans(Integer current, Integer size, String status);

    /** 预警事件分页。 */
    Page<AiAlertEvent> pageEvents(Integer current, Integer size, String status, String severity,
                                  String metricCode, String keyword);

    /** 事件详情。 */
    AiAlertEvent getEvent(Long alertId);

    /** 事件已执行的自动动作明细。 */
    List<AiAlertAction> actions(Long alertId);

    /** 人工确认收到（把 OPEN 置为 ACKNOWLEDGED）。 */
    boolean acknowledge(Long alertId, String note, Long operatorId);

    /** 人工关闭（业务已线下处理，不必等指标恢复）。 */
    boolean close(Long alertId, String note, Long operatorId);

    /** 未关闭事件数，供铃铛角标使用。 */
    Map<String, Object> pendingSummary();
}
