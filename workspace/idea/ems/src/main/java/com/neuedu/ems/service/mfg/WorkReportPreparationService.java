package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.*;
import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.entity.wms.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.*;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.mapper.process.ProcessOperationMapper;
import com.neuedu.ems.mapper.wms.*;
import com.neuedu.ems.service.mdm.MaterialQuantityPolicy;
import com.neuedu.ems.service.sys.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/** 报工前只读准备：按当前投料工序和本工单实领批次建议用料，不替用户确认实际数量。 */
@Service
@RequiredArgsConstructor
public class WorkReportPreparationService {
    private final MfgTaskMapper tasks;
    private final MfgWorkOrderMaterialMapper requirements;
    private final MfgWorkOrderMapper orders;
    private final MfgWorkReportMapper reports;
    private final MfgReportMaterialMapper reportMaterials;
    private final WmsInventoryMapper inventories;
    private final WmsMaterialBatchMapper batches;
    private final WmsReturnDetailMapper returnDetails;
    private final MdmBomItemMapper bomItems;
    private final MdmMaterialMapper materials;
    private final MdmProductMapper products;
    private final ProcessOperationMapper operations;
    private final ExecutionSnapshotPolicy snapshots;
    private final MaterialQuantityPolicy quantities;
    private final DataScopeService scope;

    public record MaterialLine(Long materialId, String materialName, Long batchId, String batchNo,
                               BigDecimal availableQuantity, BigDecimal suggestedQuantity) {}
    public record Preparation(Long taskId, String taskNo, Long workOrderId, String workOrderNo,
                              String productName, String operationName, Long dispatchId, String status,
                              BigDecimal plannedQuantity, BigDecimal reportedQualifiedQuantity,
                              BigDecimal reportedDefectQuantity, BigDecimal remainingQuantity,
                              boolean canReport, List<MaterialLine> materials, List<String> warnings,
                              LocalDateTime workOrderPlannedStartTime, LocalDateTime workOrderPlannedEndTime,
                              BigDecimal taskStandardWorkHours) {}

    /** 只筛选本工序涉及的物料；返回数量仍为工单领料汇总，实际耗用以报工明细为准。 */
    @Transactional(readOnly = true)
    public List<MfgWorkOrderMaterial> materialRequirements(Long taskId) {
        var query=new LambdaQueryWrapper<MfgTask>().eq(MfgTask::getTaskId,taskId).eq(MfgTask::getDeletedFlag,0);
        scope.applyTaskScope(query);
        MfgTask task=tasks.selectOne(query);
        if(task==null) throw invalid("生产任务不存在或无权查看");
        MfgWorkOrder order=orders.selectById(task.getWorkOrderId());
        if(order==null || !Integer.valueOf(0).equals(order.getDeletedFlag())) throw invalid("生产工单不存在");
        Set<Long> ids=demands(order,task.getOperationId(),task.getPlannedQuantity()).keySet();
        if(ids.isEmpty()) return List.of();
        return requirements.selectList(new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                .eq(MfgWorkOrderMaterial::getWorkOrderId,task.getWorkOrderId()).in(MfgWorkOrderMaterial::getMaterialId,ids)
                .eq(MfgWorkOrderMaterial::getDeletedFlag,0).orderByAsc(MfgWorkOrderMaterial::getRequirementId));
    }

