package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.service.mfg.ComponentSupplyPlanningService;
import com.neuedu.ems.service.mfg.MfgProductionPlanService;
import com.neuedu.ems.service.mfg.MfgProductionOrderService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 生产计划管理 Service 实现类。
 */
@Service
public class MfgProductionPlanServiceImpl extends ServiceImpl<MfgProductionPlanMapper, MfgProductionPlan> implements MfgProductionPlanService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;


    @Autowired private com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper workOrders;
    @Autowired private com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper requirements;
    @Autowired private com.neuedu.ems.service.mfg.ComponentSupplyPlanningService componentSupply;
    @Autowired private com.neuedu.ems.service.mfg.ComponentCapacityService componentCapacity;
    @Autowired private com.neuedu.ems.mapper.mfg.MfgSupplySourceMapper supplySources;

    @Autowired
    private MfgProductionOrderService productionOrderService;

    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 组件 BOM 层级的防护上限，与工单生成侧保持一致，防止父子链异常时死循环。 */
    private static final int MAX_COMPONENT_DEPTH = 8;

    @Autowired
    private MfgProductionOrderMapper productionOrderMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    /**
     * 分页查询生产计划列表，支持关键字搜索、状态筛选、生产订单筛选和产线筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 关键字（匹配计划编号）
     * @param status 计划状态
     * @param productionOrderId 生产订单ID
     * @param lineId 产线ID
     * @return 分页结果
     */
    @Override
    public Page<MfgProductionPlan> pagePlans(Integer current, Integer size, String keyword, String status, Long productionOrderId, Long lineId) {
        Page<MfgProductionPlan> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildPlanQuery(keyword, status, productionOrderId, lineId));
    }

    /**
     * 根据生产计划ID查询生产计划信息。
     * @param planId 生产计划ID
     * @return 生产计划实体
     */
    @Override
    public MfgProductionPlan getPlanById(Long planId) {
        QueryWrapper<MfgProductionPlan> wrapper = new QueryWrapper<>();
        wrapper.eq("plan_id", planId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    /**
     * 新增生产计划，自动设置删除标志为0。
     * @param plan 生产计划实体
     * @return 是否成功
     */
    @Override
    public boolean savePlan(MfgProductionPlan plan) {
        throw business(ResultCode.BUSINESS_ERROR, "生产计划必须从已下达生产订单生成，不能脱离来源手工新增");
    }

    /**
     * 更新生产计划信息，不允许修改删除标志。
     * @param plan 生产计划实体
     * @return 是否成功
     */
    @Override
    public boolean updatePlan(MfgProductionPlan plan) {
        if (plan.getPlanId() == null) {
            throw business(ResultCode.PARAM_ERROR, "请指明要修改哪一张生产计划");
        }
        MfgProductionPlan existing = getPlanById(plan.getPlanId());
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "生产计划不存在或已删除");
        }
        // 已下达的计划已经派了工单、领了料，直接改时间会让现场对不上账。
        // 这里必须说清「为什么不能改、该怎么做」，而不是回一个笼统的失败。
        if (!BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "计划 " + existing.getPlanNo() + " 当前状态为「" + statusName(existing.getStatus())
                            + "」，只有草稿计划可以直接改时间。要调整已下达计划，请先撤回该计划，"
                            + "或在排程看板里重新排产");
        }
        plan.setDeletedFlag(null);
        plan.setStatus(null);
        plan.setPlanNo(null);
        plan.setProductionOrderId(null);
        plan.setPlanType(null);
        plan.setParentPlanId(null);
        if (plan.getVersion() == null) {
            plan.setVersion(existing.getVersion());
        }
        if (baseMapper.updateById(plan) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "计划 " + existing.getPlanNo() + " 已被其他请求修改，本次没有保存");
        }
        return true;
    }

    /**
     * 根据ID删除生产计划（软删除，将删除标志设为1）。
     * @param planId 生产计划ID
     * @return 是否成功
     */
    @Override
    public boolean removePlanById(Long planId) {
        MfgProductionPlan existing = getPlanById(planId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "生产计划不存在或已删除");
        }
        if (!BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "计划 " + existing.getPlanNo() + " 当前状态为「" + statusName(existing.getStatus())
                            + "」，只有草稿计划可以删除");
        }
        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanId(planId);
        plan.setVersion(existing.getVersion());
        plan.setDeletedFlag(1);
        if (baseMapper.updateById(plan) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "计划 " + existing.getPlanNo() + " 已被其他请求修改，本次没有删除");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgProductionPlan generatePlan(Long productionOrderId, MfgProductionPlan request, Long operatorId) {
        validateGenerateRequest(productionOrderId, request, operatorId);
        MfgProductionOrder order = requireReleasedOrder(productionOrderId);
        validateFutureTimeWindow(request);
        ensurePlanQuantityWithinOrder(productionOrderId, request.getPlannedQuantity(), order.getOrderQuantity());
        LocalDateTime now = LocalDateTime.now();
        MfgProductionPlan plan = newDraftPlan(productionOrderId, request, operatorId, now);
        if (baseMapper.insert(plan) == 0 || plan.getPlanId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }
        insertStatusLog(plan.getPlanId(), null, BusinessStatus.Document.DRAFT,
                "GENERATE_PRODUCTION_PLAN_DRAFT", operatorId, request.getRemark(), now);
        insertOperationLog(plan.getPlanId(), "GENERATE_PRODUCTION_PLAN_DRAFT", operatorId, request.getRemark(), now);
        return plan;
    }

    /** 编制计划的入参校验：产线、时间、数量与操作人必须完整有效。 */
    private void validateGenerateRequest(Long productionOrderId, MfgProductionPlan request, Long operatorId) {
        if (productionOrderId == null || request == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "产线、计划时间、计划数量和操作人不能为空");
        }
        if (request.getLineId() == null || !validTimeWindow(request)) {
            throw business(ResultCode.PARAM_ERROR, "产线、计划时间、计划数量和操作人不能为空");
        }
        if (request.getPlannedQuantity() == null
                || request.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw business(ResultCode.PARAM_ERROR, "产线、计划时间、计划数量和操作人不能为空");
        }
    }

    /** 取已提交排产的生产订单，计划只能挂在已提交排产的订单上。 */
    private MfgProductionOrder requireReleasedOrder(Long productionOrderId) {
        MfgProductionOrder order = activeOrder(productionOrderId);
        if (order == null) {
            throw business(ResultCode.NOT_FOUND, "生产订单不存在或已删除");
        }
        if (!BusinessStatus.Document.RELEASED.equals(order.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已提交排产的生产订单可以编制计划");
        }
        return order;
    }

    /** 累计计划数量不能超过生产订单数量。 */
    private void ensurePlanQuantityWithinOrder(Long productionOrderId, BigDecimal addingQuantity,
                                               BigDecimal orderQuantity) {
        BigDecimal planned = existingPlannedQuantity(productionOrderId);
        if (planned.add(addingQuantity).compareTo(orderQuantity) > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "累计计划数量不能超过生产订单数量");
        }
    }

    /** 用请求参数组装一张草稿计划。 */
    private MfgProductionPlan newDraftPlan(Long productionOrderId, MfgProductionPlan request,
                                           Long operatorId, LocalDateTime now) {
        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanNo(numbers.nextFor("PL","PRODUCTION_ORDER",productionOrderId));
        plan.setProductionOrderId(productionOrderId);
        plan.setLineId(request.getLineId());
        plan.setPlannedStartTime(request.getPlannedStartTime());
        plan.setPlannedEndTime(request.getPlannedEndTime());
        plan.setPlannedQuantity(request.getPlannedQuantity());
        plan.setStatus(BusinessStatus.Document.DRAFT);
        plan.setRemark(request.getRemark());
        plan.setVersion(0);
        plan.setCreatedBy(operatorId);
        plan.setCreatedTime(now);
        plan.setUpdatedBy(operatorId);
        plan.setUpdatedTime(now);
        plan.setDeletedFlag(0);
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgProductionPlan releasePlan(Long planId, Long operatorId, String remark) {
        if (planId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产计划和操作人不能为空");
        }
        MfgProductionPlan plan = lockDraftPlan(planId);
        validateReleaseBasics(plan);
        validateFutureTimeWindow(plan);
        MfgProductionOrder order = requireReleasedOrderForPlan(plan);
        validateDeliveryCommitment(plan, order, remark);
        if ("COMPONENT".equals(plan.getPlanType())) {
            validateComponentPlan(plan);
        }
        ensurePlanQuantityWithinOrder(plan.getProductionOrderId(), BigDecimal.ZERO, order.getOrderQuantity());
        LocalDateTime now = LocalDateTime.now();
        markPlanReleased(plan, operatorId, remark, now);
        insertStatusLog(planId, plan.getStatus(), BusinessStatus.Document.RELEASED,
                "RELEASE_PRODUCTION_PLAN", operatorId, remark, now);
        insertOperationLog(planId, "RELEASE_PRODUCTION_PLAN", operatorId, remark, now);
        return getPlanById(planId);
    }

    /** 加锁读取草稿计划，只有草稿可以重新生效。 */
    private MfgProductionPlan lockDraftPlan(Long planId) {
        MfgProductionPlan plan = baseMapper.selectOne(new QueryWrapper<MfgProductionPlan>()
                .eq("plan_id", planId)
                .eq("deleted_flag", 0)
                .last("FOR UPDATE"));
        if (plan == null) {
            throw business(ResultCode.NOT_FOUND, "生产计划不存在或已删除");
        }
        if (!BusinessStatus.Document.DRAFT.equals(plan.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有草稿生产计划可以重新生效");
        }
        return plan;
    }

    /** 生效前确认计划自身的产线、时间与数量完整。 */
    private void validateReleaseBasics(MfgProductionPlan plan) {
        if (plan.getLineId() == null || !validTimeWindow(plan)) {
            throw business(ResultCode.BUSINESS_ERROR, "生产计划缺少有效产线、时间或计划数量");
        }
        if (plan.getPlannedQuantity() == null || plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw business(ResultCode.BUSINESS_ERROR, "生产计划缺少有效产线、时间或计划数量");
        }
    }

    /** 计划来源订单必须存在且处于已提交排产状态。 */
    private MfgProductionOrder requireReleasedOrderForPlan(MfgProductionPlan plan) {
        MfgProductionOrder order = activeOrder(plan.getProductionOrderId());
        // 一个组件开工会把来源订单推进 RUNNING，其余合法组件计划仍须允许接续生效。
        boolean componentDuringProduction = order != null && "COMPONENT".equals(plan.getPlanType())
                && BusinessStatus.Production.RUNNING.equals(order.getStatus());
        if (order == null || (!BusinessStatus.Document.RELEASED.equals(order.getStatus())
                && !componentDuringProduction)) {
            throw business(ResultCode.BUSINESS_ERROR, "生产计划的来源订单未处于已提交排产状态");
        }
        return order;
    }

    /** 按乐观锁把计划置为已生效。 */
    private void markPlanReleased(MfgProductionPlan plan, Long operatorId, String remark, LocalDateTime now) {
        MfgProductionPlan update = new MfgProductionPlan();
        update.setPlanId(plan.getPlanId());
        update.setVersion(plan.getVersion());
        update.setStatus(BusinessStatus.Document.RELEASED);
        update.setRemark(StringUtils.hasText(remark) ? remark : plan.getRemark());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "生产计划已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    public void withdrawPlan(Long planId, Long operatorId, String remark) {
        productionOrderService.withdrawUnexecutedPlanChain(planId, operatorId, remark);
    }

    /**
     * 查询生产计划列表，支持关键字搜索、状态筛选、生产订单筛选和产线筛选。
     * @param keyword 关键字（匹配计划编号）
     * @param status 计划状态
     * @param productionOrderId 生产订单ID
     * @param lineId 产线ID
     * @return 生产计划列表
     */
    @Override
    public List<MfgProductionPlan> listPlans(String keyword, String status, Long productionOrderId, Long lineId) {
        return baseMapper.selectList(buildPlanQuery(keyword, status, productionOrderId, lineId));
    }

    /**
     * 构建生产计划查询条件，过滤已删除记录。
     * @param keyword 关键字
     * @param status 状态
     * @param productionOrderId 生产订单ID
     * @param lineId 产线ID
     * @return 查询条件包装器
     */
    private QueryWrapper<MfgProductionPlan> buildPlanQuery(String keyword, String status, Long productionOrderId, Long lineId) {
        QueryWrapper<MfgProductionPlan> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(keyword)) {
            var aliases=numbers.aliasIds("PL",keyword);
            wrapper.and(q -> q.like("plan_no",keyword)
                    .or(!aliases.isEmpty()).in(!aliases.isEmpty(),"plan_id",aliases));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        if (productionOrderId != null) {
            wrapper.eq("production_order_id", productionOrderId);
        }
        if (lineId != null) {
            wrapper.eq("line_id", lineId);
        }
        wrapper.orderByDesc("plan_id");
        return wrapper;
    }

    private boolean validTimeWindow(MfgProductionPlan plan) {
        return plan.getPlannedStartTime() != null && plan.getPlannedEndTime() != null
                && plan.getPlannedEndTime().isAfter(plan.getPlannedStartTime());
    }

    private void validateFutureTimeWindow(MfgProductionPlan plan) {
        if (!validTimeWindow(plan)) {
            throw business(ResultCode.PARAM_ERROR, "计划结束时间必须晚于开始时间");
        }
        if (plan.getPlannedStartTime().isBefore(LocalDateTime.now().minusMinutes(2))) {
            throw business(ResultCode.BUSINESS_ERROR, "计划开始时间已过，请重新安排未来有效时间");
        }
    }

    private void validateDeliveryCommitment(MfgProductionPlan plan, MfgProductionOrder order, String remark) {
        if (order.getDeliveryDate() != null && plan.getPlannedEndTime().toLocalDate().isAfter(order.getDeliveryDate())
                && !StringUtils.hasText(remark)) {
            throw business(ResultCode.BUSINESS_ERROR, "计划预计晚于订单交付日期，请填写延期确认说明后再生效");
        }
    }

    private MfgProductionOrder activeOrder(Long productionOrderId) {
        QueryWrapper<MfgProductionOrder> query = new QueryWrapper<>();
        query.eq("production_order_id", productionOrderId)
                .eq("deleted_flag", 0)
                .last("FOR UPDATE");
        return productionOrderMapper.selectOne(query);
    }

    private BigDecimal existingPlannedQuantity(Long productionOrderId) {
        QueryWrapper<MfgProductionPlan> query = new QueryWrapper<>();
        query.select("COALESCE(SUM(planned_quantity), 0) AS planned_quantity")
                .eq("production_order_id", productionOrderId)
                .eq("plan_type", "ROOT")
                .eq("deleted_flag", 0)
                .ne("status", BusinessStatus.Document.CLOSED);
        MfgProductionPlan total = baseMapper.selectOne(query);
        return total == null || total.getPlannedQuantity() == null ? BigDecimal.ZERO : total.getPlannedQuantity();
    }

    /** 组件计划不能借用整机订单产品、产线或数量口径完成生效校验。 */
    private void validateComponentPlan(MfgProductionPlan plan) {
        var child = requireSingleComponentWorkOrder(plan);
        validateComponentWorkOrder(child);
        var parent = workOrders.selectById(child.getParentWorkOrderId());
        var requirement = requirements.selectById(child.getSourceRequirementId());
        validateParentAndRequirement(plan, parent, requirement);
        validateSupplySource(plan, child, requirement);
        validateParentPlanSource(plan, parent);
        var resource = componentSupply.resolve(parent.getBomId(), requirement.getMaterialId(),
                plan.getPlannedStartTime().toLocalDate(), plan.getLineId());
        validateComponentResource(plan, child, requirement, resource);
        // 草稿保存后周历可能被停用、日产能被下调、同线被别的计划占用，生效时必须用同一套口径复核。
        // 同线时还要和生成阶段一样排除父计划家族，否则父计划占住的时段会被重复计入。
        componentCapacity.assertSchedulable(resource.line(), resource.route(), plan.getPlannedQuantity(),
                plan.getPlannedStartTime(), plan.getPlannedEndTime(), plan.getPlanId(),
                resource.capacityFactor(), familyPlanIds(plan, getPlanById(parent.getPlanId()), resource.line()));
    }

    /**
     * 同一张工单家族里、与本次复核共用一条产线的其他计划：父计划及祖先链，加上同父的兄弟组件计划。
     *
     * <p>生成组件计划时，{@code MfgWorkOrderServiceImpl} 先排除父计划家族的占用，再按上一段的结束时刻
     * 给下一个组件计划开头，整族在一条线上首尾相接。生效复核若把父计划或兄弟算进占用，
     * 重算结果就会比草稿晚一整段，同一族里先复核的那张必然失败。复核必须与生成用同一套口径。</p>
     *
     * <p>不同线或没有任何可排除项时返回 null。</p>
     */
    private java.util.Collection<Long> familyPlanIds(MfgProductionPlan plan, MfgProductionPlan parentPlan,
                                                     com.neuedu.ems.entity.factory.FactoryLine line) {
        if (line == null || line.getLineId() == null) {
            return null;
        }
        java.util.Set<Long> planIds = new java.util.LinkedHashSet<>();
        java.util.Collection<Long> ancestors = ancestorPlanIds(parentPlan, line);
        if (ancestors != null) {
            planIds.addAll(ancestors);
        }
        if (plan.getParentPlanId() != null) {
            List<MfgProductionPlan> siblings = baseMapper.selectList(new QueryWrapper<MfgProductionPlan>()
                    .eq("parent_plan_id", plan.getParentPlanId())
                    .eq("line_id", line.getLineId())
                    .eq("deleted_flag", 0)
                    .ne("plan_id", plan.getPlanId()));
            for (MfgProductionPlan sibling : siblings) {
                planIds.add(sibling.getPlanId());
            }
        }
        return planIds.isEmpty() ? null : planIds;
    }

    /**
     * 组件与父计划同线时，父计划及其祖先链的计划主键集合。
     *
     * <p>生成组件计划时，{@code MfgWorkOrderServiceImpl} 用同一份祖先链把父计划家族排除出产能占用，
     * 这样半成品才能和整机在同一条线上同时开工。生效复核必须复用同一口径，否则父计划占住的时段
     * 会被重复计入，重算结果被推后一整段，正常的组件计划会被判成"结束时间早于有效产能窗口"。</p>
     *
     * <p>不同线时返回 null，表示不做额外排除。</p>
     */
    private java.util.Collection<Long> ancestorPlanIds(MfgProductionPlan parentPlan,
                                                      com.neuedu.ems.entity.factory.FactoryLine line) {
        if (parentPlan == null || parentPlan.getLineId() == null || line == null
                || !java.util.Objects.equals(parentPlan.getLineId(), line.getLineId())) {
            return null;
        }
        java.util.Set<Long> planIds = new java.util.LinkedHashSet<>();
        MfgProductionPlan current = parentPlan;
        int guard = 0;
        while (current != null && current.getPlanId() != null && guard++ < MAX_COMPONENT_DEPTH) {
            planIds.add(current.getPlanId());
            Long parentId = current.getParentPlanId();
            current = parentId == null ? null : getPlanById(parentId);
        }
        return planIds;
    }

    /** 取计划关联的唯一组件工单。 */
    private com.neuedu.ems.entity.mfg.MfgWorkOrder requireSingleComponentWorkOrder(MfgProductionPlan plan) {
        var children = workOrders.selectList(new QueryWrapper<com.neuedu.ems.entity.mfg.MfgWorkOrder>()
                .eq("plan_id", plan.getPlanId()).eq("work_order_type", "COMPONENT")
                .eq("deleted_flag", 0).last("FOR UPDATE"));
        if (children.size() != 1) throw business(ResultCode.BUSINESS_ERROR, "组件计划必须关联唯一组件工单");
        return children.get(0);
    }

    /** 组件工单必须处于可生效状态，并带父工单与需求来源。 */
    private void validateComponentWorkOrder(com.neuedu.ems.entity.mfg.MfgWorkOrder child) {
        boolean statusAllowed = java.util.Arrays.asList("DRAFT", "APPROVED").contains(child.getStatus());
        if (!statusAllowed || child.getParentWorkOrderId() == null || child.getSourceRequirementId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "组件工单状态或父需求来源无效");
        }
    }

    /** 父工单与供给需求必须有效，且归属同一父工单。 */
    private void validateParentAndRequirement(MfgProductionPlan plan,
                                              com.neuedu.ems.entity.mfg.MfgWorkOrder parent,
                                              com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial requirement) {
        if (!isActiveParent(parent) || !java.util.Objects.equals(plan.getParentPlanId(), parent.getPlanId())) {
            throw business(ResultCode.BUSINESS_ERROR, "组件计划的父工单或供给关系无效");
        }
        if (!isActiveRequirement(requirement)
                || !java.util.Objects.equals(requirement.getWorkOrderId(), parent.getWorkOrderId())) {
            throw business(ResultCode.BUSINESS_ERROR, "组件计划的父工单或供给关系无效");
        }
    }

    /** 父工单是否未删除且处于可排产状态。 */
    private boolean isActiveParent(com.neuedu.ems.entity.mfg.MfgWorkOrder parent) {
        if (parent == null || !Integer.valueOf(0).equals(parent.getDeletedFlag())) {
            return false;
        }
        return java.util.Arrays.asList("DRAFT", "APPROVED", "RELEASED", "RUNNING").contains(parent.getStatus());
    }

    /** 需求行是否未删除。 */
    private boolean isActiveRequirement(com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial requirement) {
        return requirement != null && Integer.valueOf(0).equals(requirement.getDeletedFlag());
    }

    /** 供给来源明细必须与工单、需求物料和计划数量一致。 */
    private void validateSupplySource(MfgProductionPlan plan, com.neuedu.ems.entity.mfg.MfgWorkOrder child,
                                      com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial requirement) {
        // 一条需求可以拆成多张补产单：供给关系以 mfg_supply_source 为准，
        // source_work_order_id 只保留首张补产单，不能再用它做唯一性校验。
        var source = supplySources.selectOne(new QueryWrapper<com.neuedu.ems.entity.mfg.MfgSupplySource>()
                .eq("work_order_id", child.getWorkOrderId()).eq("plan_id", plan.getPlanId())
                .eq("deleted_flag", 0).last("FOR UPDATE"));
        if (source == null) throw business(ResultCode.BUSINESS_ERROR, "组件计划的供给来源明细与工单不一致，请重建草稿");
        boolean sameRequirement = java.util.Objects.equals(source.getRequirementId(), requirement.getRequirementId());
        boolean sameMaterial = java.util.Objects.equals(source.getMaterialId(), requirement.getMaterialId());
        boolean sameQuantity = source.getPlannedQuantity() != null
                && source.getPlannedQuantity().compareTo(child.getPlannedQuantity()) == 0;
        if (!sameRequirement || !sameMaterial || !sameQuantity) {
            throw business(ResultCode.BUSINESS_ERROR, "组件计划的供给来源明细与工单不一致，请重建草稿");
        }
    }

    /** 父计划必须已生效，且与本计划同属一张生产订单。 */
    private void validateParentPlanSource(MfgProductionPlan plan, com.neuedu.ems.entity.mfg.MfgWorkOrder parent) {
        var parentPlan = getPlanById(parent.getPlanId());
        if (parentPlan == null || !"RELEASED".equals(parentPlan.getStatus())
                || !java.util.Objects.equals(parentPlan.getProductionOrderId(), plan.getProductionOrderId())) {
            throw business(ResultCode.BUSINESS_ERROR, "组件计划与父计划的订单来源不一致");
        }
    }

    /** 选中的组件资源必须与工单的产品、BOM、路线、产线以及需求数量一致。 */
    private void validateComponentResource(MfgProductionPlan plan, com.neuedu.ems.entity.mfg.MfgWorkOrder child,
                                           com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial requirement,
                                           ComponentSupplyPlanningService.ComponentResources resource) {
        boolean sameProduct = java.util.Objects.equals(resource.product().getProductId(), child.getProductId());
        boolean sameBom = java.util.Objects.equals(resource.bom().getBomId(), child.getBomId());
        boolean sameLine = java.util.Objects.equals(resource.line().getLineId(), plan.getLineId());
        boolean sameRoute = java.util.Objects.equals(resource.route().getRouteId(), child.getRouteId());
        if (!sameProduct || !sameBom || !sameLine || !sameRoute) {
            throw business(ResultCode.BUSINESS_ERROR, "组件计划与自身BOM、路线、产线或需求数量不一致，请重建草稿");
        }
        boolean quantityMatched = child.getPlannedQuantity() != null
                && plan.getPlannedQuantity().compareTo(child.getPlannedQuantity()) == 0;
        boolean withinRequirement = requirement.getRequiredQuantity() != null
                && plan.getPlannedQuantity().compareTo(requirement.getRequiredQuantity()) <= 0;
        if (!quantityMatched || !withinRequirement) {
            throw business(ResultCode.BUSINESS_ERROR, "组件计划与自身BOM、路线、产线或需求数量不一致，请重建草稿");
        }
    }

    private void insertStatusLog(Long bizId, String oldStatus, String newStatus, String reason,
                                 Long operatorId, String remark, LocalDateTime now) {
        SysStatusChangeLog log = new SysStatusChangeLog();
        log.setBizType("MFG_PRODUCTION_PLAN");
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
        SysOperationLog log = new SysOperationLog();
        log.setUserId(operatorId);
        log.setOperationType(type);
        log.setModuleCode("mfg_production_plan");
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

    /** 计划状态的中文名，报错时给用户看编码没有意义。 */
    private String statusName(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "RELEASED" -> "已下达";
            case "RUNNING" -> "在制";
            case "FINISHED" -> "已完工";
            case "CLOSED" -> "已关闭";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }
}
