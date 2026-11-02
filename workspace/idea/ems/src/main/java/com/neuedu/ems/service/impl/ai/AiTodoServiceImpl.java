package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.ai.AiAutomationRun;
import com.neuedu.ems.entity.eam.EamEquipment;
import com.neuedu.ems.entity.mfg.MfgException;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.mapper.ai.AiAutomationRunMapper;
import com.neuedu.ems.mapper.eam.EamEquipmentMapper;
import com.neuedu.ems.mapper.mfg.MfgExceptionMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.service.ai.AiTodoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 我的待办聚合实现。
 *
 * <p>九类待办全部按业务表真实状态判定，不读通知已读标记。每条待办附带的动作先按
 * 「单据状态是否允许」由查询条件本身保证，再按「账号是否具备动作权限」过滤，
 * 未实现的动作根本不进候选，因此列表里不会出现点了就报错的按钮。</p>
 */
@Service
public class AiTodoServiceImpl implements AiTodoService {

    /* ---------- 待办类型 ---------- */
    private static final String TYPE_PENDING_MATERIAL_ISSUE = "PENDING_MATERIAL_ISSUE";
    private static final String TYPE_PENDING_INBOUND = "PENDING_INBOUND";
    private static final String TYPE_PENDING_WORK_REPORT = "PENDING_WORK_REPORT";
    private static final String TYPE_PENDING_INSPECTION = "PENDING_INSPECTION";
    private static final String TYPE_MATERIAL_SHORTAGE = "MATERIAL_SHORTAGE";
    private static final String TYPE_WORK_ORDER_DELAY = "WORK_ORDER_DELAY";
    private static final String TYPE_EQUIPMENT_FAULT = "EQUIPMENT_FAULT";
    private static final String TYPE_AUTOMATION_FAILED = "AUTOMATION_FAILED";
    private static final String TYPE_EXCEPTION_PENDING = "EXCEPTION_PENDING";

    /* ---------- 严重程度 ---------- */
    private static final String SEVERITY_HIGH = "HIGH";
    private static final String SEVERITY_MEDIUM = "MEDIUM";
    private static final String SEVERITY_LOW = "LOW";

    /* ---------- 动作编码（与 AiCommandServiceImpl 的 8 个写动作保持一致） ---------- */
    private static final String ACTION_CONFIRM_OUTBOUND = AiCommandServiceImpl.ACTION_CONFIRM_OUTBOUND;
    private static final String ACTION_CONFIRM_INBOUND = AiCommandServiceImpl.ACTION_CONFIRM_INBOUND;
    private static final String ACTION_AUDIT_WORK_REPORT = AiCommandServiceImpl.ACTION_AUDIT_WORK_REPORT;
    private static final String ACTION_SUBMIT_INSPECTION = AiCommandServiceImpl.ACTION_SUBMIT_INSPECTION;
    private static final String ACTION_GENERATE_MATERIAL_ISSUE =
            AiCommandServiceImpl.ACTION_GENERATE_MATERIAL_ISSUE;
    private static final String ACTION_START_WORK_ORDER = AiCommandServiceImpl.ACTION_START_WORK_ORDER;
    private static final String ACTION_RELEASE_WORK_ORDER = AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER;

    /* ---------- 跳转权限码（没有写动作、但能直接看单据的待办用） ---------- */
    private static final String PERM_EXCEPTION_VIEW = "mfg:exception:view";
    private static final String PERM_EQUIPMENT_VIEW = "eam:equipment:view";
    private static final String PERM_ASSISTANT_USE = "ai:assistant:use";

    /* ---------- 跳转目标路由 ---------- */
    private static final String ROUTE_EXCEPTIONS = "/mfg/exceptions";
    private static final String ROUTE_EQUIPMENT = "/eam/equipment";
    private static final String ROUTE_AUTOMATION = "/ai/automation";

    /* ---------- 动作权限码 ---------- */
    private static final String PERM_OUTBOUND_CONFIRM = "wms:outbound-order:confirm";
    private static final String PERM_INBOUND_CONFIRM = "wms:inbound-order:confirm";
    private static final String PERM_WORK_REPORT_AUDIT = "mfg:work-report:audit";
    private static final String PERM_INSPECTION_SUBMIT = "qms:inspection-order:submit";
    private static final String PERM_MATERIAL_ISSUE = "mfg:work-order:material-issue";
    private static final String PERM_WORK_ORDER_START = "mfg:work-order:start";

