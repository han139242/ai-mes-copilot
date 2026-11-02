package com.neuedu.ems.service.impl.wms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.MdmCustomer;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.qms.QmsInspectionScheme;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.entity.wms.WmsOutboundDetail;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmCustomerMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.impl.ai.InventoryAutomationTrigger;
import com.neuedu.ems.service.wms.WmsSalesOutboundService;
import com.neuedu.ems.vo.wms.WmsSalesOutboundView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 销售发货出库 Service 实现类。
 * 负责生产订单完工后的销售出库生成、出货检验、发运确认与库存扣减。
 */
@Service
public class WmsSalesOutboundServiceImpl extends ServiceImpl<WmsOutboundOrderMapper, WmsOutboundOrder>
        implements WmsSalesOutboundService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WmsSalesOutboundServiceImpl.class);

    @Autowired(required = false)
    private InventoryAutomationTrigger inventoryAutomationTrigger;
    @Autowired private com.neuedu.ems.service.mdm.ManufacturingPreparationGate preparationGate;
    @Autowired(required = false) private com.neuedu.ems.service.mfg.StockComponentSupplyService stockComponentSupply;

    private static final String SALES_OUTBOUND_TYPE = "SALE";
    private static final String FINISHED_WAREHOUSE_TYPE = "FINISHED";
    private static final String SUBMITTED_STATUS = BusinessStatus.Document.SUBMITTED;
    private static final String APPROVED_STATUS = BusinessStatus.Document.APPROVED;
    /** 允许生成销售出库单的生产订单状态：已下达、生产中、已完成。 */
    private static final Set<String> GENERATABLE_PRODUCTION_STATUSES = Set.of(
            BusinessStatus.Document.RELEASED,
            BusinessStatus.Production.RUNNING,
            BusinessStatus.Production.FINISHED);
    private static final String OUTGOING_INSPECTION_TYPE = "OUTGOING";
    private static final String INSPECTION_FINISHED_STATUS = "FINISHED";
    private static final Set<String> PASSED_INSPECTION_RESULTS = Set.of(
            BusinessStatus.Quality.PASS, BusinessStatus.Quality.CONCESSION);
    private static final Set<String> RELEASED_QUALITY_STATUSES = Set.of(
            BusinessStatus.Quality.PASS, BusinessStatus.Quality.CONCESSION);
    private static final Set<String> ISSUABLE_BATCH_STATUSES = Set.of("AVAILABLE", "ENABLE");
    private static final DateTimeFormatter SALES_NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Autowired
    private WmsOutboundDetailMapper wmsOutboundDetailMapper;

    @Autowired
    private WmsInventoryMapper wmsInventoryMapper;

    @Autowired
    private WmsMaterialBatchMapper wmsMaterialBatchMapper;

    @Autowired
    private WmsStockMovementMapper wmsStockMovementMapper;

    @Autowired
    private WmsWarehouseMapper wmsWarehouseMapper;

    @Autowired
    private MdmCustomerMapper mdmCustomerMapper;

    @Autowired
    private MdmProductMapper mdmProductMapper;

    @Autowired
    private MdmMaterialMapper mdmMaterialMapper;

    @Autowired
    private MfgProductionOrderMapper mfgProductionOrderMapper;

    @Autowired
    private MfgProductionPlanMapper mfgProductionPlanMapper;

    @Autowired
    private MfgWorkOrderMapper mfgWorkOrderMapper;

    @Autowired
    private QmsInspectionSchemeMapper qmsInspectionSchemeMapper;

    @Autowired
    private QmsInspectionOrderMapper qmsInspectionOrderMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    // 发货确认后回写销售订单的已发数量；销售订单服务不反向依赖本类，不会产生循环依赖。
    @Autowired
    private com.neuedu.ems.service.mfg.MfgSalesOrderService salesOrderService;

    /**
     * 根据生产订单生成销售出库单，按成品仓可用库存 FIFO 预分配批次。
     * @param productionOrderId 生产订单ID
     * @param customerId 经销商客户ID
     * @param warehouseId 成品仓仓库ID
     * @param locationId 默认发货库位ID，可为空
     * @param remark 备注
     * @param operatorId 操作人ID
     * @return 生成的销售出库单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsOutboundOrder generateSalesOutbound(Long productionOrderId, Long customerId, Long warehouseId,
                                                  Long locationId, String remark, Long operatorId) {
        requireSalesOutboundParams(productionOrderId, customerId, warehouseId, operatorId);
        MfgProductionOrder productionOrder = requireGeneratableProductionOrder(productionOrderId);
        requireMatchingCustomer(productionOrder, customerId);
        requireEnabledCustomer(customerId);
        requireFinishedWarehouse(warehouseId);
        List<WmsOutboundOrder> existingOrders = listActiveSalesOrders(productionOrderId);
        if (!existingOrders.isEmpty()) {
            // 同一生产订单只保留一张未作废的销售出库单，重复请求直接返回已生成的单据。
            requireMatchingCustomer(productionOrder, existingOrders.get(0).getCustomerId());
            return existingOrders.get(0);
        }
        List<MfgWorkOrder> workOrders = requireFinishedWorkOrders(productionOrderId);
        MdmProduct product = requireProductWithMaterial(productionOrder.getProductId());
        preparationGate.assertExecutionSupported(product.getProductId());
        BigDecimal shippableQuantity = completedQuantityOfProduct(workOrders, productionOrder.getProductId());
        if (shippableQuantity.signum() <= 0) {
            throw business(ResultCode.BUSINESS_ERROR, "该生产订单没有可发货的完工数量");
        }
        List<SalesAllocation> allocations = allocateFinishedStock(
                warehouseId, locationId, product.getMaterialId(), shippableQuantity);
        MfgWorkOrder lastWorkOrder = workOrders.stream()
                .filter(workOrder -> Objects.equals(productionOrder.getProductId(), workOrder.getProductId()))
                .max(Comparator.comparing(MfgWorkOrder::getWorkOrderId))
                .orElseThrow(() -> business(ResultCode.BUSINESS_ERROR, "未找到订单产品对应的完工工单，不能生成销售出库单"));
        LocalDateTime now = LocalDateTime.now();
        WmsOutboundOrder order = insertSalesOutbound(productionOrderId, customerId, warehouseId,
                lastWorkOrder, remark, operatorId, now);
        insertSalesOutboundDetails(order, product.getMaterialId(), productionOrderId, allocations, operatorId, now);
        return order;
    }

    /** 入参必填校验。 */
    private void requireSalesOutboundParams(Long productionOrderId, Long customerId, Long warehouseId,
                                            Long operatorId) {
        if (productionOrderId == null || customerId == null || warehouseId == null) {
            throw business(ResultCode.PARAM_ERROR, "生产订单、客户和成品仓不能为空");
        }
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "当前登录用户不能为空");
        }
    }

    /** 生产订单必须存在且处于可发货状态。 */
    private MfgProductionOrder requireGeneratableProductionOrder(Long productionOrderId) {
        MfgProductionOrder productionOrder = mfgProductionOrderMapper.selectOne(
                new LambdaQueryWrapper<MfgProductionOrder>()
                        .eq(MfgProductionOrder::getProductionOrderId, productionOrderId)
                        .eq(MfgProductionOrder::getDeletedFlag, 0)
                        .last("FOR UPDATE"));
        if (productionOrder == null) {
            throw business(ResultCode.NOT_FOUND, "生产订单不存在或已删除");
        }
        // 生产订单下达后开工即转 RUNNING，完工后转 FINISHED；销售发货发生在完工之后，
        // 因此这里接受已下达、生产中、已完成三种状态，只拦截未下达和已关闭的订单。
        if (!GENERATABLE_PRODUCTION_STATUSES.contains(productionOrder.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "生产订单未下达或已关闭，不能生成销售出库单");
        }
        return productionOrder;
    }

    /** 已指定客户的生产订单不能向其他客户发货；兼容未指定客户的备货订单。 */
    private void requireMatchingCustomer(MfgProductionOrder productionOrder, Long customerId) {
        if (productionOrder.getCustomerId() != null
                && !Objects.equals(productionOrder.getCustomerId(), customerId)) {
            throw business(ResultCode.BUSINESS_ERROR, "发货客户必须与来源生产订单客户一致");
        }
    }

    /** 客户必须存在且处于启用状态。 */
    private void requireEnabledCustomer(Long customerId) {
        MdmCustomer customer = mdmCustomerMapper.selectOne(new LambdaQueryWrapper<MdmCustomer>()
                .eq(MdmCustomer::getCustomerId, customerId)
                .eq(MdmCustomer::getDeletedFlag, 0));
        if (customer == null) {
            throw business(ResultCode.NOT_FOUND, "客户不存在或已删除");
        }
        if (!BusinessStatus.Common.ENABLE.equals(customer.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "客户已停用，不能生成销售出库单");
        }
    }

    /** 出库仓库必须是启用的成品仓。 */
    private void requireFinishedWarehouse(Long warehouseId) {
        WmsWarehouse warehouse = wmsWarehouseMapper.selectOne(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseId, warehouseId)
                .eq(WmsWarehouse::getDeletedFlag, 0));
        if (warehouse == null) {
            throw business(ResultCode.NOT_FOUND, "出库仓库不存在或已删除");
        }
        if (!FINISHED_WAREHOUSE_TYPE.equals(warehouse.getWarehouseType())) {
            throw business(ResultCode.BUSINESS_ERROR, "销售发货只能从成品仓出库，请重新选择仓库");
        }
        if (!BusinessStatus.Common.ENABLE.equals(warehouse.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "出库仓库已停用，不能生成销售出库单");
        }
    }

    /** 生产订单下的所有生产工单都必须已完成。 */
    private List<MfgWorkOrder> requireFinishedWorkOrders(Long productionOrderId) {
        List<MfgWorkOrder> workOrders = listWorkOrdersOfProductionOrder(productionOrderId);
        if (workOrders.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "该生产订单尚未生成生产工单，不能生成销售出库单");
        }
        for (MfgWorkOrder workOrder : workOrders) {
            if (!BusinessStatus.Production.FINISHED.equals(workOrder.getStatus())
                    && (stockComponentSupply==null || !stockComponentSupply.fulfilledForShipment(workOrder))) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "生产工单 " + displayWorkOrder(workOrder) + " 尚未完成，不能生成销售出库单");
            }
        }
        return workOrders;
    }

    /** 生产订单产品必须存在且已关联成品物料。 */
    private MdmProduct requireProductWithMaterial(Long productId) {
        MdmProduct product = mdmProductMapper.selectOne(new LambdaQueryWrapper<MdmProduct>()
                .eq(MdmProduct::getProductId, productId)
                .eq(MdmProduct::getDeletedFlag, 0));
        if (product == null || product.getMaterialId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "生产订单产品不存在或未关联成品物料，不能生成销售出库单");
        }
        return product;
    }

    /** 新建销售出库单主表，落库失败直接抛出。 */
    private WmsOutboundOrder insertSalesOutbound(Long productionOrderId, Long customerId, Long warehouseId,
                                                 MfgWorkOrder lastWorkOrder, String remark,
                                                 Long operatorId, LocalDateTime now) {
        WmsOutboundOrder order = new WmsOutboundOrder();
        order.setOutboundNo(numbers.nextFor("SI","PRODUCTION_ORDER",productionOrderId));
        order.setOutboundType(SALES_OUTBOUND_TYPE);
        order.setWarehouseId(warehouseId);
        order.setWorkOrderId(lastWorkOrder.getWorkOrderId());
        order.setApplicantId(operatorId);
        order.setCustomerId(customerId);
        order.setSourceProductionOrderId(productionOrderId);
        order.setAuditStatus(SUBMITTED_STATUS);
        order.setRemark(StringUtils.hasText(remark) ? remark.trim() : "生产订单完工后生成销售出库");
        order.setVersion(0);
        order.setCreatedBy(operatorId);
        order.setCreatedTime(now);
        order.setUpdatedBy(operatorId);
        order.setUpdatedTime(now);
        order.setDeletedFlag(0);
        if (baseMapper.insert(order) == 0 || order.getOutboundId() == null) {
            throw business(ResultCode.FAILED, "销售出库单生成失败");
        }
        return order;
    }

    /** 按分配结果逐条写入销售出库明细。 */
    private void insertSalesOutboundDetails(WmsOutboundOrder order, Long materialId, Long productionOrderId,
                                            List<SalesAllocation> allocations, Long operatorId,
                                            LocalDateTime now) {
        for (SalesAllocation allocation : allocations) {
            WmsOutboundDetail detail = new WmsOutboundDetail();
            detail.setOutboundId(order.getOutboundId());
            detail.setMaterialId(materialId);
            detail.setBatchId(allocation.batch().getBatchId());
            detail.setLocationId(allocation.inventory().getLocationId());
            detail.setOutboundQuantity(allocation.availableQuantity());
            detail.setRemark("生产订单 " + productionOrderId + " 按批次 FIFO 自动分配");
            detail.setVersion(0);
            detail.setCreatedBy(operatorId);
            detail.setCreatedTime(now);
            detail.setUpdatedBy(operatorId);
            detail.setUpdatedTime(now);
            detail.setDeletedFlag(0);
            if (wmsOutboundDetailMapper.insert(detail) == 0) {
                throw business(ResultCode.FAILED, "销售出库明细生成失败");
            }
        }
    }

    /**
     * 分页查询销售出库单，返回客户名称。
     * @param current 当前页码
     * @param size 每页记录数
     * @param customerId 客户ID，可选
     * @param auditStatus 审核状态，可选
     * @return 销售出库单分页结果
     */
    @Override
    public Page<WmsSalesOutboundView> pageSalesOutbound(Integer current, Integer size, Long customerId,
                                                        String auditStatus) {
        Page<WmsOutboundOrder> source = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        LambdaQueryWrapper<WmsOutboundOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsOutboundOrder::getOutboundType, SALES_OUTBOUND_TYPE)
                .eq(WmsOutboundOrder::getDeletedFlag, 0)
                .eq(customerId != null, WmsOutboundOrder::getCustomerId, customerId)
                .eq(StringUtils.hasText(auditStatus), WmsOutboundOrder::getAuditStatus, auditStatus)
                .orderByDesc(WmsOutboundOrder::getCreatedTime)
                .orderByDesc(WmsOutboundOrder::getOutboundId);
        Page<WmsOutboundOrder> page = baseMapper.selectPage(source, queryWrapper);
        Map<Long, String> customerNames = customerNames(page.getRecords());
        Page<WmsSalesOutboundView> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<WmsSalesOutboundView> views = new ArrayList<>();
        for (WmsOutboundOrder order : page.getRecords()) {
            WmsSalesOutboundView view = new WmsSalesOutboundView();
            view.setOutboundId(order.getOutboundId());
            view.setOutboundNo(order.getOutboundNo());
            view.setOutboundType(order.getOutboundType());
            view.setWarehouseId(order.getWarehouseId());
            view.setWorkOrderId(order.getWorkOrderId());
            view.setApplicantId(order.getApplicantId());
            view.setCustomerId(order.getCustomerId());
            view.setSourceProductionOrderId(order.getSourceProductionOrderId());
            view.setInspectionId(order.getInspectionId());
            view.setAuditStatus(order.getAuditStatus());
            view.setOutboundTime(order.getOutboundTime());
            view.setRemark(order.getRemark());
            view.setVersion(order.getVersion());
            view.setCreatedBy(order.getCreatedBy());
            view.setCreatedTime(order.getCreatedTime());
            view.setUpdatedBy(order.getUpdatedBy());
            view.setUpdatedTime(order.getUpdatedTime());
            view.setDeletedFlag(order.getDeletedFlag());
            view.setCustomerName(customerNames.get(order.getCustomerId()));
            views.add(view);
        }
        result.setRecords(views);
        return result;
    }

    /**
     * 确认发货：校验出货检验结果，扣减成品仓可用库存并写入库存流水。
     * @param outboundId 出库单ID
     * @param operatorId 操作人ID
     * @param remark 备注
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmSalesOutbound(Long outboundId, Long operatorId, String remark) {
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "当前登录用户不能为空");
        }
        WmsOutboundOrder outboundOrder = getActiveSalesOrder(outboundId);
        if (outboundOrder == null) {
            throw business(ResultCode.NOT_FOUND, "销售出库单不存在或已删除");
        }
        if (!SUBMITTED_STATUS.equals(outboundOrder.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待确认的销售出库单可以确认发货");
        }
        MfgProductionOrder sourceOrder = mfgProductionOrderMapper.selectById(outboundOrder.getSourceProductionOrderId());
        if (sourceOrder == null || !Integer.valueOf(0).equals(sourceOrder.getDeletedFlag()))
            throw business(ResultCode.BUSINESS_ERROR, "销售出库来源订单无效");
        requireMatchingCustomer(sourceOrder, outboundOrder.getCustomerId());
        preparationGate.assertExecutionSupported(sourceOrder.getProductId());
        assertInspectionPassed(outboundOrder);
        List<WmsOutboundDetail> details = listActiveDetails(outboundId);
        if (details.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "销售出库单没有有效明细，不能确认发货");
        }
        LocalDateTime now = LocalDateTime.now();
        WmsOutboundOrder update = new WmsOutboundOrder();
        update.setOutboundId(outboundId);
        update.setVersion(outboundOrder.getVersion());
        update.setAuditStatus(APPROVED_STATUS);
        update.setOutboundTime(now);
        if (StringUtils.hasText(remark)) {
            update.setRemark(remark.trim());
        }
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "销售出库单已被其他用户修改，请刷新后重试");
        }
        for (WmsOutboundDetail detail : details) {
            deductFinishedStock(outboundOrder, detail, operatorId, now);
            insertSalesMovement(outboundOrder, detail, operatorId, remark, now);
        }
        insertStatusChangeLog(outboundId, outboundOrder.getAuditStatus(), APPROVED_STATUS,
                "WMS_OUTBOUND_ORDER", "CONFIRM_SALES_OUTBOUND", operatorId, remark, now);
        insertOperationLog(outboundId, "CONFIRM_SALES_OUTBOUND", "wms_outbound_order", operatorId, remark, now);
        // 已发数量回写销售订单：按来源生产订单找到销售明细，重算已发与订单状态。
        // 回写失败不影响发货结果本身（库存已扣、流水已写），所以只在这里兜住异常。
        try {
            if (sourceOrder.getSourceSalesOrderId() != null) {
                salesOrderService.refreshDeliveredQuantity(sourceOrder.getSourceSalesOrderId(), operatorId);
            }
        } catch (RuntimeException error) {
            // 订单侧数据不一致时不让发货事务回滚，由销售订单页面手工核对，日志留痕。
            log.warn("回写销售订单已发数量失败，outboundId={}, salesOrderId={}: {}",
                    outboundId, sourceOrder.getSourceSalesOrderId(), error.getMessage());
        }
        return true;
    }

    /**
     * 为销售出库单生成出货检验单并回写 inspection_id。
     * @param outboundId 出库单ID
     * @param operatorId 操作人ID
     * @return 出货检验单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOutgoingInspection(Long outboundId, Long operatorId) {
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "当前登录用户不能为空");
        }
        WmsOutboundOrder outboundOrder = getActiveSalesOrder(outboundId);
        if (outboundOrder == null) {
            throw business(ResultCode.NOT_FOUND, "销售出库单不存在或已删除");
        }
        Long existingId = existingInspectionId(outboundOrder);
        if (existingId != null) {
            return existingId;
        }
        MfgProductionOrder productionOrder = requireSourceProductionOrder(outboundOrder);
        QmsInspectionScheme scheme = requireReleasedOutgoingScheme(productionOrder.getProductId());
        LocalDateTime now = LocalDateTime.now();
        QmsInspectionOrder inspection = insertOutgoingInspection(outboundOrder, scheme, productionOrder, operatorId, now);
        linkInspection(outboundOrder, inspection.getInspectionId());
        insertOperationLog(outboundId, "CREATE_OUTGOING_INSPECTION", "wms_outbound_order",
                operatorId, "生成出货检验单 " + inspection.getInspectionNo(), now);
        return inspection.getInspectionId();
    }

    /** 已关联且未删除的出货检验单直接复用，返回其主键；没有则返回 null。 */
    private Long existingInspectionId(WmsOutboundOrder outboundOrder) {
        if (!SUBMITTED_STATUS.equals(outboundOrder.getAuditStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有待确认的销售出库单可以生成出货检验单");
        }
        if (outboundOrder.getInspectionId() == null) {
            return null;
        }
        QmsInspectionOrder existing = qmsInspectionOrderMapper.selectOne(
                new LambdaQueryWrapper<QmsInspectionOrder>()
                        .eq(QmsInspectionOrder::getInspectionId, outboundOrder.getInspectionId())
                        .eq(QmsInspectionOrder::getDeletedFlag, 0));
        return existing == null ? null : existing.getInspectionId();
    }

    /** 出库单必须关联有效的生产订单。 */
    private MfgProductionOrder requireSourceProductionOrder(WmsOutboundOrder outboundOrder) {
        MfgProductionOrder productionOrder = outboundOrder.getSourceProductionOrderId() == null
                ? null
                : mfgProductionOrderMapper.selectById(outboundOrder.getSourceProductionOrderId());
        if (productionOrder == null) {
            throw business(ResultCode.BUSINESS_ERROR, "销售出库单未关联有效的生产订单，不能生成出货检验单");
        }
        return productionOrder;
    }

    /** 产品必须有已发布的出货检验方案，且已具备执行条件。 */
    private QmsInspectionScheme requireReleasedOutgoingScheme(Long productId) {
        QmsInspectionScheme scheme = latestReleasedOutgoingScheme(productId);
        preparationGate.assertExecutionSupported(productId);
        if (scheme == null) {
            throw business(ResultCode.BUSINESS_ERROR, "该产品未配置已发布的出货检验方案");
        }
        return scheme;
    }

    /** 新建出货检验单。 */
    private QmsInspectionOrder insertOutgoingInspection(WmsOutboundOrder outboundOrder, QmsInspectionScheme scheme,
                                                        MfgProductionOrder productionOrder,
                                                        Long operatorId, LocalDateTime now) {
        QmsInspectionOrder inspection = new QmsInspectionOrder();
        inspection.setInspectionNo(numbers.nextFor("QI","PRODUCTION_ORDER",productionOrder.getProductionOrderId()));
        inspection.setInspectionType(OUTGOING_INSPECTION_TYPE);
        inspection.setSampleQuantity(listActiveDetails(outboundOrder.getOutboundId()).stream()
                .map(WmsOutboundDetail::getOutboundQuantity).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add));
        inspection.setSchemeId(scheme.getSchemeId());
        inspection.setWorkOrderId(outboundOrder.getWorkOrderId());
        inspection.setStatus(BusinessStatus.Document.DRAFT);
        inspection.setRemark("销售出库单 " + outboundOrder.getOutboundNo() + " 出货检验");
        inspection.setVersion(0);
        inspection.setCreatedBy(operatorId);
        inspection.setCreatedTime(now);
        inspection.setUpdatedBy(operatorId);
        inspection.setUpdatedTime(now);
        inspection.setDeletedFlag(0);
        if (qmsInspectionOrderMapper.insert(inspection) == 0 || inspection.getInspectionId() == null) {
            throw business(ResultCode.FAILED, "出货检验单生成失败");
        }
        return inspection;
    }

    /** 把检验单主键回写到销售出库单，版本冲突即报错。 */
    private void linkInspection(WmsOutboundOrder outboundOrder, Long inspectionId) {
        WmsOutboundOrder update = new WmsOutboundOrder();
        update.setOutboundId(outboundOrder.getOutboundId());
        update.setVersion(outboundOrder.getVersion());
        update.setInspectionId(inspectionId);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "销售出库单已被其他用户修改，请刷新后重试");
        }
    }

    /**
     * 校验出货检验单已完成且结果为合格或让步放行。
     * @param outboundOrder 销售出库单
     */
    private void assertInspectionPassed(WmsOutboundOrder outboundOrder) {
        if (outboundOrder.getInspectionId() == null) {
            throw business(ResultCode.BUSINESS_ERROR, "成品未通过出货检验，不能发运");
        }
        QmsInspectionOrder inspection = qmsInspectionOrderMapper.selectOne(
                new LambdaQueryWrapper<QmsInspectionOrder>()
                        .eq(QmsInspectionOrder::getInspectionId, outboundOrder.getInspectionId())
                        .eq(QmsInspectionOrder::getDeletedFlag, 0));
        if (inspection == null
                || !INSPECTION_FINISHED_STATUS.equals(inspection.getStatus())
                || !PASSED_INSPECTION_RESULTS.contains(inspection.getInspectionResult())) {
            throw business(ResultCode.BUSINESS_ERROR, "成品未通过出货检验，不能发运");
        }
    }

    /**
     * 按成品仓可用库存和批次 FIFO 分配发货数量。
     * @param warehouseId 成品仓仓库ID
     * @param locationId 指定库位ID，可为空
     * @param materialId 成品物料ID
     * @param requiredQuantity 需要发货的数量
     * @return 分配明细
     */
    private List<SalesAllocation> allocateFinishedStock(Long warehouseId, Long locationId, Long materialId,
                                                        BigDecimal requiredQuantity) {
        LambdaQueryWrapper<WmsInventory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WmsInventory::getWarehouseId, warehouseId)
                .eq(WmsInventory::getMaterialId, materialId)
                .gt(WmsInventory::getAvailableQuantity, BigDecimal.ZERO)
                .eq(locationId != null, WmsInventory::getLocationId, locationId)
                .eq(WmsInventory::getDeletedFlag, 0)
                .orderByAsc(WmsInventory::getInventoryId);
        List<WmsInventory> inventories = wmsInventoryMapper.selectList(queryWrapper);
        List<SalesAllocation> sources = new ArrayList<>();
        BigDecimal availableTotal = BigDecimal.ZERO;
        for (WmsInventory inventory : inventories) {
            if (inventory.getBatchId() == null || inventory.getLocationId() == null) {
                continue;
            }
            WmsMaterialBatch batch = getActiveBatch(inventory.getBatchId());
            if (batch == null || !Objects.equals(batch.getMaterialId(), materialId)) {
                continue;
            }
            // 生成阶段只按可用数量预分配，批次是否放行在确认发货时逐条校验。
            BigDecimal available = zero(inventory.getAvailableQuantity());
            if (available.signum() <= 0) {
                continue;
            }
            availableTotal = availableTotal.add(available);
            sources.add(new SalesAllocation(inventory, batch, available));
        }
        if (availableTotal.compareTo(requiredQuantity) < 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "成品仓可用库存不足，物料 " + displayMaterial(materialId)
                            + " 需要 " + plain(requiredQuantity) + "，现有 " + plain(availableTotal));
        }
        sources.sort(Comparator
                .comparing((SalesAllocation source) -> source.batch().getReceivedDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(source -> source.batch().getBatchId())
                .thenComparing(source -> source.inventory().getInventoryId()));
        List<SalesAllocation> allocations = new ArrayList<>();
        BigDecimal remaining = requiredQuantity;
        for (SalesAllocation source : sources) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal allocated = remaining.min(source.availableQuantity());
            if (allocated.signum() <= 0) {
                continue;
            }
            allocations.add(new SalesAllocation(source.inventory(), source.batch(), allocated));
            remaining = remaining.subtract(allocated);
        }
        return allocations;
    }

    /**
     * 扣减成品仓可用库存，按库存行逐条乐观锁扣减。
     * @param outboundOrder 销售出库单
     * @param detail 出库明细
     * @param operatorId 操作人ID
     * @param operationTime 操作时间
     */
    /** 出库明细的必要字段：物料、批次、库位齐全，且数量为正。 */
    private static boolean hasCompleteOutboundFields(WmsOutboundDetail detail) {
        if (detail.getMaterialId() == null || detail.getBatchId() == null || detail.getLocationId() == null) {
            return false;
        }
        BigDecimal quantity = detail.getOutboundQuantity();
        return quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0;
    }

    private void deductFinishedStock(WmsOutboundOrder outboundOrder, WmsOutboundDetail detail,
                                     Long operatorId, LocalDateTime operationTime) {
        if (!hasCompleteOutboundFields(detail)) {
            throw business(ResultCode.PARAM_ERROR, "出库明细的物料、批次、库位和正数数量不能为空");
        }
        WmsMaterialBatch batch = getActiveBatch(detail.getBatchId());
        if (batch == null) {
            throw business(ResultCode.NOT_FOUND, "出库明细关联的物料批次不存在或已删除");
        }
        if (!Objects.equals(detail.getMaterialId(), batch.getMaterialId())) {
            throw business(ResultCode.BUSINESS_ERROR, "出库明细物料与批次物料不一致");
        }
        if (!RELEASED_QUALITY_STATUSES.contains(batch.getQualityStatus())
                || !ISSUABLE_BATCH_STATUSES.contains(batch.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "批次未放行或不可用，禁止出库");
        }
        List<WmsInventory> inventories = wmsInventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWarehouseId, outboundOrder.getWarehouseId())
                .eq(WmsInventory::getLocationId, detail.getLocationId())
                .eq(WmsInventory::getMaterialId, detail.getMaterialId())
                .eq(WmsInventory::getBatchId, detail.getBatchId())
                .eq(WmsInventory::getDeletedFlag, 0)
                .orderByAsc(WmsInventory::getInventoryId));
        BigDecimal required = detail.getOutboundQuantity();
        BigDecimal availableTotal = inventories.stream()
                .map(inventory -> zero(inventory.getAvailableQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (availableTotal.compareTo(required) < 0) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "成品仓可用库存不足，物料 " + displayMaterial(detail.getMaterialId())
                            + " 需要 " + plain(required) + "，现有 " + plain(availableTotal));
        }
        BigDecimal remaining = required;
        for (WmsInventory inventory : inventories) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal available = zero(inventory.getAvailableQuantity());
            if (available.signum() <= 0) {
                continue;
            }
            BigDecimal deducted = remaining.min(available);
            WmsInventory update = new WmsInventory();
            update.setInventoryId(inventory.getInventoryId());
            update.setVersion(inventory.getVersion());
            update.setAvailableQuantity(available.subtract(deducted));
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(operationTime);
            if (wmsInventoryMapper.updateById(update) == 0) {
                throw business(ResultCode.DATA_CONFLICT, "库存余额已被其他请求修改，请刷新后重试");
            }
            remaining = remaining.subtract(deducted);
        }
        notifyAvailableChanged(detail.getMaterialId());
    }

    private void notifyAvailableChanged(Long materialId) {
        if (inventoryAutomationTrigger != null) {
            inventoryAutomationTrigger.afterChange(materialId);
        }
    }

    /**
     * 写入销售出库库存流水。
     * @param outboundOrder 销售出库单
     * @param detail 出库明细
     * @param operatorId 操作人ID
     * @param remark 备注
     * @param operationTime 操作时间
     */
    private void insertSalesMovement(WmsOutboundOrder outboundOrder, WmsOutboundDetail detail,
                                     Long operatorId, String remark, LocalDateTime operationTime) {
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo("WMS-SALES-" + outboundOrder.getOutboundId() + "-" + detail.getDetailId());
        movement.setMovementType("SALES_OUT");
        movement.setDirection("OUT");
        movement.setSourceOrderType("OUTBOUND_ORDER");
        movement.setSourceOrderId(outboundOrder.getOutboundId());
        movement.setWarehouseId(outboundOrder.getWarehouseId());
        movement.setLocationId(detail.getLocationId());
        movement.setWorkOrderId(outboundOrder.getWorkOrderId());
        movement.setPairNo(null);
        movement.setMaterialId(detail.getMaterialId());
        movement.setBatchId(detail.getBatchId());
        movement.setQuantity(detail.getOutboundQuantity());
        movement.setOperationTime(operationTime);
        movement.setRemark(StringUtils.hasText(remark) ? remark : "销售发货出库");
        movement.setVersion(0);
        movement.setCreatedBy(operatorId);
        movement.setCreatedTime(operationTime);
        movement.setUpdatedBy(operatorId);
        movement.setUpdatedTime(operationTime);
        movement.setDeletedFlag(0);
        if (wmsStockMovementMapper.insert(movement) == 0) {
            throw business(ResultCode.FAILED, "销售出库库存流水写入失败");
        }
    }

    /**
     * 查询指定生产订单未作废的销售出库单。
     * @param productionOrderId 生产订单ID
     * @return 销售出库单列表
     */
    private List<WmsOutboundOrder> listActiveSalesOrders(Long productionOrderId) {
        return baseMapper.selectList(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getOutboundType, SALES_OUTBOUND_TYPE)
                .eq(WmsOutboundOrder::getSourceProductionOrderId, productionOrderId)
                .eq(WmsOutboundOrder::getDeletedFlag, 0)
                .ne(WmsOutboundOrder::getAuditStatus, BusinessStatus.Document.CLOSED)
                .orderByAsc(WmsOutboundOrder::getOutboundId));
    }

    /**
     * 查询生产订单下的生产工单，通过生产计划建立关联。
     * @param productionOrderId 生产订单ID
     * @return 生产工单列表
     */
    private List<MfgWorkOrder> listWorkOrdersOfProductionOrder(Long productionOrderId) {
        List<MfgProductionPlan> plans = mfgProductionPlanMapper.selectList(
                new LambdaQueryWrapper<MfgProductionPlan>()
                        .eq(MfgProductionPlan::getProductionOrderId, productionOrderId)
                        .eq(MfgProductionPlan::getDeletedFlag, 0));
        if (plans.isEmpty()) {
            return List.of();
        }
        List<Long> planIds = plans.stream().map(MfgProductionPlan::getPlanId).toList();
        return mfgWorkOrderMapper.selectList(new LambdaQueryWrapper<MfgWorkOrder>()
                .in(MfgWorkOrder::getPlanId, planIds)
                .eq(MfgWorkOrder::getDeletedFlag, 0)
                .orderByAsc(MfgWorkOrder::getWorkOrderId));
    }

    /**
     * 汇总生产订单产品的完工数量。
     * @param workOrders 生产工单列表
     * @param productId 产品ID
     * @return 完工数量
     */
    private BigDecimal completedQuantityOfProduct(List<MfgWorkOrder> workOrders, Long productId) {
        BigDecimal total = BigDecimal.ZERO;
        for (MfgWorkOrder workOrder : workOrders) {
            if (productId != null && workOrder.getProductId() != null
                    && !Objects.equals(productId, workOrder.getProductId())) {
                continue;
            }
            total = total.add(zero(workOrder.getCompletedQuantity()));
        }
        return total;
    }

    /**
     * 查询最新一张已发布的出货检验方案。
     * @param productId 产品ID
     * @return 检验方案
     */
    private QmsInspectionScheme latestReleasedOutgoingScheme(Long productId) {
        if (productId == null) {
            return null;
        }
        List<QmsInspectionScheme> schemes = qmsInspectionSchemeMapper.selectList(
                new LambdaQueryWrapper<QmsInspectionScheme>()
                        .eq(QmsInspectionScheme::getProductId, productId)
                        .eq(QmsInspectionScheme::getInspectionType, OUTGOING_INSPECTION_TYPE)
                        .eq(QmsInspectionScheme::getStatus, BusinessStatus.Document.RELEASED)
                        .eq(QmsInspectionScheme::getDeletedFlag, 0)
                        .orderByDesc(QmsInspectionScheme::getSchemeId));
        return schemes.isEmpty() ? null : schemes.get(0);
    }

    /**
     * 查询未删除的销售出库单。
     * @param outboundId 出库单ID
     * @return 销售出库单
     */
    private WmsOutboundOrder getActiveSalesOrder(Long outboundId) {
        if (outboundId == null) {
            return null;
        }
        return baseMapper.selectOne(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getOutboundId, outboundId)
                .eq(WmsOutboundOrder::getOutboundType, SALES_OUTBOUND_TYPE)
                .eq(WmsOutboundOrder::getDeletedFlag, 0));
    }

    /**
     * 查询未删除的出库明细。
     * @param outboundId 出库单ID
     * @return 出库明细列表
     */
    private List<WmsOutboundDetail> listActiveDetails(Long outboundId) {
        return wmsOutboundDetailMapper.selectList(new LambdaQueryWrapper<WmsOutboundDetail>()
                .eq(WmsOutboundDetail::getOutboundId, outboundId)
                .eq(WmsOutboundDetail::getDeletedFlag, 0)
                .orderByAsc(WmsOutboundDetail::getDetailId));
    }

    /**
     * 查询未删除的物料批次。
     * @param batchId 批次ID
     * @return 物料批次
     */
    private WmsMaterialBatch getActiveBatch(Long batchId) {
        if (batchId == null) {
            return null;
        }
        return wmsMaterialBatchMapper.selectOne(new LambdaQueryWrapper<WmsMaterialBatch>()
                .eq(WmsMaterialBatch::getBatchId, batchId)
                .eq(WmsMaterialBatch::getDeletedFlag, 0));
    }

    /**
     * 批量查询客户名称。
     * @param orders 出库单列表
     * @return 客户ID与名称映射
     */
    private Map<Long, String> customerNames(List<WmsOutboundOrder> orders) {
        List<Long> customerIds = orders.stream()
                .map(WmsOutboundOrder::getCustomerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        List<MdmCustomer> customers = mdmCustomerMapper.selectList(new LambdaQueryWrapper<MdmCustomer>()
                .in(MdmCustomer::getCustomerId, customerIds)
                .eq(MdmCustomer::getDeletedFlag, 0));
        Map<Long, String> names = new HashMap<>();
        for (MdmCustomer customer : customers) {
            names.put(customer.getCustomerId(), customer.getCustomerName());
        }
        return names;
    }

    /**
     * 取物料展示文本，优先物料编码，缺失时回退物料ID。
     * @param materialId 物料ID
     * @return 展示文本
     */
    private String displayMaterial(Long materialId) {
        if (materialId == null) {
            return "未知物料";
        }
        MdmMaterial material = mdmMaterialMapper.selectById(materialId);
        String text = material == null ? null : materialText(material);
        return text == null ? String.valueOf(materialId) : text;
    }

    /** 优先物料编码，其次物料名称；都没有时返回 null。 */
    private String materialText(MdmMaterial material) {
        if (StringUtils.hasText(material.getMaterialCode())) {
            return material.getMaterialCode();
        }
        String name = material.getMaterialName();
        return StringUtils.hasText(name) ? name : null;
    }

    private String displayWorkOrder(MfgWorkOrder workOrder) {
        return StringUtils.hasText(workOrder.getWorkOrderNo())
                ? workOrder.getWorkOrderNo() : String.valueOf(workOrder.getWorkOrderId());
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
        statusLog.setRemark(remark);
        statusLog.setVersion(0);
        statusLog.setCreatedBy(operatorId);
        statusLog.setCreatedTime(changedTime);
        statusLog.setUpdatedBy(operatorId);
        statusLog.setUpdatedTime(changedTime);
        statusLog.setDeletedFlag(0);
        if (sysStatusChangeLogMapper.insert(statusLog) == 0) {
            throw business(ResultCode.FAILED, "销售出库状态日志写入失败");
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
        operationLog.setRemark(remark);
        operationLog.setVersion(0);
        operationLog.setCreatedBy(operatorId);
        operationLog.setCreatedTime(operationTime);
        operationLog.setUpdatedBy(operatorId);
        operationLog.setUpdatedTime(operationTime);
        operationLog.setDeletedFlag(0);
        if (sysOperationLogMapper.insert(operationLog) == 0) {
            throw business(ResultCode.FAILED, "销售出库操作日志写入失败");
        }
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }

    /**
     * 销售发货库存分配来源。
     * @param inventory 库存余额
     * @param batch 物料批次
     * @param availableQuantity 可分配数量
     */
    private record SalesAllocation(WmsInventory inventory, WmsMaterialBatch batch, BigDecimal availableQuantity) {
    }
}
