package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.mfg.MfgDispatchOrder;
import com.neuedu.ems.entity.mfg.MfgTask;
import com.neuedu.ems.entity.mfg.MfgTaskOperator;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.factory.FactoryStation;
import com.neuedu.ems.entity.factory.FactoryStationOperation;
import com.neuedu.ems.entity.org.OrgTeam;
import com.neuedu.ems.entity.org.OrgTeamMember;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgDispatchOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskOperatorMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.factory.FactoryStationMapper;
import com.neuedu.ems.mapper.factory.FactoryStationOperationMapper;
import com.neuedu.ems.mapper.org.OrgTeamMapper;
import com.neuedu.ems.mapper.org.OrgTeamMemberMapper;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.service.mfg.MfgDispatchOrderService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 派工单业务逻辑实现类。
 * 实现派工单的分页查询、条件查询、新增、更新和删除功能。
 */
@Service
public class MfgDispatchOrderServiceImpl extends ServiceImpl<MfgDispatchOrderMapper, MfgDispatchOrder> implements MfgDispatchOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;

    @Autowired private com.neuedu.ems.service.mdm.ManufacturingPreparationGate preparationGate;
    @Autowired private com.neuedu.ems.service.mfg.ExecutionSnapshotPolicy snapshotPolicy;
    @Autowired private com.neuedu.ems.service.mfg.DispatchPreparationService dispatchPreparation;

    private static final String WAIT_DISPATCH = "WAIT_DISPATCH";
    private static final String MATERIAL_READY = "READY";
    private static final String ROLE_LEADER = "LEADER";
    private static final String ROLE_OPERATOR = "OPERATOR";
    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Autowired private MfgTaskMapper taskMapper;
    @Autowired private MfgTaskOperatorMapper taskOperatorMapper;
    @Autowired private MfgWorkReportMapper workReportMapper;
    @Autowired private MfgWorkOrderMapper workOrderMapper;
    @Autowired(required = false) private com.neuedu.ems.service.mfg.ComponentHandoverService componentHandover;
    @Autowired(required = false) private com.neuedu.ems.service.mfg.StockComponentSupplyService stockComponents;
    @Autowired private MfgWorkOrderMaterialMapper workOrderMaterialMapper;
    @Autowired private MfgProductionPlanMapper productionPlanMapper;
    @Autowired private ProcessRouteItemMapper routeItemMapper;
    @Autowired private MdmProductMapper productMapper;
    @Autowired private FactoryStationOperationMapper stationOperationMapper;
    @Autowired private FactoryStationMapper stationMapper;
    @Autowired private OrgTeamMapper teamMapper;
    @Autowired private OrgTeamMemberMapper teamMemberMapper;
    @Autowired private SysStatusChangeLogMapper statusLogMapper;
    @Autowired private SysOperationLogMapper operationLogMapper;
    @Autowired(required = false) private MfgResponsibilityNotificationService notificationService;

    /**
     * 分页查询派工单，支持关键字、状态、工单ID和班组ID筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 关键字（匹配派工单编号），可选
     * @param status 派工单状态（待派发、已派发、已接受、生产中、已完成、已驳回），可选
     * @param workOrderId 工单ID，可选
     * @param teamId 班组ID，可选
     * @return 分页结果
     */
    @Override
    public Page<MfgDispatchOrder> pageDispatchOrders(Integer current, Integer size, String keyword, String status, Long workOrderId, Long teamId) {
        Page<MfgDispatchOrder> page = new Page<>(current, size);
        LambdaQueryWrapper<MfgDispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MfgDispatchOrder::getDeletedFlag, 0)
                .like(StringUtils.hasText(keyword), MfgDispatchOrder::getDispatchNo, keyword)
                .eq(StringUtils.hasText(status), MfgDispatchOrder::getStatus, status)
                .eq(workOrderId != null, MfgDispatchOrder::getWorkOrderId, workOrderId)
                .eq(teamId != null, MfgDispatchOrder::getTeamId, teamId)
                .orderByDesc(MfgDispatchOrder::getCreatedTime);
        Page<MfgDispatchOrder> result = this.page(page, queryWrapper);
        markWorkOrderTypes(result.getRecords());
        return result;
    }

    /**
     * 根据条件查询派工单列表，支持关键字、状态、工单ID和班组ID筛选。
     * @param keyword 关键字（匹配派工单编号），可选
     * @param status 派工单状态，可选
     * @param workOrderId 工单ID，可选
     * @param teamId 班组ID，可选
     * @return 派工单列表
     */
    @Override
    public List<MfgDispatchOrder> listDispatchOrders(String keyword, String status, Long workOrderId, Long teamId) {
        LambdaQueryWrapper<MfgDispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MfgDispatchOrder::getDeletedFlag, 0)
                .like(StringUtils.hasText(keyword), MfgDispatchOrder::getDispatchNo, keyword)
                .eq(StringUtils.hasText(status), MfgDispatchOrder::getStatus, status)
                .eq(workOrderId != null, MfgDispatchOrder::getWorkOrderId, workOrderId)
                .eq(teamId != null, MfgDispatchOrder::getTeamId, teamId)
                .orderByDesc(MfgDispatchOrder::getCreatedTime);
        return this.list(queryWrapper);
    }

    /** 带上工单类型：前端据此判断整机工单只能领原材料。 */
    private void markWorkOrderTypes(List<MfgDispatchOrder> dispatches) {
        if (dispatches == null || dispatches.isEmpty() || workOrderMapper == null) {
            return;
        }
        List<Long> workOrderIds = dispatches.stream().map(MfgDispatchOrder::getWorkOrderId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (workOrderIds.isEmpty()) {
            return;
        }
        java.util.Map<Long, String> types = new java.util.HashMap<>();        for (MfgWorkOrder order : workOrderMapper.selectBatchIds(workOrderIds)) {
            types.put(order.getWorkOrderId(), order.getWorkOrderType());
        }
        dispatches.forEach(dispatch -> dispatch.setWorkOrderType(types.get(dispatch.getWorkOrderId())));
    }

    /**
     * 根据派工单ID查询派工单详情。
     * @param dispatchId 派工单ID
     * @return 派工单实体
     */    @Override
    public MfgDispatchOrder getDispatchOrderById(Long dispatchId) {
        LambdaQueryWrapper<MfgDispatchOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MfgDispatchOrder::getDispatchId, dispatchId)
                .eq(MfgDispatchOrder::getDeletedFlag, 0);
        return this.getOne(queryWrapper);
    }

    /**
     * 新增派工单。
     * @param dispatchOrder 派工单实体
     * @return 操作是否成功
     */
    @Override
    public boolean saveDispatchOrder(MfgDispatchOrder dispatchOrder) {
        throw business(ResultCode.BUSINESS_ERROR, "派工单必须从已下达生产工单生成，不能脱离来源手工新增");
    }

    /**
     * 更新派工单信息。
     * @param dispatchOrder 派工单实体（必须包含dispatchId）
     * @return 操作是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateDispatchOrder(MfgDispatchOrder dispatchOrder) {
        if (dispatchOrder == null || dispatchOrder.getDispatchId() == null) {
            return false;
        }
        MfgDispatchOrder existing = getDispatchOrderById(dispatchOrder.getDispatchId());
        if (existing == null || !WAIT_DISPATCH.equals(existing.getStatus())) {
            return false;
        }
        // 普通编辑只允许补充说明；班组由预检候选生成，时间在正式派发时重新计算。
        dispatchOrder.setTeamId(null);
        dispatchOrder.setDueTime(null);
        dispatchOrder.setDispatchNo(null);
        dispatchOrder.setWorkOrderId(null);
        dispatchOrder.setPlannedQuantity(null);
        dispatchOrder.setMaterialReadyStatus(null);
        dispatchOrder.setStatus(null);
        dispatchOrder.setDeletedFlag(null);
        if (dispatchOrder.getVersion() == null) {
            dispatchOrder.setVersion(existing.getVersion());
        }
        return this.updateById(dispatchOrder);
    }

    /**
     * 删除派工单（软删除，将deletedFlag设为1）。
     * @param dispatchId 派工单ID
     * @return 操作是否成功
     */
    @Override
    public boolean removeDispatchOrderById(Long dispatchId) {
        MfgDispatchOrder existing = getDispatchOrderById(dispatchId);
        if (existing == null || !WAIT_DISPATCH.equals(existing.getStatus())) {
            return false;
        }
        MfgDispatchOrder dispatchOrder = new MfgDispatchOrder();
        dispatchOrder.setDispatchId(dispatchId);
        dispatchOrder.setVersion(existing.getVersion());
        dispatchOrder.setDeletedFlag(1);
        return this.updateById(dispatchOrder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgDispatchOrder generateDispatchOrder(Long workOrderId, MfgDispatchOrder request, Long operatorId) {
        if (workOrderId == null || request == null || request.getTeamId() == null
                || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产工单、派工班组和操作人不能为空");
        }
        MfgWorkOrder workOrder = workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, workOrderId)
                .eq(MfgWorkOrder::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (componentHandover != null && workOrder != null) componentHandover.synchronize(workOrderId, operatorId);
        if (workOrder == null) {
            throw business(ResultCode.NOT_FOUND, "生产工单不存在或已删除");
        }
        if(stockComponents != null) stockComponents.requireNoPendingReplacement(workOrder);
        if (!BusinessStatus.Document.RELEASED.equals(workOrder.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已下达生产工单可以生成派工单");
        }
        MfgProductionPlan plan = activePlan(workOrder.getPlanId());
        LocalDateTime now = LocalDateTime.now();
        var prepared = dispatchPreparation.prepare(workOrder, plan);
        requirePrepared(prepared, request.getTeamId());
        validateDispatchTime(prepared.dueTime(), plan, now);
        OrgTeam team = activeTeam(request.getTeamId());
        if (team == null) {
            throw business(ResultCode.BUSINESS_ERROR, "派工班组不存在或已停用");
        }
        validateTeamMatchesProduct(workOrder, team);
        long activeDispatches = baseMapper.selectCount(new LambdaQueryWrapper<MfgDispatchOrder>()
                .eq(MfgDispatchOrder::getWorkOrderId, workOrderId)
                .eq(MfgDispatchOrder::getDeletedFlag, 0)
                .notIn(MfgDispatchOrder::getStatus, BusinessStatus.Document.REJECTED, BusinessStatus.Document.CLOSED));
        if (activeDispatches > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "该生产工单已有有效派工单，不能重复生成");
        }
        MaterialReadiness readiness = calculateMaterialReadiness(workOrderId);
        MfgDispatchOrder dispatch = new MfgDispatchOrder();
        if (!MATERIAL_READY.equals(readiness.status()))
            throw business(ResultCode.BUSINESS_ERROR, "用料尚未实际到位，仍缺 " + readiness.shortageMaterialCount()
                    + " 种物料；请等待同线组件交接并完成其余原材料领料后再生成派工单");
        dispatch.setDispatchNo(numbers.nextFor("DO","WORK_ORDER",workOrderId));
        dispatch.setWorkOrderId(workOrderId);
        dispatch.setTeamId(team.getTeamId());
        dispatch.setPlannedQuantity(workOrder.getPlannedQuantity());
        dispatch.setDueTime(prepared.dueTime());
        dispatch.setMaterialReadyStatus(readiness.status());
        dispatch.setStatus(WAIT_DISPATCH);
        dispatch.setRemark(request.getRemark());
        dispatch.setVersion(0);
        dispatch.setCreatedBy(operatorId);
        dispatch.setCreatedTime(now);
        dispatch.setUpdatedBy(operatorId);
        dispatch.setUpdatedTime(now);
        dispatch.setDeletedFlag(0);
        if (baseMapper.insert(dispatch) == 0 || dispatch.getDispatchId() == null) {
            throw business(ResultCode.FAILED, "派工单生成失败");
        }
        saveWorkOrderWindow(workOrder, prepared, operatorId, now);
        audit("MFG_DISPATCH_ORDER", "mfg_dispatch_order", dispatch.getDispatchId(), null, WAIT_DISPATCH,
                "GENERATE_DISPATCH_ORDER", operatorId, request.getRemark(), now);
        return dispatch;
    }

    @Override
    @Transactional(readOnly = true)
    public com.neuedu.ems.vo.mfg.DispatchPreparationVO previewDispatch(Long workOrderId) {
        MfgWorkOrder workOrder = activeReleasedWorkOrder(workOrderId);
        return dispatchPreparation.prepare(workOrder, activePlan(workOrder.getPlanId()));
    }

    @Override
    @Transactional(readOnly = true)
    public void validateDispatchMaterials(Long workOrderId) {
        activeReleasedWorkOrder(workOrderId);
        MaterialReadiness readiness = calculateMaterialReadiness(workOrderId);
        if (!MATERIAL_READY.equals(readiness.status())) {
            throw business(ResultCode.BUSINESS_ERROR, "用料尚未实际到位，仍缺 "
                    + readiness.shortageMaterialCount() + " 种物料，请先完成领料或组件交接");
        }
    }

    private void requirePrepared(com.neuedu.ems.vo.mfg.DispatchPreparationVO prepared, Long teamId) {
        if (!prepared.blockers().isEmpty()) throw business(ResultCode.BUSINESS_ERROR, String.join("；", prepared.blockers()));
        if (prepared.teams().stream().noneMatch(t -> java.util.Objects.equals(t.teamId(), teamId)))
            throw business(ResultCode.BUSINESS_ERROR, "所选班组未覆盖本工单产线和生产时段，请刷新候选班组或维护日期排班");
    }

    private void saveWorkOrderWindow(MfgWorkOrder workOrder, com.neuedu.ems.vo.mfg.DispatchPreparationVO prepared,
                                     Long operatorId, LocalDateTime now) {
        MfgWorkOrder update = new MfgWorkOrder();
        update.setWorkOrderId(workOrder.getWorkOrderId()); update.setVersion(workOrder.getVersion());
        update.setPlannedStartTime(prepared.plannedStartTime()); update.setPlannedEndTime(prepared.dueTime());
        update.setUpdatedBy(operatorId); update.setUpdatedTime(now);
        if (workOrderMapper.updateById(update) == 0) throw business(ResultCode.DATA_CONFLICT, "工单时间已变化，请刷新后重试");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<MfgTask> releaseDispatchOrder(Long dispatchId, Long operatorId, String remark) {
        if (dispatchId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "派工单和操作人不能为空");
        }
        MfgDispatchOrder dispatch = lockWaitDispatchOrder(dispatchId);
        MfgWorkOrder workOrder = activeReleasedWorkOrder(dispatch.getWorkOrderId());
        if(stockComponents != null) stockComponents.requireNoPendingReplacement(workOrder);
        if (componentHandover != null) componentHandover.synchronize(workOrder.getWorkOrderId(), operatorId);
        MfgProductionPlan plan = activePlan(workOrder.getPlanId());
        LocalDateTime now = LocalDateTime.now();
        var prepared = dispatchPreparation.prepare(workOrder, plan);
        requirePrepared(prepared, dispatch.getTeamId());
        validateDispatchTime(prepared.dueTime(), plan, now);
        MaterialReadiness readiness = requireMaterialReady(workOrder);
        List<ProcessRouteItem> routeItems = resolveReleaseRouteItems(workOrder);
        requireNoActiveTasks(dispatchId);
        OrgTeam team = requireReleaseTeam(dispatch);
        List<Long> assigneeIds = requireReleaseAssignees(team);
        List<MfgTask> tasks = new ArrayList<>(routeItems.size());
        for (ProcessRouteItem routeItem : routeItems) {
            tasks.add(createReleaseTask(routeItem, dispatch, workOrder, plan, team, assigneeIds,
                    operatorId, remark, now));
        }
        MfgDispatchOrder update = new MfgDispatchOrder();
        update.setDispatchId(dispatchId);
        update.setVersion(dispatch.getVersion());
        update.setStatus(BusinessStatus.Document.RELEASED);
        update.setMaterialReadyStatus(readiness.status());
        update.setDueTime(prepared.dueTime());
        update.setRemark(StringUtils.hasText(remark) ? remark : dispatch.getRemark());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "派工单已被并发修改");
        }
        saveWorkOrderWindow(workOrder, prepared, operatorId, now);
        audit("MFG_DISPATCH_ORDER", "mfg_dispatch_order", dispatchId, WAIT_DISPATCH,
                BusinessStatus.Document.RELEASED, "RELEASE_DISPATCH_ORDER", operatorId, remark, now);
        return tasks;
    }

    private MfgDispatchOrder lockWaitDispatchOrder(Long dispatchId) {
        MfgDispatchOrder dispatch = baseMapper.selectOne(new LambdaQueryWrapper<MfgDispatchOrder>()
                .eq(MfgDispatchOrder::getDispatchId, dispatchId)
                .eq(MfgDispatchOrder::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (dispatch == null) {
            throw business(ResultCode.NOT_FOUND, "派工单不存在或已删除");
        }
        if (!WAIT_DISPATCH.equals(dispatch.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待派发派工单可以派发并生成任务");
        }
        return dispatch;
    }

    private MaterialReadiness requireMaterialReady(MfgWorkOrder workOrder) {
        MaterialReadiness readiness = calculateMaterialReadiness(workOrder.getWorkOrderId());
        if (!MATERIAL_READY.equals(readiness.status())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "物料未齐套，不能派发任务：仍有 " + readiness.shortageMaterialCount()
                            + " 种物料未实际到位。同线组件等待前置工单检验合格自动交接，其余原材料请领料并转线边");
        }
        return readiness;
    }

    private List<ProcessRouteItem> resolveReleaseRouteItems(MfgWorkOrder workOrder) {
        List<ProcessRouteItem> routeItems = workOrder.getSnapshotJson() != null
                && snapshotPolicy.isCurrentFormat(workOrder)
                ? snapshotPolicy.routeItems(workOrder)
                : routeItemMapper.selectList(new LambdaQueryWrapper<ProcessRouteItem>()
                        .eq(ProcessRouteItem::getRouteId, workOrder.getRouteId())
                        .eq(ProcessRouteItem::getDeletedFlag, 0)
                        .orderByAsc(ProcessRouteItem::getSequenceNo, ProcessRouteItem::getRouteItemId));
        if (routeItems.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单的工艺路线没有有效工序，不能派发");
        }
        return routeItems;
    }

    private void requireNoActiveTasks(Long dispatchId) {
        long activeTasks = taskMapper.selectCount(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getDispatchId, dispatchId)
                .eq(MfgTask::getDeletedFlag, 0)
                .ne(MfgTask::getStatus, BusinessStatus.Document.CLOSED));
        if (activeTasks > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "派工单已有有效生产任务，不能重复生成");
        }
    }

    private OrgTeam requireReleaseTeam(MfgDispatchOrder dispatch) {
        OrgTeam team = activeTeam(dispatch.getTeamId());
        if (team == null) {
            throw business(ResultCode.BUSINESS_ERROR, "派工班组不存在或已停用");
        }
        return team;
    }

    private List<Long> requireReleaseAssignees(OrgTeam team) {
        List<Long> assigneeIds = activeTeamAssigneeIds(team);
        if (assigneeIds.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "派工班组没有有效成员，不能派发生成无人负责的生产任务");
        }
        return assigneeIds;
    }

    private MfgTask createReleaseTask(ProcessRouteItem routeItem, MfgDispatchOrder dispatch, MfgWorkOrder workOrder,
                                      MfgProductionPlan plan, OrgTeam team, List<Long> assigneeIds,
                                      Long operatorId, String remark, LocalDateTime now) {
        if (routeItem.getOperationId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "工艺路线存在未关联工序的明细，不能派发");
        }
        Long stationId = resolveDefaultStation(routeItem.getOperationId(), plan.getLineId());
        MfgTask task = new MfgTask();
        task.setTaskNo(numbers.nextFor("TK","WORK_ORDER",workOrder.getWorkOrderId()));
        task.setDispatchId(dispatch.getDispatchId());
        task.setWorkOrderId(workOrder.getWorkOrderId());
        task.setOperationId(routeItem.getOperationId());
        task.setStationId(stationId);
        task.setPlannedQuantity(dispatch.getPlannedQuantity());
        task.setCompletedQuantity(java.math.BigDecimal.ZERO);
        task.setDefectQuantity(java.math.BigDecimal.ZERO);
        task.setTaskType("NORMAL");
        task.setStatus(BusinessStatus.Production.WAIT_START);
        task.setRemark(routeItem.getRemark());
        task.setVersion(0);
        task.setCreatedBy(operatorId);
        task.setCreatedTime(now);
        task.setUpdatedBy(operatorId);
        task.setUpdatedTime(now);
        task.setDeletedFlag(0);
        if (taskMapper.insert(task) == 0 || task.getTaskId() == null) {
            throw business(ResultCode.FAILED, "生产任务生成失败");
        }
        audit("MFG_TASK", "mfg_task", task.getTaskId(), null, BusinessStatus.Production.WAIT_START,
                "GENERATE_TASK_FROM_DISPATCH", operatorId, remark, now);
        assignTaskOperators(task.getTaskId(), team, assigneeIds, operatorId, now);
        if (notificationService != null) {
            assigneeIds.forEach(employeeId ->
                    notificationService.publishTaskAssigned(task.getTaskId(), employeeId, operatorId));
        }
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawDispatchOrder(Long dispatchId, Long operatorId, String remark) {
        if (dispatchId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "派工单和操作人不能为空");
        }
        String reason = StringUtils.hasText(remark) ? remark.trim() : "用户主动撤回，未填写补充原因";
        MfgDispatchOrder dispatch = lockWithdrawableDispatchOrder(dispatchId);
        List<MfgTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getDispatchId, dispatchId).eq(MfgTask::getDeletedFlag, 0).last("FOR UPDATE"));
        requireTasksWithdrawable(tasks);
        requireNoActiveReports(tasks);
        LocalDateTime now = LocalDateTime.now();
        closeWithdrawnTasks(tasks, operatorId, reason, now);
        resetDispatchToWait(dispatch, operatorId, now);
        audit("MFG_DISPATCH_ORDER", "mfg_dispatch_order", dispatchId, dispatch.getStatus(), WAIT_DISPATCH,
                "WITHDRAW_DISPATCH_ORDER", operatorId, reason, now);
    }

    private MfgDispatchOrder lockWithdrawableDispatchOrder(Long dispatchId) {
        MfgDispatchOrder dispatch = baseMapper.selectOne(new LambdaQueryWrapper<MfgDispatchOrder>()
                .eq(MfgDispatchOrder::getDispatchId, dispatchId)
                .eq(MfgDispatchOrder::getDeletedFlag, 0).last("FOR UPDATE"));
        if (dispatch == null || !(BusinessStatus.Document.RELEASED.equals(dispatch.getStatus())
                || "ACCEPTED".equals(dispatch.getStatus()))) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已派发或已接受且尚未执行的派工单可以撤回");
        }
        return dispatch;
    }

    private void requireTasksWithdrawable(List<MfgTask> tasks) {
        for (MfgTask task : tasks) {
            if (!isTaskWithdrawable(task)) {
                throw business(ResultCode.BUSINESS_ERROR, "派工任务已经开工或产生数量，不能撤回派工");
            }
        }
    }

    private boolean isTaskWithdrawable(MfgTask task) {
        return (BusinessStatus.Production.WAIT_START.equals(task.getStatus())
                || BusinessStatus.Document.CLOSED.equals(task.getStatus()))
                && !positive(task.getCompletedQuantity()) && !positive(task.getDefectQuantity());
    }

    private void requireNoActiveReports(List<MfgTask> tasks) {
        List<Long> taskIds = tasks.stream().map(MfgTask::getTaskId).toList();
        if (taskIds.isEmpty()) {
            return;
        }
        if (workReportMapper.selectCount(new LambdaQueryWrapper<MfgWorkReport>()
                .in(MfgWorkReport::getTaskId, taskIds).eq(MfgWorkReport::getDeletedFlag, 0)
                .ne(MfgWorkReport::getAuditStatus, BusinessStatus.Document.WITHDRAWN)) > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "派工任务已有有效报工，不能撤回派工");
        }
    }

    private void closeWithdrawnTasks(List<MfgTask> tasks, Long operatorId, String reason, LocalDateTime now) {
        for (MfgTask task : tasks) {
            if (BusinessStatus.Document.CLOSED.equals(task.getStatus())) {
                continue;
            }
            MfgTask update = new MfgTask();
            update.setTaskId(task.getTaskId()); update.setVersion(task.getVersion());
            update.setStatus(BusinessStatus.Document.CLOSED); update.setUpdatedBy(operatorId); update.setUpdatedTime(now);
            if (taskMapper.updateById(update) == 0) throw business(ResultCode.DATA_CONFLICT, "生产任务已被并发修改");
            audit("MFG_TASK", "mfg_task", task.getTaskId(), task.getStatus(), BusinessStatus.Document.CLOSED,
                    "WITHDRAW_DISPATCH_ORDER", operatorId, reason, now);
            if (notificationService != null) notificationService.cancelOpenTaskNotifications(task.getTaskId(), operatorId);
        }
    }

    private void resetDispatchToWait(MfgDispatchOrder dispatch, Long operatorId, LocalDateTime now) {
        MfgDispatchOrder update = new MfgDispatchOrder();
        update.setDispatchId(dispatch.getDispatchId()); update.setVersion(dispatch.getVersion());
        update.setStatus(WAIT_DISPATCH);
        update.setUpdatedBy(operatorId); update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) throw business(ResultCode.DATA_CONFLICT, "派工单已被并发修改");
    }

    private MfgWorkOrder activeReleasedWorkOrder(Long workOrderId) {
        MfgWorkOrder workOrder = workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, workOrderId)
                .eq(MfgWorkOrder::getDeletedFlag, 0));
        if (workOrder == null || !BusinessStatus.Document.RELEASED.equals(workOrder.getStatus())
                || workOrder.getRouteId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "派工单未关联有效的已下达生产工单和工艺路线");
        }
        preparationGate.assertExecutionSupported(workOrder.getProductId());
        return workOrder;
    }

    private MfgProductionPlan activePlan(Long planId) {
        MfgProductionPlan plan = productionPlanMapper.selectOne(new LambdaQueryWrapper<MfgProductionPlan>()
                .eq(MfgProductionPlan::getPlanId, planId)
                .eq(MfgProductionPlan::getDeletedFlag, 0));
        if (plan == null || plan.getLineId() == null
                || !java.util.Set.of("RELEASED", "RUNNING").contains(plan.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单未关联有效产线计划");
        }
        return plan;
    }

    private void validateDispatchTime(LocalDateTime dueTime, MfgProductionPlan plan, LocalDateTime now) {
        if (dueTime == null || !dueTime.isAfter(now)) {
            throw business(ResultCode.BUSINESS_ERROR, "派工要求完成时间已过，请重新选择未来有效时间");
        }
        if (plan.getPlannedEndTime() != null && dueTime.isAfter(plan.getPlannedEndTime())) {
            throw business(ResultCode.BUSINESS_ERROR, "派工要求完成时间不能晚于生产计划结束时间");
        }
    }

    private MaterialReadiness calculateMaterialReadiness(Long workOrderId) {
        List<MfgWorkOrderMaterial> requirements = workOrderMaterialMapper.selectList(
                new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .eq(MfgWorkOrderMaterial::getWorkOrderId, workOrderId)
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0));
        if (requirements.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单没有有效用料需求，不能派工");
        }
        int shortageCount = 0;
        boolean anyIssued = false;
        for (MfgWorkOrderMaterial requirement : requirements) {
            java.math.BigDecimal required = requirement.getRequiredQuantity() == null
                    ? java.math.BigDecimal.ZERO : requirement.getRequiredQuantity();
            java.math.BigDecimal issued = requirement.getIssuedQuantity() == null
                    ? java.math.BigDecimal.ZERO : requirement.getIssuedQuantity();
            anyIssued = anyIssued || issued.signum() > 0;
            if (issued.compareTo(required) < 0) {
                shortageCount++;
            }
        }
        String status;
        if (shortageCount == 0) {
            status = MATERIAL_READY;
        } else if (anyIssued) {
            status = "PARTIAL_READY";
        } else {
            status = "WAIT_READY";
        }
        return new MaterialReadiness(status, shortageCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshMaterialReadiness(Long workOrderId, Long operatorId) {
        if (workOrderId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产工单和操作人不能为空");
        }
        MaterialReadiness readiness = calculateMaterialReadiness(workOrderId);
        List<MfgDispatchOrder> dispatchOrders = baseMapper.selectList(
                new LambdaQueryWrapper<MfgDispatchOrder>()
                        .eq(MfgDispatchOrder::getWorkOrderId, workOrderId)
                        .eq(MfgDispatchOrder::getDeletedFlag, 0)
                        .notIn(MfgDispatchOrder::getStatus,
                                BusinessStatus.Document.CLOSED,
                                BusinessStatus.Document.REJECTED,
                                BusinessStatus.Production.FINISHED));
        LocalDateTime now = LocalDateTime.now();
        for (MfgDispatchOrder dispatchOrder : dispatchOrders) {
            if (java.util.Objects.equals(dispatchOrder.getMaterialReadyStatus(), readiness.status())) {
                continue;
            }
            MfgDispatchOrder update = new MfgDispatchOrder();
            update.setDispatchId(dispatchOrder.getDispatchId());
            update.setVersion(dispatchOrder.getVersion());
            update.setMaterialReadyStatus(readiness.status());
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (baseMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "派工单齐套状态已变化，请刷新后重试");
            }
            audit("MFG_DISPATCH_ORDER", "mfg_dispatch_order", dispatchOrder.getDispatchId(),
                    dispatchOrder.getMaterialReadyStatus(), readiness.status(),
                    "REFRESH_MATERIAL_READINESS", operatorId,
                    "确认生产领料后同步工单齐套状态", now);
        }
    }

    private OrgTeam activeTeam(Long teamId) {
        return teamId == null ? null : teamMapper.selectOne(new LambdaQueryWrapper<OrgTeam>()
                .eq(OrgTeam::getTeamId, teamId)
                .eq(OrgTeam::getDeletedFlag, 0));
    }

    private List<Long> activeTeamAssigneeIds(OrgTeam team) {
        Set<Long> assigneeIds = new LinkedHashSet<>();
        if (team.getLeaderId() != null) {
            assigneeIds.add(team.getLeaderId());
        }
        List<OrgTeamMember> members = teamMemberMapper.selectList(new LambdaQueryWrapper<OrgTeamMember>()
                .eq(OrgTeamMember::getTeamId, team.getTeamId())
                .eq(OrgTeamMember::getStatus, BusinessStatus.Common.ENABLE)
                .eq(OrgTeamMember::getDeletedFlag, 0)
                .and(wrapper -> wrapper.isNull(OrgTeamMember::getLeaveDate)
                        .or().ge(OrgTeamMember::getLeaveDate, java.time.LocalDate.now())));
        members.stream()
                .map(OrgTeamMember::getEmployeeId)
                .filter(employeeId -> employeeId != null)
                .forEach(assigneeIds::add);
        return new ArrayList<>(assigneeIds);
    }

    private void assignTaskOperators(Long taskId, OrgTeam team, List<Long> assigneeIds,
                                     Long operatorId, LocalDateTime now) {
        for (Long employeeId : assigneeIds) {
            MfgTaskOperator assignment = new MfgTaskOperator();
            assignment.setTaskId(taskId);
            assignment.setEmployeeId(employeeId);
            assignment.setRoleInTask(employeeId.equals(team.getLeaderId()) ? ROLE_LEADER : ROLE_OPERATOR);
            assignment.setAssignedTime(now);
            assignment.setCreatedBy(operatorId);
            assignment.setCreatedTime(now);
            assignment.setDeletedFlag(0);
            if (taskOperatorMapper.insert(assignment) == 0) {
                throw business(ResultCode.FAILED, "生产任务人员分配失败");
            }
        }
    }

    private void validateTeamMatchesProduct(MfgWorkOrder workOrder, OrgTeam team) {
        if (workOrder.getProductId() == null || team == null) {
            return;
        }
        MdmProduct product = productMapper.selectOne(new LambdaQueryWrapper<MdmProduct>()
                .eq(MdmProduct::getProductId, workOrder.getProductId())
                .eq(MdmProduct::getDeletedFlag, 0));
        if (product == null) {
            return;
        }
        String productText = normalize(product.getProductCode(), product.getProductName(), product.getProductModel());
        String teamText = normalize(team.getTeamCode(), team.getTeamName());
        if (isTypeMismatch(productText, teamText)) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "派工班组与订单产品类型不匹配，请选择对应的比赛球/训练球班组");
        }
    }

    /** 产品与班组的用途标记相反（比赛产品配训练班组，或反之）即为不匹配。 */
    private boolean isTypeMismatch(String productText, String teamText) {
        boolean productIsMatch = containsAny(productText, "MATCH", "比赛");
        boolean productIsTrain = containsAny(productText, "TRAIN", "训练");
        boolean teamIsMatch = containsAny(teamText, "MATCH", "比赛");
        boolean teamIsTrain = containsAny(teamText, "TRAIN", "训练");
        return (productIsMatch && teamIsTrain) || (productIsTrain && teamIsMatch);
    }

    private boolean containsAny(String text, String first, String second) {
        return text.contains(first) || text.contains(second);
    }

    private String normalize(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                builder.append(' ').append(value.trim());
            }
        }
        return builder.toString().toUpperCase();
    }

    private Long resolveDefaultStation(Long operationId, Long lineId) {
        List<FactoryStationOperation> candidates = stationOperationMapper.selectList(
                new LambdaQueryWrapper<FactoryStationOperation>()
                        .eq(FactoryStationOperation::getOperationId, operationId)
                        .eq(FactoryStationOperation::getStatus, BusinessStatus.Common.ENABLE)
                        .eq(FactoryStationOperation::getDeletedFlag, 0)
                        .orderByDesc(FactoryStationOperation::getIsDefault)
                        .orderByAsc(FactoryStationOperation::getId));
        Long firstCompatibleStation = null;
        for (FactoryStationOperation candidate : candidates) {
            FactoryStation station = stationMapper.selectOne(new LambdaQueryWrapper<FactoryStation>()
                    .eq(FactoryStation::getStationId, candidate.getStationId())
                    .eq(FactoryStation::getLineId, lineId)
                    .eq(FactoryStation::getStatus, BusinessStatus.Factory.RUNNING)
                    .eq(FactoryStation::getDeletedFlag, 0));
            if (station != null) {
                if (firstCompatibleStation == null) firstCompatibleStation = station.getStationId();
                long running = taskMapper.selectCount(new LambdaQueryWrapper<MfgTask>()
                        .eq(MfgTask::getStationId, station.getStationId())
                        .eq(MfgTask::getStatus, BusinessStatus.Production.RUNNING)
                        .eq(MfgTask::getDeletedFlag, 0));
                if (running == 0) return station.getStationId();
            }
        }
        // 全部被占用时保留原有派工行为；实际开工仍由任务服务检查占用。
        if (firstCompatibleStation != null) return firstCompatibleStation;
        throw business(ResultCode.BUSINESS_ERROR,
                "工序 " + operationId + " 未配置当前计划产线的可用工位，不能生成任务");
    }

    private boolean positive(java.math.BigDecimal value) { return value != null && value.signum() > 0; }

    private record MaterialReadiness(String status, int shortageMaterialCount) {
    }

    private void audit(String bizType, String module, Long id, String oldStatus, String newStatus, String reason,
                       Long operatorId, String remark, LocalDateTime now) {
        SysStatusChangeLog status = new SysStatusChangeLog();
        status.setBizType(bizType); status.setBizId(id); status.setOldStatus(oldStatus); status.setNewStatus(newStatus);
        status.setChangeReason(reason); status.setChangedBy(operatorId); status.setChangedTime(now);
        status.setRemark(remark); status.setCreatedBy(operatorId); status.setCreatedTime(now); status.setDeletedFlag(0);
        if (statusLogMapper.insert(status) == 0) throw new BusinessException(ResultCode.FAILED);
        SysOperationLog operation = new SysOperationLog();
        operation.setUserId(operatorId); operation.setModuleCode(module); operation.setBizId(id);
        operation.setOperationType(reason); operation.setOperationTime(now); operation.setRemark(remark);
        operation.setCreatedBy(operatorId); operation.setCreatedTime(now); operation.setDeletedFlag(0);
        if (operationLogMapper.insert(operation) == 0) throw new BusinessException(ResultCode.FAILED);
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }
}