    /** 每条待办最多返回几条记录，避免首页角标接口被大表拖慢。 */
    private static final int MAX_ITEMS = 200;
    /** 自动化执行失败只看最近 24 小时。 */
    private static final int AUTOMATION_WINDOW_HOURS = 24;
    /** 未完工的工单状态集合，用于判定延期风险。 */
    private static final Set<String> UNFINISHED_STATUSES = Set.of(
            BusinessStatus.Production.WAIT_START, BusinessStatus.Production.RUNNING,
            BusinessStatus.Production.PAUSED, BusinessStatus.Document.RELEASED);
    /** 自动化异常闭环单号前缀。 */
    private static final String AUTO_EXCEPTION_PREFIX = "EXC-AUTO-";
    /**
     * 预警中心自动登记的异常单号前缀。
     *
     * <p>预警中心（AiAlertServiceImpl.raiseException）把未关闭的预警登记成 {@code EXC-ALERT-xxx}
     * 形态的异常单，与自动化的 {@code EXC-AUTO-xxx} 同属"系统自己建的单据"。
     * 两类漏掉任何一类，都会出现"单据已经进了异常台账、待办里却看不到"的断链，
     * 所以这里统一按前缀并集取。</p>
     */
    private static final String ALERT_EXCEPTION_PREFIX = "EXC-ALERT-";

    @Autowired(required = false)
    private WmsOutboundOrderMapper outboundOrderMapper;
    @Autowired(required = false)
    private WmsInboundOrderMapper inboundOrderMapper;
    @Autowired(required = false)
    private MfgWorkReportMapper workReportMapper;
    @Autowired(required = false)
    private QmsInspectionOrderMapper inspectionOrderMapper;
    @Autowired(required = false)
    private MfgWorkOrderMapper workOrderMapper;
    @Autowired(required = false)
    private MfgWorkOrderMaterialMapper workOrderMaterialMapper;
    @Autowired(required = false)
    private MfgProductionPlanMapper productionPlanMapper;
    @Autowired(required = false)
    private EamEquipmentMapper equipmentMapper;
    @Autowired(required = false)
    private AiAutomationRunMapper automationRunMapper;
    @Autowired(required = false)
    private MfgExceptionMapper exceptionMapper;

    @Override
    public Map<String, Object> myTodos(AuthorizationContext authorization) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(collectPendingMaterialIssues(authorization));
        items.addAll(collectPendingInbounds(authorization));
        items.addAll(collectPendingWorkReports(authorization));
        items.addAll(collectPendingInspections(authorization));
        items.addAll(collectMaterialShortages(authorization));
        items.addAll(collectWorkOrderDelays(authorization));
        items.addAll(collectEquipmentFaults(authorization));
        items.addAll(collectAutomationFailures(authorization));
        items.addAll(collectPendingExceptions(authorization));

        items.sort(Comparator.comparingInt((Map<String, Object> item) -> severityRank(text(item.get("severity"))))
                .thenComparing(Comparator.comparingLong(
                        (Map<String, Object> item) -> longValue(item.get("waitingMinutes"))).reversed()));

