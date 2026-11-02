package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgSupplyAllocation;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsMaterialBatch;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgSupplyAllocationMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsMaterialBatchMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 供给分配与库存预留。
 *
 * <p>把"合格可用量分析"升级为可承诺的库存预留：可用量分析只是参考，预留才代表
 * 这份库存已经属于某条工单需求，其它计划不能再分配同一数量。</p>
 *
 * <p>预留只改变数量状态，不改变物理总量：公共余额行的 available 减少、locked 增加，
 * available+locked+inspection 保持不变。预留不是发料，也不是生产消耗。</p>
 */
@Service
public class SupplyAllocationService {
    private final MfgSupplyAllocationMapper allocations;
    private final MfgWorkOrderMaterialMapper requirements;
    private final WmsInventoryMapper inventories;
    private final WmsMaterialBatchMapper batches;
    private final ComponentSupplyPlanningService componentSupply;

    public SupplyAllocationService(MfgSupplyAllocationMapper allocations,
                                   MfgWorkOrderMaterialMapper requirements,
                                   WmsInventoryMapper inventories,
                                   WmsMaterialBatchMapper batches,
                                   ComponentSupplyPlanningService componentSupply) {
        this.allocations = allocations;
        this.requirements = requirements;
        this.inventories = inventories;
        this.batches = batches;
        this.componentSupply = componentSupply;
    }

    /** 一条需求的预留结果：已预留数量与仍未覆盖的缺口。 */
    public record Reservation(BigDecimal reserved, BigDecimal shortage, List<MfgSupplyAllocation> allocations) {
    }

    /**
     * 为一条工单用料需求预留合格公共库存。
     *
     * <p>按批次先入先出、同批次按余额行稳定排序分配；行锁按 inventoryId 升序获取，
     * 避免不同计划并发预留时互相等待。只分配一次：可用量在预留时即被扣减。</p>
     */
    public Reservation reserve(MfgWorkOrderMaterial requirement, BigDecimal requiredQuantity,
                               String requestKey, Long operatorId, LocalDateTime now) {
        return reserve(requirement, requiredQuantity, requestKey, operatorId, now, null);
    }

    public Reservation reserve(MfgWorkOrderMaterial requirement, BigDecimal requiredQuantity,
                               String requestKey, Long operatorId, LocalDateTime now, Long warehouseId) {
        if (requirement == null || requirement.getRequirementId() == null
                || requirement.getMaterialId() == null) {
            throw invalid("预留必须指定工单用料需求与物料");
        }
        BigDecimal required = scale(requiredQuantity);
        BigDecimal alreadyReserved = scale(requirement.getReservedQuantity());
        BigDecimal outstanding = required.subtract(alreadyReserved).max(BigDecimal.ZERO);
        if (outstanding.signum() <= 0) {
            return new Reservation(alreadyReserved, shortageOf(requirement, alreadyReserved), List.of());
        }
        List<ComponentSupplyPlanningService.QualifiedStock> candidates =
                componentSupply.qualifiedStock(List.of(requirement.getMaterialId()));
        if (warehouseId != null) {
            candidates.removeIf(stock -> !Objects.equals(stock.warehouse().getWarehouseId(), warehouseId));
        }
        Map<Long, WmsInventory> locked = lockCandidates(candidates);
        AllocationRun run = allocateAcross(candidates, locked, requirement, outstanding,
                requestKey, operatorId, now);
        BigDecimal totalReserved = alreadyReserved.add(outstanding.subtract(run.remaining()));
        return new Reservation(totalReserved, required.subtract(totalReserved).max(BigDecimal.ZERO),
                run.created());
    }

    /** 一轮分配的产出：仍未覆盖的缺口与本次新建的预留记录。 */
    private record AllocationRun(BigDecimal remaining, List<MfgSupplyAllocation> created) {}

    /** 稳定锁序：先按余额行主键升序加锁，避免不同计划并发预留时互相等待。 */
    private Map<Long, WmsInventory> lockCandidates(List<ComponentSupplyPlanningService.QualifiedStock> candidates) {
        candidates.sort(Comparator.comparing(stock -> stock.inventory().getInventoryId()));
        Map<Long, WmsInventory> locked = new LinkedHashMap<>();
        for (ComponentSupplyPlanningService.QualifiedStock candidate : candidates) {
            Long inventoryId = candidate.inventory().getInventoryId();
            if (locked.containsKey(inventoryId)) continue;
            WmsInventory row = lockInventory(inventoryId);
            if (row != null) locked.put(inventoryId, row);
        }
        return locked;
    }

