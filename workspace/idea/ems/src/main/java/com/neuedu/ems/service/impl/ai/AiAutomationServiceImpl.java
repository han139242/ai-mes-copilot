package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.ai.AiAutomationRule;
import com.neuedu.ems.entity.ai.AiAutomationRun;
import com.neuedu.ems.entity.ai.AiAutomationStep;
import com.neuedu.ems.entity.ai.AiInventoryRisk;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsReplenishmentSuggestion;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.ai.AiAutomationRuleMapper;
import com.neuedu.ems.mapper.ai.AiAutomationRunMapper;
import com.neuedu.ems.mapper.ai.AiAutomationStepMapper;
import com.neuedu.ems.mapper.ai.AiInventoryRiskMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.ai.AiAutomationService;
import com.neuedu.ems.service.wms.WmsReplenishmentSuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 自动化规则 Service 实现。
 * 规则执行全程走确定性领域服务：风险台账落 ai_inventory_risk，补料建议走
 * WmsReplenishmentSuggestionService（会算缺口并通知仓管），不依赖大模型输出。
 */
@Service
public class AiAutomationServiceImpl implements AiAutomationService {

    private static final Logger log = LoggerFactory.getLogger(AiAutomationServiceImpl.class);

    private static final String TYPE_INVENTORY_BELOW_SAFETY = "INVENTORY_BELOW_SAFETY";
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_DISABLED = "DISABLED";

    private static final String RUN_RUNNING = "RUNNING";
    private static final String RUN_SUCCEEDED = "SUCCEEDED";
    private static final String RUN_FAILED = "FAILED";
    private static final String RUN_SKIPPED = "SKIPPED";

    private static final String STEP_SUCCEEDED = "SUCCEEDED";
    private static final String STEP_FAILED = "FAILED";
    private static final String STEP_SKIPPED = "SKIPPED";

    private static final String RISK_OPEN = "OPEN";
    private static final String RISK_CLOSED = "CLOSED";

    private static final String ACTION_DETECT = "DETECT_SHORTAGE";
    private static final String ACTION_CREATE_RISK = "CREATE_RISK";
    private static final String ACTION_CREATE_REPLENISHMENT = "CREATE_REPLENISHMENT_SUGGESTION";
    private static final String ACTION_NOTIFY = "NOTIFY_WAREHOUSE";
    private static final String ACTION_CLOSE_RISK = "CLOSE_RISK";

    private static final DateTimeFormatter RUN_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final AiAutomationRuleMapper ruleMapper;
    private final AiAutomationRunMapper runMapper;
    private final AiAutomationStepMapper stepMapper;
    private final AiInventoryRiskMapper riskMapper;
    private final MdmMaterialMapper materialMapper;
    private final WmsInventoryMapper inventoryMapper;
    private final WmsWarehouseMapper warehouseMapper;
    private final WmsReplenishmentSuggestionService replenishmentService;
    private final ObjectMapper objectMapper;

    public AiAutomationServiceImpl(AiAutomationRuleMapper ruleMapper,
                                   AiAutomationRunMapper runMapper,
                                   AiAutomationStepMapper stepMapper,
                                   AiInventoryRiskMapper riskMapper,
                                   MdmMaterialMapper materialMapper,
                                   WmsInventoryMapper inventoryMapper,
                                   WmsWarehouseMapper warehouseMapper,
                                   WmsReplenishmentSuggestionService replenishmentService,
                                   ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.riskMapper = riskMapper;
        this.materialMapper = materialMapper;
        this.inventoryMapper = inventoryMapper;
        this.warehouseMapper = warehouseMapper;
        this.replenishmentService = replenishmentService;
        this.objectMapper = objectMapper;
    }

