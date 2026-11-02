package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.MdmBom;
import com.neuedu.ems.entity.mdm.MdmBomItem;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmBomMapper;
import com.neuedu.ems.mapper.mdm.MdmBomItemMapper;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmUnitMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.service.mdm.BomMaterialDemandService;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO.MaterialCoverage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 计划阶段的只读备料预览。先使用合格半成品，仅对缺口递归展开，绝不写预留或工单。 */
@Service
@RequiredArgsConstructor
public class PlanMaterialPreviewService {
    private final MdmBomMapper boms;
    private final MdmBomItemMapper items;
    private final MdmMaterialMapper materials;
    private final MdmUnitMapper units;
    private final MdmProductMapper products;
    private final BomMaterialDemandService demands;
    private final ComponentSupplyPlanningService supply;

    public record Preview(List<MaterialCoverage> basicMaterials, List<MaterialCoverage> semiFinishedMaterials,
                          List<String> missing) {}

    public Preview preview(Long productId, List<Map<String, Object>> plans,
                           List<Map<String, Object>> workOrders, List<MaterialCoverage> actual,
                           LocalDateTime asOf) {
        List<MaterialCoverage> basic = new ArrayList<>();
        List<MaterialCoverage> semi = new ArrayList<>();
        actual.forEach(m -> (isSemi(m) ? semi : basic).add(m));
        List<String> missing = new ArrayList<>();
        Set<Long> generatedPlans = new HashSet<>();
        workOrders.stream().filter(w -> "ROOT".equals(w.get("workOrderType")))
                .forEach(w -> generatedPlans.add(id(w.get("planId"))));
        Map<Long, BigDecimal> used = new HashMap<>();
        actual.forEach(m -> used.merge(m.materialId(), m.freeAllocatedQuantity(), BigDecimal::add));
        Map<Long, BigDecimal> pool = new HashMap<>();
        Map<Long, BigDecimal> stock = new HashMap<>();
        for (Map<String, Object> plan : plans) {
            if (!isPreviewablePlan(plan, generatedPlans)) continue;
            appendPlanPreview(productId, plan, asOf, pool, stock, used, basic, semi, missing);
        }
        return new Preview(List.copyOf(basic), List.copyOf(semi), List.copyOf(missing));
    }

    /** 只预演已发布、尚未生成工单的根计划。 */
    private boolean isPreviewablePlan(Map<String, Object> plan, Set<Long> generatedPlans) {
        boolean root = "ROOT".equals(plan.get("planType"));
        boolean generated = generatedPlans.contains(id(plan.get("planId")));
        boolean open = Set.of("RELEASED", "RUNNING", "FINISHED").contains(String.valueOf(plan.get("status")));
        return root && !generated && open;
    }

    /**
     * 单个计划的齐套预览：成功时把基本件与半成品分别并入结果；失败时回滚公共库存池并记入缺失说明，
     * 不影响后续计划的推演。
     */
    private void appendPlanPreview(Long productId, Map<String, Object> plan, LocalDateTime asOf,
                                   Map<Long, BigDecimal> pool, Map<Long, BigDecimal> stock,
                                   Map<Long, BigDecimal> used, List<MaterialCoverage> basic,
                                   List<MaterialCoverage> semi, List<String> missing) {
        String source = "计划 " + plan.get("planNo");
        Map<Long, BigDecimal> before = new HashMap<>(pool);
        List<MaterialCoverage> planBasic = new ArrayList<>(), planSemi = new ArrayList<>();
        try {
            Object plannedStart = plan.get("plannedStartTime");
            LocalDate date = resolvePlanDate(plannedStart, asOf);
            MdmBom root = effectiveBom(productId, date);
            expand(root.getBomId(), new BigDecimal(plan.get("plannedQuantity").toString()),
                    source, pool, stock, used, planBasic, planSemi, new HashSet<>(), date);
            basic.addAll(planBasic);
            semi.addAll(planSemi);
        } catch (BusinessException e) {
            pool.clear(); pool.putAll(before);
            missing.add(source + "的齐套预览未完成：" + e.getMessage());
        }
    }

    /** 取该产品在指定日期生效的已发布BOM；同日多版本无法判定时报错，不猜版本。 */
    private MdmBom effectiveBom(Long productId, LocalDate date) {
        List<MdmBom> roots = boms.selectList(new LambdaQueryWrapper<MdmBom>()
                .eq(MdmBom::getProductId, productId).eq(MdmBom::getStatus, "RELEASED")
                .eq(MdmBom::getDeletedFlag, 0)
                .and(q -> q.isNull(MdmBom::getEffectiveDate).or().le(MdmBom::getEffectiveDate, date))
                .and(q -> q.isNull(MdmBom::getEffectiveTo).or().ge(MdmBom::getEffectiveTo, date))
                .orderByDesc(MdmBom::getEffectiveDate));
        if (roots.isEmpty()) throw invalid("没有生效的已发布BOM");
        if (roots.size() > 1 && Objects.equals(roots.get(0).getEffectiveDate(), roots.get(1).getEffectiveDate()))
            throw invalid("同日存在多份已发布BOM，无法确定版本");
        return roots.get(0);
    }

