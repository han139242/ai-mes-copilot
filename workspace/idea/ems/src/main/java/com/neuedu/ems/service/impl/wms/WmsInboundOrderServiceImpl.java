package com.neuedu.ems.service.impl.wms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.trace.TraceProductLot;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.mapper.wms.WmsInboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.trace.TraceProductLotMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.service.impl.ai.InventoryAutomationTrigger;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import com.neuedu.ems.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 入库单管理 Service 实现类。
 */
@Service
public class WmsInboundOrderServiceImpl extends ServiceImpl<WmsInboundOrderMapper, WmsInboundOrder> implements WmsInboundOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;

    @Autowired(required = false)
    private InventoryAutomationTrigger inventoryAutomationTrigger;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WmsInboundOrderServiceImpl.class);

    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.mdm.MaterialExecutionQuantityService executionQuantities;


    private static final String SUBMITTED_STATUS = BusinessStatus.Document.SUBMITTED;
    private static final String APPROVED_STATUS = BusinessStatus.Document.APPROVED;
    private static final Set<String> RELEASED_QUALITY_STATUSES = Set.of(
            BusinessStatus.Quality.PASS, BusinessStatus.Quality.CONCESSION);
    private static final Set<String> STORABLE_BATCH_STATUSES = Set.of("AVAILABLE", "ENABLE");

    @Autowired
    private WmsInboundDetailMapper wmsInboundDetailMapper;

    @Autowired
    private WmsInventoryMapper wmsInventoryMapper;

    @Autowired
    private WmsStockMovementMapper wmsStockMovementMapper;

    @Autowired
    private WmsMaterialBatchMapper wmsMaterialBatchMapper;

    @Autowired
    private com.neuedu.ems.mapper.wms.WmsLocationMapper inboundLocationMapper;

    @Autowired
    private TraceProductLotMapper traceProductLotMapper;

    @Autowired
    private MdmProductMapper mdmProductMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private WmsResponsibilityNotificationService responsibilityNotificationService;

    /** 采购入库确认后按单头来源回写采购订单已收数量；用 required=false 避免与订单服务形成强绑定。 */
    @Autowired(required = false)
    private com.neuedu.ems.service.mfg.MfgPurchaseOrderService purchaseOrderService;

    @Autowired(required = false)
    private com.neuedu.ems.mapper.wms.WmsWarehouseMapper wmsWarehouseMapper;

    /**
     * 自动化编排依赖：采购入库确认后自动签发来料检验任务。
     * 用 required=false 是为了让不关注编排的单元测试无需构造检验单服务。
     */
    @Autowired(required = false)
    private com.neuedu.ems.service.qms.QmsInspectionOrderService qmsInspectionOrderService;

    @Autowired(required = false)
    private com.neuedu.ems.mapper.mdm.MdmMaterialMapper mdmMaterialMapper;

    @Autowired(required = false)
    private com.neuedu.ems.mapper.mdm.MdmBomMapper mdmBomMapper;

    @Autowired(required = false)
    private com.neuedu.ems.mapper.mdm.MdmBomItemMapper mdmBomItemMapper;

    @Autowired(required = false)
    private com.neuedu.ems.mapper.qms.QmsInspectionSchemeMapper qmsInspectionSchemeMapper;

    @Autowired(required = false)
    private com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper qmsInspectionOrderMapper;

    /** 事件发布器：入库单进入待确认时通知 AI 多步任务，避免业务服务直接依赖任务服务。 */
    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher taskEventPublisher;

    private static final String PURCHASE_INBOUND_TYPE = "PURCHASE";
    @Autowired(required = false) private com.neuedu.ems.service.mfg.ComponentHandoverService componentHandover;
    private static final Set<String> INCOMING_INSPECTION_MATERIAL_TYPES = Set.of("RAW", "AUXILIARY");
    private static final String INCOMING_INSPECTION_TYPE = "INCOMING";

    /**
     * 分页查询入库单列表，支持入库单号、类型、仓库、来源单据和审核状态筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 关键字（匹配入库单号）
     * @param inboundType 入库类型
     * @param warehouseId 仓库ID
     * @param sourceOrderType 来源单据类型
     * @param sourceOrderId 来源单据ID
     * @param auditStatus 审核状态
     * @return 分页结果
     */
    @Override
    public Page<WmsInboundOrder> pageInboundOrders(Integer current, Integer size, String keyword, String inboundType,
                                                   Long warehouseId, String sourceOrderType, Long sourceOrderId, String auditStatus) {
        Page<WmsInboundOrder> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildInboundOrderQuery(keyword, inboundType, warehouseId, sourceOrderType, sourceOrderId, auditStatus));
    }

    /**
     * 查询入库单列表，支持入库单号、类型、仓库、来源单据和审核状态筛选。
     * @param keyword 关键字（匹配入库单号）
     * @param inboundType 入库类型
     * @param warehouseId 仓库ID
     * @param sourceOrderType 来源单据类型
     * @param sourceOrderId 来源单据ID
     * @param auditStatus 审核状态
     * @return 入库单列表
     */
    @Override
    public List<WmsInboundOrder> listInboundOrders(String keyword, String inboundType, Long warehouseId,
                                                   String sourceOrderType, Long sourceOrderId, String auditStatus) {
        return baseMapper.selectList(buildInboundOrderQuery(keyword, inboundType, warehouseId, sourceOrderType, sourceOrderId, auditStatus));
    }

    /**
     * 根据入库单ID查询未删除的入库单详情。
     * @param inboundId 入库单ID
     * @return 入库单实体
     */
    @Override
    public WmsInboundOrder getInboundOrderById(Long inboundId) {
        LambdaQueryWrapper<WmsInboundOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsInboundOrder::getInboundId, inboundId)
                .eq(WmsInboundOrder::getDeletedFlag, 0);
        return baseMapper.selectOne(queryWrapper);
    }

    /**
     * 新增入库单，自动设置删除标志为0。
     * @param inboundOrder 入库单实体
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean saveInboundOrder(WmsInboundOrder inboundOrder) {
        if (inboundOrder == null || inboundOrder.getWarehouseId() == null) {
            return false;
        }
        if ("PRODUCTION".equalsIgnoreCase(inboundOrder.getInboundType())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "生产入库不能空白新建：必须从质检放行的产品批次发起并自动带入成品物料和可入数量");
        }
        inboundOrder.setInboundNo(numbers.next("IN",null));
        inboundOrder.setAuditStatus(SUBMITTED_STATUS);
        inboundOrder.setInboundTime(null);
        if (inboundOrder.getDeletedFlag() == null) {
            inboundOrder.setDeletedFlag(0);
        }
        boolean saved = baseMapper.insert(inboundOrder) > 0;
        if (saved) {
            responsibilityNotificationService.publishInboundPending(inboundOrder, inboundOrder.getCreatedBy());
            publishTaskTrigger(inboundOrder);
        }
        return saved;
    }

    /**
     * 新增入库单并保存明细。
     * @param inboundOrder 入库单实体
     * @param details 入库明细列表
     * @return 入库单ID
     */
    @Override
    @Transactional
    public Long saveInboundOrderFull(WmsInboundOrder inboundOrder, List<WmsInboundDetail> details) {
        if (!saveInboundOrder(inboundOrder)) {
            throw business(ResultCode.PARAM_ERROR, "入库单和入库仓库不能为空");
        }
        insertDetails(inboundOrder.getInboundId(), details, false);
        if (details != null) issueIncomingInspections(inboundOrder, details, inboundOrder.getCreatedBy(), LocalDateTime.now());
        return inboundOrder.getInboundId();
    }

    private void preparePurchaseBatch(WmsInboundOrder order, WmsInboundDetail detail) {
        if (!PURCHASE_INBOUND_TYPE.equals(order.getInboundType()) || detail.getBatchId() != null) {
            return;
        }
        // 采购订单下达时只是待收货草稿，到货编辑补齐库位后再自动建批。
        if ("PURCHASE_ORDER".equals(order.getSourceOrderType()) && detail.getLocationId() == null) {
            return;
        }
        if (detail.getMaterialId() == null || detail.getLocationId() == null) {
            throw business(ResultCode.PARAM_ERROR, "请选择入库物料和库位");
        }
        executionQuantities.validate(detail.getMaterialId(), detail.getInboundQuantity(), false);
        validateInboundLocation(order, detail);
        WmsMaterialBatch existing = wmsMaterialBatchMapper.selectOne(new LambdaQueryWrapper<WmsMaterialBatch>()
                .eq(WmsMaterialBatch::getSourceOrderType, "PURCHASE_RECEIPT")
                .eq(WmsMaterialBatch::getSourceOrderId, order.getInboundId())
                .eq(WmsMaterialBatch::getMaterialId, detail.getMaterialId())
                .eq(WmsMaterialBatch::getDeletedFlag, 0).last("LIMIT 1"));
        if (existing != null) {
            detail.setBatchId(existing.getBatchId());
            return;
        }
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchNo("RM-" + order.getInboundNo() + "-" + detail.getMaterialId());
        batch.setMaterialId(detail.getMaterialId());
        batch.setReceivedDate(LocalDate.now());
        batch.setQualityStatus("WAIT_INSPECTION");
        batch.setStatus("FROZEN");
        batch.setSourceOrderType("PURCHASE_RECEIPT");
        batch.setSourceOrderId(order.getInboundId());
        batch.setRemark("采购到货自动建批；来料检验放行后确认入库");
        batch.setCreatedBy(order.getCreatedBy());
        batch.setCreatedTime(LocalDateTime.now());
        batch.setDeletedFlag(0);
        wmsMaterialBatchMapper.insert(batch);
        detail.setBatchId(batch.getBatchId());
    }

    private void validateInboundLocation(WmsInboundOrder order, WmsInboundDetail detail) {
        com.neuedu.ems.entity.wms.WmsLocation location = inboundLocationMapper.selectById(detail.getLocationId());
        if (location == null || !Integer.valueOf(0).equals(location.getDeletedFlag())
                || !Objects.equals(location.getWarehouseId(), order.getWarehouseId())) {
            throw business(ResultCode.PARAM_ERROR, "入库库位必须属于所选仓库，请重新选择库位");
        }
    }

    /**
     * 逐条写入入库明细，可选择性清空明细主键。
     * @param inboundId 入库单ID
     * @param details 入库明细列表，可为空
     * @param resetDetailId 是否清空明细主键（替换明细场景）
     */
    private void insertDetails(Long inboundId, List<WmsInboundDetail> details, boolean resetDetailId) {
        if (details == null) {
            return;
        }
        for (WmsInboundDetail detail : details) {
            if (resetDetailId) {
                detail.setDetailId(null);
            }
            detail.setInboundId(inboundId);
            preparePurchaseBatch(getInboundOrderById(inboundId), detail);
            if (detail.getDeletedFlag() == null) {
                detail.setDeletedFlag(0);
            }
            wmsInboundDetailMapper.insert(detail);
        }
    }

    /**
     * 更新入库单信息，不允许修改删除标志。
     * @param inboundOrder 入库单实体
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean updateInboundOrder(WmsInboundOrder inboundOrder) {
        if (inboundOrder.getInboundId() == null) {
            return false;
        }
        WmsInboundOrder existing = getInboundOrderById(inboundOrder.getInboundId());
        if (existing == null || !SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            return false;
        }
        inboundOrder.setDeletedFlag(null);
        inboundOrder.setInboundNo(null);
        inboundOrder.setAuditStatus(null);
        inboundOrder.setInboundTime(null);
        inboundOrder.setInboundType(null);
        inboundOrder.setSourceOrderType(null);
        inboundOrder.setSourceOrderId(null);
        if (inboundOrder.getVersion() == null) {
            inboundOrder.setVersion(existing.getVersion());
        }
        boolean warehouseChanged = inboundOrder.getWarehouseId() != null
                && !Objects.equals(existing.getWarehouseId(), inboundOrder.getWarehouseId());
        boolean updated = baseMapper.updateById(inboundOrder) > 0;
        if (updated && warehouseChanged) {
            responsibilityNotificationService.cancelInboundPending(existing.getInboundId(), inboundOrder.getUpdatedBy());
            WmsInboundOrder refreshed = getInboundOrderById(existing.getInboundId());
            responsibilityNotificationService.publishInboundPending(refreshed, inboundOrder.getUpdatedBy());
            publishTaskTrigger(refreshed);
        }
        return updated;
    }

    /**
     * 更新入库单并替换明细。
     * @param inboundOrder 入库单实体
     * @param details 入库明细列表
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean updateInboundOrderFull(WmsInboundOrder inboundOrder, List<WmsInboundDetail> details) {
        if (!updateInboundOrder(inboundOrder)) {
            return false;
        }
        if (details != null) {
            softDeleteDetails(inboundOrder.getInboundId());
        }
        insertDetails(inboundOrder.getInboundId(), details, true);
        if (details != null) issueIncomingInspections(getInboundOrderById(inboundOrder.getInboundId()), details,
                inboundOrder.getUpdatedBy(), LocalDateTime.now());
        return true;
    }

    /**
     * 根据ID删除入库单和明细（软删除，将删除标志设为1）。
     * @param inboundId 入库单ID
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean removeInboundOrderById(Long inboundId) {
        WmsInboundOrder existing = getInboundOrderById(inboundId);
        if (existing == null || !SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            return false;
        }
        WmsInboundOrder inboundOrder = new WmsInboundOrder();
        inboundOrder.setInboundId(inboundId);
        inboundOrder.setDeletedFlag(1);
        inboundOrder.setVersion(existing.getVersion());
        boolean orderRemoved = baseMapper.updateById(inboundOrder) > 0;
        softDeleteDetails(inboundId);
        if (orderRemoved) {
            responsibilityNotificationService.cancelInboundPending(inboundId, existing.getUpdatedBy());
        }
        return orderRemoved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawInboundOrder(Long inboundId, Long operatorId, String remark) {
        WmsInboundOrder existing = getInboundOrderById(inboundId);
        if (existing == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "入库单和操作人不能为空");
        }
        remark = StringUtils.hasText(remark) ? remark.trim() : "用户主动撤回，未填写补充原因";
        if (!SUBMITTED_STATUS.equals(existing.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有未确认入库单可以撤回");
        }
        LocalDateTime now = LocalDateTime.now();
        WmsInboundOrder update = new WmsInboundOrder();
        update.setInboundId(inboundId); update.setVersion(existing.getVersion());
        update.setAuditStatus(BusinessStatus.Document.CLOSED); update.setUpdatedBy(operatorId); update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) throw business(ResultCode.DATA_CONFLICT, "入库单已被并发修改");
        SysStatusChangeLog status = new SysStatusChangeLog();
        status.setBizType("WMS_INBOUND_ORDER"); status.setBizId(inboundId); status.setOldStatus(existing.getAuditStatus());
        status.setNewStatus(BusinessStatus.Document.CLOSED); status.setChangeReason("WITHDRAW_INBOUND");
        status.setChangedBy(operatorId); status.setChangedTime(now); status.setCreatedBy(operatorId);
        status.setCreatedTime(now); status.setRemark(remark); status.setDeletedFlag(0);
        if (sysStatusChangeLogMapper.insert(status) == 0) throw new BusinessException(ResultCode.FAILED);
        SysOperationLog operation = new SysOperationLog();
        operation.setUserId(operatorId); operation.setOperationType("WITHDRAW_INBOUND");
        operation.setModuleCode("wms_inbound_order"); operation.setBizId(inboundId); operation.setOperationTime(now);
        operation.setCreatedBy(operatorId); operation.setCreatedTime(now); operation.setRemark(remark); operation.setDeletedFlag(0);
        if (sysOperationLogMapper.insert(operation) == 0) throw new BusinessException(ResultCode.FAILED);
        responsibilityNotificationService.cancelInboundPending(inboundId, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsInboundOrder generateProductionInbound(Long productLotId, Long warehouseId, Long locationId,
                                                      Long operatorId, String remark) {
        requireProductionInboundParams(productLotId, warehouseId, locationId, operatorId);
        TraceProductLot productLot = requireReleasedProductLot(productLotId);
        WmsInboundOrder existing = findPendingProductionInbound(productLotId);
        if (existing != null) {
            return existing;
        }
        MdmProduct product = requireInboundProduct(productLot.getProductId());
        BigDecimal remaining = remainingInboundQuantity(productLot);
        LocalDateTime now = LocalDateTime.now();
        WmsMaterialBatch finishedBatch = resolveFinishedBatch(productLot, product, operatorId, now);
        WmsInboundOrder order = insertProductionInbound(warehouseId, productLotId, remark, operatorId, now);
        insertProductionInboundDetail(order, product, finishedBatch, locationId, remaining,
                productLot.getLotNo(), operatorId, now);
        insertGeneratedInboundAudit(order, productLotId, operatorId, now);
        responsibilityNotificationService.publishInboundPending(order, operatorId);
        publishTaskTrigger(order);
        return order;
    }

    /** 入参必填校验。 */
    private void requireProductionInboundParams(Long productLotId, Long warehouseId, Long locationId,
                                                       Long operatorId) {
        if (productLotId == null || warehouseId == null || locationId == null || operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "产品批次、目标仓库、目标库位和操作人不能为空");
        }
    }

    /** 产品批次必须存在且已终检合格或让步放行。 */
    private TraceProductLot requireReleasedProductLot(Long productLotId) {
        TraceProductLot productLot = traceProductLotMapper.selectOne(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getProductLotId, productLotId)
                .eq(TraceProductLot::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (productLot == null) {
            throw business(ResultCode.NOT_FOUND, "产品批次不存在或已删除");
        }
        if (!RELEASED_QUALITY_STATUSES.contains(productLot.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有终检合格或让步放行的产品批次可以生成生产入库");
        }
        return productLot;
    }

    /** 同一产品批次只允许一张待确认生产入库单，存在则直接复用。 */
    private WmsInboundOrder findPendingProductionInbound(Long productLotId) {
        List<WmsInboundOrder> activeOrders = baseMapper.selectList(new LambdaQueryWrapper<WmsInboundOrder>()
                .eq(WmsInboundOrder::getInboundType, "PRODUCTION")
                .eq(WmsInboundOrder::getSourceOrderType, "PRODUCT_LOT")
                .eq(WmsInboundOrder::getSourceOrderId, productLotId)
                .eq(WmsInboundOrder::getDeletedFlag, 0)
                .eq(WmsInboundOrder::getAuditStatus, SUBMITTED_STATUS));
        if (activeOrders.size() > 1) {
            throw business(ResultCode.DATA_CONFLICT, "该产品批次存在多张待确认生产入库单，请先处理重复数据");
        }
        return activeOrders.isEmpty() ? null : activeOrders.get(0);
    }

    /** 产品必须已关联启用的成品物料。 */
    private MdmProduct requireInboundProduct(Long productId) {
        MdmProduct product = mdmProductMapper.selectOne(new LambdaQueryWrapper<MdmProduct>()
                .eq(MdmProduct::getProductId, productId)
                .eq(MdmProduct::getStatus, BusinessStatus.Common.ENABLE)
                .eq(MdmProduct::getDeletedFlag, 0));
        if (product == null || product.getMaterialId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "产品未关联启用的成品物料，不能生成生产入库");
        }
        return product;
    }

    /** 产品批次剩余可入库数量必须大于零。 */
    private BigDecimal remainingInboundQuantity(TraceProductLot productLot) {
        BigDecimal remaining = releasedInboundQuantity(productLot)
                .subtract(approvedProductionInboundQuantity(productLot.getProductLotId()))
                .subtract(componentHandover == null ? BigDecimal.ZERO : componentHandover.deliveredLot(productLot.getProductLotId()));
        if (remaining.signum() <= 0) {
            throw business(ResultCode.BUSINESS_ERROR, "产品批次已入库或已直接交接给上级工单，没有剩余可入数量");
        }
        return remaining;
    }

    /** 新建生产入库单主表。 */
    private WmsInboundOrder insertProductionInbound(Long warehouseId, Long productLotId, String remark,
                                                    Long operatorId, LocalDateTime now) {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setInboundNo(numbers.nextFor("IN","LOT",productLotId));
        order.setInboundType("PRODUCTION");
        order.setWarehouseId(warehouseId);
        order.setSourceOrderType("PRODUCT_LOT");
        order.setSourceOrderId(productLotId);
        order.setAuditStatus(SUBMITTED_STATUS);
        order.setRemark(StringUtils.hasText(remark) ? remark.trim() : "产品批次终检放行后生成生产入库");
        order.setVersion(0);
        order.setCreatedBy(operatorId);
        order.setCreatedTime(now);
        order.setUpdatedBy(operatorId);
        order.setUpdatedTime(now);
        order.setDeletedFlag(0);
        if (baseMapper.insert(order) == 0 || order.getInboundId() == null) {
            throw business(ResultCode.FAILED, "生产入库单生成失败");
        }
        return order;
    }

    /** 写一条生产入库明细。 */
    private void insertProductionInboundDetail(WmsInboundOrder order, MdmProduct product,
                                               WmsMaterialBatch finishedBatch, Long locationId,
                                               BigDecimal remaining, String lotNo,
                                               Long operatorId, LocalDateTime now) {
        WmsInboundDetail detail = new WmsInboundDetail();
        detail.setInboundId(order.getInboundId());
        detail.setMaterialId(product.getMaterialId());
        detail.setBatchId(finishedBatch.getBatchId());
        detail.setLocationId(locationId);
        detail.setInboundQuantity(remaining);
        detail.setRemark("来源产品批次：" + lotNo);
        detail.setVersion(0);
        detail.setCreatedBy(operatorId);
        detail.setCreatedTime(now);
        detail.setUpdatedBy(operatorId);
        detail.setUpdatedTime(now);
        detail.setDeletedFlag(0);
        if (wmsInboundDetailMapper.insert(detail) == 0) {
            throw business(ResultCode.FAILED, "生产入库明细生成失败");
        }
    }

    /**
     * 入库单进入待确认时向 AI 多步任务发一条事件。
     * 只有待确认状态才发，且单据ID为空直接跳过；监听方在业务事务提交后执行，失败不影响入库单。
     */
    private void publishTaskTrigger(WmsInboundOrder order) {
        if (taskEventPublisher == null || order == null || order.getInboundId() == null) {
            return;
        }
        if (!SUBMITTED_STATUS.equals(order.getAuditStatus())) {
            return;
        }
        taskEventPublisher.publishEvent(new com.neuedu.ems.common.AiTaskTriggerEvent(
                "WMS_INBOUND_PENDING", "WMS_INBOUND_ORDER", order.getInboundId(), order.getInboundNo()));
    }

    private WmsMaterialBatch resolveFinishedBatch(TraceProductLot productLot, MdmProduct product,
                                                  Long operatorId, LocalDateTime now) {
        List<WmsMaterialBatch> batches = wmsMaterialBatchMapper.selectList(new LambdaQueryWrapper<WmsMaterialBatch>()
                .eq(WmsMaterialBatch::getSourceProductLotId, productLot.getProductLotId())
                .eq(WmsMaterialBatch::getDeletedFlag, 0));
        if (batches.size() > 1) {
            throw business(ResultCode.DATA_CONFLICT, "产品批次关联了多个成品物料批次，请先修复追溯数据");
        }
        if (batches.isEmpty()) {
            return createFinishedBatch(productLot, product, operatorId, now);
        }
        WmsMaterialBatch existing = batches.get(0);
        if (!Objects.equals(existing.getMaterialId(), product.getMaterialId())) {
            throw business(ResultCode.BUSINESS_ERROR, "产品批次关联的成品物料批次与产品主数据不一致");
        }
        syncFinishedBatchStatus(existing, productLot, operatorId, now);
        return existing;
    }

    /** 线边产出登记时成品批次还是待检状态，终检放行后在这里同步为产品批次的放行结果。 */
    private void syncFinishedBatchStatus(WmsMaterialBatch existing, TraceProductLot productLot,
                                         Long operatorId, LocalDateTime now) {
        boolean released = RELEASED_QUALITY_STATUSES.contains(productLot.getStatus());
        if (!released || Objects.equals(existing.getQualityStatus(), productLot.getStatus())) {
            return;
        }
        WmsMaterialBatch sync = new WmsMaterialBatch();
        sync.setBatchId(existing.getBatchId());
        sync.setVersion(existing.getVersion());
        sync.setQualityStatus(productLot.getStatus());
        sync.setStatus("AVAILABLE");
        sync.setUpdatedBy(operatorId);
        sync.setUpdatedTime(now);
        if (wmsMaterialBatchMapper.updateById(sync) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "成品物料批次已被其他用户修改，请刷新后重试");
        }
        existing.setQualityStatus(productLot.getStatus());
        existing.setStatus("AVAILABLE");
    }

    /** 产品批次尚无成品物料批次时按放行结果新建一条。 */
    private WmsMaterialBatch createFinishedBatch(TraceProductLot productLot, MdmProduct product,
                                                 Long operatorId, LocalDateTime now) {
        WmsMaterialBatch batch = new WmsMaterialBatch();
        batch.setBatchNo("FG-" + productLot.getLotNo());
        batch.setMaterialId(product.getMaterialId());
        batch.setProductionDate(LocalDate.now());
        batch.setQualityStatus(productLot.getStatus());
        batch.setStatus("AVAILABLE");
        batch.setSourceOrderType("PRODUCT_LOT");
        batch.setSourceOrderId(productLot.getProductLotId());
        batch.setSourceProductLotId(productLot.getProductLotId());
        batch.setRemark("由终检放行产品批次自动生成");
        batch.setVersion(0);
        batch.setCreatedBy(operatorId);
        batch.setCreatedTime(now);
        batch.setUpdatedBy(operatorId);
        batch.setUpdatedTime(now);
        batch.setDeletedFlag(0);
        if (wmsMaterialBatchMapper.insert(batch) == 0 || batch.getBatchId() == null) {
            throw business(ResultCode.FAILED, "成品物料批次生成失败");
        }
        return batch;
    }

    private BigDecimal approvedProductionInboundQuantity(Long productLotId) {
        return wmsInboundDetailMapper.selectList(new LambdaQueryWrapper<WmsInboundDetail>()
                        .eq(WmsInboundDetail::getDeletedFlag, 0)
                        .inSql(WmsInboundDetail::getInboundId,
                                "SELECT inbound_id FROM wms_inbound_order WHERE deleted_flag = 0 "
                                        + "AND inbound_type = 'PRODUCTION' AND source_order_type = 'PRODUCT_LOT' "
                                        + "AND audit_status = 'APPROVED' AND source_order_id = " + productLotId))
                .stream().map(WmsInboundDetail::getInboundQuantity).map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void insertGeneratedInboundAudit(WmsInboundOrder order, Long productLotId,
                                             Long operatorId, LocalDateTime now) {
        SysStatusChangeLog status = new SysStatusChangeLog();
        status.setBizType("WMS_INBOUND_ORDER");
        status.setBizId(order.getInboundId());
        status.setNewStatus(SUBMITTED_STATUS);
        status.setChangeReason("GENERATE_PRODUCTION_INBOUND");
        status.setChangedBy(operatorId);
        status.setChangedTime(now);
        status.setCreatedBy(operatorId);
        status.setCreatedTime(now);
        status.setRemark("来源产品批次ID=" + productLotId);
        status.setDeletedFlag(0);
        if (sysStatusChangeLogMapper.insert(status) == 0) throw new BusinessException(ResultCode.FAILED);
        SysOperationLog operation = new SysOperationLog();
        operation.setUserId(operatorId);
        operation.setOperationType("GENERATE_PRODUCTION_INBOUND");
        operation.setModuleCode("wms_inbound_order");
        operation.setBizId(order.getInboundId());
        operation.setOperationTime(now);
        operation.setCreatedBy(operatorId);
        operation.setCreatedTime(now);
        operation.setRemark("来源产品批次ID=" + productLotId);
        operation.setDeletedFlag(0);
        if (sysOperationLogMapper.insert(operation) == 0) throw new BusinessException(ResultCode.FAILED);
    }

    /**
     * 确认入库，更新库存余额并写入库存流水。
     * @param inboundId 入库单ID
     * @param operatorId 操作人ID
     * @param remark 备注
     * @return 是否成功
     */
    @Override
    @Transactional
    public boolean confirmInboundOrder(Long inboundId, Long operatorId, String remark) {
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "当前登录用户不能为空");
        }
        WmsInboundOrder inboundOrder = requireConfirmableInbound(inboundId);
        List<WmsInboundDetail> details = requireActiveDetails(inboundId);
        ProductionInboundContext productionContext = "PRODUCTION".equals(inboundOrder.getInboundType())
                ? validateProductionInbound(inboundOrder, details)
                : null;
        validateInboundDetails(details);
        details.forEach(detail -> validateInboundLocation(inboundOrder, detail));
        LocalDateTime now = LocalDateTime.now();
        if (productionContext != null) {
            releaseLineSideOutput(inboundOrder, details, productionContext.productLot(), operatorId, now);
        }
        increaseInventoryAndMovements(inboundOrder, details, operatorId, remark, now);
        markInboundApproved(inboundOrder, operatorId, remark, now);
        // 自动化编排：采购到货确认即签发来料检验任务，检验结果由 QMS 侧提交后回写批次质量状态。
        issueIncomingInspections(inboundOrder, details, operatorId, now);
        markProductLotStored(inboundOrder, productionContext, operatorId, now);
        responsibilityNotificationService.cancelInboundPending(inboundId, operatorId);
        responsibilityNotificationService.publishInboundCompleted(inboundOrder, operatorId);
        refreshPurchaseReceivedQuantity(inboundOrder, operatorId);
        return true;
    }

    /** 入库单必须存在且处于待确认状态、带仓库。 */
    private WmsInboundOrder requireConfirmableInbound(Long inboundId) {
        WmsInboundOrder inboundOrder = getInboundOrderById(inboundId);
        if (inboundOrder == null) {
            throw business(ResultCode.NOT_FOUND, "入库单不存在或已删除");
        }
        if (!SUBMITTED_STATUS.equals(inboundOrder.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有 SUBMITTED 状态的入库单可以确认");
        }
        if (inboundOrder.getWarehouseId() == null) {
            throw business(ResultCode.PARAM_ERROR, "入库仓库不能为空");
        }
        return inboundOrder;
    }

    /** 入库单至少需要一条有效明细。 */
    private List<WmsInboundDetail> requireActiveDetails(Long inboundId) {
        List<WmsInboundDetail> details = listActiveDetails(inboundId);
        if (details.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "入库单至少需要一条有效明细");
        }
        return details;
    }

    /** 逐条增加库存余额并写入入库流水。 */
    private void increaseInventoryAndMovements(WmsInboundOrder inboundOrder, List<WmsInboundDetail> details,
                                               Long operatorId, String remark, LocalDateTime now) {
        for (WmsInboundDetail detail : details) {
            increaseInventory(inboundOrder, detail);
            insertInboundMovement(inboundOrder, detail, operatorId, remark, now);
        }
    }

    /** 把入库单置为已确认，版本冲突即回滚。 */
    private void markInboundApproved(WmsInboundOrder inboundOrder, Long operatorId, String remark,
                                     LocalDateTime now) {
        WmsInboundOrder update = new WmsInboundOrder();
        update.setInboundId(inboundOrder.getInboundId());
        update.setVersion(inboundOrder.getVersion());
        update.setAuditStatus(APPROVED_STATUS);
        update.setInboundTime(now);
        update.setRemark(StringUtils.hasText(remark) ? remark : inboundOrder.getRemark());
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "入库单已被其他用户修改，请刷新后重试");
        }
        insertStatusChangeLog(inboundOrder, operatorId, remark, now);
        insertOperationLog(inboundOrder, operatorId, remark, now);
    }

    /** 生产入库累计达到终检放行数量时，把产品批次推进到已入库状态。 */
    private void markProductLotStored(WmsInboundOrder inboundOrder, ProductionInboundContext productionContext,
                                      Long operatorId, LocalDateTime now) {
        if (productionContext == null) {
            return;
        }
        TraceProductLot productLot = productionContext.productLot();
        boolean fullyStored = productionContext.totalAfterConfirm()
                .compareTo(zero(productLot.getQualifiedQuantity())) >= 0;
        if (!fullyStored) {
            return;
        }
        TraceProductLot lotUpdate = new TraceProductLot();
        lotUpdate.setProductLotId(productLot.getProductLotId());
        lotUpdate.setVersion(productLot.getVersion());
        lotUpdate.setStatus("STORED");
        lotUpdate.setUpdatedBy(operatorId);
        lotUpdate.setUpdatedTime(now);
        if (traceProductLotMapper.updateById(lotUpdate) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "产品批次状态已变化，生产入库确认已回滚");
        }
    }

    /**
     * 采购订单回写：只认单头来源是采购订单的入库单，按物料汇总已收数量。
     * 回写失败不回滚入库（货已上架、批次已入库），留日志由采购订单页面核对。
     */
    private void refreshPurchaseReceivedQuantity(WmsInboundOrder inboundOrder, Long operatorId) {
        if (!"PURCHASE_ORDER".equals(inboundOrder.getSourceOrderType())
                || inboundOrder.getSourceOrderId() == null || purchaseOrderService == null) {
            return;
        }
        try {
            purchaseOrderService.refreshReceivedQuantity(inboundOrder.getSourceOrderId(), operatorId);
        } catch (RuntimeException error) {
            log.warn("回写采购订单已收数量失败，inboundId={}, purchaseOrderId={}: {}",
                    inboundOrder.getInboundId(), inboundOrder.getSourceOrderId(), error.getMessage());
        }
    }

    private ProductionInboundContext validateProductionInbound(WmsInboundOrder order,
                                                                List<WmsInboundDetail> details) {
        if (!"PRODUCT_LOT".equals(order.getSourceOrderType()) || order.getSourceOrderId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "生产入库单必须关联来源产品批次");
        }
        TraceProductLot productLot = traceProductLotMapper.selectOne(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getProductLotId, order.getSourceOrderId())
                .eq(TraceProductLot::getDeletedFlag, 0)
                .last("FOR UPDATE"));
        if (productLot == null || !RELEASED_QUALITY_STATUSES.contains(productLot.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "来源产品批次未终检放行，不能确认生产入库");
        }
        MdmProduct product = mdmProductMapper.selectOne(new LambdaQueryWrapper<MdmProduct>()
                .eq(MdmProduct::getProductId, productLot.getProductId())
                .eq(MdmProduct::getStatus, BusinessStatus.Common.ENABLE)
                .eq(MdmProduct::getDeletedFlag, 0));
        if (product == null || product.getMaterialId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "来源产品没有有效成品物料映射");
        }
        BigDecimal current = sumInboundQuantities(productLot, product, details);
        BigDecimal totalAfterConfirm = approvedProductionInboundQuantity(productLot.getProductLotId()).add(current)
                .add(componentHandover == null ? BigDecimal.ZERO : componentHandover.deliveredLot(productLot.getProductLotId()));
        if (current.signum() <= 0 || totalAfterConfirm.compareTo(releasedInboundQuantity(productLot)) > 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "生产入库累计数量不能超过产品批次终检放行数量");
        }
        return new ProductionInboundContext(productLot, totalAfterConfirm);
    }

    /** 逐条校验明细的成品批次与来源产品批次一致，并汇总本次入库量。 */
    private BigDecimal sumInboundQuantities(TraceProductLot productLot, MdmProduct product,
                                            List<WmsInboundDetail> details) {
        BigDecimal current = BigDecimal.ZERO;
        for (WmsInboundDetail detail : details) {
            executionQuantities.validate(detail.getMaterialId(), detail.getInboundQuantity(), false);
            requireMatchingFinishedBatch(detail, productLot, product);
            current = current.add(zero(detail.getInboundQuantity()));
        }
        return current;
    }

    /** 明细的成品物料批次必须指向同一产品批次与同一成品物料。 */
    private void requireMatchingFinishedBatch(WmsInboundDetail detail, TraceProductLot productLot,
                                              MdmProduct product) {
        WmsMaterialBatch batch = getActiveBatch(detail.getBatchId());
        boolean batchMissing = batch == null;
        boolean lotMismatch = !batchMissing
                && !Objects.equals(batch.getSourceProductLotId(), productLot.getProductLotId());
        boolean batchMaterialMismatch = !batchMissing
                && !Objects.equals(batch.getMaterialId(), product.getMaterialId());
        boolean detailMaterialMismatch = !Objects.equals(detail.getMaterialId(), product.getMaterialId());
        if (batchMissing || lotMismatch || batchMaterialMismatch || detailMaterialMismatch) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "生产入库明细的成品物料批次与来源产品批次不一致");
        }
    }

    private record ProductionInboundContext(TraceProductLot productLot, BigDecimal totalAfterConfirm) {
    }

    private BigDecimal releasedInboundQuantity(TraceProductLot lot) {
        BigDecimal quantity = zero(lot.getQualifiedQuantity());
        // 组件已直接交接时，其余未放行件不能再利用旧报工合格数重复入库。
        return componentHandover != null && componentHandover.deliveredLot(lot.getProductLotId()).signum() > 0
                ? quantity.min(componentHandover.acceptedQuantity(lot)) : quantity;
    }

    /**
     * 生产入库前释放线边仓的产成品占用。
     * 末工序报工已把合格产出登记为线边仓该工单的 locked_quantity，入库只是把占用转成目标仓可用库存，
     * 因此这里按入库数量扣减占用并写一笔 TRANSFER 出库流水，与入库流水共用 pair_no 前缀 LI-。
     * 线边仓没有对应记录时（历史数据或直接入库）跳过，不阻断原入库流程。
     */
    private void releaseLineSideOutput(WmsInboundOrder inboundOrder, List<WmsInboundDetail> details,
                                       TraceProductLot productLot, Long operatorId, LocalDateTime now) {
        if (wmsInventoryMapper == null || wmsStockMovementMapper == null
                || productLot == null || productLot.getWorkOrderId() == null) {
            return;
        }
        WmsWarehouse lineSide = findLineSideWarehouse();
        if (lineSide == null) {
            return;
        }
        for (WmsInboundDetail detail : details) {
            releaseSingleLineSideOutput(inboundOrder, detail, productLot, lineSide, operatorId, now);
        }
    }

    /** 释放单条明细对应的线边产出占用，并写一笔 TRANSFER 出库流水。 */
    private void releaseSingleLineSideOutput(WmsInboundOrder inboundOrder, WmsInboundDetail detail,
                                             TraceProductLot productLot, WmsWarehouse lineSide,
                                             Long operatorId, LocalDateTime now) {
        if (detail.getDetailId() == null || detail.getMaterialId() == null || detail.getBatchId() == null) {
            return;
        }
        String pairNo = "LI-" + inboundOrder.getInboundId() + "-" + detail.getDetailId();
        if (!lineSideMovementExists(pairNo)) {
            releaseLockedLineSide(inboundOrder, detail, productLot, lineSide, pairNo, operatorId, now);
        }
    }

    /** 扣减线边锁定量并写释放流水；线边无记录或无可释放量时跳过。 */
    private void releaseLockedLineSide(WmsInboundOrder inboundOrder, WmsInboundDetail detail,
                                       TraceProductLot productLot, WmsWarehouse lineSide, String pairNo,
                                       Long operatorId, LocalDateTime now) {
        WmsInventory lineInventory = lockLineSideInventory(productLot, detail, lineSide);
        if (lineInventory == null) {
            // 线边没有该产出记录，视为历史数据或直接入库，跳过释放。
            return;
        }
        BigDecimal locked = zero(lineInventory.getLockedQuantity());
        BigDecimal release = BigDecimal.ZERO;
        if (locked.signum() > 0) {
            release = zero(detail.getInboundQuantity()).min(locked);
        }
        if (release.signum() <= 0) {
            return;
        }
        decreaseLineSideLocked(lineInventory, locked, release, operatorId, now);
        insertLineSideReleaseMovement(inboundOrder, detail, productLot, lineSide, lineInventory,
                pairNo, release, operatorId, now);
    }

    /** 该明细是否已经释放过线边占用（幂等重放保护）。 */
    private boolean lineSideMovementExists(String pairNo) {
        long replay = wmsStockMovementMapper.selectCount(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getPairNo, pairNo)
                .eq(WmsStockMovement::getMovementType, "TRANSFER")
                .eq(WmsStockMovement::getDirection, "OUT")
                .eq(WmsStockMovement::getDeletedFlag, 0));
        return replay > 0;
    }

    /** 锁定工单在指定线边仓的该物料批次库存行。 */
    private WmsInventory lockLineSideInventory(TraceProductLot productLot, WmsInboundDetail detail,
                                               WmsWarehouse lineSide) {
        List<WmsInventory> lineInventories = wmsInventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWarehouseId, lineSide.getWarehouseId())
                .eq(WmsInventory::getWorkOrderId, productLot.getWorkOrderId())
                .eq(WmsInventory::getMaterialId, detail.getMaterialId())
                .eq(WmsInventory::getBatchId, detail.getBatchId())
                .eq(WmsInventory::getDeletedFlag, 0)
                .orderByAsc(WmsInventory::getLocationId)
                .last("FOR UPDATE"));
        return lineInventories.isEmpty() ? null : lineInventories.get(0);
    }

    /** 扣减线边占用余额。 */
    private void decreaseLineSideLocked(WmsInventory lineInventory, BigDecimal locked, BigDecimal release,
                                        Long operatorId, LocalDateTime now) {
        WmsInventory update = new WmsInventory();
        update.setInventoryId(lineInventory.getInventoryId());
        update.setVersion(lineInventory.getVersion());
        update.setLockedQuantity(locked.subtract(release));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (wmsInventoryMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "线边库存已被其他请求修改，请刷新后重试");
        }
    }

    /** 写一笔线边占用释放的 TRANSFER 出库流水。 */
    private void insertLineSideReleaseMovement(WmsInboundOrder inboundOrder, WmsInboundDetail detail,
                                               TraceProductLot productLot, WmsWarehouse lineSide,
                                               WmsInventory lineInventory, String pairNo,
                                               BigDecimal release, Long operatorId, LocalDateTime now) {
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo(pairNo + "-OUT");
        movement.setMovementType("TRANSFER");
        movement.setSourceOrderType("INBOUND_ORDER");
        movement.setSourceOrderId(inboundOrder.getInboundId());
        movement.setMaterialId(detail.getMaterialId());
        movement.setBatchId(detail.getBatchId());
        movement.setWarehouseId(lineSide.getWarehouseId());
        movement.setLocationId(lineInventory.getLocationId());
        movement.setWorkOrderId(productLot.getWorkOrderId());
        movement.setPairNo(pairNo);
        movement.setQuantity(release);
        movement.setDirection("OUT");
        movement.setOperationTime(now);
        movement.setRemark("生产入库释放线边产出占用");
        movement.setVersion(0);
        movement.setCreatedBy(operatorId);
        movement.setCreatedTime(now);
        movement.setUpdatedBy(operatorId);
        movement.setUpdatedTime(now);
        movement.setDeletedFlag(0);
        if (wmsStockMovementMapper.insert(movement) == 0) {
            throw business(ResultCode.FAILED, "线边产出释放流水写入失败");
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

    /** 入库明细的必要字段：物料、批次、库位齐全，且数量为正。 */
    private static boolean hasCompleteInboundFields(WmsInboundDetail detail) {
        if (detail.getMaterialId() == null || detail.getBatchId() == null || detail.getLocationId() == null) {
            return false;
        }
        BigDecimal quantity = detail.getInboundQuantity();
        return quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0;
    }

    private void validateInboundDetails(List<WmsInboundDetail> details) {
        for (WmsInboundDetail detail : details) {
            if (!hasCompleteInboundFields(detail)) {
                throw business(ResultCode.PARAM_ERROR, "入库明细的物料、批次、库位和正数数量不能为空");
            }
            executionQuantities.validate(detail.getMaterialId(), detail.getInboundQuantity(), false);
            WmsMaterialBatch batch = getActiveBatch(detail.getBatchId());
            if (batch == null) {
                throw business(ResultCode.NOT_FOUND, "入库明细关联的物料批次不存在或已删除");
            }
            if (!detail.getMaterialId().equals(batch.getMaterialId())) {
                throw business(ResultCode.BUSINESS_ERROR, "入库明细物料与批次物料不一致");
            }
            if (!RELEASED_QUALITY_STATUSES.contains(batch.getQualityStatus())) {
                throw business(ResultCode.BUSINESS_ERROR, "批次未质检放行，禁止进入可用库存");
            }
            if (!STORABLE_BATCH_STATUSES.contains(batch.getStatus())) {
                throw business(ResultCode.BUSINESS_ERROR, "批次当前状态不可入库");
            }
        }
    }

    private WmsMaterialBatch getActiveBatch(Long batchId) {
        LambdaQueryWrapper<WmsMaterialBatch> query = new LambdaQueryWrapper<>();
        query.eq(WmsMaterialBatch::getBatchId, batchId)
                .eq(WmsMaterialBatch::getDeletedFlag, 0);
        return wmsMaterialBatchMapper.selectOne(query);
    }

    /**
     * 构建入库单查询条件，默认过滤已删除记录。
     * @param keyword 关键字（匹配入库单号）
     * @param inboundType 入库类型
     * @param warehouseId 仓库ID
     * @param sourceOrderType 来源单据类型
     * @param sourceOrderId 来源单据ID
     * @param auditStatus 审核状态
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<WmsInboundOrder> buildInboundOrderQuery(String keyword, String inboundType,
                                                                       Long warehouseId, String sourceOrderType,
                                                                       Long sourceOrderId, String auditStatus) {
        LambdaQueryWrapper<WmsInboundOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsInboundOrder::getDeletedFlag, 0)
                .like(StringUtils.hasText(keyword), WmsInboundOrder::getInboundNo, keyword)
                .eq(StringUtils.hasText(inboundType), WmsInboundOrder::getInboundType, inboundType)
                .eq(warehouseId != null, WmsInboundOrder::getWarehouseId, warehouseId)
                .eq(StringUtils.hasText(sourceOrderType), WmsInboundOrder::getSourceOrderType, sourceOrderType)
                .eq(sourceOrderId != null, WmsInboundOrder::getSourceOrderId, sourceOrderId)
                .eq(StringUtils.hasText(auditStatus), WmsInboundOrder::getAuditStatus, auditStatus)
                .orderByDesc(WmsInboundOrder::getCreatedTime);
        return queryWrapper;
    }

    /**
     * 软删除指定入库单的所有明细。
     * @param inboundId 入库单ID
     */
    private void softDeleteDetails(Long inboundId) {
        List<WmsInboundDetail> details = listActiveDetails(inboundId);
        for (WmsInboundDetail detail : details) {
            WmsInboundDetail update = new WmsInboundDetail();
            update.setDetailId(detail.getDetailId());
            update.setDeletedFlag(1);
            wmsInboundDetailMapper.updateById(update);
        }
    }

    /**
     * 查询指定入库单的未删除明细。
     * @param inboundId 入库单ID
     * @return 入库明细列表
     */
    private List<WmsInboundDetail> listActiveDetails(Long inboundId) {
        LambdaQueryWrapper<WmsInboundDetail> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsInboundDetail::getInboundId, inboundId)
                .eq(WmsInboundDetail::getDeletedFlag, 0);
        return wmsInboundDetailMapper.selectList(queryWrapper);
    }

    private void notifyAvailableChanged(Long materialId) {
        if (inventoryAutomationTrigger != null) {
            inventoryAutomationTrigger.afterChange(materialId);
        }
    }

    /**
     * 根据入库明细增加库存余额。
     * @param inboundOrder 入库单实体
     * @param detail 入库明细实体
     */
    private void increaseInventory(WmsInboundOrder inboundOrder, WmsInboundDetail detail) {
        LambdaQueryWrapper<WmsInventory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsInventory::getWarehouseId, inboundOrder.getWarehouseId())
                .eq(WmsInventory::getLocationId, detail.getLocationId())
                .eq(WmsInventory::getMaterialId, detail.getMaterialId())
                .eq(WmsInventory::getBatchId, detail.getBatchId())
                // 入库目标仓为公共库存，按 work_order_id=0 定位，避免命中线边按工单占用的余额。
                .eq(WmsInventory::getWorkOrderId, 0L)
                .eq(WmsInventory::getDeletedFlag, 0);
        WmsInventory inventory = wmsInventoryMapper.selectOne(queryWrapper);
        BigDecimal inboundQuantity = detail.getInboundQuantity() == null ? BigDecimal.ZERO : detail.getInboundQuantity();
        if (inventory == null) {
            inventory = new WmsInventory();
            inventory.setWarehouseId(inboundOrder.getWarehouseId());
            inventory.setLocationId(detail.getLocationId());
            inventory.setMaterialId(detail.getMaterialId());
            inventory.setBatchId(detail.getBatchId());
            inventory.setWorkOrderId(0L);
            inventory.setAvailableQuantity(inboundQuantity);
            inventory.setLockedQuantity(BigDecimal.ZERO);
            inventory.setInspectionQuantity(BigDecimal.ZERO);
            inventory.setDeletedFlag(0);
            if (wmsInventoryMapper.insert(inventory) == 0) {
                throw new BusinessException(ResultCode.FAILED);
            }
        } else {
            WmsInventory update = new WmsInventory();
            update.setInventoryId(inventory.getInventoryId());
            update.setVersion(inventory.getVersion());
            BigDecimal currentAvailable = inventory.getAvailableQuantity() == null ? BigDecimal.ZERO : inventory.getAvailableQuantity();
            update.setAvailableQuantity(currentAvailable.add(inboundQuantity));
            if (wmsInventoryMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "库存余额已被其他请求修改，请刷新后重试");
            }
        }
        notifyAvailableChanged(detail.getMaterialId());
    }

    /**
     * 解析入库流水归属的工单：生产入库按来源产品批次回带工单，采购等其他入库记为公共库存 0。
     * @param inboundOrder 入库单实体
     * @return 工单ID，非生产入库返回 0
     */
    private Long resolveMovementWorkOrderId(WmsInboundOrder inboundOrder) {
        if (!"PRODUCTION".equals(inboundOrder.getInboundType())
                || !"PRODUCT_LOT".equals(inboundOrder.getSourceOrderType())
                || inboundOrder.getSourceOrderId() == null) {
            return 0L;
        }
        TraceProductLot productLot = traceProductLotMapper.selectById(inboundOrder.getSourceOrderId());
        if (productLot == null || productLot.getWorkOrderId() == null) {
            return 0L;
        }
        return productLot.getWorkOrderId();
    }

    /**
     * 写入入库库存流水。
     * @param inboundOrder 入库单实体
     * @param detail 入库明细实体
     * @param operatorId 操作人ID
     * @param remark 备注
     * @param operationTime 操作时间
     */
    private void insertInboundMovement(WmsInboundOrder inboundOrder, WmsInboundDetail detail,
                                       Long operatorId, String remark, LocalDateTime operationTime) {
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo("MV" + System.currentTimeMillis() + detail.getDetailId());
        movement.setMovementType("INBOUND");
        movement.setSourceOrderType("INBOUND_ORDER");
        movement.setSourceOrderId(inboundOrder.getInboundId());
        movement.setMaterialId(detail.getMaterialId());
        movement.setBatchId(detail.getBatchId());
        movement.setWarehouseId(inboundOrder.getWarehouseId());
        movement.setLocationId(detail.getLocationId());
        movement.setWorkOrderId(resolveMovementWorkOrderId(inboundOrder));
        String pairNo = resolveLineSidePairNo(inboundOrder, detail);
        if (pairNo != null) {
            // 保留生产入库的 INBOUND 分类，用同一配对号关联此前实际发生的线边转出。
            movement.setPairNo(pairNo);
        }
        movement.setQuantity(detail.getInboundQuantity());
        movement.setDirection("IN");
        movement.setOperationTime(operationTime);
        movement.setCreatedBy(operatorId);
        movement.setRemark(remark);
        movement.setDeletedFlag(0);
        if (wmsStockMovementMapper.insert(movement) == 0) {
            throw new BusinessException(ResultCode.FAILED);
        }
    }

    /**
     * 生产入库需要与线边转出流水配对；不适用或无对应转出时返回 null。
     * 配对流水存在时校验物料、批次与数量必须一致。
     */
    private String resolveLineSidePairNo(WmsInboundOrder inboundOrder, WmsInboundDetail detail) {
        boolean productionInbound = "PRODUCTION".equals(inboundOrder.getInboundType())
                && "PRODUCT_LOT".equals(inboundOrder.getSourceOrderType());
        if (!productionInbound || detail.getDetailId() == null) {
            return null;
        }
        String pairNo = "LI-" + inboundOrder.getInboundId() + "-" + detail.getDetailId();
        WmsStockMovement source = wmsStockMovementMapper.selectOne(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getPairNo, pairNo)
                .eq(WmsStockMovement::getDirection, "OUT")
                .eq(WmsStockMovement::getDeletedFlag, 0));
        if (source == null) {
            return null;
        }
        boolean materialMismatch = !Objects.equals(source.getMaterialId(), detail.getMaterialId());
        boolean batchMismatch = !Objects.equals(source.getBatchId(), detail.getBatchId());
        boolean quantityMismatch = zero(source.getQuantity()).compareTo(detail.getInboundQuantity()) != 0;
        if (materialMismatch || batchMismatch || quantityMismatch) {
            throw business(ResultCode.BUSINESS_ERROR, "生产入库与线边产出转出数量或批次不一致");
        }
        return pairNo;
    }

    /**
     * 记录入库单确认的状态变更日志。
     * @param inboundOrder 入库单实体
     * @param operatorId 操作人ID
     * @param remark 变更备注
     * @param changedTime 变更时间
     */
    private void insertStatusChangeLog(WmsInboundOrder inboundOrder, Long operatorId, String remark, LocalDateTime changedTime) {
        SysStatusChangeLog statusLog = new SysStatusChangeLog();
        statusLog.setBizType("WMS_INBOUND_ORDER");
        statusLog.setBizId(inboundOrder.getInboundId());
        statusLog.setOldStatus(inboundOrder.getAuditStatus());
        statusLog.setNewStatus(APPROVED_STATUS);
        statusLog.setChangeReason("CONFIRM_INBOUND");
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
     * 记录入库单确认的操作日志。
     * @param inboundOrder 入库单实体
     * @param operatorId 操作人ID
     * @param remark 操作备注
     * @param operationTime 操作时间
     */
    private void insertOperationLog(WmsInboundOrder inboundOrder, Long operatorId, String remark, LocalDateTime operationTime) {
        SysOperationLog operationLog = new SysOperationLog();
        operationLog.setUserId(operatorId);
        operationLog.setOperationType("CONFIRM_INBOUND");
        operationLog.setModuleCode("wms_inbound_order");
        operationLog.setBizId(inboundOrder.getInboundId());
        operationLog.setOperationTime(operationTime);
        operationLog.setCreatedBy(operatorId);
        operationLog.setCreatedTime(operationTime);
        operationLog.setRemark(remark);
        operationLog.setDeletedFlag(0);
        if (sysOperationLogMapper.insert(operationLog) == 0) {
            throw new BusinessException(ResultCode.FAILED);
        }
    }

    /**
     * 采购到货入库确认后自动签发来料检验（IQC）任务，构成一条不依赖人工的自动化编排链路：
     * 采购入库确认 → 按物料匹配已发布的来料检验方案 → 生成待检的来料检验单 → 检验员在质量管理里提交结果。
     * 只处理采购类入库的原材料与辅料，生产入库的成品不放行来料检验；同一批次已有来料检验单时跳过，保证幂等。
     * @param inboundOrder 已确认的入库单
     * @param details 入库明细
     * @param operatorId 操作人ID
     * @param now 操作时间
     */
    private void issueIncomingInspections(WmsInboundOrder inboundOrder, List<WmsInboundDetail> details,
                                          Long operatorId, LocalDateTime now) {
        if (!incomingInspectionReady(inboundOrder)) {
            return;
        }
        java.util.Map<Long, BigDecimal> pendingByBatch = collectPendingInspectionQuantities(details);
        Long issued = issueInspectionsForBatches(pendingByBatch, inboundOrder, operatorId, now);
        if (issued > 0) {
            insertIncomingInspectionLog(inboundOrder, issued, operatorId, now);
        }
    }

    /** 来料检验签发前置条件：服务与 Mapper 就绪，且是采购类入库。 */
    private boolean incomingInspectionReady(WmsInboundOrder inboundOrder) {
        if (qmsInspectionOrderService == null || inboundOrder == null) {
            return false;
        }
        if (!PURCHASE_INBOUND_TYPE.equals(inboundOrder.getInboundType())) {
            return false;
        }
        return mdmMaterialMapper != null && qmsInspectionOrderMapper != null;
    }

    /** 按批次汇总需要送检的数量，只统计来料检验覆盖的物料类型。 */
    private java.util.Map<Long, BigDecimal> collectPendingInspectionQuantities(List<WmsInboundDetail> details) {
        java.util.Map<Long, BigDecimal> pendingByBatch = new java.util.LinkedHashMap<>();
        for (WmsInboundDetail detail : details) {
            if (detail.getBatchId() == null || detail.getMaterialId() == null) {
                continue;
            }
            if (!requiresIncomingInspection(detail.getMaterialId())) {
                continue;
            }
            pendingByBatch.merge(detail.getBatchId(), zero(detail.getInboundQuantity()), BigDecimal::add);
        }
        return pendingByBatch;
    }

    /** 该物料类型是否需要来料检验。 */
    private boolean requiresIncomingInspection(Long materialId) {
        com.neuedu.ems.entity.mdm.MdmMaterial material = mdmMaterialMapper.selectById(materialId);
        return material != null && INCOMING_INSPECTION_MATERIAL_TYPES.contains(material.getMaterialType());
    }

    /** 逐批次签发来料检验单，返回实际签发张数。 */
    private Long issueInspectionsForBatches(java.util.Map<Long, BigDecimal> pendingByBatch,
                                            WmsInboundOrder inboundOrder, Long operatorId, LocalDateTime now) {
        Long issued = 0L;
        for (java.util.Map.Entry<Long, BigDecimal> entry : pendingByBatch.entrySet()) {
            Long batchId = entry.getKey();
            BigDecimal quantity = entry.getValue();
            if (quantity.signum() <= 0) {
                continue;
            }
            if (incomingInspectionExists(batchId)) {
                synchronizePendingReceiptInspection(inboundOrder, batchId, quantity);
                continue;
            }
            if (saveIncomingInspection(inboundOrder, batchId, quantity, operatorId, now)) {
                issued++;
            }
        }
        return issued;
    }

    /** 保存一张来料检验单，返回是否保存成功。 */
    private boolean saveIncomingInspection(WmsInboundOrder inboundOrder, Long batchId, BigDecimal quantity,
                                           Long operatorId, LocalDateTime now) {
        com.neuedu.ems.entity.qms.QmsInspectionOrder inspection = new com.neuedu.ems.entity.qms.QmsInspectionOrder();
        inspection.setInspectionType(INCOMING_INSPECTION_TYPE);
        inspection.setSchemeId(resolveIncomingSchemeId(batchId));
        inspection.setBatchId(batchId);
        inspection.setSampleQuantity(quantity);
        inspection.setRemark("由采购入库单 " + inboundOrder.getInboundNo() + " 自动签发，放行后确认入库");
        inspection.setCreatedBy(operatorId);
        inspection.setCreatedTime(now);
        inspection.setUpdatedBy(operatorId);
        inspection.setUpdatedTime(now);
        return qmsInspectionOrderService.saveInspectionOrder(inspection);
    }

    /** 记录本次自动签发来料检验单的操作日志。 */
    private void insertIncomingInspectionLog(WmsInboundOrder inboundOrder, Long issued,
                                             Long operatorId, LocalDateTime now) {
        SysOperationLog operationLog = new SysOperationLog();
        operationLog.setUserId(operatorId);
        operationLog.setOperationType("AUTO_ISSUE_INCOMING_INSPECTION");
        operationLog.setModuleCode("qms_inspection_order");
        operationLog.setBizId(inboundOrder.getInboundId());
        operationLog.setOperationTime(now);
        operationLog.setCreatedBy(operatorId);
        operationLog.setCreatedTime(now);
        operationLog.setRemark("采购到货自动签发来料检验单 " + issued + " 张");
        operationLog.setDeletedFlag(0);
        sysOperationLogMapper.insert(operationLog);
    }

    private void synchronizePendingReceiptInspection(WmsInboundOrder order, Long batchId, BigDecimal quantity) {
        WmsMaterialBatch batch = wmsMaterialBatchMapper.selectById(batchId);
        if (batch == null || !"PURCHASE_RECEIPT".equals(batch.getSourceOrderType())
                || !Objects.equals(batch.getSourceOrderId(), order.getInboundId())) {
            return;
        }
        var inspection = qmsInspectionOrderMapper.selectOne(
                new LambdaQueryWrapper<com.neuedu.ems.entity.qms.QmsInspectionOrder>()
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionOrder::getBatchId, batchId)
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionOrder::getInspectionType, "INCOMING")
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionOrder::getDeletedFlag, 0)
                        .orderByDesc(com.neuedu.ems.entity.qms.QmsInspectionOrder::getInspectionId).last("LIMIT 1"));
        if (inspection == null || quantity.compareTo(zero(inspection.getSampleQuantity())) == 0) {
            return;
        }
        if (!"DRAFT".equals(inspection.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "此批次来料检验已开始，不能修改到货数量，请新建到货单");
        }
        inspection.setSampleQuantity(quantity);
        qmsInspectionOrderMapper.updateById(inspection);
    }

    /** 同一批次的来料检验单只需要签发一次，避免重复确认或补建导致重复待检。 */
    private boolean incomingInspectionExists(Long batchId) {
        if (qmsInspectionOrderMapper == null) {
            return false;
        }
        Long count = qmsInspectionOrderMapper.selectCount(new LambdaQueryWrapper<com.neuedu.ems.entity.qms.QmsInspectionOrder>()
                .eq(com.neuedu.ems.entity.qms.QmsInspectionOrder::getBatchId, batchId)
                .eq(com.neuedu.ems.entity.qms.QmsInspectionOrder::getInspectionType, INCOMING_INSPECTION_TYPE)
                .eq(com.neuedu.ems.entity.qms.QmsInspectionOrder::getDeletedFlag, 0));
        return count != null && count > 0;
    }

    /**
     * 按批次对应的物料匹配已发布的来料检验方案。
     * 方案挂在成品上（L100 / L200 各一套），因此按该物料是否出现在方案的成品 BOM 里判断归属；
     * 优先选择 BOM 层级最近的组件方案；同层级按方案编号稳定排序。
     */
    private Long resolveIncomingSchemeId(Long batchId) {
        if (qmsInspectionSchemeMapper == null || mdmBomMapper == null || mdmBomItemMapper == null
                || wmsMaterialBatchMapper == null) {
            return null;
        }
        WmsMaterialBatch batch = wmsMaterialBatchMapper.selectById(batchId);
        if (batch == null || batch.getMaterialId() == null) {
            return null;
        }
        return firstMatchingSchemeId(batch.getMaterialId());
    }

    /** 在已发布的来料检验方案里找出第一个覆盖该物料的方案，方案按编号升序保证结果稳定。 */
    private Long firstMatchingSchemeId(Long materialId) {
        List<com.neuedu.ems.entity.qms.QmsInspectionScheme> schemes = qmsInspectionSchemeMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.qms.QmsInspectionScheme>()
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionScheme::getInspectionType, INCOMING_INSPECTION_TYPE)
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionScheme::getStatus, BusinessStatus.Document.RELEASED)
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionScheme::getDeletedFlag, 0)
                        .orderByAsc(com.neuedu.ems.entity.qms.QmsInspectionScheme::getSchemeId));
        Long selected = null;
        int nearest = Integer.MAX_VALUE;
        for (com.neuedu.ems.entity.qms.QmsInspectionScheme scheme : schemes) {
            if (scheme.getProductId() == null) {
                continue;
            }
            Long bomId = releasedBomId(scheme.getProductId());
            if (bomId == null) {
                continue;
            }
            int depth = materialDepth(bomId, materialId);
            if (depth >= 0 && depth < nearest) {
                selected = scheme.getSchemeId();
                nearest = depth;
            }
        }
        return selected;
    }

    /** 广度优先检查子 BOM；访问集合防止历史循环关系导致无限递归。 */
    private int materialDepth(Long bomId, Long materialId) {
        java.util.Set<Long> visited = new java.util.HashSet<>();
        List<Long> level = List.of(bomId);
        int depth = 0;
        while (!level.isEmpty()) {
            List<Long> next = new java.util.ArrayList<>();
            for (Long current : level) {
                if (!visited.add(current)) continue;
                Long matched = mdmBomItemMapper.selectCount(new LambdaQueryWrapper<com.neuedu.ems.entity.mdm.MdmBomItem>()
                        .eq(com.neuedu.ems.entity.mdm.MdmBomItem::getBomId, current)
                        .eq(com.neuedu.ems.entity.mdm.MdmBomItem::getMaterialId, materialId)
                        .eq(com.neuedu.ems.entity.mdm.MdmBomItem::getDeletedFlag, 0));
                if (matched != null && matched > 0) return depth;
                for (var item : mdmBomItemMapper.selectList(new LambdaQueryWrapper<com.neuedu.ems.entity.mdm.MdmBomItem>()
                        .eq(com.neuedu.ems.entity.mdm.MdmBomItem::getBomId, current)
                        .eq(com.neuedu.ems.entity.mdm.MdmBomItem::getDeletedFlag, 0))) {
                    if (item.getChildBomId() != null) next.add(item.getChildBomId());
                }
            }
            level = next;
            depth++;
        }
        return -1;
    }

    private Long releasedBomId(Long productId) {
        List<com.neuedu.ems.entity.mdm.MdmBom> boms = mdmBomMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.mdm.MdmBom>()
                        .eq(com.neuedu.ems.entity.mdm.MdmBom::getProductId, productId)
                        .eq(com.neuedu.ems.entity.mdm.MdmBom::getStatus, BusinessStatus.Document.RELEASED)
                        .eq(com.neuedu.ems.entity.mdm.MdmBom::getDeletedFlag, 0)
                        .orderByDesc(com.neuedu.ems.entity.mdm.MdmBom::getBomId));
        return boms.isEmpty() ? null : boms.get(0).getBomId();
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }
}
