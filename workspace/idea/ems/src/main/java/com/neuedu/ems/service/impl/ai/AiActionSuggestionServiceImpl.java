package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.entity.ai.AiQueryResult;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.service.ai.AiActionSuggestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 操作入口建议实现。
 *
 * <p>只推荐「现在点了真能办成」的动作：先把查询结果里的单号回到业务表查一次真实状态，
 * 状态允许、账号有权限、动作已实现，三个条件同时满足才给按钮。任何一条不满足就不出现，
 * 因此不会出现「点了报错」或「点了什么也没发生」的假按钮。</p>
 */
@Service
public class AiActionSuggestionServiceImpl implements AiActionSuggestionService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService documentNumbers;

    private String currentDocumentNumber(String value) {
        String normalized = value.trim();
        return documentNumbers == null ? normalized : documentNumbers.canonical(normalized);
    }


    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(AiActionSuggestionServiceImpl.class);

    /** 与 AiCommandServiceImpl 保持一致的写动作编码。 */
    private static final String ACTION_RELEASE = AiCommandServiceImpl.ACTION_RELEASE_WORK_ORDER;
    private static final String ACTION_START = AiCommandServiceImpl.ACTION_START_WORK_ORDER;
    private static final String ACTION_MATERIAL_ISSUE = AiCommandServiceImpl.ACTION_GENERATE_MATERIAL_ISSUE;
    private static final String ACTION_SUBMIT_INSPECTION = AiCommandServiceImpl.ACTION_SUBMIT_INSPECTION;

    private static final String PERM_RELEASE = "mfg:work-order:release";
    /** 生产计划页，组件工单的来源计划还没生效时先跳这里。 */
    private static final String ROUTE_PRODUCTION_PLAN = "/mfg/production-plans";
    private static final String PERM_START = "mfg:work-order:start";
    private static final String PERM_MATERIAL_ISSUE = "mfg:work-order:material-issue";
    private static final String PERM_INSPECTION_SUBMIT = "qms:inspection-order:submit";

    /** 订单进度意图：要补出成品入库、发货这两步的入口。 */
    private static final String INTENT_ORDER_PROGRESS = "order_progress";

    private static final String ACTION_GENERATE_PRODUCTION_INBOUND =
            AiCommandServiceImpl.ACTION_GENERATE_PRODUCTION_INBOUND;
    private static final String ACTION_GENERATE_SALES_OUTBOUND =
            AiCommandServiceImpl.ACTION_GENERATE_SALES_OUTBOUND;
    private static final String ACTION_CREATE_OUTGOING_INSPECTION =
            AiCommandServiceImpl.ACTION_CREATE_OUTGOING_INSPECTION;
    private static final String ACTION_CONFIRM_SALES_OUTBOUND =
            AiCommandServiceImpl.ACTION_CONFIRM_SALES_OUTBOUND;
    private static final String ACTION_SUBMIT_INSPECTION_OUTGOING = AiCommandServiceImpl.ACTION_SUBMIT_INSPECTION;

    private static final String PERM_INBOUND_CREATE = "wms:inbound-order:create";
    private static final String PERM_SALES_OUTBOUND_CREATE = "wms:sales-outbound:create";
    private static final String PERM_SALES_INSPECTION = "wms:sales-outbound:inspection";
    private static final String PERM_SALES_CONFIRM = "wms:sales-outbound:confirm";

    /** 订单还在草稿/未排产时，下一步是排产而不是入库发货。 */
    private static final String ACTION_SCHEDULE_ORDER = AiCommandServiceImpl.ACTION_SCHEDULE_PRODUCTION_ORDER;
    private static final String ACTION_GENERATE_WORK_ORDER = AiCommandServiceImpl.ACTION_GENERATE_WORK_ORDER;
    private static final String PERM_SCHEDULE_CONFIRM = "mfg:schedule-scenario:confirm";
    private static final String PERM_WORK_ORDER_GENERATE = "mfg:work-order:generate";

    /** 一次回答最多给几个办理按钮，多了会盖住正文。 */
    private static final int MAX_COMMAND_ACTIONS = 3;

    /** 能带出工单号、进而判断能否办理的意图。 */
    private static final Set<String> WORK_ORDER_INTENTS = Set.of(
            "work_order_detail", "work_order_list", "order_progress", "defect_analysis",
            "capacity_feasibility", "equipment_impact", "batch_trace", "production_daily");

    /** 开工条件核查：结论本身就要带出「先办哪一步」的按钮。 */
    private static final String INTENT_WORK_ORDER_READINESS = "work_order_readiness";

    /** 开工处置方案：带出的是方案第一步能点的按钮，与核查同一套判断。 */
    private static final String INTENT_WORK_ORDER_START_PLAN = "work_order_start_plan";

    /** 能带出检验单号的意图。 */
    private static final Set<String> INSPECTION_INTENTS = Set.of("inspection_pending");

    /** 各意图对应的列表页路由，用于「查看」类动作。 */
    private static final Map<String, String> ROUTES = Map.ofEntries(
            Map.entry("work_order_detail", "/mfg/work-orders"),
            Map.entry("work_order_list", "/mfg/work-orders"),
            Map.entry("order_progress", "/mfg/production-orders"),
            Map.entry("production_daily", "/mfg/work-reports"),
            Map.entry("defect_analysis", "/qms/inspection-orders"),
            Map.entry("inspection_pending", "/qms/inspection-orders"),
            Map.entry("inventory_flow", "/wms/movements"),
            Map.entry("inventory_stock", "/wms/inventory"),
            Map.entry("inventory_below_safety", "/wms/inventory"),
            Map.entry("batch_trace", "/trace/material-usages"),
            Map.entry("schedule_capacity", "/mfg/production-plans"),
            Map.entry("capacity_feasibility", "/mfg/schedule-workbench"),
            Map.entry("bom_requirement", "/mdm/bom-explorer"),
            Map.entry("quality_yield", "/qms/inspection-orders"),
            Map.entry("equipment_impact", "/eam/equipment"),
            Map.entry("customer_order", "/mfg/production-orders"),
            Map.entry(INTENT_WORK_ORDER_READINESS, "/mfg/work-orders"),
            Map.entry(INTENT_WORK_ORDER_START_PLAN, "/mfg/work-orders"));

    @Autowired(required = false)
    private MfgWorkOrderMapper workOrderMapper;
    @Autowired(required = false)
    private com.neuedu.ems.service.mfg.StockComponentSupplyService stockComponentSupply;
    @Autowired(required = false)
    private MfgProductionPlanMapper productionPlanMapper;
    @Autowired(required = false)
    private QmsInspectionOrderMapper inspectionOrderMapper;
    /** 开工条件核查，用于判断「开始生产」此刻点下去会不会被后端拒绝。 */
    @Autowired(required = false)
    private com.neuedu.ems.service.ai.AiDataQueryService dataQueryService;
    /** 第 7 阶段：按订单找终检合格、还没入库的产品批次。 */
    @Autowired(required = false)
    private com.neuedu.ems.mapper.trace.TraceProductLotMapper productLotMapper;
    /** 第 8 阶段：找这张订单已有的销售出库单，判断走到哪一步。 */
    @Autowired(required = false)
    private com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper salesOutboundMapper;
    /** 生产订单：拿订单量和交期，判断成品入库与发货是否走完。 */
    @Autowired(required = false)
    private com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper productionOrderMapper;
    /** 入库单：判断某个产品批次是否已经生成过入库单。 */
    @Autowired(required = false)
    private com.neuedu.ems.mapper.wms.WmsInboundOrderMapper inboundOrderMapper;

    @Override
    public List<ActionSuggestion> suggest(String intent, Map<String, Object> params,
                                          List<Map<String, Object>> rows, AuthorizationContext authorization) {
        String normalized = normalizeIntent(intent);
        List<ActionSuggestion> suggestions = new ArrayList<>();
        if (INTENT_WORK_ORDER_READINESS.equals(normalized) || INTENT_WORK_ORDER_START_PLAN.equals(normalized)) {
            // 处置方案的主表行不是核查行，读不到 passed 列，传 null 让 readinessActions 自己重新核一次。
            suggestions.addAll(readinessActions(params,
                    INTENT_WORK_ORDER_READINESS.equals(normalized) ? rows : null, authorization));
        } else if (WORK_ORDER_INTENTS.contains(normalized)) {
            suggestions.addAll(workOrderActions(rows, authorization));
        }
        // 订单进度是唯一能看到「八阶段走到哪」的意图：入库和发货没做完时，
        // 光给工单动作会把人引向已完工的工单，所以这里单独补第 7、8 步的入口。
        if (INTENT_ORDER_PROGRESS.equals(normalized)) {
            suggestions.addAll(deliveryActions(rows, authorization));
        }
        if (INSPECTION_INTENTS.contains(normalized)) {
            suggestions.addAll(inspectionActions(rows, authorization));
        }
        suggestions.addAll(viewActions(normalized, params, rows));
        // 同一个动作同一个目标只留一条：多张工单各自带出「查看这张单据」时，
        // 不去重会让按钮区出现一串重复项，答辩演示时看着像出错了。
        return distinctActions(suggestions);
    }

    /** 按「动作编码 + 目标单号」去重，保留首次出现的顺序。 */
    private List<ActionSuggestion> distinctActions(List<ActionSuggestion> suggestions) {
        Map<String, ActionSuggestion> unique = new LinkedHashMap<>();
        for (ActionSuggestion suggestion : suggestions) {
            if (suggestion == null) {
                continue;
            }
            String targetNo = suggestion.params() == null ? null : text(suggestion.params().get("targetNo"));
            String safeTarget = targetNo == null ? "" : targetNo;
            String focus = actionParameterKey(suggestion, "focus");
            String targetId = actionParameterKey(suggestion, "targetId");
            String route = suggestion.route() == null ? "" : suggestion.route();
            String key = suggestion.code() + "|" + safeTarget + "|" + focus + "|" + targetId + "|" + route;
            unique.putIfAbsent(key, suggestion);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 开工核查结论对应的动作。
     *
     * <p>这里给的不是「这张工单能做的所有事」，而是「按这份结论现在最该点的那一步」：
     * 前置条件没全过就不能给「开始生产」，否则用户点下去还是失败；
     * 挡住开工的组件子工单还是草稿时，先给它们的「下达工单」，这才是真正解开阻塞的动作。</p>
     */
    private List<ActionSuggestion> readinessActions(Map<String, Object> params, List<Map<String, Object>> checks,
                                                    AuthorizationContext authorization) {
        String workOrderNo = text(params == null ? null : params.get("workOrderNo"));
        // 没有工单号或没有工单数据源时无动作可给
        boolean queryable = StringUtils.hasText(workOrderNo) && workOrderMapper != null;
        MfgWorkOrder order = queryable ? findWorkOrder(workOrderNo) : null;
        if (order == null) {
            return List.of();
        }
        return buildReadinessActions(order, checks, authorization);
    }

    /** 按工单当前状态给出「现在最该点的那一步」。 */
    private List<ActionSuggestion> buildReadinessActions(MfgWorkOrder order,
                                                         List<Map<String, Object>> checks,
                                                         AuthorizationContext authorization) {
        List<ActionSuggestion> actions = new ArrayList<>();
        String status = order.getStatus();
        if (isDraftOrApproved(status)) {
            addCommandAction(actions, ACTION_RELEASE, "下达工单", PERM_RELEASE, order.getWorkOrderNo(),
                    order.getWorkOrderId(), authorization);
            return actions;
        }
        if (BusinessStatus.Document.RELEASED.equals(status)) {
            addReleasedActions(actions, order, checks, authorization);
        }
        if (isReleasedOrRunning(status)) {
            addCommandAction(actions, ACTION_MATERIAL_ISSUE, "生成领料单", PERM_MATERIAL_ISSUE,
                    order.getWorkOrderNo(), order.getWorkOrderId(), authorization);
        }
        return actions;
    }

    /** 已下达状态：检查全过才给「开始生产」，否则先给解开阻塞的组件工单动作。 */
    private void addReleasedActions(List<ActionSuggestion> actions, MfgWorkOrder order,
                                    List<Map<String, Object>> checks, AuthorizationContext authorization) {
        // checks 为 null 说明调用方给的不是核查结论行（比如处置方案），自己重新核一次。
        boolean ready = checks == null ? canStartNow(order) : allChecksPassed(checks);
        // 只有全部检查项都通过才给「开始生产」，否则就是点了必失败的假按钮。
        if (ready) {
            addCommandAction(actions, ACTION_START, "开始生产", PERM_START, order.getWorkOrderNo(),
                    order.getWorkOrderId(), authorization);
        } else {
            addDraftComponentReleaseActions(actions, order, authorization);
        }
    }

    /**
     * 先把挡住开工的组件子工单下达掉，这是解开阻塞的第一步。
     *
     * <p>但组件工单自己也可能还下不了：下达生产工单的前置检查要求来源生产计划已生效，
     * 计划还是草稿时点「下达组件工单」会被拦住。这种情况下给的是「先让来源计划生效」的
     * 跳转动作，而不是一个必然失败的办理按钮。</p>
     */
    private void addDraftComponentReleaseActions(List<ActionSuggestion> actions, MfgWorkOrder parent,
                                                 AuthorizationContext authorization) {
        if (!hasPermission(authorization, PERM_RELEASE)) {
            return;
        }
        List<MfgWorkOrder> children = workOrderMapper.selectList(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getParentWorkOrderId, parent.getWorkOrderId())
                .eq(MfgWorkOrder::getDeletedFlag, 0)
                .in(MfgWorkOrder::getStatus, BusinessStatus.Document.DRAFT, BusinessStatus.Document.APPROVED)
                .orderByAsc(MfgWorkOrder::getWorkOrderId));
        Set<Long> pendingPlans = new LinkedHashSet<>();
        List<MfgWorkOrder> releasable = new ArrayList<>();
        for (MfgWorkOrder child : children) {
            MfgProductionPlan plan = findPlan(child.getPlanId());
            if (plan != null && !Set.of("RELEASED","RUNNING").contains(plan.getStatus())) {
                pendingPlans.add(plan.getPlanId());
            } else {
                releasable.add(child);
            }
        }
        if (!pendingPlans.isEmpty()) {
            // 计划没生效时，先跳到计划页把计划下达，回来再下达组件工单。
            Long planId = pendingPlans.iterator().next();
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("focus", String.valueOf(planId));
            actions.add(new ActionSuggestion("VIEW_PRODUCTION_PLAN",
                    "先下达来源生产计划 " + pendingPlans.size() + " 张", ActionSuggestion.KIND_VIEW,
                    query, ROUTE_PRODUCTION_PLAN));
        }
        for (MfgWorkOrder child : releasable) {
            if (actions.size() >= MAX_COMMAND_ACTIONS) {
                return;
            }
            addCommandAction(actions, ACTION_RELEASE, "下达组件工单 " + child.getWorkOrderNo(), PERM_RELEASE,
                    child.getWorkOrderNo(), child.getWorkOrderId(), authorization);
        }
    }

    private MfgProductionPlan findPlan(Long planId) {
        if (planId == null || productionPlanMapper == null) {
            return null;
        }
        return productionPlanMapper.selectOne(new LambdaQueryWrapper<MfgProductionPlan>()
                .eq(MfgProductionPlan::getPlanId, planId)
                .eq(MfgProductionPlan::getDeletedFlag, 0).last("limit 1"));
    }

    /** 检查项里只要有一条「未通过」就说明还不能开工。 */
    private static boolean allChecksPassed(List<Map<String, Object>> checks) {
        if (checks == null || checks.isEmpty()) {
            return false;
        }
        for (Map<String, Object> row : checks) {
            if ("未通过".equals(text(row.get("passed")))) {
                return false;
            }
        }
        return true;
    }

    /* ==================== 办理类动作 ==================== */

    /**
     * 工单相关动作：按工单真实状态决定能不能下达、开始生产、生成领料单。
     */
    private List<ActionSuggestion> workOrderActions(List<Map<String, Object>> rows,
                                                    AuthorizationContext authorization) {
        if (rows == null || rows.isEmpty() || workOrderMapper == null) {
            return List.of();
        }
        List<ActionSuggestion> actions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (actions.size() >= MAX_COMMAND_ACTIONS) {
                break;
            }
            String workOrderNo = text(row.get("workOrderNo"));
            if (!StringUtils.hasText(workOrderNo)) {
                continue;
            }
            MfgWorkOrder order = findWorkOrder(workOrderNo);
            if (order == null) {
                continue;
            }
            actions.addAll(actionsForStatus(order, authorization));
        }
        return actions;
    }

    /**
     * 按单张工单的当前状态给出可执行动作，状态不允许或没权限的一律跳过。
     */
    private List<ActionSuggestion> actionsForStatus(MfgWorkOrder order, AuthorizationContext authorization) {
        String status = order.getStatus();
        List<ActionSuggestion> actions = new ArrayList<>();
        if (isDraftOrApproved(status)) {
            addCommandAction(actions, ACTION_RELEASE, "下达工单", PERM_RELEASE, order.getWorkOrderNo(),
                    order.getWorkOrderId(), authorization);
        }
        if (BusinessStatus.Document.RELEASED.equals(status)) {
            if (canStartNow(order)) {
                addCommandAction(actions, ACTION_START, "开始生产", PERM_START, order.getWorkOrderNo(),
                        order.getWorkOrderId(), authorization);
            } else {
                // 现在开不了工：先给解开阻塞的第一步，而不是一个点了必失败的按钮。
                addDraftComponentReleaseActions(actions, order, authorization);
            }
        }
        if (isReleasedOrRunning(status)) {
            addCommandAction(actions, ACTION_MATERIAL_ISSUE, "生成领料单", PERM_MATERIAL_ISSUE,
                    order.getWorkOrderNo(), order.getWorkOrderId(), authorization);
        }
        return actions;
    }

    /**
     * 已下达的工单是否真的能开工。
     *
     * <p>不能只看 status：用料没齐套或还没生成工序任务时，「开始生产」点下去一定被后端拒绝。
     * 这里复用开工条件核查的同源判定，只要有一条不满足就不给这个按钮。</p>
     */
    private boolean canStartNow(MfgWorkOrder order) {
        if (dataQueryService == null) {
            // 判定服务不可用时宁可不给按钮，也不给点了必失败的按钮。
            return false;
        }
        try {
            AiQueryResult readiness = dataQueryService.queryWorkOrderReadiness(order.getWorkOrderNo());
            return readiness != null && allChecksPassed(readiness.getRows());
        } catch (RuntimeException e) {
            LOG.warn("开工条件核查失败，跳过开始生产按钮 workOrderNo={} message={}",
                    order.getWorkOrderNo(), e.getMessage());
            return false;
        }
    }

    /**
     * 检验单动作：只有草稿状态的检验单才能提交结果。
     */
    private List<ActionSuggestion> inspectionActions(List<Map<String, Object>> rows,
                                                     AuthorizationContext authorization) {
        if (rows == null || rows.isEmpty() || inspectionOrderMapper == null
                || !hasPermission(authorization, PERM_INSPECTION_SUBMIT)) {
            return List.of();
        }
        List<ActionSuggestion> actions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (actions.size() >= MAX_COMMAND_ACTIONS) {
                break;
            }
            String inspectionNo = text(row.get("inspectionNo"));
            if (!StringUtils.hasText(inspectionNo)) {
                continue;
            }
            QmsInspectionOrder order = findInspection(inspectionNo);
            if (order == null || !BusinessStatus.Document.DRAFT.equals(order.getStatus())) {
                continue;
            }
            actions.add(new ActionSuggestion(ACTION_SUBMIT_INSPECTION, "提交检验结果",
                    ActionSuggestion.KIND_COMMAND,
                    commandParams(ACTION_SUBMIT_INSPECTION, order.getInspectionNo(), order.getInspectionId()),
                    null));
        }
        return actions;
    }

    /**
     * 第 7、8 阶段：成品入库与发货。
     *
     * <p>这两步不在工单上，只看工单状态永远推不出来：订单头 FINISHED 只代表工单完工。
     * 这里按「终检合格批次有没有入库 → 有没有发货单 → 有没有出货检验 → 检验过没过」
     * 依次判断，只给当前真正缺的那一个动作。</p>
     */
    private List<ActionSuggestion> deliveryActions(List<Map<String, Object>> rows, AuthorizationContext authorization) {
        List<ActionSuggestion> actions = new ArrayList<>();
        com.neuedu.ems.entity.mfg.MfgProductionOrder order = findProductionOrder(rows);
        if (order == null || List.of("CANCELLED", "CLOSED").contains(order.getStatus())) {
            return actions;
        }
        if (BusinessStatus.Document.DRAFT.equals(order.getStatus())) {
            if (hasPermission(authorization, PERM_SCHEDULE_CONFIRM)) {
                addCommandAction(actions, ACTION_SCHEDULE_ORDER, "安排生产订单", PERM_SCHEDULE_CONFIRM, order.getProductionOrderNo(), order.getProductionOrderId(), authorization);
            }
        } else {
            releasedOrderActions(order, authorization, actions);
        }
        return actions;
    }

    private void releasedOrderActions(com.neuedu.ems.entity.mfg.MfgProductionOrder order, AuthorizationContext authorization, List<ActionSuggestion> actions) {
        List<Long> orderWorkOrderIds = orderWorkOrderIds(order);
        // 已排产但还没生成工单时，下一步是生成工单，同样不该出现入库/发货按钮。
        if (orderWorkOrderIds.isEmpty()) {
            if (hasPermission(authorization, PERM_WORK_ORDER_GENERATE)) {
                addCommandAction(actions, ACTION_GENERATE_WORK_ORDER, "按订单生成工单", PERM_WORK_ORDER_GENERATE, order.getProductionOrderNo(), order.getProductionOrderId(), authorization);
            }
            return;
        }
        // 先看还有没有终检合格但未入库的批次：有就说明第 7 阶段还没办。
        List<com.neuedu.ems.entity.trace.TraceProductLot> pendingLots = pendingInboundLots(order.getProductId(), orderWorkOrderIds);
        if (!pendingLots.isEmpty() && hasPermission(authorization, PERM_INBOUND_CREATE)) {
            com.neuedu.ems.entity.trace.TraceProductLot lot = pendingLots.getFirst();
            addCommandAction(actions, ACTION_GENERATE_PRODUCTION_INBOUND, "生成成品入库单 " + lot.getLotNo(), PERM_INBOUND_CREATE, lot.getLotNo(), lot.getProductLotId(), authorization);
            return;
        }
        shippingActions(order, authorization, actions);
    }

    private void shippingActions(com.neuedu.ems.entity.mfg.MfgProductionOrder order, AuthorizationContext authorization, List<ActionSuggestion> actions) {
        List<com.neuedu.ems.entity.wms.WmsOutboundOrder> outbounds = activeSalesOutbounds(order.getProductionOrderId());
        if (outbounds.isEmpty()) {
            // 订单还没全部完工时不能生成发货单：领域服务会以「工单尚未完成」拒绝，
            // 提前在这里挡住，避免给用户一个点了必然失败的按钮。
            if (allWorkOrdersFinished(order) && hasPermission(authorization, PERM_SALES_OUTBOUND_CREATE)) {
                addCommandAction(actions, ACTION_GENERATE_SALES_OUTBOUND, "生成发货单", PERM_SALES_OUTBOUND_CREATE, order.getProductionOrderNo(), order.getProductionOrderId(), authorization);
            }
            return;
        }
        com.neuedu.ems.entity.wms.WmsOutboundOrder outbound = outbounds.getFirst();
        // 已经确认发货的单据不再给按钮：再点一次只会被判「只有待确认的销售出库单可以确认发货」。
        if (BusinessStatus.Document.APPROVED.equals(outbound.getAuditStatus())) {
            return;
        }
        if (outbound.getInspectionId() == null) {
            if (hasPermission(authorization, PERM_SALES_INSPECTION)) {
                addCommandAction(actions, ACTION_CREATE_OUTGOING_INSPECTION, "生成出货检验单", PERM_SALES_INSPECTION, outbound.getOutboundNo(), outbound.getOutboundId(), authorization);
            }
            return;
        }
        inspectionOrShipmentActions(outbound, authorization, actions);
    }

    private void inspectionOrShipmentActions(com.neuedu.ems.entity.wms.WmsOutboundOrder outbound, AuthorizationContext authorization, List<ActionSuggestion> actions) {
        QmsInspectionOrder inspection = findInspectionById(outbound.getInspectionId());
        if (inspection != null && BusinessStatus.Document.DRAFT.equals(inspection.getStatus()) && hasPermission(authorization, PERM_INSPECTION_SUBMIT)) {
            // 出货检验单建好了还没录结论，先给「提交检验结果」，否则确认发货必然被拒。
            addCommandAction(actions, ACTION_SUBMIT_INSPECTION_OUTGOING, "提交出货检验结果 " + inspection.getInspectionNo(), PERM_INSPECTION_SUBMIT, inspection.getInspectionNo(), inspection.getInspectionId(), authorization);
            return;
        }
        if (hasPermission(authorization, PERM_SALES_CONFIRM)) {
            addCommandAction(actions, ACTION_CONFIRM_SALES_OUTBOUND, "确认发货 " + outbound.getOutboundNo(), PERM_SALES_CONFIRM, outbound.getOutboundNo(), outbound.getOutboundId(), authorization);
        }
    }

    /** 从查询行里定位生产订单。 */
    private com.neuedu.ems.entity.mfg.MfgProductionOrder findProductionOrder(List<Map<String, Object>> rows) {
        if (productionOrderMapper == null || rows == null || rows.isEmpty()) {
            return null;
        }
        String orderNo = text(rows.get(0).get("productionOrderNo"));
        if (!StringUtils.hasText(orderNo)) {
            return null;
        }
        return productionOrderMapper.selectOne(new LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgProductionOrder>().eq(com.neuedu.ems.entity.mfg.MfgProductionOrder::getProductionOrderNo, currentDocumentNumber(orderNo)).eq(com.neuedu.ems.entity.mfg.MfgProductionOrder::getDeletedFlag, 0).last("limit 1"));
    }

    /**
     * 该产品下已终检合格/让步放行、且还没有任何有效入库单的批次。
     *
     * <p>必须按工单范围过滤：同一产品可能被别的订单生产过、批次早已入库或根本不属于
     * 当前订单。订单还没排产/还没生成工单时工单范围为空，此时返回空列表——没有工单
     * 就不可能有本订单产出的批次，放任不过滤会把别的订单的批次当成这一单的待入库项。</p>
     */
    private List<com.neuedu.ems.entity.trace.TraceProductLot> pendingInboundLots(Long productId,
                                                                                 List<Long> workOrderIds) {
        if (productLotMapper == null || productId == null
                || workOrderIds == null || workOrderIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<com.neuedu.ems.entity.trace.TraceProductLot> query =
                new LambdaQueryWrapper<com.neuedu.ems.entity.trace.TraceProductLot>()
                        .eq(com.neuedu.ems.entity.trace.TraceProductLot::getProductId, productId)
                        .in(com.neuedu.ems.entity.trace.TraceProductLot::getStatus,
                                List.of(BusinessStatus.Quality.PASS, BusinessStatus.Quality.CONCESSION))
                        .eq(com.neuedu.ems.entity.trace.TraceProductLot::getDeletedFlag, 0)
                        .in(com.neuedu.ems.entity.trace.TraceProductLot::getWorkOrderId, workOrderIds)
                        .orderByAsc(com.neuedu.ems.entity.trace.TraceProductLot::getProductLotId);
        List<com.neuedu.ems.entity.trace.TraceProductLot> lots = productLotMapper.selectList(query);
        List<com.neuedu.ems.entity.trace.TraceProductLot> pending = new ArrayList<>();
        for (com.neuedu.ems.entity.trace.TraceProductLot lot : lots) {
            if (!alreadyInbound(lot.getProductLotId())) {
                pending.add(lot);
            }
        }
        return pending;
    }

    /** 该订单下所有工单是否都已完工，没工单时按未完工处理。 */
    private boolean allWorkOrdersFinished(com.neuedu.ems.entity.mfg.MfgProductionOrder order) {
        List<Long> workOrderIds = orderWorkOrderIds(order);
        if (workOrderIds.isEmpty() || workOrderMapper == null) {
            return false;
        }
        List<MfgWorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<MfgWorkOrder>()
                .in(MfgWorkOrder::getWorkOrderId, workOrderIds)
                .eq(MfgWorkOrder::getDeletedFlag, 0));
        if (orders.isEmpty()) {
            return false;
        }
        return orders.stream().allMatch(item -> BusinessStatus.Production.FINISHED.equals(item.getStatus())
                || (stockComponentSupply!=null && stockComponentSupply.fulfilledForShipment(item)));
    }

    /** 该订单下所有工单主键，用于把批次限定在本订单范围。 */
    private List<Long> orderWorkOrderIds(com.neuedu.ems.entity.mfg.MfgProductionOrder order) {
        if (order == null || workOrderMapper == null) {
            return List.of();
        }
        List<MfgProductionPlan> plans = productionPlanMapper == null ? List.of() : productionPlanMapper.selectList(new LambdaQueryWrapper<MfgProductionPlan>().eq(MfgProductionPlan::getProductionOrderId, order.getProductionOrderId()).eq(MfgProductionPlan::getDeletedFlag, 0));
        List<Long> planIds = plans.stream().map(MfgProductionPlan::getPlanId).filter(java.util.Objects::nonNull).toList();
        if (planIds.isEmpty()) {
            return List.of();
        }
        return workOrderMapper.selectList(new LambdaQueryWrapper<MfgWorkOrder>().in(MfgWorkOrder::getPlanId, planIds).eq(MfgWorkOrder::getDeletedFlag, 0)).stream().map(MfgWorkOrder::getWorkOrderId).filter(java.util.Objects::nonNull).toList();
    }

    /** 该批次是否已经生成过入库单（含待确认和已确认）。 */
    private boolean alreadyInbound(Long productLotId) {
        if (inboundOrderMapper == null || productLotId == null) {
            return false;
        }
        Long count = inboundOrderMapper.selectCount(
                new LambdaQueryWrapper<com.neuedu.ems.entity.wms.WmsInboundOrder>()
                        .eq(com.neuedu.ems.entity.wms.WmsInboundOrder::getInboundType, "PRODUCTION")
                        .eq(com.neuedu.ems.entity.wms.WmsInboundOrder::getSourceOrderType, "PRODUCT_LOT")
                        .eq(com.neuedu.ems.entity.wms.WmsInboundOrder::getSourceOrderId, productLotId)
                        .eq(com.neuedu.ems.entity.wms.WmsInboundOrder::getDeletedFlag, 0));
        return count != null && count > 0;
    }

    /** 该订单未作废的销售出库单。 */
    private List<com.neuedu.ems.entity.wms.WmsOutboundOrder> activeSalesOutbounds(Long productionOrderId) {
        if (salesOutboundMapper == null || productionOrderId == null) {
            return List.of();
        }
        return salesOutboundMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.wms.WmsOutboundOrder>()
                        .eq(com.neuedu.ems.entity.wms.WmsOutboundOrder::getSourceProductionOrderId, productionOrderId)
                        .eq(com.neuedu.ems.entity.wms.WmsOutboundOrder::getOutboundType, "SALE")
                        .eq(com.neuedu.ems.entity.wms.WmsOutboundOrder::getDeletedFlag, 0)
                        .orderByDesc(com.neuedu.ems.entity.wms.WmsOutboundOrder::getOutboundId)
                        .last("limit " + MAX_COMMAND_ACTIONS));
    }

    /** 按主键取检验单。 */
    private QmsInspectionOrder findInspectionById(Long inspectionId) {
        if (inspectionId == null || inspectionOrderMapper == null) {
            return null;
        }
        QmsInspectionOrder order = inspectionOrderMapper.selectById(inspectionId);
        return order == null || !Integer.valueOf(0).equals(order.getDeletedFlag()) ? null : order;
    }

    /**
     * 追加一个办理动作：账号没有对应权限时不加。
     */
    private void addCommandAction(List<ActionSuggestion> actions, String action, String label, String permission,
                                  String targetNo, Long targetId, AuthorizationContext authorization) {
        if (!hasPermission(authorization, permission)) {
            return;
        }
        actions.add(new ActionSuggestion(action, labelWithTarget(label, targetNo), ActionSuggestion.KIND_COMMAND,
                commandParams(action, targetNo, targetId), null));
    }

    /**
     * 办理按钮文案补上目标单号。
     *
     * <p>同一种动作对多张工单各给一个按钮时，文案都是「生成领料单」会让人以为按钮重复了，
     * 带上单号才能分清是给哪一张办的。文案里已经出现该单号的（如「生成成品入库单 C1-LP-…」）
     * 不再重复拼接。</p>
     */
    private static String labelWithTarget(String label, String targetNo) {
        if (!StringUtils.hasText(label) || !StringUtils.hasText(targetNo)
                || label.contains(targetNo)) {
            return label;
        }
        return label + " " + targetNo;
    }

    /**
     * 办理动作的参数：动作编码 + 单据号 + 主键，前端点击可直接交给预览接口。
     */
    private Map<String, Object> commandParams(String action, String targetNo, Long targetId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", action);
        params.put("targetNo", targetNo);
        if (targetId != null) {
            params.put("targetId", targetId);
        }
        return params;
    }

    /* ==================== 查看类动作 ==================== */

    /**
     * 查看类动作：能定位到具体记录就给带主键的路由，否则只给列表页。
     */
    private List<ActionSuggestion> viewActions(String intent, Map<String, Object> params,
                                               List<Map<String, Object>> rows) {
        String route = ROUTES.get(intent);
        if (!StringUtils.hasText(route)) {
            return List.of();
        }
        String focus = focusOf(intent, rows);
        Map<String, Object> viewParams = new LinkedHashMap<>();
        if (StringUtils.hasText(focus)) {
            viewParams.put("focus", focus);
        }
        String label = StringUtils.hasText(focus) ? "查看这张单据" : "打开列表";
        return List.of(new ActionSuggestion("VIEW_LIST", label, ActionSuggestion.KIND_VIEW,
                viewParams, route));
    }

    /**
     * 从查询结果里取能直接定位记录的主键，取不到返回 null。
     */
    private String focusOf(String intent, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if ("inspection_pending".equals(intent) || "defect_analysis".equals(intent)) {
            QmsInspectionOrder order = findInspection(text(row.get("inspectionNo")));
            return order == null ? null : String.valueOf(order.getInspectionId());
        }
        MfgWorkOrder workOrder = findWorkOrder(text(row.get("workOrderNo")));
        return workOrder == null ? null : String.valueOf(workOrder.getWorkOrderId());
    }

    /* ==================== 查询与工具 ==================== */

    private MfgWorkOrder findWorkOrder(String workOrderNo) {
        if (!StringUtils.hasText(workOrderNo) || workOrderMapper == null) {
            return null;
        }
        return workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderNo, currentDocumentNumber(workOrderNo))
                .eq(MfgWorkOrder::getDeletedFlag, 0).last("limit 1"));
    }

    private QmsInspectionOrder findInspection(String inspectionNo) {
        if (!StringUtils.hasText(inspectionNo) || inspectionOrderMapper == null) {
            return null;
        }
        return inspectionOrderMapper.selectOne(new LambdaQueryWrapper<QmsInspectionOrder>()
                .eq(QmsInspectionOrder::getInspectionNo, inspectionNo.trim())
                .eq(QmsInspectionOrder::getDeletedFlag, 0).last("limit 1"));
    }

    private boolean hasPermission(AuthorizationContext authorization, String permission) {
        return authorization != null && authorization.hasPermission(permission);
    }

    private static boolean isDraftOrApproved(String status) {
        return BusinessStatus.Document.DRAFT.equals(status) || BusinessStatus.Document.APPROVED.equals(status);
    }

    private static boolean isReleasedOrRunning(String status) {
        return BusinessStatus.Document.RELEASED.equals(status) || BusinessStatus.Production.RUNNING.equals(status);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** 意图编码统一小写比较，避免调用方传大写时匹配不到路由。 */
    static String normalizeIntent(String intent) {
        return StringUtils.hasText(intent) ? intent.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String actionParameterKey(ActionSuggestion suggestion, String key) {
        return suggestion.params() == null ? "" : String.valueOf(suggestion.params().get(key));
    }
}
