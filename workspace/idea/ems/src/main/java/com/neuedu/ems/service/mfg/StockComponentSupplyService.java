package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.*;
import com.neuedu.ems.entity.qms.*;
import com.neuedu.ems.entity.wms.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.*;
import com.neuedu.ems.mapper.wms.*;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 库存组件领用：复验与真实移库供给上级工单，不报工、不生成新的产品产出。调用方负责事务。 */
@Service
@RequiredArgsConstructor
public class StockComponentSupplyService {
    private final JdbcTemplate jdbc;
    private final MfgWorkOrderMapper orders;
    private final MfgWorkOrderMaterialMapper requirements;
    private final WmsWarehouseMapper warehouses;
    private final WmsOutboundDetailMapper details;
    private final SupplyAllocationService allocations;
    private final ComponentSupplyPlanningService stocks;
    private final ObjectProvider<QmsInspectionOrderService> inspections;
    private final org.mybatis.spring.SqlSessionTemplate session;

    /**
     * 一次半成品领用选择的结果。
     *
     * @param component 办理领料的组件工单，出库单挂在这张工单下（用户按组件工单看生产领料）
     * @param receiver 上级工单，真正消耗该组件用料需求的工单
     * @param quantities 每种组件物料本次领用的数量
     */
    public record Selection(MfgWorkOrder component, MfgWorkOrder receiver, Map<Long, BigDecimal> quantities) {}

    public Selection select(MfgWorkOrder requested, Long warehouseId, Long actor, boolean reserve) {
        WmsWarehouse warehouse = warehouses.selectById(warehouseId);
        if (warehouse == null || !Set.of("SEMI", "SEMI_FINISHED").contains(warehouse.getWarehouseType())) {
            return null;
        }
        MfgWorkOrder receiver = receivingOrder(requested, reserve);
        if (receiver == null || !Set.of("RELEASED", "RUNNING").contains(receiver.getStatus())) {
            throw invalid("库存半成品供给上级工单，请先下达上级整机/组件工单，再发起半成品领料");
        }
        Long onlyMaterial = requested.getParentWorkOrderId() == null ? null : jdbc.queryForObject("SELECT material_id FROM mdm_product WHERE product_id=? AND deleted_flag=0", Long.class, requested.getProductId());
        Map<Long, BigDecimal> selected = new LinkedHashMap<>();
        for (MfgWorkOrderMaterial requirement : requirements.selectList(new LambdaQueryWrapper<MfgWorkOrderMaterial>().eq(MfgWorkOrderMaterial::getWorkOrderId, receiver.getWorkOrderId()).eq(MfgWorkOrderMaterial::getDeletedFlag, 0).orderByAsc(MfgWorkOrderMaterial::getRequirementId).last(reserve ? "FOR UPDATE" : ""))) {
            selectRequirement(receiver, requirement, onlyMaterial, warehouseId, actor, reserve, selected);
        }
        if (selected.isEmpty()) {
            throw invalid("所选半成品仓没有足额可领的组件；请核对组件物料、合格批次和库存，不要领原材料替代组件");
        }
        if (reserve) {
            session.clearCache();
        }
        return new Selection(requested, receiver, Collections.unmodifiableMap(selected));
    }

    private MfgWorkOrder receivingOrder(MfgWorkOrder requested, boolean reserve) {
        if (requested.getParentWorkOrderId() == null) {
            return requested;
        }
        return orders.selectOne(new LambdaQueryWrapper<MfgWorkOrder>().eq(MfgWorkOrder::getWorkOrderId, requested.getParentWorkOrderId()).eq(MfgWorkOrder::getDeletedFlag, 0).last(reserve ? "FOR UPDATE" : ""));
    }