    @Transactional(readOnly = true)
    public Preparation prepare(Long taskId, Long editingReportId, BigDecimal quantity) {
        MfgTask task = requireTask(taskId);
        requireEditingReportMatches(editingReportId, taskId);
        MfgWorkOrder order = requireWorkOrder(task);
        List<MfgWorkReport> existing = existingReports(taskId, editingReportId);
        BigDecimal qualified = sum(existing, MfgWorkReport::getQualifiedQuantity);
        BigDecimal defect = sum(existing, MfgWorkReport::getDefectQuantity);
        BigDecimal previous = qualified.add(defect);
        BigDecimal remaining = zero(task.getPlannedQuantity()).subtract(previous).max(BigDecimal.ZERO);
        BigDecimal requested = requireReportable(quantity, remaining);
        Map<Long, BigDecimal> incremental = incrementalDemands(order, task, previous, requested);
        Map<String, BigDecimal> reserved = reservedByBatch(order, editingReportId);
        List<MaterialLine> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        appendMaterialLines(order, incremental, reserved, result, warnings);
        appendDemandWarnings(task, incremental, warnings);
        warnings.add("使用总量包含报废用料，“其中报废数量”是总量的一部分，不再额外扣料。例如总用料100、其中报废10，只消耗100。产品不良不等同于材料报废，请按实际填写；需补产时再处置并补领料。");
        var product = products.selectById(order.getProductId());
        var operation = operations.selectById(task.getOperationId());
        return new Preparation(taskId,task.getTaskNo(),order.getWorkOrderId(),order.getWorkOrderNo(),
                product == null ? "" : product.getProductName(), operation == null ? "" : operation.getOperationName(),
                task.getDispatchId(),task.getStatus(),task.getPlannedQuantity(),qualified,defect,remaining,
                Set.of("RUNNING","PAUSED").contains(task.getStatus()) && remaining.signum() > 0,result,warnings,
                order.getPlannedStartTime(),order.getPlannedEndTime(),referenceWorkHours(order,task));
    }

    /** 整个任务的冻结工艺参考工时，不能用工单跨班次窗口相减，也不替代实际填报。 */
    BigDecimal referenceWorkHours(MfgWorkOrder order, MfgTask task) {
        BigDecimal hours = null;
        if (referenceApplicable(order,task)) {
            var steps = snapshots.routeItems(order).stream()
                    .filter(step -> Objects.equals(task.getOperationId(),step.getOperationId())).toList();
            BigDecimal minutes = steps.isEmpty() ? null : BigDecimal.ZERO;
            for (var step : steps) {
                BigDecimal single = stepMinutes(task,step);
                if (single == null) {
                    minutes = null;
                    break;
                }
                minutes = minutes.add(single);
            }
            if (minutes != null) {
                hours = minutes.divide(BigDecimal.valueOf(60),4,RoundingMode.HALF_UP);
            }
        }
        return hours;
    }

    /** 参考工时是否适用：返工任务不折算，计划数量无效或缺冻结路线时也不算。 */
    private boolean referenceApplicable(MfgWorkOrder order, MfgTask task) {
        boolean rework = "REWORK".equals(task.getTaskType());
        boolean quantityUsable = task.getPlannedQuantity() != null && task.getPlannedQuantity().signum() > 0;
        return !rework && quantityUsable && snapshots.isCurrentFormat(order);
    }

    /** 单个工序的标准工时；该工序的工艺参数不足以折算时返回 null，由调用方整体放弃。 */
    private BigDecimal stepMinutes(MfgTask task, ProcessRouteItem step) {
        BigDecimal minutes = null;
        if ("BATCH_EQUIPMENT".equals(step.getResourceType())) {
            boolean batchUsable = step.getBatchCapacity() != null && step.getBatchCapacity().signum() > 0
                    && step.getBatchDurationMinutes() != null && step.getBatchDurationMinutes().signum() > 0;
            if (batchUsable) {
                minutes = task.getPlannedQuantity().divide(step.getBatchCapacity(),0,RoundingMode.CEILING)
                        .multiply(step.getBatchDurationMinutes());
            }
        } else if (step.getStandardTime() != null && step.getStandardTime().signum() > 0) {
            minutes = step.getStandardTime().multiply(task.getPlannedQuantity());
        }
        return minutes;
    }

    /** 读取当前用户有权查看的生产任务。 */
    private MfgTask requireTask(Long taskId) {
        var taskQuery = new LambdaQueryWrapper<MfgTask>().eq(MfgTask::getTaskId, taskId).eq(MfgTask::getDeletedFlag, 0);
        scope.applyTaskScope(taskQuery);
        MfgTask task = tasks.selectOne(taskQuery);
        if (task == null) {
            throw invalid("生产任务不存在或无权查看");
        }
        return task;
    }

    /** 编辑已有报工时，报工必须属于同一张任务。 */
    private void requireEditingReportMatches(Long editingReportId, Long taskId) {
        if (editingReportId == null) {
            return;
        }
        var editing = reports.selectById(editingReportId);
        if (editing == null || !Integer.valueOf(0).equals(editing.getDeletedFlag())
                || !Objects.equals(editing.getTaskId(), taskId)) {
            throw invalid("报工与生产任务不匹配");
        }
    }