    private void expand(Long bomId, BigDecimal quantity, String source, Map<Long, BigDecimal> pool,
                        Map<Long, BigDecimal> stock, Map<Long, BigDecimal> used, List<MaterialCoverage> basic, List<MaterialCoverage> semi,
                        Set<Long> ancestors, LocalDate date) {
        if (ancestors.size() >= 8 || !ancestors.add(bomId)) throw invalid("BOM循环引用或层级超过8层");
        MdmBom bom = boms.selectById(bomId);
        if (!isBomUsable(bom, date)) throw invalid("引用BOM不存在、未发布或不在有效期内");
        List<MdmBomItem> direct = items.selectList(new LambdaQueryWrapper<MdmBomItem>()
                .eq(MdmBomItem::getBomId, bomId).eq(MdmBomItem::getDeletedFlag, 0)
                .orderByAsc(MdmBomItem::getSequenceNo, MdmBomItem::getBomItemId));
        Map<Long, Long> children = supply.childBomReferences(direct);
        for (var entry : demands.calculate(direct, quantity).entrySet()) {
            expandMaterial(entry.getKey(), entry.getValue(), source, pool, stock, used, basic, semi,
                    children, ancestors, date);
        }
    }

    /** BOM 必须未删除、已发布，且生效区间覆盖计划日期（边界为空表示不限）。 */
    private boolean isBomUsable(MdmBom bom, LocalDate date) {
        boolean usable = bom != null
                && Integer.valueOf(0).equals(bom.getDeletedFlag())
                && "RELEASED".equals(bom.getStatus());
        return usable && isEffectiveOn(bom, date);
    }

    /** 计划日期落在 BOM 生效区间内；起止为空表示不限制。 */
    private boolean isEffectiveOn(MdmBom bom, LocalDate date) {
        boolean started = bom.getEffectiveDate() == null || !bom.getEffectiveDate().isAfter(date);
        boolean ended = bom.getEffectiveTo() == null || !bom.getEffectiveTo().isBefore(date);
        return started && ended;
    }

    /** 单个直接子件的覆盖计算与展开：有子BOM或本身是半成品进半成品列表，其余进基本件列表。 */
    private void expandMaterial(Long materialId, BigDecimal required, String source, Map<Long, BigDecimal> pool,
                                Map<Long, BigDecimal> stock, Map<Long, BigDecimal> used,
                                List<MaterialCoverage> basic, List<MaterialCoverage> semi,
                                Map<Long, Long> children, Set<Long> ancestors, LocalDate date) {
        MdmMaterial material = materials.selectById(materialId);
        BigDecimal publicStock = stock.computeIfAbsent(materialId, id -> supply.qualifiedAvailable(List.of(id))
                .getOrDefault(id, BigDecimal.ZERO));
        BigDecimal available = pool.computeIfAbsent(materialId, id -> publicStock
                .subtract(used.getOrDefault(id, BigDecimal.ZERO)).max(BigDecimal.ZERO));
        BigDecimal allocated = required.min(available);
        BigDecimal shortage = required.subtract(allocated);
        pool.put(materialId, available.subtract(allocated));
        var unit = material.getUnitId() == null ? null : units.selectById(material.getUnitId());
        String unitName = unit == null ? "" : unit.getUnitName();
        String coverageStatus = coverageStatusOf(shortage, allocated);
        MaterialCoverage row = new MaterialCoverage(null, null, source, materialId,
                material.getMaterialCode(), material.getMaterialName(), material.getMaterialType(), unitName,
                required, BigDecimal.ZERO, BigDecimal.ZERO, allocated, available.subtract(allocated),
                BigDecimal.ZERO, shortage, BigDecimal.ZERO, null,
                coverageStatus, List.of(), List.of(), publicStock, required, BigDecimal.ZERO);
        boolean isChildBom = children.containsKey(materialId);
        if (!isChildBom && !isSemi(row)) {
            basic.add(row);
            return;
        }
        semi.add(row);
        if (isChildBom && shortage.signum() > 0) {
            requireValidChildBom(materialId, children.get(materialId));
            expand(children.get(materialId), shortage, source, pool, stock, used, basic, semi,
                    new HashSet<>(ancestors), date);
        } else if (shortage.signum() > 0) {
            throw invalid("半成品 " + material.getMaterialCode() + " 缺少补产子BOM");
        }
    }

    /** 子BOM的产出必须就是这个组件物料，且对应产品已启用、未删除。 */
    private void requireValidChildBom(Long materialId, Long childBomId) {
        var child = boms.selectById(childBomId);
        var output = child == null || child.getProductId() == null ? null : products.selectById(child.getProductId());
        if (!isEnabledOutput(output, materialId)) throw invalid("子BOM产出与组件物料不匹配或组件产品未启用");
    }

    /** 组件产品存在、未删除、已启用，且其物料就是引用它的组件物料。 */
    private boolean isEnabledOutput(MdmProduct product, Long materialId) {
        if (product == null) {
            return false;
        }
        boolean enabled = Integer.valueOf(0).equals(product.getDeletedFlag())
                && "ENABLE".equals(product.getStatus());
        return enabled && Objects.equals(materialId, product.getMaterialId());
    }

    public static boolean isSemi(MaterialCoverage row) {
        return Set.of("SEMI", "SEMI_FINISHED").contains(row.materialType());
    }

    private static LocalDate resolvePlanDate(Object plannedStart, LocalDateTime asOf) {
        if (plannedStart instanceof LocalDateTime time) {
            return time.toLocalDate();
        }
        if (plannedStart instanceof java.sql.Timestamp stamp) {
            return stamp.toLocalDateTime().toLocalDate();
        }
        return asOf.toLocalDate();
    }

    private static String coverageStatusOf(BigDecimal shortage, BigDecimal allocated) {
        if (shortage.signum() == 0) {
            return "COVERED";
        }
        return allocated.signum() > 0 ? "PARTIAL" : "SHORT";
    }

    private static Long id(Object value) { return value == null ? null : Long.valueOf(value.toString()); }
    private static BusinessException invalid(String message) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message);
    }
}