    private void selectRequirement(MfgWorkOrder receiver, MfgWorkOrderMaterial requirement, Long onlyMaterial, Long warehouseId, Long actor, boolean reserve, Map<Long, BigDecimal> selected) {
        if (!matchesComponentRequirement(requirement, onlyMaterial)) {
            return;
        }
        BigDecimal needed = zero(requirement.getRequiredQuantity()).subtract(zero(requirement.getIssuedQuantity()));
        if (needed.signum() <= 0) {
            return;
        }
        List<Long> children = replacedOrders(receiver.getWorkOrderId(), requirement.getMaterialId());
        BigDecimal available = availableComponentStock(receiver, requirement, warehouseId);
        if (!hasFullComponentStock(requirement, onlyMaterial, warehouseId, needed, available)) {
            return;
        }
        scheme(requirement.getMaterialId());
        requireUnused(children, null);
        if (reserve) {
            reserveComponent(requirement, needed, warehouseId, actor);
        }
        selected.put(requirement.getMaterialId(), needed);
    }

    private boolean matchesComponentRequirement(MfgWorkOrderMaterial requirement, Long onlyMaterial) {
        return (onlyMaterial == null || onlyMaterial.equals(requirement.getMaterialId())) && isComponent(requirement.getMaterialId());
    }

    private BigDecimal availableComponentStock(MfgWorkOrder receiver, MfgWorkOrderMaterial requirement, Long warehouseId) {
        List<MfgSupplyAllocation> reserved = allocations.reservedAllocations(receiver.getWorkOrderId(), requirement.getMaterialId());
        if (reserved.stream().anyMatch(a -> !warehouseId.equals(a.getWarehouseId()) && zero(a.getReservedQuantity()).signum() > 0)) {
            throw invalid("该半成品已预留在其他仓库，请选择已预留的半成品仓，不能重复占用另一份库存");
        }
        BigDecimal own = reserved.stream().map(a -> zero(a.getReservedQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return stocks.qualifiedStock(List.of(requirement.getMaterialId())).stream().filter(s -> warehouseId.equals(s.warehouse().getWarehouseId())).map(s -> zero(s.inventory().getAvailableQuantity())).reduce(own, BigDecimal::add);
    }

    private boolean hasFullComponentStock(MfgWorkOrderMaterial requirement, Long onlyMaterial, Long warehouseId, BigDecimal needed, BigDecimal available) {
        if (available.compareTo(needed) >= 0) {
            return true;
        }
        // 半成品只能整份领用，避免组件工单的冻结数量与补产数量不一致。
        BigDecimal lockedElsewhere = jdbc.queryForObject("SELECT COALESCE(SUM(locked_quantity),0) FROM wms_inventory WHERE warehouse_id=? AND material_id=? AND deleted_flag=0", BigDecimal.class, warehouseId, requirement.getMaterialId());
        if (zero(lockedElsewhere).signum() > 0) {
            throw invalid("所选半成品仓的该组件库存已被其他工单预留，本次可用 " + available + "、已预留 " + lockedElsewhere + "、需要 " + needed + "；请先释放其他工单的预留或补充该组件库存，不能重复占用同一份半成品");
        }
        if (available.signum() > 0) {
            throw invalid("半成品余量不足：该组件需要 " + needed + "，半成品仓可用 " + available + "。半成品不足整份需求时不能部分领用，请改选原材料仓申请领原材料生产该组件，或先补足半成品库存");
        }
        if (onlyMaterial != null) {
            throw invalid("所选半成品仓库存不足，需覆盖该组件剩余需求 " + needed + "，可用 0");
        }
        // 此仓没有该组件时，保留原材料领料或组件自产路径。
        return false;
    }

    private void reserveComponent(MfgWorkOrderMaterial requirement, BigDecimal quantity, Long warehouseId, Long actor) {
        var result = allocations.reserve(requirement, quantity, "STOCK_COMPONENT", actor, LocalDateTime.now(), warehouseId);
        if (result.reserved().compareTo(quantity) < 0) {
            throw invalid("半成品库存已变化，本次领用已回滚，请重试");
        }
        jdbc.update("UPDATE mfg_work_order_material SET reserved_quantity=?,updated_by=?,updated_time=NOW(),version=version+1 WHERE requirement_id=? AND deleted_flag=0", result.reserved(), actor, requirement.getRequirementId());
    }

    /**
     * 半成品物料的用料需求由上级工单承担：出库单挂在组件工单下，扣的却是整机工单那条需求。
     * 先看本工单有没有这条物料的需求，没有再落到上级，这样原材料领料仍然扣本工单。
     */
    public Long requirementOwner(Long workOrderId,Long materialId) {
        if(workOrderId==null||materialId==null) return workOrderId;
        if(requirementCount(workOrderId,materialId)>0) return workOrderId;
        MfgWorkOrder order=orders.selectById(workOrderId);
        Long parent=order==null?null:order.getParentWorkOrderId();
        return parent!=null&&requirementCount(parent,materialId)>0?parent:workOrderId;
    }

    private Integer requirementCount(Long workOrderId,Long materialId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order_material WHERE work_order_id=? AND material_id=? AND deleted_flag=0",
                Integer.class,workOrderId,materialId);
    }

    private boolean isComponent(Long material) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mdm_material WHERE material_id=? AND material_type IN ('SEMI','SEMI_FINISHED') AND deleted_flag=0",Integer.class,material)>0;
    }