    /** 读取任务所属工单。 */
    private MfgWorkOrder requireWorkOrder(MfgTask task) {
        MfgWorkOrder order = orders.selectById(task.getWorkOrderId());
        if (order == null || !Integer.valueOf(0).equals(order.getDeletedFlag())) {
            throw invalid("生产工单不存在");
        }
        return order;
    }

    /** 已提交或已审核的报工（排除正在编辑的这一张）。 */
    private List<MfgWorkReport> existingReports(Long taskId, Long editingReportId) {
        return reports.selectList(new LambdaQueryWrapper<MfgWorkReport>().eq(MfgWorkReport::getTaskId, taskId)
                .eq(MfgWorkReport::getDeletedFlag, 0).in(MfgWorkReport::getAuditStatus, "SUBMITTED", "APPROVED")
                .ne(editingReportId != null, MfgWorkReport::getReportId, editingReportId));
    }

    /** 本次可报数量：未传则默认报满剩余；超出剩余或为负则拒绝。 */
    private BigDecimal requireReportable(BigDecimal quantity, BigDecimal remaining) {
        BigDecimal requested = quantity == null ? remaining : quantity;
        if (requested.signum() < 0 || requested.compareTo(remaining) > 0) {
            throw invalid("本次合格加不良数量不能超过剩余可报数量 " + remaining.toPlainString());
        }
        return requested;
    }

    /** 本次增量用料需求：报满后的累计需求减去报工前的需求，返工不重复消耗整套物料。 */
    private Map<Long, BigDecimal> incrementalDemands(MfgWorkOrder order, MfgTask task,
                                                     BigDecimal previous, BigDecimal requested) {
        Map<Long, BigDecimal> demands = demands(order, task.getOperationId(), previous.add(requested));
        Map<Long, BigDecimal> before = demands(order, task.getOperationId(), previous);
        if ("REWORK".equals(task.getTaskType())) {
            demands.clear();
        }
        demands.replaceAll((id, amount) -> amount.subtract(before.getOrDefault(id, BigDecimal.ZERO))
                .max(BigDecimal.ZERO));
        return demands;
    }

    /** 已经被待审报工与待审退料占用的线边库存：按物料与批次归集。 */
    private Map<String, BigDecimal> reservedByBatch(MfgWorkOrder order, Long editingReportId) {
        Map<String, BigDecimal> reserved = new HashMap<>();
        var pending = reportMaterials.selectList(new LambdaQueryWrapper<MfgReportMaterial>()
                .eq(MfgReportMaterial::getDeletedFlag, 0).inSql(MfgReportMaterial::getReportId,
                        "SELECT r.report_id FROM mfg_work_report r JOIN mfg_task t ON t.task_id=r.task_id"
                                + " WHERE r.deleted_flag=0 AND t.deleted_flag=0 AND r.audit_status='SUBMITTED'"
                                + " AND t.work_order_id=" + order.getWorkOrderId())
                .ne(editingReportId != null, MfgReportMaterial::getReportId, editingReportId));
        pending.forEach(m -> reserved.merge(key(m.getMaterialId(), m.getBatchId()),
                zero(m.getUsedQuantity()).add(zero(m.getScrapQuantity())), BigDecimal::add));
        returnDetails.selectList(new LambdaQueryWrapper<WmsReturnDetail>().eq(WmsReturnDetail::getDeletedFlag, 0)
                .inSql(WmsReturnDetail::getReturnId,
                        "SELECT return_id FROM wms_return_order WHERE deleted_flag=0 AND audit_status='SUBMITTED'"
                                + " AND work_order_id=" + order.getWorkOrderId()))
                .forEach(m -> reserved.merge(key(m.getMaterialId(), m.getBatchId()),
                        zero(m.getReturnQuantity()), BigDecimal::add));
        return reserved;
    }

