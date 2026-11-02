package com.neuedu.ems.service.mdm;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.vo.mdm.BomExpansion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** 显式引用子 BOM 版本。组件合并后分配；各工单直接用料分别取整，再汇总采购视图。 */
@Service
public class BomExpansionService {
    private final MdmBomMapper boms;
    private final MdmBomItemMapper items;
    private final MdmMaterialMapper materials;
    private final MdmProductMapper products;
    private final MdmUnitMapper units;
    private final BomStockMapper stocks;
    private final MaterialQuantityPolicy quantities;

    public BomExpansionService(MdmBomMapper boms, MdmBomItemMapper items, MdmMaterialMapper materials,
            MdmProductMapper products, MdmUnitMapper units, BomStockMapper stocks, MaterialQuantityPolicy quantities) {
        this.boms = boms; this.items = items; this.materials = materials;
        this.products = products; this.units = units; this.stocks = stocks; this.quantities = quantities;
    }

    // 配置诊断会把校验失败转为 BLOCKED；纯查询不应将调用方事务标记为必须回滚。
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public BomExpansion expand(Long bomId, BigDecimal quantity, LocalDate asOf, boolean useStock) {
        if (bomId == null || quantity == null || quantity.signum() <= 0) throw invalid("请选择 BOM 并输入大于 0 的生产数量");
        Context c = new Context(resolveDate(asOf));
        readGraph(bomId, true, c, new LinkedHashSet<>());
        MdmProduct rootProduct = products.selectById(c.boms.get(bomId).getProductId());
        quantities.validateExecution(material(rootProduct.getMaterialId(), c), quantity);
        List<Long> order = topological(c);
        DemandTotals gross = propagate(bomId, quantity, order, c, false, false, new ArrayList<>());
        DemandTotals grossExecutable = propagate(bomId, quantity, order, c, true, false, new ArrayList<>());
        List<BomExpansion.ComponentDemand> components = new ArrayList<>();
        DemandTotals net = propagate(bomId, quantity, order, c, true, useStock, components);
        Map<Long, BigDecimal> direct = new LinkedHashMap<>();
        for (Arc arc : c.graph.get(bomId)) direct.merge(arc.material.getMaterialId(), amount(quantity, arc.item), BigDecimal::add);
        MdmBom root = c.boms.get(bomId);
        List<BomExpansion.Demand> grossRows = demands(gross.theoretical(), grossExecutable.executable(), c);
        return new BomExpansion(bomId, root.getBomCode(), root.getBomVersion(), c.date, quantity, unitName(material(rootProduct.getMaterialId(),c),c),
                tree(bomId, quantity, c), grossRows, demands(net.theoretical(), net.executable(), c), demands(direct, null, c), components,
                grossRows.size(), grossRows.stream().filter(row -> "RAW".equals(row.materialType())).count(),
                stockNote(useStock));
    }

    /** 未指定分析日期时按当天展开。 */
    private LocalDate resolveDate(LocalDate asOf) {
        return asOf == null ? LocalDate.now() : asOf;
    }

    /** 库存抵扣口径只影响说明文案，不改变展开结果。 */
    private String stockNote(boolean useStock) {
        if (useStock) return "当前半成品仓已放行可用余额，仅用于需求分析，不预留库存；asOf 仅选择 BOM 生效版本";
        return "未抵扣组件库存；递归展开用于需求分析，直接子项用于当前工单领料";
    }

    private void readGraph(Long id, boolean root, Context c, Set<Long> path) {
        assertGraphRange(id, c, path);
        if (c.boms.containsKey(id)) return;
        MdmBom bom = requireExpandableBom(id, root, c);
        material(requireProduct(bom).getMaterialId(), c);
        c.boms.put(id, bom);
        List<Arc> arcs = new ArrayList<>();
        c.graph.put(id, arcs);
        path.add(id);
        for (MdmBomItem item : requireItems(bom)) arcs.add(linkItem(item, c, path));
        path.remove(id);
    }