    /** 按批次先入先出分配，返回仍未覆盖的缺口与新建的预留记录。 */
    private AllocationRun allocateAcross(List<ComponentSupplyPlanningService.QualifiedStock> candidates,
                                         Map<Long, WmsInventory> locked, MfgWorkOrderMaterial requirement,
                                         BigDecimal outstanding, String requestKey, Long operatorId,
                                         LocalDateTime now) {
        List<ComponentSupplyPlanningService.QualifiedStock> order = fifoOrder(candidates);
        List<MfgSupplyAllocation> created = new ArrayList<>();
        BigDecimal remaining = outstanding;
        for (ComponentSupplyPlanningService.QualifiedStock candidate : order) {
            if (remaining.signum() <= 0) break;
            WmsInventory row = locked.get(candidate.inventory().getInventoryId());
            if (row == null) continue;
            BigDecimal take = remaining.min(scale(row.getAvailableQuantity()));
            if (take.signum() <= 0) continue;
            decreaseAvailable(row, take, operatorId, now);
            created.add(insertAllocation(requirement, row, take, requestKey, operatorId, now));
            remaining = remaining.subtract(take);
        }
        return new AllocationRun(remaining, created);
    }

    /**
     * 写入一条预留记录，写失败即整体回滚。
     *
     * <p>同一条用料需求对同一余额行反复「预留—释放」时，分配号
     * {@code SA-{需求}-{库存行}} 会重复触发唯一键冲突。这里先尝试复用已被释放的分配记录，
     * 把它重新置为预留；确实没有可复用的旧记录时，才追加序号后缀新建一条。</p>
     */
    private MfgSupplyAllocation insertAllocation(MfgWorkOrderMaterial requirement, WmsInventory row,
                                                 BigDecimal take, String requestKey, Long operatorId,
                                                 LocalDateTime now) {
        MfgSupplyAllocation reopened = reopenReleasedAllocation(requirement, row, take, requestKey, operatorId, now);
        if (reopened != null) {
            return reopened;
        }
        MfgSupplyAllocation allocation = newAllocation(requirement, row, take, requestKey, operatorId, now);
        allocation.setAllocationNo(nextAllocationNo(allocation.getAllocationNo()));
        if (allocations.insert(allocation) != 1) {
            throw invalid("库存预留写入失败");
        }
        return allocation;
    }

    /**
     * 复用同需求、同余额行上已释放且释放量足够的分配记录。
     *
     * <p>释放量够就整条重新置为预留，让「预留—释放」循环不会无限增长分配记录；
     * 释放量不够或有其它记录占用时返回 null，由调用方新建。</p>
     */
    private MfgSupplyAllocation reopenReleasedAllocation(MfgWorkOrderMaterial requirement, WmsInventory row,
                                                         BigDecimal take, String requestKey,
                                                         Long operatorId, LocalDateTime now) {
        List<MfgSupplyAllocation> rows = allocations.selectList(new LambdaQueryWrapper<MfgSupplyAllocation>()
                .eq(MfgSupplyAllocation::getRequirementId, requirement.getRequirementId())
                .eq(MfgSupplyAllocation::getInventoryId, row.getInventoryId())
                .eq(MfgSupplyAllocation::getStatus, MfgSupplyAllocation.RELEASED)
                .eq(MfgSupplyAllocation::getDeletedFlag, 0)
                .orderByAsc(MfgSupplyAllocation::getAllocationId)
                .last("FOR UPDATE"));
        for (MfgSupplyAllocation existing : rows) {
            BigDecimal released = scale(existing.getReleasedQuantity());
            if (released.compareTo(take) < 0) continue;
            MfgSupplyAllocation update = new MfgSupplyAllocation();
            update.setAllocationId(existing.getAllocationId());
            update.setVersion(existing.getVersion());
            update.setStatus(MfgSupplyAllocation.RESERVED);
            update.setReservedQuantity(take);
            update.setReleasedQuantity(released.subtract(take));
            update.setRequestKey(requestKey);
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (allocations.updateById(update) != 1) {
                throw invalid("库存预留写入失败，分配记录已被其他请求修改");
            }
            existing.setStatus(MfgSupplyAllocation.RESERVED);
            existing.setReservedQuantity(take);
            existing.setReleasedQuantity(released.subtract(take));
            existing.setRequestKey(requestKey);
            return existing;
        }
        return null;
    }