    /* ==================== 概览与规则维护 ==================== */

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        long total = countRules(null);
        data.put("totalRules", total);
        data.put("enabledRules", countRules(STATUS_ENABLED));
        data.put("pausedRules", countRules(STATUS_PAUSED));
        LocalDateTime dayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        data.put("todayRuns", runMapper.selectCount(new LambdaQueryWrapper<AiAutomationRun>()
                .eq(AiAutomationRun::getDeletedFlag, 0)
                .ge(AiAutomationRun::getStartedTime, dayStart)));
        data.put("succeededRuns", runMapper.selectCount(new LambdaQueryWrapper<AiAutomationRun>()
                .eq(AiAutomationRun::getDeletedFlag, 0)
                .eq(AiAutomationRun::getStatus, RUN_SUCCEEDED)));
        data.put("failedRuns", runMapper.selectCount(new LambdaQueryWrapper<AiAutomationRun>()
                .eq(AiAutomationRun::getDeletedFlag, 0)
                .eq(AiAutomationRun::getStatus, RUN_FAILED)));
        data.put("openRisks", riskMapper.selectCount(new LambdaQueryWrapper<AiInventoryRisk>()
                .eq(AiInventoryRisk::getDeletedFlag, 0)
                .eq(AiInventoryRisk::getStatus, RISK_OPEN)));
        return data;
    }

    private long countRules(String status) {
        return ruleMapper.selectCount(new LambdaQueryWrapper<AiAutomationRule>()
                .eq(AiAutomationRule::getDeletedFlag, 0)
                .eq(StringUtils.hasText(status), AiAutomationRule::getStatus, status));
    }

    @Override
    public Page<AiAutomationRule> pageRules(Integer current, Integer size, String status, String keyword) {
        LambdaQueryWrapper<AiAutomationRule> query = new LambdaQueryWrapper<AiAutomationRule>()
                .eq(AiAutomationRule::getDeletedFlag, 0)
                .eq(StringUtils.hasText(status), AiAutomationRule::getStatus, status)
                .and(StringUtils.hasText(keyword), wrapper -> wrapper
                        .like(AiAutomationRule::getRuleNo, keyword)
                        .or().like(AiAutomationRule::getRuleName, keyword))
                .orderByDesc(AiAutomationRule::getRuleId);
        Page<AiAutomationRule> page = pageOf(current, size);
        return ruleMapper.selectPage(page, query);
    }

    @Override
    public AiAutomationRule getRule(Long ruleId) {
        AiAutomationRule rule = ruleId == null ? null : ruleMapper.selectById(ruleId);
        if (rule == null || Integer.valueOf(1).equals(rule.getDeletedFlag())) {
            throw new BusinessException(404, "自动化规则不存在或已删除");
        }
        return rule;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveRule(AiAutomationRule rule) {
        if (rule == null || !StringUtils.hasText(rule.getRuleName())) {
            throw new BusinessException(400, "规则名称不能为空");
        }
        rule.setRuleId(null);
        // 未填的项按默认值补齐，已填的保持原值
        if (!StringUtils.hasText(rule.getRuleNo())) {
            rule.setRuleNo("RULE-" + System.currentTimeMillis());
        }
        if (!StringUtils.hasText(rule.getRuleType())) {
            rule.setRuleType(TYPE_INVENTORY_BELOW_SAFETY);
        }
        if (!StringUtils.hasText(rule.getStatus())) {
            rule.setStatus(STATUS_DRAFT);
        }
        if (rule.getIntervalMinutes() == null) {
            rule.setIntervalMinutes(10);
        }
        rule.setSuccessCount(0);
        rule.setFailCount(0);
        rule.setDeletedFlag(0);
        rule.setVersion(0);
        ruleMapper.insert(rule);
        return rule.getRuleId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateRule(AiAutomationRule rule) {
        AiAutomationRule existing = getRule(rule == null ? null : rule.getRuleId());
        rule.setRuleNo(existing.getRuleNo());
        rule.setDeletedFlag(null);
        rule.setSuccessCount(null);
        rule.setFailCount(null);
        return ruleMapper.updateById(rule) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeRule(Long ruleId) {
        AiAutomationRule existing = getRule(ruleId);
        AiAutomationRule update = new AiAutomationRule();
        update.setRuleId(existing.getRuleId());
        update.setDeletedFlag(1);
        return ruleMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStatus(Long ruleId, String status) {
        if (!Set.of(STATUS_DRAFT, STATUS_ENABLED, STATUS_PAUSED, STATUS_DISABLED).contains(status)) {
            throw new BusinessException(400, "规则状态只能是 DRAFT、ENABLED、PAUSED 或 DISABLED");
        }
        AiAutomationRule existing = getRule(ruleId);
        AiAutomationRule update = new AiAutomationRule();
        update.setRuleId(existing.getRuleId());
        update.setStatus(status);
        if (STATUS_ENABLED.equals(status)) {
            update.setNextRunTime(LocalDateTime.now().plusMinutes(
                    existing.getIntervalMinutes() == null ? 10 : existing.getIntervalMinutes()));
        } else {
            update.setNextRunTime(null);
        }
        return ruleMapper.updateById(update) > 0;
    }

    /* ==================== 试算与执行 ==================== */

    @Override
    public Map<String, Object> preview(Long ruleId) {
        AiAutomationRule rule = getRule(ruleId);
        List<Shortage> matched = detectShortages(rule);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Shortage shortage : matched) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", shortage.materialId());
            row.put("materialCode", shortage.materialCode());
            row.put("materialName", shortage.materialName());
            row.put("availableQuantity", shortage.available());
            row.put("thresholdQuantity", shortage.threshold());
            row.put("shortageQuantity", shortage.shortage());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dryRun", true);
        data.put("ruleId", rule.getRuleId());
        data.put("ruleNo", rule.getRuleNo());
        data.put("matched", rows);
        data.put("wouldCreateRisk", matched.size());
        data.put("wouldReplenish", matched.size());
        data.put("wouldNotify", matched.size());
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> run(Long ruleId) {
        AiAutomationRule rule = getRule(ruleId);
        return execute(rule, "MANUAL", "MANUAL:" + ruleId + ":" + UUID.randomUUID());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int runScheduled() {
        LocalDateTime now = LocalDateTime.now();
        List<AiAutomationRule> due = ruleMapper.selectList(new LambdaQueryWrapper<AiAutomationRule>()
                .eq(AiAutomationRule::getDeletedFlag, 0)
                .eq(AiAutomationRule::getStatus, STATUS_ENABLED)
                .orderByAsc(AiAutomationRule::getRuleId)
                .last("LIMIT 50"));
        int executed = 0;
        for (AiAutomationRule rule : due) {
            int interval = rule.getIntervalMinutes() == null ? 10 : Math.max(1, rule.getIntervalMinutes());
            if (rule.getLastRunTime() != null && rule.getLastRunTime().plusMinutes(interval).isAfter(now)) {
                continue;
            }
            // 定时触发按"规则 + 时间窗"生成幂等键，同一窗口重复扫描不会重复建单
            String window = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            try {
                execute(rule, "SCHEDULE", "SCHEDULE:" + rule.getRuleId() + ":" + window);
                executed++;
            } catch (RuntimeException ex) {
                log.warn("自动化规则 {} 执行失败：{}", rule.getRuleNo(), ex.getMessage());
            }
        }
        return executed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int runOnInventoryChange(Long materialId) {
        if (materialId == null) {
            return 0;
        }
        List<AiAutomationRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<AiAutomationRule>()
                .eq(AiAutomationRule::getDeletedFlag, 0)
                .eq(AiAutomationRule::getStatus, STATUS_ENABLED)
                .eq(AiAutomationRule::getRuleType, TYPE_INVENTORY_BELOW_SAFETY)
                .orderByAsc(AiAutomationRule::getRuleId));
        int executed = 0;
        for (AiAutomationRule rule : rules) {
            if (!coversMaterial(rule, materialId)) {
                continue;
            }
            BigDecimal available = availableQuantity(materialId, ruleWarehouseType(rule));
            String runKey = "EVENT:" + rule.getRuleId() + ":" + materialId + ":"
                    + available.stripTrailingZeros().toPlainString();
            try {
                execute(rule, "EVENT", runKey);
                executed++;
            } catch (RuntimeException ex) {
                log.warn("库存变化触发规则 {} 失败：{}", rule.getRuleNo(), ex.getMessage());
            }
        }
        return executed;
    }

    /** 显式点名的物料必须在名单里；没点名时按仓库类型覆盖该类型的启用物料。 */
    private boolean coversMaterial(AiAutomationRule rule, Long materialId) {
        ConfiguredMaterials configured = readConfiguredMaterials(rule, defaultThresholdOf(rule));
        if (!configured.materialIds().isEmpty()) {
            return configured.materialIds().contains(materialId);
        }
        MdmMaterial material = materialMapper.selectById(materialId);
        if (material == null || !Integer.valueOf(0).equals(material.getDeletedFlag())
                || !BusinessStatus.Common.ENABLE.equals(material.getStatus())) {
            return false;
        }
        return materialTypesFor(ruleWarehouseType(rule)).contains(material.getMaterialType());
    }

    private Map<String, Object> execute(AiAutomationRule rule, String triggerType, String runKey) {
        AiAutomationRun duplicated = runMapper.selectOne(new LambdaQueryWrapper<AiAutomationRun>()
                .eq(AiAutomationRun::getRunKey, runKey)
                .eq(AiAutomationRun::getDeletedFlag, 0));
        // 成功或主动跳过才算这次触发已经做完。失败和停在 RUNNING 的记录是上次没跑完，
        // 同一幂等键接着做，避免重启后永远不再补跑，也避免再插一条违反唯一键的运行记录。
        if (duplicated != null && (RUN_SUCCEEDED.equals(duplicated.getStatus())
                || RUN_SKIPPED.equals(duplicated.getStatus()))) {
            return duplicatedRunResult(duplicated);
        }

        LocalDateTime startedAt = LocalDateTime.now();
        long beginMillis = System.currentTimeMillis();
        AiAutomationRun run = duplicated == null
                ? createRunningRun(rule, triggerType, runKey, startedAt)
                : resumeRun(duplicated, startedAt);
        List<AiAutomationStep> steps = new ArrayList<>();
        try {
            List<Shortage> matched = detectShortages(rule);
            run.setMatchedCount(matched.size());
            steps.add(step(steps.size() + 1, run.getRunId(), ACTION_DETECT, "MATERIAL", null,
                    STEP_SUCCEEDED, matched.isEmpty()
                            ? "没有物料低于阈值" : "命中 " + matched.size() + " 种低于阈值的物料", null));

            ExecutionTally tally = new ExecutionTally();
            for (Shortage shortage : matched) {
                tally = tally.plus(handleShortage(rule, shortage, run.getRunId(), steps, startedAt));
            }

            int closed = closeRecoveredRisks(rule, matched, run.getRunId(), steps, startedAt);
            return finishRun(rule, run, matched.size(), tally, closed, beginMillis);
        } catch (RuntimeException ex) {
            failRun(rule, run, startedAt, beginMillis, ex);
            throw ex;
        } finally {
            for (AiAutomationStep item : steps) {
                stepMapper.insert(item);
            }
        }
    }

    /**
     * 幂等命中时的返回体：按原执行记录的结论直接回放，不再重复登记风险。
     */
    private Map<String, Object> duplicatedRunResult(AiAutomationRun duplicated) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", duplicated.getRunId());
        data.put("runNo", duplicated.getRunNo());
        data.put("status", duplicated.getStatus());
        data.put("matchedCount", duplicated.getMatchedCount());
        data.put("summary", "本次触发已执行过，按幂等键返回原结果");
        data.put("elapsedMs", duplicated.getElapsedMs());
        data.put("idempotent", true);
        return data;
    }

    /**
     * 落一条 RUNNING 状态的主记录，后续步骤明细都挂在它下面。
     */
    private AiAutomationRun createRunningRun(AiAutomationRule rule, String triggerType, String runKey,
                                             LocalDateTime startedAt) {
        AiAutomationRun run = new AiAutomationRun();
        run.setRunNo("RUN" + startedAt.format(RUN_NO_FORMAT));
        run.setRunKey(runKey);
        run.setRuleId(rule.getRuleId());
        run.setRuleNo(rule.getRuleNo());
        run.setRuleName(rule.getRuleName());
        run.setTriggerType(triggerType);
        run.setStatus(RUN_RUNNING);
        run.setMatchedCount(0);
        run.setStartedTime(startedAt);
        run.setDeletedFlag(0);
        runMapper.insert(run);
        return run;
    }

    /** 把上次失败或中断的同一条运行记录重新标成执行中，不新开幂等键。 */
    private AiAutomationRun resumeRun(AiAutomationRun existing, LocalDateTime startedAt) {
        existing.setStatus(RUN_RUNNING);
        existing.setErrorMessage("");
        existing.setStartedTime(startedAt);
        existing.setFinishedTime(null);
        existing.setSummary("");
        if (runMapper.updateById(existing) != 1) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                    "自动化运行记录 " + existing.getRunNo() + " 已被其他请求修改，本次无法继续执行");
        }
        return existing;
    }

    /**
     * 处理一个低于阈值的物料：已存在未关闭风险就跳过，否则登记风险并生成补货建议。
     */
    private ExecutionTally handleShortage(AiAutomationRule rule, Shortage shortage, Long runId,
                                          List<AiAutomationStep> steps, LocalDateTime startedAt) {
        AiInventoryRisk open = findOpenRisk(rule.getRuleId(), shortage.materialId());
        if (open != null) {
            steps.add(step(steps.size() + 1, runId, ACTION_CREATE_RISK,
                    "INVENTORY_RISK", open.getRiskNo(), STEP_SKIPPED,
                    "风险 " + open.getRiskNo() + " 已存在且未关闭，本轮不重复登记", open.getRiskNo()));
            return new ExecutionTally();
        }
        AiInventoryRisk risk = registerRisk(rule, shortage, startedAt);
        steps.add(step(steps.size() + 1, runId, ACTION_CREATE_RISK,
                "INVENTORY_RISK", risk.getRiskNo(), STEP_SUCCEEDED, shortageDescription(shortage),
                risk.getRiskNo()));
        boolean suggested = createReplenishment(rule, shortage, risk, runId, steps);
        return new ExecutionTally(1, suggested ? 1 : 0);
    }

    /**
     * 落一条 OPEN 状态的库存风险台账记录。
     */
    private AiInventoryRisk registerRisk(AiAutomationRule rule, Shortage shortage, LocalDateTime startedAt) {
        AiInventoryRisk risk = new AiInventoryRisk();
        risk.setRiskNo("RISK" + System.currentTimeMillis() + "-" + shortage.materialId());
        risk.setRuleId(rule.getRuleId());
        risk.setRuleNo(rule.getRuleNo());
        risk.setMaterialId(shortage.materialId());
        risk.setMaterialCode(shortage.materialCode());
        risk.setMaterialName(shortage.materialName());
        risk.setWarehouseType(ruleWarehouseType(rule));
        risk.setAvailableQuantity(shortage.available());
        risk.setThresholdQuantity(shortage.threshold());
        risk.setShortageQuantity(shortage.shortage());
        risk.setStatus(RISK_OPEN);
        risk.setDetectedTime(startedAt);
        risk.setVersion(0);
        risk.setDeletedFlag(0);
        if (riskMapper.insert(risk) != 1) {
            // 风险没落库就不能往下记步骤成功，否则台账里查不到这条风险。
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                    "库存风险 " + risk.getRiskNo() + " 写入没有生效，本次自动化运行按失败处理");
        }
        return risk;
    }

    /**
     * 缺口文案：可用量、阈值与缺口都用去掉尾零的十进制表示。
     */
    private String shortageDescription(Shortage shortage) {
        return "可用 " + shortage.available().stripTrailingZeros().toPlainString()
                + " 低于阈值 " + shortage.threshold().stripTrailingZeros().toPlainString()
                + "，缺口 " + shortage.shortage().stripTrailingZeros().toPlainString();
    }

    /**
     * 按缺口生成补货建议并回写风险台账；返回是否生成成功。
     */
    private boolean createReplenishment(AiAutomationRule rule, Shortage shortage, AiInventoryRisk risk,
                                        Long runId, List<AiAutomationStep> steps) {
        // 预警中心的补货动作会先查一次待确认建议再生成；这里也必须查，否则同一缺口
        // 会被两条链路各建一条建议、各通知仓管一次。
        WmsReplenishmentSuggestion pending = replenishmentService.findPendingInventoryWarning(shortage.materialId());
        boolean created = false;
        if (pending != null) {
            steps.add(linkReusedSuggestion(runId, steps, risk, pending));
        } else {
            Long suggestionId = replenishmentService.generateSuggestion(
                    shortage.materialId(), ruleWarehouseId(rule), shortage.threshold());
            if (suggestionId == null) {
                steps.add(step(steps.size() + 1, runId, ACTION_CREATE_REPLENISHMENT,
                        "REPLENISHMENT_SUGGESTION", null, STEP_FAILED,
                        "补货建议生成失败：未返回建议ID", null));
            } else if (linkRiskToSuggestion(risk, suggestionId)) {
                steps.add(step(steps.size() + 1, runId, ACTION_CREATE_REPLENISHMENT,
                        "REPLENISHMENT_SUGGESTION", "#" + suggestionId, STEP_SUCCEEDED,
                        "已按缺口生成补货建议，并通知仓库负责人", "#" + suggestionId));
                created = true;
            } else {
                // 建议已经生成但没挂到风险上，后续按风险追溯会断链，必须报失败让人看见。
                steps.add(step(steps.size() + 1, runId, ACTION_CREATE_REPLENISHMENT,
                        "REPLENISHMENT_SUGGESTION", "#" + suggestionId, STEP_FAILED,
                        "补货建议已生成，但风险台账回写没有生效，建议未关联到风险", "#" + suggestionId));
            }
        }
        return created;
    }

    /** 复用已有待确认建议：把风险指到那张单上，返回对应的一条步骤记录。 */
    private AiAutomationStep linkReusedSuggestion(Long runId, List<AiAutomationStep> steps,
                                                  AiInventoryRisk risk, WmsReplenishmentSuggestion pending) {
        boolean linked = linkRiskToSuggestion(risk, pending.getSuggestionId());
        return step(steps.size() + 1, runId, ACTION_CREATE_REPLENISHMENT,
                "REPLENISHMENT_SUGGESTION", pending.getSuggestionNo(),
                linked ? STEP_SKIPPED : STEP_FAILED,
                linked ? "该物料已有待确认的补货建议，复用原单不重复生成"
                        : "复用已有补货建议失败：风险台账回写没有生效",
                pending.getSuggestionNo());
    }

    /** 把风险台账指向补货建议，返回是否真的写进去了。 */
    private boolean linkRiskToSuggestion(AiInventoryRisk risk, Long suggestionId) {
        AiInventoryRisk link = new AiInventoryRisk();
        link.setRiskId(risk.getRiskId());
        link.setSuggestionId(suggestionId);
        return riskMapper.updateById(link) == 1;
    }

    /**
     * 成功收尾：写主记录结论、推进规则的下次执行时间与成功次数。
     */
    private Map<String, Object> finishRun(AiAutomationRule rule, AiAutomationRun run, int matchedCount,
                                          ExecutionTally tally, int closed, long beginMillis) {
        // 命中物料却一条风险都没登记、也没生成建议，说明这一轮什么也没做成，
        // 不能记成成功并把成功次数加一。
        boolean producedNothing = matchedCount > 0 && tally.risks() == 0 && tally.suggestions() == 0;
        if (producedNothing) {
            run.setStatus(RUN_SKIPPED);
            run.setFinishedTime(LocalDateTime.now());
            run.setElapsedMs(System.currentTimeMillis() - beginMillis);
            run.setSummary("命中 " + matchedCount + " 种物料，但都已有未关闭风险，本轮没有新增登记");
            if (runMapper.updateById(run) != 1) {
                throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                        "自动化运行记录 " + run.getRunNo() + " 已被其他请求修改，本轮结论没有落库");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("runId", run.getRunId());
            data.put("runNo", run.getRunNo());
            data.put("status", run.getStatus());
            data.put("matchedCount", run.getMatchedCount());
            data.put("summary", run.getSummary());
            data.put("elapsedMs", run.getElapsedMs());
            data.put("idempotent", false);
            return data;
        }
        run.setStatus(RUN_SUCCEEDED);
        run.setFinishedTime(LocalDateTime.now());
        run.setElapsedMs(System.currentTimeMillis() - beginMillis);
        run.setSummary("命中 " + matchedCount + " 种物料，登记风险 " + tally.risks()
                + " 条，生成补货建议 " + tally.suggestions() + " 条，关闭已恢复风险 " + closed + " 条");
        if (runMapper.updateById(run) != 1) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                    "自动化运行记录 " + run.getRunNo() + " 已被其他请求修改，本轮结论没有落库");
        }

        AiAutomationRule ruleUpdate = new AiAutomationRule();
        ruleUpdate.setRuleId(rule.getRuleId());
        ruleUpdate.setLastRunTime(run.getStartedTime());
        ruleUpdate.setNextRunTime(run.getStartedTime().plusMinutes(
                rule.getIntervalMinutes() == null ? 10 : Math.max(1, rule.getIntervalMinutes())));
        ruleUpdate.setSuccessCount((rule.getSuccessCount() == null ? 0 : rule.getSuccessCount()) + 1);
        if (ruleMapper.updateById(ruleUpdate) != 1) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                    "自动化规则 " + rule.getRuleNo() + " 的下次执行时间没有更新，本轮结论没有落库");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.getRunId());
        data.put("runNo", run.getRunNo());
        data.put("status", run.getStatus());
        data.put("matchedCount", run.getMatchedCount());
        data.put("summary", run.getSummary());
        data.put("elapsedMs", run.getElapsedMs());
        data.put("idempotent", false);
        return data;
    }

    /**
     * 失败收尾：主记录置 FAILED 并累计规则的失败次数。
     */
    private void failRun(AiAutomationRule rule, AiAutomationRun run, LocalDateTime startedAt,
                         long beginMillis, RuntimeException ex) {
        run.setStatus(RUN_FAILED);
        run.setFinishedTime(LocalDateTime.now());
        run.setElapsedMs(System.currentTimeMillis() - beginMillis);
        run.setErrorMessage(trim(ex.getMessage()));
        runMapper.updateById(run);
        AiAutomationRule ruleUpdate = new AiAutomationRule();
        ruleUpdate.setRuleId(rule.getRuleId());
        ruleUpdate.setLastRunTime(startedAt);
        ruleUpdate.setFailCount((rule.getFailCount() == null ? 0 : rule.getFailCount()) + 1);
        // 失败收尾发生在异常路径上，这里写不进去只能记日志，不能再抛异常盖住原始失败原因。
        if (ruleMapper.updateById(ruleUpdate) != 1) {
            log.warn("自动化规则失败次数没有更新 ruleNo={}", rule.getRuleNo());
        }
    }

    private int closeRecoveredRisks(AiAutomationRule rule, List<Shortage> matched, Long runId,
                                    List<AiAutomationStep> steps, LocalDateTime now) {
        Set<Long> stillShort = new LinkedHashSet<>();
        for (Shortage shortage : matched) {
            stillShort.add(shortage.materialId());
        }
        List<AiInventoryRisk> openRisks = riskMapper.selectList(new LambdaQueryWrapper<AiInventoryRisk>()
                .eq(AiInventoryRisk::getDeletedFlag, 0)
                .eq(AiInventoryRisk::getStatus, RISK_OPEN)
                .eq(AiInventoryRisk::getRuleId, rule.getRuleId()));
        int closed = 0;
        for (AiInventoryRisk risk : openRisks) {
            if (stillShort.contains(risk.getMaterialId())) {
                continue;
            }
            AiInventoryRisk update = new AiInventoryRisk();
            update.setRiskId(risk.getRiskId());
            update.setStatus(RISK_CLOSED);
            update.setClosedTime(now);
            update.setRemark("库存已恢复到阈值以上，规则自动验证关闭");
            riskMapper.updateById(update);
            closed++;
            steps.add(step(steps.size() + 1, runId, ACTION_CLOSE_RISK, "INVENTORY_RISK",
                    risk.getRiskNo(), STEP_SUCCEEDED,
                    "可用库存已回到阈值以上，风险自动关闭", risk.getRiskNo()));
        }
        return closed;
    }

    private AiInventoryRisk findOpenRisk(Long ruleId, Long materialId) {
        return riskMapper.selectOne(new LambdaQueryWrapper<AiInventoryRisk>()
                .eq(AiInventoryRisk::getDeletedFlag, 0)
                .eq(AiInventoryRisk::getStatus, RISK_OPEN)
                .eq(AiInventoryRisk::getRuleId, ruleId)
                .eq(AiInventoryRisk::getMaterialId, materialId)
                .orderByDesc(AiInventoryRisk::getRiskId)
                .last("LIMIT 1"));
    }

    /* ==================== 记录查询 ==================== */

    @Override
    public Page<AiAutomationRun> pageRuns(Integer current, Integer size, Long ruleId, String status) {
        LambdaQueryWrapper<AiAutomationRun> query = new LambdaQueryWrapper<AiAutomationRun>()
                .eq(AiAutomationRun::getDeletedFlag, 0)
                .eq(ruleId != null, AiAutomationRun::getRuleId, ruleId)
                .eq(StringUtils.hasText(status), AiAutomationRun::getStatus, status)
                .orderByDesc(AiAutomationRun::getRunId);
        Page<AiAutomationRun> page = pageOf(current, size);
        return runMapper.selectPage(page, query);
    }

    @Override
    public Map<String, Object> getRunDetail(Long runId) {
        AiAutomationRun run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(404, "执行记录不存在");
        }
        List<AiAutomationStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AiAutomationStep>()
                .eq(AiAutomationStep::getDeletedFlag, 0)
                .eq(AiAutomationStep::getRunId, runId)
                .orderByAsc(AiAutomationStep::getStepNo));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run", run);
        data.put("steps", steps);
        return data;
    }

    @Override
    public Page<AiInventoryRisk> pageRisks(Integer current, Integer size, String status) {
        LambdaQueryWrapper<AiInventoryRisk> query = new LambdaQueryWrapper<AiInventoryRisk>()
                .eq(AiInventoryRisk::getDeletedFlag, 0)
                .eq(StringUtils.hasText(status), AiInventoryRisk::getStatus, status)
                .orderByDesc(AiInventoryRisk::getRiskId);
        Page<AiInventoryRisk> page = pageOf(current, size);
        return riskMapper.selectPage(page, query);
    }

    /**
     * 统一分页参数兜底：页码为空按第一页，页大小为空按 10 条。
     */
    private <T> Page<T> pageOf(Integer current, Integer size) {
        int pageNo = current == null ? 1 : current;
        int pageSize = size == null ? 10 : size;
        return new Page<>(pageNo, pageSize);
    }

    @Override
    public List<Map<String, Object>> ruleMaterials(Long ruleId) {
        AiAutomationRule rule = getRule(ruleId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaterialTarget target : resolveMaterialTargets(rule)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", target.materialId());
            row.put("materialCode", target.materialCode());
            row.put("materialName", target.materialName());
            row.put("thresholdQuantity", target.threshold());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> lowStockMaterials(String warehouseType, String materialKeyword) {
        String wantedType = StringUtils.hasText(warehouseType)
                ? warehouseType.trim().toUpperCase(java.util.Locale.ROOT) : null;
        String keyword = StringUtils.hasText(materialKeyword) ? materialKeyword.trim() : null;
        Map<String, AiAutomationRule> rules = pickLowStockRules(wantedType);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, AiAutomationRule> entry : rules.entrySet()) {
            AiAutomationRule rule = entry.getValue();
            for (Shortage shortage : detectShortages(rule)) {
                if (!matchesKeyword(shortage, keyword)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("warehouseType", entry.getKey());
                row.put("ruleNo", rule.getRuleNo());
                row.put("ruleName", rule.getRuleName());
                row.put("ruleStatus", rule.getStatus());
                row.put("materialId", shortage.materialId());
                row.put("materialCode", shortage.materialCode());
                row.put("materialName", shortage.materialName());
                row.put("availableQuantity", shortage.available());
                row.put("thresholdQuantity", shortage.threshold());
                row.put("shortageQuantity", shortage.shortage());
                rows.add(row);
            }
        }
        rows.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("warehouseType")))
                .thenComparing(row -> String.valueOf(row.get("materialCode")),
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /**
     * 每个仓库类型挑一条规则来算缺口：启用优先，其次暂停，最后草稿；已禁用的不参与。
     * 这样「规则被暂停」不会被误读成「没有缺料」，结果里同时带出规则状态。
     */
    private Map<String, AiAutomationRule> pickLowStockRules(String wantedType) {
        List<AiAutomationRule> candidates = ruleMapper.selectList(new LambdaQueryWrapper<AiAutomationRule>()
                .eq(AiAutomationRule::getDeletedFlag, 0)
                .eq(AiAutomationRule::getRuleType, TYPE_INVENTORY_BELOW_SAFETY)
                .in(AiAutomationRule::getStatus, STATUS_ENABLED, STATUS_PAUSED, STATUS_DRAFT)
                .orderByAsc(AiAutomationRule::getRuleId));
        Map<String, AiAutomationRule> picked = new LinkedHashMap<>();
        for (AiAutomationRule rule : candidates) {
            String type;
            try {
                type = ruleWarehouseType(rule);
            } catch (BusinessException ex) {
                // 单条规则配置写坏了不该让整次只读查询失败，跳过它并留日志。
                log.warn("规则 {} 配置无法解析，跳过缺口计算：{}", rule.getRuleNo(), ex.getMessage());
                continue;
            }
            if (wantedType != null && !wantedType.equals(type)) {
                continue;
            }
            AiAutomationRule existing = picked.get(type);
            if (existing == null || statusRank(rule.getStatus()) < statusRank(existing.getStatus())) {
                picked.put(type, rule);
            }
        }
        return picked;
    }

    private int statusRank(String status) {
        if (STATUS_ENABLED.equals(status)) {
            return 0;
        }
        return STATUS_PAUSED.equals(status) ? 1 : 2;
    }

    /** 关键词按物料编码或名称包含匹配，空关键词表示不限物料。 */
    private boolean matchesKeyword(Shortage shortage, String keyword) {
        if (keyword == null) {
            return true;
        }
        String code = shortage.materialCode() == null ? "" : shortage.materialCode();
        String name = shortage.materialName() == null ? "" : shortage.materialName();
        return code.toUpperCase(java.util.Locale.ROOT).contains(keyword.toUpperCase(java.util.Locale.ROOT))
                || name.contains(keyword);
    }

    /* ==================== 规则解析与缺口计算 ==================== */

    private List<Shortage> detectShortages(AiAutomationRule rule) {
        List<MaterialTarget> targets = resolveMaterialTargets(rule);
        Map<Long, BigDecimal> availableByMaterial = availableQuantities(
                targets.stream().map(MaterialTarget::materialId).toList(), ruleWarehouseType(rule));
        List<Shortage> matched = new ArrayList<>();
        for (MaterialTarget target : targets) {
            BigDecimal available = availableByMaterial.getOrDefault(target.materialId(), BigDecimal.ZERO);
            if (available.compareTo(target.threshold()) >= 0) {
                continue;
            }
            matched.add(new Shortage(target.materialId(), target.materialCode(), target.materialName(),
                    target.threshold(), available, target.threshold().subtract(available)));
        }
        matched.sort(Comparator.comparing(Shortage::materialCode,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return matched;
    }

    /**
     * 解析规则适用范围：显式配置的物料用各自阈值，其余物料用默认阈值；
     * 配置里物料列表为空时，按仓库类型覆盖该类型下的全部启用物料。
     */
    private List<MaterialTarget> resolveMaterialTargets(AiAutomationRule rule) {
        requireSupportedRuleType(rule);
        BigDecimal defaultThreshold = defaultThresholdOf(rule);
        ConfiguredMaterials configured = readConfiguredMaterials(rule, defaultThreshold);
        List<MdmMaterial> candidates = loadCandidates(rule, configured.materialIds());
        List<MaterialTarget> targets = new ArrayList<>();
        for (MdmMaterial material : candidates) {
            BigDecimal threshold = configured.thresholds()
                    .getOrDefault(material.getMaterialId(), defaultThreshold);
            targets.add(new MaterialTarget(material.getMaterialId(), material.getMaterialCode(),
                    material.getMaterialName(), threshold));
        }
        return targets;
    }

    /**
     * 只支持「库存低于安全库存」这一种规则类型，其它类型直接拒绝。
     */
    private void requireSupportedRuleType(AiAutomationRule rule) {
        if (!TYPE_INVENTORY_BELOW_SAFETY.equals(rule.getRuleType())) {
            throw new BusinessException(400, "暂不支持的规则类型：" + rule.getRuleType());
        }
    }

    /**
     * 规则默认阈值，没配按 0 处理。
     */
    private BigDecimal defaultThresholdOf(AiAutomationRule rule) {
        return rule.getDefaultThreshold() == null ? BigDecimal.ZERO : rule.getDefaultThreshold();
    }

    /**
     * 从 config_json 的 materials 数组里读出显式配置的物料与各自阈值。
     */
    private ConfiguredMaterials readConfiguredMaterials(AiAutomationRule rule, BigDecimal defaultThreshold) {
        List<Long> materialIds = new ArrayList<>();
        Map<Long, BigDecimal> thresholds = new LinkedHashMap<>();
        Object materials = readConfig(rule.getConfigJson()).get("materials");
        if (materials instanceof List<?> list) {
            for (Object item : list) {
                collectConfiguredMaterial(item, defaultThreshold, materialIds, thresholds);
            }
        }
        return new ConfiguredMaterials(materialIds, thresholds);
    }

    /**
     * 读取单条物料配置；结构不合法或没给物料ID就跳过，不影响其它物料。
     */
    private void collectConfiguredMaterial(Object item, BigDecimal defaultThreshold,
                                           List<Long> materialIds, Map<Long, BigDecimal> thresholds) {
        if (!(item instanceof Map<?, ?> map)) {
            return;
        }
        Long materialId = asLong(map.get("materialId"));
        if (materialId == null) {
            return;
        }
        materialIds.add(materialId);
        thresholds.put(materialId, asDecimal(map.get("safetyStock"), defaultThreshold));
    }

    /**
     * 取候选物料：配了具体物料就按ID取，没配就按仓库类型覆盖该类型下全部启用物料。
     */
    private List<MdmMaterial> loadCandidates(AiAutomationRule rule, List<Long> configuredIds) {
        if (configuredIds.isEmpty()) {
            return materialMapper.selectList(new LambdaQueryWrapper<MdmMaterial>()
                    .eq(MdmMaterial::getDeletedFlag, 0)
                    .eq(MdmMaterial::getStatus, BusinessStatus.Common.ENABLE)
                    .in(MdmMaterial::getMaterialType, materialTypesFor(ruleWarehouseType(rule)))
                    .orderByAsc(MdmMaterial::getMaterialCode));
        }
        return materialMapper.selectList(new LambdaQueryWrapper<MdmMaterial>()
                .eq(MdmMaterial::getDeletedFlag, 0)
                .in(MdmMaterial::getMaterialId, configuredIds)
                .orderByAsc(MdmMaterial::getMaterialCode));
    }

    private Set<String> materialTypesFor(String warehouseType) {
        if ("SEMI_FINISHED".equals(warehouseType)) {
            return Set.of("SEMI_FINISHED");
        }
        if ("FINISHED".equals(warehouseType)) {
            return Set.of("FINISHED");
        }
        return Set.of("RAW", "AUXILIARY");
    }

    private BigDecimal availableQuantity(Long materialId, String warehouseType) {
        return availableQuantities(List.of(materialId), warehouseType).getOrDefault(materialId, BigDecimal.ZERO);
    }

    private Map<Long, BigDecimal> availableQuantities(List<Long> materialIds, String warehouseType) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        List<WmsWarehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .eq(StringUtils.hasText(warehouseType), WmsWarehouse::getWarehouseType, warehouseType));
        if (warehouses.isEmpty()) {
            return Map.of();
        }
        List<Long> warehouseIds = warehouses.stream().map(WmsWarehouse::getWarehouseId).toList();
        List<WmsInventory> rows = inventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getDeletedFlag, 0)
                .in(WmsInventory::getMaterialId, materialIds)
                .in(WmsInventory::getWarehouseId, warehouseIds));
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        for (WmsInventory row : rows) {
            BigDecimal available = row.getAvailableQuantity();
            if (available != null) {
                totals.merge(row.getMaterialId(), available, BigDecimal::add);
            }
        }
        return totals;
    }

    /**
     * 规则适用的仓库类型，来自 config_json.warehouseType。没配就按原料仓算。
     */
    private String ruleWarehouseType(AiAutomationRule rule) {
        Object value = readConfig(rule.getConfigJson()).get("warehouseType");
        String text = value == null ? null : String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text.toUpperCase(java.util.Locale.ROOT) : "RAW";
    }

    /**
     * 规则首个适用仓库，用于补货建议落库；取不到就返回 null 让领域服务自己选。
     */
    private Long ruleWarehouseId(AiAutomationRule rule) {
        Object configured = readConfig(rule.getConfigJson()).get("warehouseId");
        Long fixed = asLong(configured);
        if (fixed != null) {
            return fixed;
        }
        List<WmsWarehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .eq(WmsWarehouse::getWarehouseType, ruleWarehouseType(rule))
                .eq(WmsWarehouse::getStatus, BusinessStatus.Common.ENABLE)
                .orderByAsc(WmsWarehouse::getWarehouseId));
        return warehouses.isEmpty() ? null : warehouses.get(0).getWarehouseId();
    }

    private Map<String, Object> readConfig(String configJson) {
        if (!StringUtils.hasText(configJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(configJson, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new BusinessException(400, "规则配置不是合法 JSON：" + ex.getMessage());
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = numericText(value);
        return text == null ? null : parseLongOrNull(text);
    }

    private BigDecimal asDecimal(Object value, BigDecimal fallback) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = numericText(value);
        return text == null ? fallback : parseDecimalOr(text, fallback);
    }

    /** 文本转 long，格式不合法返回 null。 */
    private Long parseLongOrNull(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 文本转小数，格式不合法用调用方给的兜底值。 */
    private BigDecimal parseDecimalOr(String text, BigDecimal fallback) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 取出可参与数字解析的文本：非字符串或空白字符串一律返回 null。 */
    private String numericText(Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return null;
    }

    private AiAutomationStep step(int stepNo, Long runId, String actionType, String targetType, String targetNo,
                                  String status, String message, String bizNo) {
        AiAutomationStep step = new AiAutomationStep();
        step.setRunId(runId);
        step.setStepNo(stepNo);
        step.setActionType(actionType);
        step.setTargetType(targetType);
        step.setTargetNo(targetNo);
        step.setStatus(status);
        step.setMessage(trim(message));
        step.setBizNo(bizNo);
        step.setDeletedFlag(0);
        return step;
    }

    private String trim(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 480 ? text : text.substring(0, 480);
    }

    /** 规则范围内的一个物料及其阈值。 */
    private record MaterialTarget(Long materialId, String materialCode, String materialName, BigDecimal threshold) {
    }

    /** 规则配置里显式列出的物料ID及其阈值。 */
    private record ConfiguredMaterials(List<Long> materialIds, Map<Long, BigDecimal> thresholds) {
    }

    /** 一轮执行产出的条数：登记的风险数与生成的补货建议数。 */
    private record ExecutionTally(int risks, int suggestions) {

        /** 空计数，用于「已存在未关闭风险、本轮跳过」这类不计产出的分支。 */
        private ExecutionTally() {
            this(0, 0);
        }

        /** 累加另一次处理的产出，返回新的计数对象。 */
        private ExecutionTally plus(ExecutionTally other) {
            return new ExecutionTally(risks + other.risks, suggestions + other.suggestions);
        }
    }

    /** 一次检出：物料、阈值、可用量与缺口。 */
    private record Shortage(Long materialId, String materialCode, String materialName,
                            BigDecimal threshold, BigDecimal available, BigDecimal shortage) {
    }
}
