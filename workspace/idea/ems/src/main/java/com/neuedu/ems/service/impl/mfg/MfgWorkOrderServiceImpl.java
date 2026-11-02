package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.mfg.MfgGenerateRequest;
import com.neuedu.ems.entity.mfg.MfgSupplySource;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgLineProductCapability;
import com.neuedu.ems.entity.mdm.MdmBom;
import com.neuedu.ems.entity.mdm.MdmBomItem;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.mdm.MdmProductParameter;
import com.neuedu.ems.entity.process.ProcessOperation;
import com.neuedu.ems.entity.process.ProcessRoute;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.entity.qms.QmsInspectionScheme;
import com.neuedu.ems.entity.qms.QmsInspectionSchemeItem;
import com.neuedu.ems.entity.qms.QmsInspectionStandard;
import com.neuedu.ems.entity.mfg.MfgTask;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgLineProductCapabilityMapper;
import com.neuedu.ems.mapper.mdm.MdmBomMapper;
import com.neuedu.ems.mapper.mdm.MdmBomItemMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.mdm.MdmProductParameterMapper;
import com.neuedu.ems.mapper.process.ProcessOperationMapper;
import com.neuedu.ems.mapper.process.ProcessRouteMapper;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeItemMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionStandardMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.mfg.MfgWorkOrderService;
import com.neuedu.ems.service.mfg.MfgProductionOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 生产工单管理 Service 实现类。
 */
