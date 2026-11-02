package com.neuedu.ems.service.impl.wms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.mfg.MfgCommandRequest;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsOutboundDetail;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.impl.ai.InventoryAutomationTrigger;
import com.neuedu.ems.service.mfg.MfgDispatchOrderService;
import com.neuedu.ems.service.wms.WmsOutboundOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 出库单管理 Service 实现类。
 */
@Service
public class WmsOutboundOrderServiceImpl extends ServiceImpl<WmsOutboundOrderMapper, WmsOutboundOrder> implements WmsOutboundOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;

    @Autowired
    private com.neuedu.ems.service.wms.LineSideLocationService lineSideLocations;

    @Autowired(required = false)
    private InventoryAutomationTrigger inventoryAutomationTrigger;
    @Autowired private com.neuedu.ems.service.mdm.ManufacturingPreparationGate preparationGate;
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.mdm.MaterialExecutionQuantityService executionQuantities;


    private static final String SUBMITTED_STATUS = BusinessStatus.Document.SUBMITTED;
    private static final String APPROVED_STATUS = BusinessStatus.Document.APPROVED;
    private static final String MATERIAL_ISSUE_TYPE = "MATERIAL_ISSUE";
    private static final String RAW_WAREHOUSE_TYPE = "RAW";
    /**
     * 生产领料可用的来源仓库类型。根工单要装配组件，组件成品存放在半成品仓，
     * 因此领料不能只认原料仓，否则多层级 BOM 的根工单永远无法齐套。
     */
    private static final Set<String> ISSUEABLE_WAREHOUSE_TYPES = Set.of("RAW", "SEMI", "SEMI_FINISHED");
    private static final Set<String> RELEASED_QUALITY_STATUSES = Set.of(
            BusinessStatus.Quality.PASS, BusinessStatus.Quality.CONCESSION);
    private static final Set<String> ISSUABLE_BATCH_STATUSES = Set.of("AVAILABLE", "ENABLE");
    private static final DateTimeFormatter ISSUE_NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Autowired
    private WmsOutboundDetailMapper wmsOutboundDetailMapper;

    @Autowired
    private WmsInventoryMapper wmsInventoryMapper;

    @Autowired
    private WmsMaterialBatchMapper wmsMaterialBatchMapper;

    @Autowired
    private WmsStockMovementMapper wmsStockMovementMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private WmsResponsibilityNotificationService responsibilityNotificationService;

    @Autowired
    private MfgWorkOrderMaterialMapper workOrderMaterialMapper;

    @Autowired
    private MfgWorkOrderMapper workOrderMapper;

    @Autowired
    private WmsWarehouseMapper wmsWarehouseMapper;

    @Autowired
    private MfgDispatchOrderService mfgDispatchOrderService;

    @Autowired private com.neuedu.ems.mapper.wms.WmsLocationMapper locationMapper;
    @Autowired private com.neuedu.ems.service.sys.DataScopeService dataScopeService;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired private com.neuedu.ems.service.mfg.ExecutionSnapshotPolicy snapshotPolicy;
    @Autowired private com.neuedu.ems.service.mfg.SupplyAllocationService supplyAllocations;
    @Autowired(required = false) private com.neuedu.ems.service.mfg.ComponentHandoverService componentHandover;
    @Autowired(required = false) private com.neuedu.ems.service.mfg.StockComponentSupplyService stockComponents;
    @Autowired private com.neuedu.ems.service.mfg.CommandRequestService commandRequests;

    /**
     * 独立的备料命令。整条生产执行门禁仍保留；本命令不代表允许报工或质量放行。
     * 工单锁串行化同工单的领料，余额锁和乐观版本保护不同工单的竞争。
     */
    @Override
    @Transactional(readOnly = true)
    public com.neuedu.ems.service.wms.LineSideLocationService.Preparation prepareLineSideTransfer(Long outboundId) {
        WmsOutboundOrder order=getTransferableIssueOrder(outboundId);
        MfgWorkOrder owner=consumptionOwner(order,listActiveDetails(outboundId),false);
        return lineSideLocations.prepare(owner.getWorkOrderId());
    }

    @Override
    @Transactional(readOnly = true)
    public void validateLineSideTransfer(Long outboundId, Long targetLocationId) {
        WmsOutboundOrder order=getTransferableIssueOrder(outboundId);
        MfgWorkOrder workOrder=workOrderMapper.selectById(order.getWorkOrderId());
        List<WmsOutboundDetail> lines=listActiveDetails(outboundId);
        if(lines.isEmpty()) throw business(ResultCode.BUSINESS_ERROR,"领料单没有有效明细，请核对后重新发起领料");
        MfgWorkOrder owner=consumptionOwner(order,lines,false);
        requireTransferable(order,workOrder);
        requireTransferable(order,owner);
        validateLineSideSnapshot(owner,lines);
        var target=locationMapper.selectById(targetLocationId);
        var destination=target==null?null:wmsWarehouseMapper.selectById(target.getWarehouseId());
        requireUsableLineSideTarget(target,destination);
        lineSideLocations.requireAllowed(owner.getWorkOrderId(),targetLocationId);
        if(stockComponents!=null) stockComponents.validateStockRelease(order,lines);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferMaterialIssueToLineSide(Long outboundId, Long targetLocationId,
                                               Long operatorId, String remark) {
        transferMaterialIssueToLineSide(outboundId, targetLocationId, operatorId, remark, null);
    }

    /**
     * 领料转线边确认，支持请求键幂等。
     *
     * <p>与生成工单同一口径：同键同摘要重放返回原结果（不重复移库），
     * 同键改目标库位等参数必须拒绝。请求键为空时按既有明细级重放校验执行。</p>
     *
     * <p>没有布尔返回值：方法要么成功完成移库，要么抛业务异常说明拒绝原因，
     * 调用方用异常判断失败即可，不需要再看一个恒为 true 的返回值。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferMaterialIssueToLineSide(Long outboundId, Long targetLocationId,
                                               Long operatorId, String remark, String requestKey) {
        requireTransferRequest(outboundId, targetLocationId, operatorId);
        WmsOutboundOrder initial = getTransferableIssueOrder(outboundId);
        String commandKey = normalizeRequestKey(requestKey);
        String commandDigest = lineSideTransferDigest(outboundId, targetLocationId, remark);
        if (commandKey != null && replayLineSideTransfer(commandKey, commandDigest)) {
            return;
        }
        MfgWorkOrder workOrder = lockIssueWorkOrderPair(initial.getWorkOrderId());
        WmsOutboundOrder order = lockIssueOrder(outboundId, initial);
        List<WmsOutboundDetail> details = lockIssueDetails(outboundId);
        // 半成品领料单挂在组件工单下，但预留、线边占用与需求扣减都归上级整机工单，
        // 否则整机报工时找不到这份线边库存，组件工单又被关闭而无法消耗。
        MfgWorkOrder owner = consumptionOwner(order, details);
        // 已完成命令允许在工单完工后重放，但目标变化或不完整流水必须拒绝。
        if (APPROVED_STATUS.equals(order.getAuditStatus())) {
            verifyLineSideReplay(order, details, owner.getWorkOrderId(), targetLocationId);
            return;
        }
        requireTransferable(order, workOrder);
        requireTransferable(order, owner);
        validateLineSideSnapshot(owner, details);
        var target = lockTransferLocation(targetLocationId);
        WmsWarehouse destination = target == null ? null : lockTransferWarehouse(target.getWarehouseId());
        requireUsableLineSideTarget(target, destination);
        lineSideLocations.requireAllowed(owner.getWorkOrderId(), targetLocationId);
        if (stockComponents != null) stockComponents.requirePassedAndReplace(order, details, operatorId);
        List<MaterialIssueUpdate> issues = prepareMaterialIssueUpdates(order, details);
        applyLineSideTransfer(outboundId, order, details, issues, destination, targetLocationId,
                owner, operatorId, remark, commandKey, commandDigest);
    }

    /** 先锁上级工单再锁自身，与领料生成保持同一加锁顺序，避免并发时互相等待。 */
    private MfgWorkOrder lockIssueWorkOrderPair(Long workOrderId) {
        MfgWorkOrder hint = workOrderMapper.selectById(workOrderId);
        if (hint != null && hint.getParentWorkOrderId() != null) {
            workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                    .eq(MfgWorkOrder::getWorkOrderId, hint.getParentWorkOrderId())
                    .eq(MfgWorkOrder::getDeletedFlag, 0).last("FOR UPDATE"));
        }
        return lockWorkOrder(workOrderId);
    }

    /**
     * 领料单实际消耗的工单。
     *
     * <p>半成品领用挂在组件工单下，用料需求却登记在上级整机工单，所以库存扣减、
     * 线边占用和需求回写都要落到上级工单；原材料领料的归属仍是本工单。</p>
     */
    private MfgWorkOrder consumptionOwner(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        return consumptionOwner(order,details,true);
    }

    private MfgWorkOrder consumptionOwner(WmsOutboundOrder order, List<WmsOutboundDetail> details,boolean lockOwner) {
        MfgWorkOrder own = workOrderMapper.selectById(order.getWorkOrderId());
        if (own == null) {
            throw business(ResultCode.BUSINESS_ERROR, "领料单关联的工单不存在");
        }
        Long ownerId = null;
        for (WmsOutboundDetail detail : details) {
            Long candidate = requirementOwnerId(order.getWorkOrderId(), detail.getMaterialId());
            if (ownerId == null) {
                ownerId = candidate;
            } else if (!ownerId.equals(candidate)) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "同一张领料单不能同时领用本工单和上级工单的物料，请分开领料");
            }
        }
        if (ownerId == null || ownerId.equals(order.getWorkOrderId())) {
            return own;
        }
        MfgWorkOrder owner = lockOwner ? lockWorkOrder(ownerId) : workOrderMapper.selectById(ownerId);
        return owner == null ? own : owner;
    }

    /** 校验移库请求的必要参数与数据范围。 */
    private void requireTransferRequest(Long outboundId, Long targetLocationId, Long operatorId) {
        if (outboundId == null || targetLocationId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "领料单、目标线边库位和操作人不能为空");
        }
        if (dataScopeService.isRestricted()) {
            throw new com.neuedu.ems.exception.DataScopeAccessDeniedException();
        }
    }

    /** 移库只接受关联工单的生产领料单。 */
    private WmsOutboundOrder getTransferableIssueOrder(Long outboundId) {
        WmsOutboundOrder initial = getOutboundOrderById(outboundId);
        boolean applicable = initial != null && MATERIAL_ISSUE_TYPE.equals(initial.getOutboundType());
        if (!applicable || initial.getWorkOrderId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "线边移库必须使用关联工单的生产领料单");
        }
        return initial;
    }

    /** 请求键为空时不参与幂等登记。 */
    private String normalizeRequestKey(String requestKey) {
        return StringUtils.hasText(requestKey) ? requestKey.trim() : null;
    }

    /** 计算线边移库命令摘要，用于同键重放校验。 */
    private String lineSideTransferDigest(Long outboundId, Long targetLocationId, String remark) {
        String normalizedRemark = StringUtils.hasText(remark) ? remark.trim() : "";
        return commandRequests.digest("outbound=" + outboundId
                + ";targetLocation=" + targetLocationId
                + ";remark=" + normalizedRemark);
    }

    /** 锁定来源工单。 */
    private MfgWorkOrder lockWorkOrder(Long workOrderId) {
        return workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, workOrderId)
                .eq(MfgWorkOrder::getDeletedFlag, 0).last("FOR UPDATE"));
    }

    /** 锁定并复核领料单，来源工单不能在此期间发生变化。 */
    private WmsOutboundOrder lockIssueOrder(Long outboundId, WmsOutboundOrder initial) {
        WmsOutboundOrder order = baseMapper.selectOne(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getOutboundId, outboundId)
                .eq(WmsOutboundOrder::getDeletedFlag, 0).last("FOR UPDATE"));
        boolean sourceChanged = order == null || !MATERIAL_ISSUE_TYPE.equals(order.getOutboundType())
                || !Objects.equals(initial.getWorkOrderId(), order.getWorkOrderId());
        if (sourceChanged) {
            throw business(ResultCode.DATA_CONFLICT, "领料单来源已变化，请刷新后重试");
        }
        return order;
    }

    /** 锁定领料单明细，至少要有一条有效明细。 */
    private List<WmsOutboundDetail> lockIssueDetails(Long outboundId) {
        List<WmsOutboundDetail> details = wmsOutboundDetailMapper.selectList(new LambdaQueryWrapper<WmsOutboundDetail>()
                .eq(WmsOutboundDetail::getOutboundId, outboundId).eq(WmsOutboundDetail::getDeletedFlag, 0)
                .orderByAsc(WmsOutboundDetail::getDetailId).last("FOR UPDATE"));
        if (details.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "领料单缺少有效明细");
        }
        return details;
    }

    /** 仅待确认领料单及已下达、生产中的工单可移库。 */
    private void requireTransferable(WmsOutboundOrder order, MfgWorkOrder workOrder) {
        boolean submitted = SUBMITTED_STATUS.equals(order.getAuditStatus());
        boolean workOrderUsable = workOrder != null
                && Set.of("RELEASED", "RUNNING").contains(workOrder.getStatus());
        if (!submitted || !workOrderUsable) {
            throw business(ResultCode.BUSINESS_ERROR, "仅待确认领料单及已下达、生产中的工单可移库");
        }

    }

    /** 按明细扣减来源库存、写移库流水、更新单据状态并登记幂等命令。 */
    private void applyLineSideTransfer(Long outboundId, WmsOutboundOrder order, List<WmsOutboundDetail> details,
                                       List<MaterialIssueUpdate> issues, WmsWarehouse destination,
                                       Long targetLocationId, MfgWorkOrder workOrder, Long operatorId,
                                       String remark, String commandKey, String commandDigest) {
        // 多明细指向同一余额时先汇总，再按稳定顺序锁定并扣减。
        // 根工单会同时领用原料仓零件和半成品仓组件，因此来源仓库按明细库位逐一解析。
        Map<InventoryKey, BigDecimal> quantities = collectTransferQuantities(details);
        List<Map.Entry<InventoryKey, BigDecimal>> lines = new ArrayList<>(quantities.entrySet());
        lines.sort(Comparator.comparing((Map.Entry<InventoryKey, BigDecimal> e) -> e.getKey().locationId())
                .thenComparing(e -> e.getKey().materialId()).thenComparing(e -> e.getKey().batchId()));
        LocalDateTime now = LocalDateTime.now();
        for (var line : lines) {
            moveTransferLine(line.getKey(), line.getValue(), destination, targetLocationId, workOrder, operatorId, now);
        }
        for (WmsOutboundDetail detail : details) {
            String pair = "LS-" + outboundId + "-" + detail.getDetailId();
            Long sourceWarehouseId = resolveDetailWarehouseId(order, detail);
            insertTransferMovement(order, detail, pair, "OUT", sourceWarehouseId, detail.getLocationId(), workOrder, operatorId, now);
            insertTransferMovement(order, detail, pair, "IN", destination.getWarehouseId(), targetLocationId, workOrder, operatorId, now);
        }
        applyMaterialIssueUpdates(issues, operatorId, now);
        markTransferred(outboundId, order, remark, operatorId, now);
        insertStatusChangeLog(outboundId, SUBMITTED_STATUS, APPROVED_STATUS, "WMS_OUTBOUND_ORDER", "TRANSFER_TO_LINE_SIDE", operatorId, remark, now);
        insertOperationLog(outboundId, "TRANSFER_TO_LINE_SIDE", "wms_outbound_order", operatorId, remark, now);
        responsibilityNotificationService.cancelOutboundPending(outboundId, operatorId);
        // 幂等登记与移库写入同事务：失败整单回滚，不会出现"移了库但命令未登记"。
        if (commandKey != null) {
            commandRequests.record(MfgCommandRequest.COMMAND_LINE_SIDE_TRANSFER, commandKey,
                    MfgCommandRequest.AGGREGATE_OUTBOUND_ORDER, outboundId, commandDigest,
                    outboundId, operatorId, remark, now);
        }
    }

    /** 把领料单置为已确认并记录移库时间。 */
    private void markTransferred(Long outboundId, WmsOutboundOrder order, String remark,
                                 Long operatorId, LocalDateTime now) {
        WmsOutboundOrder update = new WmsOutboundOrder();
        update.setOutboundId(outboundId);
        update.setVersion(order.getVersion());
        update.setAuditStatus(APPROVED_STATUS);
        update.setOutboundTime(now);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        update.setRemark(StringUtils.hasText(remark) ? remark : order.getRemark());
        if (baseMapper.updateById(update) != 1) {
            throw business(ResultCode.DATA_CONFLICT, "领料单并发变化");
        }
    }

    /**
     * 重放已登记的线边移库命令：指向的领料单必须确实完成过移库。
     * @param commandKey 请求键
     * @param commandDigest 请求摘要
     * @return 是否命中已完成的重放
     */
    private boolean replayLineSideTransfer(String commandKey, String commandDigest) {
        Long replayedId = commandRequests.replayed(
                MfgCommandRequest.COMMAND_LINE_SIDE_TRANSFER, commandKey, commandDigest);
        if (replayedId == null) {
            return false;
        }
        // 已登记的移库命令必须确实完成过；目标与流水完整性仍按既有校验复核。
        WmsOutboundOrder replayed = baseMapper.selectById(replayedId);
        if (replayed == null || !APPROVED_STATUS.equals(replayed.getAuditStatus())) {
            throw business(ResultCode.DATA_CONFLICT,
                    "命令请求记录指向的领料单未完成移库，请核对后使用新的请求键");
        }
        return true;
    }

    /**
     * 校验线边移库目标库位与目标仓库均可用且为线边类型。
     * @param target 目标库位（可为 null）
     * @param destination 目标仓库（可为 null）
     */
    private void requireUsableLineSideTarget(com.neuedu.ems.entity.wms.WmsLocation target, WmsWarehouse destination) {
        if (!usableLocation(target) || !usableLineSideWarehouse(destination)) {
            throw business(ResultCode.BUSINESS_ERROR, "目标须为启用的线边仓库位");
        }
    }

    /** 库位必须启用且为普通库位。 */
    private boolean usableLocation(com.neuedu.ems.entity.wms.WmsLocation target) {
        if (target == null || !Integer.valueOf(0).equals(target.getDeletedFlag())) {
            return false;
        }
        return Set.of("IDLE", "OCCUPIED", "ENABLE").contains(target.getStatus())
                && "NORMAL".equals(target.getLocationType());
    }

    /** 仓库必须启用且为线边类型。 */
    private boolean usableLineSideWarehouse(WmsWarehouse destination) {
        if (destination == null || !Integer.valueOf(0).equals(destination.getDeletedFlag())) {
            return false;
        }
        return "ENABLE".equals(destination.getStatus())
                && "LINE_SIDE".equals(destination.getWarehouseType());
    }

    /**
     * 逐明细校验来源库位/仓库/批次，并按「来源仓库+库位+物料+批次」汇总移库数量。
     * @param details 领料明细
     * @return 按库存键汇总后的移库数量
     */
    private Map<InventoryKey, BigDecimal> collectTransferQuantities(List<WmsOutboundDetail> details) {
        Map<InventoryKey, BigDecimal> quantities = new LinkedHashMap<>();
        Map<Long, WmsWarehouse> sourceWarehouses = new LinkedHashMap<>();
        for (WmsOutboundDetail detail : details) {
            requireDetailSource(detail);
            executionQuantities.validateForUpdate(detail.getMaterialId(), detail.getOutboundQuantity(), false);
            WmsWarehouse source = requireIssueableSource(detail, sourceWarehouses);
            requireUsableBatch(detail);
            quantities.merge(new InventoryKey(source.getWarehouseId(), detail.getLocationId(),
                    detail.getMaterialId(), detail.getBatchId()), detail.getOutboundQuantity(), BigDecimal::add);
        }
        return quantities;
    }

    /** 明细必须写明来源库位与批次。 */
    private void requireDetailSource(WmsOutboundDetail detail) {
        if (detail.getDetailId() == null || detail.getLocationId() == null || detail.getBatchId() == null) {
            throw business(ResultCode.PARAM_ERROR, "领料明细缺少来源库位或批次");
        }
    }

    /** 锁定并校验来源库位与来源仓库，来源必须是启用的原料或半成品仓。 */
    private WmsWarehouse requireIssueableSource(WmsOutboundDetail detail, Map<Long, WmsWarehouse> sourceWarehouses) {
        var location = lockTransferLocation(detail.getLocationId());
        if (!usableLocation(location)) {
            throw business(ResultCode.BUSINESS_ERROR, "来源库位不可用");
        }
        WmsWarehouse source = sourceWarehouses.computeIfAbsent(location.getWarehouseId(),
                this::lockTransferWarehouse);
        if (!usableIssueableWarehouse(source)) {
            throw business(ResultCode.BUSINESS_ERROR, "来源须为启用的原料或半成品仓");
        }
        return source;
    }

    /** 来源仓库必须启用且属于可领用的仓库类型。 */
    private boolean usableIssueableWarehouse(WmsWarehouse source) {
        if (source == null || !Integer.valueOf(0).equals(source.getDeletedFlag())) {
            return false;
        }
        return "ENABLE".equals(source.getStatus())
                && ISSUEABLE_WAREHOUSE_TYPES.contains(source.getWarehouseType());
    }

    /** 来源批次必须放行且可用，并与明细物料一致。 */
    private void requireUsableBatch(WmsOutboundDetail detail) {
        WmsMaterialBatch batch = wmsMaterialBatchMapper.selectOne(new LambdaQueryWrapper<WmsMaterialBatch>()
                .eq(WmsMaterialBatch::getBatchId, detail.getBatchId())
                .eq(WmsMaterialBatch::getDeletedFlag, 0).last("FOR UPDATE"));
        if (!usableBatch(detail, batch)) {
            throw business(ResultCode.BUSINESS_ERROR, "来源批次未放行或不可用");
        }
    }

    /** 批次存在、物料一致且质量状态与批次状态都允许出库。 */
    private static boolean usableBatch(WmsOutboundDetail detail, WmsMaterialBatch batch) {
        if (batch == null) {
            return false;
        }
        boolean materialMatched = Objects.equals(detail.getMaterialId(), batch.getMaterialId());
        boolean qualityReleased = RELEASED_QUALITY_STATUSES.contains(batch.getQualityStatus());
        boolean statusIssuable = ISSUABLE_BATCH_STATUSES.contains(batch.getStatus());
        return materialMatched && qualityReleased && statusIssuable;
    }

    /**
     * 执行单条库存键的线边移库：扣减来源、落线边占用库存。
     * @param key 来源库存键
     * @param quantity 移库数量
     * @param destination 目标线边仓
     * @param targetLocationId 目标线边库位
     * @param workOrder 关联工单
     * @param operatorId 操作人
     * @param now 业务时间
     */
    private void moveTransferLine(InventoryKey key, BigDecimal quantity, WmsWarehouse destination,
                                  Long targetLocationId, MfgWorkOrder workOrder,
                                  Long operatorId, LocalDateTime now) {
        WmsInventory from = lockTransferInventory(key, 0L);
        if (from == null) {
            throw business(ResultCode.BUSINESS_ERROR, "来源可用库存不足，请重新分配领料批次");
        }
        // 已预留的部分在预留时已经从 available 扣到 locked，这里只把 locked 转出去；
        // 未预留的部分才按旧口径扣减 available。重复扣减会把同一份库存算成两份消耗。
        BigDecimal reservedPart = supplyAllocations.consumeReservation(workOrder.getWorkOrderId(),
                key.materialId(), from.getInventoryId(), quantity, operatorId, now);
        BigDecimal unreservedPart = quantity.subtract(reservedPart);
        if (unreservedPart.signum() > 0
                && zero(from.getAvailableQuantity()).compareTo(unreservedPart) < 0) {
            throw business(ResultCode.BUSINESS_ERROR, "来源可用库存不足，请重新分配领料批次");
        }
        if (zero(from.getLockedQuantity()).compareTo(reservedPart) < 0) {
            throw business(ResultCode.BUSINESS_ERROR, "来源锁定量与预留记录不一致，请刷新后重试");
        }
        deductTransferSource(from, unreservedPart, reservedPart, operatorId, now);
        WmsInventory to = lockTransferInventory(new InventoryKey(destination.getWarehouseId(), targetLocationId,
                key.materialId(), key.batchId()), workOrder.getWorkOrderId());
        if (to == null) {
            to = new WmsInventory();
            to.setWarehouseId(destination.getWarehouseId()); to.setLocationId(targetLocationId);
            to.setMaterialId(key.materialId()); to.setBatchId(key.batchId());
            to.setWorkOrderId(workOrder.getWorkOrderId()); to.setAvailableQuantity(BigDecimal.ZERO);
            to.setLockedQuantity(quantity); to.setInspectionQuantity(BigDecimal.ZERO);
            to.setCreatedBy(operatorId); to.setUpdatedBy(operatorId); to.setDeletedFlag(0);
            if (wmsInventoryMapper.insert(to) != 1) throw business(ResultCode.FAILED, "线边占用库存创建失败");
        } else {
            WmsInventory update = new WmsInventory();
            update.setInventoryId(to.getInventoryId()); update.setVersion(to.getVersion());
            update.setLockedQuantity(zero(to.getLockedQuantity()).add(quantity));
            update.setUpdatedBy(operatorId); update.setUpdatedTime(now);
            if (wmsInventoryMapper.updateById(update) != 1) throw business(ResultCode.DATA_CONFLICT, "线边库存并发变化");
        }
    }

    /**
     * 扣减移库来源余额行：未预留部分扣 available，已预留部分只扣 locked。
     *
     * <p>预留时已经把数量从 available 挪到 locked，两者合计（物理可用量）不变；
     * 移库时把 locked 部分随物理库存一起移出，available 部分才需要真正扣减。</p>
     */
    private void deductTransferSource(WmsInventory from, BigDecimal unreservedPart, BigDecimal reservedPart,
                                      Long operatorId, LocalDateTime now) {
        if (unreservedPart.signum() <= 0 && reservedPart.signum() <= 0) return;
        WmsInventory update = new WmsInventory();
        update.setInventoryId(from.getInventoryId());
        update.setVersion(from.getVersion());
        if (unreservedPart.signum() > 0) {
            update.setAvailableQuantity(zero(from.getAvailableQuantity()).subtract(unreservedPart));
        }
        if (reservedPart.signum() > 0) {
            update.setLockedQuantity(zero(from.getLockedQuantity()).subtract(reservedPart));
        }
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (wmsInventoryMapper.updateById(update) != 1) {
            throw business(ResultCode.DATA_CONFLICT, "库存余额已被其他请求修改，请刷新后重试");
        }
        if (unreservedPart.signum() > 0) {
            notifyAvailableChanged(from.getMaterialId());
        }
    }

    /**
     * 按物料汇总本工单仍有效的预留记录，供领料优先使用已预留库存。
     */
    private Map<Long, List<com.neuedu.ems.entity.mfg.MfgSupplyAllocation>> reservedAllocations(Long workOrderId) {
        Map<Long, List<com.neuedu.ems.entity.mfg.MfgSupplyAllocation>> result = new LinkedHashMap<>();
        for (com.neuedu.ems.entity.mfg.MfgSupplyAllocation allocation : supplyAllocations.reservedAllocationsForWorkOrder(workOrderId)) {
            result.computeIfAbsent(allocation.getMaterialId(), key -> new ArrayList<>()).add(allocation);
        }
        return result;
    }

    private WmsInventory lockTransferInventory(InventoryKey key, Long workOrderId) {
        return wmsInventoryMapper.selectOne(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWarehouseId, key.warehouseId()).eq(WmsInventory::getLocationId, key.locationId())
                .eq(WmsInventory::getMaterialId, key.materialId()).eq(WmsInventory::getBatchId, key.batchId())
                .eq(WmsInventory::getWorkOrderId, workOrderId).eq(WmsInventory::getDeletedFlag, 0).last("FOR UPDATE"));
    }

    private com.neuedu.ems.entity.wms.WmsLocation lockTransferLocation(Long id) {
        return locationMapper.selectOne(new LambdaQueryWrapper<com.neuedu.ems.entity.wms.WmsLocation>()
                .eq(com.neuedu.ems.entity.wms.WmsLocation::getLocationId, id)
                .eq(com.neuedu.ems.entity.wms.WmsLocation::getDeletedFlag, 0).last("FOR UPDATE"));
    }

    private WmsWarehouse lockTransferWarehouse(Long id) {
        return wmsWarehouseMapper.selectOne(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseId, id).eq(WmsWarehouse::getDeletedFlag, 0).last("FOR UPDATE"));
    }

    private void validateLineSideSnapshot(MfgWorkOrder order, List<WmsOutboundDetail> details) {
        var snapshot=snapshotPolicy.read(order);
        Set<Long> directMaterials=new java.util.HashSet<>();
        snapshot.path("bomItems").forEach(item->directMaterials.add(item.path("materialId").longValue()));
        if(details.stream().anyMatch(detail->!directMaterials.contains(detail.getMaterialId())))
            throw business(ResultCode.BUSINESS_ERROR,"领用物料不属于冻结的本级BOM");
    }

    private void insertTransferMovement(WmsOutboundOrder order, WmsOutboundDetail detail, String pair,
                                         String direction, Long warehouseId, Long locationId,
                                         MfgWorkOrder owner, Long userId, LocalDateTime now) {
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo(pair + "-" + direction); movement.setPairNo(pair);
        movement.setMovementType("TRANSFER"); movement.setSourceOrderType("OUTBOUND_ORDER");
        movement.setSourceOrderId(order.getOutboundId());
        // 流水记消耗工单而不是领料单所属工单：半成品领料挂在组件工单下，线边库存却属于整机工单。
        movement.setWorkOrderId(owner == null ? order.getWorkOrderId() : owner.getWorkOrderId());
        movement.setMaterialId(detail.getMaterialId()); movement.setBatchId(detail.getBatchId());
        movement.setWarehouseId(warehouseId); movement.setLocationId(locationId);
        movement.setQuantity(detail.getOutboundQuantity()); movement.setDirection(direction);
        movement.setOperationTime(now); movement.setCreatedBy(userId); movement.setUpdatedBy(userId);
        movement.setRemark("工单领料转线边，不计生产消耗"); movement.setDeletedFlag(0);
        if (wmsStockMovementMapper.insert(movement) != 1) throw business(ResultCode.FAILED, "移库配对流水写入失败");
    }

    private void verifyLineSideReplay(WmsOutboundOrder order, List<WmsOutboundDetail> details,
                                      Long ownerWorkOrderId, Long targetLocationId) {
        List<WmsStockMovement> rows = wmsStockMovementMapper.selectList(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getSourceOrderType, "OUTBOUND_ORDER")
                .eq(WmsStockMovement::getSourceOrderId, order.getOutboundId()).eq(WmsStockMovement::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (rows.size() != details.size() * 2) throw business(ResultCode.DATA_CONFLICT, "已确认领料单不是完整的线边移库");
        for (var detail : details) {
            String pair = "LS-" + order.getOutboundId() + "-" + detail.getDetailId();
            for (String direction : List.of("OUT", "IN")) {
                var matches = rows.stream()
                        .filter(row -> matchesReplayRow(order, detail, ownerWorkOrderId, targetLocationId, pair, direction, row))
                        .count();
                if (matches != 1) throw business(ResultCode.DATA_CONFLICT, "移库目标已变化或配对流水不完整");
            }
        }
    }

    /** 判断流水是否与该明细、方向、目标库位一一对应。 */
    private boolean matchesReplayRow(WmsOutboundOrder order, WmsOutboundDetail detail, Long ownerWorkOrderId,
                                     Long targetLocationId, String pair, String direction, WmsStockMovement row) {
        boolean inbound = "IN".equals(direction);
        Long expectedLocationId = inbound ? targetLocationId : detail.getLocationId();
        boolean contentMatched = matchesReplayContent(order, detail, ownerWorkOrderId, pair, direction, row);
        boolean locationMatched = Objects.equals(expectedLocationId, row.getLocationId());
        boolean warehouseMatched = inbound || Objects.equals(order.getWarehouseId(), row.getWarehouseId());
        return contentMatched && locationMatched && warehouseMatched;
    }

    /** 配对号、方向、单据来源与数量是否都与流水一致。 */
    private boolean matchesReplayContent(WmsOutboundOrder order, WmsOutboundDetail detail, Long ownerWorkOrderId,
                                         String pair, String direction, WmsStockMovement row) {
        boolean quantityMatched = row.getQuantity() != null
                && row.getQuantity().compareTo(detail.getOutboundQuantity()) == 0;
        return matchesReplayPair(pair, direction, row)
                && matchesReplayOwner(order, detail, ownerWorkOrderId, row)
                && quantityMatched;
    }

    /** 配对号、流水方向与流水类型是否一致。 */
    private boolean matchesReplayPair(String pair, String direction, WmsStockMovement row) {
        return pair.equals(row.getPairNo())
                && direction.equals(row.getDirection())
                && "TRANSFER".equals(row.getMovementType());
    }

    /** 工单、物料、批次三者是否与流水一致。 */
    private boolean matchesReplayOwner(WmsOutboundOrder order, WmsOutboundDetail detail, Long ownerWorkOrderId,
                                       WmsStockMovement row) {
        boolean workOrderMatched = Objects.equals(ownerWorkOrderId, row.getWorkOrderId());
        boolean materialMatched = Objects.equals(detail.getMaterialId(), row.getMaterialId());
        boolean batchMatched = Objects.equals(detail.getBatchId(), row.getBatchId());
        return workOrderMatched && materialMatched && batchMatched;
    }

    /**
     * 分页查询出库单，支持单号、类型、仓库、工单、申请人和审核状态筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 关键字，匹配出库单号
     * @param outboundType 出库类型
     * @param warehouseId 仓库ID
     * @param workOrderId 工单ID
     * @param applicantId 申请人ID
     * @param auditStatus 审核状态
     * @return 出库单分页结果
     */
    @Override
    public Page<WmsOutboundOrder> pageOutboundOrders(Integer current, Integer size, String keyword, String outboundType,
                                                     Long warehouseId, Long workOrderId, Long applicantId, String auditStatus) {
        Page<WmsOutboundOrder> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildOutboundOrderQuery(keyword, outboundType, warehouseId, workOrderId, applicantId, auditStatus));
    }

    /**
     * 查询出库单列表，支持单号、类型、仓库、工单、申请人和审核状态筛选。
     * @param keyword 关键字，匹配出库单号
     * @param outboundType 出库类型
     * @param warehouseId 仓库ID
     * @param workOrderId 工单ID
     * @param applicantId 申请人ID
     * @param auditStatus 审核状态
     * @return 出库单列表
     */
    @Override
    public List<WmsOutboundOrder> listOutboundOrders(String keyword, String outboundType, Long warehouseId,
                                                     Long workOrderId, Long applicantId, String auditStatus) {
        return baseMapper.selectList(buildOutboundOrderQuery(keyword, outboundType, warehouseId, workOrderId, applicantId, auditStatus));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsOutboundOrder generateMaterialIssue(Long workOrderId, Long warehouseId,
                                                   Long operatorId, String remark) {
        return generateMaterialIssue(workOrderId, warehouseId, operatorId, remark, null);
    }

    @Override
    @Transactional(readOnly = true)
    public void validateMaterialIssueAvailability(Long workOrderId, Long warehouseId) {
        if (workOrderId == null || warehouseId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产工单和领料仓库不能为空");
        }
        MfgWorkOrder order = workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, workOrderId)
                .eq(MfgWorkOrder::getDeletedFlag, 0));
        if (order == null) {
            throw business(ResultCode.NOT_FOUND, "生产工单不存在或已删除");
        }
        requireIssueableWorkOrder(order);
        requireIssueableWarehouse(order, warehouseId);
        var stockSelection = stockComponents == null ? null : stockComponents.select(order, warehouseId, null, false);
        List<WmsOutboundDetail> details = allocateMaterialIssueDetails(
                warehouseId, stockSelection == null ? outstandingByMaterial(workOrderId, false) : stockSelection.quantities(),
                workOrderId, null);
        if (details.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "目标仓库没有可用于该工单的已放行库存，不能生成领料单");
        }
    }

    /**
     * 生成生产领料单，支持请求键幂等。
     *
     * <p>工单行先加锁串行化同一工单的并发领料；请求键登记与业务写入同事务，
     * 同键同摘要重放原出库单，同键改参数必须拒绝，不能静默重做一遍。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsOutboundOrder generateMaterialIssue(Long workOrderId, Long warehouseId,
                                                   Long operatorId, String remark, String requestKey) {
        requireMaterialIssueParams(workOrderId, warehouseId, operatorId);
        MfgWorkOrder workOrder = lockMaterialIssueWorkOrder(workOrderId);
        String commandKey = normalizeRequestKey(requestKey);
        String commandDigest = materialIssueDigest(workOrderId, warehouseId, remark);
        WmsOutboundOrder replayed = replayMaterialIssue(commandKey, commandDigest);
        if (replayed != null) {
            return replayed;
        }
        requireIssueableWorkOrder(workOrder);
        if (componentHandover != null) componentHandover.synchronize(workOrderId, operatorId);
        requireIssueableWarehouse(workOrder, warehouseId);
        // 出库单挂在发起领料的这张工单上：组件工单领半成品，单子就显示在组件工单的生产领料下。
        // 半成品的用料需求仍登记在上级整机工单，扣需求时按物料回溯到归属工单。
        WmsOutboundOrder pending = findPendingIssueOrder(workOrderId);
        if (pending != null) {
            if (!Objects.equals(pending.getWarehouseId(), warehouseId))
                throw business(ResultCode.BUSINESS_ERROR,"该工单已有其他仓库的待确认领料单，请先完成或撤回，不能把两种供给方式混在同一张单里");
            return pending;
        }
        var stockSelection = stockComponents == null ? null : stockComponents.select(workOrder, warehouseId, operatorId, true);
        Map<Long, BigDecimal> outstandingByMaterial = stockSelection == null ? outstandingByMaterial(workOrderId) : stockSelection.quantities();
        List<WmsOutboundDetail> details = allocateMaterialIssueDetails(
                warehouseId, outstandingByMaterial, workOrderId, operatorId);
        if (details.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "目标仓库没有可用于该工单的已放行库存，不能生成领料单");
        }
        WmsOutboundOrder order = newMaterialIssueOrder(workOrderId, warehouseId, operatorId, remark);
        saveOutboundOrderFull(order, details);
        if (stockComponents != null) stockComponents.attachInspections(order, details, operatorId);
        // 幂等登记与业务写入同事务：任一失败整单回滚，不会留下"有领料单无请求记录"的中间态。
        recordMaterialIssueCommand(commandKey, commandDigest, workOrderId, order, operatorId, remark);
        return order;
    }

    /** 校验领料生成的必要参数。 */
    private void requireMaterialIssueParams(Long workOrderId, Long warehouseId, Long operatorId) {
        if (workOrderId == null || warehouseId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产工单、领料仓库和操作人不能为空");
        }
    }

    /** 锁定生产工单。 */
    private MfgWorkOrder lockMaterialIssueWorkOrder(Long workOrderId) {
        MfgWorkOrder hint=workOrderMapper.selectById(workOrderId);
        if(hint!=null && hint.getParentWorkOrderId()!=null) workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId,hint.getParentWorkOrderId()).eq(MfgWorkOrder::getDeletedFlag,0).last("FOR UPDATE"));
        MfgWorkOrder workOrder = workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, workOrderId)
                .eq(MfgWorkOrder::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (workOrder == null) {
            throw business(ResultCode.NOT_FOUND, "生产工单不存在或已删除");
        }
        return workOrder;
    }

    /** 计算领料生成命令摘要，用于同键重放校验。 */
    private String materialIssueDigest(Long workOrderId, Long warehouseId, String remark) {
        String normalizedRemark = StringUtils.hasText(remark) ? remark.trim() : "";
        return commandRequests.digest("workOrder=" + workOrderId
                + ";warehouse=" + warehouseId + ";remark=" + normalizedRemark);
    }

    /** 命中已登记的同键命令时返回原领料单，未命中返回 null。 */
    private WmsOutboundOrder replayMaterialIssue(String commandKey, String commandDigest) {
        if (commandKey == null) {
            return null;
        }
        Long replayedId = commandRequests.replayed(
                MfgCommandRequest.COMMAND_MATERIAL_ISSUE_GENERATE, commandKey, commandDigest);
        if (replayedId == null) {
            return null;
        }
        WmsOutboundOrder replayed = baseMapper.selectById(replayedId);
        if (replayed == null) {
            throw business(ResultCode.DATA_CONFLICT,
                    "命令请求记录指向的领料单已不存在，请使用新的请求键");
        }
        return replayed;
    }

    /** 只有已下达或生产中的工单可以发起领料。 */
    private void requireIssueableWorkOrder(MfgWorkOrder workOrder) {
        if (!Set.of(BusinessStatus.Document.RELEASED, BusinessStatus.Production.RUNNING)
                .contains(workOrder.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已下达或生产中的工单可以发起领料");
        }
    }

    /**
     * 目标仓库必须启用且为原料仓或半成品仓。
     *
     * <p>整机工单只领原材料：它要用的组件由组件工单整份领用半成品后直接交接，
     * 整机工单再自己去半成品仓领一次就会重复占用同一份组件，也让领料单挂错工单。</p>
     */
    private void requireIssueableWarehouse(MfgWorkOrder workOrder, Long warehouseId) {
        WmsWarehouse warehouse = wmsWarehouseMapper.selectOne(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseId, warehouseId)
                .eq(WmsWarehouse::getStatus, BusinessStatus.Common.ENABLE)
                .eq(WmsWarehouse::getDeletedFlag, 0));
        if (warehouse == null) {
            throw business(ResultCode.BUSINESS_ERROR, "目标领料仓库不存在、已停用或已删除");
        }
        if (!ISSUEABLE_WAREHOUSE_TYPES.contains(warehouse.getWarehouseType())) {
            throw business(ResultCode.BUSINESS_ERROR, "生产领料只能从原料仓或半成品仓发起，不能从成品、线边或不良品仓领料");
        }
        if (isRootWorkOrder(workOrder) && !RAW_WAREHOUSE_TYPE.equals(warehouse.getWarehouseType())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "整机工单只能领原材料，不能从半成品仓领料；半成品请在对应的组件工单里整份领用，"
                            + "检验合格后会自动交接给整机工单");
        }
    }

    /** 整机工单：没有上级工单的自制件工单。 */
    private boolean isRootWorkOrder(MfgWorkOrder workOrder) {
        return workOrder != null && workOrder.getParentWorkOrderId() == null;
    }

    /**
     * 该物料的需求登记在哪张工单上：本工单有这条需求就是本工单，
     * 没有则落到上级工单（组件工单领半成品时，需求在整机工单上）。
     */
    private Long requirementOwnerId(Long workOrderId, Long materialId) {
        if (stockComponents != null) {
            return stockComponents.requirementOwner(workOrderId, materialId);
        }
        return workOrderId;
    }

    /** 已存在待确认领料单时直接复用（多于一张视为重复数据）。 */
    private WmsOutboundOrder findPendingIssueOrder(Long workOrderId) {
        List<WmsOutboundOrder> pendingOrders = baseMapper.selectList(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getOutboundType, MATERIAL_ISSUE_TYPE)
                .eq(WmsOutboundOrder::getWorkOrderId, workOrderId)
                .eq(WmsOutboundOrder::getAuditStatus, SUBMITTED_STATUS)
                .eq(WmsOutboundOrder::getDeletedFlag, 0));
        if (pendingOrders.size() > 1) {
            throw business(ResultCode.DATA_CONFLICT, "该工单存在多张待确认生产领料单，请先处理重复数据");
        }
        return pendingOrders.isEmpty() ? null : pendingOrders.get(0);
    }

    /** 按工单用料需求汇总未领数量，全部领完时拒绝重复发起。 */
    private Map<Long, BigDecimal> outstandingByMaterial(Long workOrderId) {
        return outstandingByMaterial(workOrderId, true);
    }

    private Map<Long, BigDecimal> outstandingByMaterial(Long workOrderId, boolean lock) {
        MfgWorkOrder workOrder = workOrderMapper.selectById(workOrderId);
        LambdaQueryWrapper<MfgWorkOrderMaterial> query = new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .eq(MfgWorkOrderMaterial::getWorkOrderId, workOrderId)
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0)
                        .orderByAsc(MfgWorkOrderMaterial::getRequirementId);
        if (lock) query.last("FOR UPDATE");
        List<MfgWorkOrderMaterial> requirements = workOrderMaterialMapper.selectList(query);
        Map<Long, BigDecimal> outstandingByMaterial = new LinkedHashMap<>();
        for (MfgWorkOrderMaterial requirement : requirements) {
            BigDecimal outstanding = zero(requirement.getRequiredQuantity())
                    .subtract(zero(requirement.getIssuedQuantity())).max(BigDecimal.ZERO);
            if (componentHandover != null) outstanding = componentHandover.warehouseOutstanding(workOrder, requirement);
            if (requirement.getMaterialId() != null && outstanding.signum() > 0) {
                outstandingByMaterial.merge(requirement.getMaterialId(), outstanding, BigDecimal::add);
            }
        }
        if (outstandingByMaterial.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "本工单没有尚需仓库领用的物料；同线组件由前置工单检验合格后自动交接，请查看用料到位状态");
        }
        return outstandingByMaterial;
    }

    /** 组装生产领料单头。 */
    private WmsOutboundOrder newMaterialIssueOrder(Long workOrderId, Long warehouseId, Long operatorId,
                                                   String remark) {
        WmsOutboundOrder order = new WmsOutboundOrder();
        order.setOutboundType(MATERIAL_ISSUE_TYPE);
        order.setWarehouseId(warehouseId);
        order.setWorkOrderId(workOrderId);
        order.setApplicantId(operatorId);
        order.setRemark(StringUtils.hasText(remark) ? remark.trim() : "从生产工单用料需求自动生成");
        order.setCreatedBy(operatorId);
        order.setUpdatedBy(operatorId);
        return order;
    }

    /** 登记幂等命令记录（请求键为空时跳过）。 */
    private void recordMaterialIssueCommand(String commandKey, String commandDigest, Long workOrderId,
                                            WmsOutboundOrder order, Long operatorId, String remark) {
        if (commandKey == null) {
            return;
        }
        commandRequests.record(MfgCommandRequest.COMMAND_MATERIAL_ISSUE_GENERATE, commandKey,
                MfgCommandRequest.AGGREGATE_WORK_ORDER, workOrderId, commandDigest,
                order.getOutboundId(), operatorId, remark, LocalDateTime.now());
    }

    /**
     * 解析可用于生产领料的仓库集合。首选仓库排在最前，其余原料仓、半成品仓按仓库ID兜底，
     * 这样根工单领用组件成品时能自动到半成品仓取料。
     * @param preferredWarehouseId 单据上选定的仓库ID
     * @return 库位归属的候选仓库ID列表
     */
    private List<Long> issuableWarehouseIds(Long preferredWarehouseId) {
        WmsWarehouse preferred = preferredWarehouseId == null ? null : wmsWarehouseMapper.selectById(preferredWarehouseId);
        if (preferred != null && Set.of("SEMI", "SEMI_FINISHED").contains(preferred.getWarehouseType()))
            return List.of(preferredWarehouseId);
        List<WmsWarehouse> warehouses = wmsWarehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .in(WmsWarehouse::getWarehouseType, ISSUEABLE_WAREHOUSE_TYPES)
                .eq(WmsWarehouse::getStatus, BusinessStatus.Common.ENABLE)
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .orderByAsc(WmsWarehouse::getWarehouseId));
        List<Long> ids = new ArrayList<>();
        if (preferredWarehouseId != null) {
            ids.add(preferredWarehouseId);
        }
        for (WmsWarehouse warehouse : warehouses) {
            if (!ids.contains(warehouse.getWarehouseId())) {
                ids.add(warehouse.getWarehouseId());
            }
        }
        return ids;
    }

    private List<WmsOutboundDetail> allocateMaterialIssueDetails(Long warehouseId,
                                                                  Map<Long, BigDecimal> outstandingByMaterial,
                                                                  Long workOrderId, Long operatorId) {
        List<WmsOutboundDetail> details = new ArrayList<>();
        List<Long> sourceWarehouseIds = issuableWarehouseIds(warehouseId);
        for (Map.Entry<Long, BigDecimal> request : outstandingByMaterial.entrySet()) {
            Long materialId = request.getKey();
            // 预留记在需求归属工单上：组件工单领半成品时，预留属于上级整机工单。
            Long ownerId = requirementOwnerId(workOrderId, materialId);
            Map<Long, List<com.neuedu.ems.entity.mfg.MfgSupplyAllocation>> reserved = reservedAllocations(ownerId);
            BigDecimal remaining = request.getValue();
            // 预留优先：已预留的数量在预留时就从 available 挪到了 locked，
            // 若仍按 available 选源，预留过的库存会被判成"不可用"，从而重复补产或直接报缺料。
            remaining = allocateReservedStock(details, reserved.getOrDefault(materialId, List.of()),
                    sourceWarehouseIds, ownerId, operatorId, materialId, remaining);
            if (remaining.signum() <= 0) {
                continue;
            }
            remaining = allocateAvailableStock(details, sourceWarehouseIds, ownerId, operatorId, materialId, remaining);
            if (remaining.signum() > 0) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "原料仓及半成品仓库存未齐套，物料 " + materialId
                                + " 仍缺 " + remaining.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString()
                                + "，不能生成生产领料单；组件检验放行后，请先在组件工单的产出入库中确认入库");
            }
        }
        return details;
    }

    /**
     * 优先用本工单已预留的库存生成领料明细。
     * @return 仍未分配完的数量
     */
    private BigDecimal allocateReservedStock(List<WmsOutboundDetail> details,
                                             List<com.neuedu.ems.entity.mfg.MfgSupplyAllocation> allocations,
                                             List<Long> sourceWarehouseIds, Long workOrderId, Long operatorId,
                                             Long materialId, BigDecimal remaining) {
        for (com.neuedu.ems.entity.mfg.MfgSupplyAllocation allocation : allocations) {
            if (remaining.signum() <= 0) break;
            BigDecimal reservedQuantity = zero(allocation.getReservedQuantity());
            if (reservedQuantity.signum() <= 0) continue;
            WmsInventory inventory = wmsInventoryMapper.selectById(allocation.getInventoryId());
            if (inventory == null || !sourceWarehouseIds.contains(inventory.getWarehouseId())
                    || !Objects.equals(inventory.getDeletedFlag(), 0)) continue;
            WmsMaterialBatch batch = getActiveBatch(allocation.getBatchId());
            if (batch == null || !RELEASED_QUALITY_STATUSES.contains(batch.getQualityStatus())
                    || !ISSUABLE_BATCH_STATUSES.contains(batch.getStatus())) continue;
            BigDecimal allocated = remaining.min(reservedQuantity);
            if (allocated.signum() <= 0) continue;
            WmsOutboundDetail detail = new WmsOutboundDetail();
            detail.setMaterialId(materialId);
            detail.setBatchId(allocation.getBatchId());
            detail.setLocationId(allocation.getLocationId());
            detail.setOutboundQuantity(allocated);
            detail.setRemark("工单 " + workOrderId + " 使用已预留库存 " + allocation.getAllocationNo());
            detail.setCreatedBy(operatorId);
            detail.setUpdatedBy(operatorId);
            details.add(detail);
            remaining = remaining.subtract(allocated);
        }
        return remaining;
    }

    /**
     * 按批次先入先出从未预留的公共可用库存生成领料明细。
     * @return 仍未分配完的数量
     */
    private BigDecimal allocateAvailableStock(List<WmsOutboundDetail> details, List<Long> sourceWarehouseIds,
                                              Long workOrderId, Long operatorId, Long materialId,
                                              BigDecimal remaining) {
        List<MaterialIssueSource> sources = new ArrayList<>();
        List<WmsInventory> inventories = wmsInventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .in(WmsInventory::getWarehouseId, sourceWarehouseIds)
                .eq(WmsInventory::getMaterialId, materialId)
                .gt(WmsInventory::getAvailableQuantity, BigDecimal.ZERO)
                .eq(WmsInventory::getWorkOrderId, 0L)
                .eq(WmsInventory::getDeletedFlag, 0));
        for (WmsInventory inventory : inventories) {
            if (inventory.getBatchId() == null || inventory.getLocationId() == null) {
                continue;
            }
            WmsMaterialBatch batch = getActiveBatch(inventory.getBatchId());
            if (batch != null && Objects.equals(batch.getMaterialId(), materialId)
                    && RELEASED_QUALITY_STATUSES.contains(batch.getQualityStatus())
                    && ISSUABLE_BATCH_STATUSES.contains(batch.getStatus())) {
                sources.add(new MaterialIssueSource(inventory, batch));
            }
        }
        sources.sort(Comparator
                .comparing((MaterialIssueSource source) -> source.batch().getReceivedDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(source -> source.batch().getBatchId()));
        for (MaterialIssueSource source : sources) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal allocated = remaining.min(zero(source.inventory().getAvailableQuantity()));
            if (allocated.signum() <= 0) {
                continue;
            }
            WmsOutboundDetail detail = new WmsOutboundDetail();
            detail.setMaterialId(materialId);
            detail.setBatchId(source.batch().getBatchId());
            detail.setLocationId(source.inventory().getLocationId());
            detail.setOutboundQuantity(allocated);
            detail.setRemark("工单 " + workOrderId + " 按 FIFO 自动预分配");
            detail.setCreatedBy(operatorId);
            detail.setUpdatedBy(operatorId);
            details.add(detail);
            remaining = remaining.subtract(allocated);
        }
        return remaining;
    }

    /**
     * 根据出库单ID查询未删除的出库单详情。
     * @param outboundId 出库单ID
     * @return 出库单实体
     */
    @Override
    public WmsOutboundOrder getOutboundOrderById(Long outboundId) {
        LambdaQueryWrapper<WmsOutboundOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsOutboundOrder::getOutboundId, outboundId)
                .eq(WmsOutboundOrder::getDeletedFlag, 0);
        return baseMapper.selectOne(queryWrapper);
    }

    /**
     * 新增出库单，自动设置删除标记为0。
     * @param outboundOrder 出库单实体
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean saveOutboundOrder(WmsOutboundOrder outboundOrder) {
        if (outboundOrder != null && MATERIAL_ISSUE_TYPE.equals(outboundOrder.getOutboundType())) {
            throw business(ResultCode.BUSINESS_ERROR, "生产领料必须从工单用料需求发起并同时提交领料明细");
        }
        return saveOutboundOrderHeader(outboundOrder);
    }

    private boolean saveOutboundOrderHeader(WmsOutboundOrder outboundOrder) {
        if (outboundOrder == null || outboundOrder.getWarehouseId() == null) {
            throw business(ResultCode.PARAM_ERROR, "出库单和出库仓库不能为空");
        }
        outboundOrder.setOutboundId(null);
        outboundOrder.setOutboundNo(nextOutboundNo(outboundOrder));
        outboundOrder.setAuditStatus(SUBMITTED_STATUS);
        outboundOrder.setOutboundTime(null);
        outboundOrder.setDeletedFlag(0);
        boolean saved = baseMapper.insert(outboundOrder) > 0;
        if (saved) {
            responsibilityNotificationService.publishOutboundPending(outboundOrder, outboundOrder.getCreatedBy());
        }
        return saved;
    }

    /**
     * 出库单号：生产领料按工单取号，客户销售领料按客户取号，其余按通用出库取号。
     *
     * <p>三类单号前缀与取值维度都不同，拆出来单独判断，避免在方法体里堆嵌套三元。</p>
     */
    private String nextOutboundNo(WmsOutboundOrder outboundOrder) {
        if (outboundOrder.getWorkOrderId() != null) {
            return numbers.nextFor("MI", "WORK_ORDER", outboundOrder.getWorkOrderId());
        }
        if (outboundOrder.getCustomerId() != null) {
            return numbers.next("SI", outboundOrder.getCustomerId());
        }
        return numbers.next("OUT", null);
    }

    /**
     * 新增出库单并保存出库明细。
     * @param outboundOrder 出库单实体
     * @param details 出库明细列表
     * @return 出库单ID
     */
    @Override
    @Transactional
    public Long saveOutboundOrderFull(WmsOutboundOrder outboundOrder, List<WmsOutboundDetail> details) {
        if (details == null || details.isEmpty()) {
            throw business(ResultCode.PARAM_ERROR, "出库单至少需要一条明细");
        }
        prepareMaterialIssueUpdates(outboundOrder, details);
        if (!saveOutboundOrderHeader(outboundOrder)) {
            throw business(ResultCode.FAILED, "出库单保存失败");
        }
        insertDetails(outboundOrder.getOutboundId(), details);
        return outboundOrder.getOutboundId();
    }

    /** 写入出库明细（明细ID由数据库生成）。 */
    private void insertDetails(Long outboundId, List<WmsOutboundDetail> details) {
        for (WmsOutboundDetail detail : details) {
            detail.setInspectionId(null);
            detail.setDetailId(null);
            detail.setOutboundId(outboundId);
            detail.setDeletedFlag(0);
            if (wmsOutboundDetailMapper.insert(detail) == 0) {
                throw business(ResultCode.FAILED, "出库明细保存失败");
            }
        }
    }

    /**
     * 更新出库单，已确认出库单不允许普通修改。
     * @param outboundOrder 出库单实体
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean updateOutboundOrder(WmsOutboundOrder outboundOrder) {
        if (outboundOrder.getOutboundId() == null) {
            return false;
        }
        WmsOutboundOrder existing = getOutboundOrderById(outboundOrder.getOutboundId());
        if (existing == null || !SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            return false;
        }
        if (listActiveDetails(existing.getOutboundId()).stream().anyMatch(d -> d.getInspectionId()!=null))
            outboundOrder.setWarehouseId(null);
        outboundOrder.setDeletedFlag(null);
        outboundOrder.setOutboundNo(null);
        outboundOrder.setAuditStatus(null);
        outboundOrder.setOutboundTime(null);
        outboundOrder.setOutboundType(null);
        outboundOrder.setWorkOrderId(null);
        outboundOrder.setApplicantId(null);
        if (outboundOrder.getVersion() == null) {
            outboundOrder.setVersion(existing.getVersion());
        }
        boolean warehouseChanged = outboundOrder.getWarehouseId() != null
                && !Objects.equals(existing.getWarehouseId(), outboundOrder.getWarehouseId());
        boolean updated = baseMapper.updateById(outboundOrder) > 0;
        if (updated && warehouseChanged) {
            responsibilityNotificationService.cancelOutboundPending(existing.getOutboundId(), outboundOrder.getUpdatedBy());
            responsibilityNotificationService.publishOutboundPending(
                    getOutboundOrderById(existing.getOutboundId()), outboundOrder.getUpdatedBy());
        }
        return updated;
    }

    /**
     * 更新出库单并替换出库明细，已确认出库单不允许替换明细。
     * @param outboundOrder 出库单实体
     * @param details 出库明细列表
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean updateOutboundOrderFull(WmsOutboundOrder outboundOrder, List<WmsOutboundDetail> details) {
        WmsOutboundOrder existing = outboundOrder == null ? null : getOutboundOrderById(outboundOrder.getOutboundId());
        boolean materialIssue = existing != null && MATERIAL_ISSUE_TYPE.equals(existing.getOutboundType());
        if (materialIssue && details != null) {
            if (listActiveDetails(existing.getOutboundId()).stream().anyMatch(d -> d.getInspectionId() != null))
                throw business(ResultCode.BUSINESS_ERROR,"库存半成品领料已关联检验，不能修改批次或数量；请撤回后重新发起");
            prepareMaterialIssueUpdates(existing, details);
        }
        if (!updateOutboundOrder(outboundOrder)) {
            return false;
        }
        if (details != null) {
            softDeleteDetails(outboundOrder.getOutboundId());
            insertDetails(outboundOrder.getOutboundId(), details);
        }
        return true;
    }

    /**
     * 根据ID软删除出库单和明细，已确认出库单不允许删除。
     * @param outboundId 出库单ID
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean removeOutboundOrderById(Long outboundId) {
        WmsOutboundOrder existing = getOutboundOrderById(outboundId);
        if (existing == null || !SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            return false;
        }
        WmsOutboundOrder outboundOrder = new WmsOutboundOrder();
        outboundOrder.setOutboundId(outboundId);
        outboundOrder.setDeletedFlag(1);
        outboundOrder.setVersion(existing.getVersion());
        if (baseMapper.updateById(outboundOrder) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "出库单已被其他用户修改，请刷新后重试");
        }
        if (stockComponents != null) stockComponents.cancel(existing,listActiveDetails(outboundId),existing.getUpdatedBy());
        softDeleteDetails(outboundId);
        responsibilityNotificationService.cancelOutboundPending(outboundId, existing.getUpdatedBy());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawOutboundOrder(Long outboundId, Long operatorId, String remark) {
        WmsOutboundOrder existing = getOutboundOrderById(outboundId);
        if (existing == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "出库单和操作人不能为空");
        }
        remark = StringUtils.hasText(remark) ? remark.trim() : "用户主动撤回，未填写补充原因";
        if (!SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有未确认出库单可以撤回");
        }
        WmsOutboundOrder update = new WmsOutboundOrder();
        update.setOutboundId(outboundId); update.setVersion(existing.getVersion());
        update.setAuditStatus(BusinessStatus.Document.CLOSED); update.setUpdatedBy(operatorId); update.setUpdatedTime(LocalDateTime.now());
        if (baseMapper.updateById(update) == 0) throw business(ResultCode.DATA_CONFLICT, "出库单已被并发修改");
        if (stockComponents != null) stockComponents.cancel(existing,listActiveDetails(outboundId),operatorId);
        LocalDateTime now = LocalDateTime.now();
        insertStatusChangeLog(outboundId, existing.getAuditStatus(), BusinessStatus.Document.CLOSED,
                "WMS_OUTBOUND_ORDER", "WITHDRAW_OUTBOUND", operatorId, remark, now);
        insertOperationLog(outboundId, "WITHDRAW_OUTBOUND", "wms_outbound_order", operatorId, remark, now);
        responsibilityNotificationService.cancelOutboundPending(outboundId, operatorId);
    }

    /**
     * 确认出库，校验库存后扣减可用库存并写入库存流水。
     * @param outboundId 出库单ID
     * @param operatorId 操作人ID
     * @param remark 备注
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean confirmOutboundOrder(Long outboundId, Long operatorId, String remark) {
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "当前登录用户不能为空");
        }
        WmsOutboundOrder outboundOrder = getOutboundOrderById(outboundId);
        if (outboundOrder == null) {
            throw business(ResultCode.NOT_FOUND, "出库单不存在或已删除");
        }
        if ("SALE".equals(outboundOrder.getOutboundType())) {
            throw business(ResultCode.BUSINESS_ERROR, "销售出库必须通过销售发货入口确认并核对出货检验");
        }
        if (MATERIAL_ISSUE_TYPE.equals(outboundOrder.getOutboundType())) {
            MfgWorkOrder workOrder = workOrderMapper.selectById(outboundOrder.getWorkOrderId());
            if (workOrder == null) throw business(ResultCode.BUSINESS_ERROR, "领料单关联的工单不存在");
            preparationGate.assertExecutionSupported(workOrder.getProductId());
        }
        if (!SUBMITTED_STATUS.equals(outboundOrder.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有 SUBMITTED 状态的出库单可以确认");
        }
        if (outboundOrder.getWarehouseId() == null) {
            throw business(ResultCode.PARAM_ERROR, "出库仓库不能为空");
        }
        List<WmsOutboundDetail> details = listActiveDetails(outboundId);
        if (details.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "出库单至少需要一条有效明细");
        }
        if (MATERIAL_ISSUE_TYPE.equals(outboundOrder.getOutboundType())
                && details.stream().anyMatch(detail -> detail.getInspectionId() != null)) {
            throw business(ResultCode.BUSINESS_ERROR, "库存组件领用必须使用转线边入口，普通确认出库不能建立线边库存");
        }
        if (stockComponents != null && MATERIAL_ISSUE_TYPE.equals(outboundOrder.getOutboundType())) {
            stockComponents.validateStockRelease(outboundOrder, details);
        }
        Map<InventoryKey, InventoryDeduction> deductions = prepareInventoryDeductions(outboundOrder, details);
        List<MaterialIssueUpdate> materialIssueUpdates = prepareMaterialIssueUpdates(outboundOrder, details);
        LocalDateTime now = LocalDateTime.now();
        WmsOutboundOrder update = new WmsOutboundOrder();
        update.setOutboundId(outboundId);
        update.setVersion(outboundOrder.getVersion());
        update.setAuditStatus(APPROVED_STATUS);
        update.setOutboundTime(now);
        update.setRemark(StringUtils.hasText(remark) ? remark : outboundOrder.getRemark());
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "出库单已被其他用户修改，请刷新后重试");
        }
        for (InventoryDeduction deduction : deductions.values()) {
            decreaseInventory(outboundOrder, deduction, operatorId, now);
        }
        for (WmsOutboundDetail detail : details) {
            insertOutboundMovement(outboundOrder, detail, operatorId, remark, now);
        }
        applyMaterialIssueUpdates(materialIssueUpdates, operatorId, now);
        if (MATERIAL_ISSUE_TYPE.equals(outboundOrder.getOutboundType())) {
            mfgDispatchOrderService.refreshMaterialReadiness(outboundOrder.getWorkOrderId(), operatorId);
        }
        insertStatusChangeLog(outboundOrder.getOutboundId(), outboundOrder.getAuditStatus(), APPROVED_STATUS,
                "WMS_OUTBOUND_ORDER", "CONFIRM_OUTBOUND", operatorId, remark, now);
        insertOperationLog(outboundOrder.getOutboundId(), "CONFIRM_OUTBOUND", "wms_outbound_order", operatorId, remark, now);
        responsibilityNotificationService.cancelOutboundPending(outboundId, operatorId);
        responsibilityNotificationService.publishOutboundCompleted(outboundOrder, operatorId);
        return true;
    }

    /**
     * 构建出库单查询条件，默认过滤已删除记录。
     * @param keyword 关键字，匹配出库单号
     * @param outboundType 出库类型
     * @param warehouseId 仓库ID
     * @param workOrderId 工单ID
     * @param applicantId 申请人ID
     * @param auditStatus 审核状态
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<WmsOutboundOrder> buildOutboundOrderQuery(String keyword, String outboundType,
                                                                         Long warehouseId, Long workOrderId,
                                                                         Long applicantId, String auditStatus) {
        LambdaQueryWrapper<WmsOutboundOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsOutboundOrder::getDeletedFlag, 0)
                .like(StringUtils.hasText(keyword), WmsOutboundOrder::getOutboundNo, keyword)
                .eq(StringUtils.hasText(outboundType), WmsOutboundOrder::getOutboundType, outboundType)
                .eq(warehouseId != null, WmsOutboundOrder::getWarehouseId, warehouseId)
                .eq(workOrderId != null, WmsOutboundOrder::getWorkOrderId, workOrderId)
                .eq(applicantId != null, WmsOutboundOrder::getApplicantId, applicantId)
                .eq(StringUtils.hasText(auditStatus), WmsOutboundOrder::getAuditStatus, auditStatus)
                .orderByDesc(WmsOutboundOrder::getCreatedTime);
        return queryWrapper;
    }

    /**
     * 软删除指定出库单的所有明细。
     * @param outboundId 出库单ID
     */
    private void softDeleteDetails(Long outboundId) {
        List<WmsOutboundDetail> details = listActiveDetails(outboundId);
        for (WmsOutboundDetail detail : details) {
            WmsOutboundDetail update = new WmsOutboundDetail();
            update.setDetailId(detail.getDetailId());
            update.setDeletedFlag(1);
            if (wmsOutboundDetailMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "出库明细已被其他用户修改，请刷新后重试");
            }
        }
    }

    /**
     * 查询指定出库单的未删除明细。
     * @param outboundId 出库单ID
     * @return 出库明细列表
     */
    private List<WmsOutboundDetail> listActiveDetails(Long outboundId) {
        LambdaQueryWrapper<WmsOutboundDetail> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsOutboundDetail::getOutboundId, outboundId)
                .eq(WmsOutboundDetail::getDeletedFlag, 0);
        return wmsOutboundDetailMapper.selectList(queryWrapper);
    }

    /**
     * 校验一条出库明细可用于扣减库存，并返回其库存唯一键。
     * @param outboundOrder 出库单实体
     * @param detail 出库明细实体
     * @return 该明细对应的库存唯一键
     */
    private InventoryKey validateOutboundDetailForDeduction(WmsOutboundOrder outboundOrder, WmsOutboundDetail detail) {
        requireDeductionFields(detail);
        executionQuantities.validate(detail.getMaterialId(), detail.getOutboundQuantity(), false);
        requireUsableOutboundBatch(detail);
        return new InventoryKey(resolveDetailWarehouseId(outboundOrder, detail), detail.getLocationId(),
                detail.getMaterialId(), detail.getBatchId());
    }

    /** 出库明细的物料、批次、库位和正数数量必须齐备。 */
    private void requireDeductionFields(WmsOutboundDetail detail) {
        boolean missingReason = detail.getDetailId() == null || detail.getMaterialId() == null
                || detail.getBatchId() == null || detail.getLocationId() == null;
        BigDecimal quantity = detail.getOutboundQuantity();
        boolean badQuantity = quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0;
        if (missingReason || badQuantity) {
            throw business(ResultCode.PARAM_ERROR, "出库明细的物料、批次、库位和正数数量不能为空");
        }
    }

    /** 明细关联的批次必须存在、物料一致且已放行可用。 */
    private void requireUsableOutboundBatch(WmsOutboundDetail detail) {
        WmsMaterialBatch batch = getActiveBatch(detail.getBatchId());
        if (batch == null) {
            throw business(ResultCode.NOT_FOUND, "出库明细关联的物料批次不存在或已删除");
        }
        if (!detail.getMaterialId().equals(batch.getMaterialId())) {
            throw business(ResultCode.BUSINESS_ERROR, "出库明细物料与批次物料不一致");
        }
        boolean released = RELEASED_QUALITY_STATUSES.contains(batch.getQualityStatus());
        boolean issuable = ISSUABLE_BATCH_STATUSES.contains(batch.getStatus());
        if (!released || !issuable) {
            throw business(ResultCode.BUSINESS_ERROR, "批次未放行或不可用，禁止出库");
        }
    }

    /**
     * 校验出库明细库存是否足够扣减。
     * @param outboundOrder 出库单实体
     * @param details 出库明细列表
     * @return 按库存唯一键汇总后的扣减计划
     */
    private Map<InventoryKey, InventoryDeduction> prepareInventoryDeductions(
            WmsOutboundOrder outboundOrder, List<WmsOutboundDetail> details) {
        Map<InventoryKey, BigDecimal> requiredQuantities = new LinkedHashMap<>();
        for (WmsOutboundDetail detail : details) {
            InventoryKey key = validateOutboundDetailForDeduction(outboundOrder, detail);
            requiredQuantities.merge(key, detail.getOutboundQuantity(), BigDecimal::add);
        }

        Map<InventoryKey, InventoryDeduction> deductions = new LinkedHashMap<>();
        for (Map.Entry<InventoryKey, BigDecimal> entry : requiredQuantities.entrySet()) {
            InventoryKey key = entry.getKey();
            WmsInventory inventory = selectInventory(
                    key.warehouseId(), key.locationId(), key.materialId(), key.batchId());
            BigDecimal availableQuantity = inventory == null || inventory.getAvailableQuantity() == null
                    ? BigDecimal.ZERO : inventory.getAvailableQuantity();
            // 本工单已预留的数量在预留时就挪到了 locked，可用量校验必须把预留一起算进来。
            BigDecimal coveredQuantity = availableQuantity.add(reservedQuantity(outboundOrder, key, inventory));
            if (coveredQuantity.compareTo(entry.getValue()) < 0) {
                throw business(ResultCode.BUSINESS_ERROR, "出库库存不足，请核对仓库、库位、物料、批次和数量");
            }
            deductions.put(key, new InventoryDeduction(inventory, entry.getValue()));
        }
        return deductions;
    }

    private WmsMaterialBatch getActiveBatch(Long batchId) {
        LambdaQueryWrapper<WmsMaterialBatch> query = new LambdaQueryWrapper<>();
        query.eq(WmsMaterialBatch::getBatchId, batchId)
                .eq(WmsMaterialBatch::getDeletedFlag, 0);
        return wmsMaterialBatchMapper.selectOne(query);
    }

    private List<MaterialIssueUpdate> prepareMaterialIssueUpdates(WmsOutboundOrder outboundOrder,
                                                                   List<WmsOutboundDetail> details) {
        if (outboundOrder == null || !MATERIAL_ISSUE_TYPE.equals(outboundOrder.getOutboundType())) {
            return List.of();
        }
        Map<Long, BigDecimal> requestedByMaterial = collectRequestedByMaterial(details);
        // 领料单挂在组件工单下，半成品的用料需求却登记在上级整机工单：
        // 按物料逐一定位需求归属工单，原材料仍然扣本工单的需求行。
        Map<Long, List<MfgWorkOrderMaterial>> requirementsByOwner = new LinkedHashMap<>();
        List<MaterialIssueUpdate> updates = new java.util.ArrayList<>();
        for (Map.Entry<Long, BigDecimal> request : requestedByMaterial.entrySet()) {
            Long ownerId = requirementOwnerId(outboundOrder.getWorkOrderId(), request.getKey());
            List<MfgWorkOrderMaterial> requirements = requirementsByOwner.computeIfAbsent(ownerId,
                    id -> lockWorkOrderRequirements(id, outboundOrder));
            updates.addAll(allocateRequest(request, requirements));
        }
        return updates;
    }

    /** 领料单必须关联工单，并锁定该工单的有效用料需求。 */
    private List<MfgWorkOrderMaterial> lockWorkOrderRequirements(WmsOutboundOrder outboundOrder) {
        if (outboundOrder.getWorkOrderId() == null) {
            throw business(ResultCode.PARAM_ERROR, "生产领料必须关联生产工单");
        }
        return lockWorkOrderRequirements(outboundOrder.getWorkOrderId(), outboundOrder);
    }

    /** 锁定指定工单的有效用料需求。 */
    private List<MfgWorkOrderMaterial> lockWorkOrderRequirements(Long workOrderId, WmsOutboundOrder outboundOrder) {
        if (workOrderId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产领料必须关联生产工单");
        }
        List<MfgWorkOrderMaterial> requirements = workOrderMaterialMapper.selectList(
                new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .eq(MfgWorkOrderMaterial::getWorkOrderId, workOrderId)
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0)
                        .orderByAsc(MfgWorkOrderMaterial::getRequirementId)
                        .last("FOR UPDATE"));
        if (requirements.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单没有有效用料需求，不能创建或确认领料");
        }
        return requirements;
    }

    /** 按物料汇总本次领料数量。 */
    private Map<Long, BigDecimal> collectRequestedByMaterial(List<WmsOutboundDetail> details) {
        Map<Long, BigDecimal> requestedByMaterial = new LinkedHashMap<>();
        for (WmsOutboundDetail detail : details == null ? List.<WmsOutboundDetail>of() : details) {
            if (detail.getMaterialId() == null || detail.getOutboundQuantity() == null
                    || detail.getOutboundQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw business(ResultCode.PARAM_ERROR, "生产领料明细必须包含物料和正数数量");
            }
            requestedByMaterial.merge(detail.getMaterialId(), detail.getOutboundQuantity(), BigDecimal::add);
        }
        return requestedByMaterial;
    }

    /** 把一种物料的本次领料数量按用料需求顺序分配到各条需求上。 */
    private List<MaterialIssueUpdate> allocateRequest(Map.Entry<Long, BigDecimal> request,
                                                      List<MfgWorkOrderMaterial> requirements) {
        List<MaterialIssueUpdate> updates = new java.util.ArrayList<>();
        BigDecimal remaining = request.getValue();
        for (MfgWorkOrderMaterial requirement : requirements) {
            if (!Objects.equals(requirement.getMaterialId(), request.getKey()) || remaining.signum() <= 0) {
                continue;
            }
            BigDecimal required = zero(requirement.getRequiredQuantity());
            BigDecimal issued = zero(requirement.getIssuedQuantity());
            BigDecimal outstanding = required.subtract(issued).max(BigDecimal.ZERO);
            BigDecimal applied = remaining.min(outstanding);
            if (applied.signum() > 0) {
                BigDecimal newIssued = issued.add(applied);
                BigDecimal shortage = required.subtract(newIssued).max(BigDecimal.ZERO);
                updates.add(new MaterialIssueUpdate(requirement, newIssued, shortage,
                        readinessStatus(shortage, newIssued)));
                remaining = remaining.subtract(applied);
            }
        }
        if (remaining.signum() > 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "物料 " + request.getKey() + " 的本次领料数量超过工单尚未领用数量，超出 "
                            + remaining.stripTrailingZeros().toPlainString());
        }
        return updates;
    }

    /** 按缺口与已领数量推导用料需求就绪状态。 */
    private static String readinessStatus(BigDecimal shortage, BigDecimal newIssued) {
        if (shortage.signum() == 0) {
            return "READY";
        }
        return newIssued.signum() > 0 ? "PARTIAL_READY" : "WAIT_READY";
    }

    private void applyMaterialIssueUpdates(List<MaterialIssueUpdate> updates, Long operatorId, LocalDateTime now) {
        for (MaterialIssueUpdate issue : updates) {
            MfgWorkOrderMaterial update = new MfgWorkOrderMaterial();
            update.setRequirementId(issue.requirement().getRequirementId());
            update.setVersion(issue.requirement().getVersion());
            update.setIssuedQuantity(issue.issuedQuantity());
            update.setShortageQuantity(issue.shortageQuantity());
            update.setStatus(issue.status());
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (workOrderMaterialMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "工单用料齐套状态已变化，请刷新后重试");
            }
        }
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 根据仓库、库位、物料和批次查询未删除库存余额。
     * @param warehouseId 仓库ID
     * @param locationId 库位ID
     * @param materialId 物料ID
     * @param batchId 批次ID
     * @return 库存余额实体
     */
    /**
     * 解析出库明细实际扣减的仓库：以明细库位所属仓库为准，避免同一张单跨原料仓和半成品仓时扣错账。
     * 明细没有库位时退回单头仓库。
     * @param outboundOrder 出库单实体
     * @param detail 出库明细实体
     * @return 仓库ID
     */
    private Long resolveDetailWarehouseId(WmsOutboundOrder outboundOrder, WmsOutboundDetail detail) {
        if (detail != null && detail.getLocationId() != null) {
            com.neuedu.ems.entity.wms.WmsLocation location = locationMapper.selectById(detail.getLocationId());            if (location != null && location.getWarehouseId() != null) {
                return location.getWarehouseId();
            }
        }
        return outboundOrder == null ? null : outboundOrder.getWarehouseId();
    }

    /**
     * 根据仓库、库位、物料、批次查询公共库存余额（线边按工单占用的行不参与公共扣减）。
     * @param warehouseId 仓库ID
     * @param locationId 库位ID
     * @param materialId 物料ID
     * @param batchId 批次ID
     * @return 库存余额实体
     */
    private WmsInventory selectInventory(Long warehouseId, Long locationId, Long materialId, Long batchId) {
        LambdaQueryWrapper<WmsInventory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsInventory::getWarehouseId, warehouseId)
                .eq(WmsInventory::getLocationId, locationId)
                .eq(WmsInventory::getMaterialId, materialId)
                .eq(WmsInventory::getBatchId, batchId)
                // 线边仓同一批物料会按工单各占一行，公共出库只能扣 work_order_id = 0 的那一行。
                .eq(WmsInventory::getWorkOrderId, 0L)
                .eq(WmsInventory::getDeletedFlag, 0);
        return wmsInventoryMapper.selectOne(queryWrapper);
    }

    private void notifyAvailableChanged(Long materialId) {
        if (inventoryAutomationTrigger != null) {
            inventoryAutomationTrigger.afterChange(materialId);
        }
    }

    /**
     * 根据出库明细扣减库存。
     *
     * <p>已被预留的数量在预留时就从 available 挪到了 locked：确认出库时只把 locked 转出去，
     * 未预留的部分才按旧口径扣 available。重复扣减会把同一份库存算成两次消耗，
     * 也会让「半成品仓整份预留后直接确认出库」因 available 为 0 被误判库存不足。</p>
     *
     * @param outboundOrder 出库单实体，用于定位预留所属工单
     * @param deduction 库存扣减计划
     * @param operatorId 操作人ID
     * @param now 业务时间
     */
    private void decreaseInventory(WmsOutboundOrder outboundOrder, InventoryDeduction deduction,
                                   Long operatorId, LocalDateTime now) {
        WmsInventory inventory = deduction.inventory();
        BigDecimal reservedPart = consumeReservation(outboundOrder, inventory, deduction.quantity(), operatorId, now);
        BigDecimal unreservedPart = deduction.quantity().subtract(reservedPart);
        BigDecimal availableQuantity = inventory.getAvailableQuantity() == null ? BigDecimal.ZERO : inventory.getAvailableQuantity();
        WmsInventory update = new WmsInventory();
        update.setInventoryId(inventory.getInventoryId());
        update.setVersion(inventory.getVersion());
        update.setAvailableQuantity(availableQuantity.subtract(unreservedPart));
        if (reservedPart.signum() > 0) {
            BigDecimal lockedQuantity = inventory.getLockedQuantity() == null ? BigDecimal.ZERO : inventory.getLockedQuantity();
            update.setLockedQuantity(lockedQuantity.subtract(reservedPart));
        }
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (wmsInventoryMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "库存余额已被其他请求修改，请刷新后重试");
        }
        notifyAvailableChanged(inventory.getMaterialId());
    }

    /**
     * 确认出库时把本工单在该余额行上的预留转为已消耗，返回本次由预留承担的数量。
     *
     * <p>只有生产领料单才可能有预留；其它出库类型一律按公共可用量处理。</p>
     */
    private BigDecimal consumeReservation(WmsOutboundOrder outboundOrder, WmsInventory inventory,
                                          BigDecimal quantity, Long operatorId, LocalDateTime now) {
        if (outboundOrder == null || !MATERIAL_ISSUE_TYPE.equals(outboundOrder.getOutboundType())
                || outboundOrder.getWorkOrderId() == null || inventory == null
                || inventory.getInventoryId() == null) {
            return BigDecimal.ZERO;
        }
        // 预留记在需求归属工单上：组件工单领半成品，预留属于上级整机工单。
        Long ownerId = requirementOwnerId(outboundOrder.getWorkOrderId(), inventory.getMaterialId());
        BigDecimal consumed = supplyAllocations.consumeReservation(ownerId,
                inventory.getMaterialId(), inventory.getInventoryId(), quantity, operatorId, now);
        return consumed == null ? BigDecimal.ZERO : consumed;
    }

    /**
     * 统计本工单在该余额行上仍处于预留状态的数量。
     *
     * <p>预留把数量从 available 挪到 locked，可用量校验必须把两者合起来看，
     * 否则整份预留过的半成品仓库存会被判成「库存不足」。</p>
     */
    private BigDecimal reservedQuantity(WmsOutboundOrder outboundOrder, InventoryKey key, WmsInventory inventory) {
        if (outboundOrder == null || !MATERIAL_ISSUE_TYPE.equals(outboundOrder.getOutboundType())
                || outboundOrder.getWorkOrderId() == null || inventory == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal reserved = BigDecimal.ZERO;
        Long ownerId = requirementOwnerId(outboundOrder.getWorkOrderId(), key.materialId());
        for (com.neuedu.ems.entity.mfg.MfgSupplyAllocation allocation
                : supplyAllocations.reservedAllocationsForWorkOrder(ownerId)) {
            boolean sameRow = java.util.Objects.equals(allocation.getInventoryId(), inventory.getInventoryId())
                    && java.util.Objects.equals(allocation.getMaterialId(), key.materialId());
            if (sameRow && allocation.getReservedQuantity() != null) {
                reserved = reserved.add(allocation.getReservedQuantity());
            }
        }
        return reserved;
    }

    /**
     * 写入出库库存流水。
     * @param outboundOrder 出库单实体
     * @param detail 出库明细实体
     * @param operatorId 操作人ID
     * @param remark 备注
     * @param operationTime 操作时间
     */
    private void insertOutboundMovement(WmsOutboundOrder outboundOrder, WmsOutboundDetail detail,
                                        Long operatorId, String remark, LocalDateTime operationTime) {
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo("WMS-OUT-" + outboundOrder.getOutboundId() + "-" + detail.getDetailId());
        movement.setMovementType("OUTBOUND");
        movement.setSourceOrderType("OUTBOUND_ORDER");
        movement.setSourceOrderId(outboundOrder.getOutboundId());
        movement.setMaterialId(detail.getMaterialId());
        movement.setBatchId(detail.getBatchId());
        // 记录发生仓库与库位：出库流水要能独立回答“从哪个仓库哪个库位出的”，
        // 否则追溯页只能看到单头仓库，跨仓单据（原料仓+半成品仓）无法核对。
        movement.setWarehouseId(resolveDetailWarehouseId(outboundOrder, detail));
        movement.setLocationId(detail.getLocationId());
        movement.setWorkOrderId(outboundOrder.getWorkOrderId() == null ? 0L : outboundOrder.getWorkOrderId());
        movement.setQuantity(detail.getOutboundQuantity());
        movement.setDirection("OUT");
        movement.setOperationTime(operationTime);
        movement.setCreatedBy(operatorId);
        movement.setRemark(remark);
        movement.setDeletedFlag(0);
        if (wmsStockMovementMapper.insert(movement) == 0) {
            throw business(ResultCode.FAILED, "出库库存流水写入失败");
        }
    }

    /**
     * 写入状态变更日志。
     * @param bizId 业务ID
     * @param oldStatus 原状态
     * @param newStatus 新状态
     * @param bizType 业务类型
     * @param reason 变更原因
     * @param operatorId 操作人ID
     * @param remark 备注
     * @param changedTime 变更时间
     */
    private void insertStatusChangeLog(Long bizId, String oldStatus, String newStatus, String bizType,
                                       String reason, Long operatorId, String remark, LocalDateTime changedTime) {
        SysStatusChangeLog statusLog = new SysStatusChangeLog();
        statusLog.setBizType(bizType);
        statusLog.setBizId(bizId);
        statusLog.setOldStatus(oldStatus);
        statusLog.setNewStatus(newStatus);
        statusLog.setChangeReason(reason);
        statusLog.setChangedBy(operatorId);
        statusLog.setChangedTime(changedTime);
        statusLog.setCreatedBy(operatorId);
        statusLog.setCreatedTime(changedTime);
        statusLog.setRemark(remark);
        statusLog.setDeletedFlag(0);
        if (sysStatusChangeLogMapper.insert(statusLog) == 0) {
            throw business(ResultCode.FAILED, "出库状态日志写入失败");
        }
    }

    /**
     * 写入操作日志。
     * @param bizId 业务ID
     * @param operationType 操作类型
     * @param moduleCode 模块编码
     * @param operatorId 操作人ID
     * @param remark 备注
     * @param operationTime 操作时间
     */
    private void insertOperationLog(Long bizId, String operationType, String moduleCode,
                                    Long operatorId, String remark, LocalDateTime operationTime) {
        SysOperationLog operationLog = new SysOperationLog();
        operationLog.setUserId(operatorId);
        operationLog.setOperationType(operationType);
        operationLog.setModuleCode(moduleCode);
        operationLog.setBizId(bizId);
        operationLog.setOperationTime(operationTime);
        operationLog.setCreatedBy(operatorId);
        operationLog.setCreatedTime(operationTime);
        operationLog.setRemark(remark);
        operationLog.setDeletedFlag(0);
        if (sysOperationLogMapper.insert(operationLog) == 0) {
            throw business(ResultCode.FAILED, "出库操作日志写入失败");
        }
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }

    private record InventoryKey(Long warehouseId, Long locationId, Long materialId, Long batchId) {
    }

    private record InventoryDeduction(WmsInventory inventory, BigDecimal quantity) {
    }

    private record MaterialIssueUpdate(MfgWorkOrderMaterial requirement, BigDecimal issuedQuantity,
                                       BigDecimal shortageQuantity, String status) {
    }

    private record MaterialIssueSource(WmsInventory inventory, WmsMaterialBatch batch) {
    }
}
