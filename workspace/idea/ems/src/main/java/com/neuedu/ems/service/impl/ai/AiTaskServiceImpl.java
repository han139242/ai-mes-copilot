package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.entity.ai.AiTaskDefinition;
import com.neuedu.ems.entity.ai.AiQueryResult;
import com.neuedu.ems.entity.ai.AiTaskInstance;
import com.neuedu.ems.entity.ai.AiTaskStep;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.exception.AiTaskWaitingInputException;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.mapper.ai.AiTaskDefinitionMapper;
import com.neuedu.ems.mapper.ai.AiTaskInstanceMapper;
import com.neuedu.ems.mapper.ai.AiTaskStepMapper;
import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.service.ai.AiDataQueryService;
import com.neuedu.ems.service.ai.AiTaskService;
import com.neuedu.ems.service.impl.sys.ResponsibilityNotificationSupport;
import com.neuedu.ems.service.mfg.MfgProductionPlanService;
import com.neuedu.ems.service.mfg.MfgWorkOrderService;
import com.neuedu.ems.service.mfg.MfgWorkReportService;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 多步任务 Service 实现。
 *
 * <p>设计要点：
 * 1. 每一步都走确定性领域服务（查单、确认入库、提交检验、审核报工、发通知），不让大模型决定扣多少账；
 * 2. instance_key 在库层唯一，同一事件、同一分钟窗口只产生一个实例，重复事件直接返回原实例；
 * 3. 每步按 max_attempts 重试，全部失败时实例记 PARTIAL_FAILED，并写下 resume_step_no 供人工续跑；
 * 4. 每小时实例数受 max_instances_per_hour 限制，超限时仍落一条 SKIPPED 实例，便于事后核对。</p>
 */