@Service
public class MfgWorkOrderServiceImpl extends ServiceImpl<MfgWorkOrderMapper, MfgWorkOrder> implements MfgWorkOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;

    @Autowired private com.neuedu.ems.service.mdm.ManufacturingPreparationGate preparationGate;
    @Autowired private com.neuedu.ems.service.mdm.BomMaterialDemandService materialDemands;
    @Autowired private com.neuedu.ems.service.mdm.ProductQuantityService productQuantities;
    @Autowired private com.neuedu.ems.service.mfg.WorkOrderSnapshotService executionSnapshots;
    @Autowired private com.neuedu.ems.service.mfg.ExecutionSnapshotPolicy snapshotPolicy;
    @Autowired private com.neuedu.ems.service.mfg.ComponentSupplyPlanningService componentSupply;
    @Autowired private com.neuedu.ems.service.mfg.SupplyAllocationService supplyAllocations;
    @Autowired private com.neuedu.ems.service.mfg.ComponentCapacityService componentCapacity;
    @Autowired private com.neuedu.ems.mapper.mfg.MfgGenerateRequestMapper generateRequests;
    @Autowired private com.neuedu.ems.mapper.mfg.MfgSupplySourceMapper supplySources;
    @Autowired private com.neuedu.ems.mapper.factory.FactoryLineMapper factoryLines;
    @Autowired private com.neuedu.ems.mapper.mfg.MfgDispatchOrderMapper dispatchOrders;
    @Autowired(required = false) private com.neuedu.ems.service.mfg.ComponentHandoverService componentHandover;
    @Autowired(required = false) private com.neuedu.ems.service.mfg.StockComponentSupplyService stockComponents;

    @Autowired
    private MfgProductionOrderService productionOrderService;

    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 多层组件展开的兜底层数上限；正常循环引用会被 visitedBomIds 先识别。 */
    private static final int MAX_COMPONENT_DEPTH = 8;

    /** 一条组件需求最多拆成多少张补产单；超出说明日历/产能配置不足以支撑该需求。 */
    private static final int MAX_COMPONENT_SPLITS = 12;

    @Autowired
    private MfgProductionPlanMapper productionPlanMapper;

    @Autowired
    private MfgProductionOrderMapper productionOrderMapper;

    @Autowired
    private MdmBomMapper bomMapper;

    @Autowired
    private MdmBomItemMapper bomItemMapper;

    @Autowired
    private MfgWorkOrderMaterialMapper workOrderMaterialMapper;

    @Autowired
    private WmsInventoryMapper inventoryMapper;

    @Autowired
    private MfgLineProductCapabilityMapper lineProductCapabilityMapper;

    @Autowired
    private ProcessRouteMapper routeMapper;

    @Autowired
    private MfgTaskMapper mfgTaskMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private MdmProductMapper mdmProductMapper;

    @Autowired
    private MdmMaterialMapper mdmMaterialMapper;

    @Autowired
    private MdmProductParameterMapper productParameterMapper;

    @Autowired
    private ProcessRouteItemMapper routeItemMapper;

    @Autowired
    private ProcessOperationMapper operationMapper;

    @Autowired
    private QmsInspectionSchemeMapper inspectionSchemeMapper;

    @Autowired
    private QmsInspectionSchemeItemMapper inspectionSchemeItemMapper;

    @Autowired
    private QmsInspectionStandardMapper inspectionStandardMapper;

    @Autowired
    private WmsWarehouseMapper warehouseMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 分页查询生产工单列表，支持关键字搜索、状态筛选、生产计划筛选和产品筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 关键字（匹配工单编号）
     * @param status 工单状态
     * @param planId 生产计划ID
     * @param productId 产品ID
     * @return 分页结果
     */
    @Override
    public Page<MfgWorkOrder> pageWorkOrders(Integer current, Integer size, String keyword, String status, Long planId, Long productId) {
        Page<MfgWorkOrder> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildWorkOrderQuery(keyword, status, planId, productId));
    }

    /**
     * 根据生产工单ID查询生产工单信息。
     * @param workOrderId 生产工单ID
     * @return 生产工单实体
     */
    @Override
    public MfgWorkOrder getWorkOrderById(Long workOrderId) {
        QueryWrapper<MfgWorkOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("work_order_id", workOrderId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    /**
     * 新增生产工单，自动设置删除标志为0。
     * @param workOrder 生产工单实体
     * @return 是否成功
     */
    @Override
    public boolean saveWorkOrder(MfgWorkOrder workOrder) {
        throw business(ResultCode.BUSINESS_ERROR, "生产工单必须从已生效生产计划生成，不能脱离来源手工新增");
    }

    /**
     * 更新生产工单信息，不允许修改删除标志。
     * @param workOrder 生产工单实体
     * @return 是否成功
     */
    @Override
    public boolean updateWorkOrder(MfgWorkOrder workOrder) {
        if (workOrder.getWorkOrderId() == null) {
            return false;
        }
        MfgWorkOrder existing = getWorkOrderById(workOrder.getWorkOrderId());
        if (existing == null || !BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            return false;
        }
        workOrder.setDeletedFlag(null);
        workOrder.setWorkOrderNo(null);
        workOrder.setStatus(null);
        workOrder.setCompletedQuantity(null);
        workOrder.setPlanId(null);
        workOrder.setProductId(null);
        workOrder.setBomId(null);
        workOrder.setRouteId(null);
        workOrder.setPlannedQuantity(null);
        workOrder.setPlannedStartTime(null);
        workOrder.setPlannedEndTime(null);
        workOrder.setWorkOrderType(null);
        workOrder.setParentWorkOrderId(null);
        workOrder.setRootWorkOrderId(null);
        workOrder.setSourceRequirementId(null);
        workOrder.setSnapshotJson(null);
        workOrder.setSnapshotHash(null);
        workOrder.setSnapshotTime(null);
        if (workOrder.getVersion() == null) {
            workOrder.setVersion(existing.getVersion());
        }
        return baseMapper.updateById(workOrder) > 0;
    }

    /**
     * 根据ID删除生产工单（软删除，将删除标志设为1）。
     * @param workOrderId 生产工单ID
     * @return 是否成功
     */
    @Override
    public boolean removeWorkOrderById(Long workOrderId) {
        return removeWorkOrderById(workOrderId, null);
    }

    /**
     * 删除草稿生产工单，并释放它占着的物料预留。
     *
     * <p>草稿工单在下达前可能已经预留了原料库存（预留在可用量上已扣减、记在 locked 里）。
     * 如果只把工单置删除标记而不释放预留，这批数量就既回不到可用量、也没有任何单据能再释放它，
     * 越积越多会把原料仓的可用库存耗干，后续工单全部领不到料。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeWorkOrderById(Long workOrderId, Long operatorId) {
        MfgWorkOrder existing = baseMapper.selectOne(new QueryWrapper<MfgWorkOrder>()
                .eq("work_order_id", workOrderId).eq("deleted_flag", 0).last("FOR UPDATE"));
        if (existing == null || !BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            return false;
        }
        if (componentHandover != null && componentHandover.hasHandover(workOrderId))
            throw business(ResultCode.BUSINESS_ERROR, "工单已有组件交接记录，不能删除，请先核对实际用料");
        MfgWorkOrder workOrder = new MfgWorkOrder();
        workOrder.setWorkOrderId(workOrderId);
        workOrder.setVersion(existing.getVersion());
        workOrder.setDeletedFlag(1);
        if (baseMapper.updateById(workOrder) <= 0) {
            return false;
        }
        supplyAllocations.releaseForWorkOrders(List.of(workOrderId), operatorId, LocalDateTime.now());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeWorkOrders(List<Long> workOrderIds, Long operatorId) {
        if (workOrderIds == null || workOrderIds.isEmpty() || workOrderIds.size() > 200
                || workOrderIds.stream().anyMatch(Objects::isNull)) {
            throw business(ResultCode.PARAM_ERROR, "请选择1至200张工单");
        }
        List<Long> ids = workOrderIds.stream().distinct().sorted().toList();
        List<MfgWorkOrder> selected = baseMapper.selectList(new QueryWrapper<MfgWorkOrder>()
                .in("work_order_id", ids).eq("deleted_flag", 0).orderByAsc("work_order_id").last("FOR UPDATE"));
        if (selected.size() != ids.size()) throw business(ResultCode.DATA_CONFLICT, "部分工单已删除，请刷新后重试");
        for (MfgWorkOrder row : selected) {
            if (!BusinessStatus.Document.DRAFT.equals(row.getStatus())) {
                throw business(ResultCode.BUSINESS_ERROR, row.getWorkOrderNo() + "不是草稿，整批未删除");
            }
        }
        for (MfgWorkOrder row : selected) {
            if (!removeWorkOrderById(row.getWorkOrderId(), operatorId)) {
                throw business(ResultCode.DATA_CONFLICT, "工单已变化，整批未删除，请刷新后重试");
            }
            insertOperationLog(row.getWorkOrderId(), "BATCH_DELETE_WORK_ORDER", operatorId,
                    "批量删除草稿工单并释放物料预留", LocalDateTime.now());
        }
        return ids.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgWorkOrder rescheduleWorkOrders(Long workOrderId, Long operatorId) {
        MfgWorkOrder selected = getWorkOrderById(workOrderId);
        if (selected == null) throw business(ResultCode.NOT_FOUND, "工单不存在");
        if (operatorId == null) throw business(ResultCode.PARAM_ERROR, "操作人不能为空");
        if (!BusinessStatus.Document.DRAFT.equals(selected.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只能重排未下达的草稿工单，已下达或在制工单保持原安排");
        }
        Long rootId = selected.getRootWorkOrderId() == null ? workOrderId : selected.getRootWorkOrderId();
        MfgWorkOrder root = getWorkOrderById(rootId);
        if (root == null) throw business(ResultCode.BUSINESS_ERROR, "整机工单不存在，无法重新排程");
        MfgProductionPlan initial = productionPlanMapper.selectById(root.getPlanId());
        if (initial == null || initial.getLineId() == null) throw business(ResultCode.BUSINESS_ERROR, "来源计划缺少产线");
        // 同线重排按同一把产线锁串行，计划按主键锁定，避免两次连锁顺延相互穿插。
        factoryLines.selectOne(new QueryWrapper<FactoryLine>().eq("line_id", initial.getLineId()).eq("deleted_flag",0).last("FOR UPDATE"));
        List<MfgProductionPlan> linePlans = productionPlanMapper.selectList(new QueryWrapper<MfgProductionPlan>()
                .eq("line_id", initial.getLineId()).eq("deleted_flag",0)
                .in("status",List.of("DRAFT","RELEASED","RUNNING")).orderByAsc("plan_id").last("FOR UPDATE"));
        MfgProductionPlan plan = linePlans.stream().filter(p -> Objects.equals(p.getPlanId(),initial.getPlanId())).findFirst().orElse(null);
        if (plan == null || !List.of("RELEASED","RUNNING").contains(plan.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已生效或生产中计划下的草稿工单可以重新排程");
        }
        LocalDateTime now = LocalDateTime.now();
        List<MfgProductionPlan> following = new ArrayList<>();
        Map<Long,List<MfgWorkOrder>> followingOrders = new LinkedHashMap<>();
        collectCascadeCandidates(plan, linePlans, following, followingOrders);
        following.sort(java.util.Comparator.comparing(MfgProductionPlan::getPlannedStartTime).thenComparing(MfgProductionPlan::getPlanId));
        List<Long> excluded = following.stream().map(MfgProductionPlan::getPlanId).toList();
        MfgWorkOrder result = scheduleWorkOrderTree(root,plan,operatorId,now,now,excluded);
        cascadeFollowPlans(following, followingOrders, plan, excluded, operatorId, now);
        return result;
    }

    /** 找出排在本计划之后、且还能整体顺延的计划；任何一张已被执行占用的计划都不参与连锁。 */
    private void collectCascadeCandidates(MfgProductionPlan plan, List<MfgProductionPlan> linePlans,
                                          List<MfgProductionPlan> following, Map<Long,List<MfgWorkOrder>> followingOrders) {
        for (MfgProductionPlan candidate : linePlans) {
            if (!isAfterPlan(candidate, plan)) continue;
            List<MfgWorkOrder> rows = baseMapper.selectList(new QueryWrapper<MfgWorkOrder>()
                    .eq("plan_id", candidate.getPlanId()).eq("deleted_flag",0).ne("status","CLOSED")
                    .orderByAsc("work_order_id").last("FOR UPDATE"));
            if (!List.of("DRAFT","RELEASED").contains(candidate.getStatus())) continue;
            if (rows.stream().anyMatch(w -> !"DRAFT".equals(w.getStatus()))) continue;
            if (alreadyExecuted(rows)) continue;
            if (!rows.isEmpty() && rows.stream().filter(w -> "ROOT".equals(w.getWorkOrderType())).count()!=1) continue;
            following.add(candidate);
            followingOrders.put(candidate.getPlanId(), rows);
        }
    }

    /** 候选计划是否排在本计划之后；同一开始时间按计划主键定先后，保证顺序稳定。 */
    private static boolean isAfterPlan(MfgProductionPlan candidate, MfgProductionPlan plan) {
        boolean comparable = !Objects.equals(candidate.getPlanId(), plan.getPlanId()) && hasIndependentPlanInterval(candidate) && plan.getPlannedStartTime() != null;
        if (comparable && candidate.getPlannedStartTime().isAfter(plan.getPlannedStartTime())) {
            return true;
        }
        boolean sameStart = comparable && candidate.getPlannedStartTime().equals(plan.getPlannedStartTime());
        return sameStart && candidate.getPlanId() > plan.getPlanId();
    }

    /** 已派工或已开任务说明计划已经在执行，不能当作可顺延的候选项。 */
    private boolean alreadyExecuted(List<MfgWorkOrder> rows) {
        List<Long> ids = rows.stream().map(MfgWorkOrder::getWorkOrderId).toList();
        if (ids.isEmpty()) return false;
        long dispatches = dispatchOrders.selectCount(new QueryWrapper<com.neuedu.ems.entity.mfg.MfgDispatchOrder>()
                .in("work_order_id", ids).eq("deleted_flag", 0));
        if (dispatches > 0) return true;
        return mfgTaskMapper.selectCount(new LambdaQueryWrapper<MfgTask>()
                .in(MfgTask::getWorkOrderId, ids).eq(MfgTask::getDeletedFlag, 0)) > 0;
    }

    /** 按原排队顺序把受影响的后续计划依次顺延，并记录顺延原因。 */
    private void cascadeFollowPlans(List<MfgProductionPlan> following, Map<Long,List<MfgWorkOrder>> followingOrders,
                                    MfgProductionPlan plan, List<Long> excluded, Long operatorId, LocalDateTime now) {
        LocalDateTime frontier = plan.getPlannedEndTime();
        for (MfgProductionPlan candidate : following) {
            if (!candidate.getPlannedStartTime().isBefore(frontier)) {
                // 保留未受影响的间隔和排队顺序；更后面的计划也不能越过这张计划。
                frontier = candidate.getPlannedEndTime();
                continue;
            }
            LocalDateTime oldStart=candidate.getPlannedStartTime(), oldEnd=candidate.getPlannedEndTime();
            List<MfgWorkOrder> rows = followingOrders.get(candidate.getPlanId());
            if (rows.isEmpty()) {
                reschedulePlanWithoutWorkOrders(candidate,frontier,excluded,operatorId,now);
            } else {
                MfgWorkOrder candidateRoot = rows.stream().filter(w -> "ROOT".equals(w.getWorkOrderType())).findFirst().orElseThrow();
                scheduleWorkOrderTree(candidateRoot,candidate,operatorId,now,frontier,excluded);
            }
            insertOperationLog("mfg_production_plan",candidate.getPlanId(),"CASCADE_RESCHEDULE_PLAN",operatorId,
                    "因前序计划 " + plan.getPlanNo() + " 重排顺延："+oldStart+" ~ "+oldEnd+" → "
                            +candidate.getPlannedStartTime()+" ~ "+candidate.getPlannedEndTime(),now);
            frontier = candidate.getPlannedEndTime();
        }
    }

    private void reschedulePlanWithoutWorkOrders(MfgProductionPlan plan, LocalDateTime earliest,
            List<Long> excluded, Long operatorId, LocalDateTime now) {
        MfgProductionOrder order=activeOrder(plan.getProductionOrderId());
        if (order==null || order.getStatus()==null || !List.of("DRAFT","RELEASED","RUNNING").contains(order.getStatus()))
            throw business(ResultCode.BUSINESS_ERROR,"后续计划 "+plan.getPlanNo()+" 的订单已进入执行或已关闭，顺延已回滚");
        ProcessRoute route=resolveReleasedRoute(order.getProductId(),plan.getLineId());
        List<MfgLineProductCapability> caps=lineProductCapabilityMapper.selectList(new LambdaQueryWrapper<MfgLineProductCapability>()
                .eq(MfgLineProductCapability::getLineId,plan.getLineId()).eq(MfgLineProductCapability::getProductId,order.getProductId())
                .eq(MfgLineProductCapability::getRouteId,route.getRouteId()).eq(MfgLineProductCapability::getStatus,"ENABLE")
                .eq(MfgLineProductCapability::getDeletedFlag,0).orderByAsc(MfgLineProductCapability::getPriorityLevel,MfgLineProductCapability::getCapabilityId));
        var window=componentCapacity.planWorkOrder(factoryLines.selectById(plan.getLineId()),route,plan.getPlannedQuantity(),
                earliest,plan.getPlanId(),caps.isEmpty()?BigDecimal.ONE:caps.get(0).getCapacityFactor(),excluded);
        if (window.produced().compareTo(plan.getPlannedQuantity())<0)
            throw business(ResultCode.BUSINESS_ERROR,"后续计划 "+plan.getPlanNo()+" 在有效日历内排不下，连锁顺延已回滚");
        plan.setPlannedStartTime(window.start());plan.setPlannedEndTime(window.end());
        plan.setUpdatedBy(operatorId);plan.setUpdatedTime(now);
        if(productionPlanMapper.updateById(plan)!=1) throw business(ResultCode.DATA_CONFLICT,"后续计划已变化，顺延已回滚");
    }

    /** 按组件依赖后序排程；当前工单树共用一条线，兄弟组件也必须依次占用产能。 */
    private MfgWorkOrder scheduleWorkOrderTree(MfgWorkOrder root, MfgProductionPlan plan,
                                              Long operatorId, LocalDateTime now) {
        return scheduleWorkOrderTree(root,plan,operatorId,now,now,List.of());
    }

    private MfgWorkOrder scheduleWorkOrderTree(MfgWorkOrder root, MfgProductionPlan plan,
            Long operatorId, LocalDateTime now, LocalDateTime earliest, List<Long> excluded) {
        Long rootId = root.getWorkOrderId();
        List<MfgWorkOrder> family = baseMapper.selectList(new QueryWrapper<MfgWorkOrder>()
                .eq("plan_id", plan.getPlanId()).eq("deleted_flag", 0)
                .and(q -> q.eq("root_work_order_id", rootId).or().eq("work_order_id", rootId))
                .orderByAsc("work_order_id").last("FOR UPDATE"));
        root = family.stream().filter(w -> rootId.equals(w.getWorkOrderId())).findFirst().orElse(null);
        if (root == null || !BusinessStatus.Document.DRAFT.equals(root.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "整机工单已下达，不能通过草稿重排改变其组件依赖；请先处理整机执行安排");
        }
        if (family.stream().anyMatch(w -> "CLOSED".equals(w.getStatus()))) {
            throw business(ResultCode.BUSINESS_ERROR, "本组存在已关闭工单，请先核实组件供给来源后再重排");
        }
        List<Long> pendingIds = family.stream().filter(w -> BusinessStatus.Document.DRAFT.equals(w.getStatus()))
                .map(MfgWorkOrder::getWorkOrderId).toList();
        if (dispatchOrders.selectCount(new QueryWrapper<com.neuedu.ems.entity.mfg.MfgDispatchOrder>()
                .in("work_order_id", pendingIds).eq("deleted_flag", 0)) > 0
                || mfgTaskMapper.selectCount(new LambdaQueryWrapper<MfgTask>()
                .in(MfgTask::getWorkOrderId, pendingIds).eq(MfgTask::getDeletedFlag, 0)) > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "待重排草稿工单已有派工或任务记录，请先核实执行状态，整组未改动");
        }
        List<MfgWorkOrder> ordered = new ArrayList<>();
        appendScheduleOrder(root.getWorkOrderId(), family, ordered, new java.util.HashSet<>());
        if (ordered.size() != family.size()) throw business(ResultCode.DATA_CONFLICT, "工单依赖不完整，不能重新排程");
        FactoryLine line = factoryLines.selectById(plan.getLineId());
        LocalDateTime cursor = plan.getPlannedStartTime() != null && plan.getPlannedStartTime().isAfter(earliest)
                ? plan.getPlannedStartTime() : earliest;
        // 已下达或在制工单占用保持不变；先避开其有效预计结束时间，再安排尚未下达的部分。
        for (MfgWorkOrder row : ordered) {
            if (BusinessStatus.Document.DRAFT.equals(row.getStatus())
                    || BusinessStatus.Production.FINISHED.equals(row.getStatus())) continue;
            LocalDateTime occupiedUntil = row.getPlannedEndTime();
            for (var dispatch : dispatchOrders.selectList(new QueryWrapper<com.neuedu.ems.entity.mfg.MfgDispatchOrder>()
                    .eq("work_order_id", row.getWorkOrderId()).eq("deleted_flag", 0)
                    .notIn("status", List.of("FINISHED", "REJECTED", "CLOSED", "CANCELLED")))) {
                if (dispatch.getDueTime() != null && (occupiedUntil == null || dispatch.getDueTime().isAfter(occupiedUntil)))
                    occupiedUntil = dispatch.getDueTime();
            }
            if (occupiedUntil == null || !occupiedUntil.isAfter(now)) {
                throw business(ResultCode.BUSINESS_ERROR, "在制或已下达工单 " + row.getWorkOrderNo()
                        + " 的执行窗口已过期，请先确认完工/终检或更新有效派工预计时间；未下达工单尚未改动");
            }
            if (occupiedUntil.isAfter(cursor)) cursor = occupiedUntil;
        }
        for (MfgWorkOrder row : ordered) {
            if (!BusinessStatus.Document.DRAFT.equals(row.getStatus())) continue;
            ProcessRoute route = routeMapper.selectById(row.getRouteId());
            if (route == null || !"RELEASED".equals(route.getStatus()) || !Integer.valueOf(0).equals(route.getDeletedFlag())) {
                throw business(ResultCode.BUSINESS_ERROR, "工单工艺路线未发布，不能重新排程");
            }
            List<MfgLineProductCapability> capabilities = lineProductCapabilityMapper.selectList(
                    new LambdaQueryWrapper<MfgLineProductCapability>()
                            .eq(MfgLineProductCapability::getLineId, plan.getLineId())
                            .eq(MfgLineProductCapability::getProductId, row.getProductId())
                            .eq(MfgLineProductCapability::getRouteId, row.getRouteId())
                            .eq(MfgLineProductCapability::getStatus, "ENABLE")
                            .eq(MfgLineProductCapability::getDeletedFlag, 0)
                            .orderByAsc(MfgLineProductCapability::getPriorityLevel, MfgLineProductCapability::getCapabilityId));
            BigDecimal factor = capabilities.isEmpty() ? BigDecimal.ONE : capabilities.get(0).getCapacityFactor();
            var window = componentCapacity.planWorkOrder(line, route, row.getPlannedQuantity(), cursor,
                    plan.getPlanId(), factor, excluded);
            if (window.produced().compareTo(row.getPlannedQuantity()) < 0) {
                throw business(ResultCode.BUSINESS_ERROR, "工单" + row.getWorkOrderNo() + "在有效日历内排不下，请调整产能或拆分计划");
            }
            row.setPlannedStartTime(window.start());
            row.setPlannedEndTime(window.end());
            row.setUpdatedBy(operatorId);
            row.setUpdatedTime(now);
            if (baseMapper.updateById(row) != 1) throw business(ResultCode.DATA_CONFLICT, "工单已变化，重排已回滚");
            insertOperationLog(row.getWorkOrderId(), "RESCHEDULE_WORK_ORDER", operatorId,
                    "仅重排未下达工单，保留已下达/在制/完工安排；组件齐套后装配，同线串行；" + window.explanation(), now);
            cursor = window.end();
            if (row.getWorkOrderId().equals(root.getWorkOrderId())) root = row;
        }
        // 计划范围包含历史已执行部分，不能把生产中计划的开始时间重置到今天。
        LocalDateTime historicalStart=plan.getPlannedStartTime();
        LocalDateTime newStart=ordered.stream().map(MfgWorkOrder::getPlannedStartTime).filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(historicalStart);
        if ("RUNNING".equals(plan.getStatus()) && historicalStart!=null && historicalStart.isBefore(newStart))
            newStart=historicalStart;
        plan.setPlannedStartTime(newStart);
        plan.setPlannedEndTime(ordered.stream().map(MfgWorkOrder::getPlannedEndTime).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(cursor));
        plan.setUpdatedBy(operatorId);
        plan.setUpdatedTime(now);
        if (productionPlanMapper.updateById(plan) != 1) throw business(ResultCode.DATA_CONFLICT, "计划已变化，重排已回滚");
        return root;
    }

    private void appendScheduleOrder(Long id, List<MfgWorkOrder> family, List<MfgWorkOrder> ordered, Set<Long> visited) {
        if (!visited.add(id)) throw business(ResultCode.DATA_CONFLICT, "工单依赖循环，不能排程");
        for (MfgWorkOrder child : family) {
            if (id.equals(child.getParentWorkOrderId())) appendScheduleOrder(child.getWorkOrderId(), family, ordered, visited);
        }
        family.stream().filter(w -> id.equals(w.getWorkOrderId())).findFirst().ifPresent(ordered::add);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgWorkOrder generateWorkOrder(Long planId, MfgWorkOrder request, Long operatorId) {
        if (planId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产计划和操作人不能为空");
        }
        MfgWorkOrder command = request == null ? new MfgWorkOrder() : request;
        MfgProductionPlan plan = activePlan(planId);
        if (plan == null) {
            throw business(ResultCode.NOT_FOUND, "生产计划不存在或已删除");
        }
        // 幂等键：同键同摘要重放原结果；同键不同摘要必须拒绝，不能静默复用。
        String requestKey = trimToNull(command.getRequestKey());
        String requestDigest = generateDigest(planId, plan, command);
        MfgWorkOrder replayed = replayGenerateRequest(requestKey, requestDigest);
        if (replayed != null) {
            return replayed;
        }
        MfgProductionOrder order = requireGeneratablePlan(plan, planId);
        MdmBom bom = resolveReleasedBom(order.getProductId(), plan);
        preparationGate.assertExecutionSupported(order.getProductId());
        ProcessRoute route = resolveReleasedRoute(order.getProductId(), plan.getLineId());
        List<MfgWorkOrderMaterial> materialRequirements = buildMaterialRequirements(
                bom, plan.getPlannedQuantity());
        LocalDateTime now = LocalDateTime.now();
        MfgWorkOrder workOrder = createRootWorkOrder(planId, plan, order, bom, route, command, operatorId, now);
        saveMaterialRequirements(workOrder, bom, materialRequirements, operatorId, now);
        reserveRequirements(materialRequirements, requestKey, operatorId, now);
        // 多层组件：每层先按本级需求预留，再按预留后缺口逐层展开子组件，直到没有组件缺口或触底。
        createComponentWorkOrders(workOrder, workOrder, plan, order, bom.getBomId(), materialRequirements,
                requestKey, operatorId, now, new java.util.LinkedHashSet<>(List.of(bom.getBomId())));
        if (baseMapper.selectCount(new QueryWrapper<MfgWorkOrder>().eq("plan_id", planId).eq("deleted_flag", 0)) > 1) {
            workOrder = scheduleWorkOrderTree(workOrder, plan, operatorId, now);
        }
        if (requestKey != null) {
            MfgGenerateRequest record = new MfgGenerateRequest();
            record.setRequestKey(requestKey);
            record.setPlanId(planId);
            record.setCommand("GENERATE_WORK_ORDER");
            record.setRequestDigest(requestDigest);
            record.setRootWorkOrderId(workOrder.getWorkOrderId());
            record.setOperatorId(operatorId);
            record.setStatus(MfgGenerateRequest.COMPLETED);
            record.setRemark(command.getRemark());
            record.setVersion(0);
            record.setCreatedBy(operatorId);
            record.setCreatedTime(now);
            record.setUpdatedBy(operatorId);
            record.setUpdatedTime(now);
            record.setDeletedFlag(0);
            if (generateRequests.insert(record) != 1) {
                throw business(ResultCode.FAILED, "生成请求幂等记录保存失败");
            }
        }
        insertStatusLog(workOrder.getWorkOrderId(), null, BusinessStatus.Document.DRAFT,
                "GENERATE_WORK_ORDER", operatorId, command.getRemark(), now);
        insertOperationLog(workOrder.getWorkOrderId(), "GENERATE_WORK_ORDER", operatorId, command.getRemark(), now);
        return workOrder;
    }

    /**
     * 幂等重放：同键同摘要直接返回原根工单，同键不同摘要或指向的根工单已不存在则拒绝。
     *
     * @return 可重放的根工单；没有幂等键或首次生成时返回 null
     */
    private MfgWorkOrder replayGenerateRequest(String requestKey, String requestDigest) {
        if (requestKey == null) {
            return null;
        }
        MfgGenerateRequest existing = generateRequests.selectOne(new QueryWrapper<MfgGenerateRequest>()
                .eq("request_key", requestKey).eq("deleted_flag", 0).last("FOR UPDATE"));
        if (existing == null) {
            return null;
        }
        if (!Objects.equals(requestDigest, existing.getRequestDigest())) {
            throw business(ResultCode.DATA_CONFLICT, "同一生成请求键的请求参数已变化，不能复用原结果");
        }
        MfgWorkOrder replayed = baseMapper.selectById(existing.getRootWorkOrderId());
        if (replayed == null || !Integer.valueOf(0).equals(replayed.getDeletedFlag())) {
            throw business(ResultCode.DATA_CONFLICT, "生成请求记录指向的根工单已不存在，请使用新的请求键");
        }
        return replayed;
    }

    /**
     * 校验计划具备生成工单的前置条件，并返回其关联的生产订单。
     */
    private MfgProductionOrder requireGeneratablePlan(MfgProductionPlan plan, Long planId) {
        if (!BusinessStatus.Document.RELEASED.equals(plan.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有 RELEASED 状态的生产计划可以生成工单");
        }
        if (hasActiveWorkOrder(planId)) {
            throw business(ResultCode.BUSINESS_ERROR, "该生产计划已生成工单，不能重复生成");
        }
        MfgProductionOrder order = activeOrder(plan.getProductionOrderId());
        if (order == null || order.getProductId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "生产计划未关联有效产品订单");
        }
        if (plan.getLineId() == null || plan.getPlannedQuantity() == null
                || plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw business(ResultCode.BUSINESS_ERROR, "生产计划缺少有效产线或计划数量");
        }
        return order;
    }

    /**
     * 落库根工单：先插入拿到自增主键，再回填 root_work_order_id 让它自成根。
     */
    private MfgWorkOrder createRootWorkOrder(Long planId, MfgProductionPlan plan, MfgProductionOrder order,
                                             MdmBom bom, ProcessRoute route, MfgWorkOrder command,
                                             Long operatorId, LocalDateTime now) {
        MfgWorkOrder workOrder = new MfgWorkOrder();
        workOrder.setWorkOrderNo(numbers.nextFor("WO","PLAN",planId));
        workOrder.setPlanId(planId);
        workOrder.setProductId(order.getProductId());
        workOrder.setBomId(bom.getBomId());
        workOrder.setRouteId(route.getRouteId());
        // 先写入初始窗口；组件展开完成后按依赖统一重排，整机窗口不能早于组件齐套。
        workOrder.setPlannedStartTime(plan.getPlannedStartTime());
        workOrder.setPlannedEndTime(plan.getPlannedEndTime());
        workOrder.setPlannedQuantity(plan.getPlannedQuantity());
        workOrder.setCompletedQuantity(BigDecimal.ZERO);
        workOrder.setStatus(BusinessStatus.Document.DRAFT);
        workOrder.setWorkOrderType("ROOT");
        workOrder.setRemark(command.getRemark());
        workOrder.setVersion(0);
        workOrder.setCreatedBy(operatorId);
        workOrder.setCreatedTime(now);
        workOrder.setUpdatedBy(operatorId);
        workOrder.setUpdatedTime(now);
        workOrder.setDeletedFlag(0);
        if (baseMapper.insert(workOrder) == 0 || workOrder.getWorkOrderId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }
        // 根工单自成根：先拿到自增主键后再回填 root_work_order_id
        MfgWorkOrder selfRoot = new MfgWorkOrder();
        selfRoot.setWorkOrderId(workOrder.getWorkOrderId());
        selfRoot.setRootWorkOrderId(workOrder.getWorkOrderId());
        selfRoot.setVersion(workOrder.getVersion());
        if (baseMapper.updateById(selfRoot) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "根工单关联更新失败，请重新生成工单");
        }
        workOrder.setRootWorkOrderId(workOrder.getWorkOrderId());
        return workOrder;
    }

    /**
     * 为工单用料需求预留合格公共库存。
     *
     * <p>预留与生成在同一事务：任一需求预留失败整组回滚，不会留下"部分预留"。
     * 预留在生成阶段完成，因为组件补产量必须由预留之后的未覆盖缺口决定。</p>
     *
     * <p>组件需求例外：不由库存预留覆盖，缺口始终等于需求量，组件工单永远按整份数量生成。
     * 半成品仓有没有货，只影响组件工单开工时"领半成品还是领原材料"，不影响要不要建这张工单；
     * 生成阶段就把半成品预留走，会让补产工单凭空消失，也会让并列的组件工单抢同一份库存。</p>
     */
    private void reserveRequirements(List<MfgWorkOrderMaterial> requirements, String requestKey,
                                     Long operatorId, LocalDateTime now) {
        if (requirements == null || requirements.isEmpty()) return;
        for (MfgWorkOrderMaterial requirement : requirements) {
            if ("COMPONENT_WORK_ORDER".equals(requirement.getSupplyType())) {
                skipReservationForComponent(requirement, operatorId, now);
                continue;
            }
            String key = requestKey == null
                    ? "GEN-" + requirement.getRequirementId()
                    : requestKey + "-" + requirement.getRequirementId();
            var reservation = supplyAllocations.reserve(requirement,
                    requirement.getRequiredQuantity(), key, operatorId, now);
            MfgWorkOrderMaterial update = new MfgWorkOrderMaterial();
            update.setRequirementId(requirement.getRequirementId());
            update.setVersion(requirement.getVersion());
            update.setReservedQuantity(reservation.reserved());
            update.setShortageQuantity(reservation.shortage());
            update.setStatus(readinessOf(requirement.getIssuedQuantity(), reservation.reserved(),
                    reservation.shortage()));
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (workOrderMaterialMapper.updateById(update) != 1) {
                throw business(ResultCode.DATA_CONFLICT, "工单用料需求预留回写失败，请重新生成工单");
            }
            requirement.setReservedQuantity(reservation.reserved());
            requirement.setShortageQuantity(reservation.shortage());
            requirement.setStatus(update.getStatus());
        }
    }

    /**
     * 组件需求不占用库存：保留完整缺口，让组件工单按整份数量生成。
     *
     * <p>这条需求的缺口由组件工单供给（mfg_supply_source）而不是由库存预留覆盖，
     * 所以这里只把预留清零、缺口还原成需求量，状态回到"等组件工单"。</p>
     */
    private void skipReservationForComponent(MfgWorkOrderMaterial requirement,
                                             Long operatorId, LocalDateTime now) {
        BigDecimal required = zero(requirement.getRequiredQuantity()).setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal issued = zero(requirement.getIssuedQuantity());
        BigDecimal shortage = required.subtract(issued).max(BigDecimal.ZERO).setScale(4, java.math.RoundingMode.HALF_UP);
        MfgWorkOrderMaterial update = new MfgWorkOrderMaterial();
        update.setRequirementId(requirement.getRequirementId());
        update.setVersion(requirement.getVersion());
        update.setReservedQuantity(BigDecimal.ZERO.setScale(4));
        update.setShortageQuantity(shortage);
        update.setStatus(readinessOf(issued, BigDecimal.ZERO, shortage));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (workOrderMaterialMapper.updateById(update) != 1) {
            throw business(ResultCode.DATA_CONFLICT, "工单用料需求预留回写失败，请重新生成工单");
        }
        requirement.setReservedQuantity(BigDecimal.ZERO.setScale(4));
        requirement.setShortageQuantity(shortage);
        requirement.setStatus(update.getStatus());
    }

    /**
     * 齐套状态口径：已发料与已预留都不再算缺口，只有未被任何有效供给覆盖的数量才等待。
     */
    private String readinessOf(BigDecimal issued, BigDecimal reserved, BigDecimal shortage) {
        if (shortage == null || shortage.signum() <= 0) {
            return "READY";
        }
        BigDecimal covered = zero(issued).add(zero(reserved));
        return covered.signum() > 0 ? "PARTIAL_READY" : "WAIT_READY";
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String generateDigest(Long planId, MfgProductionPlan plan, MfgWorkOrder command) {
        String summary = "plan=" + planId
                + ";quantity=" + (plan.getPlannedQuantity() == null ? "" : plan.getPlannedQuantity().stripTrailingZeros().toPlainString())
                + ";line=" + plan.getLineId()
                + ";remark=" + (command.getRemark() == null ? "" : command.getRemark());
        return sha256(summary);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 生成工单用料需求。
     * <p>只展开当前 BOM 的直接子项：根工单只领用本级的组件与直接原材料，组件工单再领用自己的直接项。
     * 组件行（child_bom_id 非空）标记 supply_type=COMPONENT_WORK_ORDER，由组件工单补产缺口；
     * 其余行标记 supply_type=STOCK，直接由仓库供给。</p>
     */
    private List<MfgWorkOrderMaterial> buildMaterialRequirements(MdmBom bom, BigDecimal plannedQuantity) {
        List<MdmBomItem> items = bomItemMapper.selectList(new LambdaQueryWrapper<MdmBomItem>()
                .eq(MdmBomItem::getBomId, bom.getBomId())
                .eq(MdmBomItem::getDeletedFlag, 0)
                .orderByAsc(MdmBomItem::getSequenceNo, MdmBomItem::getBomItemId));
        if (items == null || items.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "已发布物料清单没有有效明细，不能生成生产工单");
        }

        Map<Long, BigDecimal> requiredByMaterial = materialDemands.calculate(items, plannedQuantity);
        Map<Long, Long> childBomByMaterial = componentSupply.childBomReferences(items);

        List<MfgWorkOrderMaterial> requirements = new ArrayList<>(requiredByMaterial.size());
        for (Map.Entry<Long, BigDecimal> entry : requiredByMaterial.entrySet()) {
            BigDecimal required = entry.getValue().setScale(4, RoundingMode.HALF_UP);
            // 公共可用库存只是供给分析；未预留、未转入本工单前不能标记齐套。
            BigDecimal shortage = required;
            String readiness = "WAIT_READY";

            MfgWorkOrderMaterial requirement = new MfgWorkOrderMaterial();
            requirement.setMaterialId(entry.getKey());
            requirement.setRequiredQuantity(required);
            requirement.setIssuedQuantity(BigDecimal.ZERO.setScale(4));
            requirement.setShortageQuantity(shortage);
            requirement.setStatus(readiness);
            requirement.setSupplyType(childBomByMaterial.containsKey(entry.getKey())
                    ? "COMPONENT_WORK_ORDER" : "STOCK");
            requirements.add(requirement);
        }
        return requirements;
    }

    /**
     * 根工单生成后，按层为缺口组件自动创建组件补产计划与组件工单。
     *
     * <p>缺口直接取预留之后的未覆盖数量：合格库存已经按需求预留并扣减了可用量，
     * 这里再按可用量算一次会把同一份库存算成两次供给。</p>
     *
     * <p>每一层都先为本级需求预留，再按预留后的缺口决定是否继续展开下一层，
     * 因此多层组件的库存也只会被分配一次。{@code visitedBomIds} 记录本次生成已经展开过的
     * BOM 版本，重复出现即视为循环引用；{@code depth} 只是兜底上限。</p>
     *
     * @param parentBomId     本层需求所属的父BOM：根层是根工单BOM，下层是上一层的组件BOM，
     *                        组件能力/路线必须按父BOM明确引用的子版本解析，不能都拿根BOM去查。
     * @param parentWorkOrder 本层需求所属的工单：根层是根工单，下层是上一层的组件工单。
     *                        孙层工单的 parent_work_order_id 必须指向真正供给它的组件工单，
     *                        否则整棵树会被压平成"根工单 + 一堆直接子工单"。
     * @param parentPlan      本层需求所属的计划，用于组件计划回填 parent_plan_id。
     */
    private void createComponentWorkOrders(MfgWorkOrder rootWorkOrder, MfgWorkOrder parentWorkOrder,
                                           MfgProductionPlan parentPlan, MfgProductionOrder rootOrder,
                                           Long parentBomId,
                                           List<MfgWorkOrderMaterial> rootRequirements,
                                           String requestKey, Long operatorId, LocalDateTime now,
                                           Set<Long> visitedBomIds) {
        if (visitedBomIds.size() > MAX_COMPONENT_DEPTH) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "组件BOM层级超过 " + MAX_COMPONENT_DEPTH + " 层，请检查物料清单是否循环引用");
        }
        for (MfgWorkOrderMaterial requirement : rootRequirements) {
            createComponentForRequirement(rootWorkOrder, parentWorkOrder, parentPlan, rootOrder, parentBomId,
                    requirement, requestKey, operatorId, now, visitedBomIds);
        }
    }

    /** 单条组件需求：解析产线与资源，按可排产能拆成多张补产单并回写首张供给来源。 */
    private void createComponentForRequirement(MfgWorkOrder rootWorkOrder, MfgWorkOrder parentWorkOrder,
                                               MfgProductionPlan parentPlan, MfgProductionOrder rootOrder,
                                               Long parentBomId, MfgWorkOrderMaterial requirement,
                                               String requestKey, Long operatorId, LocalDateTime now,
                                               Set<Long> visitedBomIds) {
        if (!"COMPONENT_WORK_ORDER".equals(requirement.getSupplyType())) {
            return;
        }
        BigDecimal shortage = componentShortage(requirement);
        if (shortage.signum() <= 0) {
            return;
        }
        // 组件跟随根计划的产线：一张工单选一条产线，就在这条线上把整台产品做出来，
        // 不再按组件自己的能力优先级另找产线，也就不会顺带占掉别的产线。
        var resources = resolveComponentResources(parentBomId, requirement, parentPlan, now);
        if (!java.util.Objects.equals(resources.line().getLineId(), parentPlan.getLineId())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "当前计划产线未配置该组件的生产能力，请维护产线产品能力后重试；不能生成隐含占用其他产线的工单");
        }
        MdmProduct componentProduct = resources.product();
        MdmBom componentBom = resources.bom();
        if (visitedBomIds.contains(componentBom.getBomId())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "组件物料清单存在循环引用（BOM " + componentBom.getBomId() + " 重复展开），不能生成工单");
        }
        productQuantities.validate(componentProduct.getProductId(), shortage);

        // 一条组件需求在有效日历产能窗口内可能排不下：按组件自身产线的周历/班次/日产能拆成多张补产单，
        // 每张补产单的供给数量都登记到 mfg_supply_source，缺口清零即停止，不再凭父计划时间硬塞。
        // 组件从"现在"开始在这条产线上排：父计划占住整机装配的时段后，
        // 组件会先填满父计划开工之前的空档，于是灯头/灯臂/底座先做出来、再进整机装配，
        // 每产出一套就喂给装配，而不是等整机装配完了才回头做半成品。
        Long firstWorkOrderId = createSplitWorkOrders(rootWorkOrder, parentWorkOrder, parentPlan, rootOrder,
                requirement, resources, shortage, requestKey, operatorId, now, visitedBomIds);
        // 单一来源字段保留为"首张补产单"，多来源明细在 mfg_supply_source。
        MfgWorkOrderMaterial link = new MfgWorkOrderMaterial();
        link.setRequirementId(requirement.getRequirementId());
        link.setSourceWorkOrderId(firstWorkOrderId);
        link.setVersion(requirement.getVersion());
        link.setUpdatedBy(operatorId);
        link.setUpdatedTime(now);
        if (workOrderMaterialMapper.updateById(link) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "组件需求供给关联失败，请重新生成工单");
        }
    }

    /** 组件缺口数量：优先取缺口量，否则取需求量，均为非负四位小数。 */
    private BigDecimal componentShortage(MfgWorkOrderMaterial requirement) {
        BigDecimal shortage = requirement.getShortageQuantity() == null
                ? requirement.getRequiredQuantity()
                : requirement.getShortageQuantity();
        return shortage.max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
    }

    private com.neuedu.ems.service.mfg.ComponentSupplyPlanningService.ComponentResources resolveComponentResources(
            Long parentBomId, MfgWorkOrderMaterial requirement, MfgProductionPlan parentPlan, LocalDateTime now) {
        LocalDateTime plannedStart = parentPlan.getPlannedStartTime();
        return componentSupply.resolve(parentBomId, requirement.getMaterialId(),
                plannedStart == null ? now.toLocalDate() : plannedStart.toLocalDate(),
                parentPlan.getLineId());
    }

    /** 按产能窗口循环拆分补产单，返回首张补产单主键；每拆一张就同步下单用料、预留、供给来源与日志。 */
    private Long createSplitWorkOrders(MfgWorkOrder rootWorkOrder, MfgWorkOrder parentWorkOrder,
                                       MfgProductionPlan parentPlan, MfgProductionOrder rootOrder,
                                       MfgWorkOrderMaterial requirement,
                                       com.neuedu.ems.service.mfg.ComponentSupplyPlanningService.ComponentResources resources,
                                       BigDecimal shortage, String requestKey, Long operatorId, LocalDateTime now,
                                       Set<Long> visitedBomIds) {
        BigDecimal remaining = shortage;
        int sequence = 0;
        LocalDateTime earliestStart = now;
        Long firstWorkOrderId = null;
        while (remaining.signum() > 0) {
            sequence++;
            if (sequence > MAX_COMPONENT_SPLITS) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "组件 " + requirement.getMaterialId() + " 的补产在有效日历产能内需要超过 "
                                + MAX_COMPONENT_SPLITS + " 张补产单，请先维护产线周历与产能");
            }
            var window = componentCapacity.plan(resources.line(), resources.route(), remaining,
                    earliestStart, null, ancestorPlanIds(parentPlan, resources.line()),
                    resources.capacityFactor());
            if (window.produced().signum() <= 0) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "组件 " + requirement.getMaterialId() + " 在有效日历产能内没有可用窗口，"
                                + "请先维护组件产线周历、班次与日产能");
            }
            BigDecimal produced = window.produced().setScale(4, RoundingMode.HALF_UP);
            MfgWorkOrder componentWorkOrder = insertSplitWorkOrder(rootWorkOrder, parentWorkOrder, parentPlan,
                    rootOrder, requirement, resources, window, produced, sequence, requestKey, operatorId, now,
                    visitedBomIds);
            if (firstWorkOrderId == null) {
                firstWorkOrderId = componentWorkOrder.getWorkOrderId();
            }
            remaining = remaining.subtract(produced).setScale(4, RoundingMode.HALF_UP);
            earliestStart = window.end();
        }
        return firstWorkOrderId;
    }

    /** 生成一张组件补产工单：落库、存用料、预留库存、登记供给来源、写日志并向下一层展开。 */
    private MfgWorkOrder insertSplitWorkOrder(MfgWorkOrder rootWorkOrder, MfgWorkOrder parentWorkOrder,
                                              MfgProductionPlan parentPlan, MfgProductionOrder rootOrder,
                                              MfgWorkOrderMaterial requirement,
                                              com.neuedu.ems.service.mfg.ComponentSupplyPlanningService.ComponentResources resources,
                                              com.neuedu.ems.service.mfg.ComponentCapacityService.Plan window,
                                              BigDecimal produced, int sequence, String requestKey,
                                              Long operatorId, LocalDateTime now, Set<Long> visitedBomIds) {
        // 一张订单一张计划：组件工单直接挂在这唯一一张生产计划上，不再另建 PLN-C-* 计划。
        // 组件自己在同一条产线上的窗口落到工单自身的 planned_start_time / planned_end_time 上，
        // 由前端工单列表与进度看板直接读取，不需要再靠计划表 JOIN 出时间。
        MdmBom componentBom = resources.bom();
        List<MfgWorkOrderMaterial> componentRequirements = buildMaterialRequirements(componentBom, produced);
        MfgWorkOrder componentWorkOrder = new MfgWorkOrder();
        componentWorkOrder.setWorkOrderNo(numbers.nextFor("WO","PLAN",parentPlan.getPlanId()));
        componentWorkOrder.setPlanId(parentPlan.getPlanId());
        componentWorkOrder.setPlannedStartTime(window.start());
        componentWorkOrder.setPlannedEndTime(window.end());
        componentWorkOrder.setProductId(resources.product().getProductId());
        componentWorkOrder.setBomId(componentBom.getBomId());
        componentWorkOrder.setRouteId(resources.route().getRouteId());
        componentWorkOrder.setPlannedQuantity(produced);
        componentWorkOrder.setCompletedQuantity(BigDecimal.ZERO);
        componentWorkOrder.setStatus(BusinessStatus.Document.DRAFT);
        componentWorkOrder.setWorkOrderType("COMPONENT");
        componentWorkOrder.setParentWorkOrderId(parentWorkOrder.getWorkOrderId());
        componentWorkOrder.setRootWorkOrderId(rootWorkOrder.getWorkOrderId());
        componentWorkOrder.setSourceRequirementId(requirement.getRequirementId());
        componentWorkOrder.setRemark("由工单 " + parentWorkOrder.getWorkOrderNo() + " 的组件缺口自动生成");
        componentWorkOrder.setVersion(0);
        componentWorkOrder.setCreatedBy(operatorId);
        componentWorkOrder.setCreatedTime(now);
        componentWorkOrder.setUpdatedBy(operatorId);
        componentWorkOrder.setUpdatedTime(now);
        componentWorkOrder.setDeletedFlag(0);
        if (baseMapper.insert(componentWorkOrder) == 0 || componentWorkOrder.getWorkOrderId() == null) {
            throw business(ResultCode.FAILED, "组件补产工单生成失败");
        }
        saveMaterialRequirements(componentWorkOrder, componentBom, componentRequirements, operatorId, now);
        // 组件工单自身的用料也要先预留：否则下一层缺口仍按"可用量"计算，
        // 同一份库存会被根需求与组件需求各算一次供给。
        reserveRequirements(componentRequirements, requestKey, operatorId, now);
        // 每条供给来源单独登记：一条需求可以有多张补产单，单一 source_work_order_id 表达不了。
        saveSupplySource(requirement, parentPlan.getPlanId(), componentWorkOrder, produced, sequence,
                requestKey, operatorId, now);
        insertStatusLog(componentWorkOrder.getWorkOrderId(), null, BusinessStatus.Document.DRAFT,
                "GENERATE_COMPONENT_WORK_ORDER", operatorId,
                "工单 " + parentWorkOrder.getWorkOrderNo() + " 组件缺口", now);
        insertOperationLog(componentWorkOrder.getWorkOrderId(), "GENERATE_COMPONENT_WORK_ORDER",
                operatorId, componentWorkOrder.getRemark(), now);
        // 继续向下展开：组件自己的缺口组件同样在【同一张计划】下建工单，库存同样只分配一次。
        Set<Long> childVisited = new java.util.LinkedHashSet<>(visitedBomIds);
        childVisited.add(componentBom.getBomId());
        createComponentWorkOrders(rootWorkOrder, componentWorkOrder, parentPlan, rootOrder,
                componentBom.getBomId(), componentRequirements, requestKey, operatorId, now,
                childVisited);
        return componentWorkOrder;
    }

    /**
     * 组件产线是否就是父计划所在产线。
     *
     * <p>同一张工单同一条产线时，组件与整机共享这条线的产能池：父计划已经占住的时段不再重复扣一次，
     * 否则组件会被排到整机装完之后，变成"先整机后半成品"。算出占用时段后仍按产能折算，
     * 不会凭空多出产能。</p>
     */
    private boolean sameLineAsParent(MfgProductionPlan parentPlan, FactoryLine line) {
        return parentPlan != null && parentPlan.getLineId() != null && line != null
                && Objects.equals(parentPlan.getLineId(), line.getLineId());
    }

    /**
     * 组件与父计划同线时，父计划及其祖先链的计划主键集合。
     *
     * <p>组件排到父计划那条线上以后，父计划本身也会被产能占用查询算进来：它是同一条线上的一张
     * 有效计划。这里把父计划家族显式排除，让组件从当前时刻起填这条线的空档（父计划开工之前
     * 和之后的空闲时段都能用），从而做到"半成品先做、每产出一套就进装配"。
     * 不同线时返回 null，表示不做额外排除。</p>
     */
    private java.util.Collection<Long> ancestorPlanIds(MfgProductionPlan parentPlan, FactoryLine line) {
        if (!sameLineAsParent(parentPlan, line)) {
            return null;
        }
        Set<Long> planIds = new java.util.LinkedHashSet<>();
        MfgProductionPlan current = parentPlan;
        int guard = 0;
        while (current != null && current.getPlanId() != null && guard++ < MAX_COMPONENT_DEPTH) {
            planIds.add(current.getPlanId());
            Long parentId = current.getParentPlanId();
            current = parentId == null ? null : productionPlanMapper.selectById(parentId);
        }
        return planIds;
    }

    /** 登记一条需求的供给来源明细，供 PC 与追溯核对一条需求被拆成哪几张补充工单。 */
    private void saveSupplySource(MfgWorkOrderMaterial requirement, Long planId,
                                  MfgWorkOrder workOrder, BigDecimal quantity, int sequence,
                                  String requestKey, Long operatorId, LocalDateTime now) {
        MfgSupplySource source = new MfgSupplySource();
        source.setSourceNo("SS-" + requirement.getRequirementId() + "-" + sequence);
        source.setRequirementId(requirement.getRequirementId());
        source.setWorkOrderId(workOrder.getWorkOrderId());
        source.setPlanId(planId);
        source.setMaterialId(requirement.getMaterialId());
        source.setSupplyType("COMPONENT_WORK_ORDER");
        source.setPlannedQuantity(quantity);
        source.setSequenceNo(sequence);
        source.setStatus(MfgSupplySource.PLANNED);
        source.setRequestKey(requestKey);
        source.setRemark("由工单 " + workOrder.getWorkOrderNo() + " 供给需求 " + requirement.getRequirementId());
        source.setVersion(0);
        source.setCreatedBy(operatorId);
        source.setCreatedTime(now);
        source.setUpdatedBy(operatorId);
        source.setUpdatedTime(now);
        source.setDeletedFlag(0);
        if (supplySources.insert(source) == 0) {
            throw business(ResultCode.FAILED, "组件供给来源登记失败");
        }
    }

    private void saveMaterialRequirements(MfgWorkOrder workOrder, MdmBom bom,
                                          List<MfgWorkOrderMaterial> requirements,
                                          Long operatorId, LocalDateTime now) {
        for (MfgWorkOrderMaterial requirement : requirements) {
            requirement.setWorkOrderId(workOrder.getWorkOrderId());
            requirement.setRemark("由物料清单 " + bom.getBomId() + " 自动生成");
            requirement.setVersion(0);
            requirement.setCreatedBy(operatorId);
            requirement.setCreatedTime(now);
            requirement.setUpdatedBy(operatorId);
            requirement.setUpdatedTime(now);
            requirement.setDeletedFlag(0);
            if (workOrderMaterialMapper.insert(requirement) == 0) {
                throw business(ResultCode.FAILED, "工单用料需求生成失败");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgWorkOrder releaseWorkOrder(Long workOrderId, Long operatorId, String remark) {
        MfgWorkOrder existing = lockReleasableWorkOrder(workOrderId);
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "操作人不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        MfgProductionPlan sourcePlan = requireEffectiveSourcePlan(existing);
        LocalDateTime plannedStart = existing.getPlannedStartTime() != null
                ? existing.getPlannedStartTime() : sourcePlan.getPlannedStartTime();
        LocalDateTime plannedEnd = existing.getPlannedEndTime() != null
                ? existing.getPlannedEndTime() : sourcePlan.getPlannedEndTime();
        requireReleaseWindow(plannedStart, plannedEnd, now);
        validateSharedPlanComponent(existing, sourcePlan, plannedStart, plannedEnd);
        requireWorkOrderMaterials(workOrderId);
        ReleaseSnapshot snapshot = freezeExecutionSnapshot(existing, plannedStart, now, operatorId);
        MfgWorkOrder update = buildReleaseUpdate(existing, snapshot, operatorId, now, remark);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "生产工单已被其他用户修改，请刷新后重试");
        }
        insertStatusLog(workOrderId, existing.getStatus(), BusinessStatus.Document.RELEASED,
                "RELEASE_WORK_ORDER", operatorId, remark, now);
        insertOperationLog(workOrderId, "RELEASE_WORK_ORDER", operatorId,
                releaseRemark(snapshot, remark), now);
        return getWorkOrderById(workOrderId);
    }

    /** 撤回后再次下达时，操作日志要说明沿用的是原冻结依据。 */
    private static String releaseRemark(ReleaseSnapshot snapshot, String remark) {
        if (!snapshot.retained()) {
            return remark;
        }
        String kept = "撤回后再次下达，沿用已校验的原冻结依据；";
        return remark == null ? kept : kept + remark;
    }

    /** 下达时冻结下来的执行依据，以及它是否沿用了撤回前的原快照。 */
    private record ReleaseSnapshot(String json, boolean retained) {
    }

    /** 锁住工单行，并确认它存在、产品可执行、状态允许下达。 */
    private MfgWorkOrder lockReleasableWorkOrder(Long workOrderId) {
        MfgWorkOrder existing = baseMapper.selectOne(new QueryWrapper<MfgWorkOrder>()
                .eq("work_order_id", workOrderId).eq("deleted_flag", 0).last("FOR UPDATE"));
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "生产工单不存在或已删除");
        }
        preparationGate.assertExecutionSupported(existing.getProductId());
        if (!List.of(BusinessStatus.Document.DRAFT, BusinessStatus.Document.APPROVED).contains(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有 DRAFT 或 APPROVED 状态的生产工单可以下达");
        }
        return existing;
    }

    /** 工单必须有计划、物料清单、工艺路线，且来源计划已生效。 */
    private MfgProductionPlan requireEffectiveSourcePlan(MfgWorkOrder existing) {
        if (existing.getPlanId() == null || existing.getBomId() == null || existing.getRouteId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单缺少有效生产计划、物料清单或工艺路线");
        }
        MfgProductionPlan sourcePlan = productionPlanMapper.selectOne(new LambdaQueryWrapper<MfgProductionPlan>()
                .eq(MfgProductionPlan::getPlanId, existing.getPlanId())
                .eq(MfgProductionPlan::getDeletedFlag, 0));
        if (sourcePlan == null || !List.of(BusinessStatus.Document.RELEASED, BusinessStatus.Production.RUNNING)
                .contains(sourcePlan.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单的来源计划不存在或尚未生效");
        }
        return sourcePlan;
    }

    /**
     * 下达窗口校验：开工时间不能已经过去，完工时间必须还在未来。
     *
     * <p>工单自己的执行窗口优先：一张计划下有多张工单时，组件工单的时间是它自己在这条产线上的窗口，
     * 不是整张计划的时间；只有工单没自己的时间（历史数据）才回落到计划时间。</p>
     */
    private void requireReleaseWindow(LocalDateTime plannedStart, LocalDateTime plannedEnd, LocalDateTime now) {
        if (plannedStart == null || plannedEnd == null
                || plannedStart.isBefore(now.minusMinutes(2))
                || !plannedEnd.isAfter(now)) {
            throw business(ResultCode.BUSINESS_ERROR, "工单执行窗口已过期，请先使用“重排未下达工单”安排新时间，再下达工单");
        }
    }

    /** 没有用料需求的工单不能下达。 */
    private void requireWorkOrderMaterials(Long workOrderId) {
        if (workOrderMaterialMapper.selectCount(new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                .eq(MfgWorkOrderMaterial::getWorkOrderId, workOrderId)
                .eq(MfgWorkOrderMaterial::getDeletedFlag, 0)) == 0) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单没有有效用料需求，不能下达");
        }
    }

    /**
     * 下达即冻结执行依据：物料清单、工艺路线、产品参数、检验方案全部落快照，后续执行只读快照。
     *
     * <p>撤回只回退执行状态，不撤销冻结依据：工单已带快照时沿用原版本，不拿当前主数据覆盖；
     * 备料受控产品还要核对草稿需求与待冻结 BOM 是否一致，不一致就拒绝，绝不静默换执行依据。</p>
     */
    private ReleaseSnapshot freezeExecutionSnapshot(MfgWorkOrder existing, LocalDateTime plannedStart,
                                                    LocalDateTime now, Long operatorId) {
        MdmProduct executionProduct = mdmProductMapper.selectById(existing.getProductId());
        boolean controlled = executionProduct != null
                && Integer.valueOf(1).equals(executionProduct.getPreparationControlled());
        if (!controlled) {
            return new ReleaseSnapshot(buildExecutionSnapshot(existing), false);
        }
        boolean retained = existing.getSnapshotJson() != null || existing.getSnapshotHash() != null;
        String snapshotJson = retained
                ? retainedSnapshotJson(existing)
                : executionSnapshots.freeze(existing, plannedStart.toLocalDate(), now, operatorId);
        requireDraftDemandsMatchSnapshot(existing, snapshotJson);
        return new ReleaseSnapshot(snapshotJson, retained);
    }

    /** 撤回后重新下达时取回原冻结依据，顺带校验它能被正常解析。 */
    private String retainedSnapshotJson(MfgWorkOrder order) {
        snapshotPolicy.read(order);
        return order.getSnapshotJson();
    }

    /** 草稿需求必须与待冻结快照折算出的需求完全一致（物料集合与数量都要对得上）。 */
    private void requireDraftDemandsMatchSnapshot(MfgWorkOrder existing, String snapshotJson) {
        MfgWorkOrder candidate = new MfgWorkOrder();
        org.springframework.beans.BeanUtils.copyProperties(existing, candidate);
        candidate.setSnapshotJson(snapshotJson);
        candidate.setSnapshotHash(com.neuedu.ems.service.mfg.ExecutionSnapshotPolicy.hash(snapshotJson));
        Map<Long, BigDecimal> expected = snapshotPolicy.directDemands(
                snapshotPolicy.read(candidate), existing.getPlannedQuantity());
        Map<Long, BigDecimal> actual = new LinkedHashMap<>();
        for (MfgWorkOrderMaterial requirement : workOrderMaterialMapper.selectList(
                new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .eq(MfgWorkOrderMaterial::getWorkOrderId, existing.getWorkOrderId())
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0)
                        .orderByAsc(MfgWorkOrderMaterial::getRequirementId).last("FOR UPDATE"))) {
            actual.merge(requirement.getMaterialId(), requirement.getRequiredQuantity(), BigDecimal::add);
        }
        boolean sameKeys = expected.keySet().equals(actual.keySet());
        boolean sameValues = expected.entrySet().stream()
                .allMatch(entry -> entry.getValue().compareTo(actual.get(entry.getKey())) == 0);
        if (!sameKeys || !sameValues) {
            throw business(ResultCode.DATA_CONFLICT, "草稿需求与待冻结BOM不一致，请撤回重建，不得静默更换执行依据");
        }
    }

    /** 组装下达要写回工单的字段；沿用原快照时，快照时间也保留原值。 */
    private MfgWorkOrder buildReleaseUpdate(MfgWorkOrder existing, ReleaseSnapshot snapshot, Long operatorId,
                                            LocalDateTime now, String remark) {
        MfgWorkOrder update = new MfgWorkOrder();
        update.setWorkOrderId(existing.getWorkOrderId());
        update.setStatus(BusinessStatus.Document.RELEASED);
        update.setVersion(existing.getVersion());
        update.setRemark(StringUtils.hasText(remark) ? remark : existing.getRemark());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        update.setSnapshotJson(snapshot.json());
        update.setSnapshotHash(sha256(snapshot.json()));
        update.setSnapshotTime(snapshot.retained() ? retainedSnapshotTime(existing) : now);
        return update;
    }

    private LocalDateTime retainedSnapshotTime(MfgWorkOrder order) {
        return order.getSnapshotTime()!=null ? order.getSnapshotTime()
                : LocalDateTime.parse(snapshotPolicy.read(order).path("snapshotTime").asText());
    }

    /** 一计划多工单后，组件自身必须接续原组件计划的来源、数量及资源复核，不能绕过。 */
    private void validateSharedPlanComponent(MfgWorkOrder child, MfgProductionPlan plan,
                                              LocalDateTime start, LocalDateTime end) {
        if (!"COMPONENT".equals(child.getWorkOrderType())) return;
        requireOpenComponentOrder(plan);
        MfgWorkOrder parent = child.getParentWorkOrderId() == null ? null : baseMapper.selectById(child.getParentWorkOrderId());
        MfgWorkOrderMaterial requirement = child.getSourceRequirementId() == null ? null
                : workOrderMaterialMapper.selectById(child.getSourceRequirementId());
        requireValidParentRequirement(parent, requirement);
        requireSamePlanOwner(parent, plan);
        requireSameSupplySource(child, plan, requirement);
        var resource = componentSupply.resolve(parent.getBomId(), requirement.getMaterialId(), start.toLocalDate(), plan.getLineId());
        requireSameResource(resource, child, plan);
        componentCapacity.assertSchedulable(resource.line(), resource.route(), child.getPlannedQuantity(),
                start, end, plan.getPlanId(), resource.capacityFactor(), ancestorPlanIds(plan, resource.line()));
    }

    /** 组件工单的父订单必须处于已下达或生产中。 */
    private void requireOpenComponentOrder(MfgProductionPlan plan) {
        MfgProductionOrder order = activeOrder(plan.getProductionOrderId());
        if (!isOpenOrder(order)) {
            throw business(ResultCode.BUSINESS_ERROR, "组件来源订单已关闭或未下达");
        }
    }

    private boolean isOpenOrder(MfgProductionOrder order) {
        return order != null && List.of("RELEASED", "RUNNING").contains(order.getStatus());
    }

    /** 父工单与来源需求都必须有效，且需求必须挂在父工单上并按组件工单供给。 */
    private void requireValidParentRequirement(MfgWorkOrder parent, MfgWorkOrderMaterial requirement) {
        if (!isValidParent(parent, requirement)) {
            throw business(ResultCode.BUSINESS_ERROR, "组件父工单或来源需求无效，请重新安排供给");
        }
    }

    private boolean isValidParent(MfgWorkOrder parent, MfgWorkOrderMaterial requirement) {
        if (!isUsableParent(parent)) return false;
        return isUsableRequirement(requirement, parent) && "COMPONENT_WORK_ORDER".equals(requirement.getSupplyType());
    }

    /** 父工单存在、未删除且处于草稿/已审核/已下达/生产中。 */
    private boolean isUsableParent(MfgWorkOrder parent) {
        return parent != null
                && Integer.valueOf(0).equals(parent.getDeletedFlag())
                && List.of("DRAFT", "APPROVED", "RELEASED", "RUNNING").contains(parent.getStatus());
    }

    /** 来源需求存在、未删除，且挂在该父工单下。 */
    private boolean isUsableRequirement(MfgWorkOrderMaterial requirement, MfgWorkOrder parent) {
        return requirement != null
                && Integer.valueOf(0).equals(requirement.getDeletedFlag())
                && java.util.Objects.equals(requirement.getWorkOrderId(), parent.getWorkOrderId());
    }

    /** 父工单所在计划必须同属当前订单，且是本计划或其父计划。 */
    private void requireSamePlanOwner(MfgWorkOrder parent, MfgProductionPlan plan) {
        MfgProductionPlan parentPlan = productionPlanMapper.selectById(parent.getPlanId());
        if (!isSamePlanOwner(parentPlan, parent, plan)) {
            throw business(ResultCode.BUSINESS_ERROR, "组件与父工单的计划归属不一致");
        }
    }

    private boolean isSamePlanOwner(MfgProductionPlan parentPlan, MfgWorkOrder parent, MfgProductionPlan plan) {
        return isUsablePlan(parentPlan, plan) && isPlanOfParent(parent, plan);
    }

    private boolean isUsablePlan(MfgProductionPlan parentPlan, MfgProductionPlan plan) {
        return parentPlan != null
                && Integer.valueOf(0).equals(parentPlan.getDeletedFlag())
                && java.util.Objects.equals(parentPlan.getProductionOrderId(), plan.getProductionOrderId());
    }

    private boolean isPlanOfParent(MfgWorkOrder parent, MfgProductionPlan plan) {
        return java.util.Objects.equals(parent.getPlanId(), plan.getPlanId())
                || java.util.Objects.equals(parent.getPlanId(), plan.getParentPlanId());
    }

    /** 供给来源明细必须与来源需求、组件数量一致。 */
    private void requireSameSupplySource(MfgWorkOrder child, MfgProductionPlan plan, MfgWorkOrderMaterial requirement) {
        var source = supplySources.selectOne(new QueryWrapper<MfgSupplySource>()
                .eq("work_order_id", child.getWorkOrderId()).eq("plan_id", plan.getPlanId())
                .eq("deleted_flag", 0).ne("status", "CLOSED").last("FOR UPDATE"));
        if (!isSameSupplySource(source, requirement, child)) {
            throw business(ResultCode.DATA_CONFLICT, "组件数量与供给来源明细不一致，请重建草稿");
        }
    }

    private boolean isSameSupplySource(MfgSupplySource source, MfgWorkOrderMaterial requirement, MfgWorkOrder child) {
        if (source == null) return false;
        return isSameSupplyRequirement(source, requirement) && isSamePlannedQuantity(source, child);
    }

    private boolean isSameSupplyRequirement(MfgSupplySource source, MfgWorkOrderMaterial requirement) {
        return java.util.Objects.equals(source.getRequirementId(), requirement.getRequirementId())
                && java.util.Objects.equals(source.getMaterialId(), requirement.getMaterialId())
                && "COMPONENT_WORK_ORDER".equals(source.getSupplyType());
    }

    private boolean isSamePlannedQuantity(MfgSupplySource source, MfgWorkOrder child) {
        return source.getPlannedQuantity() != null && child.getPlannedQuantity() != null
                && source.getPlannedQuantity().compareTo(child.getPlannedQuantity()) == 0;
    }

    /** 组件工单的产线、物料清单与工艺路线必须与当前解析结果一致。 */
    private void requireSameResource(com.neuedu.ems.service.mfg.ComponentSupplyPlanningService.ComponentResources resource,
                                     MfgWorkOrder child, MfgProductionPlan plan) {
        if (!isSameResource(resource, child, plan)) {
            throw business(ResultCode.BUSINESS_ERROR, "组件工单与当前产线、物料清单或工艺路线不一致");
        }
    }

    private boolean isSameResource(com.neuedu.ems.service.mfg.ComponentSupplyPlanningService.ComponentResources resource,
                                   MfgWorkOrder child, MfgProductionPlan plan) {
        return java.util.Objects.equals(resource.line().getLineId(), plan.getLineId())
                && java.util.Objects.equals(resource.product().getProductId(), child.getProductId())
                && java.util.Objects.equals(resource.bom().getBomId(), child.getBomId())
                && java.util.Objects.equals(resource.route().getRouteId(), child.getRouteId());
    }

    @Override
    public void withdrawWorkOrder(Long workOrderId, Long operatorId, String remark) {
        productionOrderService.withdrawUnexecutedWorkOrderChain(workOrderId, operatorId, remark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgWorkOrder startWorkOrder(Long workOrderId, Long operatorId) {
        MfgWorkOrder existing = getWorkOrderById(workOrderId);
        if(stockComponents != null) stockComponents.requireNoPendingReplacement(existing);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "生产工单不存在或已删除");
        }
        preparationGate.assertExecutionSupported(existing.getProductId());
        if (!BusinessStatus.Document.RELEASED.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待开始的已下达工单可以开始生产");
        }
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "操作人不能为空");
        }
        List<MfgWorkOrderMaterial> requirements = workOrderMaterialMapper.selectList(
                new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .eq(MfgWorkOrderMaterial::getWorkOrderId, workOrderId)
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0));
        if (requirements.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "工单没有有效用料需求，不能开始生产");
        }
        long shortageCount = requirements.stream().filter(requirement -> {
            BigDecimal required = requirement.getRequiredQuantity() == null ? BigDecimal.ZERO : requirement.getRequiredQuantity();
            BigDecimal issued = requirement.getIssuedQuantity() == null ? BigDecimal.ZERO : requirement.getIssuedQuantity();
            return issued.compareTo(required) < 0;
        }).count();
        if (shortageCount > 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "物料未齐套，仍有 " + shortageCount + " 种物料未足额发到本工单，不能开始生产");
        }
        long taskCount = mfgTaskMapper.selectCount(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getWorkOrderId, workOrderId)
                .eq(MfgTask::getDeletedFlag, 0));
        if (taskCount == 0) {
            throw business(ResultCode.BUSINESS_ERROR, "工单尚未生成工序任务，不能开始生产");
        }
        long nonWaitingTaskCount = mfgTaskMapper.selectCount(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getWorkOrderId, workOrderId)
                .eq(MfgTask::getDeletedFlag, 0)
                .ne(MfgTask::getStatus, BusinessStatus.Production.WAIT_START));
        if (nonWaitingTaskCount > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "工单存在非待开始工序任务，请先修正任务状态");
        }
        LocalDateTime now = LocalDateTime.now();
        MfgWorkOrder update = new MfgWorkOrder();
        update.setWorkOrderId(workOrderId);
        update.setStatus(BusinessStatus.Production.RUNNING);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "生产工单已被其他用户修改，请刷新后重试");
        }
        insertStatusLog(workOrderId, existing.getStatus(), BusinessStatus.Production.RUNNING,
                "START_WORK_ORDER", operatorId, null, now);
        insertOperationLog(workOrderId, "START_WORK_ORDER", operatorId, null, now);
        return getWorkOrderById(workOrderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSupplementMaterialRequirements(Long workOrderId, BigDecimal supplementQuantity,
                                                  Long operatorId, String reason) {
        addSupplementMaterialRequirements(workOrderId,supplementQuantity,operatorId,reason,null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSupplementMaterialRequirements(Long workOrderId, BigDecimal supplementQuantity,
                                                  Long operatorId, String reason, java.util.Set<Long> operations) {
        requireSupplementParams(workOrderId, supplementQuantity, operatorId, reason);
        MfgWorkOrder workOrder = lockWorkOrderForSupplement(workOrderId);
        MdmProduct executionProduct = mdmProductMapper.selectById(workOrder.getProductId());
        Map<Long, BigDecimal> additionalByMaterial =
                supplementDemands(workOrder, executionProduct, supplementQuantity, operations);
        applySupplementRequirements(workOrderId, additionalByMaterial, operatorId, reason);
        insertOperationLog(workOrderId, "ADD_SUPPLEMENT_MATERIAL_REQUIREMENTS", operatorId,
                "补产/返工数量=" + supplementQuantity.toPlainString() + "；" + reason.trim(), LocalDateTime.now());
    }

    /** 补投物料的入参校验：工单、数量、操作人、原因都不能缺。 */
    private void requireSupplementParams(Long workOrderId, BigDecimal supplementQuantity,
                                         Long operatorId, String reason) {
        if (workOrderId == null || operatorId == null || !hasPositiveQuantity(supplementQuantity)
                || !StringUtils.hasText(reason)) {
            throw business(ResultCode.PARAM_ERROR, "工单、补投数量、操作人和补投原因不能为空");
        }
    }

    /** 补投数量必须是正数；空值按不合格处理，避免空指针与零数量。 */
    private static boolean hasPositiveQuantity(BigDecimal quantity) {
        return quantity != null && quantity.signum() > 0;
    }

    /** 锁住工单行并确认它还存在、未关闭。 */
    private MfgWorkOrder lockWorkOrderForSupplement(Long workOrderId) {
        MfgWorkOrder workOrder = baseMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, workOrderId)
                .eq(MfgWorkOrder::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (workOrder == null || BusinessStatus.Document.CLOSED.equals(workOrder.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单不存在或已关闭，不能增加补投物料");
        }
        return workOrder;
    }

    /**
     * 按补投数量折算各物料的追加需求。
     *
     * <p>备料受控的产品走工单快照里的用料结构，可按工序过滤；其余产品走 BOM 明细。</p>
     */
    private Map<Long, BigDecimal> supplementDemands(MfgWorkOrder workOrder, MdmProduct executionProduct,
                                                    BigDecimal supplementQuantity,
                                                    java.util.Set<Long> operations) {
        if (executionProduct != null && Integer.valueOf(1).equals(executionProduct.getPreparationControlled())) {
            return snapshotSupplementDemands(workOrder, supplementQuantity, operations);
        }
        List<MdmBomItem> bomItems = bomItemMapper.selectList(new LambdaQueryWrapper<MdmBomItem>()
                .eq(MdmBomItem::getBomId, workOrder.getBomId()).eq(MdmBomItem::getDeletedFlag, 0)
                .orderByAsc(MdmBomItem::getSequenceNo, MdmBomItem::getBomItemId));
        if (bomItems.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单的物料清单没有有效明细，不能计算补投需求");
        }
        if (operations != null) {
            bomItems = bomItems.stream().filter(i -> operations.contains(i.getConsumptionOperationId())).toList();
        }
        productQuantities.validate(workOrder.getProductId(), supplementQuantity);
        return materialDemands.calculate(bomItems, supplementQuantity);
    }

    /** 备料受控产品：把快照的用料结构按工序裁剪后交给同一套需求折算。 */
    private Map<Long, BigDecimal> snapshotSupplementDemands(MfgWorkOrder workOrder, BigDecimal supplementQuantity,
                                                           java.util.Set<Long> operations) {
        var snapshot = snapshotPolicy.read(workOrder).deepCopy();
        if (operations != null) {
            var selected = ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot).putArray("bomItems");
            for (var item : snapshotPolicy.read(workOrder).path("bomItems")) {
                if (operations.contains(item.path("consumptionOperationId").asLong())) {
                    selected.add(item);
                }
            }
        }
        return snapshotPolicy.directDemands(snapshot, supplementQuantity);
    }

    /** 把折算出来的需求逐条落到工单用料需求上：没有就新建，有就累加。 */
    private void applySupplementRequirements(Long workOrderId, Map<Long, BigDecimal> additionalByMaterial,
                                             Long operatorId, String reason) {
        Map<Long, MfgWorkOrderMaterial> requirementByMaterial = lockRequirementsByMaterial(workOrderId);
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<Long, BigDecimal> entry : additionalByMaterial.entrySet()) {
            MfgWorkOrderMaterial existing = requirementByMaterial.get(entry.getKey());
            BigDecimal issued = existing == null || existing.getIssuedQuantity() == null
                    ? BigDecimal.ZERO : existing.getIssuedQuantity();
            BigDecimal required = (existing == null || existing.getRequiredQuantity() == null
                    ? BigDecimal.ZERO : existing.getRequiredQuantity()).add(entry.getValue()).setScale(4, RoundingMode.HALF_UP);
            BigDecimal shortage = required.subtract(issued).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
            String readiness = readinessOf(shortage, issued);
            if (existing == null) {
                insertSupplementRequirement(workOrderId, entry.getKey(), required, shortage, readiness, reason, operatorId, now);
            } else {
                updateSupplementRequirement(existing, required, shortage, readiness, entry.getValue(), reason, operatorId, now);
            }
        }
    }

    /** 现有用料需求按物料索引，并加行锁。 */
    private Map<Long, MfgWorkOrderMaterial> lockRequirementsByMaterial(Long workOrderId) {
        List<MfgWorkOrderMaterial> requirements = workOrderMaterialMapper.selectList(
                new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .eq(MfgWorkOrderMaterial::getWorkOrderId, workOrderId)
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0)
                        .last("FOR UPDATE"));
        Map<Long, MfgWorkOrderMaterial> requirementByMaterial = new LinkedHashMap<>();
        requirements.forEach(item -> requirementByMaterial.put(item.getMaterialId(), item));
        return requirementByMaterial;
    }

    /** 齐套状态：无缺口即就绪，部分到料算部分就绪，其余待备料。 */
    private static String readinessOf(BigDecimal shortage, BigDecimal issued) {
        if (shortage.signum() == 0) {
            return "READY";
        }
        return issued.signum() > 0 ? "PARTIAL_READY" : "WAIT_READY";
    }

    /** 新建一条补投用料需求。 */
    private void insertSupplementRequirement(Long workOrderId, Long materialId, BigDecimal required,
                                             BigDecimal shortage, String readiness, String reason,
                                             Long operatorId, LocalDateTime now) {
        MfgWorkOrderMaterial created = new MfgWorkOrderMaterial();
        created.setWorkOrderId(workOrderId);
        created.setMaterialId(materialId);
        created.setRequiredQuantity(required);
        created.setIssuedQuantity(BigDecimal.ZERO.setScale(4));
        created.setShortageQuantity(shortage);
        created.setStatus(readiness);
        created.setRemark("质量/生产补偿新增物料需求：" + reason.trim());
        created.setVersion(0);
        created.setCreatedBy(operatorId);
        created.setCreatedTime(now);
        created.setUpdatedBy(operatorId);
        created.setUpdatedTime(now);
        created.setDeletedFlag(0);
        if (workOrderMaterialMapper.insert(created) == 0) {
            throw business(ResultCode.FAILED, "补投物料需求新增失败");
        }
    }

    /** 在已有的补投用料需求上累加数量并刷新齐套状态。 */
    private void updateSupplementRequirement(MfgWorkOrderMaterial existing, BigDecimal required, BigDecimal shortage,
                                             String readiness, BigDecimal added, String reason,
                                             Long operatorId, LocalDateTime now) {
        MfgWorkOrderMaterial update = new MfgWorkOrderMaterial();
        update.setRequirementId(existing.getRequirementId());
        update.setVersion(existing.getVersion());
        update.setRequiredQuantity(required);
        update.setShortageQuantity(shortage);
        update.setStatus(readiness);
        update.setRemark("质量/生产补偿增加 " + added.toPlainString() + "：" + reason.trim());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (workOrderMaterialMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "补投物料需求已被其他用户修改，请刷新后重试");
        }
    }

    /**
     * 查询生产工单列表，支持关键字搜索、状态筛选、生产计划筛选和产品筛选。
     * @param keyword 关键字（匹配工单编号）
     * @param status 工单状态
     * @param planId 生产计划ID
     * @param productId 产品ID
     * @return 生产工单列表
     */
    @Override
    public List<MfgWorkOrder> listWorkOrders(String keyword, String status, Long planId, Long productId) {
        return baseMapper.selectList(buildWorkOrderQuery(keyword, status, planId, productId));
    }

    /**
     * 构建生产工单查询条件，过滤已删除记录。
     * @param keyword 关键字
     * @param status 状态
     * @param planId 生产计划ID
     * @param productId 产品ID
     * @return 查询条件包装器
     */
    private QueryWrapper<MfgWorkOrder> buildWorkOrderQuery(String keyword, String status, Long planId, Long productId) {
        QueryWrapper<MfgWorkOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(keyword)) {
            var aliases=numbers.aliasIds("WO",keyword);
            wrapper.and(q -> q.like("work_order_no",keyword)
                    .or(!aliases.isEmpty()).in(!aliases.isEmpty(),"work_order_id",aliases));
        }
        if (StringUtils.hasText(status)) {
            if (BusinessStatus.Production.WAIT_START.equals(status)) {
                wrapper.in("status", BusinessStatus.Document.DRAFT, BusinessStatus.Document.APPROVED,
                        BusinessStatus.Document.RELEASED, BusinessStatus.Production.WAIT_START);
            } else {
                wrapper.eq("status", status);
            }
        }
        if (planId != null) {
            wrapper.eq("plan_id", planId);
        }
        if (productId != null) {
            wrapper.eq("product_id", productId);
        }
        wrapper.orderByDesc("work_order_id");
        return wrapper;
    }

    private MfgProductionPlan activePlan(Long planId) {
        QueryWrapper<MfgProductionPlan> query = new QueryWrapper<>();
        query.eq("plan_id", planId)
                .eq("deleted_flag", 0)
                .last("FOR UPDATE");
        return productionPlanMapper.selectOne(query);
    }

    private MfgProductionOrder activeOrder(Long productionOrderId) {
        QueryWrapper<MfgProductionOrder> query = new QueryWrapper<>();
        query.eq("production_order_id", productionOrderId).eq("deleted_flag", 0);
        return productionOrderMapper.selectOne(query);
    }

    private boolean hasActiveWorkOrder(Long planId) {
        QueryWrapper<MfgWorkOrder> query = new QueryWrapper<>();
        query.eq("plan_id", planId).eq("deleted_flag", 0).ne("status", BusinessStatus.Document.CLOSED);
        return baseMapper.selectCount(query) > 0;
    }

    private MdmBom resolveReleasedBom(Long productId, MfgProductionPlan plan) {
        LambdaQueryWrapper<MdmBom> query = new LambdaQueryWrapper<MdmBom>()
                .eq(MdmBom::getProductId, productId)
                .eq(MdmBom::getStatus, BusinessStatus.Document.RELEASED)
                .eq(MdmBom::getDeletedFlag, 0)
                .isNotNull(MdmBom::getEffectiveDate)
                .orderByDesc(MdmBom::getEffectiveDate)
                .orderByDesc(MdmBom::getBomId);
        if (plan.getPlannedStartTime() != null) {
            query.le(MdmBom::getEffectiveDate, plan.getPlannedStartTime().toLocalDate());
        }
        List<MdmBom> candidates = bomMapper.selectList(query);
        if (candidates.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "订单产品在计划日期没有已发布的有效物料清单");
        }
        if (candidates.size() > 1
                && Objects.equals(candidates.get(0).getEffectiveDate(), candidates.get(1).getEffectiveDate())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "订单产品存在多个同日生效的已发布物料清单，请先清理版本有效性");
        }
        return candidates.get(0);
    }

    private ProcessRoute resolveReleasedRoute(Long productId, Long lineId) {
        ProcessRoute byCapability = resolveRouteFromCapability(productId, lineId);
        if (byCapability != null) {
            return byCapability;
        }
        return resolveSoleReleasedRoute(productId);
    }

    /** 按产线产品能力的优先级挑路线；同优先级出现多条不同路线视为配置歧义。 */
    private ProcessRoute resolveRouteFromCapability(Long productId, Long lineId) {
        List<MfgLineProductCapability> capabilities = lineProductCapabilityMapper.selectList(
                new LambdaQueryWrapper<MfgLineProductCapability>()
                        .eq(MfgLineProductCapability::getLineId, lineId)
                        .eq(MfgLineProductCapability::getProductId, productId)
                        .eq(MfgLineProductCapability::getStatus, BusinessStatus.Common.ENABLE)
                        .eq(MfgLineProductCapability::getDeletedFlag, 0)
                        .orderByAsc(MfgLineProductCapability::getPriorityLevel)
                        .orderByAsc(MfgLineProductCapability::getCapabilityId));
        ProcessRoute selectedRoute = null;
        Integer selectedPriority = null;
        for (MfgLineProductCapability capability : capabilities) {
            ProcessRoute route = releasedRouteOf(capability, productId);
            if (route == null) continue;
            if (selectedRoute == null) {
                selectedRoute = route;
                selectedPriority = capability.getPriorityLevel();
                continue;
            }
            if (isPriorityConflict(selectedRoute, route, selectedPriority, capability.getPriorityLevel())) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "计划产线存在多个同优先级产品路线，请先明确产线产品能力优先级");
            }
            break;
        }
        return selectedRoute;
    }

    /** 同优先级却对应不同路线，说明产线产品能力配置有歧义。 */
    private boolean isPriorityConflict(ProcessRoute selectedRoute, ProcessRoute route,
                                       Integer selectedPriority, Integer routePriority) {
        return Objects.equals(selectedPriority, routePriority)
                && !Objects.equals(selectedRoute.getRouteId(), route.getRouteId());
    }

    private ProcessRoute releasedRouteOf(MfgLineProductCapability capability, Long productId) {
        return routeMapper.selectOne(new LambdaQueryWrapper<ProcessRoute>()
                .eq(ProcessRoute::getRouteId, capability.getRouteId())
                .eq(ProcessRoute::getProductId, productId)
                .eq(ProcessRoute::getStatus, BusinessStatus.Document.RELEASED)
                .eq(ProcessRoute::getDeletedFlag, 0));
    }

    /** 产线能力没配出路线时，只有唯一一条已发布路线才能自动采用。 */
    private ProcessRoute resolveSoleReleasedRoute(Long productId) {
        List<ProcessRoute> releasedRoutes = routeMapper.selectList(new LambdaQueryWrapper<ProcessRoute>()
                .eq(ProcessRoute::getProductId, productId)
                .eq(ProcessRoute::getStatus, BusinessStatus.Document.RELEASED)
                .eq(ProcessRoute::getDeletedFlag, 0)
                .orderByAsc(ProcessRoute::getRouteId));
        if (releasedRoutes.size() == 1) {
            return releasedRoutes.get(0);
        }
        if (releasedRoutes.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "订单产品没有已发布的工艺路线");
        }
        throw business(ResultCode.BUSINESS_ERROR,
                "计划产线未配置产品路线且产品存在多个已发布路线，请先维护产线产品能力");
    }

    private void insertStatusLog(Long bizId, String oldStatus, String newStatus, String reason,
                                 Long operatorId, String remark, LocalDateTime now) {
        insertStatusLog("MFG_WORK_ORDER", bizId, oldStatus, newStatus, reason, operatorId, remark, now);
    }

    private void insertStatusLog(String bizType, Long bizId, String oldStatus, String newStatus, String reason,
                                 Long operatorId, String remark, LocalDateTime now) {
        SysStatusChangeLog log = new SysStatusChangeLog();
        log.setBizType(bizType);
        log.setBizId(bizId);
        log.setOldStatus(oldStatus);
        log.setNewStatus(newStatus);
        log.setChangeReason(reason);
        log.setChangedBy(operatorId);
        log.setChangedTime(now);
        log.setRemark(remark);
        log.setCreatedBy(operatorId);
        log.setCreatedTime(now);
        log.setUpdatedBy(operatorId);
        log.setUpdatedTime(now);
        log.setDeletedFlag(0);
        if (sysStatusChangeLogMapper.insert(log) == 0) throw new BusinessException(ResultCode.FAILED);
    }

    private void insertOperationLog(Long bizId, String type, Long operatorId, String remark, LocalDateTime now) {
        insertOperationLog("mfg_work_order", bizId, type, operatorId, remark, now);
    }

    private void insertOperationLog(String moduleCode, Long bizId, String type, Long operatorId, String remark, LocalDateTime now) {
        SysOperationLog log = new SysOperationLog();
        log.setUserId(operatorId);
        log.setOperationType(type);
        log.setModuleCode(moduleCode);
        log.setBizId(bizId);
        log.setOperationTime(now);
        log.setRemark(remark);
        log.setCreatedBy(operatorId);
        log.setCreatedTime(now);
        log.setUpdatedBy(operatorId);
        log.setUpdatedTime(now);
        log.setDeletedFlag(0);
        if (sysOperationLogMapper.insert(log) == 0) throw new BusinessException(ResultCode.FAILED);
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }

    /**
     * 构建工单执行快照：下达瞬间冻结 BOM 本级明细、工艺路线工序、产品参数与适用检验方案。
     * <p>执行阶段只读这份快照，主数据后续变更不影响已下达工单。</p>
     */
    private String buildExecutionSnapshot(MfgWorkOrder workOrder) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("formatVersion", "LAMP-SNAPSHOT-1");
        snapshot.put("workOrderId", workOrder.getWorkOrderId());
        snapshot.put("workOrderNo", workOrder.getWorkOrderNo());
        snapshot.put("workOrderType", workOrder.getWorkOrderType());
        snapshot.put("productId", workOrder.getProductId());
        snapshot.put("bomId", workOrder.getBomId());
        snapshot.put("routeId", workOrder.getRouteId());
        snapshot.put("plannedQuantity", workOrder.getPlannedQuantity());
        snapshot.put("bomItems", bomSnapshotRows(workOrder));
        snapshot.put("routeItems", routeSnapshotRows(workOrder));
        snapshot.put("parameters", parameterSnapshotRows(workOrder));
        snapshot.put("inspectionSchemes", inspectionSchemeSnapshotRows(workOrder));
        snapshot.put("snapshotTime", LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw business(ResultCode.FAILED, "生产工单执行快照生成失败：" + ex.getMessage());
        }
    }

    /** 冻结 BOM 本级明细。 */
    private List<Map<String, Object>> bomSnapshotRows(MfgWorkOrder workOrder) {
        List<Map<String, Object>> bomRows = new ArrayList<>();
        List<MdmBomItem> items = bomItemMapper.selectList(new LambdaQueryWrapper<MdmBomItem>()
                .eq(MdmBomItem::getBomId, workOrder.getBomId())
                .eq(MdmBomItem::getDeletedFlag, 0)
                .orderByAsc(MdmBomItem::getSequenceNo, MdmBomItem::getBomItemId));
        for (MdmBomItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", item.getMaterialId());
            row.put("childBomId", item.getChildBomId());
            row.put("quantity", item.getQuantity());
            row.put("lossRate", item.getLossRate());
            row.put("sequenceNo", item.getSequenceNo());
            bomRows.add(row);
        }
        return bomRows;
    }

    /** 冻结工艺路线工序及其工序主数据编码。 */
    private List<Map<String, Object>> routeSnapshotRows(MfgWorkOrder workOrder) {
        List<Map<String, Object>> routeRows = new ArrayList<>();
        List<ProcessRouteItem> routeItems = routeItemMapper.selectList(new LambdaQueryWrapper<ProcessRouteItem>()
                .eq(ProcessRouteItem::getRouteId, workOrder.getRouteId())
                .eq(ProcessRouteItem::getDeletedFlag, 0)
                .orderByAsc(ProcessRouteItem::getSequenceNo, ProcessRouteItem::getRouteItemId));
        for (ProcessRouteItem item : routeItems) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routeItemId", item.getRouteItemId());
            row.put("operationId", item.getOperationId());
            row.put("sequenceNo", item.getSequenceNo());
            row.put("standardTime", item.getStandardTime());
            row.put("resourceType", item.getResourceType());
            row.put("batchCapacity", item.getBatchCapacity());
            row.put("batchDurationMinutes", item.getBatchDurationMinutes());
            row.put("timeSourceReference", item.getTimeSourceReference());
            row.put("qcRequired", item.getQcRequired());
            ProcessOperation operation = operationMapper.selectById(item.getOperationId());
            row.put("operationCode", operation == null ? null : operation.getOperationCode());
            row.put("operationName", operation == null ? null : operation.getOperationName());
            routeRows.add(row);
        }
        return routeRows;
    }

    /** 冻结产品参数。 */
    private List<Map<String, Object>> parameterSnapshotRows(MfgWorkOrder workOrder) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        List<MdmProductParameter> productParameters = productParameterMapper.selectList(
                new LambdaQueryWrapper<MdmProductParameter>()
                        .eq(MdmProductParameter::getProductId, workOrder.getProductId())
                        .eq(MdmProductParameter::getDeletedFlag, 0)
                        .orderByAsc(MdmProductParameter::getParameterCode));
        for (MdmProductParameter parameter : productParameters) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("parameterCode", parameter.getParameterCode());
            row.put("parameterName", parameter.getParameterName());
            row.put("parameterValue", parameter.getParameterValue());
            row.put("unitLabel", parameter.getUnitLabel());
            row.put("sourceReference", parameter.getSourceReference());
            row.put("status", parameter.getStatus());
            parameters.add(row);
        }
        return parameters;
    }

    /** 冻结已发布检验方案及其检验标准。 */
    private List<Map<String, Object>> inspectionSchemeSnapshotRows(MfgWorkOrder workOrder) {
        List<Map<String, Object>> schemes = new ArrayList<>();
        List<QmsInspectionScheme> inspectionSchemes = inspectionSchemeMapper.selectList(
                new LambdaQueryWrapper<QmsInspectionScheme>()
                        .eq(QmsInspectionScheme::getProductId, workOrder.getProductId())
                        .eq(QmsInspectionScheme::getStatus, BusinessStatus.Document.RELEASED)
                        .eq(QmsInspectionScheme::getDeletedFlag, 0)
                        .orderByAsc(QmsInspectionScheme::getSchemeId));
        for (QmsInspectionScheme scheme : inspectionSchemes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schemeId", scheme.getSchemeId());
            row.put("schemeCode", scheme.getSchemeCode());
            row.put("inspectionType", scheme.getInspectionType());
            row.put("operationId", scheme.getOperationId());
            row.put("standards", schemeStandardRows(scheme));
            schemes.add(row);
        }
        return schemes;
    }

    /** 单个检验方案下的检验项目及其标准值。 */
    private List<Map<String, Object>> schemeStandardRows(QmsInspectionScheme scheme) {
        List<Map<String, Object>> standards = new ArrayList<>();
        List<QmsInspectionSchemeItem> schemeItems = inspectionSchemeItemMapper.selectList(
                new LambdaQueryWrapper<QmsInspectionSchemeItem>()
                        .eq(QmsInspectionSchemeItem::getSchemeId, scheme.getSchemeId())
                        .eq(QmsInspectionSchemeItem::getDeletedFlag, 0)
                        .orderByAsc(QmsInspectionSchemeItem::getSequenceNo));
        for (QmsInspectionSchemeItem schemeItem : schemeItems) {
            QmsInspectionStandard standard = inspectionStandardMapper.selectById(schemeItem.getStandardId());
            Map<String, Object> standardRow = new LinkedHashMap<>();
            standardRow.put("itemId", schemeItem.getItemId());
            standardRow.put("standardId", schemeItem.getStandardId());
            standardRow.put("samplingMode", schemeItem.getSamplingMode());
            standardRow.put("sampleQuantity", schemeItem.getSampleQuantity());
            if (standard != null) {
                standardRow.put("standardValue", standard.getStandardValue());
                standardRow.put("upperLimit", standard.getUpperLimit());
                standardRow.put("lowerLimit", standard.getLowerLimit());
                standardRow.put("judgementRule", standard.getJudgementRule());
            }
            standards.add(standardRow);
        }
        return standards;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw business(ResultCode.FAILED, "生产工单快照摘要生成失败");
        }
    }

    private static boolean hasIndependentPlanInterval(MfgProductionPlan candidate) {
        return candidate.getPlannedStartTime() != null && candidate.getPlannedEndTime() != null && candidate.getParentPlanId() == null;
    }
}