        // 明细最多返回 MAX_ITEMS 条，但条数统计必须按全量算，否则超过上限时会少报待办。
        List<Map<String, Object>> limited = items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", limited);
        data.put("counts", countByType(items));
        data.put("total", items.size());
        data.put("truncated", items.size() > limited.size());
        data.put("generatedAt", LocalDateTime.now());
        return data;
    }

    /* ==================== 九类待办采集 ==================== */

    /** 待确认领料单：已提交待审核的领料出库单。 */
    private List<Map<String, Object>> collectPendingMaterialIssues(AuthorizationContext authorization) {
        if (outboundOrderMapper == null || !hasPermission(authorization, PERM_OUTBOUND_CONFIRM)) {
            return List.of();
        }
        List<WmsOutboundOrder> orders = outboundOrderMapper.selectList(new QueryWrapper<WmsOutboundOrder>()
                .eq("audit_status", BusinessStatus.Document.SUBMITTED)
                .eq("outbound_type", "MATERIAL_ISSUE")
                .eq("deleted_flag", 0)
                .orderByAsc("created_time"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (WmsOutboundOrder order : orders) {
            items.add(todoItem(TYPE_PENDING_MATERIAL_ISSUE, "待确认领料单", SEVERITY_MEDIUM,
                    "领料单 " + text(order.getOutboundNo()) + " 已提交，等待确认出库",
                    order.getOutboundNo(), "OUTBOUND_ORDER", order.getOutboundId(),
                    "工单ID " + text(order.getWorkOrderId()) + " 的领料单待确认出库",
                    order.getCreatedTime(),
                    commandAction(ACTION_CONFIRM_OUTBOUND, "确认出库", PERM_OUTBOUND_CONFIRM, authorization)));
        }
        return items;
    }

    /** 待确认入库单：已提交待审核的入库单。 */
    private List<Map<String, Object>> collectPendingInbounds(AuthorizationContext authorization) {
        if (inboundOrderMapper == null || !hasPermission(authorization, PERM_INBOUND_CONFIRM)) {
            return List.of();
        }
        List<WmsInboundOrder> orders = inboundOrderMapper.selectList(new QueryWrapper<WmsInboundOrder>()
                .eq("audit_status", BusinessStatus.Document.SUBMITTED)
                .eq("deleted_flag", 0)
                .orderByAsc("created_time"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (WmsInboundOrder order : orders) {
            items.add(todoItem(TYPE_PENDING_INBOUND, "待确认入库单", SEVERITY_MEDIUM,
                    "入库单 " + text(order.getInboundNo()) + " 已提交，等待确认入库",
                    order.getInboundNo(), "INBOUND_ORDER", order.getInboundId(),
                    "入库类型 " + text(order.getInboundType()) + "，待确认后增加库存",
                    order.getCreatedTime(),
                    commandAction(ACTION_CONFIRM_INBOUND, "确认入库", PERM_INBOUND_CONFIRM, authorization)));
        }
        return items;
    }

    /** 待审核报工：已提交待审核的报工单。 */
    private List<Map<String, Object>> collectPendingWorkReports(AuthorizationContext authorization) {
        if (workReportMapper == null || !hasPermission(authorization, PERM_WORK_REPORT_AUDIT)) {
            return List.of();
        }
        List<MfgWorkReport> reports = workReportMapper.selectList(new QueryWrapper<MfgWorkReport>()
                .eq("audit_status", BusinessStatus.Document.SUBMITTED)
                .eq("deleted_flag", 0)
                .orderByAsc("created_time"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (MfgWorkReport report : reports) {
            items.add(todoItem(TYPE_PENDING_WORK_REPORT, "待审核报工", SEVERITY_MEDIUM,
                    "报工单 " + text(report.getReportNo()) + " 已提交，等待审核",
                    report.getReportNo(), "WORK_REPORT", report.getReportId(),
                    "合格 " + plain(report.getQualifiedQuantity()) + "，不良 "
                            + plain(report.getDefectQuantity()) + "，待审核",
                    report.getCreatedTime(),
                    commandAction(ACTION_AUDIT_WORK_REPORT, "审核报工", PERM_WORK_REPORT_AUDIT, authorization)));
        }
        return items;
    }

    /** 待执行检验：草稿状态的检验单。 */
    private List<Map<String, Object>> collectPendingInspections(AuthorizationContext authorization) {
        if (inspectionOrderMapper == null || !hasPermission(authorization, PERM_INSPECTION_SUBMIT)) {
            return List.of();
        }
        List<QmsInspectionOrder> orders = inspectionOrderMapper.selectList(new QueryWrapper<QmsInspectionOrder>()
                .eq("status", BusinessStatus.Document.DRAFT)
                .eq("deleted_flag", 0)
                .orderByAsc("created_time"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (QmsInspectionOrder order : orders) {
            items.add(todoItem(TYPE_PENDING_INSPECTION, "待执行检验", SEVERITY_MEDIUM,
                    "检验单 " + text(order.getInspectionNo()) + " 待录入检验结果",
                    order.getInspectionNo(), "INSPECTION_ORDER", order.getInspectionId(),
                    "检验类型 " + text(order.getInspectionType()) + "，抽样 "
                            + plain(order.getSampleQuantity()),
                    order.getCreatedTime(),
                    commandAction(ACTION_SUBMIT_INSPECTION, "提交检验结果", PERM_INSPECTION_SUBMIT,
                            authorization)));
        }
        return items;
    }

    /** 缺料：已下达/生产中的工单存在未满足的用料需求。 */
    private List<Map<String, Object>> collectMaterialShortages(AuthorizationContext authorization) {
        boolean queryable = workOrderMaterialMapper != null && workOrderMapper != null
                && hasPermission(authorization, PERM_MATERIAL_ISSUE);
        if (!queryable) {
            return List.of();
        }
        List<MfgWorkOrderMaterial> requirements = workOrderMaterialMapper.selectList(
                new QueryWrapper<MfgWorkOrderMaterial>()
                        .apply("shortage_quantity > 0")
                        .eq("deleted_flag", 0)
                        .orderByDesc("shortage_quantity"));
        // 同一个工单缺几种料就是几条记录，逐条推「生成领料单」会让待办列表被同一张工单刷屏，
        // 而且这个动作在库存不够时必然失败。这里按工单合并成一条，并把「先干什么」说清楚。
        Map<Long, List<MfgWorkOrderMaterial>> grouped = groupByWorkOrder(requirements);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<Long, List<MfgWorkOrderMaterial>> entry : grouped.entrySet()) {
            Map<String, Object> item = materialShortageItem(entry.getKey(), entry.getValue(), authorization);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** 按工单号归并缺料明细，保持原始查询顺序。 */
    private Map<Long, List<MfgWorkOrderMaterial>> groupByWorkOrder(List<MfgWorkOrderMaterial> requirements) {
        Map<Long, List<MfgWorkOrderMaterial>> grouped = new LinkedHashMap<>();
        for (MfgWorkOrderMaterial requirement : requirements) {
            if (requirement.getWorkOrderId() == null) {
                continue;
            }
            grouped.computeIfAbsent(requirement.getWorkOrderId(), key -> new ArrayList<>()).add(requirement);
        }
        return grouped;
    }

    /** 单张工单的缺料待办；工单不存在或已不在生产阶段时返回 null。 */
    private Map<String, Object> materialShortageItem(Long workOrderId,
                                                    List<MfgWorkOrderMaterial> gaps,
                                                    AuthorizationContext authorization) {
        MfgWorkOrder order = loadWorkOrder(workOrderId);
        if (order == null || !isReleasedOrRunning(order.getStatus())) {
            return null;
        }
        BigDecimal totalShortage = gaps.stream()
                .map(item -> item.getShortageQuantity() == null ? BigDecimal.ZERO : item.getShortageQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String materials = gaps.stream()
                .map(item -> "物料" + text(item.getMaterialId()))
                .collect(java.util.stream.Collectors.joining("、"));
        List<MfgWorkOrder> pendingComponents = pendingComponentWorkOrders(order);
        MaterialShortageAdvice advice = pendingComponents.isEmpty()
                ? issueMaterialAdvice(materials, totalShortage, authorization)
                : pendingComponentAdvice(materials, totalShortage, pendingComponents, authorization);
        return todoItem(TYPE_MATERIAL_SHORTAGE, "缺料", SEVERITY_HIGH,
                "工单 " + text(order.getWorkOrderNo()) + " 缺料 " + plain(totalShortage),
                order.getWorkOrderNo(), "WORK_ORDER", order.getWorkOrderId(),
                advice.summary(), order.getCreatedTime(), advice.actions());
    }

    /** 缺料待办的建议文案与可执行动作。 */
    private record MaterialShortageAdvice(String summary, List<Map<String, Object>> actions) {
    }

    /** 组件都已下达：缺料就推「生成领料单」。 */
    private MaterialShortageAdvice issueMaterialAdvice(String materials,
                                                       BigDecimal totalShortage,
                                                       AuthorizationContext authorization) {
        return new MaterialShortageAdvice(
                "缺 " + materials + "，合计 " + plain(totalShortage) + "；原料仓存量不足时需先补料",
                commandAction(ACTION_GENERATE_MATERIAL_ISSUE, "生成领料单", PERM_MATERIAL_ISSUE,
                        authorization));
    }

    /** 组件还没下达：原料再够也领不到组件，该推的是下达组件工单。 */
    private MaterialShortageAdvice pendingComponentAdvice(String materials,
                                                          BigDecimal totalShortage,
                                                          List<MfgWorkOrder> pendingComponents,
                                                          AuthorizationContext authorization) {
        String componentNos = pendingComponents.stream()
                .map(MfgWorkOrder::getWorkOrderNo)
                .collect(java.util.stream.Collectors.joining("、"));
        List<Map<String, Object>> actions = pendingComponents.size() == 1
                ? commandAction(ACTION_RELEASE_WORK_ORDER, "下达组件工单",
                        "mfg:work-order:release", authorization)
                : List.of();
        String summary = "缺 " + materials + "，合计 " + plain(totalShortage)
                + "；组件工单 " + componentNos + " 还没下达，先下达并生产组件再领料";
        if (pendingComponents.size() > 1) {
            summary = summary + "（共 " + pendingComponents.size() + " 张，请逐张下达）";
        }
        return new MaterialShortageAdvice(summary, actions);
    }

    /** 该工单下还未下达（仍是草稿）的组件子工单。 */
    private List<MfgWorkOrder> pendingComponentWorkOrders(MfgWorkOrder order) {
        if (workOrderMapper == null || order.getWorkOrderId() == null) {
            return List.of();
        }
        List<MfgWorkOrder> children = workOrderMapper.selectList(new QueryWrapper<MfgWorkOrder>()
                .eq("root_work_order_id", order.getWorkOrderId())
                .ne("work_order_id", order.getWorkOrderId())
                .eq("deleted_flag", 0));
        if (children == null) {
            return List.of();
        }
        return children.stream()
                .filter(child -> BusinessStatus.Document.DRAFT.equals(child.getStatus()))
                .toList();
    }

    /** 工单延期风险：计划结束时间已过但工单仍未完工。 */
    private List<Map<String, Object>> collectWorkOrderDelays(AuthorizationContext authorization) {
        if (workOrderMapper == null || productionPlanMapper == null) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<MfgWorkOrder> orders = workOrderMapper.selectList(new QueryWrapper<MfgWorkOrder>()
                .in("status", UNFINISHED_STATUSES)
                .eq("deleted_flag", 0));
        List<Map<String, Object>> items = new ArrayList<>();
        for (MfgWorkOrder order : orders) {
            MfgProductionPlan plan = loadPlan(order.getPlanId());
            if (plan == null || plan.getPlannedEndTime() == null || !plan.getPlannedEndTime().isBefore(now)) {
                continue;
            }
            items.add(todoItem(TYPE_WORK_ORDER_DELAY, "工单延期风险", SEVERITY_HIGH,
                    "工单 " + text(order.getWorkOrderNo()) + " 已超过计划结束时间仍未完工",
                    order.getWorkOrderNo(), "WORK_ORDER", order.getWorkOrderId(),
                    "计划结束 " + text(plan.getPlannedEndTime()) + "，当前状态 "
                            + text(order.getStatus()) + "，完成 "
                            + plain(order.getCompletedQuantity()) + "/" + plain(order.getPlannedQuantity()),
                    plan.getPlannedEndTime(),
                    commandAction(ACTION_START_WORK_ORDER, "开始生产", PERM_WORK_ORDER_START, authorization)));
        }
        return items;
    }

    /** 设备故障：状态为故障或维修中。 */
    private List<Map<String, Object>> collectEquipmentFaults(AuthorizationContext authorization) {
        if (equipmentMapper == null) {
            return List.of();
        }
        List<EamEquipment> equipments = equipmentMapper.selectList(new QueryWrapper<EamEquipment>()
                .in("status", BusinessStatus.Equipment.FAULT, BusinessStatus.Equipment.REPAIRING)
                .eq("deleted_flag", 0));
        List<Map<String, Object>> items = new ArrayList<>();
        for (EamEquipment equipment : equipments) {
            items.add(todoItem(TYPE_EQUIPMENT_FAULT, "设备故障", SEVERITY_HIGH,
                    "设备 " + text(equipment.getEquipmentName()) + " 处于 "
                            + text(equipment.getStatus()) + " 状态",
                    equipment.getEquipmentCode(), "EQUIPMENT", equipment.getEquipmentId(),
                    "型号 " + text(equipment.getModel()) + "，需要安排维修",
                    equipment.getUpdatedTime(),
                    viewActionWhenAllowed("查看设备", PERM_EQUIPMENT_VIEW, ROUTE_EQUIPMENT,
                            Map.of("focus", text(equipment.getEquipmentId())), authorization)));
        }
        return items;
    }

    /** 自动化执行失败：最近 24 小时内失败的规则执行。 */
    private List<Map<String, Object>> collectAutomationFailures(AuthorizationContext authorization) {
        if (automationRunMapper == null) {
            return List.of();
        }
        LocalDateTime since = LocalDateTime.now().minusHours(AUTOMATION_WINDOW_HOURS);
        List<AiAutomationRun> runs = automationRunMapper.selectList(new QueryWrapper<AiAutomationRun>()
                .eq("status", "FAILED")
                .ge("started_time", since)
                .eq("deleted_flag", 0)
                .orderByDesc("started_time"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiAutomationRun run : runs) {
            items.add(todoItem(TYPE_AUTOMATION_FAILED, "自动化执行失败", SEVERITY_HIGH,
                    "规则「" + text(run.getRuleName()) + "」执行失败",
                    run.getRunNo(), "AUTOMATION_RUN", run.getRunId(),
                    text(run.getErrorMessage()), run.getStartedTime(),
                    viewActionWhenAllowed("查看执行记录", null, ROUTE_AUTOMATION,
                            Map.of("tab", "runs", "runId", text(run.getRunId())), authorization)));
        }
        return items;
    }

    /** 异常闭环待处理：自动化产生的异常单仍未处理。 */
    private List<Map<String, Object>> collectPendingExceptions(AuthorizationContext authorization) {
        if (exceptionMapper == null) {
            return List.of();
        }
        List<MfgException> exceptions = exceptionMapper.selectList(new QueryWrapper<MfgException>()
                .eq("status", BusinessStatus.Document.SUBMITTED)
                // 自动化的 EXC-AUTO-* 与预警中心的 EXC-ALERT-* 都要收，
                // 否则预警中心自动登记的异常单在待办里没有入口，只能靠翻通知去找。
                .and(wrapper -> wrapper.likeRight("exception_no", AUTO_EXCEPTION_PREFIX)
                        .or()
                        .likeRight("exception_no", ALERT_EXCEPTION_PREFIX))
                .eq("deleted_flag", 0)
                .orderByAsc("created_time"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (MfgException exception : exceptions) {
            items.add(todoItem(TYPE_EXCEPTION_PENDING, "异常闭环待处理", SEVERITY_MEDIUM,
                    "异常单 " + text(exception.getExceptionNo()) + " 待处理",
                    exception.getExceptionNo(), "EXCEPTION", exception.getExceptionId(),
                    text(exception.getDescription()), exception.getCreatedTime(),
                    viewActionWhenAllowed("去处理异常", PERM_EXCEPTION_VIEW, ROUTE_EXCEPTIONS,
                            Map.of("focus", text(exception.getExceptionId())), authorization)));
        }
        return items;
    }

    /* ==================== 组装与工具 ==================== */

    /**
     * 组装一条待办。
     *
     * @param type      待办类型编码
     * @param typeLabel 待办类型中文名
     * @param severity  严重程度
     * @param title     一句话标题
     * @param bizNo     业务单号
     * @param bizType   业务类型
     * @param bizId     业务主键
     * @param summary   补充说明
     * @param since     计时起点，为空则等待时长记 0
     * @param actions   已按权限过滤的动作列表
     */
    private Map<String, Object> todoItem(String type, String typeLabel, String severity, String title,
                                         String bizNo, String bizType, Long bizId, String summary,
                                         LocalDateTime since, List<Map<String, Object>> actions) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("typeLabel", typeLabel);
        item.put("severity", severity);
        item.put("title", title);
        item.put("bizNo", bizNo);
        item.put("bizType", bizType);
        item.put("bizId", bizId);
        item.put("summary", summary);
        item.put("waitingMinutes", waitingMinutes(since));
        item.put("actions", actions);
        return item;
    }

    /**
     * 构造一个办理类动作，账号没有对应权限时返回空列表。
     *
     * @param action        动作编码
     * @param label         按钮文案
     * @param permission    动作所需权限码
     * @param authorization 当前登录上下文
     */
    private List<Map<String, Object>> commandAction(String action, String label, String permission,
                                                    AuthorizationContext authorization) {
        if (!hasPermission(authorization, permission)) {
            return List.of();
        }
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("capability", action);
        Map<String, Object> actionView = new LinkedHashMap<>();
        actionView.put("code", action);
        actionView.put("label", label);
        actionView.put("capability", action);
        actionView.put("params", param);
        return List.of(actionView);
    }

    /** 账号是否具备动作权限；上下文为空视为无权限。 */
    private boolean hasPermission(AuthorizationContext authorization, String permission) {
        return authorization != null && authorization.hasPermission(permission);
    }

    /**
     * 构造一个跳转类动作：该类待办没有可预览的写操作，只是把人送到对应页面。
     *
     * @param label 按钮文案
     * @param route 目标页面路由
     * @param query 定位参数，例如 focus 指向具体单据主键
     */
    private List<Map<String, Object>> viewAction(String label, String route, Map<String, Object> query) {
        Map<String, Object> actionView = new LinkedHashMap<>();
        actionView.put("kind", "view");
        actionView.put("label", label);
        actionView.put("route", route);
        if (query != null && !query.isEmpty()) {
            actionView.put("query", query);
        }
        return List.of(actionView);
    }

    /**
     * 带权限过滤的跳转动作：没有目标页面查看权限就不给按钮，避免点了必然 403。
     *
     * @param permission 目标页面查看权限，null 表示只要求能用智能工作台
     */
    private List<Map<String, Object>> viewActionWhenAllowed(String label, String permission, String route,
                                                            Map<String, Object> query,
                                                            AuthorizationContext authorization) {
        if (!hasPermission(authorization, PERM_ASSISTANT_USE)) {
            return List.of();
        }
        if (permission != null && !hasPermission(authorization, permission)) {
            return List.of();
        }
        return viewAction(label, route, query);
    }

    /** 按类型统计条数，供前端角标使用；计数为 0 的类型也返回，方便直接绑定。 */
    private Map<String, Object> countByType(List<Map<String, Object>> items) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put(TYPE_PENDING_MATERIAL_ISSUE, 0);
        counts.put(TYPE_PENDING_INBOUND, 0);
        counts.put(TYPE_PENDING_WORK_REPORT, 0);
        counts.put(TYPE_PENDING_INSPECTION, 0);
        counts.put(TYPE_MATERIAL_SHORTAGE, 0);
        counts.put(TYPE_WORK_ORDER_DELAY, 0);
        counts.put(TYPE_EQUIPMENT_FAULT, 0);
        counts.put(TYPE_AUTOMATION_FAILED, 0);
        counts.put(TYPE_EXCEPTION_PENDING, 0);
        for (Map<String, Object> item : items) {
            String type = text(item.get("type"));
            Object current = counts.get(type);
            if (current instanceof Integer count) {
                counts.put(type, count + 1);
            }
        }
        return counts;
    }

    /** 严重程度排序权重，数字越小越靠前。 */
    private int severityRank(String severity) {
        if (SEVERITY_HIGH.equals(severity)) {
            return 0;
        }
        return SEVERITY_MEDIUM.equals(severity) ? 1 : 2;
    }

    /** 已等待分钟数，起点为空或未来时间按 0 处理。 */
    private long waitingMinutes(LocalDateTime since) {
        if (since == null) {
            return 0L;
        }
        long minutes = Duration.between(since, LocalDateTime.now()).toMinutes();
        return Math.max(minutes, 0L);
    }

    /** 工单状态是否处于已下达或生产中。 */
    private boolean isReleasedOrRunning(String status) {
        return BusinessStatus.Document.RELEASED.equals(status) || BusinessStatus.Production.RUNNING.equals(status);
    }

    private MfgWorkOrder loadWorkOrder(Long workOrderId) {
        if (workOrderId == null) {
            return null;
        }
        MfgWorkOrder order = workOrderMapper.selectById(workOrderId);
        return order == null || Integer.valueOf(1).equals(order.getDeletedFlag()) ? null : order;
    }

    private MfgProductionPlan loadPlan(Long planId) {
        if (planId == null) {
            return null;
        }
        MfgProductionPlan plan = productionPlanMapper.selectById(planId);
        return plan == null || Integer.valueOf(1).equals(plan.getDeletedFlag()) ? null : plan;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
