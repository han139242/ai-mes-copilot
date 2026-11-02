package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.*;
import com.neuedu.ems.entity.qms.*;
import com.neuedu.ems.entity.trace.TraceProductLot;
import com.neuedu.ems.entity.wms.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.*;
import com.neuedu.ems.mapper.qms.*;
import com.neuedu.ems.mapper.trace.TraceProductLotMapper;
import com.neuedu.ems.mapper.wms.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 同线组件直接交接：转移已有线边产出占用，不创建仓库入出库单，不推测或补造实物产出。 */
@Service
@RequiredArgsConstructor
public class ComponentHandoverService {
    public static final String MOVEMENT = "COMPONENT_HANDOVER";
    private final MfgWorkOrderMapper orders;
    private final MfgProductionPlanMapper plans;
    private final MfgWorkOrderMaterialMapper requirements;
    private final MfgSupplySourceMapper sources;
    private final com.neuedu.ems.mapper.mdm.MdmProductMapper products;
    private final TraceProductLotMapper lots;
    private final QmsInspectionOrderMapper inspections;
    private final QmsNonconformingOrderMapper dispositions;
    private final WmsMaterialBatchMapper batches;
    private final WmsInventoryMapper inventories;
    private final WmsLocationMapper locations;
    private final WmsWarehouseMapper warehouses;
    private final WmsStockMovementMapper movements;
    private final WmsInboundOrderMapper inboundOrders;
    private final WmsInboundDetailMapper inboundDetails;
    private final org.springframework.beans.factory.ObjectProvider<MfgDispatchOrderService> dispatches;

    private static final Set<String> HANDOVER_READY_STATUS = Set.of("DRAFT", "APPROVED", "RELEASED", "RUNNING");
    private static final Set<String> CLOSED_OR_CANCELLED = Set.of("CLOSED", "CANCELLED");
    private static final Set<String> LOCATION_USABLE_STATUS = Set.of("IDLE", "OCCUPIED", "ENABLE");

    public record Released(Long workOrderId, Long operatorId) {}

    /** 事件在质量事务提交后执行，避免质量事务持有批次锁再反向等待上级工单。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Long afterRelease(Long sourceId, Long actor) {
        MfgWorkOrder source = orders.selectById(sourceId);
        if (source == null || source.getParentWorkOrderId() == null) return null;
        synchronize(source.getParentWorkOrderId(), actor);
        dispatches.getObject().refreshMaterialReadiness(source.getParentWorkOrderId(), actor);
        return source.getParentWorkOrderId();
    }

    @Transactional(rollbackFor = Exception.class)
    public BigDecimal synchronize(Long parentId, Long actor) {
        if (parentId == null || actor == null) throw invalid("交接必须指定接收工单和操作人");
        MfgWorkOrder parent = orders.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, parentId).eq(MfgWorkOrder::getDeletedFlag, 0).last("FOR UPDATE"));
        if (parent == null) throw invalid("接收工单不存在");
        if (!HANDOVER_READY_STATUS.contains(parent.getStatus())) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (MfgWorkOrderMaterial requirement : requirements.selectList(new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                .eq(MfgWorkOrderMaterial::getWorkOrderId, parentId).eq(MfgWorkOrderMaterial::getDeletedFlag, 0)
                .orderByAsc(MfgWorkOrderMaterial::getRequirementId).last("FOR UPDATE"))) {
            total = total.add(synchronizeRequirement(parent, requirement, actor));
        }
        return total;
    }

    /** 逐条需求把可用的同线产出发货批次交接给上级工单。 */
    private BigDecimal synchronizeRequirement(MfgWorkOrder parent, MfgWorkOrderMaterial requirement, Long actor) {
        BigDecimal total = BigDecimal.ZERO;
        for (var source : directSources(parent, requirement).entrySet()) {
            BigDecimal outstanding = outstandingOf(requirement);
            if (outstanding.signum() <= 0) break;
            BigDecimal assigned = source.getValue().subtract(delivered(source.getKey(), parent.getWorkOrderId(), requirement.getMaterialId()));
            if (assigned.signum() <= 0) continue;
            total = total.add(handoverLots(source.getKey(), requirement, assigned, actor));
        }
        return total;
    }