    /** 分配号去重：基础号被占用时依次尝试 {@code -2}、{@code -3} 后缀。 */
    private String nextAllocationNo(String base) {
        String candidate = base;
        for (int suffix = 2; suffix <= 999; suffix++) {
            boolean taken = allocations.selectCount(new LambdaQueryWrapper<MfgSupplyAllocation>()
                    .eq(MfgSupplyAllocation::getAllocationNo, candidate)) > 0;
            if (!taken) {
                return candidate;
            }
            candidate = base + "-" + suffix;
        }
        throw invalid("同一条用料需求的库存预留记录过多，请先清理无效分配记录");
    }

    /** 分配顺序：批次收货日期、批次主键、余额行主键依次升序。 */
    private static List<ComponentSupplyPlanningService.QualifiedStock> fifoOrder(
            List<ComponentSupplyPlanningService.QualifiedStock> candidates) {
        List<ComponentSupplyPlanningService.QualifiedStock> order = new ArrayList<>(candidates);
        order.sort(Comparator.comparing((ComponentSupplyPlanningService.QualifiedStock stock) -> {
                    WmsMaterialBatch batch = stock.batch();
                    return batch == null || batch.getReceivedDate() == null
                            ? java.time.LocalDate.MAX : batch.getReceivedDate();
                })
                .thenComparing(stock -> stock.batch() == null ? Long.MAX_VALUE : stock.batch().getBatchId())
                .thenComparing(stock -> stock.inventory().getInventoryId()));
        return order;
    }

    /** 组装一条预留记录，数量口径与状态在此固定。 */
    private static MfgSupplyAllocation newAllocation(MfgWorkOrderMaterial requirement, WmsInventory row,
                                                     BigDecimal take, String requestKey, Long operatorId,
                                                     LocalDateTime now) {
        MfgSupplyAllocation allocation = new MfgSupplyAllocation();
        allocation.setAllocationNo("SA-" + requirement.getRequirementId() + "-" + row.getInventoryId());
        allocation.setRequirementId(requirement.getRequirementId());
        allocation.setWorkOrderId(requirement.getWorkOrderId());
        allocation.setMaterialId(requirement.getMaterialId());
        allocation.setWarehouseId(row.getWarehouseId());
        allocation.setLocationId(row.getLocationId());
        allocation.setBatchId(row.getBatchId());
        allocation.setInventoryId(row.getInventoryId());
        allocation.setAllocatedQuantity(take);
        allocation.setReservedQuantity(take);
        allocation.setTransferredQuantity(BigDecimal.ZERO.setScale(4));
        allocation.setConsumedQuantity(BigDecimal.ZERO.setScale(4));
        allocation.setReleasedQuantity(BigDecimal.ZERO.setScale(4));
        allocation.setStatus(MfgSupplyAllocation.RESERVED);
        allocation.setRequestKey(requestKey);
        allocation.setRemark("合格库存预留，可用量在预留时扣减");
        allocation.setVersion(0);
        allocation.setCreatedBy(operatorId);
        allocation.setCreatedTime(now);
        allocation.setUpdatedBy(operatorId);
        allocation.setUpdatedTime(now);
        allocation.setDeletedFlag(0);
        return allocation;
    }

    /**
     * 释放某组工单尚未转为线边占用的预留，把数量退回公共可用量。
     * 已转线边或已消耗的分配不在这里释放，必须走专门的退料命令。
     */
    public BigDecimal releaseForWorkOrders(List<Long> workOrderIds, Long operatorId, LocalDateTime now) {
        if (workOrderIds == null || workOrderIds.isEmpty()) return BigDecimal.ZERO;
        return release(workOrderIds, null, operatorId, now);
    }

    public BigDecimal releaseForRequirements(Long workOrderId, List<Long> requirementIds, Long operatorId, LocalDateTime now) {
        if (requirementIds == null || requirementIds.isEmpty()) return BigDecimal.ZERO;
        return release(List.of(workOrderId), requirementIds, operatorId, now);
    }