    /** 循环引用与规模上限在读取任何数据前拦掉。 */
    private void assertGraphRange(Long id, Context c, Set<Long> path) {
        if (path.contains(id)) throw invalid("BOM 存在循环引用：" + path + " → " + id);
        if (path.size() >= 32 || c.boms.size() >= 1000) throw invalid("BOM 层级或节点数超出分析范围");
    }

    /** 读取 BOM 头并校验删除标记、发布状态与生效区间。 */
    private MdmBom requireExpandableBom(Long id, boolean root, Context c) {
        MdmBom bom = boms.selectById(id);
        if (bom == null || !Integer.valueOf(0).equals(bom.getDeletedFlag())) throw invalid("引用 BOM 不存在或已删除：" + id);
        boolean statusAllowed = "RELEASED".equals(bom.getStatus()) || root && "DRAFT".equals(bom.getStatus());
        if (!statusAllowed) throw invalid("子 BOM 必须已发布，根 BOM 必须为草稿或已发布：" + bom.getBomCode());
        if (!effectiveOn(bom, c.date)) throw invalid("BOM 在所选日期不生效：" + bom.getBomCode());
        return bom;
    }

    /** BOM 生效区间是否覆盖分析日期。 */
    private boolean effectiveOn(MdmBom bom, LocalDate date) {
        if (bom.getEffectiveDate() != null && bom.getEffectiveDate().isAfter(date)) return false;
        return bom.getEffectiveTo() == null || !bom.getEffectiveTo().isBefore(date);
    }

    /** 读取 BOM 对应的启用产品。 */
    private MdmProduct requireProduct(MdmBom bom) {
        MdmProduct product = products.selectById(bom.getProductId());
        if (product == null || !Integer.valueOf(0).equals(product.getDeletedFlag()) || !"ENABLE".equals(product.getStatus())) {
            throw invalid("BOM 对应产品不存在或已停用");
        }
        return product;
    }

    /** 取 BOM 的有效明细，空明细不能展开。 */
    private List<MdmBomItem> requireItems(MdmBom bom) {
        List<MdmBomItem> lines = items.activeItems(bom.getBomId());
        if (lines == null || lines.isEmpty()) throw invalid("BOM 没有有效明细：" + bom.getBomCode());
        return lines;
    }

    /** 校验单条明细的用量与损耗率，并递归展开其引用的子 BOM。 */
    private Arc linkItem(MdmBomItem item, Context c, Set<Long> path) {
        validateItemQuantity(item);
        MdmMaterial material = material(item.getMaterialId(), c);
        Long childId = item.getChildBomId();
        if (childId != null) {
            linkChildBom(item, material, childId, c, path);
        } else if ("SEMI_FINISHED".equals(material.getMaterialType())) {
            throw invalid("自制半成品必须明确引用子 BOM：" + material.getMaterialName());
        }
        return new Arc(item, material, childId);
    }

    /** BOM 用量必须大于 0，损耗率必须落在 0 到 1 之间。 */
    private void validateItemQuantity(MdmBomItem item) {
        boolean quantityValid = item.getQuantity() != null && item.getQuantity().signum() > 0;
        boolean lossRateValid = item.getLossRate() == null
                || item.getLossRate().signum() >= 0 && item.getLossRate().compareTo(BigDecimal.ONE) <= 0;
        if (!quantityValid || !lossRateValid) throw invalid("BOM 用量必须大于 0，损耗率必须在 0 到 1 之间");
    }

    /** 校验子 BOM 引用：物料类型、版本唯一性与产出物料一致性。 */
    private void linkChildBom(MdmBomItem item, MdmMaterial material, Long childId, Context c, Set<Long> path) {
        if (!"SEMI_FINISHED".equals(material.getMaterialType())) throw invalid("只有自制半成品物料可以引用子 BOM");
        Long prior = c.componentVersions.putIfAbsent(material.getMaterialId(), childId);
        if (prior != null && !prior.equals(childId)) throw invalid("同一组件在本次需求中引用多个 BOM 版本，无法安全合并库存");
        readGraph(childId, false, c, path);
        MdmProduct childProduct = products.selectById(c.boms.get(childId).getProductId());
        if (!material.getMaterialId().equals(childProduct.getMaterialId())) throw invalid("子 BOM 的产出物料与父项组件不一致");
    }