    /** 按需求逐物料挑选线边批次，给出建议领料量；扣不齐的部分转成警告。 */
    private void appendMaterialLines(MfgWorkOrder order, Map<Long, BigDecimal> demands,
                                     Map<String, BigDecimal> reserved,
                                     List<MaterialLine> result, List<String> warnings) {
        for (var demand : demands.entrySet()) {
            var material = materials.selectById(demand.getKey());
            String name = material == null ? "物料#" + demand.getKey() : material.getMaterialName();
            BigDecimal left = demand.getValue();
            for (var stock : lineSideStocks(order, demand.getKey()).entrySet()) {
                BigDecimal available = stock.getValue()
                        .subtract(reserved.getOrDefault(key(demand.getKey(), stock.getKey()), BigDecimal.ZERO))
                        .max(BigDecimal.ZERO);
                if (available.signum() == 0) {
                    continue;
                }
                BigDecimal suggested = left.min(available);
                var batch = batches.selectById(stock.getKey());
                result.add(new MaterialLine(demand.getKey(), name, stock.getKey(),
                        batch == null ? "批次#" + stock.getKey() : batch.getBatchNo(), available, suggested));
                left = left.subtract(suggested);
            }
            if (left.signum() > 0) {
                warnings.add(name + "缺少可用领料 " + left.stripTrailingZeros().toPlainString()
                        + "；请先补料转线边，再确认实际用料");
            }
        }
    }

    /** 本工单在当前物料下、各线边批次的锁定数量。 */
    private Map<Long, BigDecimal> lineSideStocks(MfgWorkOrder order, Long materialId) {
        Map<Long, BigDecimal> stocks = new LinkedHashMap<>();
        inventories.selectList(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWorkOrderId, order.getWorkOrderId())
                .eq(WmsInventory::getMaterialId, materialId).eq(WmsInventory::getDeletedFlag, 0)
                .inSql(WmsInventory::getWarehouseId,
                        "SELECT warehouse_id FROM wms_warehouse WHERE warehouse_type='LINE_SIDE'"
                                + " AND status='ENABLE' AND deleted_flag=0")
                .orderByAsc(WmsInventory::getBatchId, WmsInventory::getLocationId))
                .forEach(i -> stocks.merge(i.getBatchId(), zero(i.getLockedQuantity()), BigDecimal::add));
        return stocks;
    }

    /** 无直接投料时的口径说明。 */
    private static void appendDemandWarnings(MfgTask task, Map<Long, BigDecimal> demands, List<String> warnings) {
        if (!demands.isEmpty()) {
            return;
        }
        warnings.add("REWORK".equals(task.getTaskType())
                ? "返工复用原在制品，不默认重复消耗整套物料；如更换材料，按实际补领批次填写"
                : "本工序无直接投料，不重复消耗其他工序物料，可不填写报工用料");
    }

    private static BigDecimal sum(List<MfgWorkReport> reports,
                                  java.util.function.Function<MfgWorkReport, BigDecimal> field) {
        return reports.stream().map(r -> zero(field.apply(r))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<Long, BigDecimal> demands(MfgWorkOrder order, Long operationId, BigDecimal quantity) {
        if (quantity.signum() == 0) return new LinkedHashMap<>();
        if (snapshots.isCurrentFormat(order)) {
            var root = snapshots.read(order).deepCopy();
            var lines = ((com.fasterxml.jackson.databind.node.ObjectNode)root).putArray("bomItems");
            for (var line : snapshots.read(order).path("bomItems"))
                if (line.path("consumptionOperationId").asLong() == operationId) lines.add(line);
            return snapshots.directDemands(root, quantity);
        }
        Map<Long,BigDecimal> theoretical = new LinkedHashMap<>();
        bomItems.selectList(new LambdaQueryWrapper<MdmBomItem>().eq(MdmBomItem::getBomId,order.getBomId())
                .eq(MdmBomItem::getConsumptionOperationId,operationId).eq(MdmBomItem::getDeletedFlag,0))
                .forEach(i -> theoretical.merge(i.getMaterialId(),quantity.multiply(zero(i.getQuantity()))
                        .multiply(BigDecimal.ONE.add(zero(i.getLossRate()))),BigDecimal::add));
        theoretical.replaceAll((id,q) -> quantities.executionDemand(materials.selectById(id),q));
        return theoretical;
    }

    private static String key(Long materialId, Long batchId) { return materialId + ":" + batchId; }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BusinessException invalid(String message) { return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message); }
}
