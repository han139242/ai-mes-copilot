package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.mfg.MfgReportMaterial;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgTask;
import com.neuedu.ems.entity.mfg.MfgTaskOperator;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.qms.QmsInspectionScheme;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.trace.TraceMaterialUsage;
import com.neuedu.ems.entity.trace.TraceProductLot;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsOutboundDetail;
import com.neuedu.ems.entity.wms.WmsReturnDetail;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.mapper.mfg.MfgReportMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskOperatorMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.sys.SysUserMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.trace.TraceMaterialUsageMapper;
import com.neuedu.ems.mapper.trace.TraceProductLotMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsLocationMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsReturnDetailMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.entity.sys.SysUser;
import com.neuedu.ems.service.mfg.MfgWorkReportService;
import com.neuedu.ems.service.impl.qms.QmsResponsibilityNotificationService;
import com.neuedu.ems.service.sys.DataScopeService;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import com.neuedu.ems.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生产报工 Service 实现类。
 */
@Slf4j
@Service
public class MfgWorkReportServiceImpl extends ServiceImpl<MfgWorkReportMapper, MfgWorkReport> implements MfgWorkReportService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;

    @Autowired private com.neuedu.ems.service.mdm.ManufacturingPreparationGate preparationGate;
    @Autowired private com.neuedu.ems.service.mfg.ExecutionSnapshotPolicy snapshotPolicy;
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.mdm.MaterialExecutionQuantityService executionQuantities;
    @Autowired private com.neuedu.ems.service.mdm.ProductQuantityService productQuantities;


    private static final String SUBMITTED_STATUS = "SUBMITTED";
    private static final String APPROVED_STATUS = "APPROVED";

    @Autowired
    private MfgReportMaterialMapper mfgReportMaterialMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private MfgTaskMapper mfgTaskMapper;

    @Autowired
    private MfgTaskOperatorMapper mfgTaskOperatorMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private MfgWorkOrderMapper mfgWorkOrderMapper;

    @Autowired
    private MfgProductionPlanMapper mfgProductionPlanMapper;

    @Autowired
    private MfgProductionOrderMapper mfgProductionOrderMapper;

    @Autowired
    private ProcessRouteItemMapper processRouteItemMapper;

    @Autowired
    private WmsMaterialBatchMapper wmsMaterialBatchMapper;

    @Autowired
    private WmsOutboundDetailMapper wmsOutboundDetailMapper;

    @Autowired
    private WmsReturnDetailMapper wmsReturnDetailMapper;

    @Autowired
    private TraceProductLotMapper traceProductLotMapper;

    @Autowired
    private TraceMaterialUsageMapper traceMaterialUsageMapper;

    @Autowired
    private QmsInspectionSchemeMapper qmsInspectionSchemeMapper;

    @Autowired
    private QmsInspectionOrderMapper qmsInspectionOrderMapper;

    @Autowired(required = false)
    private QmsResponsibilityNotificationService qmsResponsibilityNotificationService;

    @Autowired(required = false)
    private DataScopeService dataScopeService;

    @Autowired(required = false)
    private MfgResponsibilityNotificationService responsibilityNotificationService;

    @Autowired(required = false)
    private WmsInventoryMapper wmsInventoryMapper;

    @Autowired(required = false)
    private WmsStockMovementMapper wmsStockMovementMapper;

    @Autowired(required = false)
    private WmsWarehouseMapper wmsWarehouseMapper;

    @Autowired(required = false)
    private WmsLocationMapper wmsLocationMapper;

    @Autowired(required = false)
    private MdmProductMapper mdmProductMapper;

    /**
     * 分页查询生产报工，支持编号、任务、人员、工位和审核状态筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 报工编号或任务编号关键字
     * @param taskId 任务ID
     * @param operatorId 操作工ID
     * @param stationId 工位ID
     * @param auditStatus 审核状态
     * @return 报工分页结果
     */
    @Override
    public Page<MfgWorkReport> pageWorkReports(Integer current, Integer size, String keyword, Long taskId,
                                               Long operatorId, Long stationId, String auditStatus) {
        Page<MfgWorkReport> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildWorkReportQuery(keyword, taskId, operatorId, stationId, auditStatus));
    }

    /**
     * 查询生产报工列表，支持编号、任务、人员、工位和审核状态筛选。
     * @param keyword 报工编号关键字
     * @param taskId 任务ID
     * @param operatorId 操作工ID
     * @param stationId 工位ID
     * @param auditStatus 审核状态
     * @return 报工列表
     */
    @Override
    public List<MfgWorkReport> listWorkReports(String keyword, Long taskId, Long operatorId, Long stationId, String auditStatus) {
        return baseMapper.selectList(buildWorkReportQuery(keyword, taskId, operatorId, stationId, auditStatus));
    }

    /**
     * 根据ID查询未删除的生产报工。
     * @param reportId 报工ID
     * @return 报工实体
     */
    @Override
    public MfgWorkReport getWorkReportById(Long reportId) {
        LambdaQueryWrapper<MfgWorkReport> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MfgWorkReport::getReportId, reportId)
                .eq(MfgWorkReport::getDeletedFlag, 0);
        applyDataScope(queryWrapper);
        MfgWorkReport report = baseMapper.selectOne(queryWrapper);
        if (report == null && dataScopeService != null && dataScopeService.isRestricted()
                && baseMapper.selectCount(new LambdaQueryWrapper<MfgWorkReport>()
                .eq(MfgWorkReport::getReportId, reportId).eq(MfgWorkReport::getDeletedFlag, 0)) > 0) {
            throw new DataScopeAccessDeniedException();
        }
        return report;
    }

    /**
     * 新增生产报工，自动补齐编号、数量、时间、状态和删除标记。
     * @param workReport 报工实体
     * @return 是否成功
     */
    @Override
    public boolean saveWorkReport(MfgWorkReport workReport) {
        if (workReport == null) {
            throw business(ResultCode.PARAM_ERROR, "报工信息不能为空");
        }
        prepareWorkReport(workReport);
        validateWorkReport(workReport);
        MfgTask task = activeTaskForUpdate(workReport.getTaskId());
        if (task == null || !(BusinessStatus.Production.RUNNING.equals(task.getStatus())
                || BusinessStatus.Production.PAUSED.equals(task.getStatus()))) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已开工任务可以提交报工");
        }
        validateTaskReportQuantity(task, workReport);
        if (dataScopeService != null) {
            dataScopeService.assertCanWriteWorkReport(workReport);
        }
        return baseMapper.insert(workReport) > 0;
    }

    /**
     * 新增报工主表和用料明细。
     * @param workReport 报工实体
     * @param materials 报工用料明细列表
     * @return 报工ID
     */
    @Override
    @Transactional
    public Long saveWorkReportFull(MfgWorkReport workReport, List<MfgReportMaterial> materials) {
        validateReportMaterials(workReport, materials);
        saveWorkReport(workReport);
        insertMaterials(workReport.getReportId(), materials);
        return workReport.getReportId();
    }

    /**
     * 修改生产报工，已审核通过报工不允许普通修改。
     * @param workReport 报工实体
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean updateWorkReport(MfgWorkReport workReport) {
        if (workReport.getReportId() == null) {
            return false;
        }
        MfgWorkReport existing = getWorkReportById(workReport.getReportId());
        if (existing == null || !(SUBMITTED_STATUS.equals(existing.getAuditStatus())
                || BusinessStatus.Document.REJECTED.equals(existing.getAuditStatus()))) {
            return false;
        }
        fillMissingQuantities(workReport, existing);
        if (workReport.getDefectDescription()==null) workReport.setDefectDescription(existing.getDefectDescription());
        validateDefectDescription(workReport);
        workReport.setDeletedFlag(null);
        workReport.setAuditStatus(null);
        workReport.setReportNo(null);
        workReport.setTaskId(null);
        workReport.setOperatorId(null);
        workReport.setStationId(null);
        workReport.setReportTime(null);
        if (workReport.getVersion() == null) {
            workReport.setVersion(existing.getVersion());
        }
        boolean updated = baseMapper.updateById(workReport) > 0;
        if (updated && SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            syncTaskCompletionAfterReportChange(existing.getTaskId(), reportChangeOperator(workReport, existing),
                    LocalDateTime.now());
        }
        return updated;
    }

    /**
     * 修改报工主表并替换用料明细，已审核通过报工不允许替换明细。
     * @param workReport 报工实体
     * @param materials 报工用料明细列表
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean updateWorkReportFull(MfgWorkReport workReport, List<MfgReportMaterial> materials) {
        MfgWorkReport original = getWorkReportById(workReport.getReportId());
        if (original == null) return false;
        workReport.setTaskId(original.getTaskId());
        validateReportMaterials(workReport, materials);
        if (!updateWorkReport(workReport)) {
            return false;
        }
        if (materials != null) {
            softDeleteMaterials(workReport.getReportId());
            insertMaterials(workReport.getReportId(), materials);
        }
        return true;
    }

    /**
     * 根据ID软删除生产报工及用料明细，已审核通过报工不允许删除。
     * @param reportId 报工ID
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean removeWorkReportById(Long reportId) {
        MfgWorkReport existing = getWorkReportById(reportId);
        if (existing == null || !(SUBMITTED_STATUS.equals(existing.getAuditStatus())
                || BusinessStatus.Document.REJECTED.equals(existing.getAuditStatus()))) {
            return false;
        }
        MfgWorkReport workReport = new MfgWorkReport();
        workReport.setReportId(reportId);
        workReport.setVersion(existing.getVersion());
        workReport.setDeletedFlag(1);
        boolean removed = baseMapper.updateById(workReport) > 0;
        if (!removed) {
            return false;
        }
        softDeleteMaterials(reportId);
        if (SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            syncTaskCompletionAfterReportChange(existing.getTaskId(), reportChangeOperator(workReport, existing),
                    LocalDateTime.now());
        }
        return true;
    }

    /**
     * 提交生产报工并保存用料明细，状态写为SUBMITTED。
     * @param workReport 报工实体
     * @param materials 报工用料明细列表
     * @return 报工ID
     */
    @Override
    @Transactional
    public Long submitWorkReport(MfgWorkReport workReport, List<MfgReportMaterial> materials, Long userId) {
        bindCurrentOperator(workReport, userId);
        workReport.setAuditStatus(SUBMITTED_STATUS);
        Long reportId = saveWorkReportFull(workReport, materials);
        LocalDateTime now = LocalDateTime.now();
        Long currentUserId = userId;
        insertStatusChangeLog(reportId, null, SUBMITTED_STATUS, "MFG_WORK_REPORT", "SUBMIT_WORK_REPORT",
                currentUserId, workReport.getRemark(), now);
        insertOperationLog(reportId, "SUBMIT_WORK_REPORT", "mfg_work_report", currentUserId, workReport.getRemark(), now);
        finishTaskIfReportedComplete(workReport.getTaskId(), currentUserId, now);
        if (responsibilityNotificationService != null) {
            responsibilityNotificationService.publishWorkReportSubmitted(workReport, currentUserId);
        }
        return reportId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawWorkReport(Long reportId, Long userId, String remark) {
        if (reportId == null || userId == null) {
            throw business(ResultCode.PARAM_ERROR, "报工单和操作人不能为空");
        }
        remark = StringUtils.hasText(remark) ? remark.trim() : "用户主动撤回，未填写补充原因";
        MfgWorkReport existing = getWorkReportById(reportId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "生产报工不存在或已删除");
        }
        if (!SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待审核报工可以撤回修改");
        }
        SysUser user = activeUser(userId);
        if (user.getEmployeeId() == null || !user.getEmployeeId().equals(existing.getOperatorId())) {
            throw business(ResultCode.FORBIDDEN, "只能撤回本人提交的生产报工");
        }
        MfgWorkReport update = new MfgWorkReport();
        update.setReportId(reportId);
        update.setVersion(existing.getVersion());
        update.setAuditStatus(BusinessStatus.Document.WITHDRAWN);
        update.setUpdatedBy(userId);
        update.setUpdatedTime(LocalDateTime.now());
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "生产报工已被其他用户修改，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        insertStatusChangeLog(reportId, existing.getAuditStatus(), BusinessStatus.Document.WITHDRAWN,
                "MFG_WORK_REPORT", "WITHDRAW_WORK_REPORT", userId, remark, now);
        insertOperationLog(reportId, "WITHDRAW_WORK_REPORT", "mfg_work_report", userId, remark, now);
        reopenTaskIfReportedIncomplete(existing.getTaskId(), userId, now);
        if (responsibilityNotificationService != null) {
            responsibilityNotificationService.cancelWorkReportSubmitted(reportId, userId);
        }
    }

    /**
     * 审核生产报工，审核结果使用APPROVED或REJECTED。
     * @param reportId 报工ID
     * @param auditResult 审核结果
     * @param auditComment 审核备注
     * @param operatorId 操作人ID
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean auditWorkReport(Long reportId, String auditResult, String auditComment, Long operatorId) {
        String normalizedAuditResult = BusinessStatus.normalizeCode(auditResult);
        if (!BusinessStatus.isApprovalResult(normalizedAuditResult) || operatorId == null) {
            return false;
        }
        MfgWorkReport existing = getWorkReportById(reportId);
        if (existing == null || !SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            return false;
        }
        MfgWorkOrder workOrder = APPROVED_STATUS.equals(normalizedAuditResult)
                ? validateApprovalQuantity(existing)
                : null;
        MfgWorkReport update = new MfgWorkReport();
        update.setReportId(reportId);
        update.setVersion(existing.getVersion());
        update.setAuditStatus(normalizedAuditResult);
        update.setRemark(StringUtils.hasText(auditComment) ? auditComment : existing.getRemark());
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "生产报工已被其他用户修改，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        insertStatusChangeLog(reportId, existing.getAuditStatus(), normalizedAuditResult, "MFG_WORK_REPORT", "AUDIT_WORK_REPORT",
                operatorId, auditComment, now);
        insertOperationLog(reportId, "AUDIT_WORK_REPORT", "mfg_work_report", operatorId, auditComment, now);
        if (workOrder != null) {
            consumeLineSideMaterials(existing, workOrder, operatorId, now);
            generateQualityChain(existing, workOrder, operatorId, now);
            outputLineSideProducts(existing, workOrder, operatorId, now);
            aggregateWorkOrder(workOrder, operatorId, now);
            finishTaskIfReportedComplete(existing.getTaskId(), operatorId, now);
        } else if (BusinessStatus.Document.REJECTED.equals(normalizedAuditResult)) {
            reopenTaskIfReportedIncomplete(existing.getTaskId(), operatorId, now);
        }
        if (responsibilityNotificationService != null) {
            responsibilityNotificationService.cancelWorkReportSubmitted(reportId, operatorId);
            existing.setAuditStatus(normalizedAuditResult);
            responsibilityNotificationService.publishWorkReportAudited(existing, normalizedAuditResult, operatorId);
        }
        return true;
    }

    /**
     * 审核通过后扣减线边仓实耗占用。
     * 线边库存的 available_quantity 恒为 0，工单占用量记录在 locked_quantity，因此这里只扣占用。
     * 该工单在线边仓没有对应物料记录时（旧数据或未做移库）跳过，保证旧流程兼容。
     */
    private void consumeLineSideMaterials(MfgWorkReport report, MfgWorkOrder workOrder,
                                          Long operatorId, LocalDateTime now) {
        List<MfgReportMaterial> materials = consumableMaterials(report);
        if (materials == null) {
            return;
        }
        // 幂等：同一报工已经扣减过线边占用时直接跳过，避免重复审核重复扣减。
        if (isAlreadyConsumed(report) || !hasLineSideWarehouse(report)) {
            return;
        }
        Map<String, BigDecimal> requested = requestedConsumptions(materials);
        for (Map.Entry<String, BigDecimal> entry : requested.entrySet()) {
            consumeOneEntry(report, workOrder, operatorId, now, entry);
        }
    }

    /** 取本次报工需要扣减的物料明细；依赖缺失或没有明细时返回 null。 */
    private List<MfgReportMaterial> consumableMaterials(MfgWorkReport report) {
        if (wmsInventoryMapper == null || wmsStockMovementMapper == null || report.getReportId() == null) {
            return null;
        }
        List<MfgReportMaterial> materials = mfgReportMaterialMapper.selectList(new LambdaQueryWrapper<MfgReportMaterial>()
                .eq(MfgReportMaterial::getReportId, report.getReportId())
                .eq(MfgReportMaterial::getDeletedFlag, 0));
        return materials.isEmpty() ? null : materials;
    }

    /** 同一报工是否已经写过实耗流水。 */
    private boolean isAlreadyConsumed(MfgWorkReport report) {
        long consumed = wmsStockMovementMapper.selectCount(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getMovementType, "CONSUME")
                .eq(WmsStockMovement::getSourceOrderType, "WORK_REPORT")
                .eq(WmsStockMovement::getSourceOrderId, report.getReportId())
                .eq(WmsStockMovement::getDeletedFlag, 0));
        return consumed > 0;
    }

    /** 是否存在启用的线边仓；不存在时记录告警并跳过扣减。 */
    private boolean hasLineSideWarehouse(MfgWorkReport report) {
        if (findLineSideWarehouse() != null) {
            return true;
        }
        log.warn("未找到启用的线边仓，跳过报工实耗扣减：报工ID={}", report.getReportId());
        return false;
    }

    /** 按「物料:批次」汇总本次实际占用（实耗 + 报废），忽略缺物料/批次及非正数行。 */
    private Map<String, BigDecimal> requestedConsumptions(List<MfgReportMaterial> materials) {
        Map<String, BigDecimal> requested = new LinkedHashMap<>();
        for (MfgReportMaterial material : materials) {
            if (material.getMaterialId() == null || material.getBatchId() == null) {
                continue;
            }
            BigDecimal quantity = zero(material.getUsedQuantity()).add(zero(material.getScrapQuantity()));
            if (quantity.signum() <= 0) {
                continue;
            }
            requested.merge(material.getMaterialId() + ":" + material.getBatchId(), quantity, BigDecimal::add);
        }
        return requested;
    }

    /** 扣减单个物料批次的线边占用并写实耗流水；线边没有该物料占用时跳过。 */
    private void consumeOneEntry(MfgWorkReport report, MfgWorkOrder workOrder, Long operatorId,
                                 LocalDateTime now, Map.Entry<String, BigDecimal> entry) {
        String[] parts = entry.getKey().split(":");
        Long materialId = Long.valueOf(parts[0]);
        Long batchId = Long.valueOf(parts[1]);
        BigDecimal quantity = entry.getValue();
        // 同一物料批次可能分散在多个线边库位。按本工单在所有启用线边仓里的占用扣，
        // 不能只看最早建的那一个仓，否则后建的线边仓会被当成没移库。
        List<WmsInventory> lineInventories = wmsInventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .inSql(WmsInventory::getWarehouseId, """
                        SELECT warehouse_id FROM wms_warehouse
                        WHERE warehouse_type='LINE_SIDE' AND status='ENABLE' AND deleted_flag=0
                        """)
                .eq(WmsInventory::getWorkOrderId, workOrder.getWorkOrderId())
                .eq(WmsInventory::getMaterialId, materialId)
                .eq(WmsInventory::getBatchId, batchId)
                .eq(WmsInventory::getDeletedFlag, 0)
                .orderByAsc(WmsInventory::getLocationId)
                .last("FOR UPDATE"));
        if (lineInventories.isEmpty()) {
            long anyLineInventory = wmsInventoryMapper.selectCount(new LambdaQueryWrapper<WmsInventory>()
                    .inSql(WmsInventory::getWarehouseId, """
                            SELECT warehouse_id FROM wms_warehouse
                            WHERE warehouse_type='LINE_SIDE' AND status='ENABLE' AND deleted_flag=0
                            """)
                    .eq(WmsInventory::getWorkOrderId, workOrder.getWorkOrderId())
                    .eq(WmsInventory::getMaterialId, materialId)
                    .eq(WmsInventory::getDeletedFlag, 0));
            if (anyLineInventory == 0) {
                // 旧数据或未做移库的工单，线边没有该物料，跳过扣减。
                log.warn("线边仓没有该物料占用，跳过报工实耗扣减：工单ID={}，报工ID={}，物料ID={}",
                        workOrder.getWorkOrderId(), report.getReportId(), materialId);
            }
            return;
        }
        BigDecimal locked = lineInventories.stream()
                .map(i -> zero(i.getLockedQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (locked.compareTo(quantity) < 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "线边库存不足：物料ID=" + materialId + "，批次ID=" + batchId
                            + "，可用占用=" + locked.toPlainString() + "，本次消耗=" + quantity.toPlainString());
        }
        BigDecimal remainingConsumption = quantity;
        for (WmsInventory balance : lineInventories) {
            if (remainingConsumption.signum() == 0) {
                break;
            }
            BigDecimal consumedHere = zero(balance.getLockedQuantity()).min(remainingConsumption);
            if (consumedHere.signum() <= 0) {
                continue;
            }
            WmsInventory update = new WmsInventory();
            update.setInventoryId(balance.getInventoryId());
            update.setVersion(balance.getVersion());
            update.setLockedQuantity(zero(balance.getLockedQuantity()).subtract(consumedHere));
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (wmsInventoryMapper.updateById(update) != 1) {
                throw business(ResultCode.DATA_CONFLICT, "线边库存已被其他请求修改，请刷新后重试");
            }
            WmsStockMovement movement = new WmsStockMovement();
            movement.setMovementNo("WMS-CONSUME-" + report.getReportId() + "-" + balance.getInventoryId());
            movement.setMovementType("CONSUME");
            movement.setSourceOrderType("WORK_REPORT");
            movement.setSourceOrderId(report.getReportId());
            movement.setMaterialId(materialId);
            movement.setBatchId(batchId);
            movement.setWarehouseId(balance.getWarehouseId());
            movement.setLocationId(balance.getLocationId());
            movement.setWorkOrderId(workOrder.getWorkOrderId());
            movement.setQuantity(consumedHere);
            movement.setDirection("OUT");
            movement.setOperationTime(now);
            movement.setRemark("报工实耗扣减线边占用");
            movement.setVersion(0);
            movement.setCreatedBy(operatorId);
            movement.setCreatedTime(now);
            movement.setUpdatedBy(operatorId);
            movement.setUpdatedTime(now);
            movement.setDeletedFlag(0);
            if (wmsStockMovementMapper.insert(movement) != 1) {
                throw business(ResultCode.FAILED, "报工实耗流水写入失败");
            }
            remainingConsumption = remainingConsumption.subtract(consumedHere);
        }
    }

    /**
     * 末工序审核通过后，把合格产出登记到线边仓作为该工单的待检占用。
     * 产品批次由 generateQualityChain 生成；没有产品批次说明不是末工序，直接跳过。
     */
    private void outputLineSideProducts(MfgWorkReport report, MfgWorkOrder workOrder,
                                        Long operatorId, LocalDateTime now) {
        OutputContext context = outputContext(report, workOrder);
        if (context == null) {
            return;
        }
        WmsMaterialBatch batch = ensureOutputBatch(context, operatorId, now);
        registerLineSideOutput(context, workOrder, batch, operatorId, now);
    }

    /** 校验依赖、合格量与幂等，再确认线边仓与库位；任一项不满足返回 null。 */
    private OutputContext outputContext(MfgWorkReport report, MfgWorkOrder workOrder) {
        ReportedOutput reported = reportedOutput(report, workOrder);
        OutputContext context = null;
        if (reported != null) {
            WmsWarehouse lineSide = findLineSideWarehouse();
            WmsLocation location = lineSide == null ? null : issuedLineSideLocation(workOrder, lineSide);
            if (location != null && !java.util.Objects.equals(location.getWarehouseId(), lineSide.getWarehouseId())) {
                lineSide = wmsWarehouseMapper.selectById(location.getWarehouseId());
            }
            if (location == null) {
                warnMissingLineSide(report, lineSide);
            } else {
                context = new OutputContext(report.getReportId(), reported.qualified(), reported.productLot(),
                        reported.product(), lineSide, location);
            }
        }
        return context;
    }

    /** 产出登记优先落在本工单实际领料的线边库位，没有领料记录时退回线边仓的默认库位。 */
    private WmsLocation issuedLineSideLocation(MfgWorkOrder workOrder, WmsWarehouse lineSide) {
        List<WmsInventory> issued = wmsInventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWorkOrderId, workOrder.getWorkOrderId())
                .eq(WmsInventory::getDeletedFlag, 0)
                .inSql(WmsInventory::getWarehouseId, """
                        SELECT warehouse_id FROM wms_warehouse
                        WHERE warehouse_type='LINE_SIDE' AND status='ENABLE' AND deleted_flag=0
                        """)
                .orderByAsc(WmsInventory::getInventoryId));
        if (!issued.isEmpty() && issued.get(0).getLocationId() != null) {
            WmsLocation location = wmsLocationMapper.selectById(issued.get(0).getLocationId());
            if (location != null && location.getWarehouseId() != null
                    && !location.getWarehouseId().equals(lineSide.getWarehouseId())) {
                WmsWarehouse actual = wmsWarehouseMapper.selectById(location.getWarehouseId());
                if (actual != null) {
                    return location;
                }
            }
            if (location != null) {
                return location;
            }
        }
        return findLineSideLocation(lineSide.getWarehouseId());
    }

    /** 线边仓或库位缺失时的告警，两种情况文案不同。 */
    private void warnMissingLineSide(MfgWorkReport report, WmsWarehouse lineSide) {
        if (lineSide == null) {
            log.warn("未找到启用的线边仓，跳过线边产出登记：报工ID={}", report.getReportId());
        } else {
            log.warn("未找到可用的线边库位，跳过线边产出登记：报工ID={}", report.getReportId());
        }
    }

    /** 合格量、产品批次、幂等与成品物料映射校验；通过则返回产出要素。 */
    private ReportedOutput reportedOutput(MfgWorkReport report, MfgWorkOrder workOrder) {
        ReportedOutput output = null;
        if (outputDependenciesReady(report)) {
            BigDecimal qualified = zero(report.getQualifiedQuantity());
            TraceProductLot productLot = qualified.signum() > 0 ? productLotOf(report) : null;
            // 末工序以外的工序不产出成品；同一报工已经登记过产出时也跳过。
            boolean shouldProduce = productLot != null && !isAlreadyOutput(report);
            MdmProduct product = shouldProduce ? productOf(workOrder) : null;
            if (isOutputProduct(product)) {
                output = new ReportedOutput(qualified, productLot, product);
            } else if (shouldProduce) {
                log.warn("工单产品没有成品物料映射，跳过线边产出登记：工单ID={}，产品ID={}",
                        workOrder.getWorkOrderId(), workOrder.getProductId());
            }
        }
        return output;
    }

    /** 产品是否有可用的成品物料映射。 */
    private boolean isOutputProduct(MdmProduct product) {
        return product != null && product.getMaterialId() != null;
    }

    /** 取报工对应的产品批次档案。 */
    private TraceProductLot productLotOf(MfgWorkReport report) {
        return traceProductLotMapper.selectOne(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getSourceReportId, report.getReportId())
                .eq(TraceProductLot::getDeletedFlag, 0));
    }

    /** 取工单对应的产品档案。 */
    private MdmProduct productOf(MfgWorkOrder workOrder) {
        return mdmProductMapper.selectOne(new LambdaQueryWrapper<MdmProduct>()
                .eq(MdmProduct::getProductId, workOrder.getProductId())
                .eq(MdmProduct::getDeletedFlag, 0));
    }

    /** 线边产出登记所需的 mapper 与报工主键是否齐备。 */
    private boolean outputDependenciesReady(MfgWorkReport report) {
        return outputMappersReady() && report.getReportId() != null;
    }

    private boolean outputMappersReady() {
        return wmsInventoryMapper != null && wmsStockMovementMapper != null && mdmProductMapper != null
                && wmsMaterialBatchMapper != null;
    }

    /** 同一报工是否已经登记过产出流水。 */
    private boolean isAlreadyOutput(MfgWorkReport report) {
        long output = wmsStockMovementMapper.selectCount(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getMovementType, "REPORT_OUTPUT")
                .eq(WmsStockMovement::getSourceOrderType, "WORK_REPORT")
                .eq(WmsStockMovement::getSourceOrderId, report.getReportId())
                .eq(WmsStockMovement::getDeletedFlag, 0));

        return output > 0;
    }

    /** 取成品批次，不存在时按产品批次档案新建。 */
    private WmsMaterialBatch ensureOutputBatch(OutputContext context, Long operatorId, LocalDateTime now) {
        String batchNo = "FG-" + context.productLot().getLotNo();
        WmsMaterialBatch batch = wmsMaterialBatchMapper.selectOne(new LambdaQueryWrapper<WmsMaterialBatch>()
                .eq(WmsMaterialBatch::getBatchNo, batchNo)
                .eq(WmsMaterialBatch::getDeletedFlag, 0));
        if (batch != null) {
            return batch;
        }
        WmsMaterialBatch created = new WmsMaterialBatch();
        created.setBatchNo(batchNo);
        created.setMaterialId(context.product().getMaterialId());
        created.setProductionDate(java.time.LocalDate.now());
        created.setQualityStatus(context.productLot().getStatus());
        created.setStatus("AVAILABLE");
        created.setSourceOrderType("PRODUCT_LOT");
        created.setSourceOrderId(context.productLot().getProductLotId());
        created.setSourceProductLotId(context.productLot().getProductLotId());
        created.setRemark("由末工序审核报工自动生成");
        created.setVersion(0);
        created.setCreatedBy(operatorId);
        created.setCreatedTime(now);
        created.setUpdatedBy(operatorId);
        created.setUpdatedTime(now);
        created.setDeletedFlag(0);
        if (wmsMaterialBatchMapper.insert(created) != 1 || created.getBatchId() == null) {
            throw business(ResultCode.FAILED, "线边产出成品批次生成失败");
        }
        return created;
    }

    /** 线边库存已有则加锁定量，没有则新建一条待检产出库存。 */
    private void registerLineSideOutput(OutputContext context, MfgWorkOrder workOrder, WmsMaterialBatch batch,
                                        Long operatorId, LocalDateTime now) {
        WmsInventory lineInventory = wmsInventoryMapper.selectOne(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWarehouseId, context.lineSide().getWarehouseId())
                .eq(WmsInventory::getLocationId, context.location().getLocationId())
                .eq(WmsInventory::getMaterialId, context.product().getMaterialId())
                .eq(WmsInventory::getBatchId, batch.getBatchId())
                .eq(WmsInventory::getWorkOrderId, workOrder.getWorkOrderId())
                .eq(WmsInventory::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (lineInventory == null) {
            createLineSideOutputInventory(context, workOrder, batch, operatorId, now);
        } else {
            increaseLockedQuantity(lineInventory, context.qualified(), operatorId, now);
        }
        insertOutputMovement(context, workOrder, batch, operatorId, now);
    }

    private void createLineSideOutputInventory(OutputContext context, MfgWorkOrder workOrder, WmsMaterialBatch batch,
                                               Long operatorId, LocalDateTime now) {
        WmsInventory created = new WmsInventory();
        created.setWarehouseId(context.lineSide().getWarehouseId());
        created.setLocationId(context.location().getLocationId());
        created.setMaterialId(context.product().getMaterialId());
        created.setBatchId(batch.getBatchId());
        created.setWorkOrderId(workOrder.getWorkOrderId());
        created.setAvailableQuantity(BigDecimal.ZERO);
        created.setLockedQuantity(context.qualified());
        created.setInspectionQuantity(BigDecimal.ZERO);
        created.setRemark("线边待检产出");
        created.setVersion(0);
        created.setCreatedBy(operatorId);
        created.setCreatedTime(now);
        created.setUpdatedBy(operatorId);
        created.setUpdatedTime(now);
        created.setDeletedFlag(0);
        if (wmsInventoryMapper.insert(created) != 1) {
            throw business(ResultCode.FAILED, "线边产出库存创建失败");
        }
    }

    private void increaseLockedQuantity(WmsInventory lineInventory, BigDecimal qualified, Long operatorId,
                                        LocalDateTime now) {
        WmsInventory update = new WmsInventory();
        update.setInventoryId(lineInventory.getInventoryId());
        update.setVersion(lineInventory.getVersion());
        update.setLockedQuantity(zero(lineInventory.getLockedQuantity()).add(qualified));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (wmsInventoryMapper.updateById(update) != 1) {
            throw business(ResultCode.DATA_CONFLICT, "线边库存已被其他请求修改，请刷新后重试");
        }
    }

    private void insertOutputMovement(OutputContext context, MfgWorkOrder workOrder, WmsMaterialBatch batch,
                                      Long operatorId, LocalDateTime now) {
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo("WMS-OUTPUT-" + context.reportId());
        movement.setMovementType("REPORT_OUTPUT");
        movement.setSourceOrderType("WORK_REPORT");
        movement.setSourceOrderId(context.reportId());
        movement.setMaterialId(context.product().getMaterialId());
        movement.setBatchId(batch.getBatchId());
        movement.setWarehouseId(context.lineSide().getWarehouseId());
        movement.setLocationId(context.location().getLocationId());
        movement.setWorkOrderId(workOrder.getWorkOrderId());
        movement.setQuantity(context.qualified());
        movement.setDirection("IN");
        movement.setOperationTime(now);
        movement.setRemark("报工合格产出登记线边待检量");
        movement.setVersion(0);
        movement.setCreatedBy(operatorId);
        movement.setCreatedTime(now);
        movement.setUpdatedBy(operatorId);
        movement.setUpdatedTime(now);
        movement.setDeletedFlag(0);
        if (wmsStockMovementMapper.insert(movement) != 1) {
            throw business(ResultCode.FAILED, "线边产出流水写入失败");
        }
    }

    private WmsWarehouse findLineSideWarehouse() {
        if (wmsWarehouseMapper == null) {
            return null;
        }
        List<WmsWarehouse> warehouses = wmsWarehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseType, "LINE_SIDE")
                .eq(WmsWarehouse::getStatus, BusinessStatus.Common.ENABLE)
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .orderByAsc(WmsWarehouse::getWarehouseId));
        return warehouses.isEmpty() ? null : warehouses.get(0);
    }

    private WmsLocation findLineSideLocation(Long warehouseId) {
        if (wmsLocationMapper == null || warehouseId == null) {
            return null;
        }
        List<WmsLocation> locations = wmsLocationMapper.selectList(new LambdaQueryWrapper<WmsLocation>()
                .eq(WmsLocation::getWarehouseId, warehouseId)
                .in(WmsLocation::getStatus, List.of("IDLE", "OCCUPIED", "ENABLE"))
                .eq(WmsLocation::getDeletedFlag, 0)
                .orderByAsc(WmsLocation::getLocationId));
        return locations.isEmpty() ? null : locations.get(0);
    }

    /**
     * 查询该工单在线边仓对指定物料、批次的占用量。
     * 同一物料批次可能分散在多个线边库位，按所有启用线边仓汇总；
     * 返回 null 表示线边仓没有该物料的库存记录，调用方应回退到旧的领料量算法。
     */
    private BigDecimal findLineSideLockedQuantity(Long workOrderId, Long materialId, Long batchId) {
        if (wmsInventoryMapper == null || workOrderId == null) {
            return null;
        }
        List<WmsInventory> inventories = wmsInventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .inSql(WmsInventory::getWarehouseId, """
                        SELECT warehouse_id FROM wms_warehouse
                        WHERE warehouse_type='LINE_SIDE' AND status='ENABLE' AND deleted_flag=0
                        """)
                .eq(WmsInventory::getWorkOrderId, workOrderId)
                .eq(WmsInventory::getMaterialId, materialId)
                .eq(WmsInventory::getBatchId, batchId)
                .eq(WmsInventory::getDeletedFlag, 0)
                .orderByAsc(WmsInventory::getLocationId));
        if (inventories.isEmpty()) {
            return null;
        }
        return inventories.stream().map(item -> zero(item.getLockedQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private MfgWorkOrder validateApprovalQuantity(MfgWorkReport report) {
        MfgWorkOrder workOrder = requireApprovableWorkOrder(report);
        productQuantities.validate(workOrder.getProductId(), zero(report.getQualifiedQuantity()), true);
        productQuantities.validate(workOrder.getProductId(), zero(report.getDefectQuantity()), true);
        BigDecimal currentApproved = normalFinalReportedQuantity(workOrder);
        MfgTask task = activeTask(report.getTaskId());
        BigDecimal candidate = isNormalFinalTask(workOrder, task)
                ? currentApproved.add(zero(report.getQualifiedQuantity())) : currentApproved;
        if (candidate.compareTo(workOrder.getPlannedQuantity()) > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "累计审核合格数量不能超过工单计划数量");
        }
        return workOrder;
    }

    /** 报工必须关联有效任务与可审核状态的工单，否则抛业务异常。 */
    private MfgWorkOrder requireApprovableWorkOrder(MfgWorkReport report) {
        MfgTask task = activeTask(report.getTaskId());
        if (!hasTaskWithWorkOrder(task)) {
            throw business(ResultCode.BUSINESS_ERROR, "报工未关联有效生产任务和工单");
        }
        MfgWorkOrder workOrder = activeWorkOrder(task.getWorkOrderId());
        if (!isApprovableWorkOrder(workOrder)) {
            throw business(ResultCode.BUSINESS_ERROR, "报工关联的生产工单不存在或当前状态不可审核");
        }
        return workOrder;
    }

    private boolean hasTaskWithWorkOrder(MfgTask task) {
        return task != null && task.getWorkOrderId() != null;
    }

    private boolean isApprovableWorkOrder(MfgWorkOrder workOrder) {
        return workOrder != null
                && List.of(BusinessStatus.Document.RELEASED, BusinessStatus.Production.RUNNING)
                        .contains(workOrder.getStatus());
    }

    /** 末工序的正常任务才累加审核合格量，返工任务不计入。 */
    private boolean isNormalFinalTask(MfgWorkOrder workOrder, MfgTask task) {
        return isFinalOperation(workOrder, task.getOperationId())
                && (!StringUtils.hasText(task.getTaskType()) || "NORMAL".equals(task.getTaskType()));
    }

    private void aggregateWorkOrder(MfgWorkOrder workOrder, Long operatorId, LocalDateTime now) {
        BigDecimal completed = acceptedProductLotQuantity(workOrder);
        if (completed.compareTo(workOrder.getPlannedQuantity()) > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "累计质量放行数量不能超过工单计划数量");
        }
        String oldStatus = workOrder.getStatus();
        String newStatus;
        if (completed.compareTo(workOrder.getPlannedQuantity()) >= 0) {
            newStatus = BusinessStatus.Production.FINISHED;
        } else if (completed.signum() > 0 || BusinessStatus.Production.FINISHED.equals(oldStatus)) {
            newStatus = BusinessStatus.Production.RUNNING;
        } else {
            newStatus = oldStatus;
        }
        MfgWorkOrder update = new MfgWorkOrder();
        update.setWorkOrderId(workOrder.getWorkOrderId());
        update.setCompletedQuantity(completed);
        update.setStatus(newStatus);
        update.setVersion(workOrder.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (mfgWorkOrderMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "工单完成数量已被其他请求修改，请刷新后重试");
        }
        if (!java.util.Objects.equals(oldStatus, newStatus)) {
            insertStatusChangeLog(workOrder.getWorkOrderId(), oldStatus, newStatus, "MFG_WORK_ORDER",
                    "AGGREGATE_RELEASED_PRODUCT_LOTS", operatorId, null, now);
        }
        insertOperationLog(workOrder.getWorkOrderId(), "AGGREGATE_WORK_ORDER_ACCEPTED_QUANTITY", "mfg_work_order",
                operatorId, "按质量放行产品批次重算完成数量=" + completed.toPlainString(), now);
        if (BusinessStatus.Production.FINISHED.equals(newStatus)) {
            finishPlanAndOrderIfComplete(workOrder.getPlanId(), operatorId, now);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recalculateWorkOrderCompletion(Long workOrderId, Long operatorId, String reason) {
        if (workOrderId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产工单和操作人不能为空");
        }
        MfgWorkOrder workOrder = activeWorkOrder(workOrderId);
        if (workOrder == null || BusinessStatus.Document.CLOSED.equals(workOrder.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单不存在或已关闭，不能重新计算完成数量");
        }
        LocalDateTime now = LocalDateTime.now();
        aggregateWorkOrder(workOrder, operatorId, now);
        insertOperationLog(workOrderId, "RECALCULATE_WORK_ORDER_AFTER_QUALITY", "mfg_work_order",
                operatorId, reason, now);
    }

    private void finishPlanAndOrderIfComplete(Long planId, Long operatorId, LocalDateTime now) {
        MfgProductionPlan plan = activePlan(planId);
        if (plan == null || BusinessStatus.Document.CLOSED.equals(plan.getStatus())) {
            return;
        }
        LambdaQueryWrapper<MfgWorkOrder> workOrderQuery = new LambdaQueryWrapper<>();
        workOrderQuery.eq(MfgWorkOrder::getPlanId, planId)
                .eq(MfgWorkOrder::getDeletedFlag, 0)
                .ne(MfgWorkOrder::getStatus, BusinessStatus.Document.CLOSED);
        List<MfgWorkOrder> workOrders = mfgWorkOrderMapper.selectList(workOrderQuery);
        if (workOrders.isEmpty() || workOrders.stream().anyMatch(item ->
                !BusinessStatus.Production.FINISHED.equals(item.getStatus()))
                || workOrders.stream().map(MfgWorkOrder::getPlannedQuantity).map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(plan.getPlannedQuantity()) < 0) {
            return;
        }
        if (!BusinessStatus.Production.FINISHED.equals(plan.getStatus())) {
            MfgProductionPlan update = new MfgProductionPlan();
            update.setPlanId(planId);
            update.setStatus(BusinessStatus.Production.FINISHED);
            update.setVersion(plan.getVersion());
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (mfgProductionPlanMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "生产计划已被其他请求修改，请刷新后重试");
            }
            insertStatusChangeLog(planId, plan.getStatus(), BusinessStatus.Production.FINISHED,
                    "MFG_PRODUCTION_PLAN", "ALL_WORK_ORDERS_FINISHED", operatorId, null, now);
        }
        finishProductionOrderIfComplete(plan.getProductionOrderId(), operatorId, now);
    }

    private void finishProductionOrderIfComplete(Long productionOrderId, Long operatorId, LocalDateTime now) {
        MfgProductionOrder order = activeOrder(productionOrderId);
        if (order == null || BusinessStatus.Document.CLOSED.equals(order.getStatus())) {
            return;
        }
        LambdaQueryWrapper<MfgProductionPlan> planQuery = new LambdaQueryWrapper<>();
        planQuery.eq(MfgProductionPlan::getProductionOrderId, productionOrderId)
                .eq(MfgProductionPlan::getDeletedFlag, 0)
                .ne(MfgProductionPlan::getStatus, BusinessStatus.Document.CLOSED);
        List<MfgProductionPlan> plans = mfgProductionPlanMapper.selectList(planQuery);
        if (plans.isEmpty() || plans.stream().anyMatch(item ->
                !BusinessStatus.Production.FINISHED.equals(item.getStatus()))
                || plans.stream().map(MfgProductionPlan::getPlannedQuantity).map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(order.getOrderQuantity()) < 0) {
            return;
        }
        if (!BusinessStatus.Production.FINISHED.equals(order.getStatus())) {
            MfgProductionOrder update = new MfgProductionOrder();
            update.setProductionOrderId(productionOrderId);
            update.setStatus(BusinessStatus.Production.FINISHED);
            update.setVersion(order.getVersion());
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (mfgProductionOrderMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "生产订单已被其他请求修改，请刷新后重试");
            }
            insertStatusChangeLog(productionOrderId, order.getStatus(), BusinessStatus.Production.FINISHED,
                    "MFG_PRODUCTION_ORDER", "ALL_PRODUCTION_PLANS_FINISHED", operatorId, null, now);
        }
    }

    private BigDecimal normalFinalReportedQuantity(MfgWorkOrder workOrder) {
        Long finalOperationId = finalOperationId(workOrder);
        LambdaQueryWrapper<MfgWorkReport> query = new LambdaQueryWrapper<>();
        query.eq(MfgWorkReport::getDeletedFlag, 0)
                .eq(MfgWorkReport::getAuditStatus, APPROVED_STATUS)
                .inSql(MfgWorkReport::getTaskId,
                        "SELECT task_id FROM mfg_task WHERE deleted_flag = 0 AND work_order_id = "
                                + workOrder.getWorkOrderId() + " AND operation_id = " + finalOperationId
                                + " AND COALESCE(task_type, 'NORMAL') = 'NORMAL'");
        return baseMapper.selectList(query).stream()
                .map(MfgWorkReport::getQualifiedQuantity)
                .map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal acceptedProductLotQuantity(MfgWorkOrder workOrder) {
        return traceProductLotMapper.selectList(new LambdaQueryWrapper<TraceProductLot>()
                        .eq(TraceProductLot::getWorkOrderId, workOrder.getWorkOrderId())
                        .eq(TraceProductLot::getDeletedFlag, 0)
                        .in(TraceProductLot::getStatus, BusinessStatus.Quality.PASS,
                                BusinessStatus.Quality.CONCESSION, BusinessStatus.Quality.REWORK,
                                BusinessStatus.Quality.DOWNGRADE, "SCRAPPED", "STORED"))
                .stream().map(this::acceptedProductLotQuantity).map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal acceptedProductLotQuantity(TraceProductLot productLot) {
        if (BusinessStatus.Quality.CONCESSION.equals(productLot.getStatus())) {
            BigDecimal batchQuantity = productLot.getBatchQuantity();
            return batchQuantity == null
                    ? zero(productLot.getQualifiedQuantity()).add(zero(productLot.getDefectQuantity()))
                    : batchQuantity;
        }
        return productLot.getQualifiedQuantity();
    }

    private void generateQualityChain(MfgWorkReport report, MfgWorkOrder workOrder,
                                      Long operatorId, LocalDateTime now) {
        MfgTask task = activeTask(report.getTaskId());
        if (task == null) return;
        if (isFinalOperation(workOrder, task.getOperationId())) {
            generateFinalQualityChain(report, workOrder, operatorId, now);
        } else {
            generateProcessInspection(report, task, workOrder, operatorId, now);
        }
    }

    private void generateProcessInspection(MfgWorkReport report, MfgTask task, MfgWorkOrder workOrder,
                                           Long operatorId, LocalDateTime now) {
        if (zero(report.getQualifiedQuantity()).add(zero(report.getDefectQuantity())).signum() <= 0) return;
        long activeInspection = qmsInspectionOrderMapper.selectCount(new LambdaQueryWrapper<QmsInspectionOrder>()
                .eq(QmsInspectionOrder::getReportId, report.getReportId())
                .eq(QmsInspectionOrder::getInspectionType, "PROCESS")
                .eq(QmsInspectionOrder::getDeletedFlag, 0)
                .ne(QmsInspectionOrder::getStatus, BusinessStatus.Document.CLOSED));
        if (activeInspection > 0) return;
        List<QmsInspectionScheme> schemes = workOrder.getSnapshotJson()!=null && snapshotPolicy.isCurrentFormat(workOrder)
                ? snapshotPolicy.inspectionSchemes(workOrder,task.getOperationId(),"PROCESS") : qmsInspectionSchemeMapper.selectList(
                new LambdaQueryWrapper<QmsInspectionScheme>()
                        .eq(QmsInspectionScheme::getProductId, workOrder.getProductId())
                        .eq(QmsInspectionScheme::getOperationId, task.getOperationId())
                        .eq(QmsInspectionScheme::getInspectionType, "PROCESS")
                        .eq(QmsInspectionScheme::getStatus, BusinessStatus.Document.RELEASED)
                        .eq(QmsInspectionScheme::getDeletedFlag, 0));
        if (schemes.isEmpty()) return;
        if (schemes.size() > 1) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "当前工序存在多个已发布过程检方案，请先消除主数据歧义");
        }
        QmsInspectionOrder inspection = new QmsInspectionOrder();
        inspection.setInspectionNo(numbers.nextFor("QI","TASK",report.getTaskId()));
        inspection.setInspectionType("PROCESS");
        inspection.setSchemeId(schemes.get(0).getSchemeId());
        inspection.setWorkOrderId(workOrder.getWorkOrderId());
        inspection.setTaskId(task.getTaskId());
        inspection.setReportId(report.getReportId());
        inspection.setSampleQuantity(BigDecimal.ZERO);
        inspection.setQualifiedQuantity(BigDecimal.ZERO);
        inspection.setUnqualifiedQuantity(BigDecimal.ZERO);
        inspection.setStatus(BusinessStatus.Document.DRAFT);
        inspection.setRemark("由需检工序审核报工自动生成过程检单");
        inspection.setVersion(0);
        inspection.setCreatedBy(operatorId);
        inspection.setCreatedTime(now);
        inspection.setUpdatedBy(operatorId);
        inspection.setUpdatedTime(now);
        inspection.setDeletedFlag(0);
        if (qmsInspectionOrderMapper.insert(inspection) == 0 || inspection.getInspectionId() == null) {
            throw business(ResultCode.FAILED, "过程检单生成失败");
        }
        insertStatusChangeLog(inspection.getInspectionId(), null, BusinessStatus.Document.DRAFT,
                "QMS_INSPECTION_ORDER", "GENERATE_PROCESS_INSPECTION_FROM_REPORT", operatorId,
                "来源报工ID=" + report.getReportId(), now);
        if (qmsResponsibilityNotificationService != null) {
            qmsResponsibilityNotificationService.publishOpenInspection(inspection, operatorId);
        }
    }

    private boolean isFinalOperation(MfgWorkOrder workOrder, Long operationId) {
        return operationId != null && operationId.equals(finalOperationId(workOrder));
    }

    private Long finalOperationId(MfgWorkOrder workOrder) {
        if (workOrder == null || workOrder.getRouteId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "生产工单未关联有效工艺路线，不能汇总完成数量");
        }
        Long frozenOperationId = frozenFinalOperationId(workOrder);
        return frozenOperationId != null ? frozenOperationId : loadedFinalOperationId(workOrder);
    }

    /** 快照为当前格式时直接取冻结的末工序，否则返回 null 走实时路线查询。 */
    private Long frozenFinalOperationId(MfgWorkOrder workOrder) {
        if (workOrder.getSnapshotJson() == null || !snapshotPolicy.isCurrentFormat(workOrder)) {
            return null;
        }
        var frozen = snapshotPolicy.routeItems(workOrder);
        return frozen.getLast().getOperationId();
    }

    /** 从工艺路线明细里识别唯一末工序。 */
    private Long loadedFinalOperationId(MfgWorkOrder workOrder) {
        List<ProcessRouteItem> finalItems = processRouteItemMapper.selectList(
                new LambdaQueryWrapper<ProcessRouteItem>()
                        .eq(ProcessRouteItem::getRouteId, workOrder.getRouteId())
                        .eq(ProcessRouteItem::getDeletedFlag, 0)
                        .orderByDesc(ProcessRouteItem::getSequenceNo, ProcessRouteItem::getRouteItemId));
        if (finalItems.isEmpty() || finalItems.get(0).getOperationId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "工艺路线没有可识别的末工序，不能汇总完成数量");
        }
        Integer maxSequence = finalItems.get(0).getSequenceNo();
        if (maxSequence == null || hasMultipleFinalOperations(finalItems, maxSequence)) {
            throw business(ResultCode.BUSINESS_ERROR, "工艺路线存在多个末工序且未配置并行汇总规则");
        }
        return finalItems.get(0).getOperationId();
    }

    private boolean hasMultipleFinalOperations(List<ProcessRouteItem> finalItems, Integer maxSequence) {
        long finalCount = finalItems.stream()
                .filter(item -> java.util.Objects.equals(maxSequence, item.getSequenceNo()))
                .count();
        return finalCount != 1;
    }

    private void generateFinalQualityChain(MfgWorkReport report, MfgWorkOrder workOrder,
                                           Long operatorId, LocalDateTime now) {
        MfgTask task = activeTask(report.getTaskId());
        if (task == null || !isFinalOperation(workOrder, task.getOperationId())
                || zero(report.getQualifiedQuantity()).add(zero(report.getDefectQuantity())).signum() <= 0) {
            return;
        }
        TraceProductLot productLot = traceProductLotMapper.selectOne(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getSourceReportId, report.getReportId())
                .eq(TraceProductLot::getDeletedFlag, 0));
        if (productLot == null) {
            productLot = new TraceProductLot();
            productLot.setLotNo(numbers.nextFor("LP","WORK_ORDER",workOrder.getWorkOrderId()));
            productLot.setProductId(workOrder.getProductId());
            productLot.setWorkOrderId(workOrder.getWorkOrderId());
            productLot.setSourceReportId(report.getReportId());
            productLot.setBatchQuantity(zero(report.getQualifiedQuantity()).add(zero(report.getDefectQuantity())));
            productLot.setQualifiedQuantity(zero(report.getQualifiedQuantity()));
            productLot.setDefectQuantity(zero(report.getDefectQuantity()));
            productLot.setStatus("WAIT_INSPECTION");
            productLot.setRemark("由末工序审核报工自动结批");
            productLot.setVersion(0);
            productLot.setCreatedBy(operatorId);
            productLot.setCreatedTime(now);
            productLot.setUpdatedBy(operatorId);
            productLot.setUpdatedTime(now);
            productLot.setDeletedFlag(0);
            if (traceProductLotMapper.insert(productLot) == 0 || productLot.getProductLotId() == null) {
                throw business(ResultCode.FAILED, "末工序报工生成产品批次失败");
            }
            insertStatusChangeLog(productLot.getProductLotId(), null, "WAIT_INSPECTION",
                    "TRACE_PRODUCT_LOT", "GENERATE_PRODUCT_LOT_FROM_REPORT", operatorId,
                    "来源报工ID=" + report.getReportId(), now);
        }
        generateTraceMaterialUsages(productLot, report, workOrder, operatorId, now);
        generateFinalInspection(productLot, report, task, workOrder, operatorId, now);
    }

    private void generateTraceMaterialUsages(TraceProductLot productLot, MfgWorkReport report,
                                             MfgWorkOrder workOrder, Long operatorId, LocalDateTime now) {
        List<MfgReportMaterial> materials = mfgReportMaterialMapper.selectList(
                new LambdaQueryWrapper<MfgReportMaterial>()
                        .eq(MfgReportMaterial::getReportId, report.getReportId())
                        .eq(MfgReportMaterial::getDeletedFlag, 0));
        for (MfgReportMaterial material : materials) {
            long existing = traceMaterialUsageMapper.selectCount(new LambdaQueryWrapper<TraceMaterialUsage>()
                    .eq(TraceMaterialUsage::getProductLotId, productLot.getProductLotId())
                    .eq(TraceMaterialUsage::getReportId, report.getReportId())
                    .eq(TraceMaterialUsage::getMaterialId, material.getMaterialId())
                    .eq(TraceMaterialUsage::getBatchId, material.getBatchId())
                    .eq(TraceMaterialUsage::getDeletedFlag, 0));
            if (existing > 0) {
                continue;
            }
            TraceMaterialUsage usage = new TraceMaterialUsage();
            usage.setProductLotId(productLot.getProductLotId());
            usage.setWorkOrderId(workOrder.getWorkOrderId());
            usage.setReportId(report.getReportId());
            usage.setMaterialId(material.getMaterialId());
            usage.setBatchId(material.getBatchId());
            usage.setUsedQuantity(zero(material.getUsedQuantity()).add(zero(material.getScrapQuantity())));
            usage.setRemark("由末工序审核报工自动生成");
            usage.setVersion(0);
            usage.setCreatedBy(operatorId);
            usage.setCreatedTime(now);
            usage.setUpdatedBy(operatorId);
            usage.setUpdatedTime(now);
            usage.setDeletedFlag(0);
            if (traceMaterialUsageMapper.insert(usage) == 0) {
                throw business(ResultCode.FAILED, "产品批次物料追溯生成失败");
            }
        }
    }

    private void generateFinalInspection(TraceProductLot productLot, MfgWorkReport report, MfgTask task,
                                         MfgWorkOrder workOrder, Long operatorId, LocalDateTime now) {
        long activeInspection = qmsInspectionOrderMapper.selectCount(new LambdaQueryWrapper<QmsInspectionOrder>()
                .eq(QmsInspectionOrder::getProductLotId, productLot.getProductLotId())
                .eq(QmsInspectionOrder::getInspectionType, "FINAL")
                .eq(QmsInspectionOrder::getDeletedFlag, 0)
                .ne(QmsInspectionOrder::getStatus, BusinessStatus.Document.CLOSED));
        if (activeInspection > 0) {
            return;
        }
        // 终检是产品级检验，方案可能不绑定工序（operation_id 为空），因此同时匹配产品级方案和当前末工序方案。
        List<QmsInspectionScheme> schemes = workOrder.getSnapshotJson() != null && snapshotPolicy.isCurrentFormat(workOrder)
                ? snapshotPolicy.inspectionSchemes(workOrder, task.getOperationId(), "FINAL")
                : qmsInspectionSchemeMapper.selectList(
                        new LambdaQueryWrapper<QmsInspectionScheme>()
                                .eq(QmsInspectionScheme::getProductId, workOrder.getProductId())
                                .eq(QmsInspectionScheme::getInspectionType, "FINAL")
                                .eq(QmsInspectionScheme::getStatus, BusinessStatus.Document.RELEASED)
                                .eq(QmsInspectionScheme::getDeletedFlag, 0)
                                .and(w -> w.isNull(QmsInspectionScheme::getOperationId)
                                        .or().eq(QmsInspectionScheme::getOperationId, task.getOperationId())));
        if (schemes.size() != 1) {
            throw business(ResultCode.BUSINESS_ERROR, schemes.isEmpty()
                    ? "产品末工序缺少已发布终检方案，报工不能审核通过"
                    : "产品末工序存在多个已发布终检方案，请先消除主数据歧义");
        }
        QmsInspectionOrder inspection = new QmsInspectionOrder();
        inspection.setInspectionNo(numbers.nextFor("QI","TASK",report.getTaskId()));
        inspection.setInspectionType("FINAL");
        inspection.setSchemeId(schemes.get(0).getSchemeId());
        inspection.setWorkOrderId(workOrder.getWorkOrderId());
        inspection.setTaskId(task.getTaskId());
        inspection.setReportId(report.getReportId());
        inspection.setBatchId(null);
        inspection.setProductLotId(productLot.getProductLotId());
        inspection.setSampleQuantity(BigDecimal.ZERO);
        inspection.setQualifiedQuantity(BigDecimal.ZERO);
        inspection.setUnqualifiedQuantity(BigDecimal.ZERO);
        inspection.setStatus(BusinessStatus.Document.DRAFT);
        inspection.setRemark("由待检产品批次自动生成终检单");
        inspection.setVersion(0);
        inspection.setCreatedBy(operatorId);
        inspection.setCreatedTime(now);
        inspection.setUpdatedBy(operatorId);
        inspection.setUpdatedTime(now);
        inspection.setDeletedFlag(0);
        if (qmsInspectionOrderMapper.insert(inspection) == 0 || inspection.getInspectionId() == null) {
            throw business(ResultCode.FAILED, "产品批次终检单生成失败");
        }
        insertStatusChangeLog(inspection.getInspectionId(), null, BusinessStatus.Document.DRAFT,
                "QMS_INSPECTION_ORDER", "GENERATE_FINAL_INSPECTION_FROM_PRODUCT_LOT", operatorId,
                "产品批次ID=" + productLot.getProductLotId(), now);
        if (qmsResponsibilityNotificationService != null) {
            qmsResponsibilityNotificationService.publishOpenInspection(inspection, operatorId);
        }
    }

    private MfgTask activeTask(Long taskId) {
        return taskId == null ? null : mfgTaskMapper.selectOne(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getTaskId, taskId).eq(MfgTask::getDeletedFlag, 0));
    }

    private MfgTask activeTaskForUpdate(Long taskId) {
        return taskId == null ? null : mfgTaskMapper.selectOne(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getTaskId, taskId)
                .eq(MfgTask::getDeletedFlag, 0)
                .last("FOR UPDATE"));
    }

    private MfgWorkOrder activeWorkOrder(Long workOrderId) {
        MfgWorkOrder row = mfgWorkOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, workOrderId).eq(MfgWorkOrder::getDeletedFlag, 0));
        if (row != null) preparationGate.assertExecutionSupported(row.getProductId());
        return row;
    }

    private MfgProductionPlan activePlan(Long planId) {
        return planId == null ? null : mfgProductionPlanMapper.selectOne(new LambdaQueryWrapper<MfgProductionPlan>()
                .eq(MfgProductionPlan::getPlanId, planId).eq(MfgProductionPlan::getDeletedFlag, 0));
    }

    private MfgProductionOrder activeOrder(Long productionOrderId) {
        return productionOrderId == null ? null : mfgProductionOrderMapper.selectOne(
                new LambdaQueryWrapper<MfgProductionOrder>()
                        .eq(MfgProductionOrder::getProductionOrderId, productionOrderId)
                        .eq(MfgProductionOrder::getDeletedFlag, 0));
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }

    /**
     * 构建生产报工查询条件，默认过滤已删除记录。
     * @param keyword 报工编号关键字
     * @param taskId 任务ID
     * @param operatorId 操作工ID
     * @param stationId 工位ID
     * @param auditStatus 审核状态
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<MfgWorkReport> buildWorkReportQuery(String keyword, Long taskId, Long operatorId,
                                                                   Long stationId, String auditStatus) {
        LambdaQueryWrapper<MfgWorkReport> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MfgWorkReport::getDeletedFlag, 0)
                .eq(taskId != null, MfgWorkReport::getTaskId, taskId)
                .eq(operatorId != null, MfgWorkReport::getOperatorId, operatorId)
                .eq(stationId != null, MfgWorkReport::getStationId, stationId)
                .eq(StringUtils.hasText(auditStatus), MfgWorkReport::getAuditStatus, auditStatus)
                .orderByDesc(MfgWorkReport::getReportTime)
                .orderByDesc(MfgWorkReport::getCreatedTime);
        if (StringUtils.hasText(keyword)) {
            queryWrapper.and(search -> search.like(MfgWorkReport::getReportNo, keyword)
                    .or()
                    .apply("task_id IN (SELECT task_id FROM mfg_task WHERE deleted_flag = 0 "
                            + "AND task_no LIKE CONCAT('%', {0}, '%'))", keyword));
        }
        applyDataScope(queryWrapper);
        return queryWrapper;
    }

    private void applyDataScope(LambdaQueryWrapper<MfgWorkReport> queryWrapper) {
        if (dataScopeService != null) {
            dataScopeService.applyWorkReportScope(queryWrapper);
        }
    }

    private void bindCurrentOperator(MfgWorkReport report, Long userId) {
        if (report == null || userId == null || report.getTaskId() == null) {
            throw business(ResultCode.PARAM_ERROR, "生产任务和当前用户不能为空");
        }
        SysUser user = activeUser(userId);
        boolean superAdmin = isSuperAdmin(user);
        requireEmployeeLinked(user, superAdmin);
        MfgTask task = activeTask(report.getTaskId());
        requireReportableTask(task);
        if (!superAdmin) {
            requireOperatorAssigned(task, user.getEmployeeId());
        }
        if (task.getStationId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "生产任务尚未确定工位，不能提交报工");
        }
        applyReportDefaults(report, task, user, userId);
    }

    /** 非超管账号必须关联员工。 */
    private void requireEmployeeLinked(SysUser user, boolean superAdmin) {
        if (user.getEmployeeId() == null && !superAdmin) {
            throw business(ResultCode.BUSINESS_ERROR, "当前账号未关联员工，不能提交报工");
        }
    }

    /** 只有已开工（运行中或暂停）的任务可以提交报工。 */
    private void requireReportableTask(MfgTask task) {
        if (task == null || !isReportableStatus(task.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已开工任务可以提交报工");
        }
    }

    private boolean isReportableStatus(String status) {
        return BusinessStatus.Production.RUNNING.equals(status) || BusinessStatus.Production.PAUSED.equals(status);
    }

    /** 非超管必须被分配到该任务。 */
    private void requireOperatorAssigned(MfgTask task, Long employeeId) {
        long assigned = mfgTaskOperatorMapper.selectCount(new LambdaQueryWrapper<MfgTaskOperator>()
                .eq(MfgTaskOperator::getTaskId, task.getTaskId())
                .eq(MfgTaskOperator::getEmployeeId, employeeId)
                .eq(MfgTaskOperator::getDeletedFlag, 0));
        if (assigned == 0) {
            throw business(ResultCode.FORBIDDEN, "当前员工未分配到该生产任务");
        }
    }

    /** 用服务端数据覆盖前端传入的报工主键、作业人与工位等字段。 */
    private void applyReportDefaults(MfgWorkReport report, MfgTask task, SysUser user, Long userId) {
        report.setReportId(null);
        report.setReportNo(null);
        report.setOperatorId(user.getEmployeeId() != null ? user.getEmployeeId() : user.getUserId());
        report.setStationId(task.getStationId());
        report.setReportTime(null);
        report.setVersion(0);
        report.setDeletedFlag(0);
        report.setCreatedBy(userId);
        report.setUpdatedBy(userId);
    }

    private boolean isSuperAdmin(SysUser user) {
        if (user == null) {
            return false;
        }
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            return true;
        }
        List<String> roles = sysUserMapper.listRoleCodes(user.getUserId());
        return roles != null && roles.contains("SUPER_ADMIN");
    }

    private SysUser activeUser(Long userId) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUserId, userId)
                .eq(SysUser::getDeletedFlag, 0)
                .eq(SysUser::getStatus, BusinessStatus.Common.ENABLE));
        if (user == null) {
            throw business(ResultCode.UNAUTHORIZED, "当前账号不存在或已停用");
        }
        return user;
    }

    /**
     * 补齐报工默认值。
     * @param workReport 报工实体
     */
    private void prepareWorkReport(MfgWorkReport workReport) {
        workReport.setReportNo(numbers.nextFor("WR","TASK",workReport.getTaskId()));
        if (workReport.getQualifiedQuantity() == null) {
            workReport.setQualifiedQuantity(BigDecimal.ZERO);
        }
        if (workReport.getDefectQuantity() == null) {
            workReport.setDefectQuantity(BigDecimal.ZERO);
        }
        if (workReport.getActualWorkHours() == null) {
            workReport.setActualWorkHours(BigDecimal.ZERO);
        }
        if (workReport.getReportTime() == null) {
            workReport.setReportTime(LocalDateTime.now());
        }
        workReport.setAuditStatus(SUBMITTED_STATUS);
        if (workReport.getDeletedFlag() == null) {
            workReport.setDeletedFlag(0);
        }
    }

    private void validateWorkReport(MfgWorkReport workReport) {
        if (!hasRequiredReportFields(workReport)) {
            throw business(ResultCode.PARAM_ERROR, "生产任务、操作工和工位不能为空");
        }
        validateDefectDescription(workReport);
        BigDecimal qualified = zero(workReport.getQualifiedQuantity());
        BigDecimal defect = zero(workReport.getDefectQuantity());
        BigDecimal workHours = zero(workReport.getActualWorkHours());
        if (hasNegativeQuantity(qualified, defect, workHours)) {
            throw business(ResultCode.PARAM_ERROR, "合格数量、不良数量和实际工时不能为负数");
        }
        if (qualified.add(defect).signum() <= 0) {
            throw business(ResultCode.PARAM_ERROR, "报工数量必须大于 0");
        }
    }

    private void validateDefectDescription(MfgWorkReport report) {
        String description=report.getDefectDescription();
        if (zero(report.getDefectQuantity()).signum()>0 && !StringUtils.hasText(description))
            throw business(ResultCode.PARAM_ERROR,"不良数量大于0时，必须填写不良情况（现象、原因及数量说明）");
        if(description!=null && description.length()>1000) throw business(ResultCode.PARAM_ERROR,"不良情况最多1000字");
    }

    private boolean hasRequiredReportFields(MfgWorkReport workReport) {
        return workReport.getTaskId() != null && workReport.getOperatorId() != null
                && workReport.getStationId() != null;
    }

    private boolean hasNegativeQuantity(BigDecimal qualified, BigDecimal defect, BigDecimal workHours) {
        return isNegative(qualified) || isNegative(defect) || isNegative(workHours);
    }

    private boolean isNegative(BigDecimal value) {
        return value.signum() < 0;
    }

    private void validateTaskReportQuantity(MfgTask task, MfgWorkReport workReport) {
        if (task == null || task.getTaskId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "报工未关联有效生产任务");
        }
        BigDecimal planned = zero(task.getPlannedQuantity());
        if (planned.signum() <= 0) {
            throw business(ResultCode.BUSINESS_ERROR, "生产任务计划数量必须大于 0");
        }
        BigDecimal reported = baseMapper.selectList(new LambdaQueryWrapper<MfgWorkReport>()
                        .eq(MfgWorkReport::getTaskId, task.getTaskId())
                        .eq(MfgWorkReport::getDeletedFlag, 0)
                        .in(MfgWorkReport::getAuditStatus, SUBMITTED_STATUS, APPROVED_STATUS))
                .stream()
                .map(report -> zero(report.getQualifiedQuantity()).add(zero(report.getDefectQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal candidate = reported
                .add(zero(workReport.getQualifiedQuantity()))
                .add(zero(workReport.getDefectQuantity()));
        if (candidate.compareTo(planned) > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "累计报工数量不能超过任务计划数量");
        }
    }

    private void finishTaskIfReportedComplete(Long taskId, Long operatorId, LocalDateTime now) {
        MfgTask task = activeTaskForUpdate(taskId);
        if (task == null || BusinessStatus.Production.FINISHED.equals(task.getStatus())) {
            return;
        }
        if (!(BusinessStatus.Production.RUNNING.equals(task.getStatus())
                || BusinessStatus.Production.PAUSED.equals(task.getStatus()))) {
            return;
        }
        BigDecimal[] quantities = reportedTaskQuantities(taskId);
        BigDecimal planned = zero(task.getPlannedQuantity());
        BigDecimal total = quantities[0].add(quantities[1]);
        if (total.compareTo(planned) > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "累计报工数量不能超过任务计划数量");
        }
        if (planned.signum() <= 0 || total.compareTo(planned) < 0) {
            return;
        }
        MfgTask update = new MfgTask();
        update.setTaskId(taskId);
        update.setVersion(task.getVersion());
        update.setCompletedQuantity(quantities[0]);
        update.setDefectQuantity(quantities[1]);
        update.setStatus(BusinessStatus.Production.FINISHED);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (mfgTaskMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "生产任务已被其他请求修改，请刷新后重试");
        }
        insertStatusChangeLog(taskId, task.getStatus(), BusinessStatus.Production.FINISHED,
                "MFG_TASK", "AUTO_FINISH_TASK_BY_REPORT", operatorId,
                "报工累计数量已达到任务计划数量", now);
        insertOperationLog(taskId, "AUTO_FINISH_TASK_BY_REPORT", "mfg_task", operatorId,
                "报工累计数量已达到任务计划数量", now);
        if (responsibilityNotificationService != null) {
            responsibilityNotificationService.cancelOpenTaskNotifications(taskId, operatorId);
        }
    }

    private void reopenTaskIfReportedIncomplete(Long taskId, Long operatorId, LocalDateTime now) {
        MfgTask task = activeTaskForUpdate(taskId);
        if (task == null || !BusinessStatus.Production.FINISHED.equals(task.getStatus())) {
            return;
        }
        BigDecimal[] quantities = reportedTaskQuantities(taskId);
        BigDecimal planned = zero(task.getPlannedQuantity());
        BigDecimal total = quantities[0].add(quantities[1]);
        if (planned.signum() > 0 && total.compareTo(planned) >= 0) {
            return;
        }
        MfgTask update = new MfgTask();
        update.setTaskId(taskId);
        update.setVersion(task.getVersion());
        update.setCompletedQuantity(quantities[0]);
        update.setDefectQuantity(quantities[1]);
        update.setStatus(BusinessStatus.Production.RUNNING);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (mfgTaskMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "生产任务已被其他请求修改，请刷新后重试");
        }
        insertStatusChangeLog(taskId, task.getStatus(), BusinessStatus.Production.RUNNING,
                "MFG_TASK", "AUTO_REOPEN_TASK_AFTER_REPORT_CHANGE", operatorId,
                "报工有效累计数量未达到任务计划数量", now);
        insertOperationLog(taskId, "AUTO_REOPEN_TASK_AFTER_REPORT_CHANGE", "mfg_task", operatorId,
                "报工有效累计数量未达到任务计划数量", now);
    }

    private void syncTaskCompletionAfterReportChange(Long taskId, Long operatorId, LocalDateTime now) {
        finishTaskIfReportedComplete(taskId, operatorId, now);
        reopenTaskIfReportedIncomplete(taskId, operatorId, now);
    }

    private Long reportChangeOperator(MfgWorkReport request, MfgWorkReport existing) {
        if (request != null && request.getUpdatedBy() != null) {
            return request.getUpdatedBy();
        }
        if (existing != null && existing.getUpdatedBy() != null) {
            return existing.getUpdatedBy();
        }
        return existing == null ? null : existing.getCreatedBy();
    }

    private BigDecimal[] reportedTaskQuantities(Long taskId) {
        List<MfgWorkReport> reports = baseMapper.selectList(new LambdaQueryWrapper<MfgWorkReport>()
                .eq(MfgWorkReport::getTaskId, taskId)
                .eq(MfgWorkReport::getDeletedFlag, 0)
                .in(MfgWorkReport::getAuditStatus, SUBMITTED_STATUS, APPROVED_STATUS));
        BigDecimal qualified = BigDecimal.ZERO;
        BigDecimal defect = BigDecimal.ZERO;
        if (reports != null) {
            for (MfgWorkReport report : reports) {
                qualified = qualified.add(zero(report.getQualifiedQuantity()));
                defect = defect.add(zero(report.getDefectQuantity()));
            }
        }
        return new BigDecimal[]{qualified, defect};
    }

    private void validateReportMaterials(MfgWorkReport report, List<MfgReportMaterial> materials) {
        if (materials == null || materials.isEmpty()) {
            return;
        }
        MfgTask task = activeTask(report == null ? null : report.getTaskId());
        if (task == null || task.getWorkOrderId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "报工未关联有效工单，不能登记实际用料");
        }
        MfgWorkOrder workOrder = mfgWorkOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, task.getWorkOrderId())
                .eq(MfgWorkOrder::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (workOrder == null || BusinessStatus.Document.CLOSED.equals(workOrder.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "报工关联的生产工单不存在或已关闭");
        }
        Map<String, BigDecimal> requested = requestedMaterials(materials);
        for (Map.Entry<String, BigDecimal> entry : requested.entrySet()) {
            validateEntryAvailable(task, report == null ? null : report.getReportId(), entry);
        }
    }

    /** 校验单条用料并汇总成「物料:批次 → 消耗量」。 */
    private Map<String, BigDecimal> requestedMaterials(List<MfgReportMaterial> materials) {
        Map<String, BigDecimal> requested = new HashMap<>();
        for (MfgReportMaterial material : materials) {
            com.neuedu.ems.service.mfg.ReportMaterialQuantities.normalize(material);
            BigDecimal used = zero(material.getUsedQuantity());
            BigDecimal scrap = zero(material.getScrapQuantity());
            requireUsableMaterial(material, used, scrap);
            executionQuantities.validate(material.getMaterialId(), used, true);
            executionQuantities.validate(material.getMaterialId(), scrap, true);
            requireMatchingBatch(material);
            String key = material.getMaterialId() + ":" + material.getBatchId();
            requested.merge(key, used.add(scrap), BigDecimal::add);
        }
        return requested;
    }

    /** 用料行必须带物料、批次且消耗为正数。 */
    private void requireUsableMaterial(MfgReportMaterial material, BigDecimal used, BigDecimal scrap) {
        if (!isUsableMaterial(material, used, scrap)) {
            throw business(ResultCode.PARAM_ERROR, "报工用料必须包含物料、原料批次和正数消耗数量");
        }
    }

    private boolean isUsableMaterial(MfgReportMaterial material, BigDecimal used, BigDecimal scrap) {
        if (material.getMaterialId() == null || material.getBatchId() == null) {
            return false;
        }
        return hasPositiveConsumption(used, scrap);
    }

    /** 实耗与报废都不为负，且合计为正数。 */
    private boolean hasPositiveConsumption(BigDecimal used, BigDecimal scrap) {
        return !isNegative(used) && !isNegative(scrap) && used.add(scrap).signum() > 0;
    }

    /** 原料批次必须存在且属于该物料。 */
    private void requireMatchingBatch(MfgReportMaterial material) {
        WmsMaterialBatch batch = wmsMaterialBatchMapper.selectOne(new LambdaQueryWrapper<WmsMaterialBatch>()
                .eq(WmsMaterialBatch::getBatchId, material.getBatchId())
                .eq(WmsMaterialBatch::getDeletedFlag, 0));
        if (batch == null || !material.getMaterialId().equals(batch.getMaterialId())) {
            throw business(ResultCode.BUSINESS_ERROR, "报工用料与原料批次不匹配");
        }
    }

    /** 单条用料的可用量校验：优先看线边占用，没有占用记录时回退到领料-退料-已耗。 */
    private void validateEntryAvailable(MfgTask task, Long reportId, Map.Entry<String, BigDecimal> entry) {
        String[] parts = entry.getKey().split(":");
        Long materialId = Long.valueOf(parts[0]);
        Long batchId = Long.valueOf(parts[1]);
        // 优先按线边占用判断可用量：领料移库后线边库存按工单占用，原「领料-退料-已耗」算法会重复计量。
        BigDecimal lineSideLocked = findLineSideLockedQuantity(task.getWorkOrderId(), materialId, batchId);
        if (lineSideLocked != null) {
            // 同一工单的其他报工已提交但未审核时，其消耗也已从线边占用中扣掉，这里要一并扣减，避免重复可用。
            BigDecimal pending = pendingConsumption(task.getWorkOrderId(), materialId, batchId, reportId);
            lineSideLocked = lineSideLocked.subtract(pending).max(BigDecimal.ZERO);
            if (entry.getValue().compareTo(lineSideLocked) > 0) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "报工用料超过本工单该批次可用领料量；物料ID=" + materialId
                                + "，批次ID=" + batchId + "，可用=" + lineSideLocked
                                + "，本次=" + entry.getValue());
            }
            return;
        }
        BigDecimal available = issuedQuantity(task, materialId, batchId)
                .subtract(returnedQuantity(task, materialId, batchId))
                .subtract(consumedQuantity(task, materialId, batchId));
        if (entry.getValue().compareTo(available) > 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "报工用料超过本工单该批次可用领料量；物料ID=" + materialId
                            + "，批次ID=" + batchId + "，可用=" + available.max(BigDecimal.ZERO)
                            + "，本次=" + entry.getValue());
        }
    }

    /** 同一工单下已提交未审核报工对该物料批次的消耗合计，排除当前报工自身。 */
    private BigDecimal pendingConsumption(Long workOrderId, Long materialId, Long batchId, Long reportId) {
        return mfgReportMaterialMapper.selectList(new LambdaQueryWrapper<MfgReportMaterial>()
                        .eq(MfgReportMaterial::getMaterialId, materialId)
                        .eq(MfgReportMaterial::getBatchId, batchId)
                        .eq(MfgReportMaterial::getDeletedFlag, 0)
                        .ne(reportId != null, MfgReportMaterial::getReportId, reportId)
                        .inSql(MfgReportMaterial::getReportId,
                                "SELECT r.report_id FROM mfg_work_report r JOIN mfg_task t ON t.task_id = r.task_id "
                                        + "WHERE r.deleted_flag = 0 AND t.deleted_flag = 0 "
                                        + "AND r.audit_status = 'SUBMITTED' AND t.work_order_id = " + workOrderId))
                .stream().map(m -> zero(m.getUsedQuantity()).add(zero(m.getScrapQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    private BigDecimal issuedQuantity(MfgTask task, Long materialId, Long batchId) {
        return wmsOutboundDetailMapper.selectList(new LambdaQueryWrapper<WmsOutboundDetail>()
                        .eq(WmsOutboundDetail::getMaterialId, materialId)
                        .eq(WmsOutboundDetail::getBatchId, batchId)
                        .eq(WmsOutboundDetail::getDeletedFlag, 0)
                        .inSql(WmsOutboundDetail::getOutboundId,
                                "SELECT outbound_id FROM wms_outbound_order WHERE deleted_flag = 0 "
                                        + "AND outbound_type = 'MATERIAL_ISSUE' AND audit_status = 'APPROVED' "
                                        + "AND work_order_id = " + task.getWorkOrderId()))
                .stream().map(WmsOutboundDetail::getOutboundQuantity).map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 本工单该批次已提交或已审核的退料量。 */
    private BigDecimal returnedQuantity(MfgTask task, Long materialId, Long batchId) {
        return wmsReturnDetailMapper.selectList(new LambdaQueryWrapper<WmsReturnDetail>()
                        .eq(WmsReturnDetail::getMaterialId, materialId)
                        .eq(WmsReturnDetail::getBatchId, batchId)
                        .eq(WmsReturnDetail::getDeletedFlag, 0)
                        .inSql(WmsReturnDetail::getReturnId,
                                "SELECT return_id FROM wms_return_order WHERE deleted_flag = 0 "
                                        + "AND audit_status IN ('SUBMITTED','APPROVED') AND work_order_id = "
                                        + task.getWorkOrderId()))
                .stream().map(WmsReturnDetail::getReturnQuantity).map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 本工单该批次已提交或已审核的报工消耗量。 */
    private BigDecimal consumedQuantity(MfgTask task, Long materialId, Long batchId) {
        return mfgReportMaterialMapper.selectList(new LambdaQueryWrapper<MfgReportMaterial>()
                        .eq(MfgReportMaterial::getMaterialId, materialId)
                        .eq(MfgReportMaterial::getBatchId, batchId)
                        .eq(MfgReportMaterial::getDeletedFlag, 0)
                        .inSql(MfgReportMaterial::getReportId,
                                "SELECT wr.report_id FROM mfg_work_report wr JOIN mfg_task t ON t.task_id = wr.task_id "
                                        + "WHERE wr.deleted_flag = 0 AND t.deleted_flag = 0 "
                                        + "AND wr.audit_status IN ('SUBMITTED','APPROVED') AND t.work_order_id = "
                                        + task.getWorkOrderId()))
                .stream().map(item -> zero(item.getUsedQuantity()).add(zero(item.getScrapQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 部分更新报工时，用已有数量回填未传字段。
     * @param workReport 请求中的报工实体
     * @param existing 数据库中已有的报工实体
     */
    private void fillMissingQuantities(MfgWorkReport workReport, MfgWorkReport existing) {
        if (workReport.getQualifiedQuantity() == null) {
            workReport.setQualifiedQuantity(existing.getQualifiedQuantity());
        }
        if (workReport.getDefectQuantity() == null) {
            workReport.setDefectQuantity(existing.getDefectQuantity());
        }
        if (workReport.getActualWorkHours() == null) {
            workReport.setActualWorkHours(existing.getActualWorkHours());
        }
    }

    /**
     * 批量插入报工用料明细。
     * @param reportId 报工ID
     * @param materials 报工用料明细列表
     */
    private void insertMaterials(Long reportId, List<MfgReportMaterial> materials) {
        if (materials == null) {
            return;
        }
        for (MfgReportMaterial material : materials) {
            material.setId(null);
            material.setReportId(reportId);
            if (material.getUsedQuantity() == null) {
                material.setUsedQuantity(BigDecimal.ZERO);
            }
            if (material.getScrapQuantity() == null) {
                material.setScrapQuantity(BigDecimal.ZERO);
            }
            if (material.getDeletedFlag() == null) {
                material.setDeletedFlag(0);
            }
            mfgReportMaterialMapper.insert(material);
        }
    }

    /**
     * 软删除指定报工的全部用料明细。
     * @param reportId 报工ID
     */
    private void softDeleteMaterials(Long reportId) {
        LambdaQueryWrapper<MfgReportMaterial> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(MfgReportMaterial::getReportId, reportId)
                .eq(MfgReportMaterial::getDeletedFlag, 0);
        List<MfgReportMaterial> materials = mfgReportMaterialMapper.selectList(queryWrapper);
        for (MfgReportMaterial material : materials) {
            MfgReportMaterial update = new MfgReportMaterial();
            update.setId(material.getId());
            update.setDeletedFlag(1);
            mfgReportMaterialMapper.updateById(update);
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
            throw new BusinessException(ResultCode.FAILED);
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
            throw new BusinessException(ResultCode.FAILED);
        }
    }

    /** 线边产出登记的上下文：报工、合格产出、成品批次/物料与线边仓/库位。 */
    private record OutputContext(Long reportId, BigDecimal qualified, TraceProductLot productLot,
                                 MdmProduct product, WmsWarehouse lineSide, WmsLocation location) {
    }

    /** 已通过前置校验的报工产出要素。 */
    private record ReportedOutput(BigDecimal qualified, TraceProductLot productLot, MdmProduct product) {
    }
}
