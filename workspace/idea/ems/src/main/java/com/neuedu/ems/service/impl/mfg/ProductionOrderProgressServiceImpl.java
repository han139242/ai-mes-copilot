package com.neuedu.ems.service.impl.mfg;

import com.fasterxml.jackson.databind.JsonNode;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.ProductionOrderProgressMapper;
import com.neuedu.ems.service.mfg.ComponentSupplyPlanningService;
import com.neuedu.ems.service.mfg.ExecutionSnapshotPolicy;
import com.neuedu.ems.service.mfg.ProductionOrderProgressService;
import com.neuedu.ems.service.mfg.PlanMaterialPreviewService;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Action;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Blocker;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.DataQuality;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Dependency;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Document;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Evidence;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Impact;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.MaterialCoverage;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.OrderHeader;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Owner;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.PlanActual;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Stage;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.Summary;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.TimelineEvent;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.WorkOrderNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生产订单全过程只读投影。
 *
 * <p>本服务只读：所有查询都在同一个只读事务内完成，不写业务单据、不产生异常台账、
 * 不改动库存或预留。数量口径遵守以下不可互换的边界：</p>
 * <ul>
 *   <li>订单量只计整机；组件产量与多工序报工不得相加为整机产量。</li>
 *   <li>本单已发料、本单仍有效预留、公共合格可用量三者分开统计，公共池在本订单各需求间只分配一次。</li>
 *   <li>预留以 {@code mfg_supply_allocation} 的实际行状态为准，不直接采信用料需求上可能滞后的
 *       {@code reserved_quantity} 汇总列。</li>
 *   <li>生产入库来源固定 {@code PRODUCT_LOT}；发货只认 {@code SALE} 且来源生产订单匹配且已确认。</li>
 *   <li>实际起止时间优先取状态变更日志，缺失时退回报工时间并标 {@code DERIVED}。</li>
 * </ul>
 */
@Service
public class ProductionOrderProgressServiceImpl implements ProductionOrderProgressService {

    private static final String BIZ_TASK = "MFG_TASK";
    private static final String BIZ_WORK_ORDER = "MFG_WORK_ORDER";

    private static final String T_NOT_STARTED = "NOT_STARTED";
    private static final String T_RUNNING = "RUNNING";
    private static final String T_DONE = "DONE";
    private static final String T_RISK = "RISK";
    private static final String T_BLOCKED = "BLOCKED";
    private static final String T_NA = "NA";

    private static final String LV_RED = "RED";
    private static final String LV_YELLOW = "YELLOW";

    private static final String TIME_LOGGED = "LOGGED";
    private static final String TIME_DERIVED = "DERIVED";
    private static final String TIME_MISSING = "MISSING";
    private static final String TIME_REPORTED = "REPORTED_UNVERIFIED";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 4;

    private final ProductionOrderProgressMapper progressMapper;
    private final MfgWorkOrderMapper workOrderMapper;
    private final ExecutionSnapshotPolicy snapshots;
    private final ComponentSupplyPlanningService supplyPlanning;
    private final PlanMaterialPreviewService materialPreview;

    public ProductionOrderProgressServiceImpl(ProductionOrderProgressMapper progressMapper,
            MfgWorkOrderMapper workOrderMapper, ExecutionSnapshotPolicy snapshots,
            ComponentSupplyPlanningService supplyPlanning, PlanMaterialPreviewService materialPreview) {
        this.progressMapper = progressMapper;
        this.workOrderMapper = workOrderMapper;
        this.snapshots = snapshots;
        this.supplyPlanning = supplyPlanning;
        this.materialPreview = materialPreview;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductionOrderProgressVO inspect(Long orderId, LocalDateTime asOf, AuthorizationContext authorization) {
        if (orderId == null) throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "必须指定生产订单");
        requireCrossModuleAccess(authorization);
        LocalDateTime now = asOf == null ? LocalDateTime.now() : asOf;

        Map<String, Object> headerRow = progressMapper.selectOrderHeader(orderId);
        if (headerRow == null) throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "生产订单不存在或已删除");

        Collect c = new Collect();
        c.headerRow = headerRow;
        c.evaluatedAt = now;
        c.plans = progressMapper.selectPlans(orderId);
        c.orderLogs = progressMapper.selectStatusLogs("MFG_PRODUCTION_ORDER",List.of(orderId));
        c.workOrders = progressMapper.selectWorkOrders(orderId);
        c.workOrderIds = c.workOrders.stream().map(r -> lng(r.get("workOrderId")))
                .filter(Objects::nonNull).distinct().toList();

        loadSnapshots(c);

        if (!c.workOrderIds.isEmpty()) {
            c.requirements = progressMapper.selectRequirements(c.workOrderIds);
            c.dispatches = progressMapper.selectDispatches(c.workOrderIds);
            c.tasks = progressMapper.selectTasks(c.workOrderIds);
            c.lots = progressMapper.selectProductLots(c.workOrderIds);
            c.materialIssues = progressMapper.selectMaterialIssues(c.workOrderIds);
            c.returns = progressMapper.selectConfirmedReturns(c.workOrderIds);
            c.sourceDocuments = progressMapper.selectBatchSourceDocuments(c.workOrderIds);
        }
        c.requirementIds = c.requirements.stream().map(r -> lng(r.get("requirementId")))
                .filter(Objects::nonNull).distinct().toList();
        if (!c.requirementIds.isEmpty()) {
            c.supplySources = progressMapper.selectSupplySources(c.requirementIds);
            c.allocations = progressMapper.selectAllocations(c.requirementIds);
        }
        c.taskIds = c.tasks.stream().map(r -> lng(r.get("taskId")))
                .filter(Objects::nonNull).distinct().toList();
        if (!c.taskIds.isEmpty()) c.reports = progressMapper.selectReports(c.taskIds);
        c.productLotIds = c.lots.stream().map(r -> lng(r.get("productLotId")))
                .filter(Objects::nonNull).distinct().toList();
        if (!c.productLotIds.isEmpty()) {
            c.inbound = progressMapper.selectProductionInbound(c.productLotIds);
            c.dispositions = progressMapper.selectDispositions(c.productLotIds);
        }
        c.shipments = progressMapper.selectShipments(orderId);
        c.inspections = progressMapper.selectInspections(c.workOrderIds, c.productLotIds, orderId);

        if (!c.taskIds.isEmpty()) c.taskLogs = progressMapper.selectStatusLogs(BIZ_TASK, c.taskIds);
        if (!c.workOrderIds.isEmpty()) c.workOrderLogs = progressMapper.selectStatusLogs(BIZ_WORK_ORDER, c.workOrderIds);

        c.stationIds = c.tasks.stream().map(r -> lng(r.get("stationId")))
                .filter(id -> id != null && id > 0).distinct().toList();
        if (!c.stationIds.isEmpty()) c.equipment = progressMapper.selectEquipmentFaults(c.stationIds);

        index(c);

        // 设备故障必须先挂到受影响的阶段上，否则汇总里计了阻塞、页面上却看不到任何红灯。
        Map<String, List<Blocker>> equipmentBlockers = equipmentFaultBlockers(c);

        // 物料覆盖先算：装配与组件阶段直接复用同一份口径，避免两处算法漂移。
        Map<Long, BigDecimal> materialInspection = materialInspectionStock(c.requirementMaterialIds());
        List<MaterialCoverage> materials = buildMaterials(c, materialInspection);
        var preparation = materialPreview.preview(lng(headerRow.get("productId")), c.plans,
                c.workOrders, materials, now);
        c.preparationMaterials = preparation.basicMaterials();
        c.semiFinishedMaterials = preparation.semiFinishedMaterials();
        c.missing.addAll(preparation.missing());

        OrderHeader order = buildOrder(c, now);
        List<Stage> stages = buildStages(c, order, materials, equipmentBlockers, now);
        List<WorkOrderNode> workOrderNodes = buildWorkOrderNodes(c);
        List<Document> documents = collectDocuments(c, stages);
        List<TimelineEvent> timeline = buildTimeline(c, stages);
        List<PlanActual> planActual = buildPlanActual(c, workOrderNodes);
        List<Dependency> dependencies = buildDependencies(c, workOrderNodes);

