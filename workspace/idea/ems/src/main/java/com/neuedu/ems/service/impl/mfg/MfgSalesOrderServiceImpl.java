package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgSalesOrder;
import com.neuedu.ems.entity.mfg.MfgSalesOrderDetail;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgSalesOrderDetailMapper;
import com.neuedu.ems.mapper.mfg.MfgSalesOrderMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.service.mdm.ProductQuantityService;
import com.neuedu.ems.service.mfg.MfgProductionOrderService;
import com.neuedu.ems.service.mfg.MfgSalesOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 销售订单 Service 实现。
 *
 * <p>订单总数量由明细汇总，不接受前端直接传值；已发货数量由销售出库回写，
 * 保证「客户要多少、排产多少、发了多少」三个口径都落在同一份明细上。</p>
 */
@Service
public class MfgSalesOrderServiceImpl extends ServiceImpl<MfgSalesOrderMapper, MfgSalesOrder>
        implements MfgSalesOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.neuedu.ems.service.sys.BusinessNumberService numbers;


    private static final DateTimeFormatter ORDER_NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String SOURCE_TYPE_SALES_ORDER = "SALES_ORDER";

    @Autowired
    private MfgSalesOrderDetailMapper detailMapper;

    @Autowired
    private MfgProductionOrderService productionOrderService;

    @Autowired
    private ProductQuantityService productQuantities;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Override
    public Page<MfgSalesOrder> pageSalesOrders(Integer current, Integer size, String keyword, String status,
                                               Long customerId) {
        Page<MfgSalesOrder> page = new Page<>(current == null ? 1 : current, size == null ? 10 : size);
        return baseMapper.selectPage(page, buildQuery(keyword, status, customerId));
    }

    @Override
    public List<MfgSalesOrder> listSalesOrders(String keyword, String status, Long customerId) {
        return baseMapper.selectList(buildQuery(keyword, status, customerId)
                .orderByDesc("sales_order_id"));
    }

    @Override
    public MfgSalesOrder getSalesOrderById(Long salesOrderId) {
        if (salesOrderId == null) {
            return null;
        }
        return baseMapper.selectOne(new QueryWrapper<MfgSalesOrder>()
                .eq("sales_order_id", salesOrderId).eq("deleted_flag", 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveSalesOrderFull(MfgSalesOrder salesOrder, List<MfgSalesOrderDetail> details, Long operatorId) {
        requireOperator(operatorId);
        if (salesOrder == null || salesOrder.getCustomerId() == null) {
            throw business(ResultCode.PARAM_ERROR, "客户不能为空");
        }
        List<MfgSalesOrderDetail> rows = normalizeDetails(details);
        LocalDateTime now = LocalDateTime.now();
        salesOrder.setSalesOrderId(null);
        salesOrder.setSalesOrderNo(numbers.next("SO",salesOrder.getCustomerId()));
        salesOrder.setStatus(BusinessStatus.Document.DRAFT);
        salesOrder.setTotalQuantity(sumQuantity(rows));
        salesOrder.setVersion(0);
        salesOrder.setCreatedBy(operatorId);
        salesOrder.setCreatedTime(now);
        salesOrder.setUpdatedBy(operatorId);
        salesOrder.setUpdatedTime(now);
        salesOrder.setDeletedFlag(0);
        if (baseMapper.insert(salesOrder) == 0 || salesOrder.getSalesOrderId() == null) {
            throw business(ResultCode.FAILED, "销售订单保存失败");
        }
        for (MfgSalesOrderDetail row : rows) {
            row.setSalesOrderId(salesOrder.getSalesOrderId());
            row.setVersion(0);
            row.setCreatedBy(operatorId);
            row.setCreatedTime(now);
            row.setUpdatedBy(operatorId);
            row.setUpdatedTime(now);
            row.setDeletedFlag(0);
            detailMapper.insert(row);
        }
        insertOperationLog("mfg_sales_order", salesOrder.getSalesOrderId(), "CREATE_SALES_ORDER", operatorId,
                "新建销售订单 " + salesOrder.getSalesOrderNo(), now);
        return salesOrder.getSalesOrderId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSalesOrderFull(MfgSalesOrder salesOrder, List<MfgSalesOrderDetail> details,
                                        Long operatorId) {
        requireOperator(operatorId);
        if (salesOrder == null || salesOrder.getSalesOrderId() == null) {
            throw business(ResultCode.PARAM_ERROR, "销售订单 ID 不能为空");
        }
        MfgSalesOrder existing = requireDraft(salesOrder.getSalesOrderId());
        List<MfgSalesOrderDetail> rows = normalizeDetails(details);
        LocalDateTime now = LocalDateTime.now();
        salesOrder.setStatus(null);
        salesOrder.setSalesOrderNo(null);
        salesOrder.setTotalQuantity(sumQuantity(rows));
        salesOrder.setDeletedFlag(null);
        salesOrder.setUpdatedBy(operatorId);
        salesOrder.setUpdatedTime(now);
        if (salesOrder.getVersion() == null) {
            salesOrder.setVersion(existing.getVersion());
        }
        if (baseMapper.updateById(salesOrder) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "销售订单已被其他用户修改，请刷新后重试");
        }
        softDeleteDetails(existing.getSalesOrderId(), operatorId, now);
        for (MfgSalesOrderDetail row : rows) {
            row.setDetailId(null);
            row.setSalesOrderId(existing.getSalesOrderId());
            row.setVersion(0);
            row.setCreatedBy(operatorId);
            row.setCreatedTime(now);
            row.setUpdatedBy(operatorId);
            row.setUpdatedTime(now);
            row.setDeletedFlag(0);
            detailMapper.insert(row);
        }
        insertOperationLog("mfg_sales_order", existing.getSalesOrderId(), "UPDATE_SALES_ORDER", operatorId,
                "修改销售订单 " + existing.getSalesOrderNo(), now);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeSalesOrderById(Long salesOrderId, Long operatorId) {
        requireOperator(operatorId);
        MfgSalesOrder existing = requireDraft(salesOrderId);
        LocalDateTime now = LocalDateTime.now();
        MfgSalesOrder update = new MfgSalesOrder();
        update.setSalesOrderId(existing.getSalesOrderId());
        update.setDeletedFlag(1);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        update.setVersion(existing.getVersion());
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "销售订单已被其他用户修改，请刷新后重试");
        }
        softDeleteDetails(existing.getSalesOrderId(), operatorId, now);
        insertOperationLog("mfg_sales_order", existing.getSalesOrderId(), "DELETE_SALES_ORDER", operatorId,
                "删除销售订单 " + existing.getSalesOrderNo(), now);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> confirmSalesOrder(Long salesOrderId, Long operatorId, String remark) {
        requireOperator(operatorId);
        MfgSalesOrder existing = getSalesOrderById(salesOrderId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "销售订单不存在或已删除");
        }
        if (!BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有草稿销售订单可以确认");
        }
        if (existing.getDeliveryDate() == null) {
            throw business(ResultCode.PARAM_ERROR, "确认前必须填写交付日期");
        }
        if (existing.getDeliveryDate().isBefore(LocalDate.now())) {
            throw business(ResultCode.BUSINESS_ERROR,
                    "交付日期 " + existing.getDeliveryDate() + " 已早于当前日期，请先调整交期");
        }
        List<MfgSalesOrderDetail> rows = listDetails(salesOrderId);
        if (rows.isEmpty()) {
            throw business(ResultCode.BUSINESS_ERROR, "销售订单没有明细，不能确认");
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> generated = new ArrayList<>();
        for (MfgSalesOrderDetail row : rows) {
            if (row.getProductId() == null || row.getOrderQuantity() == null
                    || row.getOrderQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw business(ResultCode.PARAM_ERROR, "销售明细的产品和正数数量都必须填写");
            }
            MfgProductionOrder order = new MfgProductionOrder();
            order.setCustomerId(existing.getCustomerId());
            order.setProductId(row.getProductId());
            order.setOrderQuantity(row.getOrderQuantity());
            order.setDeliveryDate(existing.getDeliveryDate());
            order.setSourceType(SOURCE_TYPE_SALES_ORDER);
            order.setSourceSalesOrderId(existing.getSalesOrderId());
            order.setSourceSalesOrderDetailId(row.getDetailId());
            order.setRemark("由销售订单 " + existing.getSalesOrderNo() + " 确认后生成");
            order.setCreatedBy(operatorId);
            order.setCreatedTime(now);
            order.setUpdatedBy(operatorId);
            order.setUpdatedTime(now);
            if (!productionOrderService.saveOrder(order)) {
                throw business(ResultCode.FAILED, "销售明细生成生产订单失败");
            }
            generated.add(order.getProductionOrderId());
        }
        MfgSalesOrder update = new MfgSalesOrder();
        update.setSalesOrderId(existing.getSalesOrderId());
        update.setStatus(BusinessStatus.Document.SUBMITTED);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "销售订单已被其他用户修改，请刷新后重试");
        }
        insertStatusLog(existing.getSalesOrderId(), existing.getStatus(), BusinessStatus.Document.SUBMITTED,
                "CONFIRM_SALES_ORDER", operatorId, remark, now);
        insertOperationLog("mfg_sales_order", existing.getSalesOrderId(), "CONFIRM_SALES_ORDER", operatorId,
                "确认销售订单 " + existing.getSalesOrderNo() + "，生成 " + generated.size() + " 张生产订单", now);
        return generated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawSalesOrder(Long salesOrderId, Long operatorId, String remark) {
        requireOperator(operatorId);
        MfgSalesOrder existing = getSalesOrderById(salesOrderId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "销售订单不存在或已删除");
        }
        if (!BusinessStatus.Document.SUBMITTED.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有已确认的销售订单可以撤回");
        }
        List<MfgProductionOrder> orders = productionOrderService.list(new QueryWrapper<MfgProductionOrder>()
                .eq("source_sales_order_id", salesOrderId).eq("deleted_flag", 0));
        for (MfgProductionOrder order : orders) {
            if (!BusinessStatus.Document.DRAFT.equals(order.getStatus())) {
                throw business(ResultCode.BUSINESS_ERROR,
                        "生产订单 " + order.getProductionOrderNo() + " 已下达或已执行，不能撤回销售订单");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (MfgProductionOrder order : orders) {
            productionOrderService.removeOrderById(order.getProductionOrderId());
        }
        MfgSalesOrder update = new MfgSalesOrder();
        update.setSalesOrderId(existing.getSalesOrderId());
        update.setStatus(BusinessStatus.Document.DRAFT);
        update.setVersion(existing.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw business(ResultCode.DATA_CONFLICT, "销售订单已被其他用户修改，请刷新后重试");
        }
        insertStatusLog(existing.getSalesOrderId(), existing.getStatus(), BusinessStatus.Document.DRAFT,
                "WITHDRAW_SALES_ORDER", operatorId, remark, now);
        insertOperationLog("mfg_sales_order", existing.getSalesOrderId(), "WITHDRAW_SALES_ORDER", operatorId,
                "撤回销售订单 " + existing.getSalesOrderNo() + "，同步撤回 " + orders.size() + " 张生产订单", now);
    }

    @Override
    public List<MfgSalesOrderDetail> listDetails(Long salesOrderId) {
        if (salesOrderId == null) {
            return List.of();
        }
        return detailMapper.selectList(new QueryWrapper<MfgSalesOrderDetail>()
                .eq("sales_order_id", salesOrderId).eq("deleted_flag", 0)
                .orderByAsc("detail_id"));
    }

    @Override
    public void refreshDeliveredQuantity(Long salesOrderId, Long operatorId) {
        if (salesOrderId == null) {
            return;
        }
        // 已发货数量按销售出库明细汇总，不落冗余统计表，避免出库单作废后口径不一致。
        List<MfgSalesOrderDetail> rows = listDetails(salesOrderId);
        if (rows.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean allDelivered = true;
        for (MfgSalesOrderDetail row : rows) {
            BigDecimal delivered = baseMapper.deliveredQuantityOfDetail(row.getDetailId());
            if (delivered == null) {
                delivered = BigDecimal.ZERO;
            }
            if (row.getOrderQuantity() == null || delivered.compareTo(row.getOrderQuantity()) < 0) {
                allDelivered = false;
            }
            if (row.getDeliveredQuantity() != null && row.getDeliveredQuantity().compareTo(delivered) == 0) {
                continue;
            }
            MfgSalesOrderDetail update = new MfgSalesOrderDetail();
            update.setDetailId(row.getDetailId());
            update.setDeliveredQuantity(delivered);
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            update.setVersion(row.getVersion());
            detailMapper.updateById(update);
        }
        closeWhenDelivered(salesOrderId, allDelivered, operatorId, now);
    }

    /**
     * 全部明细发完后把销售订单置为已关闭，避免订单页一直显示「已确认」。
     *
     * <p>只在 SUBMITTED 状态下关闭；退回到未发完时不自动改状态，
     * 因为出库单作废属于事后修正，订单是否需要重开应由业务判断。</p>
     */
    private void closeWhenDelivered(Long salesOrderId, boolean allDelivered, Long operatorId, LocalDateTime now) {
        if (!allDelivered) {
            return;
        }
        MfgSalesOrder existing = getSalesOrderById(salesOrderId);
        if (existing == null || !BusinessStatus.Document.SUBMITTED.equals(existing.getStatus())) {
            return;
        }
        MfgSalesOrder update = new MfgSalesOrder();
        update.setSalesOrderId(salesOrderId);
        update.setStatus(BusinessStatus.Document.CLOSED);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        update.setVersion(existing.getVersion());
        if (baseMapper.updateById(update) == 0) {
            return;
        }
        insertStatusLog(salesOrderId, existing.getStatus(), BusinessStatus.Document.CLOSED,
                "SALES_ORDER_DELIVERED", operatorId, "全部明细已发完，自动关闭", now);
        insertOperationLog("mfg_sales_order", salesOrderId, "CLOSE_SALES_ORDER", operatorId,
                "销售订单 " + existing.getSalesOrderNo() + " 已全部发完，自动关闭", now);
    }

    private QueryWrapper<MfgSalesOrder> buildQuery(String keyword, String status, Long customerId) {
        QueryWrapper<MfgSalesOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(keyword)) {
            var aliases=numbers.aliasIds("SO",keyword.trim());
            wrapper.and(q -> q.like("sales_order_no",keyword.trim())
                    .or(!aliases.isEmpty()).in(!aliases.isEmpty(),"sales_order_id",aliases));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        if (customerId != null) {
            wrapper.eq("customer_id", customerId);
        }
        return wrapper;
    }

    private List<MfgSalesOrderDetail> normalizeDetails(List<MfgSalesOrderDetail> details) {
        List<MfgSalesOrderDetail> rows = new ArrayList<>();
        if (details == null) {
            return rows;
        }
        for (MfgSalesOrderDetail detail : details) {
            if (detail == null || detail.getProductId() == null) {
                continue;
            }
            if (detail.getOrderQuantity() == null || detail.getOrderQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw business(ResultCode.PARAM_ERROR, "销售明细数量必须大于 0");
            }
            productQuantities.validate(detail.getProductId(), detail.getOrderQuantity());
            if (detail.getDeliveredQuantity() == null) {
                detail.setDeliveredQuantity(BigDecimal.ZERO);
            }
            rows.add(detail);
        }
        if (rows.isEmpty()) {
            throw business(ResultCode.PARAM_ERROR, "销售订单至少要有一行明细");
        }
        return rows;
    }

    private BigDecimal sumQuantity(List<MfgSalesOrderDetail> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (MfgSalesOrderDetail row : rows) {
            total = total.add(row.getOrderQuantity());
        }
        return total;
    }

    private MfgSalesOrder requireDraft(Long salesOrderId) {
        MfgSalesOrder existing = getSalesOrderById(salesOrderId);
        if (existing == null) {
            throw business(ResultCode.NOT_FOUND, "销售订单不存在或已删除");
        }
        if (!BusinessStatus.Document.DRAFT.equals(existing.getStatus())) {
            throw business(ResultCode.BUSINESS_ERROR, "只有草稿销售订单可以修改或删除");
        }
        return existing;
    }

    private void softDeleteDetails(Long salesOrderId, Long operatorId, LocalDateTime now) {
        List<MfgSalesOrderDetail> rows = listDetails(salesOrderId);
        for (MfgSalesOrderDetail row : rows) {
            MfgSalesOrderDetail update = new MfgSalesOrderDetail();
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
        log.setBizType("MFG_SALES_ORDER");
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

    private void insertOperationLog(String moduleCode, Long bizId, String type,
                                    Long operatorId, String remark, LocalDateTime now) {
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
        sysOperationLogMapper.insert(log);
    }

    private BusinessException business(ResultCode code, String message) {
        return new BusinessException(code.getCode(), message);
    }
}