@Service
public class AiTaskServiceImpl implements AiTaskService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService documentNumbers;

    private String currentDocumentNumber(String value) {
        String normalized = value.trim();
        return documentNumbers == null ? normalized : documentNumbers.canonical(normalized);
    }


    private static final Logger log = LoggerFactory.getLogger(AiTaskServiceImpl.class);

    private static final String TRIGGER_EVENT = "EVENT";
    private static final String TRIGGER_SCHEDULE = "SCHEDULE";
    private static final String TRIGGER_MANUAL = "MANUAL";

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_DISABLED = "DISABLED";

    private static final String INSTANCE_RUNNING = "RUNNING";
    private static final String INSTANCE_SUCCEEDED = "SUCCEEDED";
    private static final String INSTANCE_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String INSTANCE_FAILED = "FAILED";
    private static final String INSTANCE_SKIPPED = "SKIPPED";
    /** 步骤在等人补数据，流程本身没错，补齐后可从同一步继续 */
    private static final String INSTANCE_WAITING_INPUT = "WAITING_INPUT";
    /** 人主动取消 */
    private static final String INSTANCE_CANCELLED = "CANCELLED";
    /** 超过 timeout_minutes 仍未收敛 */
    private static final String INSTANCE_TIMEOUT = "TIMEOUT";

    private static final String STEP_PENDING = "PENDING";
    private static final String STEP_SUCCEEDED = "SUCCEEDED";
    private static final String STEP_FAILED = "FAILED";
    private static final String STEP_SKIPPED = "SKIPPED";

    private static final String CAP_INBOUND_LOOKUP = "inbound_lookup";
    private static final String CAP_WORK_ORDER_LOOKUP = "work_order_lookup";
    private static final String CAP_CONFIRM_INBOUND = "confirm_inbound";
    private static final String CAP_SUBMIT_INSPECTION = "submit_inspection";
    private static final String CAP_AUDIT_WORK_REPORT = "audit_work_report";
    private static final String CAP_NOTIFY_WAREHOUSE = "notify_warehouse";
    private static final String CAP_NOTIFY_IQC = "notify_iqc_pending";
    /** 下达来源生产计划：处置方案里「先让来源计划生效」那一步真的能办 */
    private static final String CAP_RELEASE_PLAN = "release_plan";
    /** 下达生产工单：方案里「下达组件工单」那一步 */
    private static final String CAP_RELEASE_WORK_ORDER = "release_work_order";
    /** 复查开工条件：所有步骤做完后回读一次核查结论，用来判断任务是否真的办成了 */
    private static final String CAP_READINESS_RECHECK = "readiness_recheck";

    // 部门编码必须与 org_department.department_code 一致；早期写的 LAMP-DEPT-WMS / LAMP-DEPT-QMS
    // 在库里并不存在，departmentManager 查不到人返回 null，通知事件的 recipients 会是空数组。
    private static final String WAREHOUSE_DEPARTMENT = "DEPT-WMS";
    private static final String QUALITY_DEPARTMENT = "DEPT-QMS";
    private static final String INBOUND_ROUTE = "/wms/inbound-orders";
    private static final String INSPECTION_ROUTE = "/qms/inspection-orders";
    private static final String WORK_REPORT_ROUTE = "/mfg/work-reports";

    private static final DateTimeFormatter INSTANCE_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    // 手动执行只靠毫秒时间戳做实例键，同一毫秒内连点两次会撞 ai_task_instance 的唯一键
    // （uk_ai_task_instance_key）和实例编号唯一键（uk_ai_task_instance_no），第二次手动触发直接失败。
    // 补一个进程内自增序号同时保证两列唯一。
    private static final java.util.concurrent.atomic.AtomicLong MANUAL_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private static String nextInstanceNo(LocalDateTime now) {
        return "AI-TASK-" + now.format(INSTANCE_NO_FORMAT) + "-" + MANUAL_SEQUENCE.incrementAndGet();
    }

    private final AiTaskDefinitionMapper definitionMapper;
    private final AiTaskInstanceMapper instanceMapper;
    private final AiTaskStepMapper stepMapper;
    private final WmsInboundOrderMapper inboundOrderMapper;
    private final WmsInboundOrderService inboundOrderService;
    private final QmsInspectionOrderService inspectionOrderService;
    private final MfgWorkReportService workReportService;
    private final ResponsibilityNotificationSupport notificationSupport;
    private final ObjectMapper objectMapper;
    /** 领域服务：下达计划、下达工单、复查开工条件；为空时对应步骤直接报错而不是静默跳过 */
    private final MfgProductionPlanService productionPlanService;
    private final MfgWorkOrderService workOrderService;
    private final AiDataQueryService dataQueryService;
    /** 建处置任务时要用它取工单与来源计划的主键，字段注入免得多加一个构造参数 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiDataQueryMapper dataQueryMapper;
    /** 确认入库后要回查库存流水，字段注入与 dataQueryMapper 同理，免得多加两个构造参数 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.neuedu.ems.mapper.wms.WmsStockMovementMapper stockMovementMapper;
    /** 回查流水条数时要先知道这张入库单有几条有效明细 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.neuedu.ems.mapper.wms.WmsInboundDetailMapper inboundDetailMapper;

    public AiTaskServiceImpl(AiTaskDefinitionMapper definitionMapper,
                             AiTaskInstanceMapper instanceMapper,
                             AiTaskStepMapper stepMapper,
                             WmsInboundOrderMapper inboundOrderMapper,
                             WmsInboundOrderService inboundOrderService,
                             QmsInspectionOrderService inspectionOrderService,
                             MfgWorkReportService workReportService,
                             ResponsibilityNotificationSupport notificationSupport,
                             ObjectMapper objectMapper,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             MfgProductionPlanService productionPlanService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             MfgWorkOrderService workOrderService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             AiDataQueryService dataQueryService) {
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.stepMapper = stepMapper;
        this.inboundOrderMapper = inboundOrderMapper;
        this.inboundOrderService = inboundOrderService;
        this.inspectionOrderService = inspectionOrderService;
        this.workReportService = workReportService;
        this.notificationSupport = notificationSupport;
        this.objectMapper = objectMapper;
        this.productionPlanService = productionPlanService;
        this.workOrderService = workOrderService;
        this.dataQueryService = dataQueryService;
    }

    /* ==================== 概览与定义维护 ==================== */

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalDefinitions", countDefinitions(null));
        data.put("enabledDefinitions", countDefinitions(STATUS_ENABLED));
        data.put("pausedDefinitions", countDefinitions(STATUS_PAUSED));
        LocalDateTime dayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        data.put("todayInstances", instanceMapper.selectCount(new LambdaQueryWrapper<AiTaskInstance>()
                .eq(AiTaskInstance::getDeletedFlag, 0)
                .ge(AiTaskInstance::getStartedTime, dayStart)));
        data.put("succeededInstances", countInstances(INSTANCE_SUCCEEDED));
        data.put("failedInstances", countInstances(INSTANCE_FAILED) + countInstances(INSTANCE_PARTIAL_FAILED));
        return data;
    }

    private long countDefinitions(String status) {
        LambdaQueryWrapper<AiTaskDefinition> wrapper = new LambdaQueryWrapper<AiTaskDefinition>()
                .eq(AiTaskDefinition::getDeletedFlag, 0);
        if (StringUtils.hasText(status)) {
            wrapper.eq(AiTaskDefinition::getStatus, status);
        }
        return definitionMapper.selectCount(wrapper);
    }

    private long countInstances(String status) {
        return instanceMapper.selectCount(new LambdaQueryWrapper<AiTaskInstance>()
                .eq(AiTaskInstance::getDeletedFlag, 0)
                .eq(AiTaskInstance::getStatus, status));
    }

    @Override
    public Page<AiTaskDefinition> pageDefinitions(Integer current, Integer size, String status, String keyword) {
        LambdaQueryWrapper<AiTaskDefinition> wrapper = new LambdaQueryWrapper<AiTaskDefinition>()
                .eq(AiTaskDefinition::getDeletedFlag, 0)
                .eq(StringUtils.hasText(status), AiTaskDefinition::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(AiTaskDefinition::getDefinitionNo, keyword)
                        .or().like(AiTaskDefinition::getDefinitionName, keyword))
                .orderByDesc(AiTaskDefinition::getDefinitionId);
        return definitionMapper.selectPage(new Page<>(page(current), pageSize(size)), wrapper);
    }

    @Override
    public AiTaskDefinition getDefinition(Long definitionId) {
        return definitionId == null ? null : definitionMapper.selectById(definitionId);
    }

    /**
     * 把开工处置方案落成一条可跟踪的任务定义。
     *
     * <p>方案只告诉人「先做什么」，人一走开就断了。这里把它变成有步骤、有实例、有断点的任务：
     * 先让来源计划生效，再逐张下达组件工单，最后复查开工条件。
     * 默认建成草稿让用户自己确认后再启用，不替用户自动开始执行。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createDefinitionFromStartPlan(String workOrderNo, Long operatorId) {
        if (!StringUtils.hasText(workOrderNo)) {
            throw error("工单号不能为空");
        }
        if (dataQueryMapper == null || dataQueryService == null) {
            throw error("处置任务需要的查询服务不可用，暂时不能生成任务");
        }
        String no = currentDocumentNumber(workOrderNo.trim());
        assertWorkOrderExists(no);
        Map<String, Object> header = dataQueryMapper.selectWorkOrderHeader(no);
        if (header == null || header.isEmpty()) {
            throw error("未找到工单「" + no + "」，无法生成处置任务");
        }
        Long workOrderId = longValue(header.get("workOrderId"));
        List<Map<String, Object>> components = workOrderId == null
                ? List.of() : dataQueryMapper.selectComponentWorkOrders(workOrderId);

        List<Map<String, Object>> rawSteps = new ArrayList<>();
        // 一、来源计划还是草稿的，先下达计划。计划不生效时下达工单会被前置检查拦住。
        java.util.LinkedHashSet<Long> pendingPlans = collectPendingPlanIds(components);
        int seq = appendPlanSteps(rawSteps, pendingPlans, 1);
        // 二、逐张下达组件工单。
        seq = appendComponentSteps(rawSteps, components, seq);
        // 三、复查开工条件，判断这条任务到底办成没办成。
        appendRecheckStep(rawSteps, seq, text(header.get("workOrderNo")));

        if (rawSteps.size() <= 1) {
            throw error("工单「" + no + "」当前没有需要下达的计划或组件工单，不需要生成处置任务");
        }
        AiTaskDefinition definition = buildStartPlanDefinition(header, operatorId, rawSteps);
        Long definitionId = saveDefinition(definition);
        return startPlanResult(definitionId, definition, rawSteps, pendingPlans, header);
    }

    /** 先用开工资讯查一遍，工单查不到就直接报错，避免后面拼出一堆空步骤。 */
    private void assertWorkOrderExists(String workOrderNo) {
        AiQueryResult readiness = dataQueryService.queryWorkOrderReadiness(workOrderNo);
        if (readiness == null || readiness.getRows().isEmpty()) {
            throw error("未找到工单「" + workOrderNo + "」，无法生成处置任务");
        }
    }

    /** 收集「组件工单还没下达、且来源计划还没生效」的计划 id，按出现顺序去重。 */
    private java.util.LinkedHashSet<Long> collectPendingPlanIds(List<Map<String, Object>> components) {
        java.util.LinkedHashSet<Long> pendingPlans = new java.util.LinkedHashSet<>();
        for (Map<String, Object> item : components) {
            if (!notStartedStatus(text(item.get("status")))) {
                continue;
            }
            if ("RELEASED".equals(text(item.get("planStatus")))) {
                continue;
            }
            Long planId = longValue(item.get("planId"));
            if (planId != null && planId > 0) {
                pendingPlans.add(planId);
            }
        }
        return pendingPlans;
    }

    /** 为每个待下达的计划追加一步「下达来源生产计划」，返回下一个可用步骤号。 */
    private int appendPlanSteps(List<Map<String, Object>> rawSteps,
                                java.util.LinkedHashSet<Long> pendingPlans,
                                int seq) {
        int next = seq;
        for (Long planId : pendingPlans) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepNo", next++);
            step.put("label", "下达来源生产计划 #" + planId);
            step.put("kind", "command");
            step.put("capability", CAP_RELEASE_PLAN);
            step.put("targetType", "PRODUCTION_PLAN");
            step.put("targetNo", "#" + planId);
            step.put("bizId", planId);
            rawSteps.add(step);
        }
        return next;
    }

    /** 为每张未下达的组件工单追加一步「下达组件工单」，返回下一个可用步骤号。 */
    private int appendComponentSteps(List<Map<String, Object>> rawSteps,
                                     List<Map<String, Object>> components,
                                     int seq) {
        int next = seq;
        for (Map<String, Object> item : components) {
            if (!notStartedStatus(text(item.get("status")))) {
                continue;
            }
            Long childId = longValue(item.get("workOrderId"));
            if (childId == null) {
                continue;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepNo", next++);
            step.put("label", "下达组件工单 " + text(item.get("workOrderNo")));
            step.put("kind", "command");
            step.put("capability", CAP_RELEASE_WORK_ORDER);
            step.put("targetType", "WORK_ORDER");
            step.put("targetNo", text(item.get("workOrderNo")));
            step.put("bizId", childId);
            rawSteps.add(step);
        }
        return next;
    }

    /** 末尾追加一步「复查工单开工条件」。 */
    private void appendRecheckStep(List<Map<String, Object>> rawSteps, int seq, String targetNo) {
        Map<String, Object> recheck = new LinkedHashMap<>();
        recheck.put("stepNo", seq);
        recheck.put("label", "复查工单开工条件");
        recheck.put("kind", "query");
        recheck.put("capability", CAP_READINESS_RECHECK);
        recheck.put("targetType", "WORK_ORDER");
        recheck.put("targetNo", targetNo);
        rawSteps.add(recheck);
    }

    /** 组装待保存的任务定义（草稿态）。 */
    private AiTaskDefinition buildStartPlanDefinition(Map<String, Object> header,
                                                      Long operatorId,
                                                      List<Map<String, Object>> rawSteps) {
        AiTaskDefinition definition = new AiTaskDefinition();
        definition.setDefinitionName(trim("处置工单 " + text(header.get("workOrderNo")) + " 的开工前置条件", 120));
        definition.setTriggerType(TRIGGER_MANUAL);
        definition.setStatus(STATUS_DRAFT);
        definition.setRemark(trim("由开工处置方案生成。先让来源计划生效、再下达组件工单，最后复查开工条件。"
                + "组件生产、检验与入库仍需按工序实际完成。", 480));
        definition.setCreatedBy(operatorId);
        definition.setStepsJson(writeStepsJson(rawSteps));
        return definition;
    }

    /** 建单成功后返回给调用方的摘要。 */
    private Map<String, Object> startPlanResult(Long definitionId,
                                                AiTaskDefinition definition,
                                                List<Map<String, Object>> rawSteps,
                                                java.util.LinkedHashSet<Long> pendingPlans,
                                                Map<String, Object> header) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("definitionId", definitionId);
        data.put("definitionNo", definition.getDefinitionNo());
        data.put("definitionName", definition.getDefinitionName());
        data.put("stepCount", rawSteps.size());
        data.put("planCount", pendingPlans.size());
        data.put("workOrderNo", text(header.get("workOrderNo")));
        data.put("status", definition.getStatus());
        data.put("note", "已建成草稿任务，确认步骤无误后再启用或手动执行一次");
        return data;
    }

    private String writeStepsJson(List<Map<String, Object>> rawSteps) {
        try {
            return objectMapper.writeValueAsString(rawSteps);
        } catch (Exception ex) {
            throw error("任务步骤无法序列化：" + ex.getMessage());
        }
    }

    private static boolean notStartedStatus(String status) {
        return "DRAFT".equals(status) || "APPROVED".equals(status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDefinition(AiTaskDefinition definition) {
        validateDefinitionHeader(definition);
        List<StepDef> steps = requireParsedSteps(definition.getStepsJson());
        normalizeDefinition(definition, steps);
        applyDefinitionDefaults(definition);
        LocalDateTime now = LocalDateTime.now();
        definition.setCreatedTime(now);
        definition.setUpdatedTime(now);
        definition.setDeletedFlag(0);
        definition.setVersion(0);
        definitionMapper.insert(definition);
        return definition.getDefinitionId();
    }

    /** 校验必填项：定义本身、名称、步骤 JSON。 */
    private void validateDefinitionHeader(AiTaskDefinition definition) {
        if (definition == null) {
            throw error("任务定义不能为空");
        }
        if (!StringUtils.hasText(definition.getDefinitionName())) {
            throw error("任务名称不能为空");
        }
        if (!StringUtils.hasText(definition.getStepsJson())) {
            throw error("任务步骤不能为空");
        }
    }

    /** 步骤 JSON 必须能解析出至少一步。 */
    private List<StepDef> requireParsedSteps(String stepsJson) {
        List<StepDef> steps = parseSteps(stepsJson);
        if (steps.isEmpty()) {
            throw error("任务步骤不能为空");
        }
        return steps;
    }

    /** 归一化触发器、回写步骤 JSON、补定义编号与初始状态。 */
    private void normalizeDefinition(AiTaskDefinition definition, List<StepDef> steps) {
        String triggerType = normalizeTrigger(definition.getTriggerType());
        boolean eventTriggered = TRIGGER_EVENT.equals(triggerType);
        if (eventTriggered && !StringUtils.hasText(definition.getTriggerEventKey())) {
            throw error("事件触发必须填写触发事件键");
        }
        definition.setTriggerType(triggerType);
        definition.setStepsJson(writeSteps(steps));
        boolean hasDefinitionNo = StringUtils.hasText(definition.getDefinitionNo());
        definition.setDefinitionNo(hasDefinitionNo
                ? definition.getDefinitionNo().trim()
                : "TASK-" + LocalDateTime.now().format(INSTANCE_NO_FORMAT));
        if (!StringUtils.hasText(definition.getStatus())) {
            definition.setStatus(STATUS_DRAFT);
        }
    }

    /** 数值型配置项的兜底默认值。 */
    private void applyDefinitionDefaults(AiTaskDefinition definition) {
        definition.setIntervalMinutes(positiveOrDefault(definition.getIntervalMinutes(), 10));
        definition.setMaxAttempts(positiveOrDefault(definition.getMaxAttempts(), 2));
        definition.setMaxInstancesPerHour(positiveOrDefault(definition.getMaxInstancesPerHour(), 50));
        definition.setTimeoutMinutes(positiveOrDefault(definition.getTimeoutMinutes(), 30));
        definition.setMaxStepCallsPerDay(positiveOrDefault(definition.getMaxStepCallsPerDay(), 500));
        if (definition.getSuccessCount() == null) {
            definition.setSuccessCount(0);
        }
        if (definition.getFailCount() == null) {
            definition.setFailCount(0);
        }
    }

    /** 为空或小于 1 时取默认值。 */
    private Integer positiveOrDefault(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDefinition(AiTaskDefinition definition) {
        if (definition == null || definition.getDefinitionId() == null) {
            throw error("任务定义ID不能为空");
        }
        AiTaskDefinition existing = definitionMapper.selectById(definition.getDefinitionId());
        if (existing == null || Integer.valueOf(1).equals(existing.getDeletedFlag())) {
            throw error("任务定义不存在或已删除");
        }
        if (StringUtils.hasText(definition.getStepsJson())) {
            List<StepDef> steps = parseSteps(definition.getStepsJson());
            if (steps.isEmpty()) {
                throw error("任务步骤不能为空");
            }
            definition.setStepsJson(writeSteps(steps));
        }
        if (StringUtils.hasText(definition.getTriggerType())) {
            definition.setTriggerType(normalizeTrigger(definition.getTriggerType()));
        }
        definition.setVersion(existing.getVersion());
        definition.setUpdatedTime(LocalDateTime.now());
        return definitionMapper.updateById(definition) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeDefinition(Long definitionId) {
        AiTaskDefinition existing = getDefinition(definitionId);
        if (existing == null) {
            return false;
        }
        AiTaskDefinition update = new AiTaskDefinition();
        update.setDefinitionId(definitionId);
        update.setDeletedFlag(1);
        update.setVersion(existing.getVersion());
        update.setUpdatedTime(LocalDateTime.now());
        return definitionMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStatus(Long definitionId, String status) {
        AiTaskDefinition existing = getDefinition(definitionId);
        if (existing == null) {
            throw error("任务定义不存在或已删除");
        }
        String target = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of(STATUS_DRAFT, STATUS_ENABLED, STATUS_PAUSED, STATUS_DISABLED).contains(target)) {
            throw error("任务状态只能是 DRAFT、ENABLED、PAUSED 或 DISABLED");
        }
        if (STATUS_ENABLED.equals(target)) {
            List<StepDef> steps = parseSteps(existing.getStepsJson());
            if (steps.isEmpty()) {
                throw error("任务没有配置步骤，不能启用");
            }
            if (TRIGGER_EVENT.equals(existing.getTriggerType())
                    && !StringUtils.hasText(existing.getTriggerEventKey())) {
                throw error("事件触发的任务必须填写触发事件键");
            }
        }
        AiTaskDefinition update = new AiTaskDefinition();
        update.setDefinitionId(definitionId);
        update.setStatus(target);
        update.setVersion(existing.getVersion());
        update.setUpdatedTime(LocalDateTime.now());
        return definitionMapper.updateById(update) > 0;
    }

    @Override
    public Map<String, Object> preview(Long definitionId) {
        AiTaskDefinition definition = getDefinition(definitionId);
        if (definition == null) {
            throw error("任务定义不存在或已删除");
        }
        List<StepDef> steps = parseSteps(definition.getStepsJson());
        List<Map<String, Object>> stepViews = new ArrayList<>();
        for (StepDef step : steps) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("stepNo", step.stepNo());
            view.put("label", step.label());
            view.put("kind", step.kind());
            view.put("capability", step.capability());
            view.put("supported", isSupported(step.capability()));
            stepViews.add(view);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dryRun", true);
        data.put("definitionId", definition.getDefinitionId());
        data.put("definitionNo", definition.getDefinitionNo());
        data.put("definitionName", definition.getDefinitionName());
        data.put("triggerType", definition.getTriggerType());
        data.put("triggerEventKey", definition.getTriggerEventKey());
        data.put("steps", stepViews);
        data.put("totalSteps", steps.size());
        return data;
    }

    /* ==================== 执行 ==================== */

    /**
     * 手动触发一次。
     *
     * <p>刻意不加事务：步骤里调的是带事务的领域服务，领域服务拒绝时会把共享事务标成
     * rollback-only，外层再吞掉异常就变成 UnexpectedRollbackException，连「实例已建、
     * 第几步失败」都存不下来。让每步各自提交，实例与步骤流水才能作为失败现场保留。</p>
     */
    @Override
    public Map<String, Object> run(Long definitionId, Long operatorId) {
        AiTaskDefinition definition = getDefinition(definitionId);
        if (definition == null) {
            throw error("任务定义不存在或已删除");
        }
        if (STATUS_DISABLED.equals(definition.getStatus())) {
            throw error("任务已停用，不能执行");
        }
        if (dailyStepLimitReached(definition)) {
            throw error("任务 " + definition.getDefinitionNo() + " 今日步骤调用量已达上限 "
                    + definition.getMaxStepCallsPerDay() + "，明天会自动恢复，也可以先把上限调大");
        }
        String source = "MANUAL:" + (operatorId == null ? "system" : operatorId);
        String instanceKey = "MANUAL:" + definitionId + ":" + LocalDateTime.now().format(INSTANCE_NO_FORMAT)
                + "-" + MANUAL_SEQUENCE.incrementAndGet();
        return execute(definition, instanceKey, source, null, null, operatorId);
    }

    /**
     * 事件触发。与 run 一样不加事务：领域服务拒绝时若共享同一个事务，
     * 实例和步骤流水会被一起回滚，事后查不到是哪一步、为什么没过。
     */
    @Override
    public int runByEvent(String eventKey, Long aggregateId, String aggregateType, String bizNo) {
        if (!StringUtils.hasText(eventKey) || aggregateId == null) {
            return 0;
        }
        List<AiTaskDefinition> definitions = definitionMapper.selectList(new LambdaQueryWrapper<AiTaskDefinition>()
                .eq(AiTaskDefinition::getDeletedFlag, 0)
                .eq(AiTaskDefinition::getStatus, STATUS_ENABLED)
                .eq(AiTaskDefinition::getTriggerType, TRIGGER_EVENT)
                .eq(AiTaskDefinition::getTriggerEventKey, eventKey));
        int executed = 0;
        for (AiTaskDefinition definition : definitions) {
            String instanceKey = "EVENT:" + definition.getDefinitionId() + ":" + eventKey + ":" + aggregateId;
            AiTaskInstance existing = instanceMapper.selectOne(new LambdaQueryWrapper<AiTaskInstance>()
                    .eq(AiTaskInstance::getInstanceKey, instanceKey)
                    .last("LIMIT 1"));
            if (existing != null) {
                log.info("任务实例已存在，跳过重复事件：{}", instanceKey);
                continue;
            }
            if (hourlyLimitReached(definition)) {
                insertSkippedInstance(definition, instanceKey, "EVENT:" + eventKey + "#" + aggregateId,
                        "本小时实例数已达上限 " + definition.getMaxInstancesPerHour() + "，本次事件不再执行",
                        aggregateType, aggregateId, bizNo);
                continue;
            }
            if (dailyStepLimitReached(definition)) {
                insertSkippedInstance(definition, instanceKey, "EVENT:" + eventKey + "#" + aggregateId,
                        "今日步骤调用量已达上限 " + definition.getMaxStepCallsPerDay() + "，本次事件不再执行",
                        aggregateType, aggregateId, bizNo);
                continue;
            }
            execute(definition, instanceKey, "EVENT:" + eventKey + "#" + aggregateId, aggregateType, aggregateId, 1L);
            executed++;
        }
        return executed;
    }

    @Override
    public int runScheduled() {
        List<AiTaskDefinition> definitions = definitionMapper.selectList(new LambdaQueryWrapper<AiTaskDefinition>()
                .eq(AiTaskDefinition::getDeletedFlag, 0)
                .eq(AiTaskDefinition::getStatus, STATUS_ENABLED)
                .eq(AiTaskDefinition::getTriggerType, TRIGGER_SCHEDULE)
                .orderByAsc(AiTaskDefinition::getDefinitionId));
        int executed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AiTaskDefinition definition : definitions) {
            int interval = definition.getIntervalMinutes() == null ? 10 : definition.getIntervalMinutes();
            if (definition.getLastRunTime() != null
                    && definition.getLastRunTime().plusMinutes(interval).isAfter(now)) {
                continue;
            }
            if (hourlyLimitReached(definition)) {
                log.warn("任务 {} 本小时实例数已达上限，跳过定时触发", definition.getDefinitionNo());
                continue;
            }
            if (dailyStepLimitReached(definition)) {
                continue;
            }
            String instanceKey = "SCHEDULE:" + definition.getDefinitionId() + ":" + now.format(MINUTE_FORMAT);
            if (instanceMapper.selectCount(new LambdaQueryWrapper<AiTaskInstance>()
                    .eq(AiTaskInstance::getInstanceKey, instanceKey)) > 0) {
                continue;
            }
            try {
                execute(definition, instanceKey, "SCHEDULE:" + now.format(MINUTE_FORMAT), null, null, 1L);
                executed++;
            } catch (RuntimeException ex) {
                log.warn("定时任务 {} 执行失败：{}", definition.getDefinitionNo(), ex.getMessage());
            }
        }
        return executed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverInterrupted() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<AiTaskInstance> running = instanceMapper.selectList(new LambdaQueryWrapper<AiTaskInstance>()
                .eq(AiTaskInstance::getDeletedFlag, 0)
                .eq(AiTaskInstance::getStatus, INSTANCE_RUNNING)
                .lt(AiTaskInstance::getStartedTime, threshold)
                .orderByAsc(AiTaskInstance::getInstanceId));
        int recovered = 0;
        for (AiTaskInstance instance : running) {
            int current = instance.getCurrentStepNo() == null ? 0 : instance.getCurrentStepNo();
            AiTaskInstance update = new AiTaskInstance();
            update.setInstanceId(instance.getInstanceId());
            update.setStatus(INSTANCE_PARTIAL_FAILED);
            update.setResumeStepNo(Math.max(1, current + 1));
            update.setFinishedTime(LocalDateTime.now());
            update.setErrorMessage("进程中断，已保留已完成步骤，可从第 " + Math.max(1, current + 1) + " 步续跑");
            update.setSummary(trim("进程中断，可从第 " + Math.max(1, current + 1) + " 步续跑"));
            update.setVersion(instance.getVersion());
            update.setUpdatedTime(LocalDateTime.now());
            instanceMapper.updateById(update);
            recovered++;
        }
        if (recovered > 0) {
            log.warn("恢复了 {} 个中断的任务实例", recovered);
        }
        return recovered;
    }

    /**
     * 续跑。同样不加事务，理由同 run：失败现场要留下来给人看。
     */
    @Override
    public Map<String, Object> resume(Long instanceId, Long operatorId) {
        AiTaskInstance instance = loadResumableInstance(instanceId);
        AiTaskDefinition definition = getDefinition(instance.getDefinitionId());
        if (definition == null) {
            throw error("任务定义不存在或已删除");
        }
        List<StepDef> steps = parseSteps(definition.getStepsJson());
        int startStepNo = instance.getResumeStepNo() == null ? 1 : instance.getResumeStepNo();
        if (startStepNo > steps.size()) {
            throw error("所有步骤均已执行，无需续跑");
        }
        instance = markResumeStarted(instanceId, definition, instance);
        return executeSteps(definition, instance, steps, startStepNo, operatorId == null ? 1L : operatorId);
    }

    /** 取实例并校验它当前确实可以续跑。 */
    private AiTaskInstance loadResumableInstance(Long instanceId) {
        AiTaskInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null || Integer.valueOf(1).equals(instance.getDeletedFlag())) {
            throw error("任务实例不存在或已删除");
        }
        if (INSTANCE_SUCCEEDED.equals(instance.getStatus())) {
            throw error("实例已成功完成，无需续跑");
        }
        if (INSTANCE_RUNNING.equals(instance.getStatus())) {
            throw error("实例正在执行，不能重复续跑");
        }
        if (INSTANCE_CANCELLED.equals(instance.getStatus())) {
            throw error("实例已被取消，需要重新触发一次而不是续跑");
        }
        return instance;
    }

    /** 把实例改成执行中并顺延截止时间，随后取回带新版本号的实例。 */
    private AiTaskInstance markResumeStarted(Long instanceId,
                                             AiTaskDefinition definition,
                                             AiTaskInstance instance) {
        AiTaskInstance update = new AiTaskInstance();
        update.setInstanceId(instanceId);
        update.setStatus(INSTANCE_RUNNING);
        update.setErrorMessage(null);
        update.setWaitingReason(null);
        // 续跑相当于重新开始一段执行，截止时间要往后顺延，否则一启动就被判超时
        update.setDeadlineTime(LocalDateTime.now().plusMinutes(
                definition.getTimeoutMinutes() == null ? 30 : definition.getTimeoutMinutes()));
        update.setVersion(instance.getVersion());
        update.setUpdatedTime(LocalDateTime.now());
        instanceMapper.updateById(update);
        // 乐观锁会把版本号加一，续跑前必须取回最新版本，否则收尾更新会撞版本冲突
        AiTaskInstance refreshed = instanceMapper.selectById(instanceId);
        AiTaskInstance current = refreshed != null ? refreshed : instance;
        current.setStatus(INSTANCE_RUNNING);
        return current;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(Long instanceId, Long operatorId, String reason) {
        AiTaskInstance instance = instanceId == null ? null : instanceMapper.selectById(instanceId);
        if (instance == null || Integer.valueOf(1).equals(instance.getDeletedFlag())) {
            throw error("任务实例不存在或已删除");
        }
        if (INSTANCE_SUCCEEDED.equals(instance.getStatus())) {
            throw error("实例已经执行成功，没有可取消的内容");
        }
        if (INSTANCE_CANCELLED.equals(instance.getStatus())) {
            throw error("实例已取消，无需重复操作");
        }
        if (INSTANCE_SKIPPED.equals(instance.getStatus())) {
            throw error("实例因循环防护未执行，没有可取消的内容");
        }
        LocalDateTime now = LocalDateTime.now();
        String note = StringUtils.hasText(reason) ? reason.trim() : "人工取消";
        AiTaskInstance update = new AiTaskInstance();
        update.setInstanceId(instanceId);
        update.setStatus(INSTANCE_CANCELLED);
        update.setFinishedTime(now);
        update.setSummary(trim("已取消：" + note));
        update.setErrorMessage(null);
        update.setWaitingReason(null);
        update.setUpdatedBy(operatorId);
        update.setVersion(instance.getVersion());
        update.setUpdatedTime(now);
        instanceMapper.updateById(update);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instanceId", instanceId);
        data.put("instanceNo", instance.getInstanceNo());
        data.put("status", INSTANCE_CANCELLED);
        data.put("summary", update.getSummary());
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int expireOverdue() {
        LocalDateTime now = LocalDateTime.now();
        // WAITING_INPUT 也要一起收敛：等人补齐的单据同样会一直挂着，
        // 只扫 RUNNING 的话「等待补充」会成为永不超时的黑洞。
        List<AiTaskInstance> overdue = instanceMapper.selectList(new LambdaQueryWrapper<AiTaskInstance>()
                .eq(AiTaskInstance::getDeletedFlag, 0)
                .in(AiTaskInstance::getStatus, INSTANCE_RUNNING, INSTANCE_WAITING_INPUT)
                .isNotNull(AiTaskInstance::getDeadlineTime)
                .lt(AiTaskInstance::getDeadlineTime, now)
                .orderByAsc(AiTaskInstance::getInstanceId));
        int expired = 0;
        for (AiTaskInstance instance : overdue) {
            // 续跑起点沿用实例自己的 resume_step_no：等待补充的实例要重跑卡住的那一步，
            // 这里若按 current_step_no+1 顺推，会把「等人补齐的那步」直接跳过去。
            int resumeStepNo = instance.getResumeStepNo() == null ? 1 : instance.getResumeStepNo();
            AiTaskInstance update = new AiTaskInstance();
            update.setInstanceId(instance.getInstanceId());
            update.setStatus(INSTANCE_TIMEOUT);
            update.setResumeStepNo(Math.max(1, resumeStepNo));
            update.setFinishedTime(now);
            update.setErrorMessage("执行已超过截止时间 " + instance.getDeadlineTime() + "，收敛为超时");
            update.setSummary(trim("超时收敛，可从第 " + Math.max(1, resumeStepNo) + " 步续跑"));
            update.setVersion(instance.getVersion());
            update.setUpdatedTime(now);
            instanceMapper.updateById(update);
            expired++;
        }
        if (expired > 0) {
            log.warn("超时收敛了 {} 个任务实例", expired);
        }
        return expired;
    }

    @Override
    public Page<AiTaskInstance> pageInstances(Integer current, Integer size, Long definitionId, String status) {
        LambdaQueryWrapper<AiTaskInstance> wrapper = new LambdaQueryWrapper<AiTaskInstance>()
                .eq(AiTaskInstance::getDeletedFlag, 0)
                .eq(definitionId != null, AiTaskInstance::getDefinitionId, definitionId)
                .eq(StringUtils.hasText(status), AiTaskInstance::getStatus, status)
                .orderByDesc(AiTaskInstance::getInstanceId);
        return instanceMapper.selectPage(new Page<>(page(current), pageSize(size)), wrapper);
    }

    @Override
    public Map<String, Object> getInstanceDetail(Long instanceId) {
        AiTaskInstance instance = instanceId == null ? null : instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw error("任务实例不存在或已删除");
        }
        List<AiTaskStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AiTaskStep>()
                .eq(AiTaskStep::getInstanceId, instanceId)
                .eq(AiTaskStep::getDeletedFlag, 0)
                .orderByAsc(AiTaskStep::getStepNo));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instance", instance);
        data.put("steps", steps);
        data.put("definition", getDefinition(instance.getDefinitionId()));
        return data;
    }

    @Override
    public List<Map<String, Object>> stepCapabilities() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(capability(CAP_INBOUND_LOOKUP, "查询入库单", "query", "WMS_INBOUND_ORDER",
                "按触发源的入库单ID读取单据，校验存在且状态为待确认"));
        list.add(capability(CAP_WORK_ORDER_LOOKUP, "查询生产工单", "query", "WORK_ORDER",
                "按触发源的工单ID读取工单，用于在执行前确认状态"));
        list.add(capability(CAP_CONFIRM_INBOUND, "确认入库", "command", "WMS_INBOUND_ORDER",
                "调用入库确认领域服务，按明细扣批次并增加库存现量"));
        list.add(capability(CAP_SUBMIT_INSPECTION, "提交检验结果", "command", "INSPECTION_ORDER",
                "按检验单ID提交判定结果；方案要求逐件全检时必须先由质检员逐件填报，缺逐件结果会拒绝提交"));
        list.add(capability(CAP_AUDIT_WORK_REPORT, "审核报工", "command", "WORK_REPORT",
                "按报工单ID执行审核，默认通过"));
        list.add(capability(CAP_NOTIFY_WAREHOUSE, "通知仓管", "notify", "WMS_INBOUND_ORDER",
                "给仓库负责人发一条待处理通知，带单据号和跳转路径"));
        list.add(capability(CAP_NOTIFY_IQC, "通知质检", "notify", "INSPECTION_ORDER",
                "给质量负责人发一条来料检验待办通知"));
        list.add(capability(CAP_RELEASE_PLAN, "下达生产计划", "command", "PRODUCTION_PLAN",
                "调用计划下达领域服务，把来源计划从草稿置为已生效；已生效时按幂等处理返回原状态"));
        list.add(capability(CAP_RELEASE_WORK_ORDER, "下达生产工单", "command", "WORK_ORDER",
                "调用工单下达领域服务，把草稿工单置为已下达并展开工序任务；前置条件不满足时会被领域服务拒绝"));
        list.add(capability(CAP_READINESS_RECHECK, "复查开工条件", "query", "WORK_ORDER",
                "重新跑一遍开工条件核查，把结论写进步骤流水，用来判断这条处置任务是否真的办成了"));
        return list;
    }

    private Map<String, Object> capability(String code, String label, String kind, String targetType,
                                           String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        item.put("kind", kind);
        item.put("targetType", targetType);
        item.put("description", description);
        return item;
    }

    /* ==================== 实例执行细节 ==================== */

    private Map<String, Object> execute(AiTaskDefinition definition, String instanceKey, String triggerSource,
                                        String aggregateType, Long aggregateId, Long operatorId) {
        List<StepDef> steps = parseSteps(definition.getStepsJson());
        if (steps.isEmpty()) {
            throw error("任务没有配置步骤，不能执行");
        }
        LocalDateTime now = LocalDateTime.now();
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceNo(nextInstanceNo(now));
        instance.setInstanceKey(instanceKey);
        instance.setDefinitionId(definition.getDefinitionId());
        instance.setDefinitionNo(definition.getDefinitionNo());
        instance.setDefinitionName(definition.getDefinitionName());
        instance.setTriggerType(definition.getTriggerType());
        instance.setTriggerSource(trim(triggerSource, 240));
        instance.setStatus(INSTANCE_RUNNING);
        instance.setCurrentStepNo(0);
        instance.setResumeStepNo(1);
        instance.setTotalSteps(steps.size());
        instance.setAttemptCount(0);
        instance.setStartedTime(now);
        instance.setDeadlineTime(now.plusMinutes(
                definition.getTimeoutMinutes() == null ? 30 : definition.getTimeoutMinutes()));
        instance.setCreatedBy(operatorId);
        instance.setCreatedTime(now);
        instance.setUpdatedBy(operatorId);
        instance.setUpdatedTime(now);
        instance.setDeletedFlag(0);
        instance.setVersion(0);
        instanceMapper.insert(instance);
        Map<String, Object> result = executeSteps(definition, instance, steps, 1, operatorId, aggregateType, aggregateId);
        result.put("instanceId", instance.getInstanceId());
        result.put("instanceNo", instance.getInstanceNo());
        return result;
    }

    private Map<String, Object> executeSteps(AiTaskDefinition definition, AiTaskInstance instance,
                                            List<StepDef> steps, int startStepNo, Long operatorId) {
        return executeSteps(definition, instance, steps, startStepNo, operatorId, null, null);
    }

    private Map<String, Object> executeSteps(AiTaskDefinition definition, AiTaskInstance instance,
                                            List<StepDef> steps, int startStepNo, Long operatorId,
                                            String aggregateType, Long aggregateId) {
        long begin = System.currentTimeMillis();
        int maxAttempts = definition.getMaxAttempts() == null ? 2 : definition.getMaxAttempts();
        StepRunState state = runAllSteps(instance, steps, startStepNo, maxAttempts, aggregateType, aggregateId, operatorId);
        LocalDateTime now = LocalDateTime.now();
        persistStepRecords(state.records, now);
        String status = resolveInstanceStatus(state);
        String summary = buildInstanceSummary(state, steps);
        AiTaskInstance update = finishInstance(instance, steps, state, status, summary, begin, now);
        updateDefinitionCounters(definition, status, now);
        return buildRunResult(instance, steps, update, status, summary);
    }

    /** 逐步骤执行过程中累积的状态，避免用一堆局部变量在方法间传递。 */
    private static final class StepRunState {
        private final List<AiTaskStep> records = new ArrayList<>();
        private final List<String> succeeded = new ArrayList<>();
        private boolean failed;
        private int failedStepNo;
        private String failMessage;
        private String waitingReason;
    }

    /** 单步重试的最终结果。 */
    private record StepAttemptResult(boolean ok, boolean waiting, String lastError, long elapsedMs) {
    }

    /** 按步骤顺序跑一遍，返回累积的执行状态。 */
    private StepRunState runAllSteps(AiTaskInstance instance,
                                     List<StepDef> steps,
                                     int startStepNo,
                                     int maxAttempts,
                                     String aggregateType,
                                     Long aggregateId,
                                     Long operatorId) {
        StepRunState state = new StepRunState();
        for (StepDef step : steps) {
            runSingleStep(state, instance, step, startStepNo, maxAttempts, aggregateType, aggregateId, operatorId);
        }
        return state;
    }

    /** 跑单个步骤，把结果并进累积状态。 */
    private void runSingleStep(StepRunState state,
                               AiTaskInstance instance,
                               StepDef step,
                               int startStepNo,
                               int maxAttempts,
                               String aggregateType,
                               Long aggregateId,
                               Long operatorId) {
        AiTaskStep record = newStepRecord(instance, step);
        if (step.stepNo() < startStepNo) {
            // 续跑时前序步骤的历史流水已经在库里，不能重复插入
            state.succeeded.add("第 " + step.stepNo() + " 步 " + step.label());
            return;
        }
        if (state.failed) {
            record.setStatus(STEP_SKIPPED);
            record.setMessage("前序步骤失败，本步未执行");
            state.records.add(record);
            return;
        }
        StepAttemptResult attempt = attemptStep(step, record, instance, maxAttempts,
                aggregateType, aggregateId, operatorId);
        if (attempt.ok()) {
            state.succeeded.add("第 " + step.stepNo() + " 步 " + step.label());
        } else {
            record.setStatus(STEP_FAILED);
            record.setMessage(trim(attempt.lastError(), 1000));
            record.setElapsedMs(attempt.elapsedMs());
            state.failed = true;
            state.failedStepNo = step.stepNo();
            state.failMessage = attempt.lastError();
            if (attempt.waiting()) {
                state.waitingReason = attempt.lastError();
            }
        }
        state.records.add(record);
    }

    /** 新建一条步骤流水（尚未落库）。 */
    private AiTaskStep newStepRecord(AiTaskInstance instance, StepDef step) {
        AiTaskStep record = new AiTaskStep();
        record.setInstanceId(instance.getInstanceId());
        record.setStepNo(step.stepNo());
        record.setStepLabel(step.label());
        record.setCapability(step.capability());
        record.setTargetType(step.targetType());
        record.setTargetNo(step.targetNo());
        record.setAttemptCount(0);
        record.setCreatedTime(LocalDateTime.now());
        record.setDeletedFlag(0);
        return record;
    }

    /** 单步按最大重试次数执行；等人补齐数据的不再重试。 */
    private StepAttemptResult attemptStep(StepDef step,
                                          AiTaskStep record,
                                          AiTaskInstance instance,
                                          int maxAttempts,
                                          String aggregateType,
                                          Long aggregateId,
                                          Long operatorId) {
        String lastError = null;
        long stepBegin = System.currentTimeMillis();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            record.setAttemptCount(attempt);
            try {
                StepOutcome outcome = runStep(step, instance, aggregateType, aggregateId, operatorId);
                record.setStatus(STEP_SUCCEEDED);
                record.setBizNo(trim(outcome.bizNo(), 64));
                record.setBizId(outcome.bizId());
                record.setMessage(trim(outcome.message(), 1000));
                long elapsed = System.currentTimeMillis() - stepBegin;
                record.setElapsedMs(elapsed);
                return new StepAttemptResult(true, false, null, elapsed);
            } catch (AiTaskWaitingInputException ex) {
                // 缺人填的数据，重试多少遍都一样，直接停在等人补齐，不算失败
                lastError = ex.getMessage() == null ? "需要人工补充数据后才能继续" : ex.getMessage();
                log.info("任务 {} 第 {} 步等待人工补充：{}",
                        instance.getInstanceNo(), step.stepNo(), lastError);
                return new StepAttemptResult(false, true, lastError, System.currentTimeMillis() - stepBegin);
            } catch (RuntimeException ex) {
                lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.warn("任务 {} 第 {} 步第 {} 次执行失败：{}",
                        instance.getInstanceNo(), step.stepNo(), attempt, lastError);
            }
        }
        return new StepAttemptResult(false, false, lastError, System.currentTimeMillis() - stepBegin);
    }

    /** 步骤流水统一落库。 */
    private void persistStepRecords(List<AiTaskStep> records, LocalDateTime now) {
        for (AiTaskStep record : records) {
            record.setUpdatedTime(now);
            stepMapper.insert(record);
        }
    }

    /** 由执行状态推导实例终态。 */
    private String resolveInstanceStatus(StepRunState state) {
        String status;
        if (!state.failed) {
            status = INSTANCE_SUCCEEDED;
        } else if (state.waitingReason != null) {
            // 有人要补的东西没补，流程不算断，停在 WAITING_INPUT 等补齐后续跑
            status = INSTANCE_WAITING_INPUT;
        } else if (state.succeeded.isEmpty()) {
            status = INSTANCE_FAILED;
        } else {
            status = INSTANCE_PARTIAL_FAILED;
        }
        return status;
    }

    /** 生成实例摘要文案。 */
    private String buildInstanceSummary(StepRunState state, List<StepDef> steps) {
        String summary;
        if (!state.failed) {
            summary = trim("全部 " + steps.size() + " 步执行成功");
        } else if (state.waitingReason != null) {
            summary = trim("已完成 " + state.succeeded.size() + " 步，第 " + state.failedStepNo
                    + " 步等待人工补充：" + state.waitingReason);
        } else {
            summary = trim("已完成 " + state.succeeded.size() + " 步，第 " + state.failedStepNo
                    + " 步失败：" + state.failMessage);
        }
        return summary;
    }

    /** 回写实例终态，返回落库用的更新对象。 */
    private AiTaskInstance finishInstance(AiTaskInstance instance,
                                          List<StepDef> steps,
                                          StepRunState state,
                                          String status,
                                          String summary,
                                          long begin,
                                          LocalDateTime now) {
        AiTaskInstance update = new AiTaskInstance();
        update.setInstanceId(instance.getInstanceId());
        update.setStatus(status);
        int stepNo = state.failed ? state.failedStepNo : steps.size();
        update.setCurrentStepNo(stepNo);
        update.setResumeStepNo(stepNo);
        update.setAttemptCount(1);
        update.setFinishedTime(now);
        update.setElapsedMs(System.currentTimeMillis() - begin);
        update.setSummary(summary);
        String errorMessage = state.failed ? state.failMessage : null;
        update.setErrorMessage(trim(errorMessage));
        update.setWaitingReason(state.waitingReason == null ? null : trim(state.waitingReason, 512));
        update.setVersion(instance.getVersion());
        update.setUpdatedTime(now);
        instanceMapper.updateById(update);
        return update;
    }

    /** 成功/失败计数按实例终态维护。 */
    private void updateDefinitionCounters(AiTaskDefinition definition, String status, LocalDateTime now) {
        int successDelta = INSTANCE_SUCCEEDED.equals(status) ? 1 : 0;
        int failDelta = 1 - successDelta;
        AiTaskDefinition definitionUpdate = new AiTaskDefinition();
        definitionUpdate.setDefinitionId(definition.getDefinitionId());
        definitionUpdate.setLastRunTime(now);
        definitionUpdate.setSuccessCount(countOrZero(definition.getSuccessCount()) + successDelta);
        definitionUpdate.setFailCount(countOrZero(definition.getFailCount()) + failDelta);
        definitionUpdate.setVersion(definition.getVersion());
        definitionUpdate.setUpdatedTime(now);
        definitionMapper.updateById(definitionUpdate);
    }

    /** 计数缺失时按 0 处理。 */
    private static int countOrZero(Integer count) {
        return count == null ? 0 : count;
    }

    /** 执行完成后返回给调用方的摘要。 */
    private Map<String, Object> buildRunResult(AiTaskInstance instance,
                                               List<StepDef> steps,
                                               AiTaskInstance update,
                                               String status,
                                               String summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instanceId", instance.getInstanceId());
        data.put("instanceNo", instance.getInstanceNo());
        data.put("status", status);
        data.put("summary", summary);
        data.put("currentStepNo", update.getCurrentStepNo());
        data.put("resumeStepNo", update.getResumeStepNo());
        data.put("totalSteps", steps.size());
        data.put("elapsedMs", update.getElapsedMs());
        return data;
    }

    private StepOutcome runStep(StepDef step, AiTaskInstance instance, String aggregateType, Long aggregateId,
                                Long operatorId) {
        String capability = step.capability() == null ? "" : step.capability();

        return switch (capability) {
            case CAP_INBOUND_LOOKUP -> lookupInbound(aggregateId);
            case CAP_WORK_ORDER_LOOKUP -> lookupWorkOrder(aggregateId);
            case CAP_CONFIRM_INBOUND -> confirmInbound(step, instance, aggregateId, operatorId);
            case CAP_SUBMIT_INSPECTION -> submitInspection(step, instance, aggregateId, operatorId);
            case CAP_AUDIT_WORK_REPORT -> auditWorkReport(step, instance, aggregateId, operatorId);
            case CAP_NOTIFY_WAREHOUSE -> notifyWarehouse(instance, step, aggregateType, aggregateId);
            case CAP_NOTIFY_IQC -> notifyIqc(instance, step, aggregateType, aggregateId);
            case CAP_RELEASE_PLAN -> releasePlan(step, instance, aggregateId, operatorId);
            case CAP_RELEASE_WORK_ORDER -> releaseWorkOrder(step, instance, aggregateId, operatorId);
            case CAP_READINESS_RECHECK -> recheckReadiness(step, aggregateId);
            default -> throw new IllegalStateException("未支持的步骤能力：" + capability);
        };
    }

    /** 读取入库单当前状态。 */
    private StepOutcome lookupInbound(Long aggregateId) {
        Long inboundId = requireAggregateId(aggregateId, "入库单");
        WmsInboundOrder order = inboundOrderMapper.selectById(inboundId);
        if (order == null || Integer.valueOf(1).equals(order.getDeletedFlag())) {
            throw new IllegalStateException("入库单不存在或已删除：" + inboundId);
        }
        return new StepOutcome(order.getInboundNo(), order.getInboundId(),
                "入库单 " + order.getInboundNo() + " 当前状态 " + order.getAuditStatus());
    }

    /** 读取生产工单。 */
    private StepOutcome lookupWorkOrder(Long aggregateId) {
        Long workOrderId = requireAggregateId(aggregateId, "生产工单");
        if (workOrderService == null) {
            throw new IllegalStateException("工单服务不可用，无法读取工单：" + workOrderId);
        }
        MfgWorkOrder order = workOrderService.getWorkOrderById(workOrderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeletedFlag())) {
            throw new IllegalStateException("生产工单不存在或已删除：" + workOrderId);
        }
        String no = order.getWorkOrderNo() == null ? "#" + workOrderId : order.getWorkOrderNo();
        // 以前这里只拼一句「已读取」，用户看到的其实是空话；现在把库里真实状态带上。
        return new StepOutcome(no, workOrderId,
                "工单 " + no + " 当前状态 " + order.getStatus()
                        + "，计划 " + plain(order.getPlannedQuantity())
                        + "，已完成 " + plain(order.getCompletedQuantity()));
    }

    /** 数量去掉多余的尾零，避免页面上出现 12.000。 */
    private String plain(java.math.BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** 确认入库。单据 ID 优先取事件聚合根，其次取步骤里写死的 bizId。 */
    private StepOutcome confirmInbound(StepDef step, AiTaskInstance instance, Long aggregateId, Long operatorId) {
        Long inboundId = requireAggregateId(aggregateId != null ? aggregateId : step.bizIdHint(), "入库单");
        WmsInboundOrder order = inboundOrderMapper.selectById(inboundId);
        boolean ok = inboundOrderService.confirmInboundOrder(inboundId, operatorId,
                "AI 多步任务 " + instance.getInstanceNo() + " 自动确认入库");
        if (!ok) {
            throw new IllegalStateException("入库单确认未生效：" + inboundId);
        }
        String no = order == null ? "#" + inboundId : order.getInboundNo();
        // 单据状态变了不等于库存真的加了：回查确认后的单据状态、明细条数与入库流水条数。
        String problem = inboundOutcomeProblem(inboundId);
        if (problem != null) {
            throw new IllegalStateException("入库单 " + no + " 已确认，但后置复核未通过：" + problem);
        }
        return new StepOutcome(no, inboundId, "入库单 " + no + " 已确认，入库流水与明细条数一致");
    }

    /**
     * 确认入库后回查业务结果，返回问题描述；核对通过返回 null。
     *
     * <p>只断言可验证的事实：单据已是已确认、确认时间已写、每条有效明细都有对应的入库流水。
     * 不拿「接口返回 true」当结果，也不去猜库存余额应该涨到多少（那需要重新做一遍期初期末计算，
     * 容易和并发写入打架），流水与明细一一对应已经能证明扣账动作真的落库了。</p>
     */
    private String inboundOutcomeProblem(Long inboundId) {
        String problem = null;
        if (inboundOrderMapper == null || stockMovementMapper == null || inboundDetailMapper == null) {
            problem = "缺少入库单、明细或库存流水查询依赖，无法核对入库结果";
        } else {
            WmsInboundOrder confirmed = inboundOrderMapper.selectById(inboundId);
            long detailCount = confirmed == null ? 0 : countInboundDetails(inboundId);
            long movementCount = detailCount > 0 ? countInboundMovements(inboundId) : 0;
            if (confirmed == null) {
                problem = "确认后读不到入库单";
            } else if (!"APPROVED".equals(confirmed.getAuditStatus())) {
                problem = "单据状态仍是 " + confirmed.getAuditStatus() + "，不是已确认";
            } else if (confirmed.getInboundTime() == null) {
                problem = "确认时间没有写入";
            } else if (detailCount <= 0) {
                problem = "确认后没有有效明细";
            } else if (movementCount < detailCount) {
                problem = "明细 " + detailCount + " 条，但只写入 " + movementCount + " 条入库流水";
            }
        }
        return problem;
    }

    /** 入库单的有效明细条数。 */
    private long countInboundDetails(Long inboundId) {
        return inboundDetailMapper.selectCount(new LambdaQueryWrapper<WmsInboundDetail>()
                .eq(WmsInboundDetail::getInboundId, inboundId)
                .eq(WmsInboundDetail::getDeletedFlag, 0));
    }

    /** 入库单已写入的入库流水条数。 */
    private long countInboundMovements(Long inboundId) {
        return stockMovementMapper.selectCount(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getSourceOrderType, "INBOUND_ORDER")
                .eq(WmsStockMovement::getSourceOrderId, inboundId)
                .eq(WmsStockMovement::getMovementType, "INBOUND")
                .eq(WmsStockMovement::getDirection, "IN")
                .eq(WmsStockMovement::getDeletedFlag, 0));
    }

    /** 提交检验结果。 */
    private StepOutcome submitInspection(StepDef step, AiTaskInstance instance, Long aggregateId, Long operatorId) {
        Long inspectionId = aggregateId == null ? step.bizIdHint() : aggregateId;
        if (inspectionId == null) {
            throw new IllegalStateException("缺少检验单ID，无法提交检验结果");
        }
        // 不传任何逐件结果：需要逐件全检的方案会被领域服务拒绝并要求补齐，
        // 不允许由自动任务凭空生成「合格」台账。
        boolean ok = submitInspectionAutoPieces(inspectionId, instance, operatorId);
        if (!ok) {
            throw new IllegalStateException("检验单提交未生效：" + inspectionId);
        }
        return new StepOutcome("#" + inspectionId, inspectionId, "检验单 " + inspectionId + " 已提交");
    }

    /** 调领域服务自动提交；要求逐件填实测值的转成「等待补充」。 */
    private boolean submitInspectionAutoPieces(Long inspectionId, AiTaskInstance instance, Long operatorId) {
        try {
            return inspectionOrderService.submitInspectionAutoPieces(inspectionId, null,
                    "AI 多步任务 " + instance.getInstanceNo() + " 自动提交", operatorId, null, null, null, null);
        } catch (BusinessException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (message.contains("逐件全检") || message.contains("请补齐")) {
                // 方案要求人逐件填实测值，自动任务补不出来，转成「等待补充」让质检员接手
                throw new AiTaskWaitingInputException(message);
            }
            throw ex;
        }
    }

    /** 审核报工单。 */
    private StepOutcome auditWorkReport(StepDef step, AiTaskInstance instance, Long aggregateId, Long operatorId) {
        Long reportId = aggregateId == null ? step.bizIdHint() : aggregateId;
        if (reportId == null) {
            throw new IllegalStateException("缺少报工单ID，无法审核");
        }
        boolean ok = workReportService.auditWorkReport(reportId, "APPROVED",
                "AI 多步任务 " + instance.getInstanceNo() + " 自动审核通过", operatorId);
        if (!ok) {
            throw new IllegalStateException("报工审核未生效：" + reportId);
        }
        return new StepOutcome("#" + reportId, reportId, "报工单 " + reportId + " 已审核通过");
    }

    /** 通知仓库负责人。 */
    private StepOutcome notifyWarehouse(AiTaskInstance instance, StepDef step,
                                        String aggregateType, Long aggregateId) {
        Long employeeId = notificationSupport.departmentManager(WAREHOUSE_DEPARTMENT);
        NotificationOutcome outcome = publish(instance, step, "AI_TASK_WAREHOUSE_PENDING",
                "WMS", "INFO", "自动化任务待处理",
                "任务 " + instance.getDefinitionName() + " 已处理 " + describe(instance, aggregateType, aggregateId),
                aggregateType, aggregateId, instance.getTriggerSource(), INBOUND_ROUTE,
                employeeId, "wms:inbound-order:view");
        return new StepOutcome(outcome.bizNo(), outcome.employeeId(),
                outcome.delivered() ? "已通知仓库负责人" : "仓库负责人未配置，通知已入队待补发");
    }

    /** 通知质量负责人。 */
    private StepOutcome notifyIqc(AiTaskInstance instance, StepDef step,
                                  String aggregateType, Long aggregateId) {
        Long employeeId = notificationSupport.departmentManager(QUALITY_DEPARTMENT);
        NotificationOutcome outcome = publish(instance, step, "AI_TASK_IQC_PENDING",
                "QMS", "WARNING", "来料检验待办",
                "任务 " + instance.getDefinitionName() + " 已登记 " + describe(instance, aggregateType, aggregateId),
                aggregateType, aggregateId, instance.getTriggerSource(), INSPECTION_ROUTE,
                employeeId, "qms:inspection-order:view");
        return new StepOutcome(outcome.bizNo(), outcome.employeeId(),
                outcome.delivered() ? "已通知质量负责人" : "质量负责人未配置，通知已入队待补发");
    }

    /** 下达来源生产计划。 */
    private StepOutcome releasePlan(StepDef step, AiTaskInstance instance, Long aggregateId, Long operatorId) {
        Long planId = step.bizIdHint() != null ? step.bizIdHint() : aggregateId;
        if (planId == null) {
            throw new IllegalStateException("缺少生产计划ID，无法下达计划");
        }
        if (productionPlanService == null) {
            throw new IllegalStateException("计划领域服务不可用，无法下达生产计划");
        }
        MfgProductionPlan current = productionPlanService.getPlanById(planId);
        if (current != null && Integer.valueOf(0).equals(current.getDeletedFlag())
                && List.of("RELEASED", "RUNNING", "FINISHED").contains(current.getStatus())) {
            return new StepOutcome(current.getPlanNo(), planId, "生产计划已生效，无需重复下达");
        }
        MfgProductionPlan released = productionPlanService.releasePlan(planId, operatorId,
                "AI 多步任务 " + instance.getInstanceNo() + " 自动下达来源计划");
        if (released == null) {
            throw new IllegalStateException("生产计划下达未生效：" + planId);
        }
        return new StepOutcome(released.getPlanNo(), released.getPlanId(),
                "生产计划 " + released.getPlanNo() + " 已生效");
    }

    /** 下达组件工单。 */
    private StepOutcome releaseWorkOrder(StepDef step, AiTaskInstance instance, Long aggregateId, Long operatorId) {
        Long workOrderId = step.bizIdHint() != null ? step.bizIdHint() : aggregateId;
        if (workOrderId == null) {
            throw new IllegalStateException("缺少生产工单ID，无法下达工单");
        }
        if (workOrderService == null) {
            throw new IllegalStateException("工单领域服务不可用，无法下达生产工单");
        }
        MfgWorkOrder current = workOrderService.getWorkOrderById(workOrderId);
        if (current != null && Integer.valueOf(0).equals(current.getDeletedFlag())
                && List.of("RELEASED", "RUNNING", "FINISHED").contains(current.getStatus())) {
            return new StepOutcome(current.getWorkOrderNo(), workOrderId, "生产工单已下达，无需重复下达");
        }
        MfgWorkOrder released = workOrderService.releaseWorkOrder(workOrderId, operatorId,
                "AI 多步任务 " + instance.getInstanceNo() + " 自动下达工单");
        if (released == null) {
            throw new IllegalStateException("生产工单下达未生效：" + workOrderId);
        }
        return new StepOutcome(released.getWorkOrderNo(), released.getWorkOrderId(),
                "生产工单 " + released.getWorkOrderNo() + " 已下达");
    }

    /** 复查开工条件。 */
    private StepOutcome recheckReadiness(StepDef step, Long aggregateId) {
        String workOrderNo = step.targetNo();
        if (!StringUtils.hasText(workOrderNo)) {
            throw new IllegalStateException("缺少工单号，无法复查开工条件");
        }
        if (dataQueryService == null) {
            throw new IllegalStateException("查询服务不可用，无法复查开工条件");
        }
        AiQueryResult readiness = dataQueryService.queryWorkOrderReadiness(workOrderNo);
        if (readiness == null || readiness.getRows().isEmpty()) {
            throw new IllegalStateException("未找到工单 " + workOrderNo + "，无法复查开工条件");
        }
        long blocked = readiness.getRows().stream()
                .filter(row -> !"通过".equals(String.valueOf(row.get("passed"))))
                .count();
        if (blocked > 0) {
            // 核查还有没通过的项，这是要人去补的事，不能当成失败也不能当成成功
            throw new AiTaskWaitingInputException("复查仍有 " + blocked + " 项未通过：" + readiness.getSummary());
        }
        return new StepOutcome(workOrderNo, aggregateId,
                "复查通过，工单 " + workOrderNo + " 已具备开工条件");
    }

    private NotificationOutcome publish(AiTaskInstance instance, StepDef step, String eventType,
                                        String category, String severity, String title, String summary,
                                        String aggregateType, Long aggregateId, String bizNo, String route,
                                        Long employeeId, String permission) {
        String eventKey = "AI_TASK:" + instance.getInstanceId() + ":" + step.stepNo() + ":" + eventType;
        List<com.neuedu.ems.common.NotificationEventPayload.RecipientTarget> targets =
                employeeId == null ? Collections.emptyList()
                        : Collections.singletonList(notificationSupport.target(employeeId,
                        "AUTOMATION_OWNER", permission));
        notificationSupport.publish(eventKey, aggregateType == null ? "AI_TASK" : aggregateType, aggregateId,
                eventType, category, severity, title, summary, bizNo, route, null, false, targets);
        return new NotificationOutcome(bizNo, employeeId, employeeId != null);
    }

    private String describe(AiTaskInstance instance, String aggregateType, Long aggregateId) {
        if (aggregateType == null || aggregateId == null) {
            return "触发源 " + instance.getTriggerSource();
        }
        return aggregateType + " #" + aggregateId;
    }

    private Long requireAggregateId(Long aggregateId, String label) {
        if (aggregateId == null) {
            throw new IllegalStateException("缺少" + label + "ID，无法执行该步骤");
        }
        return aggregateId;
    }

    private boolean hourlyLimitReached(AiTaskDefinition definition) {
        int limit = definition.getMaxInstancesPerHour() == null ? 50 : definition.getMaxInstancesPerHour();
        long count = instanceMapper.selectCount(new LambdaQueryWrapper<AiTaskInstance>()
                .eq(AiTaskInstance::getDeletedFlag, 0)
                .eq(AiTaskInstance::getDefinitionId, definition.getDefinitionId())
                .ge(AiTaskInstance::getStartedTime, LocalDateTime.now().minusHours(1)));
        return count >= limit;
    }

    /**
     * 每日步骤调用量上限：一条定义一天最多真的调多少次领域服务。
     *
     * <p>每小时实例数只挡住短时爆发，配错一条规则连跑一天照样能把库存服务刷满。
     * 这里按步骤计数，达到上限当天不再执行，第二天自然恢复。</p>
     */
    private boolean dailyStepLimitReached(AiTaskDefinition definition) {
        int limit = definition.getMaxStepCallsPerDay() == null ? 500 : definition.getMaxStepCallsPerDay();
        LocalDateTime dayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        long used = stepMapper.countStepsSince(definition.getDefinitionId(), dayStart);
        if (used >= limit) {
            log.warn("任务 {} 今日步骤调用已达上限 {}/{}，暂停到明天", definition.getDefinitionNo(), used, limit);
            return true;
        }
        return false;
    }

    private void insertSkippedInstance(AiTaskDefinition definition, String instanceKey, String triggerSource,
                                       String message, String aggregateType, Long aggregateId, String bizNo) {
        LocalDateTime now = LocalDateTime.now();
        AiTaskInstance instance = new AiTaskInstance();
        instance.setInstanceNo(nextInstanceNo(now));
        instance.setInstanceKey(instanceKey);
        instance.setDefinitionId(definition.getDefinitionId());
        instance.setDefinitionNo(definition.getDefinitionNo());
        instance.setDefinitionName(definition.getDefinitionName());
        instance.setTriggerType(definition.getTriggerType());
        instance.setTriggerSource(trim(triggerSource, 240));
        instance.setStatus(INSTANCE_SKIPPED);
        instance.setCurrentStepNo(0);
        instance.setResumeStepNo(1);
        instance.setTotalSteps(0);
        instance.setAttemptCount(0);
        instance.setStartedTime(now);
        instance.setFinishedTime(now);
        instance.setElapsedMs(0L);
        instance.setSummary(trim(message));
        instance.setCreatedTime(now);
        instance.setUpdatedTime(now);
        instance.setDeletedFlag(0);
        instance.setVersion(0);
        instanceMapper.insert(instance);
        AiTaskStep step = new AiTaskStep();
        step.setInstanceId(instance.getInstanceId());
        step.setStepNo(1);
        step.setStepLabel("循环防护");
        step.setCapability("LOOP_GUARD");
        step.setTargetType(aggregateType);
        step.setTargetNo(bizNo);
        step.setStatus(STEP_SKIPPED);
        step.setAttemptCount(0);
        step.setMessage(trim(message, 1000));
        step.setElapsedMs(0L);
        step.setCreatedTime(now);
        step.setUpdatedTime(now);
        step.setDeletedFlag(0);
        stepMapper.insert(step);
    }

    /* ==================== 步骤解析 ==================== */

    private List<StepDef> parseSteps(String stepsJson) {
        if (!StringUtils.hasText(stepsJson)) {
            return Collections.emptyList();
        }
        try {
            return readStepDefs(stepsJson);
        } catch (Exception ex) {
            throw error("任务步骤配置无法解析，请检查 JSON 格式：" + ex.getMessage());
        }
    }

    /** 反序列化步骤 JSON 并按步骤号排序。 */
    private List<StepDef> readStepDefs(String stepsJson) throws Exception {
        List<Map<String, Object>> raw = objectMapper.readValue(stepsJson,
                new TypeReference<List<Map<String, Object>>>() {
                });
        List<StepDef> steps = new ArrayList<>();
        int auto = 0;
        for (Map<String, Object> item : raw) {
            if (item == null) {
                continue;
            }
            auto++;
            StepDef step = toStepDef(item, auto);
            if (step != null) {
                steps.add(step);
            }
        }
        steps.sort((a, b) -> Integer.compare(a.stepNo(), b.stepNo()));
        return steps;
    }

    /** 一条 JSON 步骤转成 StepDef；缺少 capability 的步骤直接跳过。 */
    private StepDef toStepDef(Map<String, Object> item, int autoStepNo) {
        String capability = text(item.get("capability"));
        if (!StringUtils.hasText(capability)) {
            return null;
        }
        Integer stepNo = intValue(item.get("stepNo"), autoStepNo);
        String label = text(item.get("label"));
        if (!StringUtils.hasText(label)) {
            label = capability;
        }
        String kind = text(item.get("kind"));
        if (!StringUtils.hasText(kind)) {
            kind = isCommandCapability(capability) ? "command" : "query";
        }
        String targetType = text(item.get("targetType"));
        String targetNo = text(item.get("targetNo"));
        Long bizIdHint = longValue(item.get("bizId"));
        return new StepDef(stepNo, label, kind, capability, targetType, targetNo, bizIdHint);
    }

    private String writeSteps(List<StepDef> steps) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (StepDef step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepNo", step.stepNo());
            item.put("label", step.label());
            item.put("kind", step.kind());
            item.put("capability", step.capability());
            if (StringUtils.hasText(step.targetType())) {
                item.put("targetType", step.targetType());
            }
            if (StringUtils.hasText(step.targetNo())) {
                item.put("targetNo", step.targetNo());
            }
            if (step.bizIdHint() != null) {
                item.put("bizId", step.bizIdHint());
            }
            raw.add(item);
        }
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception ex) {
            throw error("任务步骤无法序列化：" + ex.getMessage());
        }
    }

    private boolean isSupported(String capability) {
        return List.of(CAP_INBOUND_LOOKUP, CAP_WORK_ORDER_LOOKUP, CAP_CONFIRM_INBOUND,
                CAP_SUBMIT_INSPECTION, CAP_AUDIT_WORK_REPORT, CAP_NOTIFY_WAREHOUSE,
                CAP_NOTIFY_IQC, CAP_RELEASE_PLAN, CAP_RELEASE_WORK_ORDER,
                CAP_READINESS_RECHECK).contains(capability);
    }

    private boolean isCommandCapability(String capability) {
        return List.of(CAP_CONFIRM_INBOUND, CAP_SUBMIT_INSPECTION, CAP_AUDIT_WORK_REPORT,
                CAP_RELEASE_PLAN, CAP_RELEASE_WORK_ORDER).contains(capability);
    }

    private String normalizeTrigger(String triggerType) {
        String value = triggerType == null ? "" : triggerType.trim().toUpperCase(Locale.ROOT);
        if (!List.of(TRIGGER_EVENT, TRIGGER_SCHEDULE, TRIGGER_MANUAL).contains(value)) {
            return TRIGGER_MANUAL;
        }
        return value;
    }

    private int page(Integer current) {
        return current == null || current < 1 ? 1 : current;
    }

    private int pageSize(Integer size) {
        return size == null || size < 1 ? 10 : Math.min(size, 200);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? null : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String trim(String value) {
        return trim(value, 480);
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private BusinessException error(String message) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    /* ==================== 内部类型 ==================== */

    private record StepDef(int stepNo, String label, String kind, String capability,
                           String targetType, String targetNo, Long bizIdHint) {
    }

    private record StepOutcome(String bizNo, Long bizId, String message) {
    }

    private record NotificationOutcome(String bizNo, Long employeeId, boolean delivered) {
    }
}