    private List<Long> topological(Context c) {
        Map<Long, Integer> degree = new LinkedHashMap<>();
        c.graph.keySet().forEach(id -> degree.put(id, 0));
        for (List<Arc> arcs : c.graph.values()) childIds(arcs).forEach(id -> degree.merge(id, 1, Integer::sum));
        ArrayDeque<Long> queue = new ArrayDeque<>();
        degree.forEach((id, n) -> { if (n == 0) queue.add(id); });
        List<Long> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            Long id = queue.remove(); result.add(id);
            childIds(c.graph.get(id)).forEach(child -> {
                if (degree.merge(child, -1, Integer::sum) == 0) queue.add(child);
            });
        }
        if (result.size() != c.graph.size()) throw invalid("BOM 存在循环引用");
        return result;
    }

    /** 一组引用里出现过的子 BOM 编号（去重、忽略叶子物料）。 */
    private java.util.stream.Stream<Long> childIds(List<Arc> arcs) {
        return arcs.stream().map(Arc::childId).filter(Objects::nonNull).distinct();
    }

    private DemandTotals propagate(Long root, BigDecimal quantity, List<Long> order, Context c,
            boolean executable, boolean useStock, List<BomExpansion.ComponentDemand> components) {
        Map<Long, BigDecimal> needs = new HashMap<>(); needs.put(root, quantity);
        Map<Long, BigDecimal> leaves = new LinkedHashMap<>();
        Map<Long, BigDecimal> executedLeaves = new LinkedHashMap<>();
        for (Long id : order) {
            BigDecimal needed = needs.getOrDefault(id, BigDecimal.ZERO);
            BigDecimal produce = needed;
            if (!id.equals(root) && executable) {
                produce = planComponent(id, needed, c, useStock, components);
            }
            Map<Long, BigDecimal> directLeaves = new LinkedHashMap<>();
            for (Arc arc : c.graph.get(id)) {
                BigDecimal amount = amount(produce, arc.item);
                if (arc.childId == null) directLeaves.merge(arc.material.getMaterialId(), amount, BigDecimal::add);
                else needs.merge(arc.childId, amount, BigDecimal::add);
            }
            directLeaves.forEach((materialId, amount) -> {
                leaves.merge(materialId, amount, BigDecimal::add);
                executedLeaves.merge(materialId, quantities.executionDemand(material(materialId, c), amount), BigDecimal::add);
            });
        }
        return new DemandTotals(leaves, executedLeaves);
    }

    /** 计算子件按可执行口径的需求量、可用量与需要生产的数量，并登记组件需求。 */
    private BigDecimal planComponent(Long id, BigDecimal needed, Context c, boolean useStock,
                                     List<BomExpansion.ComponentDemand> components) {
        MdmBom bom = c.boms.get(id);
        MdmMaterial output = material(products.selectById(bom.getProductId()).getMaterialId(), c);
        BigDecimal executableNeed = quantities.executionDemand(output, needed);
        BigDecimal available = useStock ? stocks.availableComponents(output.getMaterialId()) : BigDecimal.ZERO;
        available = available == null ? BigDecimal.ZERO : available.max(BigDecimal.ZERO);
        BigDecimal step = quantities.step(output);
        BigDecimal allocated = executableNeed.min(available).divide(step, 0, RoundingMode.FLOOR).multiply(step);
        BigDecimal produce = executableNeed.subtract(allocated);
        components.add(new BomExpansion.ComponentDemand(id, bom.getBomCode(), output.getMaterialId(),
                output.getMaterialName(), executableNeed, available, allocated, produce));
        return produce;
    }

    private List<BomExpansion.Demand> demands(Map<Long, BigDecimal> values, Map<Long, BigDecimal> execution, Context c) {
        return values.entrySet().stream().map(entry -> demandRow(entry, execution, c))
                .sorted(Comparator.comparing(BomExpansion.Demand::materialCode)).toList();
    }

    /** 组装一行需求汇总，执行数量缺失时按物料策略换算。 */
    private BomExpansion.Demand demandRow(Map.Entry<Long, BigDecimal> entry, Map<Long, BigDecimal> execution, Context c) {
        MdmMaterial m = material(entry.getKey(), c);
        BigDecimal executed = execution == null
                ? quantities.executionDemand(m, entry.getValue())
                : execution.get(entry.getKey());
        quantities.executionDemand(m, executed); // 合计同样必须落在数据库数量范围内。
        return new BomExpansion.Demand(m.getMaterialId(), m.getMaterialCode(), m.getMaterialName(),
                m.getMaterialType(), unitName(m, c), quantities.mode(m), entry.getValue(), executed,
                executed.subtract(entry.getValue()), quantities.step(m));
    }

    private List<BomExpansion.Node> tree(Long id, BigDecimal quantity, Context c) {
        List<BomExpansion.Node> result = new ArrayList<>();
        for (Arc arc : c.graph.get(id)) {
            if (++c.treeNodes > 10000) throw invalid("BOM 展开路径过多，请缩小结构");
            BigDecimal total = amount(quantity, arc.item);
            MdmBom child = arc.childId == null ? null : c.boms.get(arc.childId);
            result.add(new BomExpansion.Node(arc.item.getBomItemId(), arc.material.getMaterialId(),
                    arc.material.getMaterialCode(), arc.material.getMaterialName(), arc.material.getMaterialType(),
                    unitName(arc.material, c), total, arc.childId, childVersion(child),
                    childNodes(arc, total, c)));
        }
        return result;
    }

    /** 子 BOM 版本号，叶子物料没有子 BOM。 */
    private String childVersion(MdmBom child) {
        return child == null ? null : child.getBomVersion();
    }

    /** 子 BOM 的展开节点，叶子物料返回空列表。 */
    private List<BomExpansion.Node> childNodes(Arc arc, BigDecimal total, Context c) {
        return arc.childId == null ? List.of() : tree(arc.childId, total, c);
    }

    private BigDecimal amount(BigDecimal parent, MdmBomItem item) {
        BigDecimal result = parent.multiply(item.getQuantity()).multiply(BigDecimal.ONE.add(
                item.getLossRate() == null ? BigDecimal.ZERO : item.getLossRate()));
        if (result.compareTo(new BigDecimal("99999999999999.9999")) > 0) throw invalid("BOM 理论需求超出数据库数量范围");
        return result;
    }

    private MdmMaterial material(Long id, Context c) {
        if (id == null) throw invalid("BOM 或产品缺少对应物料");
        return c.materials.computeIfAbsent(id, key -> {
            MdmMaterial value = materials.selectById(key);
            if (value == null || !Integer.valueOf(0).equals(value.getDeletedFlag()) || !"ENABLE".equals(value.getStatus())) throw invalid("物料不存在或已停用：" + key);
            quantities.validateDefinition(value);
            return value;
        });
    }

    private String unitName(MdmMaterial m, Context c) {
        if (m.getUnitId() == null) throw invalid("物料缺少计量单位：" + m.getMaterialName());
        MdmUnit unit = c.units.computeIfAbsent(m.getUnitId(), units::selectById);
        if (unit == null || !Integer.valueOf(0).equals(unit.getDeletedFlag()) || !"ENABLE".equals(unit.getStatus())) throw invalid("计量单位不存在或已停用");
        return unit.getUnitName();
    }

    private BusinessException invalid(String message) { return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message); }
    private record Arc(MdmBomItem item, MdmMaterial material, Long childId) {}
    private record DemandTotals(Map<Long, BigDecimal> theoretical, Map<Long, BigDecimal> executable) {}
    private static class Context {
        final LocalDate date;
        final Map<Long, MdmBom> boms = new LinkedHashMap<>();
        final Map<Long, List<Arc>> graph = new LinkedHashMap<>();
        final Map<Long, MdmMaterial> materials = new HashMap<>();
        final Map<Long, MdmUnit> units = new HashMap<>();
        final Map<Long, Long> componentVersions = new HashMap<>();
        int treeNodes;
        Context(LocalDate date) { this.date = date; }
    }
}