    private BigDecimal release(List<Long> workOrderIds, List<Long> requirementIds, Long operatorId, LocalDateTime now) {
        List<MfgSupplyAllocation> rows = allocations.selectList(new LambdaQueryWrapper<MfgSupplyAllocation>()
                .in(MfgSupplyAllocation::getWorkOrderId, workOrderIds)
                .in(requirementIds != null, MfgSupplyAllocation::getRequirementId, requirementIds)
                .eq(MfgSupplyAllocation::getStatus, MfgSupplyAllocation.RESERVED)
                .eq(MfgSupplyAllocation::getDeletedFlag, 0)
                .orderByAsc(MfgSupplyAllocation::getInventoryId)
                .last("FOR UPDATE"));
        BigDecimal released = BigDecimal.ZERO;
        for (MfgSupplyAllocation row : rows) {
            BigDecimal quantity = scale(row.getReservedQuantity());
            if (quantity.signum() <= 0) continue;
            WmsInventory inventory = lockInventory(row.getInventoryId());
            if (inventory == null) {
                throw invalid("预留对应的库存余额已不存在，不能释放");
            }
            restoreAvailable(inventory, quantity, operatorId, now);
            MfgSupplyAllocation update = new MfgSupplyAllocation();
            update.setAllocationId(row.getAllocationId());
            update.setVersion(row.getVersion());
            update.setStatus(MfgSupplyAllocation.RELEASED);
            update.setReservedQuantity(BigDecimal.ZERO.setScale(4));
            update.setReleasedQuantity(scale(row.getReleasedQuantity()).add(quantity));
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (allocations.updateById(update) != 1) {
                throw invalid("预留释放失败，库存余额已被其他请求修改");
            }
            released = released.add(quantity);
        }
        if (released.signum() > 0) {
            resetRequirements(workOrderIds, requirementIds, operatorId, now);
        }
        return released;
    }

