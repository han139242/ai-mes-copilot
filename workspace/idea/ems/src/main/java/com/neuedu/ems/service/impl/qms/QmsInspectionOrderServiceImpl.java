package com.neuedu.ems.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.qms.QmsInspectionResult;
import com.neuedu.ems.entity.qms.QmsInspectionSchemeItem;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.trace.TraceProductLot;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionResultMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeItemMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.trace.TraceProductLotMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import com.neuedu.ems.service.mfg.MfgWorkReportService;
import com.neuedu.ems.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检验单 Service 实现类。
 */
@Service
public class QmsInspectionOrderServiceImpl extends ServiceImpl<QmsInspectionOrderMapper, QmsInspectionOrder> implements QmsInspectionOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;

    @Autowired private com.neuedu.ems.service.mdm.ManufacturingPreparationGate executionGate;
    @Autowired private com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper executionWorkOrders;
    @Autowired private com.neuedu.ems.service.qms.InspectionStandardReviewService preparationReview;

    private static final BigDecimal UNQUALIFIED_RATE_LIMIT = new BigDecimal("0.005");
    private static final String BATCH_AVAILABLE = "AVAILABLE";
    private static final String BATCH_FROZEN = "FROZEN";
    private static final String BATCH_EXHAUSTED = "EXHAUSTED";
    private static final String BATCH_SCRAPPED = "SCRAPPED";

    @Autowired
    private QmsInspectionResultMapper qmsInspectionResultMapper;

    @Autowired
    private QmsInspectionSchemeItemMapper qmsInspectionSchemeItemMapper;

    @Autowired
    private MfgWorkReportMapper mfgWorkReportMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private WmsMaterialBatchMapper wmsMaterialBatchMapper;

    @Autowired
    private TraceProductLotMapper traceProductLotMapper;

    @Autowired
    private QmsResponsibilityNotificationService responsibilityNotificationService;

    @Autowired
    private MfgWorkReportService mfgWorkReportService;
    @Autowired private com.neuedu.ems.mapper.wms.WmsInboundOrderMapper productionInbounds;
    @Autowired private com.neuedu.ems.mapper.qms.QmsNonconformingOrderMapper nonconformingOrders;
    @Autowired(required = false) private org.springframework.context.ApplicationEventPublisher componentEvents;
    @Autowired private com.neuedu.ems.mapper.wms.WmsStockMovementMapper componentMovements;

    /**
     * 分页查询检验单，支持单号、类型、方案、工单、任务、报工、批次、检验员、状态和结果筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 检验单号关键字
     * @param inspectionType 检验类型
     * @param schemeId 方案ID
     * @param workOrderId 工单ID
     * @param taskId 任务ID
     * @param reportId 报工ID
     * @param batchId 批次ID
     * @param inspectorId 检验员ID
     * @param status 状态
     * @param inspectionResult 检验结果
     * @return 检验单分页结果
     */
    @Override
    public Page<QmsInspectionOrder> pageInspectionOrders(Integer current, Integer size, String keyword, String inspectionType,
                                                         Long schemeId, Long workOrderId, Long taskId, Long reportId,
                                                         Long batchId, Long productLotId, Long inspectorId,
                                                         String status, String inspectionResult) {
        Page<QmsInspectionOrder> page = new Page<>(current, size);
        Page<QmsInspectionOrder> result=baseMapper.selectPage(page, buildInspectionOrderQuery(keyword, inspectionType, schemeId, workOrderId,
                taskId, reportId, batchId, productLotId, inspectorId, status, inspectionResult));
        fillProductionDefects(result.getRecords());
        return result;
    }

    /**
     * 查询检验单列表，支持单号、类型、方案、工单、任务、报工、批次、检验员、状态和结果筛选。
     * @param keyword 检验单号关键字
     * @param inspectionType 检验类型
     * @param schemeId 方案ID
     * @param workOrderId 工单ID
     * @param taskId 任务ID
     * @param reportId 报工ID
     * @param batchId 批次ID
     * @param inspectorId 检验员ID
     * @param status 状态
     * @param inspectionResult 检验结果
     * @return 检验单列表
     */
    @Override
    public List<QmsInspectionOrder> listInspectionOrders(String keyword, String inspectionType, Long schemeId, Long workOrderId,
                                                         Long taskId, Long reportId, Long batchId, Long productLotId,
                                                         Long inspectorId, String status, String inspectionResult) {
        List<QmsInspectionOrder> result=baseMapper.selectList(buildInspectionOrderQuery(keyword, inspectionType, schemeId, workOrderId,
                taskId, reportId, batchId, productLotId, inspectorId, status, inspectionResult));
        fillProductionDefects(result);
        return result;
    }

    /**
     * 根据ID查询未删除的检验单。
     * @param inspectionId 检验单ID
     * @return 检验单实体
     */
    @Override
    public QmsInspectionOrder getInspectionOrderById(Long inspectionId) {
        LambdaQueryWrapper<QmsInspectionOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QmsInspectionOrder::getInspectionId, inspectionId)
                .eq(QmsInspectionOrder::getDeletedFlag, 0);
        QmsInspectionOrder result=baseMapper.selectOne(queryWrapper);
        if(result!=null) fillProductionDefects(List.of(result));
        return result;
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public QmsInspectionOrder reopenProductionDefects(Long inspectionId,Long userId,String reason) {
        if(userId==null || !StringUtils.hasText(reason) || reason.length()>500) throw business(ResultCode.PARAM_ERROR,"请填写重检原因（最多500字）");
        var inspection=baseMapper.selectOne(new LambdaQueryWrapper<QmsInspectionOrder>().eq(QmsInspectionOrder::getInspectionId,inspectionId)
                .eq(QmsInspectionOrder::getDeletedFlag,0).last("FOR UPDATE"));
        if(inspection==null) throw business(ResultCode.NOT_FOUND,"检验单不存在");
        fillProductionDefects(List.of(inspection));
        // 已经退回草稿且带着生产不良数量，说明上一次重检申请已生效，重复点击直接返回现状。
        if(isAlreadyReopened(inspection)) return inspection;
        ensureReopenable(inspection);
        var lot=lockReopenableLot(inspection);
        ensureNoDownstream(inspectionId,lot);
        LocalDateTime now=LocalDateTime.now();
        // 新建复检单，原逐件记录及件号保持完整，避免覆盖证据或占用逐件唯一键。
        QmsInspectionOrder reinspection=new QmsInspectionOrder();
        reinspection.setInspectionNo(numbers.nextFor("QI","TASK",inspection.getTaskId()));
        reinspection.setInspectionType(inspection.getInspectionType());reinspection.setSchemeId(inspection.getSchemeId());
        reinspection.setWorkOrderId(inspection.getWorkOrderId());reinspection.setTaskId(inspection.getTaskId());
        reinspection.setReportId(inspection.getReportId());reinspection.setProductLotId(inspection.getProductLotId());
        reinspection.setInspectorId(inspection.getInspectorId());reinspection.setSampleQuantity(inspection.getSampleQuantity());
        reinspection.setQualifiedQuantity(BigDecimal.ZERO);reinspection.setUnqualifiedQuantity(BigDecimal.ZERO);
        reinspection.setStatus("DRAFT");reinspection.setVersion(0);reinspection.setDeletedFlag(0);
        reinspection.setRemark("复检来源："+inspection.getInspectionNo()+"；"+reason.trim());
        reinspection.setCreatedBy(userId);reinspection.setCreatedTime(now);reinspection.setUpdatedBy(userId);reinspection.setUpdatedTime(now);
        if(baseMapper.insert(reinspection)!=1 || reinspection.getInspectionId()==null)
            throw business(ResultCode.FAILED,"复检单生成失败");
        var update=new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QmsInspectionOrder>()
                .eq("inspection_id",inspectionId).eq("version",inspection.getVersion()).eq("deleted_flag",0)
                .set("status","CLOSED")
                .set("version",inspection.getVersion()+1).set("updated_by",userId).set("updated_time",now);
        if(baseMapper.update(null,update)!=1) throw business(ResultCode.DATA_CONFLICT,"检验单已变化，重检申请已回滚");
        if(traceProductLotMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<TraceProductLot>()
                .eq("product_lot_id",lot.getProductLotId()).eq("version",lot.getVersion()).eq("deleted_flag",0)
                .set("status","WAIT_INSPECTION").set("version",lot.getVersion()+1).set("updated_by",userId).set("updated_time",now))!=1)
            throw business(ResultCode.DATA_CONFLICT,"产品批次已变化，重检申请已回滚");
        String note="原检验结论="+inspection.getInspectionResult()+"，合格="+inspection.getQualifiedQuantity()+"，不合格="+inspection.getUnqualifiedQuantity()
                +"；生产已报不良="+inspection.getSourceDefectQuantity()+"；重检原因："+reason.trim();
        note+="；新复检单："+reinspection.getInspectionNo();
        insertStatusChangeLog(inspectionId,"FINISHED","CLOSED","QMS_INSPECTION_ORDER","REOPEN_PRODUCTION_DEFECT_CONFLICT",userId,note,now);
        insertStatusChangeLog(reinspection.getInspectionId(),null,"DRAFT","QMS_INSPECTION_ORDER","REINSPECT_PRODUCTION_DEFECTS",userId,note,now);
        insertStatusChangeLog(lot.getProductLotId(),lot.getStatus(),"WAIT_INSPECTION","TRACE_PRODUCT_LOT","REOPEN_PRODUCTION_DEFECT_CONFLICT",userId,reason,now);
        insertOperationLog(inspectionId,"REOPEN_PRODUCTION_DEFECT_CONFLICT","qms_inspection_order",userId,note,now);
        mfgWorkReportService.recalculateWorkOrderCompletion(inspection.getWorkOrderId(),userId,"检验结果与生产不良冲突，申请重新检验");
        responsibilityNotificationService.publishOpenInspection(reinspection,userId);
        return getInspectionOrderById(reinspection.getInspectionId());
    }

    /** 已退回草稿并带上生产不良数量，表示重检申请已经生效过。 */
    private static boolean isAlreadyReopened(QmsInspectionOrder inspection) {
        return "DRAFT".equals(inspection.getStatus()) && inspection.getSourceDefectQuantity()!=null
                && inspection.getSourceDefectQuantity().signum()>0;
    }

    /** 只有「与生产不良冲突的产品批次检验单」才有资格申请重检。 */
    private void ensureReopenable(QmsInspectionOrder inspection) {
        if(!Boolean.TRUE.equals(inspection.getProductionDefectConflict()) || inspection.getProductLotId()==null)
            throw business(ResultCode.BUSINESS_ERROR,"只有与生产不良冲突的已完成产品批次检验单可以申请重检");
    }

    /** 锁批次并确认它还没被后续流程带走。 */
    private TraceProductLot lockReopenableLot(QmsInspectionOrder inspection) {
        var lot=traceProductLotMapper.selectOne(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getProductLotId,inspection.getProductLotId()).eq(TraceProductLot::getDeletedFlag,0).last("FOR UPDATE"));
        if(lot==null || !List.of("PASS","FAIL","WAIT_INSPECTION").contains(lot.getStatus()))
            throw business(ResultCode.BUSINESS_ERROR,"产品批次已进入后续流程，不能直接重检");
        return lot;
    }

    /** 组件已交接、已有入库或不合格处置，都说明下游已经动了这份数据，不能原地重检。 */
    private void ensureNoDownstream(Long inspectionId, TraceProductLot lot) {
        if (componentMovements.selectCount(new LambdaQueryWrapper<com.neuedu.ems.entity.wms.WmsStockMovement>()
                .eq(com.neuedu.ems.entity.wms.WmsStockMovement::getSourceOrderType,"PRODUCT_LOT")
                .eq(com.neuedu.ems.entity.wms.WmsStockMovement::getSourceOrderId,lot.getProductLotId())
                .eq(com.neuedu.ems.entity.wms.WmsStockMovement::getMovementType,"COMPONENT_HANDOVER")
                .eq(com.neuedu.ems.entity.wms.WmsStockMovement::getDeletedFlag,0)) > 0)
            throw business(ResultCode.BUSINESS_ERROR,"组件已交接给下游工单，需先核对下游，不能直接重检");
        // 入库生成也先锁批次；取得批次锁后用当前读核查，避免等待锁期间新增的入库单被快照遗漏。
        if(nonconformingOrders.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.neuedu.ems.entity.qms.QmsNonconformingOrder>()
                .eq("inspection_id",inspectionId).eq("deleted_flag",0))>0
                || !productionInbounds.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.neuedu.ems.entity.wms.WmsInboundOrder>()
                .eq("source_order_type","PRODUCT_LOT").eq("source_order_id",lot.getProductLotId()).eq("deleted_flag",0).last("FOR UPDATE")).isEmpty())
            throw business(ResultCode.BUSINESS_ERROR,"已有入库或不合格处置记录，需先核对下游，不能直接重检");
    }

    private void fillProductionDefects(List<QmsInspectionOrder> inspections) {
        if(stockIssueDetails != null && !inspections.isEmpty()) {
            List<Long> ids=inspections.stream().map(QmsInspectionOrder::getInspectionId).filter(Objects::nonNull).toList();
            if(!ids.isEmpty()) {
                var stockChecks=stockIssueDetails.selectList(new LambdaQueryWrapper<com.neuedu.ems.entity.wms.WmsOutboundDetail>()
                        .in(com.neuedu.ems.entity.wms.WmsOutboundDetail::getInspectionId,ids)
                        .eq(com.neuedu.ems.entity.wms.WmsOutboundDetail::getDeletedFlag,0));
                inspections.forEach(i->i.setStockReleaseInspection(stockChecks.stream().anyMatch(d->Objects.equals(i.getInspectionId(),d.getInspectionId()))));
            }
        }
        fillExistingDispositions(inspections);
        List<Long> ids=inspections.stream().map(QmsInspectionOrder::getReportId).filter(java.util.Objects::nonNull).distinct().toList();
        if(ids.isEmpty()) return;
        var reports=mfgWorkReportMapper.selectList(new LambdaQueryWrapper<MfgWorkReport>()
                .in(MfgWorkReport::getReportId,ids).eq(MfgWorkReport::getDeletedFlag,0).eq(MfgWorkReport::getAuditStatus,"APPROVED"));
        for(var inspection:inspections) {
            var report=reports.stream().filter(r->java.util.Objects.equals(r.getReportId(),inspection.getReportId())).findFirst().orElse(null);
            if(report==null) continue;
            BigDecimal bad=report.getDefectQuantity()==null?BigDecimal.ZERO:report.getDefectQuantity();
            inspection.setSourceReportNo(report.getReportNo());inspection.setSourceDefectQuantity(bad);
            inspection.setSourceDefectDescription(report.getDefectDescription());
            inspection.setProductionDefectConflict(productionDefectConflict(inspection,bad));
        }
    }

    /** 已完成检验单的坏品数少于报工登记的不良数，说明这批不良被漏记，标记为冲突以便走重检。 */
    private static boolean productionDefectConflict(QmsInspectionOrder inspection, BigDecimal bad) {
        if (!"FINISHED".equals(inspection.getStatus()) || bad.signum() <= 0) {
            return false;
        }
        return inspection.getUnqualifiedQuantity() == null || inspection.getUnqualifiedQuantity().compareTo(bad) < 0;
    }

    private void fillExistingDispositions(List<QmsInspectionOrder> inspections) {
        if (inspections.isEmpty()) return;
        List<Long> ids = inspections.stream().map(QmsInspectionOrder::getInspectionId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        var existing = listDispositionsOf(ids);
        for (var inspection : inspections) {
            inspection.setNonconformingOrderId(null);
            inspection.setNonconformingOrderStatus(null);
            existing.stream().filter(n -> java.util.Objects.equals(n.getInspectionId(), inspection.getInspectionId()))
                    .findFirst().ifPresent(n -> {
                        inspection.setNonconformingOrderId(n.getNcrId());
                        inspection.setNonconformingOrderStatus(n.getStatus());
                    });
        }
    }

    /** 一次取回这批检验单的有效不合格品处置单，避免逐单查询。 */
    private List<com.neuedu.ems.entity.qms.QmsNonconformingOrder> listDispositionsOf(List<Long> inspectionIds) {
        return nonconformingOrders.selectList(new LambdaQueryWrapper<com.neuedu.ems.entity.qms.QmsNonconformingOrder>()
                .in(com.neuedu.ems.entity.qms.QmsNonconformingOrder::getInspectionId, inspectionIds)
                .eq(com.neuedu.ems.entity.qms.QmsNonconformingOrder::getDeletedFlag, 0)
                .orderByAsc(com.neuedu.ems.entity.qms.QmsNonconformingOrder::getNcrId));
    }

    private void assertProductionDefectsPreserved(QmsInspectionOrder inspection,BigDecimal qualified,
                                                  BigDecimal unqualified,String verdict) {
        if(inspection.getReportId()==null) return;
        MfgWorkReport report=mfgWorkReportMapper.selectOne(new LambdaQueryWrapper<MfgWorkReport>()
                .eq(MfgWorkReport::getReportId,inspection.getReportId()).eq(MfgWorkReport::getDeletedFlag,0)
                .eq(MfgWorkReport::getAuditStatus,"APPROVED"));
        if(report==null || report.getDefectQuantity()==null || report.getDefectQuantity().signum()<=0) return;
        inspection.setSourceDefectQuantity(report.getDefectQuantity());
        if(defectsLost(report,qualified,unqualified,verdict))
            throw business(ResultCode.BUSINESS_ERROR,"来源报工已登记不良 "+report.getDefectQuantity().stripTrailingZeros().toPlainString()
                    +" 件，本次检验必须保留这些不良，不能直接判为合格；请通过返工或补产补足");
    }

    /**
     * 来源报工已登记的不良是否被本次检验丢掉了。
     *
     * <p>判为让步接收、未填不良数、不良数少于报工登记值、或合格数超过报工合格值，
     * 都意味着来源不良被抹掉，必须拦住。</p>
     */
    private static boolean defectsLost(MfgWorkReport report,BigDecimal qualified,BigDecimal unqualified,String verdict) {
        if("CONCESSION".equals(verdict)) return true;
        if(unqualified==null || qualified==null) return true;
        boolean defectsDropped=unqualified.compareTo(report.getDefectQuantity())<0;
        boolean qualifiedInflated=qualified.compareTo(report.getQualifiedQuantity())>0;
        return defectsDropped || qualifiedInflated;
    }

    /**
     * 新增检验单，自动补齐编号、状态和删除标记。
     * @param inspectionOrder 检验单实体
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean saveInspectionOrder(QmsInspectionOrder inspectionOrder) {
        if (inspectionOrder != null && ("PROCESS".equalsIgnoreCase(inspectionOrder.getInspectionType())
                || "FINAL".equalsIgnoreCase(inspectionOrder.getInspectionType()))) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "过程检和终检不能空白新建：过程检必须从需检报工发起，终检必须从待检产品批次发起");
        }
        prepareInspectionOrder(inspectionOrder);
        boolean saved = baseMapper.insert(inspectionOrder) > 0;
        if (saved) {
            responsibilityNotificationService.publishOpenInspection(inspectionOrder, inspectionOrder.getCreatedBy());
        }
        return saved;
    }

    @Autowired private com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper stockIssueOrders;
    @Autowired private com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper stockIssueDetails;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QmsInspectionOrder createStockReleaseInspection(Long outboundId,Long detailId,Long schemeId,Long operatorId) {
        var order=stockIssueOrders.selectById(outboundId);
        var detail=stockIssueDetails.selectById(detailId);
        if(operatorId==null || schemeId==null || order==null || detail==null
                || !Integer.valueOf(0).equals(order.getDeletedFlag()) || !Integer.valueOf(0).equals(detail.getDeletedFlag())
                || !"MATERIAL_ISSUE".equals(order.getOutboundType()) || !"SUBMITTED".equals(order.getAuditStatus())
                || !Objects.equals(order.getOutboundId(),detail.getOutboundId()) || detail.getInspectionId()!=null
                || order.getWorkOrderId()==null || detail.getBatchId()==null || valueOrZero(detail.getOutboundQuantity()).signum()<=0)
            throw business(ResultCode.BUSINESS_ERROR,"领用检验必须从有效待确认领料明细发起，不能空白新建或重复签发");
        var inspection=new QmsInspectionOrder();
        inspection.setInspectionType("PROCESS");inspection.setWorkOrderId(order.getWorkOrderId());
        inspection.setBatchId(detail.getBatchId());inspection.setSchemeId(schemeId);
        inspection.setSampleQuantity(detail.getOutboundQuantity());inspection.setCreatedBy(operatorId);inspection.setUpdatedBy(operatorId);
        inspection.setCreatedTime(LocalDateTime.now());inspection.setUpdatedTime(LocalDateTime.now());
        inspection.setRemark("库存半成品领用复验；来源 "+order.getOutboundNo()+" 明细 "+detailId+"；按组件终检标准检查库存实物，不生成生产任务");
        prepareInspectionOrder(inspection);
        if(baseMapper.insert(inspection)!=1) throw business(ResultCode.FAILED,"领用复验建单失败");
        responsibilityNotificationService.publishOpenInspection(inspection,operatorId);
        return inspection;
    }

    /**
     * 新增检验单和检验结果明细。
     * @param inspectionOrder 检验单实体
     * @param results 检验结果列表
     * @return 检验单ID
     */
    @Override
    @Transactional
    public Long saveInspectionOrderFull(QmsInspectionOrder inspectionOrder, List<QmsInspectionResult> results) {
        saveInspectionOrder(inspectionOrder);
        insertResults(inspectionOrder.getInspectionId(), results);
        return inspectionOrder.getInspectionId();
    }

    /**
     * 修改检验单，已完成检验单不允许普通修改。
     * @param inspectionOrder 检验单实体
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean updateInspectionOrder(QmsInspectionOrder inspectionOrder) {
        if (inspectionOrder.getInspectionId() == null) {
            return false;
        }
        QmsInspectionOrder existing = getInspectionOrderById(inspectionOrder.getInspectionId());
        if (existing == null || !BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            return false;
        }
        preparationReview.assertInspectionExecutionSupported(existing.getSchemeId());
        assertTargetExecutionSupported(existing);
        preparationReview.assertInspectionExecutionSupported(inspectionOrder.getSchemeId());
        inspectionOrder.setStatus(null);
        inspectionOrder.setInspectionResult(null);
        inspectionOrder.setSampleQuantity(null);
        inspectionOrder.setQualifiedQuantity(null);
        inspectionOrder.setUnqualifiedQuantity(null);
        inspectionOrder.setInspectionTime(null);
        inspectionOrder.setDeletedFlag(null);
        inspectionOrder.setInspectionNo(null);
        inspectionOrder.setInspectionType(null);
        inspectionOrder.setWorkOrderId(null);
        inspectionOrder.setTaskId(null);
        inspectionOrder.setReportId(null);
        inspectionOrder.setBatchId(null);
        inspectionOrder.setProductLotId(null);
        if (inspectionOrder.getVersion() == null) {
            inspectionOrder.setVersion(existing.getVersion());
        }
        boolean updated = baseMapper.updateById(inspectionOrder) > 0;
        if (updated && inspectionOrder.getInspectorId() != null
                && !Objects.equals(existing.getInspectorId(), inspectionOrder.getInspectorId())) {
            responsibilityNotificationService.cancelOpenInspection(existing.getInspectionId(), inspectionOrder.getUpdatedBy());
            QmsInspectionOrder current = getInspectionOrderById(existing.getInspectionId());
            responsibilityNotificationService.publishOpenInspection(current, inspectionOrder.getUpdatedBy());
        }
        return updated;
    }

    /**
     * 修改检验单并替换检验结果明细，已完成检验单不允许替换明细。
     * @param inspectionOrder 检验单实体
     * @param results 检验结果列表
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean updateInspectionOrderFull(QmsInspectionOrder inspectionOrder, List<QmsInspectionResult> results) {
        if (!updateInspectionOrder(inspectionOrder)) {
            return false;
        }
        if (results != null) {
            softDeleteResults(inspectionOrder.getInspectionId());
            insertResults(inspectionOrder.getInspectionId(), results);
        }
        return true;
    }

    /**
     * 根据ID软删除检验单和检验结果，已完成检验单不允许删除。
     * @param inspectionId 检验单ID
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean removeInspectionOrderById(Long inspectionId) {
        QmsInspectionOrder existing = getInspectionOrderById(inspectionId);
        if (existing == null || !BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            return false;
        }
        if (existing.getProductLotId() != null) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "来源生成的产品终检单不能删除，应保留产品批次质量链路");
        }
        QmsInspectionOrder inspectionOrder = new QmsInspectionOrder();
        inspectionOrder.setInspectionId(inspectionId);
        inspectionOrder.setDeletedFlag(1);
        inspectionOrder.setVersion(existing.getVersion());
        boolean removed = baseMapper.updateById(inspectionOrder) > 0;
        softDeleteResults(inspectionId);
        if (removed) {
            responsibilityNotificationService.cancelOpenInspection(inspectionId, existing.getUpdatedBy());
        }
        return removed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawInspectionOrder(Long inspectionId, Long operatorId, String remark) {
        QmsInspectionOrder existing = getInspectionOrderById(inspectionId);
        if (existing == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "检验单和操作人不能为空");
        }
        remark = StringUtils.hasText(remark) ? remark.trim() : "用户主动撤回，未填写补充原因";
        if (!BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有尚未开始检验的检验单可以撤回");
        }
        if (existing.getProductLotId() != null) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "产品批次终检单不能普通撤回，请从来源报工或质量异常流程处理");
        }
        if (qmsInspectionResultMapper.selectCount(new LambdaQueryWrapper<QmsInspectionResult>()
                .eq(QmsInspectionResult::getInspectionId, inspectionId)
                .eq(QmsInspectionResult::getDeletedFlag, 0)) > 0) {
            throw business(ResultCode.BUSINESS_ERROR, "检验单已有检验结果，不能撤回");
        }
        QmsInspectionOrder update = new QmsInspectionOrder();
        update.setInspectionId(inspectionId); update.setVersion(existing.getVersion());
        update.setStatus(BusinessStatus.Document.CLOSED); update.setUpdatedBy(operatorId); update.setUpdatedTime(LocalDateTime.now());
        if (baseMapper.updateById(update) == 0) throw business(ResultCode.DATA_CONFLICT, "检验单已被并发修改");
        LocalDateTime now = LocalDateTime.now();
        insertStatusChangeLog(inspectionId, existing.getStatus(), BusinessStatus.Document.CLOSED,
                "QMS_INSPECTION_ORDER", "WITHDRAW_INSPECTION", operatorId, remark, now);
        insertOperationLog(inspectionId, "WITHDRAW_INSPECTION", "qms_inspection_order", operatorId, remark, now);
    }

    /**
     * 提交检验单结果并结束检验单，重复提交已完成检验单会失败。
     * @param inspectionId 检验单ID
     * @param inspectionResult 检验结果
     * @param remark 备注
     * @param operatorId 操作人ID
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean submitInspectionOrder(Long inspectionId, String inspectionResult, String remark, Long operatorId) {
        return submitInspectionOrder(inspectionId, inspectionResult, remark, operatorId, null, null, null);
    }

    /**
     * 提交检验单结果和汇总数量并结束检验单；未提供数量时兼容原有调用。
     */
    @Override
    @Transactional
    public boolean submitInspectionOrder(Long inspectionId, String inspectionResult, String remark, Long operatorId,
                                         BigDecimal sampleQuantity, BigDecimal qualifiedQuantity,
                                         BigDecimal unqualifiedQuantity) {
        return submitInspectionInternal(inspectionId,inspectionResult,remark,operatorId,sampleQuantity,qualifiedQuantity,unqualifiedQuantity,false);
    }

    private boolean submitInspectionInternal(Long inspectionId,String inspectionResult,String remark,Long operatorId,
            BigDecimal sampleQuantity,BigDecimal qualifiedQuantity,BigDecimal unqualifiedQuantity,boolean pieceValidated) {
        boolean quantitiesProvided = sampleQuantity != null || qualifiedQuantity != null || unqualifiedQuantity != null;
        String normalizedInspectionResult = BusinessStatus.normalizeCode(inspectionResult);
        if (!pieceValidated && quantitiesProvided) {
            normalizedInspectionResult = determineInspectionResult(sampleQuantity, unqualifiedQuantity);
        }
        QmsInspectionOrder existing = findSubmittableOrder(inspectionId, normalizedInspectionResult, operatorId,
                quantitiesProvided, sampleQuantity, qualifiedQuantity, unqualifiedQuantity);
        if (existing == null) {
            return false;
        }
        if (!pieceValidated && requiresPieceInspection(existing)) {
            throw business(ResultCode.BUSINESS_ERROR,"本方案要求逐件全检，请使用逐件检验填写每件产品的必检项目");
        }
        normalizedInspectionResult = applyProductionDefectOverride(existing, normalizedInspectionResult);
        InspectionTargets targets = loadInspectionTargets(existing, normalizedInspectionResult);
        LocalDateTime inspectionTime = LocalDateTime.now();
        persistSubmittedResult(existing, normalizedInspectionResult, remark, operatorId, quantitiesProvided,
                sampleQuantity, qualifiedQuantity, unqualifiedQuantity, inspectionTime);
        propagateSubmittedResult(existing, targets.batch(), targets.productLot(), normalizedInspectionResult,
                operatorId, inspectionTime);
        return true;
    }

    /** 生产已确认不良不能因批次容许不良率而放行；合格部分在正式处置后单独保留。 */
    private static String applyProductionDefectOverride(QmsInspectionOrder existing, String normalizedInspectionResult) {
        if (existing.getSourceDefectQuantity()!=null && existing.getSourceDefectQuantity().signum()>0) {
            return BusinessStatus.Quality.FAIL;
        }
        return normalizedInspectionResult;
    }

    /** 落库：结果、状态、检验时间与本次随单数量一起写，版本号不匹配即判并发冲突。 */
    private void persistSubmittedResult(QmsInspectionOrder existing, String normalizedInspectionResult, String remark,
                                        Long operatorId, boolean quantitiesProvided, BigDecimal sampleQuantity,
                                        BigDecimal qualifiedQuantity, BigDecimal unqualifiedQuantity,
                                        LocalDateTime inspectionTime) {
        Long inspectionId = existing.getInspectionId();
        QmsInspectionOrder update = new QmsInspectionOrder();
        update.setInspectionId(inspectionId);
        update.setVersion(existing.getVersion());
        update.setInspectionResult(normalizedInspectionResult);
        update.setStatus(BusinessStatus.Production.FINISHED);
        update.setInspectionTime(inspectionTime);
        update.setRemark(StringUtils.hasText(remark) ? remark : existing.getRemark());
        if (quantitiesProvided) {
            update.setSampleQuantity(sampleQuantity);
            update.setQualifiedQuantity(qualifiedQuantity);
            update.setUnqualifiedQuantity(unqualifiedQuantity);
        }
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "检验单已被其他用户修改，请刷新后重试");
        }
        insertStatusChangeLog(inspectionId, existing.getStatus(), BusinessStatus.Production.FINISHED, "QMS_INSPECTION_ORDER",
                "SUBMIT_INSPECTION", operatorId, remark, inspectionTime);
        insertOperationLog(inspectionId, "SUBMIT_INSPECTION", "qms_inspection_order", operatorId, remark, inspectionTime);
    }

    /** 结果向下游传导：批次与产品批次的质量状态、完工率重算、组件交接事件与通知收口。 */
    private void propagateSubmittedResult(QmsInspectionOrder existing, WmsMaterialBatch batch, TraceProductLot productLot,
                                          String normalizedInspectionResult, Long operatorId,
                                          LocalDateTime inspectionTime) {
        Long inspectionId = existing.getInspectionId();
        if (batch != null) {
            synchronizeBatchQuality(batch, normalizedInspectionResult, inspectionId, operatorId, inspectionTime);
        }
        if (productLot != null) {
            synchronizeProductLotQuality(productLot, normalizedInspectionResult, inspectionId, operatorId, inspectionTime);
            mfgWorkReportService.recalculateWorkOrderCompletion(existing.getWorkOrderId(), operatorId,
                    "终检单 " + inspectionId + " 提交结果 " + normalizedInspectionResult);
            publishReleasedIfFinal(existing, operatorId);
        }
        responsibilityNotificationService.cancelOpenInspection(inspectionId, operatorId);
        responsibilityNotificationService.publishInspectionCompleted(existing, normalizedInspectionResult, operatorId);
    }

    /** 终检单提交后组件的可交接状态才成立，非终检不影响下游。 */
    private void publishReleasedIfFinal(QmsInspectionOrder existing, Long operatorId) {
        if (componentEvents != null && "FINAL".equals(existing.getInspectionType())) {
            componentEvents.publishEvent(new com.neuedu.ems.service.mfg.ComponentHandoverService.Released(
                    existing.getWorkOrderId(), operatorId));
        }
    }

    /**
     * 找出可以提交结果的检验单，不合规时返回 null 由调用方按失败处理。
     *
     * @param inspectionId              检验单ID
     * @param normalizedInspectionResult 规整后的检验结论
     * @param operatorId                操作人
     * @param quantitiesProvided        是否随单提供了抽样数量
     * @param sampleQuantity            抽样数量
     * @param qualifiedQuantity         合格数量
     * @param unqualifiedQuantity       不合格数量
     * @return 可提交时返回库内检验单，否则返回 null
     */
    private QmsInspectionOrder findSubmittableOrder(Long inspectionId, String normalizedInspectionResult,
                                                    Long operatorId, boolean quantitiesProvided,
                                                    BigDecimal sampleQuantity, BigDecimal qualifiedQuantity,
                                                    BigDecimal unqualifiedQuantity) {
        if (!BusinessStatus.isInspectionResult(normalizedInspectionResult) || operatorId == null) {
            return null;
        }
        QmsInspectionOrder existing = getInspectionOrderById(inspectionId);
        if (!submittableDraft(existing, quantitiesProvided, sampleQuantity, qualifiedQuantity,
                unqualifiedQuantity)) {
            return null;
        }
        assertProductionDefectsPreserved(existing,qualifiedQuantity,unqualifiedQuantity,normalizedInspectionResult);
        preparationReview.assertInspectionExecutionSupported(existing.getSchemeId());
        assertTargetExecutionSupported(existing);
        return existing;
    }

    /**
     * 判断库内检验单是否为可提交的草稿，且本次数量与检验单匹配。
     *
     * @param existing           库内检验单
     * @param quantitiesProvided 本次是否提交了数量
     * @param sampleQuantity     抽样数量
     * @param qualifiedQuantity  合格数量
     * @param unqualifiedQuantity 不合格数量
     * @return 是否可提交
     */
    private boolean submittableDraft(QmsInspectionOrder existing, boolean quantitiesProvided,
                                     BigDecimal sampleQuantity, BigDecimal qualifiedQuantity,
                                     BigDecimal unqualifiedQuantity) {
        boolean draft = existing != null && BusinessStatus.Document.DRAFT.equals(existing.getStatus());
        if (!draft) {
            return false;
        }
        return !quantitiesProvided
                || validInspectionQuantities(existing, sampleQuantity, qualifiedQuantity, unqualifiedQuantity);
    }

    /**
     * 读取并校验检验单指向的物料批次或产品批次，两者互斥且关联对象必须存在并自洽。
     *
     * @param existing                   库内检验单
     * @param normalizedInspectionResult 规整后的检验结论
     * @return 关联的批次对象
     */
    private InspectionTargets loadInspectionTargets(QmsInspectionOrder existing, String normalizedInspectionResult) {
        WmsMaterialBatch batch = existing.getBatchId() == null ? null : getActiveBatch(existing.getBatchId());
        TraceProductLot productLot = existing.getProductLotId() == null ? null
                : getActiveProductLot(existing.getProductLotId());
        requireSingleTarget(existing, batch, productLot);
        requireTargetMatches(existing, normalizedInspectionResult, batch, productLot);
        return new InspectionTargets(batch, productLot);
    }

    /** 检验单只能关联一种批次，且关联对象必须存在。 */
    private void requireSingleTarget(QmsInspectionOrder existing, WmsMaterialBatch batch,
                                     TraceProductLot productLot) {
        if (existing.getBatchId() != null && existing.getProductLotId() != null) {
            throw business(ResultCode.BUSINESS_ERROR, "检验单不能同时关联原料物料批次和产品批次");
        }
        if (existing.getBatchId() != null && batch == null) {
            throw business(ResultCode.NOT_FOUND, "检验单关联的物料批次不存在或已删除");
        }
        if (existing.getProductLotId() != null && productLot == null) {
            throw business(ResultCode.NOT_FOUND, "终检单关联的产品批次不存在或已删除");
        }
    }

    /** 终检单必须与产品批次一致，且耗尽或报废批次不能通过检验重新放行。 */
    private void requireTargetMatches(QmsInspectionOrder existing, String normalizedInspectionResult,
                                      WmsMaterialBatch batch, TraceProductLot productLot) {
        if(batch != null && "PASS".equals(normalizedInspectionResult) && stockIssueDetails != null
                && stockIssueDetails.selectCount(new LambdaQueryWrapper<com.neuedu.ems.entity.wms.WmsOutboundDetail>()
                .eq(com.neuedu.ems.entity.wms.WmsOutboundDetail::getInspectionId,existing.getInspectionId())
                .eq(com.neuedu.ems.entity.wms.WmsOutboundDetail::getDeletedFlag,0))>0
                && (!"PASS".equals(batch.getQualityStatus()) || !"AVAILABLE".equals(batch.getStatus())))
            throw business(ResultCode.BUSINESS_ERROR,"库存批次已冻结或不合格，请先完成批次质量处置；领用复验不能解冻整批库存");
        if (productLot != null && !matchesFinalInspection(existing, productLot)) {
            throw business(ResultCode.BUSINESS_ERROR, "终检单与待检产品批次、工单或来源报工不一致");
        }
        if (batch != null && !BusinessStatus.Quality.FAIL.equals(normalizedInspectionResult)
                && isTerminalBatch(batch)) {
            throw business(ResultCode.BUSINESS_ERROR, "耗尽或报废批次不能通过检验重新放行");
        }
    }

    /**
     * 判断终检单与产品批次的类型、状态、工单、来源报工是否一致。
     *
     * @param existing   库内检验单
     * @param productLot 产品批次
     * @return 是否一致
     */
    private boolean matchesFinalInspection(QmsInspectionOrder existing, TraceProductLot productLot) {
        return "FINAL".equals(existing.getInspectionType())
                && "WAIT_INSPECTION".equals(productLot.getStatus())
                && Objects.equals(productLot.getWorkOrderId(), existing.getWorkOrderId())
                && Objects.equals(productLot.getSourceReportId(), existing.getReportId());
    }

    /**
     * 判断批次是否已耗尽或报废。
     *
     * @param batch 物料批次
     * @return 是否处于终态
     */
    private boolean isTerminalBatch(WmsMaterialBatch batch) {
        return BATCH_EXHAUSTED.equals(batch.getStatus()) || BATCH_SCRAPPED.equals(batch.getStatus());
    }

    /** 检验单关联的批次对象：原料批次与产品批次互斥。 */
    private record InspectionTargets(WmsMaterialBatch batch, TraceProductLot productLot) {
    }

    private WmsMaterialBatch getActiveBatch(Long batchId) {
        LambdaQueryWrapper<WmsMaterialBatch> query = new LambdaQueryWrapper<>();
        query.eq(WmsMaterialBatch::getBatchId, batchId)
                .eq(WmsMaterialBatch::getDeletedFlag, 0);
        return wmsMaterialBatchMapper.selectOne(query);
    }

    private TraceProductLot getActiveProductLot(Long productLotId) {
        return traceProductLotMapper.selectOne(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getProductLotId, productLotId)
                .eq(TraceProductLot::getDeletedFlag, 0));
    }

    private void synchronizeProductLotQuality(TraceProductLot productLot, String inspectionResult,
                                              Long inspectionId, Long operatorId, LocalDateTime now) {
        String targetStatus = switch (inspectionResult) {
            case BusinessStatus.Quality.PASS -> BusinessStatus.Quality.PASS;
            case BusinessStatus.Quality.CONCESSION -> BusinessStatus.Quality.CONCESSION;
            default -> BusinessStatus.Quality.FAIL;
        };
        TraceProductLot update = new TraceProductLot();
        update.setProductLotId(productLot.getProductLotId());
        update.setVersion(productLot.getVersion());
        update.setStatus(targetStatus);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (traceProductLotMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "产品批次已被其他用户处理，请刷新后重试");
        }
        insertStatusChangeLog(productLot.getProductLotId(), productLot.getStatus(), targetStatus,
                "TRACE_PRODUCT_LOT", "SYNC_FINAL_INSPECTION_RESULT", operatorId,
                "终检单ID=" + inspectionId, now);
        insertOperationLog(productLot.getProductLotId(), "SYNC_FINAL_INSPECTION_RESULT", "trace_product_lot",
                operatorId, "终检单ID=" + inspectionId, now);
    }

    private void synchronizeBatchQuality(WmsMaterialBatch batch, String inspectionResult, Long inspectionId,
                                         Long operatorId, LocalDateTime now) {
        String targetStatus = resolveBatchStatus(batch, inspectionResult);
        WmsMaterialBatch update = new WmsMaterialBatch();
        update.setBatchId(batch.getBatchId());
        update.setVersion(batch.getVersion());
        update.setQualityStatus(inspectionResult);
        update.setStatus(targetStatus);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (wmsMaterialBatchMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "物料批次已被其他用户修改，请刷新后重试");
        }
        String batchRemark = "检验单#" + inspectionId + "同步批次质量状态，批次状态=" + targetStatus;
        insertStatusChangeLog(batch.getBatchId(), batch.getQualityStatus(), inspectionResult, "WMS_MATERIAL_BATCH",
                "SYNC_INSPECTION_RESULT", operatorId, batchRemark, now);
        insertOperationLog(batch.getBatchId(), "SYNC_INSPECTION_RESULT", "wms_material_batch",
                operatorId, batchRemark, now);
    }

    /**
     * 按不合格率上限判定检验结论，数量缺失或抽样为 0 时返回 null 交由调用方按失败处理。
     *
     * @param sampleQuantity      抽样数量
     * @param unqualifiedQuantity 不合格数量
     * @return 检验结论，无法判定时返回 null
     */
    private String determineInspectionResult(BigDecimal sampleQuantity, BigDecimal unqualifiedQuantity) {
        if (!hasPositiveSample(sampleQuantity, unqualifiedQuantity)) {
            return null;
        }
        BigDecimal allowedUnqualified = sampleQuantity.multiply(UNQUALIFIED_RATE_LIMIT);
        return unqualifiedQuantity.compareTo(allowedUnqualified) <= 0
                ? BusinessStatus.Quality.PASS
                : BusinessStatus.Quality.FAIL;
    }

    /**
     * 判断抽样数量与不合格数量是否足以判定结论。
     *
     * @param sampleQuantity      抽样数量
     * @param unqualifiedQuantity 不合格数量
     * @return 是否可以判定
     */
    private boolean hasPositiveSample(BigDecimal sampleQuantity, BigDecimal unqualifiedQuantity) {
        return sampleQuantity != null && unqualifiedQuantity != null && sampleQuantity.signum() > 0;
    }

    /**
     * 计算批次在本张检验单提交后应处于的状态：终态批次保持终态，不合格批次冻结，其余回到可用。
     *
     * @param batch            物料批次
     * @param inspectionResult 检验结论
     * @return 目标批次状态
     */
    private String resolveBatchStatus(WmsMaterialBatch batch, String inspectionResult) {
        if (isTerminalBatch(batch)) {
            return batch.getStatus();
        }
        return BusinessStatus.Quality.FAIL.equals(inspectionResult) ? BATCH_FROZEN : BATCH_AVAILABLE;
    }

    private boolean validInspectionQuantities(QmsInspectionOrder inspectionOrder, BigDecimal sampleQuantity,
                                              BigDecimal qualifiedQuantity,
                                              BigDecimal unqualifiedQuantity) {
        if (!nonNegativeSumMatches(sampleQuantity, qualifiedQuantity, unqualifiedQuantity)) {
            return false;
        }
        if (inspectionOrder.getReportId() == null) {
            return true;
        }
        MfgWorkReport report = findActiveReport(inspectionOrder.getReportId());
        return report != null && sampleQuantityMatchesReport(inspectionOrder, report, sampleQuantity);
    }

    /** 抽样数量为正、合格与不合格数量非负，且两者之和等于抽样数量。 */
    private boolean nonNegativeSumMatches(BigDecimal sampleQuantity, BigDecimal qualifiedQuantity,
                                          BigDecimal unqualifiedQuantity) {
        if (sampleQuantity == null || qualifiedQuantity == null || unqualifiedQuantity == null) {
            return false;
        }
        if (sampleQuantity.signum() <= 0 || qualifiedQuantity.signum() < 0 || unqualifiedQuantity.signum() < 0) {
            return false;
        }
        return qualifiedQuantity.add(unqualifiedQuantity).compareTo(sampleQuantity) == 0;
    }

    /** 读取未删除的报工记录。 */
    private MfgWorkReport findActiveReport(Long reportId) {
        LambdaQueryWrapper<MfgWorkReport> reportQuery = new LambdaQueryWrapper<>();
        reportQuery.eq(MfgWorkReport::getReportId, reportId)
                .eq(MfgWorkReport::getDeletedFlag, 0);
        return mfgWorkReportMapper.selectOne(reportQuery);
    }

    /** 抽样数量与报工的合格+不良数量比对：逐件检验要求相等，其余允许少于报工数量。 */
    private boolean sampleQuantityMatchesReport(QmsInspectionOrder inspectionOrder, MfgWorkReport report,
                                                BigDecimal sampleQuantity) {
        int compare = sampleQuantity.compareTo(reportProducedQuantity(report));
        return requiresPieceInspection(inspectionOrder) ? compare == 0 : compare <= 0;
    }

    /** 报工记录的合格加不良数量，空值按 0 处理。 */
    private BigDecimal reportProducedQuantity(MfgWorkReport report) {
        BigDecimal qualified = valueOrZero(report.getQualifiedQuantity());
        BigDecimal defect = valueOrZero(report.getDefectQuantity());
        return qualified.add(defect);
    }

    /** 空值按 0 处理。 */
    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 构建检验单查询条件，默认过滤已删除记录。
     * @param keyword 检验单号关键字
     * @param inspectionType 检验类型
     * @param schemeId 方案ID
     * @param workOrderId 工单ID
     * @param taskId 任务ID
     * @param reportId 报工ID
     * @param batchId 批次ID
     * @param inspectorId 检验员ID
     * @param status 状态
     * @param inspectionResult 检验结果
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<QmsInspectionOrder> buildInspectionOrderQuery(String keyword, String inspectionType,
                                                                             Long schemeId, Long workOrderId,
                                                                             Long taskId, Long reportId,
                                                                             Long batchId, Long productLotId, Long inspectorId,
                                                                             String status, String inspectionResult) {
        LambdaQueryWrapper<QmsInspectionOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QmsInspectionOrder::getDeletedFlag, 0)
                .like(StringUtils.hasText(keyword), QmsInspectionOrder::getInspectionNo, keyword)
                .eq(StringUtils.hasText(inspectionType), QmsInspectionOrder::getInspectionType, inspectionType)
                .eq(schemeId != null, QmsInspectionOrder::getSchemeId, schemeId)
                .eq(workOrderId != null, QmsInspectionOrder::getWorkOrderId, workOrderId)
                .eq(taskId != null, QmsInspectionOrder::getTaskId, taskId)
                .eq(reportId != null, QmsInspectionOrder::getReportId, reportId)
                .eq(batchId != null, QmsInspectionOrder::getBatchId, batchId)
                .eq(productLotId != null, QmsInspectionOrder::getProductLotId, productLotId)
                .eq(inspectorId != null, QmsInspectionOrder::getInspectorId, inspectorId)
                .eq(StringUtils.hasText(status), QmsInspectionOrder::getStatus, status)
                .eq(StringUtils.hasText(inspectionResult), QmsInspectionOrder::getInspectionResult, inspectionResult)
                .orderByDesc(QmsInspectionOrder::getCreatedTime);
        return queryWrapper;
    }

    /**
     * 补齐检验单默认值。
     * @param inspectionOrder 检验单实体
     */
    private void prepareInspectionOrder(QmsInspectionOrder inspectionOrder) {
        assertTargetExecutionSupported(inspectionOrder);
        // 只有显式指定了检验方案时才校验方案可执行性；自动生成的检验单可能不带方案，
        // 交由后续按产品/工序匹配或人工补录处理，不能因为 schemeId 为空就阻断建单。
        if (inspectionOrder.getSchemeId() != null) {
            preparationReview.assertInspectionExecutionSupported(inspectionOrder.getSchemeId());
        }
        inspectionOrder.setInspectionNo(numbers.nextFor("QI",inspectionOrder.getWorkOrderId()!=null?"WORK_ORDER":"LOT",
                inspectionOrder.getWorkOrderId()!=null?inspectionOrder.getWorkOrderId():inspectionOrder.getProductLotId()));
        inspectionOrder.setStatus(BusinessStatus.Document.DRAFT);
        inspectionOrder.setInspectionResult(null);
        if (inspectionOrder.getDeletedFlag() == null) {
            inspectionOrder.setDeletedFlag(0);
        }
    }

    /** 省略方案编号也不能绕过台灯执行门禁。 */
    private void assertTargetExecutionSupported(QmsInspectionOrder inspection) {
        if (inspection.getWorkOrderId() != null) {
            var order = executionWorkOrders.selectById(inspection.getWorkOrderId());
            if (order != null) executionGate.assertExecutionSupported(order.getProductId());
        }
        if (inspection.getProductLotId() != null) {
            var lot = getActiveProductLot(inspection.getProductLotId());
            if (lot != null) executionGate.assertExecutionSupported(lot.getProductId());
        }
    }

    /**
     * 提交检验结果的统一入口：需要逐件全检时只接受真实逐件结果，不需要时退回汇总提交。
     *
     * <p>方案里有全检项时，每件每个必检项都得有人真检过。这里绝不按数量自己编台账：
     * 编出来的结果会让批次和产品批次被判成合格并往下游流转，事后追溯还分不清是人工还是
     * 系统补的，危害远大于办不成。缺结果就直接拒绝，要求补齐后再提交。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean submitInspectionAutoPieces(Long inspectionId, String inspectionResult, String remark, Long operatorId,
                                              BigDecimal sampleQuantity, BigDecimal qualifiedQuantity,
                                              BigDecimal unqualifiedQuantity,
                                              List<QmsInspectionResult> pieceResults) {
        QmsInspectionOrder order = inspectionId == null ? null : getInspectionOrderById(inspectionId);
        if (order == null) {
            throw business(ResultCode.NOT_FOUND, "检验单不存在或已删除");
        }
        if (!requiresPieceInspection(order)) {
            return submitInspectionOrder(inspectionId, inspectionResult, remark, operatorId,
                    sampleQuantity, qualifiedQuantity, unqualifiedQuantity);
        }
        if (pieceResults == null || pieceResults.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "本方案要求逐件全检，缺少每件产品的实际检验结果，请补齐后再生效");
        }
        for (QmsInspectionResult row : pieceResults) {
            if (row == null) {
                throw business(ResultCode.PARAM_ERROR, "逐件检验结果不能有空行");
            }
            row.setInspectionId(inspectionId);
        }
        return submitPieceInspection(inspectionId, pieceResults, remark, operatorId);
    }

    /**
     * 逐件全检提交：先校验每件产品的每个必检项都有结果，再把件结论汇总回检验单。
     * 缺项、重复项、未知检验项一律拒绝，避免只填汇总数量而没有逐件依据。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean submitPieceInspection(Long inspectionId, List<QmsInspectionResult> results, String remark, Long operatorId) {
        if (inspectionId == null || operatorId == null || results == null || results.isEmpty()) {
            throw business(ResultCode.PARAM_ERROR, "检验单、逐件检验结果和操作人都不能为空");
        }
        QmsInspectionOrder existing = getInspectionOrderById(inspectionId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "检验单不存在或已删除");
        }
        if (!BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待检验状态的检验单可以提交逐件结果");
        }
        Set<Long> requiredItems = requiredItemIds(existing);
        if (requiredItems.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "检验单未绑定检验方案，无法按逐件全检提交");
        }
        Map<String, Set<Long>> itemsByPiece = collectItemsByPiece(results, requiredItems);
        requireCompleteItems(itemsByPiece, requiredItems);
        requireReportedDefectPieces(existing, itemsByPiece, results);
        PieceVerdicts verdicts = countPieceVerdicts(itemsByPiece, results);
        BigDecimal sampleQuantity = BigDecimal.valueOf(itemsByPiece.size());
        requirePieceSampleMatch(existing, sampleQuantity);
        boolean concession = results.stream().anyMatch(r -> BusinessStatus.Quality.CONCESSION.equals(r.getResult()));
        String verdict = resolvePieceVerdict(verdicts.unqualifiedPieces(), concession);
        assertProductionDefectsPreserved(existing, BigDecimal.valueOf(verdicts.qualifiedPieces()),
                BigDecimal.valueOf(verdicts.unqualifiedPieces()), verdict);
        // 完整性和生产不良下限核对后再替换草稿，避免覆盖原始证据。
        softDeleteResults(inspectionId);
        insertResults(inspectionId, results);
        boolean submitted = submitInspectionInternal(inspectionId, verdict, remark, operatorId,
                sampleQuantity, BigDecimal.valueOf(verdicts.qualifiedPieces()),
                BigDecimal.valueOf(verdicts.unqualifiedPieces()), true);
        if (!submitted) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "逐件结果已登记，但检验单汇总提交失败，请核对抽样数量与来源报工的合格加不良数量是否匹配");
        }
        return true;
    }

    /** 固定报工不良的列表位置，不把数量约束变成可以任意移动的不良身份。 */
    private void requireReportedDefectPieces(QmsInspectionOrder inspection,
            Map<String, Set<Long>> itemsByPiece, List<QmsInspectionResult> results) {
        if (inspection.getReportId() == null) return;
        MfgWorkReport report = mfgWorkReportMapper.selectOne(new LambdaQueryWrapper<MfgWorkReport>()
                .eq(MfgWorkReport::getReportId, inspection.getReportId())
                .eq(MfgWorkReport::getDeletedFlag, 0).eq(MfgWorkReport::getAuditStatus, "APPROVED"));
        if (report == null || valueOrZero(report.getDefectQuantity()).signum() <= 0) return;
        int total = valueOrZero(report.getQualifiedQuantity()).add(report.getDefectQuantity()).intValueExact();
        int defects = report.getDefectQuantity().intValueExact();
        if (itemsByPiece.size() != total) throw business(ResultCode.BUSINESS_ERROR, "逐件数量必须与来源报工一致");
        for (int number = total - defects + 1; number <= total; number++) {
            String piece = inspection.getInspectionNo() + "-" + String.format(java.util.Locale.ROOT, "%04d", number);
            if (!itemsByPiece.containsKey(piece) || !BusinessStatus.Quality.FAIL.equals(pieceVerdict(piece, results)))
                throw business(ResultCode.BUSINESS_ERROR, "报工不良定位件 " + piece + " 已锁定为不合格，请填写实际不合格项目，不能改为合格或更换件号");
        }
    }

    /** 逐件结论汇总：合格件数与不合格件数。 */
    private record PieceVerdicts(long qualifiedPieces, long unqualifiedPieces) {
    }

    /** 校验每条结果的件号、实测值和检验项，并按件号归集已登记的检验项。 */
    private Map<String, Set<Long>> collectItemsByPiece(List<QmsInspectionResult> results, Set<Long> requiredItems) {
        Map<String, Set<Long>> itemsByPiece = new LinkedHashMap<>();
        for (QmsInspectionResult result : results) {
            String pieceNo = result.getPieceNo() == null ? null : result.getPieceNo().trim();
            if (!StringUtils.hasText(pieceNo)) {
                throw business(ResultCode.PARAM_ERROR, "逐件全检要求每条结果都写明件号");
            }
            if (!StringUtils.hasText(result.getResult())) {
                throw business(ResultCode.PARAM_ERROR, "件号 " + pieceNo + " 缺少检验判定");
            }
            normalizeInspectionResult(result);
            if (result.getItemId() == null) {
                throw business(ResultCode.PARAM_ERROR, "件号 " + pieceNo + " 的检验结果缺少检验项");
            }
            if (!requiredItems.contains(result.getItemId())) {
                throw business(ResultCode.BUSINESS_ERROR, "检验项 " + result.getItemId() + " 不在本检验单的必检项内");
            }
            result.setPieceNo(pieceNo);
            Set<Long> provided = itemsByPiece.computeIfAbsent(pieceNo, key -> new LinkedHashSet<>());
            if (!provided.add(result.getItemId())) {
                throw business(ResultCode.BUSINESS_ERROR, "件号 " + pieceNo + " 的同一检验项重复登记，请合并后再提交");
            }
        }
        return itemsByPiece;
    }

    /** 每件产品都必须把必检项全部检完。 */
    private void requireCompleteItems(Map<String, Set<Long>> itemsByPiece, Set<Long> requiredItems) {
        for (Map.Entry<String, Set<Long>> entry : itemsByPiece.entrySet()) {
            Set<Long> missing = new LinkedHashSet<>(requiredItems);
            missing.removeAll(entry.getValue());
            if (!missing.isEmpty()) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "件号 " + entry.getKey() + " 还缺 " + missing.size() + " 个必检项结果，逐件全检要求每件全部检完");
            }
        }
    }

    /** 按件结论统计合格与不合格件数。 */
    private PieceVerdicts countPieceVerdicts(Map<String, Set<Long>> itemsByPiece, List<QmsInspectionResult> results) {
        long qualifiedPieces = 0;
        long unqualifiedPieces = 0;
        for (String pieceNo : itemsByPiece.keySet()) {
            String pieceResult = pieceVerdict(pieceNo, results);
            if (BusinessStatus.Quality.FAIL.equals(pieceResult)) {
                unqualifiedPieces++;
            } else {
                qualifiedPieces++;
            }
        }
        return new PieceVerdicts(qualifiedPieces, unqualifiedPieces);
    }

    /** 逐件全检件数必须等于检验单的实际检验数量。 */
    private void requirePieceSampleMatch(QmsInspectionOrder existing, BigDecimal sampleQuantity) {
        boolean checkRequired = requiresPieceInspection(existing) && existing.getReportId() == null
                && existing.getSampleQuantity() != null && existing.getSampleQuantity().signum() > 0;
        if (checkRequired && sampleQuantity.compareTo(existing.getSampleQuantity()) != 0) {
            throw business(ResultCode.BUSINESS_ERROR, "逐件全检件数必须等于本检验单的实际检验数量");
        }
    }

    /** 由逐件结论决定整单结论：有不合格即不合格，否则有让步则让步，其余合格。 */
    private String resolvePieceVerdict(long unqualifiedPieces, boolean concession) {
        if (unqualifiedPieces > 0) {
            return BusinessStatus.Quality.FAIL;
        }
        if (concession) {
            return BusinessStatus.Quality.CONCESSION;
        }
        return BusinessStatus.Quality.PASS;
    }

    @Override
    public List<QmsInspectionResult> listPieceResults(Long inspectionId) {
        if (inspectionId == null) {
            return List.of();
        }
        LambdaQueryWrapper<QmsInspectionResult> query = new LambdaQueryWrapper<>();
        query.eq(QmsInspectionResult::getInspectionId, inspectionId)
                .eq(QmsInspectionResult::getDeletedFlag, 0)
                .orderByAsc(QmsInspectionResult::getPieceNo)
                .orderByAsc(QmsInspectionResult::getItemId);
        return qmsInspectionResultMapper.selectList(query);
    }

    /**
     * 取检验单绑定的必检项集合；方案缺失时返回空集合，由调用方拒绝提交。
     */
    private boolean requiresPieceInspection(QmsInspectionOrder inspection) {
        if(inspection.getSchemeId()==null)return false;
        List<QmsInspectionSchemeItem> rows=qmsInspectionSchemeItemMapper.selectList(new LambdaQueryWrapper<QmsInspectionSchemeItem>()
                .eq(QmsInspectionSchemeItem::getSchemeId,inspection.getSchemeId()).eq(QmsInspectionSchemeItem::getDeletedFlag,0));
        return rows!=null && rows.stream().anyMatch(row->"ALL".equals(row.getSamplingMode()));
    }

    private Set<Long> requiredItemIds(QmsInspectionOrder inspectionOrder) {
        if (inspectionOrder.getSchemeId() == null) {
            return Set.of();
        }
        LambdaQueryWrapper<QmsInspectionSchemeItem> query = new LambdaQueryWrapper<>();
        query.eq(QmsInspectionSchemeItem::getSchemeId, inspectionOrder.getSchemeId())
                .eq(QmsInspectionSchemeItem::getDeletedFlag, 0);
        Set<Long> items = new LinkedHashSet<>();
        for (QmsInspectionSchemeItem item : qmsInspectionSchemeItemMapper.selectList(query)) {
            if (item.getItemId() != null) {
                items.add(item.getItemId());
            }
        }
        return items;
    }

    /**
     * 单件结论：有任一检验项不合格即判不合格，出现让步接收记让步，否则合格。
     */
    private String pieceVerdict(String pieceNo, List<QmsInspectionResult> results) {
        boolean concession = false;
        for (QmsInspectionResult result : results) {
            if (!pieceNo.equals(result.getPieceNo())) {
                continue;
            }
            String normalized = result.getResult() == null ? null : BusinessStatus.normalizeCode(result.getResult());
            if (BusinessStatus.Quality.FAIL.equals(normalized)) {
                return BusinessStatus.Quality.FAIL;
            }
            if (BusinessStatus.Quality.CONCESSION.equals(normalized)) {
                concession = true;
            }
        }
        return concession ? BusinessStatus.Quality.CONCESSION : BusinessStatus.Quality.PASS;
    }

    /**
     * 批量插入检验结果明细。
     * @param inspectionId 检验单ID
     * @param results 检验结果列表
     */
    private void insertResults(Long inspectionId, List<QmsInspectionResult> results) {
        if (results == null) {
            return;
        }
        for (QmsInspectionResult result : results) {
            normalizeInspectionResult(result);
            result.setResultId(null);
            result.setInspectionId(inspectionId);
            // 逐件草稿可能被软删除过：同一（检验单, 必检项, 件号）只能存在一行，恢复它而不是新插一行。
            if (reviveSoftDeletedPiece(inspectionId, result)) {
                continue;
            }
            if (result.getDeletedFlag() == null) {
                result.setDeletedFlag(0);
            }
            qmsInspectionResultMapper.insert(result);
        }
    }

    /** 命中已软删除的同件同项记录时就地恢复并刷新取值；命中未删除记录说明重复提交。 */
    private boolean reviveSoftDeletedPiece(Long inspectionId, QmsInspectionResult result) {
        if (!StringUtils.hasText(result.getPieceNo()) || result.getItemId() == null) {
            return false;
        }
        QmsInspectionResult saved = qmsInspectionResultMapper.selectOne(new LambdaQueryWrapper<QmsInspectionResult>()
                .eq(QmsInspectionResult::getInspectionId, inspectionId).eq(QmsInspectionResult::getItemId, result.getItemId())
                .eq(QmsInspectionResult::getPieceNo, result.getPieceNo()).last("FOR UPDATE"));
        if (saved == null) {
            return false;
        }
        if (!Integer.valueOf(1).equals(saved.getDeletedFlag())) {
            throw business(ResultCode.DATA_CONFLICT, "同一件产品的检验项目重复，请刷新草稿后重试");
        }
        var update = new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<QmsInspectionResult>()
                .eq("result_id", saved.getResultId()).eq("inspection_id", inspectionId).eq("deleted_flag", 1)
                .set("deleted_flag", 0).set("result", result.getResult()).set("measured_value", result.getMeasuredValue())
                .set("defect_description", result.getDefectDescription()).set("photo_file_id", result.getPhotoFileId())
                .set("remark", result.getRemark()).set("updated_time", LocalDateTime.now())
                .set("version", saved.getVersion() == null ? 1 : saved.getVersion() + 1);
        if (qmsInspectionResultMapper.update(null, update) != 1) {
            throw business(ResultCode.DATA_CONFLICT, "逐件草稿已变化，请刷新后重试");
        }
        return true;
    }

    private void normalizeInspectionResult(QmsInspectionResult inspectionResult) {
        if (!StringUtils.hasText(inspectionResult.getResult())) {
            return;
        }
        String normalized = BusinessStatus.normalizeCode(inspectionResult.getResult());
        if (!BusinessStatus.isInspectionResult(normalized)) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        inspectionResult.setResult(normalized);
    }

    /**
     * 软删除指定检验单的全部检验结果明细。
     * @param inspectionId 检验单ID
     */
    private void softDeleteResults(Long inspectionId) {
        LambdaQueryWrapper<QmsInspectionResult> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QmsInspectionResult::getInspectionId, inspectionId)
                .eq(QmsInspectionResult::getDeletedFlag, 0);
        List<QmsInspectionResult> results = qmsInspectionResultMapper.selectList(queryWrapper);
        for (QmsInspectionResult result : results) {
            QmsInspectionResult update = new QmsInspectionResult();
            update.setResultId(result.getResultId());
            update.setDeletedFlag(1);
            qmsInspectionResultMapper.updateById(update);
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

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }
}
