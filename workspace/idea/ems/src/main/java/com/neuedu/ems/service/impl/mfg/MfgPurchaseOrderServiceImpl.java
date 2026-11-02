package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrder;
import com.neuedu.ems.entity.mfg.MfgPurchaseOrderDetail;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mdm.MdmSupplier;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmSupplierMapper;
import com.neuedu.ems.mapper.mfg.MfgPurchaseOrderDetailMapper;
import com.neuedu.ems.mapper.mfg.MfgPurchaseOrderMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.wms.WmsInboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.mfg.MfgPurchaseOrderService;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购订单 Service 实现。
 *
 * <p>下达时只生成入库单头和明细（物料 + 采购数量），批次和库位留空等收货时录，
 * 因为下单时货还没到、批次号还不存在。仓库收货确认入库后，已收数量按入库流水回写。</p>
 */
@Service
public class MfgPurchaseOrderServiceImpl extends ServiceImpl<MfgPurchaseOrderMapper, MfgPurchaseOrder>
        implements MfgPurchaseOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;


    private static final DateTimeFormatter ORDER_NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String PURCHASE_INBOUND_TYPE = "PURCHASE";
    private static final String SOURCE_ORDER_TYPE = "PURCHASE_ORDER";
    private static final String RAW_WAREHOUSE_TYPE = "RAW";

    @Autowired
    private MfgPurchaseOrderDetailMapper detailMapper;

    /** 下达采购订单时生成入库单。延迟注入，避免和入库确认回写采购订单互相卡住。 */
    @Autowired
    @Lazy
    private WmsInboundOrderService inboundOrderService;

    @Autowired
    private WmsInboundOrderMapper inboundOrderMapper;

    @Autowired
    private WmsInboundDetailMapper inboundDetailMapper;

    @Autowired
    private WmsWarehouseMapper warehouseMapper;

    @Autowired
    private MdmSupplierMapper supplierMapper;

    @Autowired
    private MdmMaterialMapper materialMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Override
    public Page<MfgPurchaseOrder> pagePurchaseOrders(Integer current, Integer size, String keyword, String status,
                                                     Long supplierId) {
        Page<MfgPurchaseOrder> page = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        return baseMapper.selectPage(page, buildQuery(keyword, status, supplierId));
    }

    @Override
    public List<MfgPurchaseOrder> listPurchaseOrders(String keyword, String status, Long supplierId) {
        return baseMapper.selectList(buildQuery(keyword, status, supplierId).orderByDesc("purchase_order_id"));
    }

    @Override
    public MfgPurchaseOrder getPurchaseOrderById(Long purchaseOrderId) {
        if (purchaseOrderId == null) {
            return null;
        }
        return baseMapper.selectOne(new QueryWrapper<MfgPurchaseOrder>()
                .eq("purchase_order_id", purchaseOrderId).eq("deleted_flag", 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePurchaseOrderFull(MfgPurchaseOrder purchaseOrder, List<MfgPurchaseOrderDetail> details,
                                      Long operatorId) {
        requireOperator(operatorId);
        if (purchaseOrder == null || purchaseOrder.getSupplierId() == null) {
            throw business(ResultCode.PARAM_ERROR, "供应商不能为空");
        }
        requireEnabledSupplier(purchaseOrder.getSupplierId());
        List<MfgPurchaseOrderDetail> rows = normalizeDetails(details);
        LocalDateTime now = LocalDateTime.now();
        purchaseOrder.setPurchaseOrderId(null);
        purchaseOrder.setPurchaseOrderNo(numbers.next("PU",null));
        purchaseOrder.setStatus(BusinessStatus.Document.DRAFT);
        purchaseOrder.setTotalQuantity(sumQuantity(rows));
        purchaseOrder.setVersion(0);
        purchaseOrder.setCreatedBy(operatorId);
        purchaseOrder.setCreatedTime(now);
        purchaseOrder.setUpdatedBy(operatorId);
        purchaseOrder.setUpdatedTime(now);
        purchaseOrder.setDeletedFlag(0);
        if (baseMapper.insert(purchaseOrder) == 0 || purchaseOrder.getPurchaseOrderId() == null) {
            throw business(ResultCode.FAILED, "采购订单保存失败");
        }
        insertDetails(rows, purchaseOrder.getPurchaseOrderId(), operatorId, now);
        insertOperationLog(purchaseOrder.getPurchaseOrderId(), "CREATE_PURCHASE_ORDER", operatorId,
                "新建采购订单 " + purchaseOrder.getPurchaseOrderNo(), now);
        return purchaseOrder.getPurchaseOrderId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updatePurchaseOrderFull(MfgPurchaseOrder purchaseOrder, List<MfgPurchaseOrderDetail> details,
                                           Long operatorId) {
        requireOperator(operatorId);
        if (purchaseOrder == null || purchaseOrder.getPurchaseOrderId() == null) {
            throw business(ResultCode.PARAM_ERROR, "采购订单 ID 不能为空");
        }
        MfgPurchaseOrder existing = requireDraft(purchaseOrder.getPurchaseOrderId());
        List<MfgPurchaseOrderDetail> rows = normalizeDetails(details);
        LocalDateTime now = LocalDateTime.now();
        purchaseOrder.setStatus(null);
        purchaseOrder.setPurchaseOrderNo(null);
        purchaseOrder.setTotalQuantity(sumQuantity(rows));
        purchaseOrder.setDeletedFlag(null);
        purchaseOrder.setUpdatedBy(operatorId);
        purchaseOrder.setUpdatedTime(now);
        if (purchaseOrder.getVersion() == null) {
            purchaseOrder.setVersion(existing.getVersion());
        }
        if (baseMapper.updateById(purchaseOrder) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "采购订单已被其他用户修改，请刷新后重试");
        }
        softDeleteDetails(existing.getPurchaseOrderId(), operatorId, now);
        insertDetails(rows, existing.getPurchaseOrderId(), operatorId, now);
        insertOperationLog(existing.getPurchaseOrderId(), "UPDATE_PURCHASE_ORDER", operatorId,
                "修改采购订单 " + existing.getPurchaseOrderNo(), now);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removePurchaseOrderById(Long purchaseOrderId, Long operatorId) {
        requireOperator(operatorId);
        MfgPurchaseOrder existing = requireDraft(purchaseOrderId);
        LocalDateTime now = LocalDateTime.now();
        MfgPurchaseOrder update = new MfgPurchaseOrder();
        update.setPurchaseOrderId(existing.getPurchaseOrderId());
        update.setDeletedFlag(1);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        update.setVersion(existing.getVersion());
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "采购订单已被其他用户修改，请刷新后重试");
        }
        softDeleteDetails(existing.getPurchaseOrderId(), operatorId, now);
        insertOperationLog(existing.getPurchaseOrderId(), "DELETE_PURCHASE_ORDER", operatorId,
                "删除采购订单 " + existing.getPurchaseOrderNo(), now);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long releasePurchaseOrder(Long purchaseOrderId, Long operatorId, String remark) {
        requireOperator(operatorId);
        MfgPurchaseOrder existing = getPurchaseOrderById(purchaseOrderId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "采购订单不存在或已删除");
        }
        if (!BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有草稿采购订单可以下达");
        }
        if (existing.getWarehouseId() == null) {
            throw business(ResultCode.PARAM_ERROR, "下达前必须指定收货仓库");
        }
        WmsWarehouse warehouse = warehouseMapper.selectOne(new QueryWrapper<WmsWarehouse>()
                .eq("warehouse_id", existing.getWarehouseId()).eq("deleted_flag", 0));
        if (warehouse == null) {
            throw business(ResultCode.NOT_FOUND, "收货仓库不存在或已删除");
        }
        if (!RAW_WAREHOUSE_TYPE.equals(warehouse.getWarehouseType())) {
            throw business(ResultCode.BUSINESS_ERROR, "采购到货只能收进原料仓，请重新选择仓库");
        }
        if (!BusinessStatus.Common.ENABLE.equals(warehouse.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "收货仓库已停用，不能下达采购订单");
        }
        List<MfgPurchaseOrderDetail> rows = listDetails(purchaseOrderId);
        if (rows.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "采购订单没有明细，不能下达");
        }
        LocalDateTime now = LocalDateTime.now();
        WmsInboundOrder inbound = new WmsInboundOrder();
        inbound.setInboundNo(numbers.next("IN",null));
        inbound.setInboundType(PURCHASE_INBOUND_TYPE);
        inbound.setWarehouseId(existing.getWarehouseId());
        inbound.setSourceOrderType(SOURCE_ORDER_TYPE);
        inbound.setSourceOrderId(purchaseOrderId);
        inbound.setRemark(StringUtils.hasText(remark) ? remark.trim()
                : "由采购订单 " + existing.getPurchaseOrderNo() + " 下达生成，收货时补录批次与库位");
        inbound.setVersion(0);
        inbound.setCreatedBy(operatorId);
        inbound.setCreatedTime(now);
        inbound.setUpdatedBy(operatorId);
        inbound.setUpdatedTime(now);
        inbound.setDeletedFlag(0);
        List<WmsInboundDetail> inboundDetails = new ArrayList<>();
        for (MfgPurchaseOrderDetail row : rows) {
            WmsInboundDetail detail = new WmsInboundDetail();
            detail.setMaterialId(row.getMaterialId());
            detail.setInboundQuantity(row.getOrderQuantity());
            detail.setRemark("采购明细 " + row.getDetailId());
            inboundDetails.add(detail);
        }
        Long inboundId = inboundOrderService.saveInboundOrderFull(inbound, inboundDetails);
        if (inboundId == null) {
            throw business(ResultCode.FAILED, "采购入库单生成失败");
        }
        MfgPurchaseOrder update = new MfgPurchaseOrder();
        update.setPurchaseOrderId(purchaseOrderId);
        update.setStatus(BusinessStatus.Document.RELEASED);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "采购订单已被其他用户修改，请刷新后重试");
        }
        insertStatusLog(purchaseOrderId, existing.getStatus(), BusinessStatus.Document.RELEASED,
                "RELEASE_PURCHASE_ORDER", operatorId, remark, now);
        insertOperationLog(purchaseOrderId, "RELEASE_PURCHASE_ORDER", operatorId,
                "下达采购订单 " + existing.getPurchaseOrderNo() + "，生成采购入库单 " + inboundId, now);
        return inboundId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawPurchaseOrder(Long purchaseOrderId, Long operatorId, String remark) {
        requireOperator(operatorId);
        MfgPurchaseOrder existing = getPurchaseOrderById(purchaseOrderId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "采购订单不存在或已删除");
        }
        if (!BusinessStatus.Document.RELEASED.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已下达的采购订单可以撤回");
        }
        List<WmsInboundOrder> inbounds = inboundOrderMapper.selectList(new QueryWrapper<WmsInboundOrder>()
                .eq("source_order_type", SOURCE_ORDER_TYPE)
                .eq("source_order_id", purchaseOrderId)
                .eq("deleted_flag", 0));
        LocalDateTime now = LocalDateTime.now();
        for (WmsInboundOrder inbound : inbounds) {
            if (BusinessStatus.Document.APPROVED.equals(inbound.getAuditStatus())) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "采购入库单 " + inbound.getInboundNo() + " 已确认入库，不能撤回采购订单");
            }
        }
        for (WmsInboundOrder inbound : inbounds) {
            inboundOrderService.removeInboundOrderById(inbound.getInboundId());
        }
        MfgPurchaseOrder update = new MfgPurchaseOrder();
        update.setPurchaseOrderId(purchaseOrderId);
        update.setStatus(BusinessStatus.Document.DRAFT);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "采购订单已被其他用户修改，请刷新后重试");
        }
        insertStatusLog(purchaseOrderId, existing.getStatus(), BusinessStatus.Document.DRAFT,
                "WITHDRAW_PURCHASE_ORDER", operatorId, remark, now);
        insertOperationLog(purchaseOrderId, "WITHDRAW_PURCHASE_ORDER", operatorId,
                "撤回采购订单 " + existing.getPurchaseOrderNo() + "，作废 " + inbounds.size() + " 张未确认入库单", now);
    }

    @Override
    public List<MfgPurchaseOrderDetail> listDetails(Long purchaseOrderId) {
        if (purchaseOrderId == null) {
            return List.of();
        }
        return detailMapper.selectList(new QueryWrapper<MfgPurchaseOrderDetail>()
                .eq("purchase_order_id", purchaseOrderId).eq("deleted_flag", 0)
                .orderByAsc("detail_id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshReceivedQuantity(Long purchaseOrderId, Long operatorId) {
        if (purchaseOrderId == null) {
            return;
        }
        List<MfgPurchaseOrderDetail> rows = listDetails(purchaseOrderId);
        if (rows.isEmpty()) {
            return;
        }
        // 已收数量只统计已确认（APPROVED）的采购入库单，未确认的收货不计入。
        Map<Long, BigDecimal> received = new HashMap<>();
        List<Map<String, Object>> sums = baseMapper.receivedQuantityByMaterial(purchaseOrderId);
        for (Map<String, Object> row : sums) {
            Object materialId = row.get("materialId");
            Object quantity = row.get("receivedQuantity");
            if (materialId == null) {
                continue;
            }
            BigDecimal value = quantity == null ? BigDecimal.ZERO : new BigDecimal(quantity.toString());
            received.merge(Long.valueOf(materialId.toString()), value, BigDecimal::add);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean allReceived = true;
        for (MfgPurchaseOrderDetail row : rows) {
            BigDecimal total = received.getOrDefault(row.getMaterialId(), BigDecimal.ZERO);
            if (row.getOrderQuantity() == null || total.compareTo(row.getOrderQuantity()) < 0) {
                allReceived = false;
            }
            if (row.getReceivedQuantity() != null && row.getReceivedQuantity().compareTo(total) == 0) {
                continue;
            }
            MfgPurchaseOrderDetail update = new MfgPurchaseOrderDetail();
            update.setDetailId(row.getDetailId());
            update.setReceivedQuantity(total);
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            update.setVersion(row.getVersion());
            detailMapper.updateById(update);
        }
        closeWhenReceived(purchaseOrderId, allReceived, operatorId, now);
    }

    /**
     * 全部明细收满后把采购订单置为已关闭，避免订单页一直停在「已下达」。
     *
     * <p>只在 RELEASED 状态下关闭；入库单作废导致数量回退时不自动改状态，
     * 是否需要重开由业务判断，避免系统自己反复开关单据。</p>
     */
    private void closeWhenReceived(Long purchaseOrderId, boolean allReceived, Long operatorId, LocalDateTime now) {
        if (!allReceived) {
            return;
        }
        MfgPurchaseOrder existing = getPurchaseOrderById(purchaseOrderId);
        if (existing == null || !BusinessStatus.Document.RELEASED.equals(existing.getStatus())) {
            return;
        }
        MfgPurchaseOrder update = new MfgPurchaseOrder();
        update.setPurchaseOrderId(purchaseOrderId);
        update.setStatus(BusinessStatus.Document.CLOSED);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        update.setVersion(existing.getVersion());
        if (baseMapper.updateById(update) == 0) {
            return;
        }
        insertStatusLog(purchaseOrderId, existing.getStatus(), BusinessStatus.Document.CLOSED,
                "PURCHASE_ORDER_RECEIVED", operatorId, "全部明细已收满，自动关闭", now);
        insertOperationLog(purchaseOrderId, "CLOSE_PURCHASE_ORDER", operatorId,
                "采购订单 " + existing.getPurchaseOrderNo() + " 已全部收满，自动关闭", now);
    }

    private QueryWrapper<MfgPurchaseOrder> buildQuery(String keyword, String status, Long supplierId) {
        QueryWrapper<MfgPurchaseOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(keyword)) {
            var aliases=numbers.aliasIds("PU",keyword.trim());
            wrapper.and(q -> q.like("purchase_order_no",keyword.trim())
                    .or(!aliases.isEmpty()).in(!aliases.isEmpty(),"purchase_order_id",aliases));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        if (supplierId != null) {
            wrapper.eq("supplier_id", supplierId);
        }
        return wrapper;
    }

    private List<MfgPurchaseOrderDetail> normalizeDetails(List<MfgPurchaseOrderDetail> details) {
        List<MfgPurchaseOrderDetail> rows = new ArrayList<>();
        if (details == null) {
            return rows;
        }
        // 同一张采购单不允许同一物料出现两行：已收数量是按「入库单头 + 物料」汇总的，
        // 两行同料会各自拿到同一个汇总值，已收数量就会凭空翻倍。
        Map<Long, Integer> materialRows = new HashMap<>();
        for (int index = 0; index < details.size(); index++) {
            MfgPurchaseOrderDetail detail = details.get(index);
            if (detail == null || detail.getMaterialId() == null) {
                continue;
            }
            if (detail.getOrderQuantity() == null || detail.getOrderQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw business(ResultCode.PARAM_ERROR, "采购明细数量必须大于 0");
            }
            requireEnabledMaterial(detail.getMaterialId());
            Integer previous = materialRows.putIfAbsent(detail.getMaterialId(), index + 1);
            if (previous != null) {
                throw business(ResultCode.PARAM_ERROR,
                        "第 " + previous + " 行和第 " + (index + 1) + " 行是同一个物料，请合并成一行后再保存");
            }
            if (detail.getReceivedQuantity() == null) {
                detail.setReceivedQuantity(BigDecimal.ZERO);
            }
            rows.add(detail);
        }
        if (rows.isEmpty()) {
            throw business(ResultCode.PARAM_ERROR, "采购订单至少要有一行明细");
        }
        return rows;
    }

    private void insertDetails(List<MfgPurchaseOrderDetail> rows, Long purchaseOrderId, Long operatorId,
                               LocalDateTime now) {
        for (MfgPurchaseOrderDetail row : rows) {
            row.setDetailId(null);
            row.setPurchaseOrderId(purchaseOrderId);
            row.setVersion(0);
            row.setCreatedBy(operatorId);
            row.setCreatedTime(now);
            row.setUpdatedBy(operatorId);
            row.setUpdatedTime(now);
            row.setDeletedFlag(0);
            detailMapper.insert(row);
        }
    }

    private BigDecimal sumQuantity(List<MfgPurchaseOrderDetail> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (MfgPurchaseOrderDetail row : rows) {
            total = total.add(row.getOrderQuantity());
        }
        return total;
    }

    private void requireEnabledSupplier(Long supplierId) {
        MdmSupplier supplier = supplierMapper.selectOne(new QueryWrapper<MdmSupplier>()
                .eq("supplier_id", supplierId).eq("deleted_flag", 0));
        if (supplier == null) {
            throw business(ResultCode.NOT_FOUND, "供应商不存在或已删除");
        }
        if (!BusinessStatus.Common.ENABLE.equals(supplier.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "供应商已停用，不能下单");
        }
    }

    private void requireEnabledMaterial(Long materialId) {
        MdmMaterial material = materialMapper.selectOne(new QueryWrapper<MdmMaterial>()
                .eq("material_id", materialId).eq("deleted_flag", 0));
        if (material == null) {
            throw business(ResultCode.NOT_FOUND, "物料不存在或已删除");
        }
        if (!BusinessStatus.Common.ENABLE.equals(material.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "物料已停用，不能采购");
        }
    }

    private MfgPurchaseOrder requireDraft(Long purchaseOrderId) {
        MfgPurchaseOrder existing = getPurchaseOrderById(purchaseOrderId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "采购订单不存在或已删除");
        }
        if (!BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有草稿采购订单可以修改或删除");
        }
        return existing;
    }

    private void softDeleteDetails(Long purchaseOrderId, Long operatorId, LocalDateTime now) {
        List<MfgPurchaseOrderDetail> rows = listDetails(purchaseOrderId);
        for (MfgPurchaseOrderDetail row : rows) {
            MfgPurchaseOrderDetail update = new MfgPurchaseOrderDetail();
            update.setDetailId(row.getDetailId());
            update.setDeletedFlag(1);
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            update.setVersion(row.getVersion());
            detailMapper.updateById(update);
        }
    }

    private void requireOperator(Long operatorId) {
        if (operatorId == null) {
            throw business(ResultCode.PARAM_ERROR, "当前登录用户不能为空");
        }
    }

    private void insertStatusLog(Long bizId, String oldStatus, String newStatus, String reason,
                                 Long operatorId, String remark, LocalDateTime now) {
        SysStatusChangeLog log = new SysStatusChangeLog();
        log.setBizType("MFG_PURCHASE_ORDER");
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
        sysStatusChangeLogMapper.insert(log);
    }

    private void insertOperationLog(Long bizId, String type, Long operatorId, String remark, LocalDateTime now) {
        SysOperationLog log = new SysOperationLog();
        log.setUserId(operatorId);
        log.setOperationType(type);
        log.setModuleCode("mfg_purchase_order");
        log.setBizId(bizId);
        log.setOperationTime(now);
        log.setRemark(remark);
        log.setCreatedBy(operatorId);
        log.setCreatedTime(now);
        log.setUpdatedBy(operatorId);
        log.setUpdatedTime(now);
        log.setDeletedFlag(0);
        sysOperationLogMapper.insert(log);
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }
}