    /** 从某来源工单已放行的产品批次里逐批取用，直到份额用完。 */
    private BigDecimal handoverLots(Long sourceWorkOrderId, MfgWorkOrderMaterial requirement, BigDecimal assigned, Long actor) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal quota = assigned;
        for (TraceProductLot lot : acceptedLotsOf(sourceWorkOrderId)) {
            BigDecimal accepted = acceptedQuantity(lot);
            if (accepted.signum() <= 0) continue;
            BigDecimal remaining = outstandingOf(requirement);
            BigDecimal take = transfer(lot, requirement, remaining.min(quota), accepted, actor);
            quota = quota.subtract(take);
            total = total.add(take);
        }
        return total;
    }

    private List<TraceProductLot> acceptedLotsOf(Long sourceWorkOrderId) {
        return lots.selectList(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getWorkOrderId, sourceWorkOrderId).eq(TraceProductLot::getDeletedFlag, 0)
                .eq(TraceProductLot::getStatus, "PASS").orderByAsc(TraceProductLot::getProductLotId).last("FOR UPDATE"));
    }

    private static BigDecimal outstandingOf(MfgWorkOrderMaterial requirement) {
        return zero(requirement.getRequiredQuantity()).subtract(zero(requirement.getIssuedQuantity()));
    }

    /** 只有明确关联到本需求、父子关系及产线一致的生产来源，才自动衔接；跨线继续走仓库。 */
    public Map<Long, BigDecimal> directSources(MfgWorkOrder parent, MfgWorkOrderMaterial requirement) {
        Map<Long, BigDecimal> candidates = new LinkedHashMap<>();
        for (MfgSupplySource source : sources.selectList(new LambdaQueryWrapper<MfgSupplySource>()
                .eq(MfgSupplySource::getRequirementId, requirement.getRequirementId())
                .eq(MfgSupplySource::getDeletedFlag, 0).ne(MfgSupplySource::getStatus, "CLOSED")
                .eq(MfgSupplySource::getSupplyType, "COMPONENT_WORK_ORDER").orderByAsc(MfgSupplySource::getSourceId))) {
            if (source.getWorkOrderId() != null) candidates.merge(source.getWorkOrderId(), zero(source.getPlannedQuantity()), BigDecimal::add);
        }
        if (candidates.isEmpty() && requirement.getSourceWorkOrderId() != null) {
            MfgWorkOrder source = orders.selectById(requirement.getSourceWorkOrderId());
            if (source != null) candidates.put(source.getWorkOrderId(), zero(source.getPlannedQuantity()));
        }
        var parentPlan = plans.selectById(parent.getPlanId());
        candidates.entrySet().removeIf(entry -> !usableDirectSource(entry.getKey(), parentPlan, requirement));
        candidates.replaceAll((id, amount) -> amount.subtract(warehousedForSource(id)).max(BigDecimal.ZERO));
        return candidates;
    }

    /** 自动衔接的四类前提：来源工单有效、是组件工单、对应该物料、父子关系与产线一致。 */
    private boolean usableDirectSource(Long sourceId, MfgProductionPlan parentPlan, MfgWorkOrderMaterial requirement) {
        MfgWorkOrder source = orders.selectById(sourceId);
        boolean usable = source != null && Objects.equals(source.getDeletedFlag(), 0)
                && !CLOSED_OR_CANCELLED.contains(String.valueOf(source.getStatus()))
                && "COMPONENT".equals(source.getWorkOrderType())
                && Objects.equals(source.getParentWorkOrderId(), requirement.getWorkOrderId())
                && matchesSourceRequirement(source, requirement)
                && matchesMaterial(source, requirement.getMaterialId());
        return usable && sharesSameLine(source, parentPlan);
    }

    /** 来源工单若显式声明了它的用料需求，就必须正是当前这条需求，避免串料。 */
    private static boolean matchesSourceRequirement(MfgWorkOrder source, MfgWorkOrderMaterial requirement) {
        return source.getSourceRequirementId() == null
                || Objects.equals(source.getSourceRequirementId(), requirement.getRequirementId());
    }

    private boolean matchesMaterial(MfgWorkOrder source, Long materialId) {
        var product = products.selectById(source.getProductId());
        boolean productValid = product != null && Objects.equals(product.getDeletedFlag(), 0);
        return productValid && Objects.equals(product.getMaterialId(), materialId);
    }

    private boolean sharesSameLine(MfgWorkOrder source, MfgProductionPlan parentPlan) {
        var plan = plans.selectById(source.getPlanId());
        boolean sourcePlanValid = plan != null && Objects.equals(plan.getDeletedFlag(), 0);
        boolean parentPlanValid = parentPlan != null && Objects.equals(parentPlan.getDeletedFlag(), 0);
        boolean parentHasLine = parentPlanValid && parentPlan.getLineId() != null;
        return sourcePlanValid && parentPlanValid && parentHasLine
                && Objects.equals(plan.getLineId(), parentPlan.getLineId());
    }

    private BigDecimal warehousedForSource(Long sourceId) {
        List<Long> ids = lots.selectList(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getWorkOrderId, sourceId).eq(TraceProductLot::getDeletedFlag, 0))
                .stream().map(TraceProductLot::getProductLotId).toList();
        if (ids.isEmpty()) return BigDecimal.ZERO;
        List<Long> inbounds = inboundOrders.selectList(new LambdaQueryWrapper<WmsInboundOrder>()
                .eq(WmsInboundOrder::getSourceOrderType, "PRODUCT_LOT").in(WmsInboundOrder::getSourceOrderId, ids)
                .in(WmsInboundOrder::getAuditStatus, "SUBMITTED", "APPROVED").eq(WmsInboundOrder::getDeletedFlag, 0))
                .stream().map(WmsInboundOrder::getInboundId).toList();
        return inbounds.isEmpty() ? BigDecimal.ZERO : inboundDetails.selectList(new LambdaQueryWrapper<WmsInboundDetail>()
                .in(WmsInboundDetail::getInboundId, inbounds).eq(WmsInboundDetail::getDeletedFlag, 0))
                .stream().map(d -> zero(d.getInboundQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 领料只取仓库供给份额。混合来源时分别扣已交接与已领用数量，不能重复领组件。 */
    public BigDecimal warehouseOutstanding(MfgWorkOrder parent, MfgWorkOrderMaterial requirement) {
        BigDecimal direct = directSources(parent, requirement).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .min(zero(requirement.getRequiredQuantity()));
        BigDecimal arrived = deliveredToParent(parent.getWorkOrderId(), requirement.getMaterialId());
        BigDecimal outstanding = zero(requirement.getRequiredQuantity()).subtract(zero(requirement.getIssuedQuantity())).max(BigDecimal.ZERO);
        return outstanding.subtract(direct.subtract(arrived).max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }

    public BigDecimal deliveredLot(Long lotId) {
        return sumHandoverQuantity(handoverMovements(List.of(lotId)));
    }

    public boolean hasHandover(Long workOrderId) {
        return movements.selectCount(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getMovementType, MOVEMENT).eq(WmsStockMovement::getWorkOrderId, workOrderId)
                .eq(WmsStockMovement::getDeletedFlag, 0)) > 0;
    }

    private BigDecimal deliveredToParent(Long parentId, Long materialId) {
        return sumHandoverQuantity(new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getMovementType, MOVEMENT).eq(WmsStockMovement::getDirection, "IN")
                .eq(WmsStockMovement::getWorkOrderId, parentId).eq(WmsStockMovement::getMaterialId, materialId)
                .eq(WmsStockMovement::getDeletedFlag, 0));
    }

    private BigDecimal delivered(Long sourceId, Long parentId, Long materialId) {
        List<Long> lotIds = lots.selectList(new LambdaQueryWrapper<TraceProductLot>()
                .eq(TraceProductLot::getWorkOrderId, sourceId).eq(TraceProductLot::getDeletedFlag, 0))
                .stream().map(TraceProductLot::getProductLotId).toList();
        if (lotIds.isEmpty()) return BigDecimal.ZERO;
        return sumHandoverQuantity(handoverMovements(lotIds)
                .eq(WmsStockMovement::getWorkOrderId, parentId).eq(WmsStockMovement::getMaterialId, materialId));
    }

    /** 交接流水的基础条件：本模块的入方向、来源是产品批次，并限定在给定批次范围内。 */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WmsStockMovement> handoverMovements(List<Long> lotIds) {
        return new LambdaQueryWrapper<WmsStockMovement>()
                .eq(WmsStockMovement::getMovementType, MOVEMENT).eq(WmsStockMovement::getDirection, "IN")
                .eq(WmsStockMovement::getSourceOrderType, "PRODUCT_LOT")
                .in(WmsStockMovement::getSourceOrderId, lotIds).eq(WmsStockMovement::getDeletedFlag, 0);
    }

    /** 把一批交接流水按数量求和，没有流水时返回 0。 */
    private BigDecimal sumHandoverQuantity(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WmsStockMovement> query) {
        return movements.selectList(query).stream().map(m -> zero(m.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal acceptedQuantity(TraceProductLot lot) {
        QmsInspectionOrder inspection = latestFinalInspection(lot.getProductLotId());
        if (inspection == null) return BigDecimal.ZERO;
        return resolvedByInspection(inspection) ? zero(lot.getQualifiedQuantity()).min(zero(inspection.getQualifiedQuantity())) : BigDecimal.ZERO;
    }

    /** 取该产品批次最近一次已完成的终检单，作为可用数量的判定依据。 */
    private QmsInspectionOrder latestFinalInspection(Long productLotId) {
        return inspections.selectOne(new LambdaQueryWrapper<QmsInspectionOrder>()
                .eq(QmsInspectionOrder::getProductLotId, productLotId).eq(QmsInspectionOrder::getInspectionType, "FINAL")
                .eq(QmsInspectionOrder::getStatus, "FINISHED").eq(QmsInspectionOrder::getDeletedFlag, 0)
                .orderByDesc(QmsInspectionOrder::getInspectionId).last("LIMIT 1"));
    }

    /** 判定通过直接算解决；判定不通过时，只有已审批关闭且处置方式明确（返工/报废/降级）才算解决。 */
    private boolean resolvedByInspection(QmsInspectionOrder inspection) {
        if ("PASS".equals(inspection.getInspectionResult())) return true;
        if (!"FAIL".equals(inspection.getInspectionResult())) return false;
        return dispositions.selectCount(new LambdaQueryWrapper<QmsNonconformingOrder>()
                .eq(QmsNonconformingOrder::getInspectionId, inspection.getInspectionId())
                .eq(QmsNonconformingOrder::getApprovalStatus, "APPROVED").eq(QmsNonconformingOrder::getStatus, "CLOSED")
                .in(QmsNonconformingOrder::getDispositionType, "REWORK", "SCRAP", "DOWNGRADE")
                .eq(QmsNonconformingOrder::getDeletedFlag, 0)) > 0;
    }

    private BigDecimal transfer(TraceProductLot lot, MfgWorkOrderMaterial requirement, BigDecimal wanted, BigDecimal accepted, Long actor) {
        if (wanted.signum() <= 0) return BigDecimal.ZERO;
        // 待确认入库也占用数量；不抢走用户已经提交给仓库的批次。
        List<Long> inboundIds = inboundOrders.selectList(new LambdaQueryWrapper<WmsInboundOrder>()
                .eq(WmsInboundOrder::getSourceOrderType, "PRODUCT_LOT").eq(WmsInboundOrder::getSourceOrderId, lot.getProductLotId())
                .in(WmsInboundOrder::getAuditStatus, "SUBMITTED", "APPROVED").eq(WmsInboundOrder::getDeletedFlag, 0).last("FOR UPDATE"))
                .stream().map(WmsInboundOrder::getInboundId).toList();
        BigDecimal warehoused = inboundIds.isEmpty() ? BigDecimal.ZERO : inboundDetails.selectList(new LambdaQueryWrapper<WmsInboundDetail>()
                .in(WmsInboundDetail::getInboundId, inboundIds).eq(WmsInboundDetail::getDeletedFlag, 0))
                .stream().map(d -> zero(d.getInboundQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = accepted.subtract(deliveredLot(lot.getProductLotId())).subtract(warehoused)
                .max(BigDecimal.ZERO).min(wanted);
        if (remaining.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal done = BigDecimal.ZERO;
        for (WmsMaterialBatch batch : batches.selectList(new LambdaQueryWrapper<WmsMaterialBatch>()
                .eq(WmsMaterialBatch::getSourceProductLotId, lot.getProductLotId())
                .eq(WmsMaterialBatch::getMaterialId, requirement.getMaterialId()).eq(WmsMaterialBatch::getDeletedFlag, 0)
                .eq(WmsMaterialBatch::getStatus, "AVAILABLE").last("FOR UPDATE"))) {
            for (WmsInventory stock : inventories.selectList(new LambdaQueryWrapper<WmsInventory>()
                    .eq(WmsInventory::getWorkOrderId, lot.getWorkOrderId()).eq(WmsInventory::getBatchId, batch.getBatchId())
                    .eq(WmsInventory::getMaterialId, requirement.getMaterialId()).eq(WmsInventory::getDeletedFlag, 0)
                    .gt(WmsInventory::getLockedQuantity, BigDecimal.ZERO).orderByAsc(WmsInventory::getInventoryId).last("FOR UPDATE"))) {
                var receiver = orders.selectById(requirement.getWorkOrderId());
                var plan = plans.selectById(receiver.getPlanId());
                if (!usableLineSideStock(stock, plan)) continue;
                BigDecimal take = remaining.min(zero(stock.getLockedQuantity()));
                if (take.signum() <= 0) break;
                markBatchQuality(batch, actor);
                move(stock, requirement, lot, take, actor);
                done = done.add(take);
                remaining = remaining.subtract(take);
            }
        }
        return done;
    }

    /** 只有线边仓、合格库位、且库位与接收工单同线的库存，才允许同线交接。 */
    private boolean usableLineSideStock(WmsInventory stock, MfgProductionPlan plan) {
        WmsWarehouse warehouse = warehouses.selectById(stock.getWarehouseId());
        WmsLocation location = locations.selectById(stock.getLocationId());
        if (!enabledLineSideWarehouse(warehouse)) return false;
        if (!usableLocation(location, stock)) return false;
        return location.getLineId() == null || Objects.equals(location.getLineId(), plan.getLineId());
    }

    private static boolean enabledLineSideWarehouse(WmsWarehouse warehouse) {
        return warehouse != null
                && Objects.equals(warehouse.getDeletedFlag(), 0)
                && "ENABLE".equals(warehouse.getStatus())
                && "LINE_SIDE".equals(warehouse.getWarehouseType());
    }

    private static boolean usableLocation(WmsLocation location, WmsInventory stock) {
        boolean usable = location != null && Objects.equals(location.getDeletedFlag(), 0)
                && "NORMAL".equals(location.getLocationType())
                && LOCATION_USABLE_STATUS.contains(String.valueOf(location.getStatus()));
        return usable && Objects.equals(location.getWarehouseId(), stock.getWarehouseId());
    }

    /** 批次本身已是可用批，这里只是把来源批次的质量状态补齐为 PASS。 */
    private void markBatchQuality(WmsMaterialBatch batch, Long actor) {
        if ("PASS".equals(batch.getQualityStatus())) return;
        batch.setQualityStatus("PASS");
        batch.setUpdatedBy(actor);
        batch.setUpdatedTime(LocalDateTime.now());
        if (batches.updateById(batch) != 1) throw invalid("组件批次状态已变化，请重试");
    }

    private void move(WmsInventory from, MfgWorkOrderMaterial requirement, TraceProductLot lot, BigDecimal quantity, Long actor) {
        LocalDateTime now = LocalDateTime.now();
        WmsInventory to = inventories.selectOne(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWorkOrderId, requirement.getWorkOrderId()).eq(WmsInventory::getWarehouseId, from.getWarehouseId())
                .eq(WmsInventory::getLocationId, from.getLocationId()).eq(WmsInventory::getMaterialId, from.getMaterialId())
                .eq(WmsInventory::getBatchId, from.getBatchId()).eq(WmsInventory::getDeletedFlag, 0).last("FOR UPDATE"));
        from.setLockedQuantity(zero(from.getLockedQuantity()).subtract(quantity)); from.setUpdatedBy(actor); from.setUpdatedTime(now);
        if (inventories.updateById(from) != 1) throw invalid("组件线边占用已变化，请重试");
        if (to == null) {
            to = new WmsInventory(); to.setWarehouseId(from.getWarehouseId()); to.setLocationId(from.getLocationId());
            to.setMaterialId(from.getMaterialId()); to.setBatchId(from.getBatchId()); to.setWorkOrderId(requirement.getWorkOrderId());
            to.setAvailableQuantity(BigDecimal.ZERO); to.setInspectionQuantity(BigDecimal.ZERO); to.setLockedQuantity(quantity);
            to.setVersion(0); to.setDeletedFlag(0); to.setCreatedBy(actor); to.setCreatedTime(now);
            to.setUpdatedBy(actor); to.setUpdatedTime(now); to.setRemark("同线组件直接交接，来源工单 " + lot.getWorkOrderId());
            if (inventories.insert(to) != 1) throw invalid("组件交接占用登记失败");
        } else {
            to.setLockedQuantity(zero(to.getLockedQuantity()).add(quantity)); to.setUpdatedBy(actor); to.setUpdatedTime(now);
            if (inventories.updateById(to) != 1) throw invalid("接收工单占用已变化，请重试");
        }
        String pair = "CH-" + UUID.randomUUID().toString().replace("-", "");
        writeMovement(from, lot, quantity, "OUT", pair, actor, now);
        writeMovement(to, lot, quantity, "IN", pair, actor, now);
        requirement.setIssuedQuantity(zero(requirement.getIssuedQuantity()).add(quantity));
        BigDecimal missing = zero(requirement.getRequiredQuantity()).subtract(requirement.getIssuedQuantity()).max(BigDecimal.ZERO);
        requirement.setShortageQuantity(missing.subtract(zero(requirement.getReservedQuantity())).max(BigDecimal.ZERO));
        requirement.setStatus(missing.signum() == 0 ? "READY" : "PARTIAL_READY");
        requirement.setUpdatedBy(actor); requirement.setUpdatedTime(now);
        if (requirements.updateById(requirement) != 1) throw invalid("工单用料到位数量更新失败");
    }

    private void writeMovement(WmsInventory stock, TraceProductLot lot, BigDecimal quantity, String direction,
                               String pair, Long actor, LocalDateTime now) {
        WmsStockMovement movement = new WmsStockMovement();
        movement.setMovementNo(pair + "-" + direction); movement.setPairNo(pair); movement.setMovementType(MOVEMENT);
        movement.setSourceOrderType("PRODUCT_LOT"); movement.setSourceOrderId(lot.getProductLotId());
        movement.setMaterialId(stock.getMaterialId()); movement.setBatchId(stock.getBatchId());
        movement.setWarehouseId(stock.getWarehouseId()); movement.setLocationId(stock.getLocationId()); movement.setWorkOrderId(stock.getWorkOrderId());
        movement.setQuantity(quantity); movement.setDirection(direction); movement.setOperationTime(now);
        movement.setRemark("同线组件交接；产品批次 " + lot.getLotNo()); movement.setVersion(0); movement.setDeletedFlag(0);
        movement.setCreatedBy(actor); movement.setCreatedTime(now); movement.setUpdatedBy(actor); movement.setUpdatedTime(now);
        if (movements.insert(movement) != 1) throw invalid("组件交接流水写入失败");
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BusinessException invalid(String message) { return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message); }
}