        DataQuality quality = buildDataQuality(c, documents);
        Summary summary = buildSummary(c, order, stages, materials);
        return new ProductionOrderProgressVO(order, summary, stages, workOrderNodes, materials, documents,
                timeline, planActual, dependencies, quality, now,
                preparation.basicMaterials(), preparation.semiFinishedMaterials());
    }

    // ------------------------------------------------------------------ 权限

    /**
     * 全过程视图跨制造、仓储、质量、设备和追溯五个模块，只具备单个模块查看权的账号
     * 不能借此穿透其他模块数据；这里要求全部相关查看权且数据范围为 ALL 或超级管理员。
     */
    private void requireCrossModuleAccess(AuthorizationContext authorization) {
        if (authorization == null) throw new DataScopeAccessDeniedException();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (!authorization.hasPermission(permission)) throw new DataScopeAccessDeniedException();
        }
        if (!authorization.isSuperAdmin() && !"ALL".equals(authorization.dataScope())) {
            throw new DataScopeAccessDeniedException();
        }
    }

    // ------------------------------------------------------------------ 采集中间态

    private static final class Collect {
        Map<String, Object> headerRow;
        LocalDateTime evaluatedAt;
        List<Map<String,Object>> orderLogs = List.of();
        List<Map<String,Object>> returns = List.of();
        List<Map<String,Object>> sourceDocuments = List.of();
        List<Map<String,Object>> dispositions = List.of();
        List<Map<String, Object>> plans = List.of();
        List<Map<String, Object>> workOrders = List.of();
        List<Map<String, Object>> requirements = List.of();
        List<MaterialCoverage> preparationMaterials = List.of();
        List<MaterialCoverage> semiFinishedMaterials = List.of();
        List<Map<String, Object>> supplySources = List.of();
        List<Map<String, Object>> allocations = List.of();
        List<Map<String, Object>> dispatches = List.of();
        List<Map<String, Object>> tasks = List.of();
        List<Map<String, Object>> reports = List.of();
        List<Map<String, Object>> lots = List.of();
        List<Map<String, Object>> inbound = List.of();
        List<Map<String, Object>> shipments = List.of();
        List<Map<String, Object>> inspections = List.of();
        List<Map<String, Object>> taskLogs = List.of();
        List<Map<String, Object>> workOrderLogs = List.of();
        List<Map<String, Object>> equipment = List.of();
        List<Map<String, Object>> materialIssues = List.of();

        List<Long> workOrderIds = List.of();
        List<Long> requirementIds = List.of();
        List<Long> taskIds = List.of();
        List<Long> productLotIds = List.of();
        List<Long> stationIds = List.of();

        final Map<Long, MfgWorkOrder> entities = new LinkedHashMap<>();
        final Map<Long, Map<Long, BigDecimal>> originalMaterialDemands = new LinkedHashMap<>();
        final Map<Long, List<JsonNode>> routeSteps = new LinkedHashMap<>();
        final Map<Long, Long> outputOperation = new LinkedHashMap<>();
        final Map<Long, String> bomVersions = new LinkedHashMap<>();
        final Map<Long, String> routeVersions = new LinkedHashMap<>();
        final List<String> snapshotNotes = new ArrayList<>();
        final List<String> equipmentNotes = new ArrayList<>();
        final List<String> missing = new ArrayList<>();

        final Map<Long, List<Map<String, Object>>> reqByWorkOrder = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> allocByRequirement = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> sourceByRequirement = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> taskByWorkOrder = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> reportByTask = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> lotByWorkOrder = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> inboundByLot = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> inspectionByWorkOrder = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> inspectionByLot = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> taskLogByTask = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> workOrderLog = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> dispatchByWorkOrder = new LinkedHashMap<>();
        final Map<Long, List<Map<String, Object>>> issueByWorkOrder = new LinkedHashMap<>();
        final List<Map<String, Object>> equipmentFaults = new ArrayList<>();

        List<Long> requirementMaterialIds() {
            return requirements.stream().map(r -> lng(r.get("materialId")))
                    .filter(Objects::nonNull).distinct().toList();
        }

        List<Map<String,Object>> rootWorkOrders() {
            return workOrders.stream().filter(w -> "ROOT".equals(str(w.get("workOrderType")))).toList();
        }



        String workOrderNo(Long workOrderId) {
            for (Map<String, Object> row : workOrders) {
                if (Objects.equals(lng(row.get("workOrderId")), workOrderId)) return str(row.get("workOrderNo"));
            }
            return "";
        }

        String productName(Long workOrderId) {
            for (Map<String, Object> row : workOrders) {
                if (Objects.equals(lng(row.get("workOrderId")), workOrderId)) return str(row.get("productName"));
            }
            return "";
        }
    }

    private void loadSnapshots(Collect c) {
        if (c.workOrderIds.isEmpty()) return;
        List<MfgWorkOrder> loaded = workOrderMapper.selectBatchIds(c.workOrderIds);
        for (MfgWorkOrder wo : loaded) {
            c.entities.put(wo.getWorkOrderId(), wo);
            readSnapshot(c, wo);
        }
    }

    private void readSnapshot(Collect c, MfgWorkOrder wo) {
        try {
            JsonNode root = snapshots.read(wo);
            List<JsonNode> steps = new ArrayList<>();
            root.path("routeItems").forEach(steps::add);
            c.routeSteps.put(wo.getWorkOrderId(), List.copyOf(steps));
            recordOutputOperation(c, wo, steps);
            c.originalMaterialDemands.put(wo.getWorkOrderId(), snapshots.directDemands(root, wo.getPlannedQuantity()));
            c.bomVersions.put(wo.getWorkOrderId(), bomVersionOf(root, wo.getBomId()));
            c.routeVersions.put(wo.getWorkOrderId(), root.path("routeVersion").asText(""));
        } catch (BusinessException e) {
            c.snapshotNotes.add("工单 " + wo.getWorkOrderNo() + " 冻结快照不可用：" + e.getMessage()
                    + "；不推测历史工序及批量参数");
        }
    }

    /** 产出工序 = 冻结路线最后一个工序；路线为空时不记录，避免猜错产出工序。 */
    private void recordOutputOperation(Collect c, MfgWorkOrder wo, List<JsonNode> steps) {
        if (steps.isEmpty()) return;
        JsonNode last = steps.get(steps.size() - 1);
        if (last.path("operationId").isIntegralNumber()) {
            c.outputOperation.put(wo.getWorkOrderId(), last.path("operationId").longValue());
        }
    }

    /**
     * 快照里的 BOM 版本挂在 bomTree 的根节点上，不在顶层；取不到时返回空串，
     * 由数据质量说明「未取得冻结 BOM 版本」，不猜当前主数据版本。
     */
    private String bomVersionOf(JsonNode root, Long bomId) {
        String version = "";
        if (root != null && bomId != null) {
            version = bomVersionInTree(root.path("bomTree"), bomId);
        }
        return version;
    }

    /** 在 bomTree 里找与工单 BOM 一致的节点，取其 bomVersion；找不到返回空串。 */
    private String bomVersionInTree(JsonNode tree, Long bomId) {
        String version = "";
        if (!tree.isArray()) {
            return version;
        }
        for (JsonNode node : tree) {
            if (matchesBom(node, bomId)) {
                version = node.path("bomVersion").asText("");
                break;
            }
        }
        return version;
    }

    private boolean matchesBom(JsonNode node, Long bomId) {
        return node.path("bomId").isIntegralNumber() && node.path("bomId").longValue() == bomId;
    }

    private void index(Collect c) {
        c.requirements.forEach(r -> c.reqByWorkOrder
                .computeIfAbsent(lng(r.get("workOrderId")), k -> new ArrayList<>()).add(r));
        c.allocations.forEach(a -> c.allocByRequirement
                .computeIfAbsent(lng(a.get("requirementId")), k -> new ArrayList<>()).add(a));
        c.supplySources.forEach(s -> c.sourceByRequirement
                .computeIfAbsent(lng(s.get("requirementId")), k -> new ArrayList<>()).add(s));
        c.dispatches.forEach(d -> c.dispatchByWorkOrder
                .computeIfAbsent(lng(d.get("workOrderId")), k -> new ArrayList<>()).add(d));
        c.tasks.forEach(t -> c.taskByWorkOrder
                .computeIfAbsent(lng(t.get("workOrderId")), k -> new ArrayList<>()).add(t));
        c.reports.forEach(r -> c.reportByTask
                .computeIfAbsent(lng(r.get("taskId")), k -> new ArrayList<>()).add(r));
        c.lots.forEach(l -> c.lotByWorkOrder
                .computeIfAbsent(lng(l.get("workOrderId")), k -> new ArrayList<>()).add(l));
        c.inbound.forEach(i -> c.inboundByLot
                .computeIfAbsent(lng(i.get("sourceOrderId")), k -> new ArrayList<>()).add(i));
        c.materialIssues.forEach(i -> c.issueByWorkOrder
                .computeIfAbsent(lng(i.get("workOrderId")), k -> new ArrayList<>()).add(i));
        for (Map<String, Object> q : c.inspections) {
            Long wo = lng(q.get("workOrderId"));
            if (wo != null && wo > 0) c.inspectionByWorkOrder.computeIfAbsent(wo, k -> new ArrayList<>()).add(q);
            Long lot = lng(q.get("productLotId"));
            if (lot != null && lot > 0) c.inspectionByLot.computeIfAbsent(lot, k -> new ArrayList<>()).add(q);
        }
        c.taskLogs.forEach(l -> c.taskLogByTask
                .computeIfAbsent(lng(l.get("bizId")), k -> new ArrayList<>()).add(l));
        c.workOrderLogs.forEach(l -> c.workOrderLog
                .computeIfAbsent(lng(l.get("bizId")), k -> new ArrayList<>()).add(l));
        for (Map<String, Object> e : c.equipment) {
            boolean fault = !str(e.get("repairStatus")).isEmpty()
                    || Set.of("FAULT", "REPAIR", "MAINTAIN", "REPAIRING", "MAINTAINING", "STOPPED", "SCRAPPED", "OFFLINE").contains(str(e.get("equipmentStatus")));
            if (fault) c.equipmentFaults.add(e);
        }
    }

    /** 待检库存：只读仓库余额上的 inspection_quantity，不把待检量算作可用量。 */
    private Map<Long, BigDecimal> materialInspectionStock(List<Long> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) return Map.of();
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (Map<String, Object> row : progressMapper.selectInspectionStock(materialIds)) {
            result.merge(lng(row.get("materialId")), dec(row.get("inspectionQuantity")), BigDecimal::add);
        }
        return result;
    }

    // ------------------------------------------------------------------ 订单头

    private OrderHeader buildOrder(Collect c, LocalDateTime now) {
        Map<String, Object> h = c.headerRow;
        String customerName = str(h.get("customerName"));
        String remark = str(h.get("remark"));
        boolean rehearsal = customerName.contains("演练") || customerName.contains("虚拟")
                || remark.toLowerCase().contains("rehearsal");
        return new OrderHeader(lng(h.get("productionOrderId")), str(h.get("productionOrderNo")), customerName,
                lng(h.get("productId")), str(h.get("productCode")), str(h.get("productName")),
                dec(h.get("orderQuantity")), unitOfOrder(c), date(h.get("deliveryDate")),
                str(h.get("status")), ts(h.get("createdTime")), rehearsal);
    }

    private String unitOfOrder(Collect c) {
        String unit = str(c.headerRow.get("unitName"));
        return unit.isEmpty() ? "台" : unit;
    }

    // ------------------------------------------------------------------ 工单节点

    private List<WorkOrderNode> buildWorkOrderNodes(Collect c) {
        List<WorkOrderNode> nodes = new ArrayList<>();
        for (Map<String, Object> row : c.workOrders) {
            Long woId = lng(row.get("workOrderId"));
            LocalDateTime actualStart = workOrderActualStart(c, woId);
            LocalDateTime actualEnd = workOrderActualEnd(c, woId);
            String startType = actualStart == null ? TIME_MISSING : TIME_LOGGED;
            if (actualStart != null && firstTransitionTime(c.workOrderLog.get(woId), "RUNNING") == null) {
                startType = TIME_DERIVED;
            }
            nodes.add(new WorkOrderNode(woId, str(row.get("workOrderNo")), str(row.get("workOrderType")),
                    lng(row.get("parentWorkOrderId")), lng(row.get("rootWorkOrderId")),
                    lng(row.get("productId")), lng(row.get("materialId")),
                    str(row.get("productCode")), str(row.get("productName")), str(row.get("status")),
                    scale(dec(row.get("plannedQuantity"))), reportedQualified(c, woId),
                    releasedQualified(c, woId), inboundQuantity(c, woId), str(row.get("lineName")),
                    c.bomVersions.getOrDefault(woId, ""), c.routeVersions.getOrDefault(woId, ""),
                    c.routeSteps.containsKey(woId),
                    ts(row.get("plannedStartTime")), ts(row.get("plannedEndTime")),
                    actualStart, actualEnd, startType, ownerOf(c, woId, startType)));
        }
        return nodes;
    }

    private Owner ownerOf(Collect c, Long workOrderId, String timeType) {
        List<Map<String,Object>> active=c.dispatchByWorkOrder.getOrDefault(workOrderId,List.of()).stream()
                .filter(d->!Set.of("CLOSED","CANCELLED").contains(str(d.get("status")))).toList();
        if(active.isEmpty())return new Owner("待分配","待分配","","NO_DISPATCH");
        String teams=active.stream().map(d->str(d.get("teamName"))).filter(v->!v.isEmpty()).distinct().collect(Collectors.joining("、"));
        String leaders=active.stream().map(d->str(d.get("leaderName"))).filter(v->!v.isEmpty()).distinct().collect(Collectors.joining("、"));
        return new Owner(teams.isEmpty()?"待分配":teams,leaders.isEmpty()?"待分配":leaders,"","DISPATCH");
    }

    /** 工单产出合格量：只认产出工序（冻结路线末工序）的已审核报工，多工序不能相加。 */
    private BigDecimal reportedQualified(Collect c, Long workOrderId) {
        Long outputOp = c.outputOperation.get(workOrderId);
        if (outputOp == null) {
            return lotQualified(c, workOrderId);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> task : c.taskByWorkOrder.getOrDefault(workOrderId, List.of())) {
            if (!Objects.equals(lng(task.get("operationId")), outputOp)
                    || !validOutputRecovery(task, c.taskByWorkOrder.getOrDefault(workOrderId, List.of()))) continue;
            total = total.add(approvedQualified(c, task));
        }
        MfgWorkOrder order = c.entities.get(workOrderId);
        return scale(order == null ? total : total.min(dec(order.getPlannedQuantity())));
    }

    private boolean validOutputRecovery(Map<String, Object> task, List<Map<String, Object>> family) {
        Set<Long> visited = new LinkedHashSet<>();
        Map<String, Object> current = task;
        while ("REWORK".equals(str(current.get("taskType")))) {
            if (!visited.add(lng(current.get("taskId")))) return false;
            Long sourceId = lng(current.get("sourceTaskId"));
            Map<String, Object> source = family.stream()
                    .filter(t -> Objects.equals(lng(t.get("taskId")), sourceId)).findFirst().orElse(null);
            if (source == null || !Objects.equals(source.get("operationId"), task.get("operationId"))
                    || !Objects.equals(source.get("dispatchId"), task.get("dispatchId"))) return false;
            current = source;
        }
        return true;
    }

    /** 该任务下已审核报工的合格量。 */
    private BigDecimal approvedQualified(Collect c, Map<String, Object> task) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> report : c.reportByTask.getOrDefault(lng(task.get("taskId")), List.of())) {
            if ("APPROVED".equals(str(report.get("auditStatus")))) {
                total = total.add(dec(report.get("qualifiedQuantity")));
            }
        }
        return total;
    }

    /** 快照不可用时不猜产出工序，改用产品批次合格量；绝不把多工序报工相加。 */
    private BigDecimal lotQualified(Collect c, Long workOrderId) {
        BigDecimal byLot = BigDecimal.ZERO;
        for (Map<String, Object> lot : c.lotByWorkOrder.getOrDefault(workOrderId, List.of())) {
            byLot = byLot.add(dec(lot.get("qualifiedQuantity")));
        }
        return scale(byLot);
    }

    /** 是否产出工序上的有效报工任务：工序必须等于产出工序，返工任务不计入产出。 */
    private boolean isOutputTask(Map<String, Object> task, Long outputOp) {
        return Objects.equals(lng(task.get("operationId")), outputOp)
                && !"REWORK".equals(str(task.get("taskType")));
    }

    /** 质量放行量：以产品批次 + 正式终检通过为依据，APPROVED 报工不代表放行。 */
    private BigDecimal releasedQualified(Collect c, Long workOrderId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String,Object> lot : c.lotByWorkOrder.getOrDefault(workOrderId,List.of())) {
            Map<String,Object> q = effectiveFinal(c,lng(lot.get("productLotId")));
            if (q == null || !"FINISHED".equals(str(q.get("status")))) continue;
            String result = str(q.get("inspectionResult"));
            if (!Set.of("PASS","CONCESSION").contains(result) && !approvedDisposition(c,lng(lot.get("productLotId")),"CONCESSION")
                    && !resolvedPartialLot(c,lot)) continue;
            if (Set.of("FAIL","REWORK","DOWNGRADE","SCRAPPED").contains(str(lot.get("status")))) continue;
            total = total.add(("CONCESSION".equals(result) || approvedDisposition(c,lng(lot.get("productLotId")),"CONCESSION"))
                    ? dec(lot.get("batchQuantity")) : dec(lot.get("qualifiedQuantity")));
        }
        return scale(total);
    }

    private boolean resolvedPartialLot(Collect c,Map<String,Object> lot) {
        Long lotId=lng(lot.get("productLotId"));
        return Set.of("PASS","STORED").contains(str(lot.get("status"))) && dec(lot.get("qualifiedQuantity")).signum()>0
                && java.util.stream.Stream.of("SCRAP","REWORK","DOWNGRADE").anyMatch(type->approvedDisposition(c,lotId,type));
    }

    private BigDecimal inboundQuantity(Collect c, Long workOrderId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> lot : c.lotByWorkOrder.getOrDefault(workOrderId, List.of())) {
            for (Map<String, Object> in : approvedInboundOfLot(c, lot)) {
                total = total.add(dec(in.get("inboundQuantity")));
            }
        }
        return scale(total);
    }

    /** 某批次下已审核的生产入库单。 */
    private List<Map<String, Object>> approvedInboundOfLot(Collect c, Map<String, Object> lot) {
        return c.inboundByLot.getOrDefault(lng(lot.get("productLotId")), List.of()).stream()
                .filter(in -> "APPROVED".equals(str(in.get("auditStatus")))).toList();
    }

    private LocalDateTime earliestReportTime(Collect c, Long workOrderId) {
        LocalDateTime best = null;
        for (Map<String, Object> report : approvedReports(c, workOrderId)) {
            LocalDateTime at = ts(report.get("reportTime"));
            if (at != null && (best == null || at.isBefore(best))) best = at;
        }
        return best;
    }

    private LocalDateTime latestReportTime(Collect c, Long workOrderId) {
        LocalDateTime best = null;
        for (Map<String, Object> report : approvedReports(c, workOrderId)) {
            LocalDateTime at = ts(report.get("reportTime"));
            if (at != null && (best == null || at.isAfter(best))) best = at;
        }
        return best;
    }

    /** 工单下所有工序的已审核报工，按任务展开。 */
    private List<Map<String, Object>> approvedReports(Collect c, Long workOrderId) {
        return c.taskByWorkOrder.getOrDefault(workOrderId, List.of()).stream()
                .flatMap(task -> c.reportByTask.getOrDefault(lng(task.get("taskId")), List.<Map<String, Object>>of()).stream())
                .filter(report -> "APPROVED".equals(str(report.get("auditStatus"))))
                .toList();
    }

    // ------------------------------------------------------------------ 用料覆盖

    private List<MaterialCoverage> buildMaterials(Collect c, Map<Long, BigDecimal> inspectionStock) {
        // 公共合格可用量按物料汇总一次，再在本次订单的各需求间顺序分配一次，避免重复承诺。
        List<Long> materialIds = c.requirementMaterialIds();
        Map<Long, BigDecimal> publicPool = materialIds.isEmpty() ? Map.of()
                : new LinkedHashMap<>(supplyPlanning.qualifiedAvailable(materialIds));
        Map<Long, BigDecimal> lockedPool = new LinkedHashMap<>();
        Map<String,BigDecimal> returnedPool = new LinkedHashMap<>();
        for (Map<String,Object> r : c.returns) returnedPool.merge(
                r.get("workOrderId") + ":" + r.get("materialId"), dec(r.get("returnQuantity")),BigDecimal::add);
        Map<Long, BigDecimal> remainPublic = new LinkedHashMap<>();
        publicPool.forEach((k, v) -> remainPublic.put(k, scale(v)));

        List<Map<String, Object>> ordered = new ArrayList<>(c.requirements);
        ordered.sort(Comparator.comparing(r -> lng(r.get("requirementId"))));

        List<MaterialCoverage> result = new ArrayList<>();
        for (Map<String, Object> r : ordered) {
            result.add(coverageOf(c, r, publicPool, remainPublic, lockedPool, returnedPool, inspectionStock));
        }
        return result;
    }

    /** 单条需求的用料覆盖：原始需求基线、已发已退、预留、公共池分配与缺口，全部按同一口径算一次。 */
    private MaterialCoverage coverageOf(Collect c, Map<String, Object> r, Map<Long, BigDecimal> publicPool,
                                        Map<Long, BigDecimal> remainPublic, Map<Long, BigDecimal> lockedPool,
                                        Map<String,BigDecimal> returnedPool, Map<Long, BigDecimal> inspectionStock) {
        Long requirementId = lng(r.get("requirementId"));
        Long workOrderId = lng(r.get("workOrderId"));
        Long materialId = lng(r.get("materialId"));
        BigDecimal required = dec(r.get("requiredQuantity"));
        BigDecimal originalRequired = originalRequiredOf(c, workOrderId, materialId, requirementId, required);
        BigDecimal supplementRequired = supplementRequiredOf(c, requirementId, required, originalRequired);
        BigDecimal issued = netIssued(r, workOrderId, materialId, returnedPool);
        BigDecimal reserved = reservedForRequirement(c, requirementId, lockedPool);

        // 已发料已经覆盖转线边与已消耗部分，公共池只补剩余缺口，同一物料只扣一次。
        BigDecimal covered = issued.add(reserved);
        BigDecimal gap = required.subtract(covered).max(BigDecimal.ZERO);
        BigDecimal publicAvailable = remainPublic.getOrDefault(materialId, BigDecimal.ZERO);
        BigDecimal freeAllocated = allocateFromPublic(remainPublic, materialId, gap, publicAvailable);
        BigDecimal waitingInspection = inspectionStock.getOrDefault(materialId, BigDecimal.ZERO);
        BigDecimal shortage = required.subtract(covered).subtract(freeAllocated).max(BigDecimal.ZERO);

        SupplySources supply = supplySourcesOf(c, r, requirementId, unit(r));
        BigDecimal perUnit = perUnitUsage(c, workOrderId, required);
        BigDecimal available = covered.add(freeAllocated);
        BigDecimal maxAssemblable = perUnit.signum() <= 0 ? null
                : scale(available.divide(perUnit, 0, RoundingMode.DOWN));
        return new MaterialCoverage(requirementId, workOrderId, c.workOrderNo(workOrderId),
                materialId, str(r.get("materialCode")), str(r.get("materialName")),
                str(r.get("materialType")), unit(r), scale(required), scale(issued), scale(reserved),
                scale(freeAllocated), scale(remainPublic.getOrDefault(materialId, BigDecimal.ZERO)),
                scale(waitingInspection), scale(shortage), scale(supply.plannedSupply()), maxAssemblable,
                coverageStatus(shortage, covered, freeAllocated), supply.sourceWorkOrderIds(), supply.sources(),
                scale(publicPool.getOrDefault(materialId, BigDecimal.ZERO)),
                originalRequired == null ? null : scale(originalRequired),
                supplementRequired == null ? null : scale(supplementRequired));
    }

    /** 原始需求固定取已验证的冻结BOM和工单原计划数量，不能把报废补投后的累计需求当成订单基线。 */
    private BigDecimal originalRequiredOf(Collect c, Long workOrderId, Long materialId, Long requirementId,
                                          BigDecimal required) {
        Map<Long, BigDecimal> originalDemands = c.originalMaterialDemands.get(workOrderId);
        BigDecimal originalRequired = originalDemands == null ? null
                : originalDemands.getOrDefault(materialId, BigDecimal.ZERO);
        if (originalRequired == null && isInitialDraftWithoutSnapshot(c, workOrderId)) {
            return required;
        }
        return originalRequired;
    }

    /** 尚未下达且从未生成任务的初始草稿没有执行快照，此时生成时的需求本身就是基线。 */
    private static boolean isInitialDraftWithoutSnapshot(Collect c, Long workOrderId) {
        MfgWorkOrder entity = c.entities.get(workOrderId);
        if (entity == null || !"DRAFT".equals(entity.getStatus())) return false;
        if (entity.getSnapshotJson() != null) return false;
        return c.tasks.stream().noneMatch(t -> Objects.equals(workOrderId, lng(t.get("workOrderId"))));
    }

    /** 报废补投后的累计需求减去冻结基线即为追加需求；少于基线说明数据不一致。 */
    private static BigDecimal supplementRequiredOf(Collect c, Long requirementId, BigDecimal required,
                                                   BigDecimal originalRequired) {
        if (originalRequired == null) return null;
        BigDecimal supplementRequired = required.subtract(originalRequired);
        if (supplementRequired.signum() < 0) {
            c.missing.add("需求 " + requirementId + " 的累计需求少于冻结BOM原始需求，追加用料无法核实");
            return null;
        }
        return supplementRequired;
    }

    /** 已发数量要扣掉已退回部分，退回来的料不能再算作已覆盖。 */
    private static BigDecimal netIssued(Map<String, Object> r, Long workOrderId, Long materialId,
                                        Map<String,BigDecimal> returnedPool) {
        String returnKey = workOrderId + ":" + materialId;
        BigDecimal grossIssued = dec(r.get("issuedQuantity"));
        BigDecimal available = returnedPool.getOrDefault(returnKey, BigDecimal.ZERO);
        BigDecimal returned = available.min(grossIssued);
        returnedPool.put(returnKey, available.subtract(returned));
        return grossIssued.subtract(returned).max(BigDecimal.ZERO);
    }

    private static BigDecimal allocateFromPublic(Map<Long, BigDecimal> remainPublic, Long materialId,
                                                 BigDecimal gap, BigDecimal publicAvailable) {
        BigDecimal freeAllocated = gap.min(publicAvailable).max(BigDecimal.ZERO);
        remainPublic.put(materialId, publicAvailable.subtract(freeAllocated).max(BigDecimal.ZERO));
        return freeAllocated;
    }

    private static String coverageStatus(BigDecimal shortage, BigDecimal covered, BigDecimal freeAllocated) {
        if (shortage.signum() == 0) return "COVERED";
        if (covered.signum() > 0 || freeAllocated.signum() > 0) return "PARTIAL";
        return "SHORT";
    }

    /** 单条需求的预留量：只认已锁定且合格库存足够的预留分配，超出部分记入数据质量说明。 */
    private BigDecimal reservedForRequirement(Collect c, Long requirementId, Map<Long, BigDecimal> lockedPool) {
        BigDecimal reserved = BigDecimal.ZERO;
        for (Map<String, Object> a : c.allocByRequirement.getOrDefault(requirementId, List.of())) {
            if (!"RESERVED".equals(str(a.get("status")))) continue;
            Long inventoryId = lng(a.get("inventoryId"));
            BigDecimal asked = dec(a.get("reservedQuantity"));
            BigDecimal locked = lockedPool.computeIfAbsent(inventoryId, k -> dec(a.get("lockedQuantity")));
            boolean eligible = dec(a.get("eligibleReservation")).signum() > 0;
            BigDecimal accepted = eligible ? asked.min(locked).max(BigDecimal.ZERO) : BigDecimal.ZERO;
            reserved = reserved.add(accepted);
            lockedPool.put(inventoryId, locked.subtract(accepted).max(BigDecimal.ZERO));
            if (accepted.compareTo(asked) < 0) c.missing.add("需求 " + requirementId
                    + " 的预留分配与合格库存锁定不一致，未核实部分未计入覆盖");
        }
        return reserved;
    }

    /** 组件补产来源：汇总计划量，收集来源工单与证据，无来源时回退到需求上的历史来源工单。 */
    private SupplySources supplySourcesOf(Collect c, Map<String, Object> r, Long requirementId, String unitName) {
        BigDecimal plannedSupply = BigDecimal.ZERO;
        List<Long> sourceWorkOrderIds = new ArrayList<>();
        List<Document> sources = new ArrayList<>();
        for (Map<String, Object> s : c.sourceByRequirement.getOrDefault(requirementId, List.of())) {
            plannedSupply = plannedSupply.add(dec(s.get("plannedQuantity")));
            Long sourceWo = lng(s.get("workOrderId"));
            if (sourceWo != null && !sourceWorkOrderIds.contains(sourceWo)) sourceWorkOrderIds.add(sourceWo);
            sources.add(document("SUPPLY_SOURCE", lng(s.get("sourceId")), str(s.get("sourceNo")),
                    str(s.get("status")), dec(s.get("plannedQuantity")), unitName, null,
                    "mfg-work-order", sourceWo, "组件补产来源", "", sourceWo));
        }
        if (sourceWorkOrderIds.isEmpty()) {
            Long legacy = lng(r.get("sourceWorkOrderId"));
            if (legacy != null && legacy > 0) sourceWorkOrderIds.add(legacy);
        }
        return new SupplySources(plannedSupply, List.copyOf(sourceWorkOrderIds), List.copyOf(sources));
    }

    private record SupplySources(BigDecimal plannedSupply, List<Long> sourceWorkOrderIds, List<Document> sources) {
    }

    /** 单台用量：用料需求按工单计划量展开，用计划量还原单位用量。 */
    private BigDecimal perUnitUsage(Collect c, Long workOrderId, BigDecimal requiredQuantity) {
        BigDecimal planned = null;
        for (Map<String, Object> row : c.workOrders) {
            if (Objects.equals(lng(row.get("workOrderId")), workOrderId)) {
                planned = dec(row.get("plannedQuantity"));
                break;
            }
        }
        if (planned == null || planned.signum() <= 0 || requiredQuantity.signum() <= 0) return BigDecimal.ZERO;
        return requiredQuantity.divide(planned, 6, RoundingMode.HALF_UP);
    }

    private String unit(Map<String, Object> row) {
        String unit = str(row.get("unitName"));
        return unit.isEmpty() ? str(row.get("unitCode")) : unit;
    }

    // ------------------------------------------------------------------ 八阶段

    private List<Stage> buildStages(Collect c, OrderHeader order, List<MaterialCoverage> materials,
            Map<String, List<Blocker>> equipmentBlockers, LocalDateTime now) {
        List<Stage> stages = new ArrayList<>();
        stages.add(stageRelease(c, order));
        stages.add(stageSchedule(c, order));
        stages.add(stageMaterial(c, order));
        stages.add(stageComponent(c, order, materials, equipmentBlockers.getOrDefault("COMPONENT", List.of())));
        stages.add(stageAssembly(c, order, materials, equipmentBlockers.getOrDefault("ASSEMBLY", List.of())));
        stages.add(stageFinalInspection(c, order, equipmentBlockers.getOrDefault("FINAL_INSPECTION",List.of())));
        stages.add(stageInbound(c, order));
        stages.add(stageShipment(c, order));
        addOverdueRisk(c,order,stages);
        return stages;
    }

    private String orderNo(Collect c) {
        return str(c.headerRow.get("productionOrderNo"));
    }

    private Document orderDocument(Collect c, String relation) {
        return document("PRODUCTION_ORDER", lng(c.headerRow.get("productionOrderId")),
                str(c.headerRow.get("productionOrderNo")), str(c.headerRow.get("status")),
                dec(c.headerRow.get("orderQuantity")), unitOfOrder(c), ts(c.headerRow.get("createdTime")),
                "mfg-production-order", null, relation, "", null);
    }

    private Stage stageRelease(Collect c, OrderHeader order) {
        boolean draft = "DRAFT".equals(order.status());
        BigDecimal planned = order.orderQuantity();
        BigDecimal executed = draft ? BigDecimal.ZERO : planned;
        List<String> notes = new ArrayList<>();
        if (draft) notes.add("订单仍为草稿，尚未下达；后续阶段保持未开始");
        LocalDateTime releasedAt = firstTransitionTime(c.orderLogs,"RELEASED");
        // 草稿订单还没下达，实际起止时间一律留空，时间口径记为缺失。
        LocalDateTime actualStart = draft ? null : releasedAt;
        String actualTimeType = releasedAt == null ? TIME_MISSING : TIME_LOGGED;
        return new Stage("ORDER_RELEASE", 1, "订单下发", unitOfOrder(c),
                draft ? T_NOT_STARTED : T_DONE, scale(planned), scale(executed), scale(executed),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                scale(planned.subtract(executed)), null, null, actualStart,
                actualStart, actualTimeType,
                new Owner("销售与计划", "", "", "ORDER"), List.of(), List.of(),
                List.of(orderDocument(c, "订单下达")), notes);
    }

    private Stage stageSchedule(Collect c, OrderHeader order) {
        List<Map<String,Object>> roots = c.plans.stream().filter(p -> "ROOT".equals(str(p.get("planType")))
                && !"CLOSED".equals(str(p.get("status")))).toList();
        BigDecimal scheduled = releasedPlanQuantity(roots);
        BigDecimal left = order.orderQuantity().subtract(scheduled).max(BigDecimal.ZERO);
        return new Stage("SCHEDULE",2,"排产安排",unitOfOrder(c),scheduleStatus(scheduled, left),
                scale(order.orderQuantity()),scale(scheduled),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,scale(left),
                minTime(roots,"plannedStartTime"),maxTime(roots,"plannedEndTime"),null,null,TIME_MISSING,
                new Owner("计划","","","PLAN"),List.of(),List.of(),planDocuments(c),
                scheduleNotes());
    }

    /** 已排产数量：只算已生效（RELEASED/RUNNING/FINISHED）的根计划，草稿计划待确认不计入。 */
    private BigDecimal releasedPlanQuantity(List<Map<String,Object>> roots) {
        return roots.stream()
                .filter(r -> Set.of("RELEASED","RUNNING","FINISHED").contains(str(r.get("status"))))
                .map(r -> dec(r.get("plannedQuantity")))
                .reduce(BigDecimal.ZERO,BigDecimal::add);
    }

    /** 排产阶段状态：没排是未开始，排了但没排满是风险，排满算完成。 */
    private static String scheduleStatus(BigDecimal scheduled, BigDecimal left) {
        if (scheduled.signum() == 0) {
            return T_NOT_STARTED;
        }
        return left.signum() > 0 ? T_RISK : T_DONE;
    }

    /** 计划单据清单。 */
    private List<Document> planDocuments(Collect c) {
        return c.plans.stream().map(p -> document("PRODUCTION_PLAN",lng(p.get("planId")),
                str(p.get("planNo")),str(p.get("status")),dec(p.get("plannedQuantity")),unitOfOrder(c),
                ts(p.get("plannedStartTime")),"mfg-production-plan",null,"生产计划",
                str(p.get("lineName")),null)).toList();
    }

    /** 排产阶段的固定口径说明。 */
    private static List<String> scheduleNotes() {
        return List.of("只统计全部已生效整机计划；草稿计划待确认，不计入已排产数量。"
                + "排产不代表物料、设备、人员和质量资源已全部落实");
    }

    private Stage stageMaterial(Collect c, OrderHeader order) {
        // 备料阶段以齐套预览的口径为准，不使用装配阶段按工单展开的物料清单。
        List<MaterialCoverage> materials = c.preparationMaterials;
        int kinds = materials.size();
        int coveredKinds = (int) materials.stream().filter(m -> m.shortageQuantity().signum() == 0).count();
        int partial = kinds - coveredKinds;
        BigDecimal waiting = materials.stream().map(MaterialCoverage::waitingInspectionQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> notes = materialNotes(kinds, coveredKinds, partial, c);
        return new Stage("MATERIAL_PREP", 3, "备料齐套", "项",
                materialStatus(order, kinds, partial, coveredKinds, c),
                scale(new BigDecimal(kinds)), scale(new BigDecimal(coveredKinds)), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, scale(waiting), BigDecimal.ZERO,
                scale(new BigDecimal(partial)), null, null, null, null, TIME_MISSING,
                new Owner("仓储", "", "", "WMS"), List.of(), List.of(),
                List.copyOf(issueDocuments(c).values()), notes);
    }

    /** 备料阶段的固定口径说明与缺口提示。 */
    private List<String> materialNotes(int kinds, int coveredKinds, int partial, Collect c) {
        List<String> notes = new ArrayList<>();
        notes.add(kinds == 0 ? "尚无可核对的有效计划或用料需求；请检查计划与BOM"
                : "共 " + kinds + " 项物料需求，齐套 " + coveredKinds + " 项"
                        + "（已发料 + 本单预留 + 本单可用公共合格库存）");
        notes.add("不同单位的物料不相加；明细见物料覆盖清单");
        notes.add("基础物料与半成品分别展示；半成品缺口按子BOM展开。"
                + "计划预览不预留库存、不代表已领料，生成工单后使用实际冻结需求");
        notes.addAll(c.missing.stream().filter(n -> n.contains("齐套预览未完成")).toList());
        if (partial > 0 && coveredKinds == 0) {
            // 备料阶段本身不产生红灯：红灯只由带单据编号的阻塞项给出，此处仅作风险提示。
            notes.add("全部用料需求均未覆盖，组件与整机都不能开工，阻塞原因见装配阶段的缺料项");
        }
        return notes;
    }

    /** 备料阶段状态：没有需求时草稿未开始、否则风险；需求全覆盖算完成；有覆盖但不全算风险。 */
    private static String materialStatus(OrderHeader order, int kinds, int partial, int coveredKinds, Collect c) {
        if (kinds == 0) return draftOrRisk(order);
        if (partial == 0 && !previewIncomplete(c)) return T_DONE;
        // 有部分物料齐套时只能算风险，不能算未开始；一项都不齐才是真正的未开工。
        return coveredKinds > 0 ? T_RISK : draftOrRisk(order);
    }

    /** 草稿保持未开始，已生效的计划缺料就是风险。 */
    private static String draftOrRisk(OrderHeader order) {
        return "DRAFT".equals(order.status()) ? T_NOT_STARTED : T_RISK;
    }

    private static boolean previewIncomplete(Collect c) {
        return c.missing.stream().anyMatch(n -> n.contains("齐套预览未完成"));
    }

    /**
     * 领料出库单据，按单据汇总明细量。
     *
     * <p>同一单据常有多种物料、单位不同，因此只给合计量并留空单位，
     * 不把颗、套、米相加后仍标成某个单位；逐物料的数量在物料覆盖清单里。</p>
     */
    private Map<String, Document> issueDocuments(Collect c) {
        Map<Long, BigDecimal> issueQty = new LinkedHashMap<>();
        for (List<Map<String, Object>> issues : c.issueByWorkOrder.values()) {
            for (Map<String, Object> i : issues) {
                issueQty.merge(lng(i.get("outboundId")), dec(i.get("outboundQuantity")), BigDecimal::add);
            }
        }
        Map<String, Document> issueDocs = new LinkedHashMap<>();
        for (List<Map<String, Object>> issues : c.issueByWorkOrder.values()) {
            for (Map<String, Object> i : issues) {
                Long id = lng(i.get("outboundId"));
                issueDocs.putIfAbsent("OUT:" + id, document("MATERIAL_ISSUE", id, str(i.get("outboundNo")),
                        str(i.get("auditStatus")), scale(issueQty.getOrDefault(id, BigDecimal.ZERO)), "",
                        ts(i.get("outboundTime")),
                        "wms-outbound-order", lng(i.get("workOrderId")), "领料出库", "", null));
            }
        }
        return issueDocs;
    }

    private Stage stageComponent(Collect c, OrderHeader order, List<MaterialCoverage> materials,
            List<Blocker> equipmentBlockers) {
        List<Stage> children = new ArrayList<>();
        BigDecimal plannedTotal = BigDecimal.ZERO;
        BigDecimal executedTotal = BigDecimal.ZERO;
        BigDecimal releasedTotal = BigDecimal.ZERO;
        BigDecimal remainingTotal = BigDecimal.ZERO;
        List<Document> docs = new ArrayList<>();
        boolean anyBlocked = false;
        boolean anyStarted = false;

        for (Map<String, Object> wo : c.workOrders) {
            if (!"COMPONENT".equals(str(wo.get("workOrderType")))) continue;
            if (Set.of("CLOSED","CANCELLED").contains(str(wo.get("status")))) continue;
            ComponentStage built = buildComponentStage(c, wo, materials, equipmentBlockers, children.size());
            plannedTotal = plannedTotal.add(built.planned());
            executedTotal = executedTotal.add(built.executed());
            releasedTotal = releasedTotal.add(built.released());
            remainingTotal = remainingTotal.add(built.remaining());
            docs.addAll(built.docs());
            if (built.blocked()) anyBlocked = true;
            if (built.started()) anyStarted = true;
            children.add(built.stage());
        }

        // 已生效计划的BOM预览也能证明组件仍需生产；没有生成工单不等于不适用。
        // 有真实工单的计划已由预览服务排除，避免把预测缺口与真实组件工单重复累计。
        List<MaterialCoverage> pending = c.semiFinishedMaterials.stream()
                .filter(m -> m.workOrderId() == null && m.shortageQuantity().signum() > 0)
                .toList();
        for (MaterialCoverage material : pending) {
            plannedTotal = plannedTotal.add(material.shortageQuantity());
            remainingTotal = remainingTotal.add(material.shortageQuantity());
        }
        String status = componentStageStatus(order, children, pending, anyBlocked, anyStarted);
        List<String> notes = componentNotes(children, pending);
        return new Stage("COMPONENT", 4, "组件生产", "套", status, scale(plannedTotal), scale(executedTotal),
                scale(executedTotal), scale(releasedTotal), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                scale(remainingTotal), null, null, null, null, TIME_MISSING,
                new Owner("组件班组", "", "", "DISPATCH"), children, List.of(), docs, notes);
    }

    /** 组件阶段状态：无工单也无待生产需求时按订单状态给未开始或不适用的统一口径。 */
    private static String componentStageStatus(OrderHeader order, List<Stage> children, List<MaterialCoverage> pending,
                                               boolean anyBlocked, boolean anyStarted) {
        String status;
        if (children.isEmpty()) {
            boolean needed = !pending.isEmpty() || "DRAFT".equals(order.status());
            status = needed ? T_NOT_STARTED : T_NA;
        } else if (anyBlocked) {
            status = T_BLOCKED;
        } else if (pending.isEmpty() && children.stream().allMatch(st -> T_DONE.equals(st.status()))) {
            status = T_DONE;
        } else if (children.stream().anyMatch(st -> T_RISK.equals(st.status()))) {
            status = T_RISK;
        } else {
            status = anyStarted ? T_RUNNING : T_NOT_STARTED;
        }
        return status;
    }

    /** 组件阶段的口径说明：没有工单时说明为什么不适用，有待生产需求时逐项列出。 */
    private static List<String> componentNotes(List<Stage> children, List<MaterialCoverage> pending) {
        List<String> notes = new ArrayList<>();
        if (children.isEmpty() && pending.isEmpty()) notes.add("当前无需组件补产，组件由现货供给或尚无可核对的有效计划需求");
        if (!children.isEmpty()) notes.add("灯头、灯臂、底座并行生产，可分别完成，不要求同步");
        if (!pending.isEmpty()) {
            notes.add("已生效计划仍有组件待生产，尚未生成相应工单，不代表已开工；先生成工单再安排派工");
            for (MaterialCoverage material : pending) {
                notes.add(material.materialName() + "：待生产 " + plain(material.shortageQuantity()) + material.unit());
            }
        }
        return notes;
    }

    /** 单张组件工单的阶段节点：同时回传累加用的量与启用状态，避免在主循环里重复展开。 */
    private ComponentStage buildComponentStage(Collect c, Map<String, Object> wo, List<MaterialCoverage> materials,
            List<Blocker> equipmentBlockers, int index) {
        Long woId = lng(wo.get("workOrderId"));
        BigDecimal planned = dec(wo.get("plannedQuantity"));
        BigDecimal executed = reportedQualified(c, woId);
        BigDecimal released = releasedQualified(c, woId);

        List<Blocker> blockers = new ArrayList<>(componentBlockers(c, wo, planned, executed, materials));
        for (Blocker b : equipmentBlockers) {
            if (impactsComponentWorkOrder(c, b, woId)) blockers.add(b);
        }
        boolean blocked = hasRedBlocker(blockers);
        boolean started = executed.signum() > 0 || "RUNNING".equals(str(wo.get("status")));

        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> l : c.lotByWorkOrder.getOrDefault(woId, List.of())) {
            docs.add(document("PRODUCT_LOT", lng(l.get("productLotId")), str(l.get("lotNo")),
                    str(l.get("status")), dec(l.get("qualifiedQuantity")), "套", ts(l.get("createdTime")),
                    "trace-product-lot", woId, "组件产出批次", "", null));
        }
        String timeType = actualTimeType(c, woId);
        Stage stage = new Stage("COMPONENT_" + woId, (int) (1 + index),
                str(wo.get("productName")), "套", stageStatus(wo, planned, executed, blockers),
                scale(planned), scale(executed), scale(executed), scale(released),
                waitingAudit(c, woId), waitingInspection(c, woId), defectQuantity(c, woId),
                scale(planned.subtract(executed).max(BigDecimal.ZERO)),
                ts(wo.get("plannedStartTime")), ts(wo.get("plannedEndTime")),
                workOrderActualStart(c, woId), workOrderActualEnd(c, woId), timeType,
                ownerOf(c, woId, timeType), List.of(), blockers, documentsOf(c,woId), new ArrayList<>());
        return new ComponentStage(stage, planned, executed, released,
                planned.subtract(executed).max(BigDecimal.ZERO), docs, blocked, started);
    }

    /** 设备故障是否波及该组件工单：工单下仍有未结束任务，且阻塞项明确列出该工单。 */
    private boolean impactsComponentWorkOrder(Collect c, Blocker b, Long woId) {
        boolean pending = c.tasks.stream()
                .filter(t -> Objects.equals(lng(t.get("workOrderId")), woId)
                        && !"FINISHED".equals(str(t.get("status")))
                        && !"CANCELLED".equals(str(t.get("status"))))
                .findFirst().orElse(null) != null;
        return pending && b.impact().affectedWorkOrders().contains(c.workOrderNo(woId));
    }

    /** 组件阶段状态：无组件时按订单状态给未开始或不适用的统一口径。 */
    private String componentStatus(OrderHeader order, List<Stage> children, boolean anyBlocked, boolean anyStarted) {
        if (children.isEmpty()) {
            return "DRAFT".equals(order.status()) ? T_NOT_STARTED : T_NA;
        }
        return componentChildrenStatus(children, anyBlocked, anyStarted);
    }

    /** 有组件工单时的汇总口径：有红阻塞优先，其次全部完成、存在风险、已开工、未开始。 */
    private String componentChildrenStatus(List<Stage> children, boolean anyBlocked, boolean anyStarted) {
        String status;
        if (anyBlocked) {
            status = T_BLOCKED;
        } else if (children.stream().allMatch(st -> T_DONE.equals(st.status()))) {
            status = T_DONE;
        } else if (children.stream().anyMatch(st -> T_RISK.equals(st.status()))) {
            status = T_RISK;
        } else {
            status = anyStarted ? T_RUNNING : T_NOT_STARTED;
        }
        return status;
    }

    private record ComponentStage(Stage stage, BigDecimal planned, BigDecimal executed, BigDecimal released,
            BigDecimal remaining, List<Document> docs, boolean blocked, boolean started) {
    }

    private Stage stageAssembly(Collect c, OrderHeader order, List<MaterialCoverage> materials,
            List<Blocker> equipmentBlockers) {
        List<Stage> rows = c.rootWorkOrders().stream().map(root -> assemblyOf(c,order,materials,equipmentBlockers,root)).toList();
        return aggregate("ASSEMBLY",5,"整机装配",unitOfOrder(c),order.orderQuantity(),rows,
                rows.isEmpty()?List.of("尚未生成整机工单"):List.of("按全部整机工单的装配节点汇总；已装配与最终生产产出分别计量"));
    }

    private Stage assemblyOf(Collect c, OrderHeader order, List<MaterialCoverage> materials,
            List<Blocker> equipmentBlockers, Map<String,Object> root) {
        Long woId = lng(root.get("workOrderId"));
        BigDecimal planned = dec(root.get("plannedQuantity"));
        BigDecimal executed = assemblyQuantity(c, woId);

        List<Blocker> blockers = equipmentBlockers.stream().filter(b ->
                b.impact().affectedWorkOrders().contains(c.workOrderNo(woId))).collect(Collectors.toCollection(ArrayList::new));
        BigDecimal remainingRoot = planned.subtract(executed).max(BigDecimal.ZERO);
        List<String> waitingComponents = new ArrayList<>();
        for (MaterialCoverage m : materials) {
            if (!Objects.equals(m.workOrderId(), woId) || m.shortageQuantity().signum() <= 0 || remainingRoot.signum()<=0) continue;
            classifyShortage(c, order, root, woId, remainingRoot, m, blockers, waitingComponents);
        }

        List<String> notes = new ArrayList<>();
        notes.add("装配进度取冻结路线中老化前的装配节点；整机最终产出另按产出节点统计");
        notes.addAll(waitingComponents);
        BigDecimal assemblable = maxAssemblable(c, materials, woId, planned);
        if (assemblable.signum() > 0) {
            notes.add("按当前已发料与本单预留，还能先装 " + plain(assemblable) + " 台");
        }
        String status = assemblyStatus(c, woId, planned, executed, blockers);
        return new Stage("ASSEMBLY_" + woId, 5, "装配 · " + c.workOrderNo(woId), unitOfOrder(c), status,
                scale(planned), scale(executed), scale(executed), releasedQualified(c, woId),
                operationQuantity(c,woId,assemblyOperation(c,woId),"SUBMITTED"), waitingInspection(c, woId), defectQuantity(c, woId),
                scale(planned.subtract(executed).max(BigDecimal.ZERO)),
                ts(root.get("plannedStartTime")), ts(root.get("plannedEndTime")),
                operationTime(c,woId,assemblyOperation(c,woId),"RUNNING"), operationTime(c,woId,assemblyOperation(c,woId),"FINISHED"), TIME_LOGGED,
                ownerOf(c, woId, actualTimeType(c, woId)), List.of(), blockers,
                documentsOf(c, woId), notes);
    }

    /** 装配状态：红阻塞优先，其次装配完成、存在风险、已开工、未开始。 */
    private String assemblyStatus(Collect c, Long woId, BigDecimal planned, BigDecimal executed, List<Blocker> blockers) {
        String status;
        if (blockers.stream().anyMatch(b -> LV_RED.equals(b.level()))) {
            status = T_BLOCKED;
        } else if (executed.compareTo(planned) >= 0) {
            status = T_DONE;
        } else if (!blockers.isEmpty()) {
            status = T_RISK;
        } else if (executed.signum() > 0 || operationStarted(c, woId, assemblyOperation(c, woId))) {
            status = T_RUNNING;
        } else {
            status = T_NOT_STARTED;
        }
        return status;
    }

    /**
     * 一条短缺物料在装配阶段的定性：已放行的组件算待交接，未到开工时间的组件来源算正常前置等待，
     * 其余才视为真实缺口并按不可装台数折算影响量。
     */
    private void classifyShortage(Collect c, OrderHeader order, Map<String,Object> root, Long woId, BigDecimal remainingRoot,
                                  MaterialCoverage m, List<Blocker> blockers, List<String> waitingComponents) {
        List<Long> releasedSources = releasedComponentSources(c, root, m);
        if (!releasedSources.isEmpty()) {
            blockers.add(componentHandoverBlocker(c, order, woId, remainingRoot, m, releasedSources));
            return;
        }
        if (normallyWaitingForComponents(c, root, m)) {
            waitingComponents.add("等待组件生产、检验及交接到位：" + m.materialName()
                    + "，当前库存尚缺 " + plain(m.shortageQuantity()) + m.unit()
                    + "；已有对应生产来源，尚未到整机计划开工时间");
            return;
        }
        BigDecimal perUnit = perUnitUsage(c, woId, m.requiredQuantity());
        BigDecimal blockedUnits = perUnit.signum() <= 0 ? m.shortageQuantity()
                : scale(m.shortageQuantity().divide(perUnit, 0, RoundingMode.UP));
        // 影响量不能超过该整机工单的剩余未完成量，已装配完成的台数不再受本次缺口影响。
        blockers.add(shortageBlocker(c, m, blockedUnits.min(remainingRoot), woId));
    }

    /** 组件已终检放行但还没到整机手边：给出可跳转查看组件产出的黄色阻塞。 */
    private Blocker componentHandoverBlocker(Collect c, OrderHeader order, Long woId, BigDecimal remainingRoot,
                                             MaterialCoverage m, List<Long> releasedSources) {
        return new Blocker("COMPONENT_HANDOVER", "ASSEMBLY", LV_YELLOW, "MATERIAL",
                "待组件交接：" + m.materialName(),
                "对应组件已终检放行；同线组件自动交接，跨线或已送仓的组件按仓库领用。请核对实际到位及线边产出记录。",
                List.of(), new Impact(List.of(c.workOrderNo(woId)), List.of(orderNo(c)), remainingRoot,
                unitOfOrder(c), "完成交接后可继续整机派工"),
                releasedSources.stream().map(id -> new Action("OPEN_COMPONENT_OUTPUT_" + id,
                        "查看组件产出 · " + c.productName(id), "NAVIGATE", "mfg-work-order",
                        Map.<String,Object>of("focus", id, "detail", "outputs"), "")).toList());
    }

    /** 已放行但尚未形成整机可领库存，属于待交接；仍保留真实缺口和派工领料校验。 */
    private List<Long> releasedComponentSources(Collect c, Map<String,Object> root, MaterialCoverage material) {
        Long rootId = lng(root.get("workOrderId"));
        List<Long> sources = new ArrayList<>();
        BigDecimal released = BigDecimal.ZERO;
        for (Long sourceId : material.sourceWorkOrderIds()) {
            Map<String,Object> source = c.workOrders.stream()
                    .filter(w -> Objects.equals(lng(w.get("workOrderId")), sourceId)).findFirst().orElse(null);
            if (!countsAsComponentSource(c, rootId, source, material)) continue;
            BigDecimal assigned = material.sources().stream().filter(d -> Objects.equals(d.workOrderId(), sourceId))
                    .map(Document::quantity).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (material.sources().isEmpty()) assigned = dec(source.get("plannedQuantity"));
            BigDecimal available = releasedQualified(c, sourceId).min(assigned);
            if (available.signum() > 0) { sources.add(sourceId); released = released.add(available); }
        }
        return released.add(material.freeAllocatedQuantity()).compareTo(material.requiredQuantity()) >= 0
                ? sources : List.of();
    }

    /** 供给来源必须同属本整机、是组件工单、且供的正是这条需求的物料，否则不参与组件交接判断。 */
    private boolean countsAsComponentSource(Collect c, Long rootId, Map<String,Object> source, MaterialCoverage material) {
        if (source == null) return false;
        Map<String,Object> parent = parentRoot(c, source);
        boolean sameRoot = parent != null && Objects.equals(lng(parent.get("workOrderId")), rootId);
        boolean componentOrder = "COMPONENT".equals(str(source.get("workOrderType")));
        return sameRoot && componentOrder
                && Objects.equals(lng(source.get("materialId")), material.materialId());
    }

    /** 在整机尚未开工且未到开工时间时，已落实的组件生产来源属于正常前置等待，不是现货。 */
    private boolean normallyWaitingForComponents(Collect c, Map<String, Object> root, MaterialCoverage material) {
        Long rootId = lng(root.get("workOrderId"));
        LocalDateTime assemblyStart = ts(root.get("plannedStartTime"));
        if (assemblyStart == null || !c.evaluatedAt.isBefore(assemblyStart)
                || operationStarted(c, rootId, assemblyOperation(c, rootId))
                || assemblyQuantity(c, rootId).signum() > 0) return false;
        BigDecimal plannedSupply = BigDecimal.ZERO;
        for (Long sourceId : material.sourceWorkOrderIds()) {
            Map<String, Object> source = c.workOrders.stream()
                    .filter(w -> Objects.equals(lng(w.get("workOrderId")), sourceId)).findFirst().orElse(null);
            if (source == null || !"COMPONENT".equals(str(source.get("workOrderType")))
                    || !Objects.equals(lng(source.get("materialId")), material.materialId())) continue;
            Map<String, Object> parent = parentRoot(c, source);
            LocalDateTime componentEnd = ts(source.get("plannedEndTime"));
            if (parent == null || !Objects.equals(lng(parent.get("workOrderId")), rootId)
                    || componentEnd == null || componentEnd.isAfter(assemblyStart)) continue;
            BigDecimal assigned = material.sources().stream()
                    .filter(d -> Objects.equals(d.workOrderId(), sourceId))
                    .map(Document::quantity).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sourcePlanned = dec(source.get("plannedQuantity"));
            // 老数据仅有 source_work_order_id 时，使用对应组件工单数量；新数据遵从供给来源分配量。
            if (material.sources().isEmpty()) assigned = sourcePlanned;
            plannedSupply = plannedSupply.add(assigned.min(sourcePlanned));
        }
        // 计划供给不能当作库存；只有生产来源能覆盖整单需求时才取消缺料红灯。
        return plannedSupply.signum() > 0 && plannedSupply.add(material.freeAllocatedQuantity())
                .compareTo(material.requiredQuantity()) >= 0;
    }

    private Stage agingOf(Collect c, OrderHeader order, Map<String,Object> root) {
        List<JsonNode> steps = root == null ? List.of()
                : c.routeSteps.getOrDefault(lng(root.get("workOrderId")), List.of());
        JsonNode agingStep = steps.stream()
                .filter(s -> "BATCH_EQUIPMENT".equals(s.path("resourceType").asText()))
                .findFirst().orElse(null);
        List<String> notes = new ArrayList<>();
        if (root == null || agingStep == null) {
            notes.add("冻结路线中没有批次设备工序，老化阶段不适用于本订单");
            return new Stage("AGING", 6, "老化检验", unitOfOrder(c), T_NA,
                    scale(order.orderQuantity()), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, null, null, TIME_MISSING, new Owner("总装班组", "", "", "DISPATCH"),
                    List.of(), List.of(), List.of(), notes);
        }
        Long woId = lng(root.get("workOrderId"));
        Long agingOperationId = agingStep.path("operationId").longValue();
        BigDecimal capacity = agingStep.path("batchCapacity").isNumber()
                ? agingStep.path("batchCapacity").decimalValue() : BigDecimal.ZERO;
        BigDecimal minutes = agingStep.path("batchDurationMinutes").isNumber()
                ? agingStep.path("batchDurationMinutes").decimalValue() : BigDecimal.ZERO;

        BigDecimal planned = dec(root.get("plannedQuantity"));
        List<Document> docs = new ArrayList<>();
        AgingTally tally = new AgingTally(BigDecimal.ZERO, BigDecimal.ZERO);
        for (Map<String, Object> task : c.taskByWorkOrder.getOrDefault(woId, List.of())) {
            if (!Objects.equals(lng(task.get("operationId")), agingOperationId)) continue;
            appendAgingTaskEvidence(c, task, woId, capacity, minutes, docs, notes);
            tally = tally.plus(inspectAgingReports(c, task, woId, docs));
        }
        BigDecimal executed = tally.executed();
        BigDecimal waitingAuditQty = tally.waitingAudit();
        notes.add("批容量与批次时长取自冻结工艺快照，是可配置值，不是最终生产标准");
        notes.add("人工报工，未验证连续运行：系统没有设备接口，不能把填写工时当作真实通电时长");

        boolean running = c.taskByWorkOrder.getOrDefault(woId,List.of()).stream()
                .anyMatch(t -> Objects.equals(lng(t.get("operationId")),agingOperationId)
                        && Set.of("RUNNING","PAUSED").contains(str(t.get("status"))));
        String status;
        if (executed.compareTo(planned) >= 0) {
            status = T_DONE;
        } else if (running || executed.signum() > 0) {
            status = T_RUNNING;
        } else {
            status = T_NOT_STARTED;
        }
        return new Stage("AGING_"+woId, 1, "老化记录 · " + c.workOrderNo(woId), unitOfOrder(c), status, scale(planned), scale(executed),
                scale(executed), BigDecimal.ZERO, scale(waitingAuditQty), waitingInspection(c, woId),
                defectQuantity(c, woId), scale(planned.subtract(executed).max(BigDecimal.ZERO)),
                null, null, operationTime(c,woId,agingOperationId,"RUNNING"), operationTime(c,woId,agingOperationId,"FINISHED"),
                TIME_REPORTED, ownerOf(c, woId, TIME_REPORTED), List.of(), List.of(), docs, notes);
    }

    /** 老化任务及其报工的证据文档，同时校验实际记录时长是否满足冻结配置的连续时长。 */
    private void appendAgingTaskEvidence(Collect c, Map<String, Object> task, Long woId, BigDecimal capacity,
            BigDecimal minutes, List<Document> docs, List<String> notes) {
        docs.add(document("TASK", lng(task.get("taskId")), str(task.get("taskNo")), str(task.get("status")),
                dec(task.get("plannedQuantity")), unitOfOrder(c), ts(task.get("createdTime")),
                "mfg-task", woId, "老化工序任务", str(task.get("stationName")), null));
        LocalDateTime start = firstTransitionTime(c.taskLogByTask.get(lng(task.get("taskId"))), "RUNNING");
        LocalDateTime end = lastTransitionTime(c.taskLogByTask.get(lng(task.get("taskId"))), "FINISHED");
        if (start != null && end != null && capacity.signum() > 0 && minutes.signum() > 0) {
            long batches = dec(task.get("plannedQuantity"))
                    .divide(capacity, 0, RoundingMode.CEILING).longValue();
            long required = batches * minutes.longValue();
            long actual = java.time.Duration.between(start, end).toMinutes();
            if (actual + 1 < required) {
                notes.add("老化工序任务 " + str(task.get("taskNo")) + " 实际记录 " + actual + " 分钟，"
                        + "按冻结配置应连续 " + required + " 分钟（" + batches + " 批 × "
                        + plain(minutes) + " 分钟）");
            }
        }
    }

    /** 老化任务的报工证据与已审核/待审核量。 */
    private AgingTally inspectAgingReports(Collect c, Map<String, Object> task, Long woId, List<Document> docs) {
        BigDecimal executed = BigDecimal.ZERO;
        BigDecimal waitingAuditQty = BigDecimal.ZERO;
        for (Map<String, Object> report : c.reportByTask.getOrDefault(lng(task.get("taskId")), List.of())) {
            docs.add(document("WORK_REPORT", lng(report.get("reportId")), str(report.get("reportNo")),
                    str(report.get("auditStatus")), dec(report.get("qualifiedQuantity")), unitOfOrder(c),
                    ts(report.get("reportTime")), "mfg-work-report", woId, "老化报工",
                    str(report.get("operatorName")), null));
            String audit = str(report.get("auditStatus"));
            if ("APPROVED".equals(audit)) {
                executed = executed.add(dec(report.get("qualifiedQuantity")));
            } else if ("SUBMITTED".equals(audit)) {
                waitingAuditQty = waitingAuditQty.add(dec(report.get("qualifiedQuantity")));
            }
        }
        return new AgingTally(executed, waitingAuditQty);
    }

    private record AgingTally(BigDecimal executed, BigDecimal waitingAudit) {
        AgingTally plus(AgingTally other) {
            return new AgingTally(executed.add(other.executed), waitingAudit.add(other.waitingAudit));
        }
    }

    private Stage stageInbound(Collect c, OrderHeader order) {
        Set<Long> rootIds = c.rootWorkOrders().stream().map(w -> lng(w.get("workOrderId"))).collect(Collectors.toSet());
        BigDecimal quantity = rootIds.stream().map(w -> inboundQuantity(c,w)).reduce(BigDecimal.ZERO,BigDecimal::add);
        List<Document> docs = new ArrayList<>();
        for (Map<String,Object> lot : c.lots) {
            Long wid=lng(lot.get("workOrderId"));
            if (!rootIds.contains(wid)) continue;
            for (Map<String,Object> in : c.inboundByLot.getOrDefault(lng(lot.get("productLotId")),List.of()))
                docs.add(document("PRODUCTION_INBOUND",lng(in.get("inboundId")),str(in.get("inboundNo")),
                        str(in.get("auditStatus")),dec(in.get("inboundQuantity")),unitOfOrder(c),ts(in.get("inboundTime")),
                        "wms-inbound-order",wid,"产品批次生产入库",str(in.get("warehouseName")),null));
        }
        List<Map<String,Object>> approved = c.inbound.stream().filter(in -> "APPROVED".equals(str(in.get("auditStatus"))))
                .filter(in -> c.lots.stream().anyMatch(l -> rootIds.contains(lng(l.get("workOrderId")))
                        && Objects.equals(lng(l.get("productLotId")),lng(in.get("sourceOrderId"))))).toList();
        boolean done = quantity.compareTo(order.orderQuantity())>=0;
        String inboundStatus;
        if (done) {
            inboundStatus = T_DONE;
        } else if (quantity.signum() > 0) {
            inboundStatus = T_RUNNING;
        } else {
            inboundStatus = T_NOT_STARTED;
        }
        return new Stage("INBOUND",7,"成品入库",unitOfOrder(c),inboundStatus,
                scale(order.orderQuantity()),scale(quantity),scale(quantity),BigDecimal.ZERO,BigDecimal.ZERO,
                BigDecimal.ZERO,BigDecimal.ZERO,scale(order.orderQuantity().subtract(quantity).max(BigDecimal.ZERO)),
                null,null,minTime(approved,"inboundTime"),done?maxTime(approved,"inboundTime"):null,
                approved.isEmpty()?TIME_MISSING:TIME_LOGGED,new Owner("仓储","","","WMS"),List.of(),List.of(),docs,
                List.of("汇总全部整机批次；只计 PRODUCT_LOT 来源的已确认生产入库"));
    }

    private Stage stageShipment(Collect c, OrderHeader order) {
        BigDecimal planned = order.orderQuantity();
        BigDecimal shipped = BigDecimal.ZERO;
        BigDecimal waitingAuditQty = BigDecimal.ZERO;
        Map<Long, BigDecimal> byOutbound = new LinkedHashMap<>();
        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> s : c.shipments) {
            Long outboundId = lng(s.get("outboundId"));
            byOutbound.merge(outboundId, dec(s.get("outboundQuantity")), BigDecimal::add);
            docs.add(document("SALES_OUTBOUND", outboundId, str(s.get("outboundNo")), str(s.get("auditStatus")),
                    dec(s.get("outboundQuantity")), unitOfOrder(c), ts(s.get("outboundTime")),
                    "wms-sales-outbound", lng(s.get("workOrderId")), "销售发货",
                    str(s.get("customerName")), null));
        }
        for (Map.Entry<Long, BigDecimal> e : byOutbound.entrySet()) {
            boolean approved = c.shipments.stream()
                    .anyMatch(s -> Objects.equals(lng(s.get("outboundId")), e.getKey())
                            && "APPROVED".equals(str(s.get("auditStatus"))));
            if (approved) shipped = shipped.add(e.getValue());
            else waitingAuditQty = waitingAuditQty.add(e.getValue());
        }
        List<String> notes = new ArrayList<>();
        notes.add("只统计 SALE 类型且来源生产订单等于本订单的已确认发货");
        if ("FINISHED".equals(order.status()) && planned.signum() > 0 && shipped.compareTo(planned) < 0) {
            notes.add("订单状态已完成，但已确认发货少于订单量，需核对发货与订单量差异");
        }
        c.inspections.stream().filter(q->"OUTGOING".equals(str(q.get("inspectionType")))).forEach(q->docs.add(inspectionDocument(c,q)));
        String status;
        if (planned.signum() == 0) {
            status = T_NA;
        } else if (shipped.compareTo(planned) >= 0) {
            status = T_DONE;
        } else if (shipped.signum() > 0) {
            status = T_RUNNING;
        } else {
            status = T_NOT_STARTED;
        }
        return new Stage("SHIPMENT", 8, "出货检验及发货", unitOfOrder(c), status, scale(planned), scale(shipped),
                scale(shipped), BigDecimal.ZERO, scale(waitingAuditQty), BigDecimal.ZERO, BigDecimal.ZERO,
                scale(planned.subtract(shipped).max(BigDecimal.ZERO)), null, null,
                minTime(c.shipments.stream().filter(r->"APPROVED".equals(str(r.get("auditStatus")))).toList(),"outboundTime"),
                shipped.compareTo(planned)>=0?maxTime(c.shipments.stream().filter(r->"APPROVED".equals(str(r.get("auditStatus")))).toList(),"outboundTime"):null,
                shipped.signum()>0?TIME_LOGGED:TIME_MISSING,
                new Owner("仓储与销售", "", "", "WMS"), List.of(), List.of(), docs, notes);
    }

    private String stageStatus(Map<String, Object> workOrderRow, BigDecimal planned, BigDecimal executed,
            List<Blocker> blockers) {
        String status;
        if (hasRedBlocker(blockers)) {
            status = T_BLOCKED;
        } else {
            String woStatus = str(workOrderRow.get("status"));
            if ("FINISHED".equals(woStatus)) {
                status = finishedStatus(planned, executed);
            } else if (!blockers.isEmpty()) {
                status = T_RISK;
            } else if ("RUNNING".equals(woStatus) || executed.signum() > 0) {
                status = T_RUNNING;
            } else if ("RELEASED".equals(woStatus)) {
                status = T_RUNNING;
            } else {
                status = T_NOT_STARTED;
            }
        }
        return status;
    }

    private static boolean hasRedBlocker(List<Blocker> blockers) {
        return blockers.stream().anyMatch(b -> LV_RED.equals(b.level()));
    }

    /** 已完成的工单：达到计划量算完成，否则按是否已产出区分进行中与未开始。 */
    private static String finishedStatus(BigDecimal planned, BigDecimal executed) {
        if (executed.compareTo(planned) >= 0) {
            return T_DONE;
        }
        return executed.signum() > 0 ? T_RUNNING : T_NOT_STARTED;
    }



    /** 还能先装多少台：取所有直接用料约束的最小值，先扣掉已经装完的数量。 */
    private BigDecimal maxAssemblable(Collect c, List<MaterialCoverage> materials, Long rootWorkOrderId,
            BigDecimal rootPlanned) {
        BigDecimal best = null;
        for (MaterialCoverage m : materials) {
            if (!Objects.equals(m.workOrderId(), rootWorkOrderId)) continue;
            BigDecimal perUnit = perUnitUsage(c, rootWorkOrderId, m.requiredQuantity());
            if (perUnit.signum() <= 0) continue;
            BigDecimal available = m.issuedQuantity().add(m.reservedQuantity())
                    .add(m.freeAllocatedQuantity());
            BigDecimal candidate = available.divide(perUnit, 0, RoundingMode.DOWN);
            best = best == null ? candidate : best.min(candidate);
        }
        if (best == null) return BigDecimal.ZERO;
        BigDecimal already = assemblyQuantity(c, rootWorkOrderId);
        return scale(best.subtract(already).max(BigDecimal.ZERO).min(rootPlanned));
    }

    private List<Blocker> componentBlockers(Collect c, Map<String, Object> workOrderRow,
            BigDecimal planned, BigDecimal executed, List<MaterialCoverage> materials) {
        List<Blocker> blockers = new ArrayList<>();
        Long woId = lng(workOrderRow.get("workOrderId"));
        String woNo = str(workOrderRow.get("workOrderNo"));
        BigDecimal remaining = planned.subtract(executed).max(BigDecimal.ZERO);
        for(Map<String,Object> lot:c.lotByWorkOrder.getOrDefault(woId,List.of())) {
            Map<String,Object> q=effectiveFinal(c,lng(lot.get("productLotId")));
            if (isFailedFinalLot(c, lot, q)) {
                blockers.add(qualityFailureBlocker(c,woId,lot,q,"COMPONENT"));
            }
        }

        for (MaterialCoverage coverage : materials) {
            if (!isShortageCoverage(coverage, woId, remaining)) continue;
            blockers.add(materialShortageBlocker(c, workOrderRow, coverage, woNo, remaining, planned));
        }

        // 组件实际完成晚于整机计划开始，且该组件仍有未完成数量时才提示延迟；已有库存覆盖不算缺料。
        Map<String, Object> root = parentRoot(c,workOrderRow);
        if (root == null) {
            return blockers;
        }
        LocalDateTime assemblyStart = ts(root.get("plannedStartTime"));
        LocalDateTime componentActualEnd = workOrderActualEnd(c, woId);
        LocalDateTime componentPlanEnd = ts(workOrderRow.get("plannedEndTime"));
        if (assemblyStart == null || remaining.signum() <= 0) {
            return blockers;
        }
        StockCover stockCover = componentStockCover(c, workOrderRow, materials);
        if (componentActualEnd != null) {
            if (componentActualEnd.isAfter(assemblyStart)) {
                blockers.add(delayBlocker(c, workOrderRow, stockCover, woNo, remaining, componentActualEnd,
                        assemblyStart, true));
            }
        } else if (componentPlanEnd != null && componentPlanEnd.isAfter(assemblyStart)) {
            blockers.add(delayBlocker(c, workOrderRow, stockCover, woNo, remaining, componentPlanEnd,
                    assemblyStart, false));
        }
        return blockers;
    }

    /** 终检已判不合格、且批次未被处置替代、也不属于已让步/入库/报废/降级状态的组件批次。 */
    private boolean isFailedFinalLot(Collect c, Map<String,Object> lot, Map<String,Object> q) {
        return isFailedFinalInspection(q) && !isDisposedLot(c, lot);
    }

    /** 是否已有正式的不合格终检结论。 */
    private boolean isFailedFinalInspection(Map<String,Object> q) {
        return q != null
                && "FINISHED".equals(str(q.get("status")))
                && "FAIL".equals(str(q.get("inspectionResult")));
    }

    /** 批次是否已被处置（返工/报废/降级）或已让步、已入库、已报废、已降级。 */
    private boolean isDisposedLot(Collect c, Map<String,Object> lot) {
        return supersededLot(c, lot) || resolvedPartialLot(c, lot)
                || Set.of("CONCESSION","STORED","SCRAPPED","DOWNGRADE").contains(str(lot.get("status")));
    }

    /** 该工单名下、确有缺口、且工单还有未完成量的用料覆盖才构成缺料。 */
    private boolean isShortageCoverage(MaterialCoverage coverage, Long woId, BigDecimal remaining) {
        if (!Objects.equals(coverage.workOrderId(), woId)) {
            return false;
        }
        if (coverage.shortageQuantity().signum() <= 0) {
            return false;
        }
        return remaining.signum() > 0;
    }

    private Blocker materialShortageBlocker(Collect c, Map<String, Object> workOrderRow, MaterialCoverage coverage,
            String woNo, BigDecimal remaining, BigDecimal planned) {
        Long woId = lng(workOrderRow.get("workOrderId"));
        BigDecimal perUnit = perUnitUsage(c, woId, coverage.requiredQuantity());
        BigDecimal blockedUnits = perUnit.signum() <= 0 ? coverage.shortageQuantity()
                : scale(coverage.shortageQuantity().divide(perUnit, 0, RoundingMode.UP));
        // 影响量取「按缺口折算的套数」与「该工单剩余未完成量」的较小值：已完成的量不再受影响。
        BigDecimal impact = blockedUnits.min(remaining).max(BigDecimal.ZERO);
        return new Blocker("MATERIAL_SHORTAGE", "COMPONENT", LV_RED, "MATERIAL",
                "组件缺料：" + coverage.materialName(),
                "工单 " + woNo + " 的物料 " + coverage.materialCode() + " 需求 "
                        + plain(coverage.requiredQuantity()) + coverage.unit() + "，已发料 "
                        + plain(coverage.issuedQuantity()) + coverage.unit() + "，本单预留 "
                        + plain(coverage.reservedQuantity()) + coverage.unit() + "，仍缺 "
                        + plain(coverage.shortageQuantity()) + coverage.unit(),
                List.of(new Evidence("用料需求", String.valueOf(coverage.requirementId()),
                                document("REQUIREMENT", coverage.requirementId(), "", coverage.status(),
                                        coverage.requiredQuantity(), coverage.unit(), null,
                                        "mfg-work-order", woId, "用料需求", "", null)),
                        new Evidence("工单", woNo, document("WORK_ORDER", woId, woNo,
                                str(workOrderRow.get("status")), planned, "套", null,
                                "mfg-work-order", woId, "组件工单", "", null))),
                new Impact(List.of(woNo), List.of(orderNo(c)), scale(impact), "套",
                        impact.signum() > 0
                                ? "组件缺料会推迟整机装配，最多影响 " + plain(impact) + " 套"
                                : "该工单已完成，缺口只影响后续补产与整机可用组件数"),
                List.of(new Action("OPEN_WORK_ORDER", "查看组件工单", "NAVIGATE",
                                "mfg-work-order", Map.of("focus", woId), ""),
                        new Action("OPEN_INVENTORY", "查看可用库存", "NAVIGATE",
                                "wms-inventory", Map.of("materialId", coverage.materialId()), "")));
    }

    /**
     * 组件完成（或计划完成）晚于整机计划开始时的黄色提示。
     * actual 为 true 表示按实际完成时间提示，false 表示按计划完成时间提示。
     */
    private Blocker delayBlocker(Collect c, Map<String, Object> workOrderRow, StockCover stockCover,
            String woNo, BigDecimal remaining, LocalDateTime milestone, LocalDateTime assemblyStart,
            boolean actual) {
        Long woId = lng(workOrderRow.get("workOrderId"));
        String coveredTitle = actual ? "组件完成晚于整机计划开始，库存可覆盖" : "组件计划完成晚于整机计划开始，库存可覆盖";
        String plainTitle = actual ? "组件完成晚于整机计划开始" : "组件计划完成晚于整机计划开始";
        String timeWord = actual ? "实际完成 " : "计划完成 ";
        return new Blocker("COMPONENT_DELAY", "COMPONENT", LV_YELLOW, "SCHEDULE",
                stockCover.covered() ? coveredTitle : plainTitle,
                "工单 " + woNo + timeWord + milestone + "，晚于整机计划开始 " + assemblyStart
                        + (stockCover.covered() ? "；整机该组件已由" + stockCover.description() + "覆盖，装配不必实际等待" : ""),
                List.of(new Evidence("组件工单", woNo, document("WORK_ORDER", woId, woNo,
                                str(workOrderRow.get("status")), dec(workOrderRow.get("plannedQuantity")), "套",
                                actual ? milestone : null,
                                "mfg-work-order", woId, "组件工单", "", null))),
                delayImpact(c, woNo, remaining, stockCover),
                List.of(new Action("OPEN_WORK_ORDER", "查看组件工单", "NAVIGATE",
                        "mfg-work-order", Map.of("focus", woId), "")));
    }

    /**
     * 组件延迟时判断整机是否真的会被卡住：若整机对这张组件工单的用料需求已被已发料、本单预留与本单可用
     * 公共库存覆盖，装配不必实际等待，只能报黄色进度提示，不能说成缺料或交期风险。
     */
    private StockCover componentStockCover(Collect c, Map<String, Object> workOrderRow,
            List<MaterialCoverage> materials) {
        Map<String, Object> root = parentRoot(c,workOrderRow);
        if (root == null) return new StockCover(false, BigDecimal.ZERO, BigDecimal.ZERO, "");
        Long rootWorkOrderId = lng(root.get("workOrderId"));
        Long sourceWorkOrderId = lng(workOrderRow.get("workOrderId"));
        BigDecimal required = BigDecimal.ZERO;
        BigDecimal issued = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        BigDecimal free = BigDecimal.ZERO;
        for (MaterialCoverage coverage : materials) {
            // 只看整机工单自己名下的需求，且这些需求确实由该组件工单供货。
            if (!Objects.equals(coverage.workOrderId(), rootWorkOrderId)) continue;
            if (coverage.sourceWorkOrderIds() == null || !coverage.sourceWorkOrderIds().contains(sourceWorkOrderId)) {
                continue;
            }
            required = required.add(coverage.requiredQuantity());
            issued = issued.add(coverage.issuedQuantity());
            reserved = reserved.add(coverage.reservedQuantity());
            free = free.add(coverage.freeAllocatedQuantity());
        }
        BigDecimal available = issued.add(reserved).add(free);
        if (required.signum() <= 0) return new StockCover(false, BigDecimal.ZERO, BigDecimal.ZERO, "");
        return new StockCover(available.compareTo(required) >= 0, available, required,
                "整机该组件需求 " + plain(required) + "，已发料 " + plain(issued) + "、本单预留 "
                        + plain(reserved) + "、可用公共库存 " + plain(free));
    }

    private Impact delayImpact(Collect c, String workOrderNo, BigDecimal remaining, StockCover stockCover) {
        // 库存能覆盖时影响量按「仍未被覆盖的差额」算，不能把整批剩余量都算成受影响。
        BigDecimal affected = remaining.subtract(stockCover.quantity()).max(BigDecimal.ZERO);
        return new Impact(List.of(workOrderNo), List.of(orderNo(c)), scale(affected), "套",
                affected.signum() > 0
                        ? "整机装配需等待该组件或改用合格库存，仍有 " + plain(affected) + " 套无库存覆盖"
                        : "整机该组件已有库存覆盖，装配不必实际等待，仅提示进度");
    }

    private record StockCover(boolean covered, BigDecimal quantity, BigDecimal required, String description) {
    }

    private Blocker shortageBlocker(Collect c, MaterialCoverage m, BigDecimal blockedQuantity, Long workOrderId) {
        return new Blocker("MATERIAL_SHORTAGE", "ASSEMBLY", LV_RED, "MATERIAL",
                "整机缺料：" + m.materialName(),
                "整机工单需求 " + plain(m.requiredQuantity()) + m.unit() + "，已发料 "
                        + plain(m.issuedQuantity()) + m.unit() + "，本单预留 " + plain(m.reservedQuantity())
                        + m.unit() + "，公共合格可用 " + plain(m.freeAllocatedQuantity()) + m.unit()
                        + "，仍缺 " + plain(m.shortageQuantity()) + m.unit(),
                List.of(new Evidence("用料需求", String.valueOf(m.requirementId()),
                                document("REQUIREMENT", m.requirementId(), "", m.status(),
                                        m.requiredQuantity(), m.unit(), null,
                                        "mfg-work-order", workOrderId, "用料需求", "", null))),
                new Impact(List.of(c.workOrderNo(workOrderId)), List.of(orderNo(c)), scale(blockedQuantity),
                        unitOfOrder(c), "最多影响 " + plain(blockedQuantity) + " 台整机装配"),
                List.of(new Action("OPEN_INVENTORY", "查看可用库存", "NAVIGATE",
                                "wms-inventory", Map.of("materialId", m.materialId()), ""),
                        new Action("OPEN_WORK_ORDER", "查看整机工单", "NAVIGATE",
                                "mfg-work-order", Map.of("focus", workOrderId), "")));
    }

    private BigDecimal waitingAudit(Collect c, Long workOrderId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> task : c.taskByWorkOrder.getOrDefault(workOrderId, List.of())) {
            if (!isOutputTask(task, c.outputOperation.get(workOrderId))) continue;
            total = total.add(submittedQualified(c, task));
        }
        return scale(total);
    }

    /** 该任务下已提交待审核报工的合格量。 */
    private BigDecimal submittedQualified(Collect c, Map<String, Object> task) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> report : c.reportByTask.getOrDefault(lng(task.get("taskId")), List.of())) {
            if ("SUBMITTED".equals(str(report.get("auditStatus")))) {
                total = total.add(dec(report.get("qualifiedQuantity")));
            }
        }
        return total;
    }

    /** 待检量：产出批次合格量减去正式终检已判定数量，不按总报工量估算。 */
    private BigDecimal waitingInspection(Collect c, Long workOrderId) {
        BigDecimal total=BigDecimal.ZERO;
        for (Map<String,Object> lot:c.lotByWorkOrder.getOrDefault(workOrderId,List.of())) {
            if (supersededLot(c,lot)) continue;
            Map<String,Object> q=effectiveFinal(c,lng(lot.get("productLotId")));
            if (q==null || !"FINISHED".equals(str(q.get("status"))))
                total=total.add(dec(lot.get("batchQuantity")));
        }
        return scale(total);
    }

    private BigDecimal defectQuantity(Collect c, Long workOrderId) {
        BigDecimal total=BigDecimal.ZERO;
        for(Map<String,Object> lot:c.lotByWorkOrder.getOrDefault(workOrderId,List.of())) {
            if (supersededLot(c,lot)) continue;
            Map<String,Object> q=effectiveFinal(c,lng(lot.get("productLotId")));
            total=total.add(q!=null && "FINISHED".equals(str(q.get("status")))
                    ? dec(q.get("unqualifiedQuantity")) : dec(lot.get("defectQuantity")));
        }
        return scale(total);
    }

    private List<Document> documentsOf(Collect c, Long workOrderId) {
        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> q : c.inspectionByWorkOrder.getOrDefault(workOrderId, List.of())) {
            docs.add(inspectionDocument(c,q));
        }
        for (Map<String, Object> lot : c.lotByWorkOrder.getOrDefault(workOrderId, List.of())) {
            docs.add(document("PRODUCT_LOT", lng(lot.get("productLotId")), str(lot.get("lotNo")),
                    str(lot.get("status")), dec(lot.get("qualifiedQuantity")), unitForWorkOrder(c,workOrderId),
                    ts(lot.get("createdTime")), "trace-product-lot", workOrderId, "产品批次", "", null));
        }
        for (Map<String, Object> task : c.taskByWorkOrder.getOrDefault(workOrderId, List.of())) {
            docs.add(document("TASK", lng(task.get("taskId")), str(task.get("taskNo")), str(task.get("status")),
                    dec(task.get("plannedQuantity")), unitForWorkOrder(c,workOrderId), ts(task.get("createdTime")),
                    "mfg-task", workOrderId, str(task.get("operationName")), str(task.get("stationName")), null));
        }
        return docs;
    }

    private String actualTimeType(Collect c, Long workOrderId) {
        if(workOrderActualStart(c,workOrderId)==null) return TIME_MISSING;
        return firstTransitionTime(c.workOrderLog.get(workOrderId),"RUNNING")!=null ? TIME_LOGGED : TIME_DERIVED;
    }

    private LocalDateTime workOrderActualStart(Collect c, Long workOrderId) {
        LocalDateTime logged = firstTransitionTime(c.workOrderLog.get(workOrderId), "RUNNING");
        return logged != null ? logged : earliestReportTime(c, workOrderId);
    }

    private LocalDateTime workOrderActualEnd(Collect c, Long workOrderId) {
        MfgWorkOrder wo=c.entities.get(workOrderId);
        if(wo==null || !"FINISHED".equals(wo.getStatus())) return null;
        LocalDateTime logged=lastTransitionTime(c.workOrderLog.get(workOrderId),"FINISHED");
        return logged!=null?logged:latestReportTime(c,workOrderId);
    }

    // ------------------------------------------------------------------ 单据、时间线、计划实际、依赖

    private List<Document> collectDocuments(Collect c, List<Stage> stages) {
        Map<String, Document> unique = new LinkedHashMap<>();
        addDocuments(unique, stages);
        addIssueDocuments(unique, c);
        addShipmentDocuments(unique, c);
        addWorkOrderDocuments(unique, c);
        addDispatchDocuments(unique, c);
        addTaskDocuments(unique, c);
        addReportDocuments(unique, c);
        for(Map<String,Object> q:c.inspections) put(unique,inspectionDocument(c,q));
        addNonconformingDocuments(unique, c);
        addReturnDocuments(unique, c);
        addSourceDocuments(unique, c);
        return List.copyOf(unique.values());
    }

    /** 领料出库：按单据汇总明细量，单位留空（明细跨单位）。 */
    private void addIssueDocuments(Map<String, Document> unique, Collect c) {
        Map<Long, BigDecimal> issueQty = new LinkedHashMap<>();
        for (List<Map<String, Object>> issues : c.issueByWorkOrder.values()) {
            for (Map<String, Object> i : issues) {
                issueQty.merge(lng(i.get("outboundId")), dec(i.get("outboundQuantity")), BigDecimal::add);
            }
        }
        for (List<Map<String, Object>> issues : c.issueByWorkOrder.values()) {
            for (Map<String, Object> i : issues) {
                Long id = lng(i.get("outboundId"));
                put(unique, document("MATERIAL_ISSUE", id, str(i.get("outboundNo")),
                        str(i.get("auditStatus")), scale(issueQty.getOrDefault(id, BigDecimal.ZERO)), "",
                        ts(i.get("outboundTime")),
                        "wms-outbound-order", lng(i.get("workOrderId")), "领料出库", "", null));
            }
        }
    }

    /** 销售发货单。 */
    private void addShipmentDocuments(Map<String, Document> unique, Collect c) {
        for (Map<String, Object> s : c.shipments) {
            put(unique, document("SALES_OUTBOUND", lng(s.get("outboundId")), str(s.get("outboundNo")),
                    str(s.get("auditStatus")), dec(s.get("outboundQuantity")), unitOfOrder(c),
                    ts(s.get("outboundTime")), "wms-sales-outbound", lng(s.get("workOrderId")),
                    "销售发货", str(s.get("customerName")), null));
        }
    }

    /** 工单本身作为执行依据留档。 */
    private void addWorkOrderDocuments(Map<String, Document> unique, Collect c) {
        for (Map<String, Object> w : c.workOrders) {
            put(unique, document("WORK_ORDER", lng(w.get("workOrderId")), str(w.get("workOrderNo")),
                    str(w.get("status")), dec(w.get("plannedQuantity")),
                    unitForWorkOrder(c, lng(w.get("workOrderId"))), ts(w.get("createdTime")),
                    "mfg-work-order", lng(w.get("workOrderId")), "订单工单", "", null));
        }
    }

    /** 派工单。 */
    private void addDispatchDocuments(Map<String, Document> unique, Collect c) {
        for (Map<String, Object> d : c.dispatches) {
            put(unique, document("DISPATCH", lng(d.get("dispatchId")), str(d.get("dispatchNo")),
                    str(d.get("status")), dec(d.get("plannedQuantity")),
                    unitForWorkOrder(c, lng(d.get("workOrderId"))), null, "mfg-dispatch-order",
                    lng(d.get("workOrderId")), "派工", str(d.get("leaderName")), null));
        }
    }

    /** 工序任务。 */
    private void addTaskDocuments(Map<String, Document> unique, Collect c) {
        for (Map<String, Object> t : c.tasks) {
            put(unique, document("TASK", lng(t.get("taskId")), str(t.get("taskNo")), str(t.get("status")),
                    dec(t.get("plannedQuantity")), unitForWorkOrder(c, lng(t.get("workOrderId"))),
                    ts(t.get("createdTime")), "mfg-task", lng(t.get("workOrderId")),
                    str(t.get("operationName")), "", null));
        }
    }

    /** 报工单。 */
    private void addReportDocuments(Map<String, Document> unique, Collect c) {
        for (Map<String, Object> r : c.reports) {
            put(unique, document("WORK_REPORT", lng(r.get("reportId")), str(r.get("reportNo")),
                    str(r.get("auditStatus")), dec(r.get("qualifiedQuantity")), unitForReport(c, r),
                    ts(r.get("reportTime")), "mfg-work-report", null, "审核报工",
                    str(r.get("operatorName")), null));
        }
    }

    /** 不合格品处置单。 */
    private void addNonconformingDocuments(Map<String, Document> unique, Collect c) {
        for (Map<String, Object> n : c.dispositions) {
            put(unique, document("NONCONFORMING", lng(n.get("ncrId")), str(n.get("ncrNo")),
                    str(n.get("status")), dec(n.get("defectQuantity")), unitOfOrder(c),
                    ts(n.get("createdTime")), "qms-nonconforming-order", null,
                    "不合格处置：" + str(n.get("dispositionType")), "", null));
        }
    }

    /** 退料单。 */
    private void addReturnDocuments(Map<String, Document> unique, Collect c) {
        for (Map<String, Object> r : c.returns) {
            put(unique, document("RETURN", lng(r.get("returnId")), str(r.get("returnNo")),
                    str(r.get("auditStatus")), null, "", ts(r.get("returnTime")),
                    "wms-return-order", lng(r.get("workOrderId")), "已确认退料", "", null));
        }
    }

    /** 用料批次的上游来源单据。 */
    private void addSourceDocuments(Map<String, Document> unique, Collect c) {
        for (Map<String, Object> d : c.sourceDocuments) {
            put(unique, document(str(d.get("docType")), lng(d.get("docId")), str(d.get("docNo")),
                    str(d.get("status")), d.get("quantity") == null ? null : dec(d.get("quantity")),
                    str(d.get("unit")), ts(d.get("eventTime")), str(d.get("targetResource")), null,
                    "用料批次上游来源", "", null));
        }
    }

    private void addDocuments(Map<String, Document> target, List<Stage> stages) {
        for (Stage stage : stages) {
            for (Document d : stage.documents()) put(target, d);
            addDocuments(target, stage.children());
        }
    }

    private void put(Map<String, Document> target, Document document) {
        target.putIfAbsent(document.type() + ":" + document.docId() + ":" + document.docNo(), document);
    }

    private List<TimelineEvent> buildTimeline(Collect c, List<Stage> stages) {
        List<TimelineEvent> events = new ArrayList<>();
        events.add(new TimelineEvent(ts(c.headerRow.get("createdTime")), "生产订单创建", TIME_LOGGED,
                orderDocument(c, "订单创建")));
        appendPlanEvents(c, events);
        appendWorkOrderLogEvents(c, events);
        appendReportEvents(c, events);
        appendInspectionEvents(c, events);
        appendInboundEvents(c, events);
        appendShipmentEvents(c, events);
        events.sort(Comparator.comparing(TimelineEvent::time, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    private void appendPlanEvents(Collect c, List<TimelineEvent> events) {
        for (Map<String, Object> p : c.plans) {
            events.add(new TimelineEvent(ts(p.get("plannedStartTime")),
                    "计划 " + str(p.get("planNo")) + " 计划开始（" + str(p.get("status")) + "）", TIME_LOGGED,
                    document("PRODUCTION_PLAN", lng(p.get("planId")), str(p.get("planNo")), str(p.get("status")),
                            dec(p.get("plannedQuantity")), unitOfOrder(c), ts(p.get("plannedStartTime")),
                            "mfg-production-plan", null, "生产计划", str(p.get("lineName")), null)));
        }
    }

    private void appendWorkOrderLogEvents(Collect c, List<TimelineEvent> events) {
        for (List<Map<String, Object>> logs : c.workOrderLog.values()) {
            for (Map<String, Object> l : logs) {
                appendWorkOrderLogEvent(c, l, events);
            }
        }
    }

    private void appendWorkOrderLogEvent(Collect c, Map<String, Object> l, List<TimelineEvent> events) {
        LocalDateTime at = ts(l.get("changedTime"));
        if (at == null) return;
        Long woId = lng(l.get("bizId"));
        events.add(new TimelineEvent(at,
                "工单 " + c.workOrderNo(woId) + " " + str(l.get("oldStatus")) + " → "
                        + str(l.get("newStatus")), TIME_LOGGED,
                document("WORK_ORDER", woId, c.workOrderNo(woId), str(l.get("newStatus")), null, "套",
                        at, "mfg-work-order", woId, "状态变更", "", null)));
    }

    private void appendReportEvents(Collect c, List<TimelineEvent> events) {
        for (Map<String, Object> r : c.reports) {
            events.add(new TimelineEvent(ts(r.get("reportTime")),
                    "报工 " + str(r.get("reportNo")) + " 合格 " + plain(dec(r.get("qualifiedQuantity")))
                            + "（" + str(r.get("auditStatus")) + "）", TIME_LOGGED,
                    document("WORK_REPORT", lng(r.get("reportId")), str(r.get("reportNo")),
                            str(r.get("auditStatus")), dec(r.get("qualifiedQuantity")), "", ts(r.get("reportTime")),
                            "mfg-work-report", null, "生产报工", str(r.get("operatorName")), null)));
        }
    }

    private void appendInspectionEvents(Collect c, List<TimelineEvent> events) {
        for (Map<String, Object> q : c.inspections) {
            events.add(new TimelineEvent(ts(q.get("inspectionTime")),
                    str(q.get("inspectionType")) + " 检验 " + str(q.get("inspectionNo")) + " "
                            + str(q.get("inspectionResult")), TIME_LOGGED,
                    document("INSPECTION", lng(q.get("inspectionId")), str(q.get("inspectionNo")),
                            str(q.get("status")), dec(q.get("qualifiedQuantity")), "", ts(q.get("inspectionTime")),
                            "qms-inspection-order", lng(q.get("workOrderId")), "检验",
                            str(q.get("inspectorName")), null)));
        }
    }

    private void appendInboundEvents(Collect c, List<TimelineEvent> events) {
        for (Map<String, Object> i : c.inbound) {
            events.add(new TimelineEvent(ts(i.get("inboundTime")),
                    "生产入库 " + str(i.get("inboundNo")) + " " + str(i.get("auditStatus")), TIME_LOGGED,
                    document("PRODUCTION_INBOUND", lng(i.get("inboundId")), str(i.get("inboundNo")),
                            str(i.get("auditStatus")), dec(i.get("inboundQuantity")), "", ts(i.get("inboundTime")),
                            "wms-inbound-order", null, "生产入库", str(i.get("warehouseName")), null)));
        }
    }

    private void appendShipmentEvents(Collect c, List<TimelineEvent> events) {
        for (Map<String, Object> s : c.shipments) {
            events.add(new TimelineEvent(ts(s.get("outboundTime")),
                    "销售发货 " + str(s.get("outboundNo")) + " " + str(s.get("auditStatus")), TIME_LOGGED,
                    document("SALES_OUTBOUND", lng(s.get("outboundId")), str(s.get("outboundNo")),
                            str(s.get("auditStatus")), dec(s.get("outboundQuantity")), unitOfOrder(c),
                            ts(s.get("outboundTime")), "wms-sales-outbound", null, "销售发货",
                            str(s.get("customerName")), null)));
        }
    }

    private List<PlanActual> buildPlanActual(Collect c, List<WorkOrderNode> nodes) {
        List<PlanActual> rows = new ArrayList<>();
        for (Map<String, Object> p : c.plans) {
            rows.add(new PlanActual("PLAN:" + lng(p.get("planId")),
                    str(p.get("planNo")) + " " + ("ROOT".equals(str(p.get("planType"))) ? "根计划" : "组件计划"),
                    "计划", null, ts(p.get("plannedStartTime")), ts(p.get("plannedEndTime")),
                    null, null, TIME_MISSING, planStatus(str(p.get("status"))),
                    document("PRODUCTION_PLAN", lng(p.get("planId")), str(p.get("planNo")),
                            str(p.get("status")), dec(p.get("plannedQuantity")), unitOfOrder(c),
                            ts(p.get("plannedStartTime")), "mfg-production-plan", null, "生产计划",
                            str(p.get("lineName")), null)));
        }
        for (WorkOrderNode n : nodes) {
            String timeType;
            if (firstTransitionTime(c.workOrderLog.get(n.workOrderId()), "RUNNING") != null) {
                timeType = TIME_LOGGED;
            } else if (n.actualStart() == null) {
                timeType = TIME_MISSING;
            } else {
                timeType = TIME_DERIVED;
            }
            rows.add(new PlanActual("WO:" + n.workOrderId(), n.workOrderNo() + " " + n.productName(),
                    "ROOT".equals(n.workOrderType()) ? "整机" : "组件", n.workOrderId(),
                    n.plannedStart(), n.plannedEnd(), n.actualStart(), n.actualEnd(), timeType,
                    workOrderStatus(n.status()),
                    document("WORK_ORDER", n.workOrderId(), n.workOrderNo(), n.status(),
                            n.plannedQuantity(), "套", n.actualStart(), "mfg-work-order", n.workOrderId(),
                            "生产工单", n.lineName(), null)));
        }
        return rows;
    }

    private String planStatus(String status) {
        return switch (status) {
            case "FINISHED" -> T_DONE;
            case "RUNNING", "RELEASED" -> T_RUNNING;
            default -> T_NOT_STARTED;
        };
    }

    private String workOrderStatus(String status) {
        return switch (status) {
            case "FINISHED" -> T_DONE;
            case "RUNNING", "RELEASED" -> T_RUNNING;
            default -> T_NOT_STARTED;
        };
    }

    private List<Dependency> buildDependencies(Collect c, List<WorkOrderNode> nodes) {
        List<Dependency> rows = new ArrayList<>();
        Map<Long, WorkOrderNode> byId = new LinkedHashMap<>();
        nodes.forEach(n -> byId.put(n.workOrderId(), n));
        appendParentDependencies(nodes, byId, rows);
        appendSupplyDependencies(c, rows);
        return rows;
    }

    /** 组件工单 → 整机的父子依赖；父工单不在本次节点内时跳过。 */
    private void appendParentDependencies(List<WorkOrderNode> nodes, Map<Long, WorkOrderNode> byId,
            List<Dependency> rows) {
        for (WorkOrderNode n : nodes) {
            if (n.parentWorkOrderId() == null || !byId.containsKey(n.parentWorkOrderId())) continue;
            WorkOrderNode parent = byId.get(n.parentWorkOrderId());
            rows.add(new Dependency(n.workOrderNo(), parent.workOrderNo(), "组件供给整机",
                    "FINISHED".equals(n.status()) ? T_DONE : T_RUNNING,
                    "组件完工并放行后才能用于整机装配"));
        }
    }

    /** 补产来源依赖：每条用料需求上的来源工单都算一条依赖。 */
    private void appendSupplyDependencies(Collect c, List<Dependency> rows) {
        for (Map.Entry<Long, List<Map<String, Object>>> entry : c.sourceByRequirement.entrySet()) {
            for (Map<String, Object> s : entry.getValue()) {
                appendSupplyDependency(c, entry.getKey(), s, rows);
            }
        }
    }

    private void appendSupplyDependency(Collect c, Long requirementId, Map<String, Object> s, List<Dependency> rows) {
        Long sourceWo = lng(s.get("workOrderId"));
        if (sourceWo == null) return;
        rows.add(new Dependency(str(s.get("sourceNo")), c.workOrderNo(sourceWo),
                "补产来源：" + str(s.get("supplyType")), T_RUNNING,
                "用料需求 " + requirementId + " 计划供给 " + plain(dec(s.get("plannedQuantity")))));
    }

    // ------------------------------------------------------------------ 汇总与数据质量

    private Summary buildSummary(Collect c, OrderHeader order, List<Stage> stages,List<MaterialCoverage> materials) {
        BigDecimal produced=BigDecimal.ZERO,released=BigDecimal.ZERO,inbound=BigDecimal.ZERO,assemblable=BigDecimal.ZERO;
        for(Map<String,Object> root:c.rootWorkOrders()) {
            Long wid=lng(root.get("workOrderId"));
            produced=produced.add(reportedQualified(c,wid));
            released=released.add(releasedQualified(c,wid));
            inbound=inbound.add(inboundQuantity(c,wid));
            if(!Set.of("CLOSED","FINISHED").contains(str(root.get("status"))))
                assemblable=assemblable.add(maxAssemblable(c,materials,wid,dec(root.get("plannedQuantity"))));
        }
        Stage shipment=stages.stream().filter(st->"SHIPMENT".equals(st.code())).findFirst().orElseThrow();
        List<Blocker> blockers=flattenStages(stages).stream().flatMap(st->st.blockers().stream()).distinct().toList();
        int red=(int)blockers.stream().filter(b->LV_RED.equals(b.level())).count();
        int yellow=(int)blockers.stream().filter(b->LV_YELLOW.equals(b.level())).count();
        Stage current=currentStage(stages);
        BigDecimal shipped=shipment.executedQuantity();
        String overall=overallStatus(order, red, yellow, shipped);
        LocalDateTime planned=plannedFinish(c), predicted=null;
        boolean finished=isAllRootWorkOrdersFinished(c, order, produced);
        String basis=predictionBasis(c, order, blockers, planned, finished, red);
        if(finished) predicted=actualFinishTime(c);
        return new Summary(overall,current==null?null:current.code(),current==null?"已完成":current.name(),
                percent(produced,order.orderQuantity()),percent(shipped,order.orderQuantity()),scale(released),scale(inbound),
                scale(shipped),scale(assemblable),planned,predicted,basis,red,yellow,order.rehearsal());
    }

    /** 当前阶段：按顺序取第一个还没走完（既非完成也非不适用）的阶段。 */
    private Stage currentStage(List<Stage> stages) {
        return stages.stream().filter(st->!Set.of(T_DONE,T_NA).contains(st.status())).findFirst().orElse(null);
    }

    /** 整体状态：草稿未开始、有红色阻塞即阻塞、发完货即完成、有黄色风险即风险，其余进行中。 */
    private String overallStatus(OrderHeader order, int red, int yellow, BigDecimal shipped) {
        String status;
        if ("DRAFT".equals(order.status())) {
            status = T_NOT_STARTED;
        } else if (red > 0) {
            status = T_BLOCKED;
        } else if (shipped.compareTo(order.orderQuantity()) >= 0) {
            status = T_DONE;
        } else {
            status = yellow > 0 ? T_RISK : T_RUNNING;
        }
        return status;
    }

    /** 计划完工时间：取未关闭的根计划里最晚的计划结束时间。 */
    private LocalDateTime plannedFinish(Collect c) {
        List<Map<String, Object>> roots = c.plans.stream()
                .filter(r -> "ROOT".equals(str(r.get("planType"))) && !"CLOSED".equals(str(r.get("status"))))
                .toList();
        return maxTime(roots, "plannedEndTime");
    }

    /** 整机全部完工：有整机工单、报工合格数达标，且所有整机工单都是 FINISHED。 */
    private boolean isAllRootWorkOrdersFinished(Collect c, OrderHeader order, BigDecimal produced) {
        return !c.rootWorkOrders().isEmpty() && produced.compareTo(order.orderQuantity()) >= 0
                && c.rootWorkOrders().stream().allMatch(r -> "FINISHED".equals(str(r.get("status"))));
    }

    /** 已完工时取各整机工单最晚的实际完工时间；只要有一张缺时间就不给预测值。 */
    private LocalDateTime actualFinishTime(Collect c) {
        boolean anyMissing = c.rootWorkOrders().stream()
                .anyMatch(r -> workOrderActualEnd(c, lng(r.get("workOrderId"))) == null);
        if (anyMissing) {
            return null;
        }
        return c.rootWorkOrders().stream().map(r -> workOrderActualEnd(c, lng(r.get("workOrderId"))))
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    /** 预计完工时间的依据说明：分已完工、有阻塞、缺计划、一般四档。 */
    private String predictionBasis(Collect c, OrderHeader order, List<Blocker> blockers,
                                   LocalDateTime planned, boolean finished, int red) {
        String basis;
        if (finished) {
            basis = actualFinishTime(c) == null
                    ? "整机生产已完成，但缺少完整的完工时间证据" : "按全部整机工单实际完工时间给出（实录）";
        } else if (red > 0) {
            basis = "存在阻塞（" + blockers.stream().filter(b -> LV_RED.equals(b.level()))
                    .map(Blocker::code).distinct().collect(Collectors.joining("、"))
                    + "），解除时间未知，无法预测完成时间";
        } else if (planned == null) {
            basis = "尚未生成完整根计划，缺少可承诺的完成依据";
        } else {
            basis = "原计划完工 " + planned + "；缺少剩余工时、到料、维修及实际排班的完整依据，暂不承诺预计完工";
        }
        return basis;
    }

    private BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private DataQuality buildDataQuality(Collect c,List<Document> documents) {
        List<String> notes=new ArrayList<>();
        notes.add("当前状态快照（CURRENT_STATE）；评估时间只用于逾期风险判断，不作历史回放");
        notes.add("实际完工须有完成证据；进行中工单的末次报工不能作为完工时间");
        notes.add("已发料采用扣除已确认退料后的净量；公共库存是规划参考，不表示已领到线边");
        notes.addAll(c.snapshotNotes);notes.addAll(c.equipmentNotes);
        List<String> missing=new ArrayList<>(c.snapshotNotes);missing.addAll(c.missing);
        return new DataQuality("CURRENT_STATE",!missing.isEmpty(),List.copyOf(new LinkedHashSet<>(missing)),notes,
                List.of("老化作为生产终检中的检验步骤，保留冻结批容量、工时和人工记录",
                        "人工报工，未验证连续运行；无设备采集，不把填写工时当作真实通电时长"),documents.size());
    }

    /**
     * 设备故障先核实工位、工序与替代资源，再决定是否升级为阻塞。
     *
     * <p>返回的阻塞项按受影响工单所属阶段分组，由调用方挂到对应阶段上；不构成阻塞时只写数据质量说明。
     * 该工位任务已全部结束时不能拿事后报修倒判停工，否则会把已完成的订单标红。</p>
     */
    private Map<String,List<Blocker>> equipmentFaultBlockers(Collect c) {
        Map<String,List<Blocker>> grouped=new LinkedHashMap<>();
        List<Long> operations=c.tasks.stream().map(t->lng(t.get("operationId"))).filter(Objects::nonNull).distinct().toList();
        List<Map<String,Object>> alternatives=operations.isEmpty()?List.of():progressMapper.selectAlternativeResources(operations);
        Set<String> seen=new LinkedHashSet<>();
        for(Map<String,Object> fault:c.equipmentFaults) {
            appendFaultBlockers(c, fault, alternatives, seen, grouped);
        }
        return grouped;
    }

    /** 单个设备故障：逐个受影响的未结束任务生成阻塞项，已全部结束的工位只写数据质量说明。 */
    private void appendFaultBlockers(Collect c, Map<String,Object> fault,
            List<Map<String,Object>> alternatives, Set<String> seen, Map<String,List<Blocker>> grouped) {
        Long station=lng(fault.get("stationId"));
        List<Map<String,Object>> pending=pendingTasksAtStation(c, station);
        if(pending.isEmpty()) {
            c.equipmentNotes.add("设备 "+str(fault.get("equipmentCode"))+" 存在未关闭报修，但本订单该工位任务均已结束，不倒判为停工");
            return;
        }
        for(Map<String,Object> task:pending) {
            appendTaskFaultBlocker(c, fault, task, station, alternatives, seen, grouped);
        }
    }

    /** 该工位尚未结束（未完工、未取消、未关闭）的任务。 */
    private List<Map<String,Object>> pendingTasksAtStation(Collect c, Long station) {
        return c.tasks.stream().filter(t->Objects.equals(lng(t.get("stationId")),station)
                && !Set.of("FINISHED","CANCELLED","CLOSED").contains(str(t.get("status")))).toList();
    }

    private void appendTaskFaultBlocker(Collect c, Map<String,Object> fault, Map<String,Object> task,
            Long station, List<Map<String,Object>> alternatives, Set<String> seen, Map<String,List<Blocker>> grouped) {
        Long wid=lng(task.get("workOrderId")),op=lng(task.get("operationId"));
        String key=fault.get("equipmentId")+":"+wid+":"+op;
        if(!seen.add(key))return;
        boolean root=c.rootWorkOrders().stream().anyMatch(w->Objects.equals(lng(w.get("workOrderId")),wid));
        boolean aging=c.routeSteps.getOrDefault(wid,List.of()).stream().anyMatch(step->
                Objects.equals(step.path("operationId").asLong(),op) && "BATCH_EQUIPMENT".equals(step.path("resourceType").asText()));
        String stage;
        if (!root) {
            stage="COMPONENT";
        } else if (aging) {
            stage="FINAL_INSPECTION";
        } else {
            stage="ASSEMBLY";
        }
        boolean candidate=hasAlternativeCandidate(c, fault, station, op, wid, alternatives);
        Long repairId=lng(fault.get("repairId"));
        Document evidence=repairId==null?document("EQUIPMENT",lng(fault.get("equipmentId")),str(fault.get("equipmentCode")),
                str(fault.get("equipmentStatus")),null,"",null,"eam-equipment",wid,"设备当前状态","",null):
                document("REPAIR_ORDER",repairId,str(fault.get("repairNo")),str(fault.get("repairStatus")),null,"",
                        ts(fault.get("reportedTime")),"eam-repair-order",wid,"设备报修","",null);
        String no=c.workOrderNo(wid);
        BigDecimal left=dec(task.get("plannedQuantity")).subtract(dec(task.get("completedQuantity"))).max(BigDecimal.ZERO);
        String level = candidate ? LV_YELLOW : LV_RED;
        String detail = "工单 " + no + " 的工序 " + str(task.get("operationName")) + "，工位 " + station
                + "，设备 " + str(fault.get("equipmentCode")) + "：" + str(fault.get("faultDescription"))
                + (candidate ? "；存在同工序、同设备类别的空闲替代候选，需确认排班和转移安排"
                        : "；未发现可替代设备（需核实同工序能力）");
        String impactText = candidate ? "可切换工位的候选已找到，尚需确认排班，不视为已完成转移"
                : "该工单相关工位任务被阻塞";
        Blocker blocker=new Blocker("EQUIPMENT_FAULT",stage,level,"EQUIPMENT",
                "设备故障："+str(fault.get("equipmentName")),
                detail,
                List.of(new Evidence(repairId==null?"设备":"维修单",evidence.docNo(),evidence)),
                new Impact(List.of(no),List.of(orderNo(c)),scale(left),root?unitOfOrder(c):"套", impactText),
                List.of(new Action("OPEN_EQUIPMENT","查看设备及维修","NAVIGATE",evidence.targetResource(),evidence.query(),"")));
        grouped.computeIfAbsent(stage,k->new ArrayList<>()).add(blocker);
    }

    /** 同工序、同设备类别、当前空闲、且与目标工单同产线的替代工位是否存在。 */
    private boolean hasAlternativeCandidate(Collect c, Map<String,Object> fault, Long station, Long op, Long wid,
            List<Map<String,Object>> alternatives) {
        Long categoryId=lng(fault.get("categoryId"));
        if (categoryId == null) {
            return false;
        }
        return alternatives.stream()
                .filter(a->!Objects.equals(lng(a.get("stationId")),station))
                .filter(a->Objects.equals(lng(a.get("operationId")),op))
                .filter(a->Objects.equals(lng(a.get("categoryId")),categoryId))
                .filter(a->dec(a.get("busy")).signum()==0)
                .anyMatch(a->onSameLine(c, wid, lng(a.get("lineId"))));
    }

    /** 目标工单与该产线是否同属一条线。 */
    private boolean onSameLine(Collect c, Long wid, Long lineId) {
        return c.workOrders.stream().anyMatch(w->Objects.equals(lng(w.get("workOrderId")),wid)
                && Objects.equals(lng(w.get("lineId")),lineId));
    }

    // ------------------------------------------------------------------ 工具

    private Document document(String type, Long docId, String docNo, String status, BigDecimal quantity,
            String unit, LocalDateTime time, String targetResource, Long workOrderId, String relation,
            String handlerName, Long extraWorkOrderId) {
        Map<String, Object> query = new LinkedHashMap<>();
        Long focus;
        if (!Set.of("REQUIREMENT","SUPPLY_SOURCE").contains(type)) {
            focus = docId;
        } else if (extraWorkOrderId!=null) {
            focus = extraWorkOrderId;
        } else {
            focus = workOrderId;
        }
        if (focus != null && focus > 0) query.put("focus", focus);
        Long wo = extraWorkOrderId != null ? extraWorkOrderId : workOrderId;
        if (wo != null && wo > 0) query.put("workOrderId", wo);
        if ("REQUIREMENT".equals(type)) query.put("detail","materials");
        return new Document(type, docId, docNo, status, quantity == null ? null : scale(quantity),
                unit, time, targetResource, query, workOrderId, relation, handlerName);
    }

    private Map<String, Object> firstTransition(List<Map<String, Object>> logs, String newStatus) {
        if (logs == null) return null;
        for (Map<String, Object> l : logs) if (newStatus.equals(str(l.get("newStatus")))) return l;
        return null;
    }

    private LocalDateTime firstTransitionTime(List<Map<String, Object>> logs, String newStatus) {
        Map<String, Object> row = firstTransition(logs, newStatus);
        return row == null ? null : ts(row.get("changedTime"));
    }

    private LocalDateTime lastTransitionTime(List<Map<String, Object>> logs, String newStatus) {
        if (logs == null) return null;
        LocalDateTime found = null;
        for (Map<String, Object> l : logs) {
            if (newStatus.equals(str(l.get("newStatus")))) found = ts(l.get("changedTime"));
        }
        return found;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(SCALE) : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        if (value == null) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal dec(Object value) {
        BigDecimal result;
        if (value == null) {
            result = BigDecimal.ZERO;
        } else if (value instanceof BigDecimal bd) {
            result = bd;
        } else if (value instanceof Number n) {
            result = new BigDecimal(n.toString());
        } else {
            String text = String.valueOf(value).trim();
            result = text.isEmpty() ? BigDecimal.ZERO : new BigDecimal(text);
        }
        return result;
    }

    private static Long lng(Object value) {
        Long result = null;
        if (value instanceof Number n) {
            result = n.longValue();
        } else if (value != null) {
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                result = parseLongOrNull(text);
            }
        }
        return result;
    }

    private static Long parseLongOrNull(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static LocalDateTime ts(Object value) {
        LocalDateTime result = null;
        if (value instanceof LocalDateTime dt) {
            result = dt;
        } else if (value instanceof Timestamp t) {
            result = t.toLocalDateTime();
        } else if (value instanceof LocalDate d) {
            result = d.atStartOfDay();
        } else if (value instanceof java.util.Date d) {
            result = new Timestamp(d.getTime()).toLocalDateTime();
        }
        return result;
    }

    private static LocalDate date(Object value) {
        LocalDate result = null;
        if (value instanceof LocalDate d) {
            result = d;
        } else if (value instanceof java.sql.Date d) {
            result = d.toLocalDate();
        } else if (value instanceof LocalDateTime dt) {
            result = dt.toLocalDate();
        } else if (value instanceof java.util.Date d) {
            result = new Timestamp(d.getTime()).toLocalDateTime().toLocalDate();
        }
        return result;
    }

    /** Inspection verdicts are attached to a batch, not summed repeatedly across inspection history. */
    private Map<String,Object> effectiveFinal(Collect c,Long lotId) {
        return c.inspectionByLot.getOrDefault(lotId,List.of()).stream()
                .filter(q->"FINAL".equals(str(q.get("inspectionType"))) && !"CLOSED".equals(str(q.get("status"))))
                .max(Comparator.comparing(q->lng(q.get("inspectionId")))).orElse(null);
    }

    private Long assemblyOperation(Collect c,Long wid) {
        List<JsonNode> steps=c.routeSteps.getOrDefault(wid,List.of());
        Long last=null;
        for(JsonNode step:steps) {
            if("BATCH_EQUIPMENT".equals(step.path("resourceType").asText())) break;
            last=step.path("operationId").asLong();
        }
        return last;
    }

    private BigDecimal operationQuantity(Collect c,Long wid,Long op,String audit) {
        if(op==null)return BigDecimal.ZERO;
        BigDecimal total=BigDecimal.ZERO;
        for(Map<String,Object> task:c.taskByWorkOrder.getOrDefault(wid,List.of())) {
            if(!Objects.equals(lng(task.get("operationId")),op) || "REWORK".equals(str(task.get("taskType"))))continue;
            for(Map<String,Object> report:c.reportByTask.getOrDefault(lng(task.get("taskId")),List.of()))
                if(audit.equals(str(report.get("auditStatus"))))total=total.add(dec(report.get("qualifiedQuantity")));
        }
        return scale(total);
    }

    private boolean operationStarted(Collect c,Long wid,Long op) {
        return c.taskByWorkOrder.getOrDefault(wid,List.of()).stream().anyMatch(t->Objects.equals(lng(t.get("operationId")),op)
                && Set.of("RUNNING","PAUSED","FINISHED").contains(str(t.get("status"))));
    }

    private BigDecimal assemblyQuantity(Collect c,Long wid) {
        return operationQuantity(c,wid,assemblyOperation(c,wid),"APPROVED");
    }

    private LocalDateTime operationTime(Collect c,Long wid,Long op,String state) {
        List<Map<String,Object>> tasks = operationTasks(c, wid, op);
        LocalDateTime result = null;
        if (!tasks.isEmpty() && !incompleteForFinished(tasks, state)) {
            List<LocalDateTime> values = transitionTimes(c, tasks, state);
            if (!"FINISHED".equals(state) || values.size() == tasks.size()) {
                result = "RUNNING".equals(state)
                        ? values.stream().min(Comparator.naturalOrder()).orElse(null)
                        : values.stream().max(Comparator.naturalOrder()).orElse(null);
            }
        }
        return result;
    }

    /** 该工单该工序下未取消、非返工的任务。 */
    private List<Map<String,Object>> operationTasks(Collect c,Long wid,Long op) {
        return c.taskByWorkOrder.getOrDefault(wid,List.of()).stream()
                .filter(t->Objects.equals(lng(t.get("operationId")),op) && !"REWORK".equals(str(t.get("taskType"))))
                .filter(t->!"CANCELLED".equals(str(t.get("status")))).toList();
    }

    /** 取完工时间时，只要还有任务未完工就不能给出完工时间。 */
    private boolean incompleteForFinished(List<Map<String,Object>> tasks, String state) {
        return "FINISHED".equals(state) && tasks.stream().anyMatch(t->!"FINISHED".equals(str(t.get("status"))));
    }

    /** 每个任务的状态变更时间：RUNNING 取首次，其他取末次；取不到的不入列。 */
    private List<LocalDateTime> transitionTimes(Collect c, List<Map<String,Object>> tasks, String state) {
        List<LocalDateTime> values=new ArrayList<>();
        for(Map<String,Object> t:tasks) {
            LocalDateTime at="RUNNING".equals(state)?firstTransitionTime(c.taskLogByTask.get(lng(t.get("taskId"))),state)
                    :lastTransitionTime(c.taskLogByTask.get(lng(t.get("taskId"))),state);
            if(at!=null)values.add(at);
        }
        return values;
    }

    private LocalDateTime minTime(List<Map<String,Object>> rows,String field) {
        return rows.stream().map(r->ts(r.get(field))).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private LocalDateTime maxTime(List<Map<String,Object>> rows,String field) {
        return rows.stream().map(r->ts(r.get(field))).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private List<Stage> flattenStages(List<Stage> stages) {
        List<Stage> result=new ArrayList<>();
        for(Stage stage:stages){result.add(stage);result.addAll(flattenStages(stage.children()));}
        return result;
    }

    private BigDecimal sum(List<Stage> rows,java.util.function.Function<Stage,BigDecimal> field) {
        return scale(rows.stream().map(field).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add));
    }

    private Stage aggregate(String code,int seq,String name,String unit,BigDecimal planned,List<Stage> rows,List<String> notes) {
        BigDecimal executed=sum(rows,Stage::executedQuantity);
        List<Blocker> blockers=rows.stream().flatMap(r->r.blockers().stream()).distinct().toList();
        String status=aggregateStatus(rows,planned,executed,blockers);
        LocalDateTime start=rows.stream().map(Stage::actualStart).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        LocalDateTime end=aggregateActualEnd(rows);
        return new Stage(code,seq,name,unit,status,scale(planned),executed,sum(rows,Stage::reportedQualifiedQuantity),sum(rows,Stage::releasedQuantity),
                sum(rows,Stage::waitingAuditQuantity),sum(rows,Stage::waitingInspectionQuantity),sum(rows,Stage::defectQuantity),
                scale(planned.subtract(executed).max(BigDecimal.ZERO)),
                rows.stream().map(Stage::plannedStart).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                rows.stream().map(Stage::plannedEnd).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null),start,end,
                aggregateTimeType(rows,start),
                rows.size()==1?rows.get(0).owner():new Owner("详见各工单","","","MULTIPLE"),
                rows.size()>1?rows:List.of(),rows.size()>1?List.of():blockers,
                rows.stream().flatMap(r->r.documents().stream()).distinct().toList(),
                java.util.stream.Stream.concat(notes.stream(),rows.stream().flatMap(r->r.notes().stream())).distinct().toList());
    }

    /** 汇总阶段状态：红阻塞优先，其次全不适用、达计划量、有阻塞、已开工，最后未开始。 */
    private String aggregateStatus(List<Stage> rows,BigDecimal planned,BigDecimal executed,List<Blocker> blockers) {
        String status;
        if (hasRedBlocker(blockers)) {
            status=T_BLOCKED;
        } else if (!rows.isEmpty() && rows.stream().allMatch(r->T_NA.equals(r.status()))) {
            status=T_NA;
        } else if (!rows.isEmpty() && executed.compareTo(planned)>=0) {
            status=T_DONE;
        } else if (!blockers.isEmpty()) {
            status=T_RISK;
        } else if (rows.stream().anyMatch(r->T_RUNNING.equals(r.status()) || r.executedQuantity().signum()>0)) {
            status=T_RUNNING;
        } else {
            status=T_NOT_STARTED;
        }
        return status;
    }

    /** 汇总阶段的实际完成时间：所有子阶段都有完成时间才取最大值，否则不给完成时间。 */
    private LocalDateTime aggregateActualEnd(List<Stage> rows) {
        LocalDateTime end=null;
        if (!rows.isEmpty() && rows.stream().allMatch(r->r.actualEnd()!=null)) {
            end=rows.stream().map(Stage::actualEnd).max(Comparator.naturalOrder()).orElse(null);
        }
        return end;
    }

    /** 汇总阶段的实际时间口径：无开始时间为缺失，有上报时间取上报，否则按状态流转推算。 */
    private static String aggregateTimeType(List<Stage> rows, LocalDateTime start) {
        if (start == null) {
            return TIME_MISSING;
        }
        if (rows.stream().anyMatch(r -> TIME_REPORTED.equals(r.actualTimeType()))) {
            return TIME_REPORTED;
        }
        return TIME_LOGGED;
    }

    private Document inspectionDocument(Collect c,Map<String,Object> q) {
        boolean stock = dec(q.get("stockReleaseInspection")).signum()>0;
        return document(stock ? "STOCK_INSPECTION" : "INSPECTION",lng(q.get("inspectionId")),str(q.get("inspectionNo")),str(q.get("status")),
                dec(q.get("sampleQuantity")),unitForWorkOrder(c,lng(q.get("workOrderId"))),ts(q.get("inspectionTime")),"qms-inspection-order",
                lng(q.get("workOrderId")),stock ? "库存半成品领用复验" : str(q.get("inspectionType"))+" 检验",str(q.get("inspectorName")),null);
    }

    private Stage stageFinalInspection(Collect c,OrderHeader order,List<Blocker> equipment) {
        List<Stage> agingRows=c.rootWorkOrders().stream().map(w->agingOf(c,order,w)).toList();
        Stage aging=aggregate("AGING",1,"老化测试记录",unitOfOrder(c),order.orderQuantity(),agingRows,
                agingRows.isEmpty()?List.of("尚未形成老化执行依据，待工单冻结后确定"):List.of("生产终检的内部步骤，仍计入排产工时和设备资源"));
        List<Blocker> blockers=new ArrayList<>(equipment);
        List<Document> docs=new ArrayList<>();
        BigDecimal inspected=BigDecimal.ZERO,released=BigDecimal.ZERO,waiting=BigDecimal.ZERO,defects=BigDecimal.ZERO;
        for(Map<String,Object> root:c.rootWorkOrders()) {
            FinalInspectionTally tally=inspectRootLots(c,lng(root.get("workOrderId")),docs,blockers);
            inspected=inspected.add(tally.inspected());
            released=released.add(tally.released());
            waiting=waiting.add(tally.waiting());
            defects=defects.add(tally.defects());
        }
        String status=finalInspectionStatus(blockers,released,order,inspected,waiting,aging);
        List<String> notes=new ArrayList<>();
        notes.add("老化为本阶段的内部测试步骤；检验通过或正式让步后才计入已放行");
        long concession=c.inspections.stream().filter(q->"FINAL".equals(str(q.get("inspectionType")))
                && "FINISHED".equals(str(q.get("status"))) && "CONCESSION".equals(str(q.get("inspectionResult")))).count();
        if(concession>0)notes.add("含正式让步放行批次，请查看对应检验及处置依据");
        return new Stage("FINAL_INSPECTION",6,"生产终检",unitOfOrder(c),status,scale(order.orderQuantity()),scale(inspected),
                scale(inspected),scale(released),aging.waitingAuditQuantity(),scale(waiting),scale(defects),
                scale(order.orderQuantity().subtract(released).max(BigDecimal.ZERO)),null,null,
                minTime(finalInspections(c),"inspectionTime"),
                T_DONE.equals(status)?maxTime(finalInspections(c),"inspectionTime"):null,
                inspected.signum()>0?TIME_LOGGED:TIME_MISSING,new Owner("质检","",finalInspections(c)
                        .stream().map(q->str(q.get("inspectorName"))).filter(v->!v.isEmpty()).distinct().collect(Collectors.joining("、")),"QMS"),List.of(aging),blockers,docs,notes);
    }

    /** 终检阶段的状态：有红阻塞即阻塞，放行量达订单量即完成，有检验/待检/老化进展即进行中。 */
    private String finalInspectionStatus(List<Blocker> blockers,BigDecimal released,OrderHeader order,
            BigDecimal inspected,BigDecimal waiting,Stage aging) {
        String status;
        if (hasRedBlocker(blockers)) {
            status=T_BLOCKED;
        } else if (released.compareTo(order.orderQuantity())>=0) {
            status=T_DONE;
        } else if (hasFinalInspectionProgress(inspected,waiting,aging)) {
            status=T_RUNNING;
        } else {
            status=T_NOT_STARTED;
        }
        return status;
    }

    /** 终检是否已有进展：有已检量、有待检量，或老化步骤已开始/完成。 */
    private boolean hasFinalInspectionProgress(BigDecimal inspected,BigDecimal waiting,Stage aging) {
        boolean inspectedAny=inspected.signum()>0 || waiting.signum()>0;
        return inspectedAny || T_RUNNING.equals(aging.status()) || T_DONE.equals(aging.status());
    }

    private List<Map<String,Object>> finalInspections(Collect c) {
        return c.inspections.stream().filter(q->"FINAL".equals(str(q.get("inspectionType")))).toList();
    }

    /** 汇总单张工单下所有批次的终检量，并把终检证据与不合格阻塞写进传入的列表。 */
    private FinalInspectionTally inspectRootLots(Collect c,Long wid,List<Document> docs,List<Blocker> blockers) {
        BigDecimal inspected=BigDecimal.ZERO;
        for(Map<String,Object> lot:c.lotByWorkOrder.getOrDefault(wid,List.of())) {
            Map<String,Object> q=effectiveFinal(c,lng(lot.get("productLotId")));
            if(q==null)continue;
            docs.add(inspectionDocument(c,q));
            if(supersededLot(c,lot))continue;
            if(!"FINISHED".equals(str(q.get("status"))))continue;
            inspected=inspected.add(dec(lot.get("batchQuantity")));
            if(!isFinalFailure(q,lot))continue;
            blockers.add(qualityFailureBlocker(c,wid,lot,q,"FINAL_INSPECTION"));
        }
        return new FinalInspectionTally(inspected,releasedQualified(c,wid),waitingInspection(c,wid),defectQuantity(c,wid));
    }

    /** 终检判不合格且批次未被让步/入库/报废/降级状态解释时才升级为阻塞。 */
    private boolean isFinalFailure(Map<String,Object> q,Map<String,Object> lot) {
        boolean failed="FAIL".equals(str(q.get("inspectionResult")));
        boolean unexplained=!Set.of("PASS","CONCESSION","STORED","SCRAPPED","DOWNGRADE").contains(str(lot.get("status")));
        return failed && unexplained;
    }

    private record FinalInspectionTally(BigDecimal inspected,BigDecimal released,BigDecimal waiting,BigDecimal defects) {
    }


    private Map<String,Object> parentRoot(Collect c,Map<String,Object> child) {
        Long parent=lng(child.get("parentWorkOrderId"));
        if(parent==null)parent=lng(child.get("rootWorkOrderId"));
        final Long id=parent;
        return c.rootWorkOrders().stream().filter(w->Objects.equals(lng(w.get("workOrderId")),id)).findFirst().orElse(null);
    }

    private void addOverdueRisk(Collect c,OrderHeader order,List<Stage> stages) {
        if("DRAFT".equals(order.status()))return;
        BigDecimal produced=c.rootWorkOrders().stream().map(w->reportedQualified(c,lng(w.get("workOrderId"))))
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        LocalDateTime planEnd=maxTime(c.plans.stream().filter(p->"ROOT".equals(str(p.get("planType")))
                && !"CLOSED".equals(str(p.get("status")))).toList(),"plannedEndTime");
        LocalDateTime due=order.deliveryDate()==null?null:order.deliveryDate().atTime(23,59,59);
        Stage shipment=stages.get(stages.size()-1);
        boolean planLate=isPlanLate(c, order, planEnd, produced);
        boolean deliveryLate=isDeliveryLate(c, order, due, shipment);
        if(!planLate && !deliveryLate)return;
        String target=overdueTarget(stages, planLate, deliveryLate);
        Blocker blocker=overdueBlocker(c, order, target, produced, planEnd, due, shipment, deliveryLate);
        attachOverdueBlocker(stages, target, blocker);
    }

    /** 计划逾期：有根计划完工时间、评估时间已过、且产出未达订单量。 */
    private boolean isPlanLate(Collect c,OrderHeader order,LocalDateTime planEnd,BigDecimal produced) {
        return planEnd!=null && c.evaluatedAt.isAfter(planEnd) && produced.compareTo(order.orderQuantity())<0;
    }

    /** 交期逾期：有交期、评估时间已过、且发货量未达订单量。 */
    private boolean isDeliveryLate(Collect c,OrderHeader order,LocalDateTime due,Stage shipment) {
        return due!=null && c.evaluatedAt.isAfter(due) && shipment.executedQuantity().compareTo(order.orderQuantity())<0;
    }

    /** 逾期挂在哪个阶段：交期逾期挂发货，计划逾期优先挂未完成的装配，否则挂终检。 */
    private String overdueTarget(List<Stage> stages, boolean planLate, boolean deliveryLate) {
        String target=deliveryLate?"SHIPMENT":"FINAL_INSPECTION";
        if(planLate && stages.stream().anyMatch(st->"ASSEMBLY".equals(st.code()) && !T_DONE.equals(st.status())))
            target="ASSEMBLY";
        return target;
    }

    private Blocker overdueBlocker(Collect c,OrderHeader order,String target,BigDecimal produced,
            LocalDateTime planEnd,LocalDateTime due,Stage shipment,boolean deliveryLate) {
        Document evidence=orderDocument(c,"交期与计划依据");
        String code = deliveryLate ? "DELIVERY_OVERDUE" : "PLAN_OVERDUE";
        String title = deliveryLate ? "已超过交期，仍未完成交付" : "已超过计划完工时间";
        String basis = deliveryLate ? "订单交期 " + due : "原计划完工 " + planEnd;
        BigDecimal remaining = order.orderQuantity()
                .subtract(deliveryLate ? shipment.executedQuantity() : produced).max(BigDecimal.ZERO);
        return new Blocker(code,target,LV_YELLOW,"SCHEDULE",
                title,
                "评估时间 "+c.evaluatedAt+"；"+basis+"，仍有未完成数量",
                List.of(new Evidence("生产订单",order.productionOrderNo(),evidence)),
                new Impact(c.rootWorkOrders().stream().map(w->str(w.get("workOrderNo"))).toList(),List.of(order.productionOrderNo()),
                        scale(remaining),
                        unitOfOrder(c),"需要核对剩余任务及恢复安排；原计划不能作为新的预计完成承诺"),
                List.of(new Action("OPEN_ORDER","查看订单及计划","NAVIGATE",evidence.targetResource(),evidence.query(),"")));
    }

    private void attachOverdueBlocker(List<Stage> stages,String target,Blocker blocker) {
        for(int i=0;i<stages.size();i++)if(target.equals(stages.get(i).code())) {
            Stage st=stages.get(i);List<Blocker> all=new ArrayList<>(st.blockers());all.add(blocker);
            stages.set(i,new Stage(st.code(),st.seq(),st.name(),st.unit(),T_BLOCKED.equals(st.status())?T_BLOCKED:T_RISK,
                    st.plannedQuantity(),st.executedQuantity(),st.reportedQualifiedQuantity(),st.releasedQuantity(),st.waitingAuditQuantity(),
                    st.waitingInspectionQuantity(),st.defectQuantity(),st.remainingQuantity(),st.plannedStart(),st.plannedEnd(),
                    st.actualStart(),st.actualEnd(),st.actualTimeType(),st.owner(),st.children(),all,st.documents(),st.notes()));
        }
    }


    private String unitForWorkOrder(Collect c,Long wid) {
        return c.workOrders.stream().filter(w->Objects.equals(lng(w.get("workOrderId")),wid))
                .map(this::unit).filter(v->!v.isEmpty()).findFirst().orElse(unitOfOrder(c));
    }

    private String unitForReport(Collect c,Map<String,Object> report) {
        Long wid=c.tasks.stream().filter(t->Objects.equals(lng(t.get("taskId")),lng(report.get("taskId"))))
                .map(t->lng(t.get("workOrderId"))).findFirst().orElse(null);
        return unitForWorkOrder(c,wid);
    }


    private boolean approvedDisposition(Collect c,Long lotId,String type) {
        return c.dispositions.stream().anyMatch(n->Objects.equals(lng(n.get("productLotId")),lotId)
                && "APPROVED".equals(str(n.get("approvalStatus"))) && "CLOSED".equals(str(n.get("status")))
                && type.equals(str(n.get("dispositionType"))));
    }

    private boolean supersededLot(Collect c,Map<String,Object> lot) {
        Long id=lng(lot.get("productLotId"));
        return Set.of("REWORK","SCRAPPED","SCRAP","DOWNGRADE").contains(str(lot.get("status")))
                && (approvedDisposition(c,id,"REWORK") || approvedDisposition(c,id,"SCRAP") || approvedDisposition(c,id,"DOWNGRADE"));
    }


    private Blocker qualityFailureBlocker(Collect c,Long wid,Map<String,Object> lot,Map<String,Object> q,String stage) {
        Document evidence=inspectionDocument(c,q);
        return new Blocker("FINAL_QUALITY_FAILED",stage,LV_RED,"QUALITY","终检不合格："+str(lot.get("lotNo")),
                "批次已有正式不合格判定，需完成处置或返工复检；不能当作待检或已放行",
                List.of(new Evidence("终检单",evidence.docNo(),evidence)),
                new Impact(List.of(c.workOrderNo(wid)),List.of(orderNo(c)),scale(dec(q.get("unqualifiedQuantity"))),
                        unitForWorkOrder(c,wid),"该批次影响组件供给、成品放行及后续交付"),
                List.of(new Action("OPEN_INSPECTION","查看检验及发起处置","NAVIGATE",evidence.targetResource(),evidence.query(),"")));
    }

}
