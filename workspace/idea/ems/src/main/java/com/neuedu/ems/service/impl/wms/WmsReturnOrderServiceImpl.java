package com.neuedu.ems.service.impl.wms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.entity.mfg.MfgReportMaterial;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mdm.MdmUnit;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsOutboundDetail;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsReturnDetail;
import com.neuedu.ems.entity.wms.WmsReturnOrder;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmUnitMapper;
import com.neuedu.ems.mapper.mfg.MfgReportMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsLocationMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsReturnDetailMapper;
import com.neuedu.ems.mapper.wms.WmsReturnOrderMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.impl.ai.InventoryAutomationTrigger;
import com.neuedu.ems.service.wms.WmsReturnOrderService;
import com.neuedu.ems.vo.wms.WmsReturnableDetailView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 退料单管理 Service 实现类。
 */
@Service
public class WmsReturnOrderServiceImpl extends ServiceImpl<WmsReturnOrderMapper, WmsReturnOrder> implements WmsReturnOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;

    @Autowired(required = false)
    private InventoryAutomationTrigger inventoryAutomationTrigger;
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.mdm.MaterialExecutionQuantityService executionQuantities;


    private static final String SUBMITTED_STATUS = BusinessStatus.Document.SUBMITTED;
    private static final String APPROVED_STATUS = BusinessStatus.Document.APPROVED;
    private static final String CLOSED_STATUS = BusinessStatus.Document.CLOSED;
    private static final String MATERIAL_ISSUE_TYPE = "MATERIAL_ISSUE";
    private static final String LINE_SIDE_WAREHOUSE_TYPE = "LINE_SIDE";
    private static final String TRANSFER_MOVEMENT_TYPE = "TRANSFER";
    private static final String OUTBOUND_SOURCE_TYPE = "OUTBOUND_ORDER";
    private static final String RETURN_SOURCE_TYPE = "RETURN_ORDER";
    private static final String NORMAL_LOCATION_TYPE = "NORMAL";
    /** 库位可用状态：IDLE 是日常未占用，OCCUPIED 表示有货在用，ENABLE 为旧口径。 */
    private static final List<String> USABLE_LOCATION_STATUSES = List.of("IDLE", "OCCUPIED", "ENABLE");
    private static final Set<String> MATERIAL_RETURN_TYPES = Set.of("GOOD", "DEFECTIVE");
    private static final DateTimeFormatter RETURN_NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Autowired
    private WmsReturnDetailMapper wmsReturnDetailMapper;

    @Autowired
    private WmsInventoryMapper wmsInventoryMapper;

    @Autowired
    private MfgReportMaterialMapper mfgReportMaterialMapper;

    @Autowired
    private MfgWorkOrderMapper mfgWorkOrderMapper;

    @Autowired
    private WmsOutboundDetailMapper wmsOutboundDetailMapper;

    @Autowired
    private WmsOutboundOrderMapper wmsOutboundOrderMapper;

    @Autowired
    private WmsStockMovementMapper wmsStockMovementMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private WmsResponsibilityNotificationService responsibilityNotificationService;

    @Autowired
    private WmsLocationMapper wmsLocationMapper;

    @Autowired
    private WmsWarehouseMapper wmsWarehouseMapper;

    @Autowired
    private WmsMaterialBatchMapper wmsMaterialBatchMapper;

    @Autowired
    private MdmMaterialMapper mdmMaterialMapper;

    @Autowired
    private MdmUnitMapper mdmUnitMapper;

    @Autowired
    private MfgWorkOrderMaterialMapper mfgWorkOrderMaterialMapper;

    /**
     * 派工单齐套刷新。用 ObjectProvider 延迟取，避免与 mfg 域形成构造期循环依赖。
     */
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<com.neuedu.ems.service.mfg.MfgDispatchOrderService> mfgDispatchOrders;

    /**
     * 分页查询退料单，支持单号、类型、工单、申请人和审核状态筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 关键字，匹配退料单号
     * @param returnType 退料类型
     * @param workOrderId 工单ID
     * @param applicantId 申请人ID
     * @param auditStatus 审核状态
     * @return 退料单分页结果
     */
    @Override
    public Page<WmsReturnOrder> pageReturnOrders(Integer current, Integer size, String keyword, String returnType,
                                                 Long workOrderId, Long applicantId, String auditStatus) {
        Page<WmsReturnOrder> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildReturnOrderQuery(keyword, returnType, workOrderId, applicantId, auditStatus));
    }

    /**
     * 查询退料单列表，支持单号、类型、工单、申请人和审核状态筛选。
     * @param keyword 关键字，匹配退料单号
     * @param returnType 退料类型
     * @param workOrderId 工单ID
     * @param applicantId 申请人ID
     * @param auditStatus 审核状态
     * @return 退料单列表
     */
    @Override
    public List<WmsReturnOrder> listReturnOrders(String keyword, String returnType, Long workOrderId, Long applicantId, String auditStatus) {
        return baseMapper.selectList(buildReturnOrderQuery(keyword, returnType, workOrderId, applicantId, auditStatus));
    }

    /**
     * 根据退料单ID查询未删除的退料单详情。
     * @param returnId 退料单ID
     * @return 退料单实体
     */
    @Override
    public WmsReturnOrder getReturnOrderById(Long returnId) {
        LambdaQueryWrapper<WmsReturnOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsReturnOrder::getReturnId, returnId)
                .eq(WmsReturnOrder::getDeletedFlag, 0);
        return baseMapper.selectOne(queryWrapper);
    }

    /**
     * 列出指定工单当前可退的来源领料明细。
     *
     * <p>可退量算法与保存退料单时的校验完全一致：已领（已确认生产领料单明细）减已消耗
     * （有效报工用料的使用量与报废量）减已退（待确认或已确认的退料明细）。这里只做只读汇总，
     * 不建单、不预占库存，界面列出的数量就是后端允许提交的上限。</p>
     * @param workOrderId 生产工单ID
     * @return 可退明细清单，没有可退项时返回空列表
     */
    @Override
    public List<WmsReturnableDetailView> listReturnableDetails(Long workOrderId) {
        return listReturnableDetails(workOrderId, null);
    }

    @Override
    public List<WmsReturnableDetailView> listReturnableDetails(Long workOrderId, Long excludedReturnId) {
        if (workOrderId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产工单不能为空");
        }
        List<WmsOutboundDetail> sources = listIssueDetails(workOrderId);
        if (sources.isEmpty()) {
            return List.of();
        }
        Map<Long, WmsOutboundOrder> orders = indexOrders(sources);
        Map<ReturnMaterialKey, BigDecimal> batchAvailable =
                computeBatchAvailability(workOrderId, sources, excludedReturnId);
        Map<Long, MdmMaterial> materials = indexMaterials(sources);
        Map<Long, MdmUnit> units = indexUnits(materials.values());
        Map<Long, WmsMaterialBatch> batches = indexBatches(sources);
        Map<Long, WmsLocation> locations = indexLocations(sources);
        Map<Long, WmsWarehouse> warehouses = indexWarehouses(orders.values());
        List<WmsReturnableDetailView> rows = new ArrayList<>();
        for (WmsOutboundDetail source : sources) {
            WmsReturnableDetailView row = newReturnableRow(source, orders.get(source.getOutboundId()),
                    excludedReturnId);
            fillReturnableNames(row, materials, units, batches, locations, warehouses);
            rows.add(row);
        }
        distributeAvailability(rows, batchAvailable);
        return rows;
    }

    /** 取该工单全部已确认生产领料单的来源明细，按明细ID升序保证界面顺序稳定。 */
    private List<WmsOutboundDetail> listIssueDetails(Long workOrderId) {
        List<WmsOutboundOrder> issueOrders = wmsOutboundOrderMapper.selectList(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getWorkOrderId, workOrderId)
                .eq(WmsOutboundOrder::getOutboundType, MATERIAL_ISSUE_TYPE)
                .eq(WmsOutboundOrder::getAuditStatus, APPROVED_STATUS)
                .eq(WmsOutboundOrder::getDeletedFlag, 0));
        if (issueOrders.isEmpty()) {
            return List.of();
        }
        List<Long> outboundIds = issueOrders.stream().map(WmsOutboundOrder::getOutboundId).toList();
        List<WmsOutboundDetail> details = wmsOutboundDetailMapper.selectList(new LambdaQueryWrapper<WmsOutboundDetail>()
                .in(WmsOutboundDetail::getOutboundId, outboundIds)
                .eq(WmsOutboundDetail::getDeletedFlag, 0)
                .orderByAsc(WmsOutboundDetail::getDetailId));
        return details.stream()
                .filter(item -> item.getMaterialId() != null && item.getBatchId() != null
                        && item.getLocationId() != null && isPositive(item.getOutboundQuantity()))
                .toList();
    }

    /** 按出库单ID索引领料单，供逐行取单号与仓库。 */
    private Map<Long, WmsOutboundOrder> indexOrders(List<WmsOutboundDetail> sources) {
        List<Long> outboundIds = sources.stream().map(WmsOutboundDetail::getOutboundId).distinct().toList();
        return wmsOutboundOrderMapper.selectList(new LambdaQueryWrapper<WmsOutboundOrder>()
                        .in(WmsOutboundOrder::getOutboundId, outboundIds)
                        .eq(WmsOutboundOrder::getDeletedFlag, 0))
                .stream().collect(java.util.stream.Collectors.toMap(
                        WmsOutboundOrder::getOutboundId, item -> item, (left, right) -> left));
    }

    /**
     * 按物料批次汇总本工单还可退的数量：已领减已消耗减已退。
     * 与 {@link #validateAggregateReturnAvailability} 使用同一口径，避免界面给出后端不接受的数字。
     */
    private Map<ReturnMaterialKey, BigDecimal> computeBatchAvailability(Long workOrderId,
                                                                        List<WmsOutboundDetail> sources,
                                                                        Long excludedReturnId) {
        Map<ReturnMaterialKey, BigDecimal> issuedByBatch = new LinkedHashMap<>();
        for (WmsOutboundDetail source : sources) {
            ReturnMaterialKey key = new ReturnMaterialKey(source.getMaterialId(), source.getBatchId());
            issuedByBatch.merge(key, source.getOutboundQuantity(), BigDecimal::add);
        }
        Map<ReturnMaterialKey, BigDecimal> available = new LinkedHashMap<>();
        for (Map.Entry<ReturnMaterialKey, BigDecimal> entry : issuedByBatch.entrySet()) {
            ReturnMaterialKey key = entry.getKey();
            BigDecimal consumed = zero(mfgReportMaterialMapper.selectList(new LambdaQueryWrapper<MfgReportMaterial>()
                            .eq(MfgReportMaterial::getMaterialId, key.materialId())
                            .eq(MfgReportMaterial::getBatchId, key.batchId())
                            .eq(MfgReportMaterial::getDeletedFlag, 0)
                            .inSql(MfgReportMaterial::getReportId,
                                    "SELECT report.report_id FROM mfg_work_report report "
                                            + "JOIN mfg_task task ON task.task_id = report.task_id "
                                            + "WHERE report.deleted_flag = 0 AND task.deleted_flag = 0 "
                                            + "AND report.audit_status IN ('SUBMITTED','APPROVED') "
                                            + "AND task.work_order_id = " + workOrderId))
                    .stream().map(item -> zero(item.getUsedQuantity()).add(zero(item.getScrapQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal returned = zero(wmsReturnDetailMapper.sumActiveReturnedQuantityByBatch(
                    workOrderId, key.materialId(), key.batchId(), excludedReturnId));
            available.put(key, entry.getValue().subtract(consumed).subtract(returned).max(BigDecimal.ZERO));
        }
        return available;
    }

    /** 建一行可退明细，先填来源明细自身字段。 */
    private WmsReturnableDetailView newReturnableRow(WmsOutboundDetail source, WmsOutboundOrder order,
                                                     Long excludedReturnId) {
        WmsReturnableDetailView row = new WmsReturnableDetailView();
        row.setOutboundDetailId(source.getDetailId());
        row.setOutboundNo(order == null ? null : order.getOutboundNo());
        row.setWarehouseId(order == null ? null : order.getWarehouseId());
        row.setMaterialId(source.getMaterialId());
        row.setBatchId(source.getBatchId());
        row.setLocationId(source.getLocationId());
        row.setOutboundQuantity(source.getOutboundQuantity());
        row.setReturnedQuantity(zero(wmsReturnDetailMapper.sumActiveReturnedQuantity(
                source.getDetailId(), excludedReturnId)));
        row.setSourceAvailableQuantity(
                source.getOutboundQuantity().subtract(row.getReturnedQuantity()).max(BigDecimal.ZERO));
        row.setLineSide(order != null && lineSideIssued(order.getOutboundId()));
        return row;
    }

    /** 补齐物料、单位、批次、库位与仓库的名称，界面不用再逐条查基础资料。 */
    private void fillReturnableNames(WmsReturnableDetailView row, Map<Long, MdmMaterial> materials,
                                     Map<Long, MdmUnit> units, Map<Long, WmsMaterialBatch> batches,
                                     Map<Long, WmsLocation> locations, Map<Long, WmsWarehouse> warehouses) {
        MdmMaterial material = materials.get(row.getMaterialId());
        if (material != null) {
            row.setMaterialCode(material.getMaterialCode());
            row.setMaterialName(material.getMaterialName());
            row.setSpecModel(material.getSpecModel());
            row.setUnitId(material.getUnitId());
            MdmUnit unit = material.getUnitId() == null ? null : units.get(material.getUnitId());
            row.setUnitName(unit == null ? null : unit.getUnitName());
        }
        WmsMaterialBatch batch = batches.get(row.getBatchId());
        row.setBatchNo(batch == null ? null : batch.getBatchNo());
        WmsLocation location = locations.get(row.getLocationId());
        row.setLocationName(location == null ? null : location.getLocationName());
        WmsWarehouse warehouse = row.getWarehouseId() == null ? null : warehouses.get(row.getWarehouseId());
        row.setWarehouseName(warehouse == null ? null : warehouse.getWarehouseName());
    }

    /**
     * 把批次级可退量分配到逐条来源明细上。
     *
     * <p>同一物料批次可能来自多张领料单，批次可退量必须整体封顶，否则界面会给出「每条都够、
     * 合计超出」的数字。按明细ID顺序依次分配，先到先得。</p>
     */
    private void distributeAvailability(List<WmsReturnableDetailView> rows,
                                        Map<ReturnMaterialKey, BigDecimal> batchAvailable) {
        Map<ReturnMaterialKey, BigDecimal> remaining = new LinkedHashMap<>(batchAvailable);
        for (WmsReturnableDetailView row : rows) {
            ReturnMaterialKey key = new ReturnMaterialKey(row.getMaterialId(), row.getBatchId());
            BigDecimal left = remaining.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal candidate = row.getSourceAvailableQuantity().min(left).max(BigDecimal.ZERO);
            row.setAvailableQuantity(candidate);
            row.setBatchAvailableQuantity(batchAvailable.getOrDefault(key, BigDecimal.ZERO));
            remaining.put(key, left.subtract(candidate).max(BigDecimal.ZERO));
        }
    }

    private Map<Long, MdmMaterial> indexMaterials(List<WmsOutboundDetail> sources) {
        List<Long> ids = sources.stream().map(WmsOutboundDetail::getMaterialId).distinct().toList();
        return mdmMaterialMapper.selectList(new LambdaQueryWrapper<MdmMaterial>()
                        .in(MdmMaterial::getMaterialId, ids)
                        .eq(MdmMaterial::getDeletedFlag, 0))
                .stream().collect(java.util.stream.Collectors.toMap(
                        MdmMaterial::getMaterialId, item -> item, (left, right) -> left));
    }

    private Map<Long, MdmUnit> indexUnits(java.util.Collection<MdmMaterial> materials) {
        List<Long> ids = materials.stream().map(MdmMaterial::getUnitId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mdmUnitMapper.selectList(new LambdaQueryWrapper<MdmUnit>()
                        .in(MdmUnit::getUnitId, ids)
                        .eq(MdmUnit::getDeletedFlag, 0))
                .stream().collect(java.util.stream.Collectors.toMap(
                        MdmUnit::getUnitId, item -> item, (left, right) -> left));
    }

    private Map<Long, WmsMaterialBatch> indexBatches(List<WmsOutboundDetail> sources) {
        List<Long> ids = sources.stream().map(WmsOutboundDetail::getBatchId).distinct().toList();
        return wmsMaterialBatchMapper.selectList(new LambdaQueryWrapper<WmsMaterialBatch>()
                        .in(WmsMaterialBatch::getBatchId, ids)
                        .eq(WmsMaterialBatch::getDeletedFlag, 0))
                .stream().collect(java.util.stream.Collectors.toMap(
                        WmsMaterialBatch::getBatchId, item -> item, (left, right) -> left));
    }

    private Map<Long, WmsLocation> indexLocations(List<WmsOutboundDetail> sources) {
        List<Long> ids = sources.stream().map(WmsOutboundDetail::getLocationId).distinct().toList();
        return wmsLocationMapper.selectList(new LambdaQueryWrapper<WmsLocation>()
                        .in(WmsLocation::getLocationId, ids)
                        .eq(WmsLocation::getDeletedFlag, 0))
                .stream().collect(java.util.stream.Collectors.toMap(
                        WmsLocation::getLocationId, item -> item, (left, right) -> left));
    }

    private Map<Long, WmsWarehouse> indexWarehouses(java.util.Collection<WmsOutboundOrder> orders) {
        List<Long> ids = orders.stream().map(WmsOutboundOrder::getWarehouseId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return wmsWarehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                        .in(WmsWarehouse::getWarehouseId, ids)
                        .eq(WmsWarehouse::getDeletedFlag, 0))
                .stream().collect(java.util.stream.Collectors.toMap(
                        WmsWarehouse::getWarehouseId, item -> item, (left, right) -> left));
    }

    /**
     * 新增退料单并保存退料明细。
     * @param returnOrder 退料单实体
     * @param details 退料明细列表
     * @param operatorId 当前登录用户ID
     * @return 退料单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveReturnOrderFull(WmsReturnOrder returnOrder, List<WmsReturnDetail> details, Long operatorId) {
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "当前登录用户不能为空");
        }
        validateReturnHeader(returnOrder);
        List<ReturnLine> returnLines = prepareReturnLines(returnOrder, details, null);
        LocalDateTime now = LocalDateTime.now();
        returnOrder.setReturnId(null);
        returnOrder.setReturnNo(numbers.nextFor("RT","WORK_ORDER",returnOrder.getWorkOrderId()));
        returnOrder.setApplicantId(operatorId);
        returnOrder.setAuditStatus(SUBMITTED_STATUS);
        returnOrder.setReturnTime(null);
        returnOrder.setVersion(0);
        returnOrder.setCreatedBy(operatorId);
        returnOrder.setCreatedTime(now);
        returnOrder.setUpdatedBy(operatorId);
        returnOrder.setUpdatedTime(now);
        returnOrder.setDeletedFlag(0);
        if (baseMapper.insert(returnOrder) == 0 || returnOrder.getReturnId() == null) {
            throw business(ResultCode.FAILED, "退料单创建失败");
        }
        for (ReturnLine line : returnLines) {
            WmsReturnDetail detail = line.detail();
            detail.setDetailId(null);
            detail.setReturnId(returnOrder.getReturnId());
            detail.setVersion(0);
            detail.setCreatedBy(operatorId);
            detail.setCreatedTime(now);
            detail.setUpdatedBy(operatorId);
            detail.setUpdatedTime(now);
            detail.setDeletedFlag(0);
            if (wmsReturnDetailMapper.insert(detail) == 0) {
                throw business(ResultCode.FAILED, "退料明细创建失败");
            }
        }
        responsibilityNotificationService.publishReturnPending(returnOrder, operatorId);
        return returnOrder.getReturnId();
    }

    /**
     * 更新退料单，已确认退料单不允许普通修改。
     * @param returnOrder 退料单实体
     * @param operatorId 当前登录用户ID
     * @return 是否成功
     */
    @Override
    public boolean updateReturnOrder(WmsReturnOrder returnOrder, Long operatorId) {
        if (returnOrder == null || returnOrder.getReturnId() == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "退料单和当前登录用户不能为空");
        }
        WmsReturnOrder existing = getReturnOrderById(returnOrder.getReturnId());
        if (existing == null || !SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待确认退料单可以修改");
        }
        returnOrder.setReturnNo(null);
        returnOrder.setReturnType(null);
        returnOrder.setWorkOrderId(null);
        returnOrder.setApplicantId(null);
        returnOrder.setDeletedFlag(null);
        returnOrder.setAuditStatus(null);
        returnOrder.setReturnTime(null);
        returnOrder.setCreatedBy(null);
        returnOrder.setCreatedTime(null);
        returnOrder.setUpdatedBy(operatorId);
        returnOrder.setUpdatedTime(LocalDateTime.now());
        if (returnOrder.getVersion() == null) {
            returnOrder.setVersion(existing.getVersion());
        }
        if (baseMapper.updateById(returnOrder) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "退料单已被其他用户修改，请刷新后重试");
        }
        return true;
    }

    /**
     * 更新退料单并替换退料明细，已确认退料单不允许替换明细。
     * @param returnOrder 退料单实体
     * @param details 退料明细列表
     * @param operatorId 当前登录用户ID
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateReturnOrderFull(WmsReturnOrder returnOrder, List<WmsReturnDetail> details, Long operatorId) {
        if (returnOrder == null || returnOrder.getReturnId() == null) {
            throw business(ResultCode.PARAM_ERROR, "退料单不能为空");
        }
        WmsReturnOrder existing = getReturnOrderById(returnOrder.getReturnId());
        if (existing == null || !SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待确认退料单可以修改");
        }
        List<ReturnLine> returnLines = prepareReturnLines(existing, details, existing.getReturnId());
        returnOrder.setVersion(existing.getVersion());
        updateReturnOrder(returnOrder, operatorId);
        softDeleteDetails(existing.getReturnId());
        LocalDateTime now = LocalDateTime.now();
        for (ReturnLine line : returnLines) {
            WmsReturnDetail detail = line.detail();
            detail.setDetailId(null);
            detail.setReturnId(existing.getReturnId());
            detail.setVersion(0);
            detail.setCreatedBy(operatorId);
            detail.setCreatedTime(now);
            detail.setUpdatedBy(operatorId);
            detail.setUpdatedTime(now);
            detail.setDeletedFlag(0);
            if (wmsReturnDetailMapper.insert(detail) == 0) {
                throw business(ResultCode.FAILED, "退料明细更新失败");
            }
        }
        return true;
    }

    /**
     * 根据ID软删除退料单和明细，已确认退料单不允许删除。
     * @param returnId 退料单ID
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean removeReturnOrderById(Long returnId) {
        WmsReturnOrder existing = getReturnOrderById(returnId);
        if (existing == null || !SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            return false;
        }
        WmsReturnOrder returnOrder = new WmsReturnOrder();
        returnOrder.setReturnId(returnId);
        returnOrder.setDeletedFlag(1);
        returnOrder.setVersion(existing.getVersion());
        boolean orderRemoved = baseMapper.updateById(returnOrder) > 0;
        softDeleteDetails(returnId);
        if (orderRemoved) {
            responsibilityNotificationService.cancelReturnPending(returnId, existing.getUpdatedBy());
        }
        return orderRemoved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawReturnOrder(Long returnId, Long operatorId, String remark) {
        WmsReturnOrder existing = getReturnOrderById(returnId);
        if (existing == null || operatorId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "退料单和操作人不能为空");
        }
        remark = StringUtils.hasText(remark) ? remark.trim() : "用户主动撤回，未填写补充原因";
        if (!SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "只有未确认退料单可以撤回");
        }
        WmsReturnOrder update = new WmsReturnOrder();
        update.setReturnId(returnId); update.setVersion(existing.getVersion());
        update.setAuditStatus(BusinessStatus.Document.CLOSED); update.setUpdatedBy(operatorId); update.setUpdatedTime(LocalDateTime.now());
        if (baseMapper.updateById(update) == 0) throw new BusinessException(ResultCode.DATA_CONFLICT);
        LocalDateTime now = LocalDateTime.now();
        insertStatusChangeLog(returnId, existing.getAuditStatus(), BusinessStatus.Document.CLOSED,
                "WMS_RETURN_ORDER", "WITHDRAW_RETURN", operatorId, remark, now);
        insertOperationLog(returnId, "WITHDRAW_RETURN", "wms_return_order", operatorId, remark, now);
        responsibilityNotificationService.cancelReturnPending(returnId, operatorId);
    }

    /**
     * 确认退料入库，回加库存并写入库存流水。
     * @param returnId 退料单ID
     * @param operatorId 操作人ID
     * @param remark 备注
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmReturnOrder(Long returnId, Long operatorId, String remark) {
        if (returnId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "退料单和当前登录用户不能为空");
        }
        WmsReturnOrder returnOrder = getReturnOrderForUpdate(returnId);
        if (returnOrder == null) {
            throw business(ResultCode.NOT_FOUND, "退料单不存在或已删除");
        }
        List<WmsReturnDetail> details = listActiveDetails(returnId);
        if (APPROVED_STATUS.equals(returnOrder.getAuditStatus())) {
            validateConfirmedReturnMovements(returnId, details);
        } else {
            confirmPendingReturn(returnOrder, details, operatorId, remark);
        }
        return true;
    }

    private void validateConfirmedReturnMovements(Long returnId, List<WmsReturnDetail> details) {
        long movementCount = wmsStockMovementMapper.selectCount(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getSourceOrderType, RETURN_SOURCE_TYPE)
                .eq(WmsStockMovement::getSourceOrderId, returnId)
                .eq(WmsStockMovement::getDeletedFlag, 0));
        // 普通退料每条明细一条流水，线边退料每条明细两条配对流水。
        long expectedSingle = details.size();
        long expectedPaired = details.size() * 2L;
        boolean movementComplete = movementCount == expectedSingle || movementCount == expectedPaired;
        if (details.isEmpty() || !movementComplete) {
            throw business(ResultCode.DATA_CONFLICT, "退料单已确认但库存流水不完整，请联系管理员核对");
        }
    }

    private void confirmPendingReturn(WmsReturnOrder returnOrder, List<WmsReturnDetail> details,
                                      Long operatorId, String remark) {
        Long returnId = returnOrder.getReturnId();
        if (!SUBMITTED_STATUS.equals(returnOrder.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待确认退料单可以确认入库");
        }
        List<ReturnLine> returnLines = prepareReturnLines(returnOrder, details, returnId);
        Map<Long, InventoryAddition> additions = prepareInventoryAdditions(returnLines);
        Map<Long, LineSideDeduction> deductions = prepareLineSideDeductions(returnLines,
                returnOrder.getWorkOrderId());
        LocalDateTime now = LocalDateTime.now();
        WmsReturnOrder update = new WmsReturnOrder();
        update.setReturnId(returnId);
        update.setVersion(returnOrder.getVersion());
        update.setAuditStatus(APPROVED_STATUS);
        update.setReturnTime(now);
        update.setRemark(StringUtils.hasText(remark) ? remark : returnOrder.getRemark());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "退料单已被其他用户修改，请刷新后重试");
        }
        // 线边退料先释放工单在线边仓的占用，再把同一数量回加到原仓公共库存。
        for (LineSideDeduction deduction : deductions.values()) {
            decreaseLineSideLocked(deduction, operatorId, now);
        }
        for (InventoryAddition addition : additions.values()) {
            increaseInventory(addition, operatorId, now);
        }
        for (ReturnLine line : returnLines) {
            if (line.lineSide()) {
                insertLineSideReturnMovements(returnOrder, line, operatorId, remark, now);
            } else {
                insertReturnMovement(returnOrder, line, operatorId, remark, now);
            }
        }
        insertStatusChangeLog(returnOrder.getReturnId(), returnOrder.getAuditStatus(), APPROVED_STATUS,
                "WMS_RETURN_ORDER", "CONFIRM_RETURN", operatorId, remark, now);
        insertOperationLog(returnOrder.getReturnId(), "CONFIRM_RETURN", "wms_return_order", operatorId, remark, now);
        restoreIssuedQuantities(returnOrder, returnLines, operatorId, now);
        responsibilityNotificationService.cancelReturnPending(returnId, operatorId);
        responsibilityNotificationService.publishReturnCompleted(returnOrder, operatorId);
    }

    /**
     * 退料确认后回退工单的「有效领用量」。
     *
     * <p>领料确认会把数量累加到 {@code mfg_work_order_material.issued_quantity}，而
     * 组件补产替换、派工齐套判断都按这个字段推断「这条需求还缺多少」。退料只把实物退回
     * 原仓，如果不把领用量减回来，工单会一直显示为已领料，后续改用库存半成品时会被
     * 「已领料、开工或已有执行记录」挡住。所以这里按来源明细扣回领用量、重算缺口与就绪
     * 状态，并在整张领料单全部退回时把出库单置为 CLOSED。</p>
     *
     * @param returnOrder 已确认的退料单
     * @param lines 本次退料明细（含来源领料明细）
     * @param operatorId 操作人ID
     * @param now 操作时间
     */
    private void restoreIssuedQuantities(WmsReturnOrder returnOrder, List<ReturnLine> lines,
                                         Long operatorId, LocalDateTime now) {
        Long workOrderId = returnOrder.getWorkOrderId();
        if (workOrderId == null || lines.isEmpty()) {
            return;
        }
        List<MfgWorkOrderMaterial> requirements = mfgWorkOrderMaterialMapper.selectList(
                new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .eq(MfgWorkOrderMaterial::getWorkOrderId, workOrderId)
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0)
                        .orderByAsc(MfgWorkOrderMaterial::getRequirementId)
                        .last("FOR UPDATE"));
        if (requirements.isEmpty()) {
            return;
        }
        // 同一物料可能有多条需求行，按需求行顺序退回，避免把数量扣到不该扣的行上。
        Map<Long, BigDecimal> returnedByMaterial = new LinkedHashMap<>();
        for (ReturnLine line : lines) {
            Long materialId = line.sourceDetail().getMaterialId();
            if (materialId == null || !isPositive(line.detail().getReturnQuantity())) {
                continue;
            }
            returnedByMaterial.merge(materialId, line.detail().getReturnQuantity(), BigDecimal::add);
        }
        boolean touched = false;
        for (MfgWorkOrderMaterial requirement : requirements) {
            BigDecimal pending = returnedByMaterial.get(requirement.getMaterialId());
            if (pending == null || pending.signum() <= 0) {
                continue;
            }
            BigDecimal issued = zero(requirement.getIssuedQuantity());
            if (issued.signum() <= 0) {
                continue;
            }
            BigDecimal applied = pending.min(issued);
            BigDecimal newIssued = issued.subtract(applied);
            BigDecimal required = zero(requirement.getRequiredQuantity());
            BigDecimal shortage = required.subtract(newIssued).max(BigDecimal.ZERO);
            MfgWorkOrderMaterial update = new MfgWorkOrderMaterial();
            update.setRequirementId(requirement.getRequirementId());
            update.setVersion(requirement.getVersion());
            update.setIssuedQuantity(newIssued);
            update.setShortageQuantity(shortage);
            update.setStatus(readinessStatus(shortage, newIssued));
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (mfgWorkOrderMaterialMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "工单用料齐套状态已变化，请刷新后重试");
            }
            returnedByMaterial.put(requirement.getMaterialId(), pending.subtract(applied));
            touched = true;
        }
        closeFullyReturnedOutboundOrders(lines, operatorId, now);
        if (touched) {
            mfgDispatchOrders.ifAvailable(dispatch ->
                    dispatch.refreshMaterialReadiness(workOrderId, operatorId));
        }
    }

    /** 领料单所有明细都已被有效退料覆盖时，把出库单置为 CLOSED，解除「已领料」判定。 */
    private void closeFullyReturnedOutboundOrders(List<ReturnLine> lines, Long operatorId, LocalDateTime now) {
        Set<Long> outboundIds = new HashSet<>();
        for (ReturnLine line : lines) {
            if (line.sourceOrder() != null && line.sourceOrder().getOutboundId() != null) {
                outboundIds.add(line.sourceOrder().getOutboundId());
            }
        }
        for (Long outboundId : outboundIds) {
            WmsOutboundOrder order = wmsOutboundOrderMapper.selectById(outboundId);
            if (order == null || CLOSED_STATUS.equals(order.getAuditStatus())) {
                continue;
            }
            List<WmsOutboundDetail> details = wmsOutboundDetailMapper.selectList(
                    new LambdaQueryWrapper<WmsOutboundDetail>()
                            .eq(WmsOutboundDetail::getOutboundId, outboundId)
                            .eq(WmsOutboundDetail::getDeletedFlag, 0));
            if (details.isEmpty()) {
                continue;
            }
            boolean fullyReturned = true;
            for (WmsOutboundDetail detail : details) {
                BigDecimal returned = zero(wmsReturnDetailMapper.sumActiveReturnedQuantity(detail.getDetailId(), null));
                if (returned.compareTo(zero(detail.getOutboundQuantity())) < 0) {
                    fullyReturned = false;
                    break;
                }
            }
            if (!fullyReturned) {
                continue;
            }
            WmsOutboundOrder update = new WmsOutboundOrder();
            update.setOutboundId(outboundId);
            update.setVersion(order.getVersion());
            update.setAuditStatus(CLOSED_STATUS);
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (wmsOutboundOrderMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "来源领料单状态已变化，请刷新后重试");
            }
            insertStatusChangeLog(outboundId, order.getAuditStatus(), CLOSED_STATUS,
                    "WMS_OUTBOUND_ORDER", "RETURN_ALL_ISSUED", operatorId, "领料数量已全部退回", now);
        }
    }

    /** 按缺口与已领数量推导用料需求就绪状态，口径与领料确认保持一致。 */
    private static String readinessStatus(BigDecimal shortage, BigDecimal issued) {
        if (shortage.signum() == 0) {
            return "READY";
        }
        return issued.signum() > 0 ? "PARTIAL_READY" : "WAIT_READY";
    }

    /**
     * 构建退料单查询条件，默认过滤已删除记录。
     * @param keyword 关键字，匹配退料单号
     * @param returnType 退料类型
     * @param workOrderId 工单ID
     * @param applicantId 申请人ID
     * @param auditStatus 审核状态
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<WmsReturnOrder> buildReturnOrderQuery(String keyword, String returnType,
                                                                     Long workOrderId, Long applicantId, String auditStatus) {
        LambdaQueryWrapper<WmsReturnOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsReturnOrder::getDeletedFlag, 0)
                .like(StringUtils.hasText(keyword), WmsReturnOrder::getReturnNo, keyword)
                .eq(StringUtils.hasText(returnType), WmsReturnOrder::getReturnType, returnType)
                .eq(workOrderId != null, WmsReturnOrder::getWorkOrderId, workOrderId)
                .eq(applicantId != null, WmsReturnOrder::getApplicantId, applicantId)
                .eq(StringUtils.hasText(auditStatus), WmsReturnOrder::getAuditStatus, auditStatus)
                .orderByDesc(WmsReturnOrder::getCreatedTime);
        return queryWrapper;
    }

    /**
     * 软删除指定退料单的所有明细。
     * @param returnId 退料单ID
     */
    private void softDeleteDetails(Long returnId) {
        List<WmsReturnDetail> details = listActiveDetails(returnId);
        for (WmsReturnDetail detail : details) {
            WmsReturnDetail update = new WmsReturnDetail();
            update.setDetailId(detail.getDetailId());
            update.setDeletedFlag(1);
            if (wmsReturnDetailMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "退料明细已被其他用户修改，请刷新后重试");
            }
        }
    }

    /**
     * 查询指定退料单的未删除明细。
     * @param returnId 退料单ID
     * @return 退料明细列表
     */
    private List<WmsReturnDetail> listActiveDetails(Long returnId) {
        LambdaQueryWrapper<WmsReturnDetail> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsReturnDetail::getReturnId, returnId)
                .eq(WmsReturnDetail::getDeletedFlag, 0);
        return wmsReturnDetailMapper.selectList(queryWrapper);
    }

    private void validateReturnHeader(WmsReturnOrder returnOrder) {
        if (returnOrder == null || returnOrder.getWorkOrderId() == null
                || !StringUtils.hasText(returnOrder.getReturnType())) {
            throw business(ResultCode.PARAM_ERROR, "退料类型和生产工单不能为空");
        }
        String returnType = BusinessStatus.normalizeCode(returnOrder.getReturnType());
        if (!MATERIAL_RETURN_TYPES.contains(returnType)) {
            throw business(ResultCode.PARAM_ERROR, "生产退料类型只能是 GOOD 或 DEFECTIVE；成品退库必须走独立成品退库流程");
        }
        returnOrder.setReturnType(returnType);
    }

    private List<ReturnLine> prepareReturnLines(WmsReturnOrder returnOrder, List<WmsReturnDetail> details,
                                                Long excludedReturnId) {
        validateReturnHeader(returnOrder);
        requireReturnableWorkOrder(returnOrder.getWorkOrderId());
        requireReturnDetails(details);
        Set<Long> sourceDetailIds = new HashSet<>();
        List<ReturnLine> lines = new ArrayList<>();
        for (WmsReturnDetail detail : details) {
            lines.add(buildReturnLine(detail, returnOrder, excludedReturnId, sourceDetailIds));
        }
        validateAggregateReturnAvailability(returnOrder.getWorkOrderId(), lines, excludedReturnId);
        return lines;
    }

    /** 生产工单必须存在且未关闭。 */
    private void requireReturnableWorkOrder(Long workOrderId) {
        MfgWorkOrder workOrder = mfgWorkOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, workOrderId)
                .eq(MfgWorkOrder::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (workOrder == null || BusinessStatus.Document.CLOSED.equals(workOrder.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单不存在或已关闭，不能办理生产退料");
        }
    }

    /** 退料单至少需要一条明细。 */
    private void requireReturnDetails(List<WmsReturnDetail> details) {
        if (details == null || details.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "退料单至少需要一条来源领料明细");
        }
    }

    /** 校验单条退料明细并解析出对应的来源领料明细、领料单与线边标记。 */
    private ReturnLine buildReturnLine(WmsReturnDetail detail, WmsReturnOrder returnOrder,
                                       Long excludedReturnId, Set<Long> sourceDetailIds) {
        requireReturnDetailFields(detail);
        if (!sourceDetailIds.add(detail.getOutboundDetailId())) {
            throw business(ResultCode.BUSINESS_ERROR, "同一来源领料明细只能填写一次，请合并退料数量");
        }
        WmsOutboundDetail sourceDetail = requireSourceDetail(detail.getOutboundDetailId());
        WmsOutboundOrder sourceOrder = requireApprovedIssueOrder(sourceDetail, returnOrder.getWorkOrderId());
        requireSourceDetailDimensions(sourceOrder, sourceDetail);
        boolean lineSide = lineSideIssued(sourceOrder.getOutboundId());
        rejectSourceFieldMismatch(detail, sourceDetail);
        validateRemainingReturnableQuantity(detail, sourceDetail, excludedReturnId);
        detail.setMaterialId(sourceDetail.getMaterialId());
        detail.setBatchId(sourceDetail.getBatchId());
        detail.setLocationId(sourceDetail.getLocationId());
        detail.setReturnReason(detail.getReturnReason().trim());
        return new ReturnLine(detail, sourceDetail, sourceOrder, lineSide);
    }

    /** 退料明细的来源、数量、原因必填。 */
    private void requireReturnDetailFields(WmsReturnDetail detail) {
        if (detail == null || detail.getOutboundDetailId() == null) {
            throw business(ResultCode.PARAM_ERROR, "退料明细的来源领料明细、正数数量和退料原因不能为空");
        }
        if (!isPositive(detail.getReturnQuantity()) || !StringUtils.hasText(detail.getReturnReason())) {
            throw business(ResultCode.PARAM_ERROR, "退料明细的来源领料明细、正数数量和退料原因不能为空");
        }
    }

    /** 数量为正。 */
    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /** 来源领料明细必须存在。 */
    private WmsOutboundDetail requireSourceDetail(Long outboundDetailId) {
        WmsOutboundDetail sourceDetail = wmsOutboundDetailMapper.selectOne(
                new LambdaQueryWrapper<WmsOutboundDetail>()
                        .eq(WmsOutboundDetail::getDetailId, outboundDetailId)
                        .eq(WmsOutboundDetail::getDeletedFlag, 0));
        if (sourceDetail == null) {
            throw business(ResultCode.NOT_FOUND, "来源领料明细不存在或已删除");
        }
        return sourceDetail;
    }

    /** 来源明细必须属于当前工单已确认的生产领料单。 */
    private WmsOutboundOrder requireApprovedIssueOrder(WmsOutboundDetail sourceDetail, Long workOrderId) {
        WmsOutboundOrder sourceOrder = wmsOutboundOrderMapper.selectOne(
                new LambdaQueryWrapper<WmsOutboundOrder>()
                        .eq(WmsOutboundOrder::getOutboundId, sourceDetail.getOutboundId())
                        .eq(WmsOutboundOrder::getDeletedFlag, 0));
        if (sourceOrder == null || !MATERIAL_ISSUE_TYPE.equals(sourceOrder.getOutboundType())
                || !APPROVED_STATUS.equals(sourceOrder.getAuditStatus())
                || !Objects.equals(workOrderId, sourceOrder.getWorkOrderId())) {
            throw business(ResultCode.BUSINESS_ERROR, "来源明细必须属于当前工单已确认的生产领料单");
        }
        return sourceOrder;
    }

    /** 来源领料明细必须带齐仓库、物料、批次、库位与有效数量。 */
    private void requireSourceDetailDimensions(WmsOutboundOrder sourceOrder, WmsOutboundDetail sourceDetail) {
        boolean missingDimension = sourceOrder.getWarehouseId() == null || sourceDetail.getMaterialId() == null
                || sourceDetail.getBatchId() == null || sourceDetail.getLocationId() == null;
        if (missingDimension || !isPositive(sourceDetail.getOutboundQuantity())) {
            throw business(ResultCode.BUSINESS_ERROR, "来源领料明细缺少仓库、物料、批次、库位或有效数量");
        }
    }

    /** 本次退料量不得超过来源明细尚未退回的数量。 */
    private void validateRemainingReturnableQuantity(WmsReturnDetail detail, WmsOutboundDetail sourceDetail,
                                                      Long excludedReturnId) {
        BigDecimal returned = wmsReturnDetailMapper.sumActiveReturnedQuantity(
                sourceDetail.getDetailId(), excludedReturnId);
        executionQuantities.validate(sourceDetail.getMaterialId(), detail.getReturnQuantity(), false);
        BigDecimal cumulative = (returned == null ? BigDecimal.ZERO : returned).add(detail.getReturnQuantity());
        if (cumulative.compareTo(sourceDetail.getOutboundQuantity()) > 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "退料数量超过来源领料明细尚未退回数量；来源明细ID=" + sourceDetail.getDetailId());
        }
    }

    /**
     * 判断该领料单是否已执行线边移库：存在成对 TRANSFER 流水即视为线边占用。
     * 线边占用的退料必须成对退回（线边锁定减、原仓可用加），不能直接回加原仓。
     */
    private boolean lineSideIssued(Long outboundId) {
        return wmsStockMovementMapper.selectCount(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getSourceOrderType, OUTBOUND_SOURCE_TYPE)
                .eq(WmsStockMovement::getSourceOrderId, outboundId)
                .eq(WmsStockMovement::getMovementType, TRANSFER_MOVEMENT_TYPE)
                .isNotNull(WmsStockMovement::getPairNo)
                .eq(WmsStockMovement::getDeletedFlag, 0)) > 0;
    }

    private void validateAggregateReturnAvailability(Long workOrderId, List<ReturnLine> lines,
                                                     Long excludedReturnId) {
        Map<ReturnMaterialKey, BigDecimal> requested = new LinkedHashMap<>();
        for (ReturnLine line : lines) {
            ReturnMaterialKey key = new ReturnMaterialKey(
                    line.detail().getMaterialId(), line.detail().getBatchId());
            requested.merge(key, line.detail().getReturnQuantity(), BigDecimal::add);
        }
        for (Map.Entry<ReturnMaterialKey, BigDecimal> entry : requested.entrySet()) {
            ReturnMaterialKey key = entry.getKey();
            BigDecimal issued = wmsOutboundDetailMapper.selectList(new LambdaQueryWrapper<WmsOutboundDetail>()
                            .eq(WmsOutboundDetail::getMaterialId, key.materialId())
                            .eq(WmsOutboundDetail::getBatchId, key.batchId())
                            .eq(WmsOutboundDetail::getDeletedFlag, 0)
                            .inSql(WmsOutboundDetail::getOutboundId,
                                    "SELECT outbound_id FROM wms_outbound_order WHERE deleted_flag = 0 "
                                            + "AND outbound_type = 'MATERIAL_ISSUE' AND audit_status = 'APPROVED' "
                                            + "AND work_order_id = " + workOrderId))
                    .stream().map(WmsOutboundDetail::getOutboundQuantity).map(this::zero)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal consumed = mfgReportMaterialMapper.selectList(new LambdaQueryWrapper<MfgReportMaterial>()
                            .eq(MfgReportMaterial::getMaterialId, key.materialId())
                            .eq(MfgReportMaterial::getBatchId, key.batchId())
                            .eq(MfgReportMaterial::getDeletedFlag, 0)
                            .inSql(MfgReportMaterial::getReportId,
                                    "SELECT report.report_id FROM mfg_work_report report "
                                            + "JOIN mfg_task task ON task.task_id = report.task_id "
                                            + "WHERE report.deleted_flag = 0 AND task.deleted_flag = 0 "
                                            + "AND report.audit_status IN ('SUBMITTED','APPROVED') "
                                            + "AND task.work_order_id = " + workOrderId))
                    .stream().map(item -> zero(item.getUsedQuantity()).add(zero(item.getScrapQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal returned = zero(wmsReturnDetailMapper.sumActiveReturnedQuantityByBatch(
                    workOrderId, key.materialId(), key.batchId(), excludedReturnId));
            BigDecimal available = issued.subtract(consumed).subtract(returned).max(BigDecimal.ZERO);
            if (entry.getValue().compareTo(available) > 0) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "退料数量超过本工单该批次未消耗领料量；物料ID=" + key.materialId()
                                + "，批次ID=" + key.batchId() + "，可退=" + available
                                + "，本次=" + entry.getValue());
            }
        }
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void rejectSourceFieldMismatch(WmsReturnDetail detail, WmsOutboundDetail sourceDetail) {
        boolean materialMismatch = detail.getMaterialId() != null
                && !Objects.equals(detail.getMaterialId(), sourceDetail.getMaterialId());
        boolean batchMismatch = detail.getBatchId() != null
                && !Objects.equals(detail.getBatchId(), sourceDetail.getBatchId());
        boolean locationMismatch = detail.getLocationId() != null
                && !Objects.equals(detail.getLocationId(), sourceDetail.getLocationId());
        if (materialMismatch || batchMismatch || locationMismatch) {
            throw business(ResultCode.BUSINESS_ERROR, "退料物料、批次或库位与来源领料明细不一致");
        }
    }

    private Map<Long, InventoryAddition> prepareInventoryAdditions(List<ReturnLine> lines) {
        Map<Long, InventoryAddition> additions = new LinkedHashMap<>();
        for (ReturnLine line : lines) {
            WmsInventory inventory = lockOrCreateReturnInventory(line);
            InventoryAddition existing = additions.get(inventory.getInventoryId());
            BigDecimal quantity = line.detail().getReturnQuantity();
            additions.put(inventory.getInventoryId(), existing == null
                    ? new InventoryAddition(inventory, quantity)
                    : new InventoryAddition(existing.inventory(), existing.quantity().add(quantity)));
        }
        return additions;
    }

    /** 锁定退料回加的库存行；线边占用的退料允许按来源仓库与库位补建公共库存行。 */
    private WmsInventory lockOrCreateReturnInventory(ReturnLine line) {
        Long warehouseId = resolveInventoryWarehouseId(line);
        WmsInventory inventory = wmsInventoryMapper.selectOne(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWarehouseId, warehouseId)
                .eq(WmsInventory::getLocationId, line.detail().getLocationId())
                .eq(WmsInventory::getMaterialId, line.detail().getMaterialId())
                .eq(WmsInventory::getBatchId, line.detail().getBatchId())
                .eq(WmsInventory::getWorkOrderId, 0L)
                .eq(WmsInventory::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (inventory != null) {
            return inventory;
        }
        if (!line.lineSide()) {
            throw business(ResultCode.BUSINESS_ERROR, "来源领料库存行不存在，不能确认退料");
        }
        // 移库时原仓该行可能已被扣到零并被清理，退料按来源仓库与库位补建公共库存行。
        return newInventoryRow(warehouseId, line.detail().getLocationId(),
                line.detail().getMaterialId(), line.detail().getBatchId());
    }

    /**
     * 解析退料应回加的仓库：以来源库位所属仓库为准，库位查不到时退回领料单头仓库。
     * 根工单会跨原料仓与半成品仓领料，单头仓库只是归档字段，不能作为唯一依据。
     */
    private Long resolveInventoryWarehouseId(ReturnLine line) {
        Long locationId = line.detail().getLocationId();
        if (locationId != null) {
            WmsLocation location = wmsLocationMapper.selectById(locationId);
            if (location != null && location.getWarehouseId() != null) {
                return location.getWarehouseId();
            }
        }
        return line.sourceOrder().getWarehouseId();
    }

    private WmsInventory newInventoryRow(Long warehouseId, Long locationId, Long materialId, Long batchId) {
        WmsInventory inventory = new WmsInventory();
        inventory.setWarehouseId(warehouseId);
        inventory.setLocationId(locationId);
        inventory.setMaterialId(materialId);
        inventory.setBatchId(batchId);
        inventory.setWorkOrderId(0L);
        inventory.setAvailableQuantity(BigDecimal.ZERO);
        inventory.setLockedQuantity(BigDecimal.ZERO);
        inventory.setInspectionQuantity(BigDecimal.ZERO);
        inventory.setDeletedFlag(0);
        return inventory;
    }

    /**
     * 汇总线边占用的退回量：线边占用按工单隔离，因此按工单+物料+批次锁行并校验可退数量。
     * 同一物料批次可能因移库分批落在多行，按行顺序累加，退回量不得超过合计锁定量。
     */
    private Map<Long, LineSideDeduction> prepareLineSideDeductions(List<ReturnLine> lines, Long workOrderId) {
        Map<Long, LineSideDeduction> deductions = new LinkedHashMap<>();
        for (ReturnLine line : lines) {
            if (line.lineSide()) {
                allocateLineSideDeduction(line, workOrderId, deductions);
            }
        }
        return deductions;
    }

    /** 为单条线边退料分配要扣减的库存行，合计锁定量不足直接抛出。 */
    private void allocateLineSideDeduction(ReturnLine line, Long workOrderId,
                                           Map<Long, LineSideDeduction> deductions) {
        List<WmsInventory> rows = lockLineSideRows(workOrderId,
                line.detail().getMaterialId(), line.detail().getBatchId());
        if (rows.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "工单在线边没有该物料批次的占用库存，不能确认线边退料");
        }
        BigDecimal locked = rows.stream().map(row -> zero(row.getLockedQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (locked.compareTo(line.detail().getReturnQuantity()) < 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "线边退料数量超过工单在线边的占用余量；物料ID=" + line.detail().getMaterialId()
                            + "，批次ID=" + line.detail().getBatchId()
                            + "，线边占用=" + locked
                            + "，本次=" + line.detail().getReturnQuantity());
        }
        BigDecimal remaining = takeFromRows(rows, line.detail().getReturnQuantity(), deductions);
        if (remaining.signum() > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "线边占用库存不足以覆盖本次退料数量，请核对后重试");
        }
    }

    /** 按工单+物料+批次锁住线边库存行，按主键升序保证加锁顺序稳定。 */
    private List<WmsInventory> lockLineSideRows(Long workOrderId, Long materialId, Long batchId) {
        return wmsInventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWorkOrderId, workOrderId)
                .eq(WmsInventory::getMaterialId, materialId)
                .eq(WmsInventory::getBatchId, batchId)
                .eq(WmsInventory::getDeletedFlag, 0)
                .orderByAsc(WmsInventory::getInventoryId)
                .last("FOR UPDATE"));
    }

    /** 按行顺序扣减，返回未能分配完的剩余量。 */
    private BigDecimal takeFromRows(List<WmsInventory> rows, BigDecimal quantity,
                                    Map<Long, LineSideDeduction> deductions) {
        BigDecimal remaining = quantity;
        for (WmsInventory row : rows) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal rowLocked = zero(row.getLockedQuantity());
            if (rowLocked.signum() <= 0) {
                continue;
            }
            BigDecimal take = rowLocked.min(remaining);
            mergeDeduction(deductions, row, take);
            remaining = remaining.subtract(take);
        }
        return remaining;
    }

    /** 同一库存行可能被多条退料命中，扣减量累加。 */
    private void mergeDeduction(Map<Long, LineSideDeduction> deductions, WmsInventory row, BigDecimal take) {
        LineSideDeduction existing = deductions.get(row.getInventoryId());
        BigDecimal merged = existing == null ? take : existing.quantity().add(take);
        deductions.put(row.getInventoryId(), new LineSideDeduction(row, merged));
    }

    private void decreaseLineSideLocked(LineSideDeduction deduction, Long operatorId, LocalDateTime now) {
        WmsInventory inventory = deduction.inventory();
        WmsInventory update = new WmsInventory();
        update.setInventoryId(inventory.getInventoryId());
        update.setVersion(inventory.getVersion());
        update.setLockedQuantity(zero(inventory.getLockedQuantity()).subtract(deduction.quantity()));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (wmsInventoryMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "线边占用余额已被其他请求修改，请刷新后重试");
        }
    }

    private WmsReturnOrder getReturnOrderForUpdate(Long returnId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<WmsReturnOrder>()
                .eq(WmsReturnOrder::getReturnId, returnId)
                .eq(WmsReturnOrder::getDeletedFlag, 0)
                .last("FOR UPDATE"));
    }

    private void increaseInventory(InventoryAddition addition, Long operatorId, LocalDateTime now) {
        WmsInventory inventory = addition.inventory();
        BigDecimal availableQuantity = inventory.getAvailableQuantity() == null
                ? BigDecimal.ZERO : inventory.getAvailableQuantity();
        if (inventory.getInventoryId() == null) {
            // 原仓该余额行已被移库扣到零并清理时补建，直接新增而不是更新空主键。
            WmsInventory created = newInventoryRow(inventory.getWarehouseId(), inventory.getLocationId(),
                    inventory.getMaterialId(), inventory.getBatchId());
            created.setAvailableQuantity(addition.quantity());
            created.setCreatedBy(operatorId);
            created.setCreatedTime(now);
            created.setUpdatedBy(operatorId);
            created.setUpdatedTime(now);
            if (wmsInventoryMapper.insert(created) != 1) {
                throw business(ResultCode.FAILED, "退料库存余额创建失败");
            }
            notifyAvailableChanged(inventory.getMaterialId());
            return;
        }
        WmsInventory update = new WmsInventory();
        update.setInventoryId(inventory.getInventoryId());
        update.setVersion(inventory.getVersion());
        update.setAvailableQuantity(availableQuantity.add(addition.quantity()));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (wmsInventoryMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "库存余额已被其他请求修改，请刷新后重试");
        }
        notifyAvailableChanged(inventory.getMaterialId());
    }

    private void notifyAvailableChanged(Long materialId) {
        if (inventoryAutomationTrigger != null) {
            inventoryAutomationTrigger.afterChange(materialId);
        }
    }

    /**
     * 写入退料入库库存流水。
     * @param returnOrder 退料单实体
     * @param line 退料明细及来源领料信息
     * @param operatorId 操作人ID
     * @param remark 备注
     * @param operationTime 操作时间
     */
    private void insertReturnMovement(WmsReturnOrder returnOrder, ReturnLine line,
                                      Long operatorId, String remark, LocalDateTime operationTime) {
        WmsReturnDetail detail = line.detail();
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo("WMS-RETURN-" + returnOrder.getReturnId() + "-" + detail.getDetailId());
        movement.setMovementType("RETURN");
        movement.setSourceOrderType("RETURN_ORDER");
        movement.setSourceOrderId(returnOrder.getReturnId());
        movement.setMaterialId(detail.getMaterialId());
        movement.setBatchId(detail.getBatchId());
        movement.setQuantity(detail.getReturnQuantity());
        movement.setDirection("IN");
        movement.setOperationTime(operationTime);
        movement.setCreatedBy(operatorId);
        movement.setRemark(remark);
        movement.setDeletedFlag(0);
        if (wmsStockMovementMapper.insert(movement) == 0) {
            throw business(ResultCode.FAILED, "退料库存流水写入失败");
        }
    }

    /**
     * 写入线边退料的成对流水：线边仓方向 OUT（释放占用），原仓方向 IN（回加可用）。
     * 两条流水共用 pairNo，便于核对「退一补一」是否完整，行为口径与移库相反。
     */
    private void insertLineSideReturnMovements(WmsReturnOrder returnOrder, ReturnLine line,
                                               Long operatorId, String remark, LocalDateTime operationTime) {
        WmsReturnDetail detail = line.detail();
        WmsWarehouse lineSide = findLineSideWarehouse();
        if (lineSide == null) {
            throw business(ResultCode.BUSINESS_ERROR, "线边仓不存在或未启用，不能确认线边退料");
        }
        WmsLocation lineSideLocation = findLineSideLocation(lineSide.getWarehouseId());
        if (lineSideLocation == null) {
            throw business(ResultCode.BUSINESS_ERROR, "线边仓库位不存在或未启用，不能确认线边退料");
        }
        String pair = "LSR-" + returnOrder.getReturnId() + "-" + detail.getDetailId();
        insertReturnPairMovement(returnOrder, line, pair, "OUT", lineSide.getWarehouseId(),
                lineSideLocation.getLocationId(), operatorId, remark, operationTime);
        insertReturnPairMovement(returnOrder, line, pair, "IN", line.sourceOrder().getWarehouseId(),
                detail.getLocationId(), operatorId, remark, operationTime);
    }

    private void insertReturnPairMovement(WmsReturnOrder returnOrder, ReturnLine line, String pair,
                                          String direction, Long warehouseId, Long locationId,
                                          Long operatorId, String remark, LocalDateTime operationTime) {
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo(pair + "-" + direction);
        movement.setPairNo(pair);
        movement.setMovementType(TRANSFER_MOVEMENT_TYPE);
        movement.setSourceOrderType(RETURN_SOURCE_TYPE);
        movement.setSourceOrderId(returnOrder.getReturnId());
        movement.setWorkOrderId(returnOrder.getWorkOrderId());
        movement.setMaterialId(line.detail().getMaterialId());
        movement.setBatchId(line.detail().getBatchId());
        movement.setWarehouseId(warehouseId);
        movement.setLocationId(locationId);
        movement.setQuantity(line.detail().getReturnQuantity());
        movement.setDirection(direction);
        movement.setOperationTime(operationTime);
        movement.setCreatedBy(operatorId);
        movement.setRemark(StringUtils.hasText(remark) ? remark : "线边退料：释放工单占用并退回原仓");
        movement.setDeletedFlag(0);
        if (wmsStockMovementMapper.insert(movement) == 0) {
            throw business(ResultCode.FAILED, "线边退料配对流水写入失败");
        }
    }

    private WmsWarehouse findLineSideWarehouse() {
        return wmsWarehouseMapper.selectOne(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseType, LINE_SIDE_WAREHOUSE_TYPE)
                .eq(WmsWarehouse::getStatus, "ENABLE")
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .orderByAsc(WmsWarehouse::getWarehouseId)
                .last("LIMIT 1"));
    }

    private WmsLocation findLineSideLocation(Long warehouseId) {
        return wmsLocationMapper.selectOne(new LambdaQueryWrapper<WmsLocation>()
                .eq(WmsLocation::getWarehouseId, warehouseId)
                .eq(WmsLocation::getLocationType, NORMAL_LOCATION_TYPE)
                .in(WmsLocation::getStatus, USABLE_LOCATION_STATUSES)
                .eq(WmsLocation::getDeletedFlag, 0)
                .orderByAsc(WmsLocation::getLocationId)
                .last("LIMIT 1"));
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
            throw business(ResultCode.FAILED, "退料状态日志写入失败");
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
            throw business(ResultCode.FAILED, "退料操作日志写入失败");
        }
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }

    private record ReturnLine(WmsReturnDetail detail, WmsOutboundDetail sourceDetail,
                              WmsOutboundOrder sourceOrder, boolean lineSide) {
    }

    private record LineSideDeduction(WmsInventory inventory, BigDecimal quantity) {
    }

    private record InventoryAddition(WmsInventory inventory, BigDecimal quantity) {
    }

    private record ReturnMaterialKey(Long materialId, Long batchId) {
    }
}