    private boolean isStockComponent(WmsOutboundDetail line) {
        return isComponent(line.getMaterialId()) && jdbc.queryForObject("""
            SELECT COUNT(*) FROM wms_location l JOIN wms_warehouse w ON w.warehouse_id=l.warehouse_id
            WHERE l.location_id=? AND l.deleted_flag=0 AND w.deleted_flag=0 AND w.warehouse_type IN ('SEMI','SEMI_FINISHED')
            """, Integer.class, line.getLocationId()) > 0;
    }

    private Long scheme(Long material) {
        List<Long> ids = jdbc.queryForList("""
            SELECT s.scheme_id FROM qms_inspection_scheme s JOIN mdm_product p ON p.product_id=s.product_id
            WHERE p.material_id=? AND p.deleted_flag=0 AND p.status='ENABLE'
              AND s.inspection_type='FINAL' AND s.status='RELEASED' AND s.deleted_flag=0
            """, Long.class, material);
        if (ids.size() != 1)
            throw invalid("库存组件缺少唯一有效的终检标准，无法生成领用复验，请先维护组件检验方案");
        return ids.getFirst();
    }

    private BigDecimal productionQuantity(Long parent, Long material) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(w.planned_quantity),0) FROM mfg_work_order w JOIN mdm_product p ON p.product_id=w.product_id
            WHERE w.parent_work_order_id=? AND p.material_id=? AND w.status NOT IN ('CLOSED','CANCELLED','FINISHED') AND w.deleted_flag=0
            """, BigDecimal.class, parent, material);
    }

    public void attachInspections(WmsOutboundOrder order,List<WmsOutboundDetail> lines,Long actor) {
        for(WmsOutboundDetail line:lines) {
            if(!isStockComponent(line)) continue;
            var inspection=inspections.getObject().createStockReleaseInspection(order.getOutboundId(),line.getDetailId(),scheme(line.getMaterialId()),actor);
            line.setInspectionId(inspection.getInspectionId());
            if(details.updateById(line)!=1) throw invalid("领用复验关联保存失败，领料已回滚");
        }
    }

    /** 预览与执行共用的只读领用复验校验，不关闭工单、不改变库存。 */
    public void validateStockRelease(WmsOutboundOrder order, List<WmsOutboundDetail> lines) {
        for (WmsOutboundDetail line : lines) {
            if (isStockComponent(line)) {
                validateStockLine(order, line);
            }
        }
    }

    private void validateStockLine(WmsOutboundOrder order, WmsOutboundDetail line) {
        if (line.getInspectionId() == null)
            throw invalid("库存半成品尚未关联领用检验，请撤回并重新发起领料，不能跳过检验");
        var checked = inspections.getObject().getInspectionOrderById(line.getInspectionId());
        // 拒绝原因必须点名是哪张检验单、卡在哪一步，否则用户不知道要去哪里补检验。
        if (checked == null)
            throw invalid("关联的领用检验单不存在，请撤回领料后重新发起");
        String no = StringUtils.hasText(checked.getInspectionNo()) ? checked.getInspectionNo() : ("#" + checked.getInspectionId());
        if (!"PROCESS".equals(checked.getInspectionType()) || !Objects.equals(order.getWorkOrderId(), checked.getWorkOrderId()) || !Objects.equals(line.getBatchId(), checked.getBatchId()))
            throw invalid("领用检验单 " + no + " 与本次领料的工单或批次不一致，请撤回领料后重新发起");
        if (!"FINISHED".equals(checked.getStatus()))
            throw invalid("领用检验单 " + no + " 尚未提交（当前状态 " + checked.getStatus() + "），请到检验单管理完成逐件检验并提交后再出库或转线边");
        if (!"PASS".equals(checked.getInspectionResult()))
            throw invalid("领用检验单 " + no + " 判定为 " + checked.getInspectionResult() + "，不合格请撤回领料后处理库存或继续原生产安排");
        if (zero(checked.getQualifiedQuantity()).compareTo(line.getOutboundQuantity()) < 0)
            throw invalid("领用检验单 " + no + " 合格数量 " + zero(checked.getQualifiedQuantity()).toPlainString() + " 小于本次出库数量 " + line.getOutboundQuantity().toPlainString() + "，请补齐检验或调整领料数量");
    }

    /** 待检/不合格时不转料、不关闭生产安排；合格后再原子替换尚未执行的组件。 */
    public void requirePassedAndReplace(WmsOutboundOrder order,List<WmsOutboundDetail> lines,Long actor) {
        validateStockRelease(order,lines);
        Set<Long> materials=new LinkedHashSet<>();
        lines.stream().filter(l->l.getInspectionId()!=null).forEach(l->materials.add(l.getMaterialId()));
        for(Long material:materials) {
            // 出库单挂在组件工单下，但半成品的用料需求登记在上级整机工单上，必须按需求归属工单替换。
            Long owner=requirementOwner(order.getWorkOrderId(),material);
            List<Long> children=replacedOrders(owner,material);
            // 必须整种组件被库存覆盖，不能把部分领用误当成全部替代。
            BigDecimal required=jdbc.queryForObject("SELECT COALESCE(SUM(required_quantity-issued_quantity),0) FROM mfg_work_order_material WHERE work_order_id=? AND material_id=? AND deleted_flag=0",BigDecimal.class,owner,material);
            BigDecimal supplied=lines.stream().filter(l->material.equals(l.getMaterialId())).map(WmsOutboundDetail::getOutboundQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(!children.isEmpty() && supplied.compareTo(required)<0) {
                if(required.subtract(supplied).compareTo(productionQuantity(owner,material))<0)
                    throw invalid("部分库存会与既有组件补产数量重叠，请先核对供给安排，不能重复生产");
                jdbc.update("UPDATE mfg_work_order_material SET supply_type='MIXED',updated_by=?,updated_time=NOW(),version=version+1 WHERE work_order_id=? AND material_id=? AND deleted_flag=0",actor,owner,material);
                continue;
            }
            requireUnused(children,order.getOutboundId());
            if(!children.isEmpty()) allocations.releaseForWorkOrders(children,actor,LocalDateTime.now());
            for(Long child:children) closeUnused(child,actor,order.getOutboundNo());
            jdbc.update("UPDATE mfg_work_order_material SET supply_type='STOCK',source_work_order_id=NULL,updated_by=?,updated_time=NOW(),version=version+1 WHERE work_order_id=? AND material_id=? AND deleted_flag=0",actor,owner,material);
        }
        session.clearCache();
    }

    private List<Long> replacedOrders(Long receiver, Long material) {
        List<Long> result = new ArrayList<>(jdbc.queryForList("""
            SELECT w.work_order_id FROM mfg_work_order w JOIN mdm_product p ON p.product_id=w.product_id
            WHERE w.parent_work_order_id=? AND p.material_id=? AND w.status NOT IN ('CLOSED','CANCELLED','FINISHED')
              AND w.deleted_flag=0 ORDER BY w.work_order_id
            """, Long.class, receiver, material));
        for (int i = 0; i < result.size(); i++) {
            for (Long child : jdbc.queryForList("SELECT work_order_id FROM mfg_work_order WHERE parent_work_order_id=? AND status NOT IN ('CLOSED','CANCELLED','FINISHED') AND deleted_flag=0 ORDER BY work_order_id", Long.class, result.get(i))) {
                if (result.contains(child))
                    throw invalid("组件依赖存在循环，不能替换供给");
                if (result.size() >= 200)
                    throw invalid("组件依赖范围过大，请分批核对");
                result.add(child);
            }
        }
        return result;
    }

    private void requireUnused(List<Long> children, Long excludedOutboundId) {
        for (Long child : children) {
            MfgWorkOrder order = orders.selectOne(new LambdaQueryWrapper<MfgWorkOrder>().eq(MfgWorkOrder::getWorkOrderId, child).eq(MfgWorkOrder::getDeletedFlag, 0).last(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "" : "FOR UPDATE"));
            if (order == null || !Set.of("DRAFT", "APPROVED", "RELEASED").contains(order.getStatus()) || hasComponentExecution(child, excludedOutboundId))
                throw invalid("组件工单 " + (order == null ? child : order.getWorkOrderNo()) + " 已领料、开工或已有执行记录，不能直接改用库存；请先按撤回/退料流程处理");
        }
    }

    public void requireNoPendingReplacement(MfgWorkOrder component) {
        if (component == null)
            return;
        // 半成品领料单挂在组件工单自己名下，也可能挂在上级整机工单（历史单据或原材料领料），两处都要查。
        Set<Long> scope = new LinkedHashSet<>();
        scope.add(component.getWorkOrderId());
        if (component.getParentWorkOrderId() != null)
            scope.add(component.getParentWorkOrderId());
        Integer pending = jdbc.queryForObject("""
            SELECT COUNT(*) FROM wms_outbound_detail d JOIN wms_outbound_order o ON o.outbound_id=d.outbound_id
            JOIN mdm_product p ON p.material_id=d.material_id
            WHERE o.work_order_id IN (%s) AND p.product_id=? AND d.inspection_id IS NOT NULL
              AND o.audit_status='SUBMITTED' AND o.deleted_flag=0 AND d.deleted_flag=0
            """.formatted(scope.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))), Integer.class, component.getProductId());
        if (pending > 0)
            throw invalid("该组件正在办理库存半成品领用检验，不能同时生产；先完成领用转线边，或撤回该领料单后恢复生产");
    }

    private void closeUnused(Long id,Long actor,String outboundNo) {
        MfgWorkOrder original=orders.selectById(id);
        jdbc.update("UPDATE mfg_task SET status='CLOSED',version=version+1,updated_by=?,updated_time=NOW() WHERE work_order_id=? AND status='WAIT_START' AND deleted_flag=0",actor,id);
        jdbc.update("UPDATE mfg_dispatch_order SET status='CLOSED',version=version+1,updated_by=?,updated_time=NOW() WHERE work_order_id=? AND deleted_flag=0",actor,id);
        jdbc.update("UPDATE mfg_supply_source SET status='CLOSED',version=version+1,updated_by=?,updated_time=NOW() WHERE work_order_id=? AND deleted_flag=0",actor,id);
        if(jdbc.update("UPDATE mfg_work_order SET status='CLOSED',version=version+1,updated_by=?,updated_time=NOW() WHERE work_order_id=? AND version=? AND deleted_flag=0",actor,id,original.getVersion())!=1)
            throw invalid("组件工单已变化，库存供给切换已回滚");
        jdbc.update("INSERT INTO sys_status_change_log(biz_type,biz_id,old_status,new_status,change_reason,changed_by,changed_time,remark,created_by,created_time,deleted_flag) VALUES('MFG_WORK_ORDER',?,?,'CLOSED','USE_STOCK_COMPONENT',?,NOW(),?,?,NOW(),0)",id,original.getStatus(),actor,"库存领用单 "+outboundNo+" 检验合格并替代生产",actor);
    }

    public void cancel(WmsOutboundOrder order,List<WmsOutboundDetail> lines,Long actor) {
        Set<Long> materials=new LinkedHashSet<>();
        lines.stream().filter(l->l.getInspectionId()!=null).forEach(l->materials.add(l.getMaterialId()));
        if(materials.isEmpty()) return;
        // 领料单挂在组件工单下，半成品的用料需求与预留都在上级整机工单上，按物料回溯归属工单释放。
        Map<Long,List<Long>> requirementsByOwner=new LinkedHashMap<>();
        for(Long material:materials) {
            Long owner=requirementOwner(order.getWorkOrderId(),material);
            List<Long> ids=requirements.selectList(new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                    .eq(MfgWorkOrderMaterial::getWorkOrderId,owner).eq(MfgWorkOrderMaterial::getMaterialId,material)
                    .eq(MfgWorkOrderMaterial::getDeletedFlag,0)).stream().map(MfgWorkOrderMaterial::getRequirementId).toList();
            if(!ids.isEmpty()) requirementsByOwner.computeIfAbsent(owner,k->new ArrayList<>()).addAll(ids);
        }
        requirementsByOwner.forEach((owner,ids)->allocations.releaseForRequirements(owner,ids,actor,LocalDateTime.now()));
        for(var line:lines) if(line.getInspectionId()!=null) {
            jdbc.update("UPDATE qms_inspection_order SET status='CLOSED',updated_by=?,updated_time=NOW(),version=version+1 WHERE inspection_id=? AND status='DRAFT' AND deleted_flag=0",actor,line.getInspectionId());
        }
        session.clearCache();
    }

    private static BigDecimal zero(BigDecimal v) {return v==null?BigDecimal.ZERO:v;}

    /** 已完工，或有完整库存替代证据的组件，才算订单供给完成；普通关闭不放行。 */
    public boolean fulfilledForShipment(MfgWorkOrder order) {
        Set<Long> visited = new HashSet<>();
        MfgWorkOrder current = order;
        boolean fulfilled = false;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if ("FINISHED".equals(current.getStatus())) {
                fulfilled = current == order;
                break;
            }
            MfgWorkOrder parent = stockReplacementParent(current, visited);
            if (parent == null) {
                break;
            }
            if ("CLOSED".equals(parent.getStatus())) {
                current = parent;
            } else {
                fulfilled = hasVerifiedStockSupply(current, parent);
                break;
            }
        }
        return fulfilled;
    }

    private MfgWorkOrder stockReplacementParent(MfgWorkOrder current, Set<Long> visited) {
        if (!isClosedStockComponent(current) || !visited.add(current.getWorkOrderId())) {
            return null;
        }
        var reasons = jdbc.queryForList("SELECT change_reason FROM sys_status_change_log WHERE biz_type='MFG_WORK_ORDER' AND biz_id=? AND new_status='CLOSED' AND deleted_flag=0 ORDER BY log_id DESC LIMIT 1", String.class, current.getWorkOrderId());
        if (reasons.isEmpty() || !"USE_STOCK_COMPONENT".equals(reasons.getFirst())) {
            return null;
        }
        MfgWorkOrder parent = orders.selectById(current.getParentWorkOrderId());
        return parent == null || Integer.valueOf(1).equals(parent.getDeletedFlag()) ? null : parent;
    }

    private boolean isClosedStockComponent(MfgWorkOrder order) {
        return "CLOSED".equals(order.getStatus()) && "COMPONENT".equals(order.getWorkOrderType()) && order.getParentWorkOrderId() != null;
    }

    private boolean hasVerifiedStockSupply(MfgWorkOrder current, MfgWorkOrder parent) {
        Long material = jdbc.queryForObject("SELECT material_id FROM mdm_product WHERE product_id=? AND deleted_flag=0", Long.class, current.getProductId());
        BigDecimal required = jdbc.queryForObject("SELECT COALESCE(SUM(required_quantity),0) FROM mfg_work_order_material WHERE work_order_id=? AND material_id=? AND deleted_flag=0", BigDecimal.class, parent.getWorkOrderId(), material);
        BigDecimal issued = jdbc.queryForObject("SELECT COALESCE(SUM(issued_quantity),0) FROM mfg_work_order_material WHERE work_order_id=? AND material_id=? AND deleted_flag=0", BigDecimal.class, parent.getWorkOrderId(), material);
        if (zero(required).signum() <= 0 || zero(issued).compareTo(required) < 0)
            return false;
        BigDecimal supplied = jdbc.queryForObject("""
            SELECT COALESCE(SUM(d.outbound_quantity),0)
            FROM wms_outbound_detail d JOIN wms_outbound_order o ON o.outbound_id=d.outbound_id
            JOIN qms_inspection_order q ON q.inspection_id=d.inspection_id
            WHERE o.work_order_id IN (?,?) AND o.outbound_type='MATERIAL_ISSUE' AND o.audit_status='APPROVED'
              AND o.deleted_flag=0 AND d.deleted_flag=0 AND d.material_id=?
              AND q.deleted_flag=0 AND q.inspection_type='PROCESS' AND q.status='FINISHED'
              AND q.inspection_result='PASS' AND q.qualified_quantity>=d.outbound_quantity
              AND q.work_order_id=o.work_order_id AND q.batch_id=d.batch_id
              AND EXISTS(SELECT 1 FROM wms_stock_movement m WHERE m.source_order_type='OUTBOUND_ORDER'
                AND m.source_order_id=o.outbound_id AND m.material_id=d.material_id AND m.batch_id=d.batch_id
                AND m.work_order_id=? AND m.movement_type='TRANSFER' AND m.direction='IN' AND m.deleted_flag=0)
            """, BigDecimal.class, current.getWorkOrderId(), parent.getWorkOrderId(), material, parent.getWorkOrderId());
        return zero(supplied).compareTo(required) >= 0;
    }
    private static BusinessException invalid(String message) {return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),message);}

    private boolean hasComponentExecution(Long child, Long excludedOutboundId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM mfg_task WHERE work_order_id=? AND deleted_flag=0
              AND (status NOT IN ('WAIT_START','CLOSED','CANCELLED')
                   OR COALESCE(completed_quantity,0)>0 OR COALESCE(defect_quantity,0)>0)
            """, Integer.class, child) > 0 || hasIssuedComponentMaterial(child) || hasOtherComponentOutbound(child, excludedOutboundId);
    }

    private boolean hasIssuedComponentMaterial(Long child) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mfg_work_order_material WHERE work_order_id=? AND issued_quantity>0 AND deleted_flag=0", Integer.class, child) > 0;
    }

    private boolean hasOtherComponentOutbound(Long child, Long excludedOutboundId) {
        Long excluded = excludedOutboundId == null ? 0L : excludedOutboundId;
        return jdbc.queryForObject("SELECT COUNT(*) FROM wms_outbound_order WHERE work_order_id=? AND audit_status<>'CLOSED' AND deleted_flag=0 AND outbound_id<>?", Integer.class, child, excluded) > 0;
    }
}