    /** 预留全部释放后，把需求行的预留量归零并重算缺口与齐套状态。 */
    private void resetRequirements(List<Long> workOrderIds, List<Long> requirementIds, Long operatorId, LocalDateTime now) {
        List<MfgWorkOrderMaterial> requirementRows = requirements.selectList(
                new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .in(MfgWorkOrderMaterial::getWorkOrderId, workOrderIds)
                        .in(requirementIds != null, MfgWorkOrderMaterial::getRequirementId, requirementIds)
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0)
                        .last("FOR UPDATE"));
        for (MfgWorkOrderMaterial requirement : requirementRows) {
            BigDecimal reserved = scale(requirement.getReservedQuantity());
            if (reserved.signum() <= 0) continue;
            BigDecimal issued = scale(requirement.getIssuedQuantity());
            BigDecimal required = scale(requirement.getRequiredQuantity());
            BigDecimal shortage = required.subtract(issued).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
            MfgWorkOrderMaterial update = new MfgWorkOrderMaterial();
            update.setRequirementId(requirement.getRequirementId());
            update.setVersion(requirement.getVersion());
            update.setReservedQuantity(BigDecimal.ZERO.setScale(4));
            update.setShortageQuantity(shortage);
            update.setStatus(readinessStatus(shortage, issued));
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (requirements.updateById(update) != 1) {
                throw invalid("工单用料需求预留回退失败，请刷新后重试");
            }
        }
    }

    /**
     * 领料转线边：把来源的预留占用转为该工单的线边占用。
     *
     * <p>只减少来源行的 locked，不再扣减 available —— 可用量在预留时已经扣过一次，
     * 重复扣减会把同一份库存算成两份消耗。</p>
     *
     * @return 本次由预留转换的数量；不足部分由调用方按旧口径处理
     */
    public BigDecimal consumeReservation(Long workOrderId, Long materialId, Long inventoryId,
                                         BigDecimal quantity, Long operatorId, LocalDateTime now) {
        if (!hasConsumableReservation(workOrderId, materialId, inventoryId, quantity)) {
            return BigDecimal.ZERO;
        }
        List<MfgSupplyAllocation> rows = allocations.selectList(new LambdaQueryWrapper<MfgSupplyAllocation>()
                .eq(MfgSupplyAllocation::getWorkOrderId, workOrderId)
                .eq(MfgSupplyAllocation::getMaterialId, materialId)
                .eq(MfgSupplyAllocation::getInventoryId, inventoryId)
                .eq(MfgSupplyAllocation::getStatus, MfgSupplyAllocation.RESERVED)
                .eq(MfgSupplyAllocation::getDeletedFlag, 0)
                .orderByAsc(MfgSupplyAllocation::getAllocationId)
                .last("FOR UPDATE"));
        BigDecimal remaining = scale(quantity);
        BigDecimal consumed = BigDecimal.ZERO;
        for (MfgSupplyAllocation row : rows) {
            if (remaining.signum() <= 0) break;
            BigDecimal reserved = scale(row.getReservedQuantity());
            BigDecimal take = remaining.min(reserved);
            if (take.signum() <= 0) continue;
            BigDecimal left = reserved.subtract(take);
            MfgSupplyAllocation update = new MfgSupplyAllocation();
            update.setAllocationId(row.getAllocationId());
            update.setVersion(row.getVersion());
            update.setReservedQuantity(left);
            update.setTransferredQuantity(scale(row.getTransferredQuantity()).add(take));
            update.setStatus(left.signum() == 0 ? MfgSupplyAllocation.TRANSFERRED : MfgSupplyAllocation.RESERVED);
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (allocations.updateById(update) != 1) {
                throw invalid("预留转线边失败，预留记录已被其他请求修改");
            }
            remaining = remaining.subtract(take);
            consumed = consumed.add(take);
        }
        return consumed;
    }

    /** 预留转换的必要条件：工单、物料、库存行齐全，且转换数量为正。 */
    private static boolean hasConsumableReservation(Long workOrderId, Long materialId, Long inventoryId,
                                                    BigDecimal quantity) {
        if (workOrderId == null || materialId == null || inventoryId == null) {
            return false;
        }
        return quantity != null && quantity.signum() > 0;
    }

    /** 列出某工单全部仍处于预留状态的分配，按物料与余额行稳定排序。 */    public List<MfgSupplyAllocation> reservedAllocationsForWorkOrder(Long workOrderId) {
        if (workOrderId == null) return List.of();
        return allocations.selectList(new LambdaQueryWrapper<MfgSupplyAllocation>()
                .eq(MfgSupplyAllocation::getWorkOrderId, workOrderId)
                .eq(MfgSupplyAllocation::getStatus, MfgSupplyAllocation.RESERVED)
                .eq(MfgSupplyAllocation::getDeletedFlag, 0)
                .orderByAsc(MfgSupplyAllocation::getMaterialId)
                .orderByAsc(MfgSupplyAllocation::getAllocationId));
    }

    /** 释放某条来源余额行上、某工单的全部剩余预留（按物料）。 */
    public List<MfgSupplyAllocation> reservedAllocations(Long workOrderId, Long materialId) {
        if (workOrderId == null || materialId == null) return List.of();
        return allocations.selectList(new LambdaQueryWrapper<MfgSupplyAllocation>()
                .eq(MfgSupplyAllocation::getWorkOrderId, workOrderId)
                .eq(MfgSupplyAllocation::getMaterialId, materialId)
                .eq(MfgSupplyAllocation::getStatus, MfgSupplyAllocation.RESERVED)
                .eq(MfgSupplyAllocation::getDeletedFlag, 0)
                .orderByAsc(MfgSupplyAllocation::getAllocationId));
    }

    private WmsInventory lockInventory(Long inventoryId) {
        return inventories.selectOne(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getInventoryId, inventoryId)
                .eq(WmsInventory::getDeletedFlag, 0)
                .last("FOR UPDATE"));
    }

    private void decreaseAvailable(WmsInventory row, BigDecimal quantity, Long operatorId, LocalDateTime now) {
        BigDecimal available = scale(row.getAvailableQuantity());
        if (available.compareTo(quantity) < 0) {
            throw invalid("合格库存已被其他请求分配，请刷新后重试");
        }
        WmsInventory update = new WmsInventory();
        update.setInventoryId(row.getInventoryId());
        update.setVersion(row.getVersion());
        update.setAvailableQuantity(available.subtract(quantity));
        update.setLockedQuantity(scale(row.getLockedQuantity()).add(quantity));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (inventories.updateById(update) != 1) {
            throw invalid("库存余额已被其他请求修改，请刷新后重试");
        }
    }

    private void restoreAvailable(WmsInventory row, BigDecimal quantity, Long operatorId, LocalDateTime now) {
        BigDecimal locked = scale(row.getLockedQuantity());
        if (locked.compareTo(quantity) < 0) {
            throw invalid("库存锁定量与预留记录不一致，不能释放");
        }
        WmsInventory update = new WmsInventory();
        update.setInventoryId(row.getInventoryId());
        update.setVersion(row.getVersion());
        update.setLockedQuantity(locked.subtract(quantity));
        update.setAvailableQuantity(scale(row.getAvailableQuantity()).add(quantity));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (inventories.updateById(update) != 1) {
            throw invalid("库存余额已被其他请求修改，请刷新后重试");
        }
    }

    private static String readinessStatus(BigDecimal shortage, BigDecimal issued) {
        if (shortage.signum() <= 0) {
            return "READY";
        }
        return issued.signum() > 0 ? "PARTIAL_READY" : "WAIT_READY";
    }

    private BigDecimal shortageOf(MfgWorkOrderMaterial requirement, BigDecimal reserved) {
        BigDecimal required = scale(requirement.getRequiredQuantity());
        BigDecimal issued = scale(requirement.getIssuedQuantity());
        return required.subtract(issued).subtract(reserved).max(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(4) : value.setScale(4, RoundingMode.HALF_UP);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message);
    }
}
