package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.common.NotificationEventPayload;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.ai.AiAlertAction;
import com.neuedu.ems.entity.ai.AiAlertEvent;
import com.neuedu.ems.entity.ai.AiAlertRule;
import com.neuedu.ems.entity.ai.AiAlertScan;
import com.neuedu.ems.entity.eam.EamRepairOrder;
import com.neuedu.ems.entity.mfg.MfgException;
import com.neuedu.ems.entity.wms.WmsReplenishmentSuggestion;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.ai.AiAlertActionMapper;
import com.neuedu.ems.mapper.ai.AiAlertEventMapper;
import com.neuedu.ems.mapper.ai.AiAlertRuleMapper;
import com.neuedu.ems.mapper.ai.AiAlertScanMapper;
import com.neuedu.ems.mapper.ai.AlertMetricMapper;
import com.neuedu.ems.mapper.eam.EamRepairOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgExceptionMapper;
import com.neuedu.ems.mapper.wms.WmsReplenishmentSuggestionMapper;
import com.neuedu.ems.service.ai.AiAlertService;
import com.neuedu.ems.service.ai.AlertMetricCatalog;
import com.neuedu.ems.service.ai.AlertStreamService;
import com.neuedu.ems.service.impl.sys.ResponsibilityNotificationSupport;
import com.neuedu.ems.service.wms.WmsReplenishmentSuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 主动预警中心实现。
 *
 * <p>每个指标都由 {@link AlertMetricMapper} 提供只读取数，阈值来自规则表，
 * 所以"低于多少就预警"是使用者随时可改的数据，不是写死在代码里的常量。</p>
 *
 * <p>一轮扫描分四步走，对应"主动发现 → 自动做能做的 → 找人处理剩下的 → 检查结果"：</p>
 * <ol>
 *   <li>发现：按启用规则逐条取数，与预警/严重限度比较，命中即落 {@code ai_alert_event}；</li>
 *   <li>自动处置：按规则声明的动作自动生成补货建议、异常台账、维修单；</li>
 *   <li>指派：把事件通知给指标对应部门的主管，并要求确认收到；</li>
 *   <li>复核：指标回到限度内的事件自动关闭并写关闭原因，形成闭环留痕。</li>
 * </ol>
 */
@Service
public class AiAlertServiceImpl implements AiAlertService {

    private static final Logger log = LoggerFactory.getLogger(AiAlertServiceImpl.class);

    private static final String ALERT_NO_PREFIX = "AL-";
    private static final String AUTO_EXCEPTION_PREFIX = "EXC-ALERT-";
    private static final String AUTO_REPAIR_PREFIX = "RP-ALERT-";
    private static final DateTimeFormatter WINDOW_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /**
     * 规则编号的时间戳。同一指标常被建成多条规则（不同仓库、不同产线各一条），
     * 只用分钟精度会撞 {@code uk_ai_alert_rule_no}，用户看到的是"系统异常"。
     */
    private static final DateTimeFormatter RULE_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    /**
     * 定时扫描的幂等键时间戳。扫描间隔默认 30 秒，若仍用分钟精度，
     * 同一分钟内的第二轮会被幂等键判成"已扫过"直接返回，30 秒节奏等于失效。
     */
    private static final DateTimeFormatter SCAN_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String STATUS_OPEN = AiAlertEvent.STATUS_OPEN;
    private static final String STATUS_ACKNOWLEDGED = AiAlertEvent.STATUS_ACKNOWLEDGED;
    private static final String STATUS_CLOSED = AiAlertEvent.STATUS_CLOSED;
    private static final String RULE_ENABLED = AiAlertRule.STATUS_ENABLED;

    private static final String ACTION_NOTIFY = AiAlertRule.ACTION_NOTIFY;
    private static final String ACTION_REPLENISH = AiAlertRule.ACTION_REPLENISH;
    private static final String ACTION_EXCEPTION = AiAlertRule.ACTION_EXCEPTION;
    private static final String ACTION_REPAIR = AiAlertRule.ACTION_REPAIR;

    private static final int MAX_ROWS_PER_METRIC = 500;
    /** 补货建议的两个口径常量，和 WmsReplenishmentSuggestionServiceImpl 保持一致。 */
    private static final String REPLENISH_SOURCE_INVENTORY_WARNING = "INVENTORY_WARNING";
    private static final String REPLENISH_STATUS_PENDING = "SUBMITTED";

    private final AiAlertRuleMapper ruleMapper;
    private final AiAlertEventMapper eventMapper;
    private final AiAlertActionMapper actionMapper;
    private final AiAlertScanMapper scanMapper;
    private final AlertMetricMapper metricMapper;
    private final MfgExceptionMapper exceptionMapper;
    private final EamRepairOrderMapper repairOrderMapper;
    private final WmsReplenishmentSuggestionService replenishmentService;
    private final WmsReplenishmentSuggestionMapper replenishmentSuggestionMapper;
    private final ResponsibilityNotificationSupport notificationSupport;
    private final AlertStreamService streamService;

    public AiAlertServiceImpl(AiAlertRuleMapper ruleMapper,
                              AiAlertEventMapper eventMapper,
                              AiAlertActionMapper actionMapper,
                              AiAlertScanMapper scanMapper,
                              AlertMetricMapper metricMapper,
                              MfgExceptionMapper exceptionMapper,
                              EamRepairOrderMapper repairOrderMapper,
                              WmsReplenishmentSuggestionService replenishmentService,
                              WmsReplenishmentSuggestionMapper replenishmentSuggestionMapper,
                              ResponsibilityNotificationSupport notificationSupport,
                              AlertStreamService streamService) {
        this.ruleMapper = ruleMapper;
        this.eventMapper = eventMapper;
        this.actionMapper = actionMapper;
        this.scanMapper = scanMapper;
        this.metricMapper = metricMapper;
        this.exceptionMapper = exceptionMapper;
        this.repairOrderMapper = repairOrderMapper;
        this.replenishmentService = replenishmentService;
        this.replenishmentSuggestionMapper = replenishmentSuggestionMapper;
        this.notificationSupport = notificationSupport;
        this.streamService = streamService;
    }

    // ======================================================================
    // 指标目录与规则维护
    // ======================================================================

    @Override
    public List<AlertMetricCatalog.Metric> metrics() {
        return AlertMetricCatalog.all();
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openCount", countEvents(STATUS_OPEN));
        data.put("acknowledgedCount", countEvents(STATUS_ACKNOWLEDGED));
        data.put("criticalCount", countEventsBySeverity(STATUS_OPEN, AiAlertEvent.SEVERITY_CRITICAL));
        data.put("closedTodayCount", countClosedToday());
        data.put("ruleCount", countRules(null));
        data.put("enabledRuleCount", countRules(RULE_ENABLED));
        data.put("metricCount", AlertMetricCatalog.all().size());
        data.put("lastScan", lastScan());
        return data;
    }

    @Override
    public Page<AiAlertRule> pageRules(Integer current, Integer size, String status, String metricCode,
                                       String keyword) {
        Page<AiAlertRule> page = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        LambdaQueryWrapper<AiAlertRule> wrapper = new LambdaQueryWrapper<AiAlertRule>()
                .eq(AiAlertRule::getDeletedFlag, 0)
                .eq(StringUtils.hasText(status), AiAlertRule::getStatus, status)
                .eq(StringUtils.hasText(metricCode), AiAlertRule::getMetricCode, metricCode)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(AiAlertRule::getRuleName, keyword)
                        .or().like(AiAlertRule::getRuleNo, keyword))
                .orderByAsc(AiAlertRule::getRuleId);
        return ruleMapper.selectPage(page, wrapper);
    }

    @Override
    public AiAlertRule getRule(Long ruleId) {
        AiAlertRule rule = ruleId == null ? null : ruleMapper.selectById(ruleId);
        if (rule == null || Integer.valueOf(1).equals(rule.getDeletedFlag())) {
            throw business("预警规则不存在或已删除");
        }
        return rule;
    }

    @Override
    @Transactional
    public Long saveRule(AiAlertRule rule, Long operatorId) {
        AlertMetricCatalog.Metric metric = requireMetric(rule.getMetricCode());
        validateThreshold(rule, metric);
        LocalDateTime now = LocalDateTime.now();
        rule.setRuleId(null);
        rule.setRuleNo(StringUtils.hasText(rule.getRuleNo())
                ? rule.getRuleNo().trim()
                : "AR-" + metric.code() + "-" + now.format(RULE_NO_FORMAT));
        rule.setRuleName(StringUtils.hasText(rule.getRuleName()) ? rule.getRuleName().trim() : metric.name());
        rule.setScopeType(defaultText(rule.getScopeType(), AiAlertRule.SCOPE_ALL));
        rule.setComparator(defaultText(rule.getComparator(), metric.comparator()));
        rule.setThresholdUnit(defaultText(rule.getThresholdUnit(), metric.unit()));
        rule.setAutoActions(defaultText(rule.getAutoActions(), metric.autoActions()));
        rule.setAcknowledgmentRequired(rule.getAcknowledgmentRequired() == null ? 1 : rule.getAcknowledgmentRequired());
        rule.setIntervalMinutes(rule.getIntervalMinutes() == null ? 5 : Math.max(1, rule.getIntervalMinutes()));
        rule.setCooldownMinutes(rule.getCooldownMinutes() == null ? 30 : Math.max(0, rule.getCooldownMinutes()));
        rule.setStatus(defaultText(rule.getStatus(), RULE_ENABLED));
        rule.setHitCount(0);
        rule.setVersion(0);
        rule.setCreatedBy(operatorId);
        rule.setCreatedTime(now);
        rule.setUpdatedBy(operatorId);
        rule.setUpdatedTime(now);
        rule.setDeletedFlag(0);
        // 规则是软删除的，编号上的唯一键却还在：同编号重建前先把历史那条改名让位，
        // 否则会撞 uk_ai_alert_rule_no，用户看到的却是"系统异常"。
        ruleMapper.update(null, new UpdateWrapper<AiAlertRule>()
                .eq("rule_no", rule.getRuleNo())
                .eq("deleted_flag", 1)
                .setSql("rule_no = CONCAT(rule_no, '#', rule_id)"));
        if (ruleMapper.insert(rule) != 1) {
            throw business("预警规则保存失败");
        }
        return rule.getRuleId();
    }

    @Override
    @Transactional
    public boolean updateRule(AiAlertRule rule, Long operatorId) {
        AiAlertRule existing = getRule(rule.getRuleId());
        AlertMetricCatalog.Metric metric = requireMetric(
                StringUtils.hasText(rule.getMetricCode()) ? rule.getMetricCode() : existing.getMetricCode());
        AiAlertRule merged = new AiAlertRule();
        merged.setRuleId(existing.getRuleId());
        merged.setRuleName(defaultText(rule.getRuleName(), existing.getRuleName()));
        merged.setMetricCode(metric.code());
        merged.setScopeType(defaultText(rule.getScopeType(), existing.getScopeType()));
        merged.setScopeValue(rule.getScopeValue() == null ? existing.getScopeValue() : rule.getScopeValue());
        merged.setComparator(defaultText(rule.getComparator(), existing.getComparator()));
        merged.setWarningThreshold(rule.getWarningThreshold() == null
                ? existing.getWarningThreshold() : rule.getWarningThreshold());
        merged.setCriticalThreshold(rule.getCriticalThreshold() == null
                ? existing.getCriticalThreshold() : rule.getCriticalThreshold());
        merged.setThresholdUnit(defaultText(rule.getThresholdUnit(), existing.getThresholdUnit()));
        merged.setAutoActions(defaultText(rule.getAutoActions(), existing.getAutoActions()));
        merged.setAcknowledgmentRequired(rule.getAcknowledgmentRequired() == null
                ? existing.getAcknowledgmentRequired() : rule.getAcknowledgmentRequired());
        merged.setIntervalMinutes(rule.getIntervalMinutes() == null
                ? existing.getIntervalMinutes() : rule.getIntervalMinutes());
        merged.setCooldownMinutes(rule.getCooldownMinutes() == null
                ? existing.getCooldownMinutes() : rule.getCooldownMinutes());
        merged.setStatus(defaultText(rule.getStatus(), existing.getStatus()));
        merged.setRemark(rule.getRemark() == null ? existing.getRemark() : rule.getRemark());
        validateThreshold(merged, metric);
        merged.setVersion(existing.getVersion());
        merged.setUpdatedBy(operatorId);
        merged.setUpdatedTime(LocalDateTime.now());
        if (ruleMapper.updateById(merged) != 1) {
            throw business("预警规则已被其他请求修改，请刷新后重试");
        }
        return true;
    }

    @Override
    @Transactional
    public boolean removeRule(Long ruleId, Long operatorId) {
        AiAlertRule existing = getRule(ruleId);
        AiAlertRule update = new AiAlertRule();
        update.setRuleId(existing.getRuleId());
        update.setDeletedFlag(1);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(LocalDateTime.now());
        return ruleMapper.updateById(update) == 1;
    }

    @Override
    @Transactional
    public boolean changeStatus(Long ruleId, String status, Long operatorId) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of(AiAlertRule.STATUS_ENABLED, AiAlertRule.STATUS_PAUSED, AiAlertRule.STATUS_DISABLED)
                .contains(normalized)) {
            throw business("规则状态只能是 ENABLED、PAUSED 或 DISABLED");
        }
        AiAlertRule existing = getRule(ruleId);
        AiAlertRule update = new AiAlertRule();
        update.setRuleId(existing.getRuleId());
        update.setStatus(normalized);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(LocalDateTime.now());
        if (ruleMapper.updateById(update) != 1) {
            throw business("预警规则已被其他请求修改，请刷新后重试");
        }
        return true;
    }

    // ======================================================================
    // 试算与扫描
    // ======================================================================

    @Override
    public Map<String, Object> preview(Long ruleId) {
        AiAlertRule rule = getRule(ruleId);
        AlertMetricCatalog.Metric metric = requireMetric(rule.getMetricCode());
        List<Map<String, Object>> rows = loadMetricRows(metric);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!inScope(rule, row)) {
                continue;
            }
            BigDecimal value = decimal(row.get("metricValue"));
            String severity = severityOf(rule, value);
            if (severity == null) {
                continue;
            }
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("bizId", row.get("bizId"));
            hit.put("bizNo", row.get("bizNo"));
            hit.put("subjectName", row.get("subjectName"));
            hit.put("extraText", row.get("extraText"));
            hit.put("metricValue", value);
            hit.put("thresholdValue", severityValue(rule, severity));
            hit.put("severity", severity);
            hit.put("comparator", rule.getComparator());
            hit.put("unit", rule.getThresholdUnit());
            hits.add(hit);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", rule.getRuleId());
        data.put("ruleName", rule.getRuleName());
        data.put("metricCode", metric.code());
        data.put("metricName", metric.name());
        data.put("evaluatedCount", rows.size());
        data.put("hitCount", hits.size());
        data.put("hits", hits.size() > 100 ? hits.subList(0, 100) : hits);
        data.put("truncated", hits.size() > 100);
        return data;
    }

    @Override
    @Transactional
    public Map<String, Object> scan(Long operatorId) {
        // 手工点"立即扫描"是人的明确意图，不该被规则的扫描间隔挡住，强制全量取数。
        return execute(AiAlertScan.TRIGGER_MANUAL, "MANUAL:" + System.currentTimeMillis(), operatorId, true);
    }

    @Override
    @Transactional
    public int scanScheduled() {
        String window = LocalDateTime.now().format(SCAN_KEY_FORMAT);
        Map<String, Object> result = execute(AiAlertScan.TRIGGER_SCHEDULE, "SCHEDULE:" + window, null, false);
        Object opened = result.get("openedCount");
        return opened instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 一轮完整扫描。同一幂等键重复触发直接返回原结果，避免定时任务重入时重复弹窗。
     *
     * <p>定时心跳只是"来看看"，一条规则都没到期时这一轮什么也不会发生：不取数、不比对、
     * 没有新预警也没有结论。这种空转轮不落扫描台账——否则 60 秒一跳、规则 10 分钟才扫一次，
     * 台账里十行有九行是"规则 0 条、对象 0 个"，把真正取过数的轮次全埋掉了。
     * 台账只记有结论的轮次，条数才有意义：它就是"系统真的取过几次数"。</p>
     *
     * @param force 为 true 时忽略每条规则自己的扫描间隔（手工触发用，也总是落台账），
     *              为 false 时只执行距上次扫描已满 interval_minutes 的规则（定时任务用）。
     */
    private Map<String, Object> execute(String triggerType, String runKey, Long operatorId, boolean force) {
        AiAlertScan duplicated = findScannedRun(runKey);
        if (duplicated != null) {
            return duplicatedScanResult(duplicated);
        }

        LocalDateTime startedAt = LocalDateTime.now();
        List<AiAlertRule> rules = loadEnabledRules();
        if (!force && !hasDueRule(rules, startedAt)) {
            return idleScanResult(triggerType, rules.size());
        }

        long beginMillis = System.currentTimeMillis();
        AiAlertScan scan = insertRunningScan(triggerType, runKey, operatorId, startedAt);
        ScanTally tally = new ScanTally();

        try {
            runRules(rules, force, startedAt, operatorId, tally);
            finishScan(scan, tally, rules.size(), beginMillis, operatorId);
            return scanResult(scan, tally);
        } catch (RuntimeException ex) {
            failScan(scan, beginMillis, ex);
            throw ex;
        }
    }

    /** 本轮是否至少有一条规则到期该取数；指标目录里查不到的规则按不执行处理，与 runRule 保持一致。 */
    private boolean hasDueRule(List<AiAlertRule> rules, LocalDateTime now) {
        for (AiAlertRule rule : rules) {
            if (AlertMetricCatalog.get(rule.getMetricCode()) == null) {
                continue;
            }
            if (due(rule, now)) {
                return true;
            }
        }
        return false;
    }

    /** 空转轮的返回体：不落库，但要告诉调用方"心跳跑过了、只是还没到任何规则的扫描时间"。 */
    private static Map<String, Object> idleScanResult(String triggerType, int enabledRules) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanId", null);
        data.put("scanNo", null);
        data.put("status", AiAlertScan.STATUS_SUCCEEDED);
        data.put("triggerType", triggerType);
        data.put("ruleCount", 0);
        data.put("evaluatedCount", 0);
        data.put("openedCount", 0);
        data.put("escalatedCount", 0);
        data.put("repeatedCount", 0);
        data.put("closedCount", 0);
        data.put("notifiedCount", 0);
        data.put("autoActionCount", 0);
        data.put("summary", "本轮没有规则到达自己的扫描时间（共 " + enabledRules + " 条启用），未取数，不记扫描记录");
        data.put("elapsedMs", 0L);
        data.put("idle", true);
        data.put("idempotent", false);
        return data;
    }

    /** 按幂等键查同一轮是否已经扫过，扫过就直接返回原记录。 */
    private AiAlertScan findScannedRun(String runKey) {
        return scanMapper.selectOne(new LambdaQueryWrapper<AiAlertScan>()
                .eq(AiAlertScan::getScanNo, runKey)
                .eq(AiAlertScan::getDeletedFlag, 0)
                .last("LIMIT 1"));
    }

    /** 幂等命中时的返回体：照抄原扫描记录的结论，不重复执行。 */
    private static Map<String, Object> duplicatedScanResult(AiAlertScan duplicated) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanId", duplicated.getScanId());
        data.put("scanNo", duplicated.getScanNo());
        data.put("status", duplicated.getStatus());
        data.put("openedCount", duplicated.getOpenedCount());
        data.put("summary", "本次触发已扫描过，按幂等键返回原结果");
        data.put("idempotent", true);
        return data;
    }

    /** 先落一条运行中的扫描主记录，后面的执行过程都挂在它下面。 */
    private AiAlertScan insertRunningScan(String triggerType, String runKey, Long operatorId, LocalDateTime startedAt) {
        AiAlertScan scan = new AiAlertScan();
        scan.setScanNo(runKey);
        scan.setTriggerType(triggerType);
        scan.setStatus(AiAlertScan.STATUS_RUNNING);
        scan.setRuleCount(0);
        scan.setEvaluatedCount(0);
        scan.setOpenedCount(0);
        scan.setEscalatedCount(0);
        scan.setRepeatedCount(0);
        scan.setClosedCount(0);
        scan.setVersion(0);
        scan.setCreatedBy(operatorId);
        scan.setCreatedTime(startedAt);
        scan.setUpdatedBy(operatorId);
        scan.setUpdatedTime(startedAt);
        scan.setDeletedFlag(0);
        scanMapper.insert(scan);
        return scan;
    }

    /** 取出本轮要评估的启用规则，按规则号顺序执行。 */
    private List<AiAlertRule> loadEnabledRules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<AiAlertRule>()
                .eq(AiAlertRule::getDeletedFlag, 0)
                .eq(AiAlertRule::getStatus, RULE_ENABLED)
                .orderByAsc(AiAlertRule::getRuleId));
    }

    /** 逐条规则执行，把结果累加到本轮计数里。 */
    private void runRules(List<AiAlertRule> rules, boolean force, LocalDateTime startedAt,
                          Long operatorId, ScanTally tally) {
        for (AiAlertRule rule : rules) {
            runRule(rule, force, startedAt, operatorId, tally);
        }
    }

    /** 单条规则一轮：到期才取数并逐行比对，最后推进它的上次扫描时间。 */
    private void runRule(AiAlertRule rule, boolean force, LocalDateTime startedAt,
                         Long operatorId, ScanTally tally) {
        AlertMetricCatalog.Metric metric = AlertMetricCatalog.get(rule.getMetricCode());
        if (metric == null) {
            return;
        }
        // 每条规则可以有自己的扫描频率：定时心跳每 60 秒来一次，但规则只在自己的
        // interval_minutes 到期时才真正取数比对。否则界面上"每 N 分钟扫一次"就只是
        // 一个存下来没人读的字段，用户改了也不会有任何变化。
        // 所有规则都没到期的那一轮由 execute 直接判为空转轮，连台账都不落。
        if (!force && !due(rule, startedAt)) {
            return;
        }
        tally.dueRules++;
        List<Map<String, Object>> rows = loadMetricRows(metric);
        tally.evaluated += rows.size();
        Set<String> hitKeys = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            RowOutcome outcome = applyRow(rule, metric, row, hitKeys, startedAt, operatorId);
            if (outcome != null) {
                tally.add(outcome);
            }
        }
        tally.closed += closeRecovered(rule, hitKeys, startedAt, operatorId);
        touchRuleScanTime(rule, startedAt, operatorId);
    }

    /** 本轮实际取过数才推进"上次扫描时间"，否则下轮 due() 还会认为到期。 */
    private void touchRuleScanTime(AiAlertRule rule, LocalDateTime startedAt, Long operatorId) {
        UpdateWrapper<AiAlertRule> scanTimeUpdate = new UpdateWrapper<AiAlertRule>()
                .eq("rule_id", rule.getRuleId())
                .set("last_scan_time", startedAt)
                .set("updated_time", startedAt);
        if (operatorId != null) {
            scanTimeUpdate.set("updated_by", operatorId);
        }
        ruleMapper.update(null, scanTimeUpdate);
    }

    /** 成功收尾：写回主记录的状态、各项计数、耗时与摘要。 */
    private void finishScan(AiAlertScan scan, ScanTally tally, int enabledRules, long beginMillis, Long operatorId) {
        scan.setStatus(AiAlertScan.STATUS_SUCCEEDED);
        scan.setRuleCount(tally.dueRules);
        scan.setEvaluatedCount(tally.evaluated);
        scan.setOpenedCount(tally.opened);
        scan.setEscalatedCount(tally.escalated);
        scan.setRepeatedCount(tally.repeated);
        scan.setClosedCount(tally.closed);
        scan.setElapsedMs(System.currentTimeMillis() - beginMillis);
        scan.setSummary("本轮按各自扫描频率执行 " + tally.dueRules + " 条规则（共 " + enabledRules + " 条启用）、"
                + tally.evaluated + " 个对象，新预警 " + tally.opened
                + " 条，升级 " + tally.escalated + " 条，持续命中 " + tally.repeated + " 条，自动动作 " + tally.autoActions
                + " 项，通知 " + tally.notified + " 条，复核关闭 " + tally.closed + " 条");
        scan.setUpdatedBy(operatorId);
        scan.setUpdatedTime(LocalDateTime.now());
        scanMapper.updateById(scan);
    }

    /** 成功扫描的返回体。 */
    private static Map<String, Object> scanResult(AiAlertScan scan, ScanTally tally) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanId", scan.getScanId());
        data.put("scanNo", scan.getScanNo());
        data.put("status", scan.getStatus());
        data.put("ruleCount", tally.dueRules);
        data.put("evaluatedCount", tally.evaluated);
        data.put("openedCount", tally.opened);
        data.put("escalatedCount", tally.escalated);
        data.put("repeatedCount", tally.repeated);
        data.put("closedCount", tally.closed);
        data.put("notifiedCount", tally.notified);
        data.put("autoActionCount", tally.autoActions);
        data.put("summary", scan.getSummary());
        data.put("elapsedMs", scan.getElapsedMs());
        data.put("idempotent", false);
        return data;
    }

    /** 失败收尾：主记录标失败并留错误摘要，原异常继续往上抛。 */
    private void failScan(AiAlertScan scan, long beginMillis, RuntimeException ex) {
        scan.setStatus(AiAlertScan.STATUS_FAILED);
        scan.setElapsedMs(System.currentTimeMillis() - beginMillis);
        scan.setErrorMessage(trim(ex.getMessage()));
        scan.setUpdatedTime(LocalDateTime.now());
        scanMapper.updateById(scan);
        log.warn("主动预警扫描失败：{}", ex.getMessage());
    }

    /** 一轮扫描内各项计数的累加器，等价于原先散在方法体里的局部变量。 */
    private static final class ScanTally {
        private int opened;
        private int escalated;
        private int repeated;
        private int closed;
        private int notified;
        private int autoActions;
        private int evaluated;
        private int dueRules;

        /** 累加一行比对的结果。 */
        private void add(RowOutcome outcome) {
            opened += outcome.opened();
            escalated += outcome.escalated();
            repeated += outcome.repeated();
            notified += outcome.notified();
            autoActions += outcome.autoActions();
        }
    }

    /** 单行比对的结果计数；不适用（不在范围或无需预警）返回 null。 */
    private record RowOutcome(int opened, int escalated, int repeated, int notified, int autoActions) {
    }

    /** 比对一行指标：命中就落事件、推送、通知并跑自动动作，返回各项计数。 */
    private RowOutcome applyRow(AiAlertRule rule, AlertMetricCatalog.Metric metric, Map<String, Object> row,
                                Set<String> hitKeys, LocalDateTime startedAt, Long operatorId) {
        if (!inScope(rule, row)) {
            return null;
        }
        BigDecimal value = decimal(row.get("metricValue"));
        String severity = severityOf(rule, value);
        if (severity == null) {
            return null;
        }
        String scopeKey = scopeKeyOf(rule, row);
        hitKeys.add(scopeKey);
        Upsert upsert = upsertEvent(rule, metric, row, scopeKey, severity, value, startedAt, operatorId);
        int opened = upsert.outcome() == Outcome.OPENED ? 1 : 0;
        int escalated = upsert.outcome() == Outcome.ESCALATED ? 1 : 0;
        int repeated = upsert.outcome() == Outcome.REPEATED ? 1 : 0;
        AiAlertEvent event = upsert.event();
        if (upsert.outcome() != Outcome.REPEATED) {
            pushEvent(event);
        }
        int notified = 0;
        if (shouldNotify(rule, event, startedAt) && notify(rule, metric, event, startedAt, operatorId)) {
            notified = 1;
        }
        int autoActions = runAutoActions(rule, metric, event, startedAt, operatorId);
        return new RowOutcome(opened, escalated, repeated, notified, autoActions);
    }

    /** 一次 upsert 的结果：新开、升级、还是仅持续命中。 */
    private enum Outcome {
        OPENED, ESCALATED, REPEATED
    }

    /** upsert 的返回值：结果类型 + 落库后的最新事件快照。 */
    private record Upsert(Outcome outcome, AiAlertEvent event) {
    }

    /**
     * 落一条预警事件：同一规则同一对象已有未关闭事件就更新现值与命中次数，
     * 严重度变高则升级；已关闭但问题再次出现则重新打开原记录（编号唯一，不能新插）。
     */
    private Upsert upsertEvent(AiAlertRule rule, AlertMetricCatalog.Metric metric, Map<String, Object> row,
                               String scopeKey, String severity, BigDecimal value,
                               LocalDateTime now, Long operatorId) {
        String alertNo = ALERT_NO_PREFIX + rule.getRuleId() + "-" + scopeKey;
        AiAlertEvent existing = eventMapper.selectOne(new LambdaQueryWrapper<AiAlertEvent>()
                .eq(AiAlertEvent::getAlertNo, alertNo)
                .eq(AiAlertEvent::getDeletedFlag, 0)
                .last("LIMIT 1"));
        String title = titleOf(metric, row);
        String summary = summaryOf(rule, metric, row, value, severity);

        if (existing != null && !STATUS_CLOSED.equals(existing.getStatus())) {
            boolean escalated = severityRank(severity) > severityRank(existing.getSeverity());
            AiAlertEvent update = new AiAlertEvent();
            update.setAlertId(existing.getAlertId());
            update.setSeverity(severity);
            update.setTitle(title);
            update.setSummary(summary);
            update.setCurrentValue(value);
            update.setThresholdValue(severityValue(rule, severity));
            update.setLastSeenTime(now);
            update.setOccurrenceCount((existing.getOccurrenceCount() == null ? 0 : existing.getOccurrenceCount()) + 1);
            update.setVersion(existing.getVersion());
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            eventMapper.updateById(update);
            AiAlertEvent merged = merge(existing, update);
            // 乐观锁会把库里的 version 自增，内存里的旧值必须同步跟上，
            // 否则同一轮里紧接着的通知时间回写会拿旧 version 更新失败。
            merged.setVersion(nextVersion(existing.getVersion()));
            return new Upsert(escalated ? Outcome.ESCALATED : Outcome.REPEATED, merged);
        }

        if (existing != null) {
            AiAlertEvent reopen = new AiAlertEvent();
            reopen.setAlertId(existing.getAlertId());
            reopen.setSeverity(severity);
            reopen.setStatus(STATUS_OPEN);
            reopen.setTitle(title);
            reopen.setSummary(summary);
            reopen.setCurrentValue(value);
            reopen.setThresholdValue(severityValue(rule, severity));
            reopen.setLastSeenTime(now);
            reopen.setLastNotifiedTime(null);
            reopen.setOccurrenceCount((existing.getOccurrenceCount() == null ? 0 : existing.getOccurrenceCount()) + 1);
            reopen.setClosedBy(null);
            reopen.setClosedTime(null);
            reopen.setCloseReason(null);
            reopen.setVersion(existing.getVersion());
            reopen.setUpdatedBy(operatorId);
            reopen.setUpdatedTime(now);
            eventMapper.updateById(reopen);
            AiAlertEvent reopened = merge(existing, reopen);
            reopened.setVersion(nextVersion(existing.getVersion()));
            return new Upsert(Outcome.OPENED, reopened);
        }

        AiAlertEvent event = new AiAlertEvent();
        event.setAlertNo(alertNo);
        event.setRuleId(rule.getRuleId());
        event.setMetricCode(metric.code());
        event.setScopeType(rule.getScopeType());
        event.setScopeKey(scopeKey);
        event.setSeverity(severity);
        event.setStatus(STATUS_OPEN);
        event.setTitle(title);
        event.setSummary(summary);
        event.setCurrentValue(value);
        event.setThresholdValue(severityValue(rule, severity));
        event.setThresholdUnit(rule.getThresholdUnit());
        event.setBizType(metric.code());
        event.setBizId(longValue(row.get("bizId")));
        event.setBizNo(stringValue(row.get("bizNo")));
        event.setRoutePath(metric.routePath());
        event.setFirstSeenTime(now);
        event.setLastSeenTime(now);
        event.setOccurrenceCount(1);
        event.setVersion(0);
        event.setCreatedBy(operatorId);
        event.setCreatedTime(now);
        event.setUpdatedBy(operatorId);
        event.setUpdatedTime(now);
        event.setDeletedFlag(0);
        if (eventMapper.insert(event) != 1) {
            throw business("预警事件写入失败");
        }
        // 命中计数走原子自增：同一条规则在一轮扫描里可能命中很多个对象，
        // 如果每次都拿规则读出来的旧 version 去 updateById，第二次就会返回 0 行、
        // 被审计拦截器判成乐观锁冲突，整轮扫描回滚，预警一条都留不下来。
        UpdateWrapper<AiAlertRule> hitUpdate = new UpdateWrapper<AiAlertRule>()
                .eq("rule_id", rule.getRuleId())
                .setSql("hit_count = hit_count + 1")
                .set("last_scan_time", now)
                .set("updated_time", now);
        if (operatorId != null) {
            hitUpdate.set("updated_by", operatorId);
        }
        ruleMapper.update(null, hitUpdate);
        return new Upsert(Outcome.OPENED, event);
    }

    /** 事件回到限度内后自动复核关闭，并写明关闭原因，便于追溯"谁在什么时候把它关掉的"。 */
    private int closeRecovered(AiAlertRule rule, Set<String> hitKeys, LocalDateTime now, Long operatorId) {
        List<AiAlertEvent> openEvents = eventMapper.selectList(new LambdaQueryWrapper<AiAlertEvent>()
                .eq(AiAlertEvent::getRuleId, rule.getRuleId())
                .eq(AiAlertEvent::getDeletedFlag, 0)
                .in(AiAlertEvent::getStatus, STATUS_OPEN, STATUS_ACKNOWLEDGED)
                .orderByAsc(AiAlertEvent::getAlertId));
        int closed = 0;
        for (AiAlertEvent event : openEvents) {
            if (hitKeys.contains(event.getScopeKey())) {
                continue;
            }
            AiAlertEvent update = new AiAlertEvent();
            update.setAlertId(event.getAlertId());
            update.setStatus(STATUS_CLOSED);
            update.setCloseReason(AiAlertEvent.CLOSE_REASON_RECOVERED);
            update.setClosedBy(operatorId);
            update.setClosedTime(now);
            update.setVersion(event.getVersion());
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (eventMapper.updateById(update) == 1) {
                closed++;
                recordAction(event.getAlertId(), 90, AiAlertAction.TYPE_VERIFY, AiAlertAction.STATUS_SUCCEEDED,
                        "ALERT_EVENT", event.getAlertNo(),
                        "指标已回到限度内，自动复核关闭", operatorId, now);
                closeAutoException(event, now, operatorId);
                event.setStatus(STATUS_CLOSED);
                event.setCloseReason(AiAlertEvent.CLOSE_REASON_RECOVERED);
                pushEvent(event);
            }
        }
        return closed;
    }

    /**
     * 预警复核关闭时，把第 2 步自动登记到异常台账的那张单子一并关掉。
     *
     * <p>两步是同一件事的两面：指标回到限度内，预警关了、异常台账却还挂着 SUBMITTED，
     * 用户会在异常列表里看到一堆"已经不存在的问题"，待办里也永远清不掉。
     * 所以这里按 {@code EXC-ALERT-<alertNo>} 找到对应单据，一并置 CLOSED 并写明依据。</p>
     */
    private void closeAutoException(AiAlertEvent event, LocalDateTime now, Long operatorId) {
        if (event.getAlertNo() == null || event.getAlertNo().isBlank()) {
            return;
        }
        String exceptionNo = AUTO_EXCEPTION_PREFIX + event.getAlertNo();
        MfgException existing = exceptionMapper.selectOne(new LambdaQueryWrapper<MfgException>()
                .eq(MfgException::getExceptionNo, exceptionNo)
                .eq(MfgException::getDeletedFlag, 0)
                .last("LIMIT 1"));
        if (existing == null || "CLOSED".equals(existing.getStatus())) {
            return;
        }
        MfgException close = new MfgException();
        close.setExceptionId(existing.getExceptionId());
        close.setStatus("CLOSED");
        close.setHandleResult("系统随预警自动复核关闭：指标已回到限度内，无需人工处理");
        close.setUpdatedBy(operatorId);
        close.setUpdatedTime(now);
        exceptionMapper.updateById(close);
    }

    /**
     * 把预警事件实时推给所有在线页面。
     *
     * <p>推送失败不影响扫描：事件已经落库，页面刷新或下次轮询仍能看到，
     * 所以这里只记录送达数，不向上抛异常。</p>
     */
    private void pushEvent(AiAlertEvent event) {
        if (event == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertId", event.getAlertId());
        payload.put("alertNo", event.getAlertNo());
        payload.put("metricCode", event.getMetricCode());
        payload.put("severity", event.getSeverity());
        payload.put("status", event.getStatus());
        payload.put("title", event.getTitle());
        payload.put("summary", event.getSummary());
        payload.put("currentValue", event.getCurrentValue());
        payload.put("thresholdValue", event.getThresholdValue());
        payload.put("thresholdUnit", event.getThresholdUnit());
        payload.put("routePath", event.getRoutePath());
        payload.put("occurrenceCount", event.getOccurrenceCount());
        payload.put("lastSeenTime", event.getLastSeenTime() == null ? null : event.getLastSeenTime().toString());
        try {
            int delivered = streamService.broadcast("alert", payload);
            streamService.logDelivery(delivered, event.getAlertNo());
        } catch (RuntimeException ex) {
            log.debug("预警实时推送失败（不影响入库）：{}", ex.getMessage());
        }
    }

    /** 同一事件在冷却时间内不重复通知，避免每轮扫描都弹一次窗。 */
    /**
     * 这条规则这一轮该不该取数。
     * 定时任务是每 60 秒跳一次心跳，规则自己的扫描频率存在 interval_minutes 里；
     * 只有距上次扫描已满该间隔才执行，否则跳过（跳过就是空转，不取数也不落台账）。
     * 首次扫描（last_scan_time 为空）总是执行。
     */
    private boolean due(AiAlertRule rule, LocalDateTime now) {
        LocalDateTime last = rule.getLastScanTime();
        if (last == null) {
            return true;
        }
        int interval = rule.getIntervalMinutes() == null ? 5 : Math.max(1, rule.getIntervalMinutes());
        return !last.plusMinutes(interval).isAfter(now);
    }

    private boolean shouldNotify(AiAlertRule rule, AiAlertEvent event, LocalDateTime now) {
        LocalDateTime last = event.getLastNotifiedTime();
        if (last == null) {
            return true;
        }
        int cooldown = rule.getCooldownMinutes() == null ? 0 : rule.getCooldownMinutes();
        return !last.plusMinutes(cooldown).isAfter(now);
    }

    /** 按指标所属部门把预警指派给主管，并要求确认收到。 */
    private boolean notify(AiAlertRule rule, AlertMetricCatalog.Metric metric, AiAlertEvent event,
                           LocalDateTime now, Long operatorId) {
        String departmentCode = departmentOf(metric.category());
        String permission = permissionOf(metric.category());
        Long employeeId = notificationSupport.departmentManager(departmentCode);
        List<NotificationEventPayload.RecipientTarget> targets = new ArrayList<>();
        if (employeeId != null) {
            targets.add(notificationSupport.target(employeeId, "指标责任人", permission));
        }
        boolean acknowledgmentRequired = !Integer.valueOf(0).equals(rule.getAcknowledgmentRequired());
        // 事件键必须与时间无关：同一条预警无论提醒多少次，都只对应一条通知记录。
        // 之前把「分钟时间戳 + 命中次数」拼进键里，每过一轮冷却就生成一个全新事件键，
        // 唯一键拦不住，同一条预警就在收件箱里堆成十几行——用户看到的是几百条未读。
        // 冷却期内不重复提醒由 shouldNotify 控制；冷却到期后再次提醒时，
        // 投递服务会把这条通知就地刷新（内容、级别、时间更新）而不是新增一行。
        String eventKey = "ALERT:" + event.getAlertNo();
        try {
            notificationSupport.publishRepeating(eventKey, "AI_ALERT_EVENT", event.getAlertId(), "AI_ALERT_TRIGGERED",
                    categoryOf(metric.category()), event.getSeverity(), event.getTitle(), event.getSummary(),
                    event.getAlertNo(), event.getRoutePath(), operatorId, acknowledgmentRequired, targets);
            AiAlertEvent update = new AiAlertEvent();
            update.setAlertId(event.getAlertId());
            update.setLastNotifiedTime(now);
            update.setVersion(event.getVersion());
            update.setUpdatedTime(now);
            eventMapper.updateById(update);
            event.setLastNotifiedTime(now);
            event.setVersion(nextVersion(event.getVersion()));
            recordAction(event.getAlertId(), 1, ACTION_NOTIFY, AiAlertAction.STATUS_SUCCEEDED,
                    "SYS_NOTIFICATION", event.getAlertNo(),
                    "已通知 " + departmentCode + " 主管（员工 " + employeeId + "）", operatorId, now);
            return true;
        } catch (RuntimeException ex) {
            recordAction(event.getAlertId(), 1, ACTION_NOTIFY, AiAlertAction.STATUS_FAILED,
                    "SYS_NOTIFICATION", event.getAlertNo(), trim(ex.getMessage()), operatorId, now);
            log.warn("预警通知发送失败：{}", ex.getMessage());
            return false;
        }
    }

    /**
     * 执行规则声明的自动动作。能自动做的就自动做掉，做不了的在动作表里写明原因，
     * 由责任人接着处理——这正是"自动做能做的步骤，找人处理剩下的"。
     */
    private int runAutoActions(AiAlertRule rule, AlertMetricCatalog.Metric metric, AiAlertEvent event,
                               LocalDateTime now, Long operatorId) {
        List<String> actions = splitActions(rule.getAutoActions());
        int done = 0;
        int step = 2;
        if (actions.contains(ACTION_REPLENISH) && "MATERIAL_AVAILABLE".equals(metric.code())) {
            done += replenish(event, now, operatorId, step++);
        }
        if (actions.contains(ACTION_EXCEPTION)) {
            done += raiseException(rule, metric, event, now, operatorId, step++);
        }
        if (actions.contains(ACTION_REPAIR) && "EQUIPMENT_FAULT".equals(metric.code())) {
            done += raiseRepair(event, now, operatorId, step++);
        }
        return done;
    }

    /** 自动生成补货建议。已有关联建议时不重复生成，避免同一缺口刷出一堆单子。 */
    private int replenish(AiAlertEvent event, LocalDateTime now, Long operatorId, int stepNo) {
        Long materialId = event.getBizId();
        if (materialId == null) {
            recordAction(event.getAlertId(), stepNo, ACTION_REPLENISH, AiAlertAction.STATUS_SKIPPED,
                    "WMS_REPLENISHMENT_SUGGESTION", null, "事件未关联物料，跳过补货建议", operatorId, now);
            return 0;
        }
        // 每轮扫描都会重新命中同一个物料，这里必须先查有没有还挂着的补货建议，
        // 否则一轮一轮刷下去，补货台账会被同一缺口淹掉。
        WmsReplenishmentSuggestion pending = replenishmentSuggestionMapper.selectOne(
                new LambdaQueryWrapper<WmsReplenishmentSuggestion>()
                        .eq(WmsReplenishmentSuggestion::getMaterialId, materialId)
                        .eq(WmsReplenishmentSuggestion::getSourceType, REPLENISH_SOURCE_INVENTORY_WARNING)
                        .eq(WmsReplenishmentSuggestion::getStatus, REPLENISH_STATUS_PENDING)
                        .eq(WmsReplenishmentSuggestion::getDeletedFlag, 0)
                        .orderByDesc(WmsReplenishmentSuggestion::getSuggestionId)
                        .last("LIMIT 1"));
        if (pending != null) {
            recordAction(event.getAlertId(), stepNo, ACTION_REPLENISH, AiAlertAction.STATUS_SKIPPED,
                    "WMS_REPLENISHMENT_SUGGESTION", pending.getSuggestionNo(),
                    "该物料已有待确认的补货建议，不重复生成", operatorId, now);
            return 0;
        }
        return createReplenishSuggestion(event, materialId, now, operatorId, stepNo);
    }

    /** 真正调用补货服务下单，并把成功、失败两种情况都记进动作台账。 */
    private int createReplenishSuggestion(AiAlertEvent event, Long materialId, LocalDateTime now,
                                          Long operatorId, int stepNo) {
        BigDecimal required = event.getThresholdValue() == null ? BigDecimal.ZERO : event.getThresholdValue();
        int created = 0;
        try {
            Long suggestionId = replenishmentService.generateSuggestion(materialId, null, required);
            if (suggestionId == null) {
                recordAction(event.getAlertId(), stepNo, ACTION_REPLENISH, AiAlertAction.STATUS_FAILED,
                        "WMS_REPLENISHMENT_SUGGESTION", null, "补货建议生成失败：未返回建议编号", operatorId, now);
            } else {
                recordAction(event.getAlertId(), stepNo, ACTION_REPLENISH, AiAlertAction.STATUS_SUCCEEDED,
                        "WMS_REPLENISHMENT_SUGGESTION", "#" + suggestionId,
                        "已按预警限度自动生成补货建议", operatorId, now);
                created = 1;
            }
        } catch (RuntimeException ex) {
            recordAction(event.getAlertId(), stepNo, ACTION_REPLENISH, AiAlertAction.STATUS_FAILED,
                    "WMS_REPLENISHMENT_SUGGESTION", null, trim(ex.getMessage()), operatorId, now);
        }
        return created;
    }

    /** 自动登记到异常台账，让"还没出事但快出事"的信号也进入统一的处置流程。 */
    private int raiseException(AiAlertRule rule, AlertMetricCatalog.Metric metric, AiAlertEvent event,
                               LocalDateTime now, Long operatorId, int stepNo) {
        String exceptionNo = AUTO_EXCEPTION_PREFIX + event.getAlertNo();
        MfgException existing = exceptionMapper.selectOne(new LambdaQueryWrapper<MfgException>()
                .eq(MfgException::getExceptionNo, exceptionNo)
                .eq(MfgException::getDeletedFlag, 0)
                .last("LIMIT 1"));
        if (existing != null && !"CLOSED".equals(existing.getStatus())) {
            recordAction(event.getAlertId(), stepNo, ACTION_EXCEPTION, AiAlertAction.STATUS_SKIPPED,
                    "MFG_EXCEPTION", existing.getExceptionNo(), "异常台账已有未关闭记录，不重复登记", operatorId, now);
            return 0;
        }
        int created = 0;
        try {
            int written = writeException(existing, exceptionNo, event, now, operatorId);
            if (written != 1) {
                // 写入没有真正生效就不能记成成功，否则台账里会出现一条不存在的异常。
                recordAction(event.getAlertId(), stepNo, ACTION_EXCEPTION, AiAlertAction.STATUS_FAILED,
                        "MFG_EXCEPTION", exceptionNo, "异常台账写入没有生效，请刷新后重试", operatorId, now);
            } else {
                recordAction(event.getAlertId(), stepNo, ACTION_EXCEPTION, AiAlertAction.STATUS_SUCCEEDED,
                        "MFG_EXCEPTION", exceptionNo, "已自动登记到异常台账，进入统一处置流程", operatorId, now);
                created = 1;
            }
        } catch (RuntimeException ex) {
            recordAction(event.getAlertId(), stepNo, ACTION_EXCEPTION, AiAlertAction.STATUS_FAILED,
                    "MFG_EXCEPTION", null, trim(ex.getMessage()), operatorId, now);
        }
        return created;
    }

    /** 已关闭过的异常单就地重开，没有的单据新建；返回真正生效的行数。 */
    private int writeException(MfgException existing, String exceptionNo, AiAlertEvent event,
                               LocalDateTime now, Long operatorId) {
        if (existing != null) {
            MfgException reopen = new MfgException();
            reopen.setExceptionId(existing.getExceptionId());
            reopen.setStatus("SUBMITTED");
            reopen.setSourceAlertId(event.getAlertId());
            reopen.setDescription(trim(event.getSummary()));
            reopen.setVersion(existing.getVersion());
            reopen.setUpdatedBy(operatorId);
            reopen.setUpdatedTime(now);
            return exceptionMapper.updateById(reopen);
        }
        MfgException exception = new MfgException();
        exception.setExceptionNo(exceptionNo);
        exception.setExceptionType(event.getMetricCode());
        // 把来源预警写进去，异常台账点开就能跳回是哪条预警触发的，演示时链路才看得见
        exception.setSourceAlertId(event.getAlertId());
        exception.setDescription(trim(event.getSummary()));
        exception.setStatus("SUBMITTED");
        exception.setVersion(0);
        exception.setCreatedBy(operatorId);
        exception.setCreatedTime(now);
        exception.setUpdatedBy(operatorId);
        exception.setUpdatedTime(now);
        exception.setDeletedFlag(0);
        return exceptionMapper.insert(exception);
    }

    /** 设备类预警自动开维修单，设备科拿到的不是一句"设备坏了"，而是带上下文的正规单据。 */
    private int raiseRepair(AiAlertEvent event, LocalDateTime now, Long operatorId, int stepNo) {
        Long equipmentId = event.getBizId();
        if (equipmentId == null) {
            recordAction(event.getAlertId(), stepNo, ACTION_REPAIR, AiAlertAction.STATUS_SKIPPED, "EAM_REPAIR_ORDER", null, "事件未关联设备，跳过维修单", operatorId, now);
            return 0;
        }
        EamRepairOrder existing = repairOrderMapper.selectOne(new LambdaQueryWrapper<EamRepairOrder>().eq(EamRepairOrder::getEquipmentId, equipmentId).eq(EamRepairOrder::getDeletedFlag, 0).in(EamRepairOrder::getStatus, "SUBMITTED", "REPAIRING").last("LIMIT 1"));
        if (existing != null) {
            recordAction(event.getAlertId(), stepNo, ACTION_REPAIR, AiAlertAction.STATUS_SKIPPED, "EAM_REPAIR_ORDER", existing.getRepairNo(), "该设备已有未关闭维修单，不重复开单", operatorId, now);
            return 0;
        }
        return createRepair(event, equipmentId, now, operatorId, stepNo);
    }

    private int createRepair(AiAlertEvent event, Long equipmentId, LocalDateTime now, Long operatorId, int stepNo) {
        try {
            EamRepairOrder order = newAutoRepairOrder(equipmentId, event, now, operatorId);
            if (repairOrderMapper.insert(order) != 1) {
                recordAction(event.getAlertId(), stepNo, ACTION_REPAIR, AiAlertAction.STATUS_FAILED, "EAM_REPAIR_ORDER", null, "维修单写入没有生效，请刷新后重试", operatorId, now);
                return 0;
            }
            recordAction(event.getAlertId(), stepNo, ACTION_REPAIR, AiAlertAction.STATUS_SUCCEEDED, "EAM_REPAIR_ORDER", order.getRepairNo(), "已自动开出维修单，等待设备科接单", operatorId, now);
            return 1;
        } catch (RuntimeException ex) {
            recordAction(event.getAlertId(), stepNo, ACTION_REPAIR, AiAlertAction.STATUS_FAILED, "EAM_REPAIR_ORDER", null, trim(ex.getMessage()), operatorId, now);
            return 0;
        }
    }

    /** 组装一条待接单状态的自动维修单，单号带上分钟级时间窗避免重复。 */
    private static EamRepairOrder newAutoRepairOrder(Long equipmentId, AiAlertEvent event,
                                                     LocalDateTime now, Long operatorId) {
        EamRepairOrder order = new EamRepairOrder();
        order.setRepairNo(AUTO_REPAIR_PREFIX + equipmentId + "-" + now.format(WINDOW_FORMAT));
        order.setEquipmentId(equipmentId);
        order.setReportedBy(operatorId);
        order.setReportedTime(now);
        order.setFaultDescription(trim(event.getSummary()));
        order.setStatus("SUBMITTED");
        order.setVersion(0);
        order.setCreatedBy(operatorId);
        order.setCreatedTime(now);
        order.setUpdatedBy(operatorId);
        order.setUpdatedTime(now);
        order.setDeletedFlag(0);
        return order;
    }

    private void recordAction(Long alertId, int stepNo, String actionType, String actionStatus,
                              String targetType, String targetNo, String message, Long operatorId,
                              LocalDateTime now) {
        // 每 30 秒一轮扫描都会重新命中同一个事件，自动动作会被反复尝试。
        // 同一事件同一动作同一结果只保留一行（重复执行时原地更新时间和说明），
        // 否则详情页会按扫描轮次线性堆出几十条一模一样的记录。
        // 按"动作+结果"去重而不是只按动作去重，是为了保留"先成功后转跳过"这类
        // 真实变化：补货建议先建成了（成功），后续轮次发现已有待确认建议（跳过），
        // 这两行都要留着，才能看出系统当时确实建过单。
        AiAlertAction existingAction = actionMapper.selectOne(new LambdaQueryWrapper<AiAlertAction>()
                .eq(AiAlertAction::getAlertId, alertId)
                .eq(AiAlertAction::getActionType, actionType)
                .eq(AiAlertAction::getActionStatus, actionStatus)
                .eq(AiAlertAction::getDeletedFlag, 0)
                .orderByAsc(AiAlertAction::getActionId)
                .last("LIMIT 1"));
        if (existingAction != null) {
            AiAlertAction refresh = new AiAlertAction();
            refresh.setActionId(existingAction.getActionId());
            refresh.setStepNo(stepNo);
            refresh.setTargetType(targetType);
            refresh.setTargetNo(targetNo);
            refresh.setMessage(trim(message));
            refresh.setBizNo(targetNo);
            refresh.setVersion(existingAction.getVersion());
            refresh.setUpdatedBy(operatorId);
            refresh.setUpdatedTime(now);
            actionMapper.updateById(refresh);
            return;
        }
        AiAlertAction action = new AiAlertAction();
        action.setAlertId(alertId);
        action.setStepNo(stepNo);
        action.setActionType(actionType);
        action.setActionStatus(actionStatus);
        action.setTargetType(targetType);
        action.setTargetNo(targetNo);
        action.setMessage(trim(message));
        action.setBizNo(targetNo);
        action.setVersion(0);
        action.setCreatedBy(operatorId);
        action.setCreatedTime(now);
        action.setUpdatedBy(operatorId);
        action.setUpdatedTime(now);
        action.setDeletedFlag(0);
        actionMapper.insert(action);
    }

    // ======================================================================
    // 事件查询与人工处置
    // ======================================================================

    @Override
    public Page<AiAlertEvent> pageEvents(Integer current, Integer size, String status, String severity,
                                         String metricCode, String keyword) {
        Page<AiAlertEvent> page = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        LambdaQueryWrapper<AiAlertEvent> wrapper = new LambdaQueryWrapper<AiAlertEvent>()
                .eq(AiAlertEvent::getDeletedFlag, 0)
                .eq(StringUtils.hasText(status), AiAlertEvent::getStatus, status)
                .eq(StringUtils.hasText(severity), AiAlertEvent::getSeverity, severity)
                .eq(StringUtils.hasText(metricCode), AiAlertEvent::getMetricCode, metricCode)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(AiAlertEvent::getTitle, keyword)
                        .or().like(AiAlertEvent::getAlertNo, keyword)
                        .or().like(AiAlertEvent::getBizNo, keyword))
                .orderByDesc(AiAlertEvent::getLastSeenTime)
                .orderByDesc(AiAlertEvent::getAlertId);
        return eventMapper.selectPage(page, wrapper);
    }

    @Override
    public AiAlertEvent getEvent(Long alertId) {
        AiAlertEvent event = alertId == null ? null : eventMapper.selectById(alertId);
        if (event == null || Integer.valueOf(1).equals(event.getDeletedFlag())) {
            throw business("预警事件不存在或已删除");
        }
        return event;
    }

    @Override
    public List<AiAlertAction> actions(Long alertId) {
        getEvent(alertId);
        return actionMapper.selectList(new LambdaQueryWrapper<AiAlertAction>()
                .eq(AiAlertAction::getAlertId, alertId)
                .eq(AiAlertAction::getDeletedFlag, 0)
                .orderByAsc(AiAlertAction::getStepNo)
                .orderByAsc(AiAlertAction::getActionId));
    }

    @Override
    @Transactional
    public boolean acknowledge(Long alertId, String note, Long operatorId) {
        AiAlertEvent event = getEvent(alertId);
        if (!STATUS_OPEN.equals(event.getStatus())) {
            throw business("只有待确认的预警可以确认收到");
        }
        AiAlertEvent update = new AiAlertEvent();
        update.setAlertId(event.getAlertId());
        update.setStatus(STATUS_ACKNOWLEDGED);
        update.setAcknowledgedBy(operatorId);
        update.setAcknowledgedTime(LocalDateTime.now());
        update.setHandledNote(trim(note));
        update.setVersion(event.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(LocalDateTime.now());
        if (eventMapper.updateById(update) != 1) {
            throw business("预警事件已被其他请求修改，请刷新后重试");
        }
        return true;
    }

    @Override
    @Transactional
    public boolean close(Long alertId, String note, Long operatorId) {
        AiAlertEvent event = getEvent(alertId);
        if (STATUS_CLOSED.equals(event.getStatus())) {
            throw business("该预警已经关闭");
        }
        AiAlertEvent update = new AiAlertEvent();
        update.setAlertId(event.getAlertId());
        update.setStatus(STATUS_CLOSED);
        update.setClosedBy(operatorId);
        update.setClosedTime(LocalDateTime.now());
        update.setCloseReason("人工关闭：" + (StringUtils.hasText(note) ? note.trim() : "业务已线下处理"));
        update.setHandledNote(trim(note));
        update.setVersion(event.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(LocalDateTime.now());
        if (eventMapper.updateById(update) != 1) {
            throw business("预警事件已被其他请求修改，请刷新后重试");
        }
        // 人工关闭预警时，第 2 步自动登记的那张异常单也要一起收口，否则异常列表和待办里会留着孤儿单据
        closeAutoException(event, LocalDateTime.now(), operatorId);
        return true;
    }

    @Override
    public Map<String, Object> pendingSummary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("open", countEvents(STATUS_OPEN));
        data.put("acknowledged", countEvents(STATUS_ACKNOWLEDGED));
        data.put("critical", countEventsBySeverity(STATUS_OPEN, AiAlertEvent.SEVERITY_CRITICAL));
        AiAlertEvent latest = eventMapper.selectOne(new LambdaQueryWrapper<AiAlertEvent>()
                .eq(AiAlertEvent::getDeletedFlag, 0)
                .in(AiAlertEvent::getStatus, STATUS_OPEN, STATUS_ACKNOWLEDGED)
                .orderByDesc(AiAlertEvent::getLastSeenTime)
                .orderByDesc(AiAlertEvent::getAlertId)
                .last("LIMIT 1"));
        data.put("latest", latest);
        return data;
    }

    // ======================================================================
    // 指标取数分派
    // ======================================================================

    /**
     * 按指标编码分派到对应的只读取数语句。这里只是"取哪些行"，不做阈值判断，
     * 因此新增指标不会影响已有的阈值配置。
     */
    private List<Map<String, Object>> loadMetricRows(AlertMetricCatalog.Metric metric) {
        List<Map<String, Object>> rows = switch (metric.code()) {
            case "MATERIAL_AVAILABLE" -> metricMapper.materialAvailable(null);
            case "BATCH_AGING" -> metricMapper.batchAging(null);
            case "BATCH_INSPECTION_PENDING" -> metricMapper.batchInspectionPending();
            case "WORK_ORDER_SHORTAGE" -> metricMapper.workOrderShortage();
            case "WORK_ORDER_IDLE" -> metricMapper.workOrderIdle();
            case "WORK_ORDER_OVERDUE" -> metricMapper.workOrderOverdue();
            case "TASK_STALLED" -> metricMapper.taskStalled();
            case "ANDON_UNRESPONDED" -> metricMapper.andonUnresponded();
            case "PLAN_DELAYED" -> metricMapper.planDelayed();
            case "EQUIPMENT_FAULT" -> metricMapper.equipmentFault();
            case "REPAIR_PENDING" -> metricMapper.repairPending();
            case "REPAIR_WAIT_ACCEPTANCE" -> metricMapper.repairWaitAcceptance();
            case "MAINTENANCE_OVERDUE" -> metricMapper.maintenanceOverdue();
            case "SPOT_CHECK_OVERDUE" -> metricMapper.spotCheckOverdue();
            case "INSPECTION_OVERDUE" -> metricMapper.inspectionOverdue();
            case "NONCONFORMING_OPEN" -> metricMapper.nonconformingOpen();
            case "SCHEDULE_CONFLICT" -> metricMapper.scheduleConflict();
            case "LINE_LOAD_RATIO" -> {
                LocalDateTime start = LocalDateTime.now();
                yield metricMapper.lineLoadRatio(start, start.plusDays(7));
            }
            case "REPLENISHMENT_PENDING" -> metricMapper.replenishmentPending();
            default -> List.of();
        };
        if (rows == null) {
            return List.of();
        }
        return rows.size() > MAX_ROWS_PER_METRIC ? rows.subList(0, MAX_ROWS_PER_METRIC) : rows;
    }

    /** 规则的作用范围过滤：全部、指定物料/设备/产线、或指定仓库类型。 */
    private boolean inScope(AiAlertRule rule, Map<String, Object> row) {
        String scopeType = rule.getScopeType() == null ? AiAlertRule.SCOPE_ALL : rule.getScopeType();
        String scopeValue = rule.getScopeValue();
        if (AiAlertRule.SCOPE_ALL.equals(scopeType) || !StringUtils.hasText(scopeValue)) {
            return true;
        }
        return switch (scopeType) {
            case AiAlertRule.SCOPE_MATERIAL, AiAlertRule.SCOPE_EQUIPMENT, AiAlertRule.SCOPE_LINE ->
                    scopeValue.trim().equals(stringValue(row.get("bizId")));
            case AiAlertRule.SCOPE_WAREHOUSE_TYPE ->
                    scopeValue.trim().equalsIgnoreCase(stringValue(row.get("warehouseType")));
            default -> true;
        };
    }

    private String scopeKeyOf(AiAlertRule rule, Map<String, Object> row) {
        String bizId = stringValue(row.get("bizId"));
        if (StringUtils.hasText(bizId)) {
            return bizId;
        }
        String scopeValue = rule.getScopeValue();
        return StringUtils.hasText(scopeValue) ? scopeValue.trim() : "ALL";
    }

    /** 返回命中的严重度；未命中返回 null。严重度只看"是否越过更严的那条线"。 */
    private String severityOf(AiAlertRule rule, BigDecimal value) {
        String severity = null;
        if (value != null) {
            BigDecimal warning = rule.getWarningThreshold();
            BigDecimal critical = rule.getCriticalThreshold();
            boolean criticalHit = critical != null && crossed(rule.getComparator(), value, critical);
            boolean warningHit = warning != null && crossed(rule.getComparator(), value, warning);
            if (criticalHit) {
                severity = AiAlertEvent.SEVERITY_CRITICAL;
            } else if (warningHit) {
                severity = AiAlertEvent.SEVERITY_WARNING;
            }
        }
        return severity;
    }

    private boolean crossed(String comparator, BigDecimal value, BigDecimal threshold) {
        String normalized = comparator == null ? "LT" : comparator.trim().toUpperCase(Locale.ROOT);
        int cmp = value.compareTo(threshold);
        return switch (normalized) {
            case "LTE" -> cmp <= 0;
            case "GT" -> cmp > 0;
            case "GTE" -> cmp >= 0;
            default -> cmp < 0;
        };
    }

    private BigDecimal severityValue(AiAlertRule rule, String severity) {
        if (AiAlertEvent.SEVERITY_CRITICAL.equals(severity) && rule.getCriticalThreshold() != null) {
            return rule.getCriticalThreshold();
        }
        return rule.getWarningThreshold();
    }

    private String titleOf(AlertMetricCatalog.Metric metric, Map<String, Object> row) {
        String subject = stringValue(row.get("subjectName"));
        String bizNo = stringValue(row.get("bizNo"));
        String who = subject;
        if (!StringUtils.hasText(who)) {
            who = StringUtils.hasText(bizNo) ? bizNo : "对象";
        }
        return "【" + metric.name() + "】" + who;
    }

    private String summaryOf(AiAlertRule rule, AlertMetricCatalog.Metric metric, Map<String, Object> row,
                             BigDecimal value, String severity) {
        String comparatorText = switch (rule.getComparator() == null ? "LT" : rule.getComparator()) {
            case "LTE" -> "不高于";
            case "GT" -> "高于";
            case "GTE" -> "不低于";
            default -> "低于";
        };
        BigDecimal threshold = severityValue(rule, severity);
        String unit = unitText(rule.getThresholdUnit());
        StringBuilder builder = new StringBuilder();
        builder.append("当前").append(metric.name()).append(' ')
                .append(plain(value)).append(unit)
                .append('，').append(comparatorText).append("预警限度 ")
                .append(plain(threshold)).append(unit);
        if (AiAlertEvent.SEVERITY_CRITICAL.equals(severity)) {
            builder.append("，已进入严重区间");
        }
        String extra = stringValue(row.get("extraText"));
        if (StringUtils.hasText(extra)) {
            builder.append("；").append(extra);
        }
        builder.append("。").append(metric.description());
        return trim(builder.toString());
    }

    private String unitText(String unit) {
        if (unit == null) {
            return "";
        }
        return switch (unit) {
            case "HOURS" -> " 小时";
            case "DAYS" -> " 天";
            case "COUNT" -> " 个";
            case "PERCENT" -> "%";
            case "QUANTITY" -> " 件";
            default -> "";
        };
    }

    private String categoryOf(String metricCategory) {
        return switch (metricCategory) {
            case AlertMetricCatalog.CATEGORY_MATERIAL -> "WMS";
            case AlertMetricCatalog.CATEGORY_EQUIPMENT -> "EAM";
            case AlertMetricCatalog.CATEGORY_QUALITY -> "QMS";
            case AlertMetricCatalog.CATEGORY_PLANNING -> "PLAN";
            default -> "MFG";
        };
    }

    private String departmentOf(String metricCategory) {
        return switch (metricCategory) {
            case AlertMetricCatalog.CATEGORY_MATERIAL -> "DEPT-WMS";
            case AlertMetricCatalog.CATEGORY_EQUIPMENT -> "DEPT-EAM";
            case AlertMetricCatalog.CATEGORY_QUALITY -> "DEPT-QMS";
            case AlertMetricCatalog.CATEGORY_PLANNING -> "DEPT-PMC";
            default -> "DEPT-MFG";
        };
    }

    private String permissionOf(String metricCategory) {
        return switch (metricCategory) {
            case AlertMetricCatalog.CATEGORY_MATERIAL -> "wms:inventory:view";
            case AlertMetricCatalog.CATEGORY_EQUIPMENT -> "eam:equipment:view";
            case AlertMetricCatalog.CATEGORY_QUALITY -> "qms:inspection-order:view";
            case AlertMetricCatalog.CATEGORY_PLANNING -> "mfg:production-plan:view";
            default -> "mfg:work-order:view";
        };
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    private AlertMetricCatalog.Metric requireMetric(String metricCode) {
        AlertMetricCatalog.Metric metric = AlertMetricCatalog.get(metricCode);
        if (metric == null) {
            throw business("指标编码不存在：" + metricCode);
        }
        return metric;
    }

    /** 限度必须与比较方向自洽：低于类指标的严重限度要比预警更小，高于类反之。 */
    private void validateThreshold(AiAlertRule rule, AlertMetricCatalog.Metric metric) {
        BigDecimal warning = rule.getWarningThreshold();
        BigDecimal critical = rule.getCriticalThreshold();
        if (warning == null) {
            throw business("预警限度不能为空");
        }
        if (critical == null) {
            return;
        }
        String comparator = rule.getComparator() == null ? metric.comparator() : rule.getComparator();
        int cmp = critical.compareTo(warning);
        boolean ascending = "GT".equalsIgnoreCase(comparator) || "GTE".equalsIgnoreCase(comparator);
        if (ascending && cmp <= 0) {
            throw business("上限类指标的严重限度必须大于预警限度");
        }
        if (!ascending && cmp >= 0) {
            throw business("下限类指标的严重限度必须小于预警限度");
        }
    }

    private List<String> splitActions(String actions) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(actions)) {
            return result;
        }
        for (String part : actions.split(",")) {
            String normalized = part.trim().toUpperCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private long countEvents(String status) {
        return eventMapper.selectCount(new LambdaQueryWrapper<AiAlertEvent>()
                .eq(AiAlertEvent::getDeletedFlag, 0)
                .eq(AiAlertEvent::getStatus, status));
    }

    private long countEventsBySeverity(String status, String severity) {
        return eventMapper.selectCount(new LambdaQueryWrapper<AiAlertEvent>()
                .eq(AiAlertEvent::getDeletedFlag, 0)
                .eq(AiAlertEvent::getStatus, status)
                .eq(AiAlertEvent::getSeverity, severity));
    }

    private long countClosedToday() {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        return eventMapper.selectCount(new LambdaQueryWrapper<AiAlertEvent>()
                .eq(AiAlertEvent::getDeletedFlag, 0)
                .eq(AiAlertEvent::getStatus, STATUS_CLOSED)
                .ge(AiAlertEvent::getClosedTime, startOfDay));
    }

    private long countRules(String status) {
        return ruleMapper.selectCount(new LambdaQueryWrapper<AiAlertRule>()
                .eq(AiAlertRule::getDeletedFlag, 0)
                .eq(StringUtils.hasText(status), AiAlertRule::getStatus, status));
    }

    private Map<String, Object> lastScan() {
        AiAlertScan scan = scanMapper.selectOne(new LambdaQueryWrapper<AiAlertScan>()
                .eq(AiAlertScan::getDeletedFlag, 0)
                .and(wrapper -> wrapper.gt(AiAlertScan::getRuleCount, 0)
                        .or()
                        .eq(AiAlertScan::getStatus, AiAlertScan.STATUS_FAILED))
                .orderByDesc(AiAlertScan::getScanId)
                .last("LIMIT 1"));
        if (scan == null) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanId", scan.getScanId());
        data.put("scanNo", scan.getScanNo());
        data.put("triggerType", scan.getTriggerType());
        data.put("status", scan.getStatus());
        data.put("openedCount", scan.getOpenedCount());
        data.put("closedCount", scan.getClosedCount());
        data.put("summary", scan.getSummary());
        data.put("createdTime", scan.getCreatedTime());
        return data;
    }

    @Override
    public Page<AiAlertScan> pageScans(Integer current, Integer size, String status) {
        Page<AiAlertScan> page = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        // 只列真正取过数的轮次（以及失败轮）：历史库里还留着改造前每 60 秒一条的空转记录，
        // 它们既没有结论也没有排查价值，混在列表里会把有内容的轮次冲掉。
        return scanMapper.selectPage(page, new LambdaQueryWrapper<AiAlertScan>()
                .eq(AiAlertScan::getDeletedFlag, 0)
                .eq(StringUtils.hasText(status), AiAlertScan::getStatus, status)
                .and(wrapper -> wrapper.gt(AiAlertScan::getRuleCount, 0)
                        .or()
                        .eq(AiAlertScan::getStatus, AiAlertScan.STATUS_FAILED))
                .orderByDesc(AiAlertScan::getScanId));
    }

    private AiAlertEvent merge(AiAlertEvent base, AiAlertEvent patch) {
        if (patch.getSeverity() != null) {
            base.setSeverity(patch.getSeverity());
        }
        if (patch.getStatus() != null) {
            base.setStatus(patch.getStatus());
        }
        if (patch.getTitle() != null) {
            base.setTitle(patch.getTitle());
        }
        if (patch.getSummary() != null) {
            base.setSummary(patch.getSummary());
        }
        if (patch.getCurrentValue() != null) {
            base.setCurrentValue(patch.getCurrentValue());
        }
        if (patch.getThresholdValue() != null) {
            base.setThresholdValue(patch.getThresholdValue());
        }
        if (patch.getLastSeenTime() != null) {
            base.setLastSeenTime(patch.getLastSeenTime());
        }
        if (patch.getLastNotifiedTime() != null) {
            base.setLastNotifiedTime(patch.getLastNotifiedTime());
        }
        if (patch.getOccurrenceCount() != null) {
            base.setOccurrenceCount(patch.getOccurrenceCount());
        }
        if (patch.getCloseReason() != null) {
            base.setCloseReason(patch.getCloseReason());
        }
        return base;
    }

    /** 乐观锁更新成功后库里的 version 已自增，内存副本要同步，否则后续回写会撞旧版本号。 */
    private static Integer nextVersion(Integer version) {
        return (version == null ? 0 : version) + 1;
    }

    /** 严重度排序值：数值越大代表越需要先处理。 */
    private static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity) {
            case AiAlertEvent.SEVERITY_CRITICAL -> 3;
            case AiAlertEvent.SEVERITY_WARNING -> 2;
            case AiAlertEvent.SEVERITY_INFO -> 1;
            default -> 0;
        };
    }

    private static BigDecimal decimal(Object value) {
        BigDecimal result = BigDecimal.ZERO;
        if (value instanceof BigDecimal decimalValue) {
            result = decimalValue;
        } else if (value instanceof Number number) {
            result = BigDecimal.valueOf(number.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        } else if (value != null) {
            try {
                result = new BigDecimal(String.valueOf(value)).setScale(4, RoundingMode.HALF_UP);
            } catch (NumberFormatException ex) {
                result = BigDecimal.ZERO;
            }
        }
        return result;
    }

    private static Long longValue(Object value) {
        Long result = null;
        if (value instanceof Number number) {
            result = number.longValue();
        } else if (value != null) {
            try {
                result = Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                result = null;
            }
        }
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    private static BusinessException business(String message) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    private static boolean nonNull(Object value) {
        return Objects.nonNull(value);
    }
}
