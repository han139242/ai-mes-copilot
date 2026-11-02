package com.neuedu.ems.service.mfg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.entity.mfg.MfgLineProductCapability;
import com.neuedu.ems.entity.process.ProcessRoute;
import com.neuedu.ems.entity.wms.*;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.factory.FactoryLineMapper;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.mapper.mfg.MfgLineProductCapabilityMapper;
import com.neuedu.ems.mapper.process.ProcessRouteMapper;
import com.neuedu.ems.mapper.wms.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 供给分析与组件资源选择。可用量只是规划参考，不表示库存已经预留或领用。 */
@Service
public class ComponentSupplyPlanningService {
    /**
     * 组件能力允许的产能系数上限。
     *
     * <p>组件在整机线上生产时，单件工时显著短于整机（例如灯头 2 分钟、整机装配 6 分钟），
     * 同一条线折算给组件的日产能会高于整机的日产能，系数必须能表达这个倍数差，
     * 所以上限按"最多快 12 倍"给，而不是原来的 2 倍。</p>
     */
    private static final BigDecimal MAX_CAPACITY_FACTOR = new BigDecimal("12");

    private final WmsInventoryMapper inventories;
    private final WmsWarehouseMapper warehouses;
    private final WmsLocationMapper locations;
    private final WmsMaterialBatchMapper batches;
    private final MdmBomMapper boms;
    private final MdmBomItemMapper items;
    private final MdmProductMapper products;
    private final MfgLineProductCapabilityMapper capabilities;
    private final FactoryLineMapper lines;
    private final ProcessRouteMapper routes;

    public ComponentSupplyPlanningService(WmsInventoryMapper inventories, WmsWarehouseMapper warehouses,
            WmsLocationMapper locations, WmsMaterialBatchMapper batches, MdmBomMapper boms,
            MdmBomItemMapper items, MdmProductMapper products, MfgLineProductCapabilityMapper capabilities,
            FactoryLineMapper lines, ProcessRouteMapper routes) {
        this.inventories=inventories; this.warehouses=warehouses; this.locations=locations; this.batches=batches;
        this.boms=boms; this.items=items; this.products=products; this.capabilities=capabilities;
        this.lines=lines; this.routes=routes;
    }

    /** 一条与线边移库来源资格一致的公共库存余额行。 */
    public record QualifiedStock(WmsInventory inventory,WmsWarehouse warehouse,WmsLocation location,
                                 WmsMaterialBatch batch) {}

    /**
     * 逐行列出与线边移库来源资格一致的公共可用库存；不包含锁定量、待检量及其他工单占用。
     *
     * <p>与 {@link #qualifiedAvailable} 资格规则完全一致，但保留仓库/库位/批次/余额行维度，
     * 供库存预留按余额行承诺并解释去向。</p>
     */
    public List<QualifiedStock> qualifiedStock(Collection<Long> materialIds) {
        List<QualifiedStock> result=new ArrayList<>();
        if (materialIds==null || materialIds.isEmpty()) return result;
        Map<Long,WmsWarehouse> warehouseCache=new HashMap<>();
        Map<Long,WmsLocation> locationCache=new HashMap<>();
        Map<Long,WmsMaterialBatch> batchCache=new HashMap<>();
        for (WmsInventory inventory:inventories.selectList(new LambdaQueryWrapper<WmsInventory>()
                .in(WmsInventory::getMaterialId,materialIds).eq(WmsInventory::getWorkOrderId,0L)
                .eq(WmsInventory::getDeletedFlag,0).gt(WmsInventory::getAvailableQuantity,BigDecimal.ZERO)
                .orderByAsc(WmsInventory::getInventoryId))) {
            if (!isCandidateInventory(inventory,materialIds)) continue;
            WmsWarehouse warehouse=cached(warehouseCache,inventory.getWarehouseId(),warehouses::selectById);
            WmsLocation location=cached(locationCache,inventory.getLocationId(),locations::selectById);
            WmsMaterialBatch batch=cached(batchCache,inventory.getBatchId(),batches::selectById);
            if (!isQualifiedSource(warehouse,location,batch,inventory)) continue;
            result.add(new QualifiedStock(inventory,warehouse,location,batch));
        }
        return result;
    }

    /** 余额行本身是否满足候选条件：未删除、公共库存、物料命中、可用量为正、维度齐全。 */
    private boolean isCandidateInventory(WmsInventory inventory,Collection<Long> materialIds) {
        boolean commonStock = active(inventory.getDeletedFlag()) && Long.valueOf(0).equals(inventory.getWorkOrderId());
        boolean materialMatched = materialIds.contains(inventory.getMaterialId());
        return commonStock && materialMatched
                && hasPositiveAvailable(inventory.getAvailableQuantity())
                && hasFullDimensions(inventory);
    }

    /** 可用量必须已填且为正，null 与负数都不算可分配。 */
    private static boolean hasPositiveAvailable(BigDecimal availableQuantity) {
        return availableQuantity != null && availableQuantity.signum() > 0;
    }

    /** 仓库、库位、批次三者齐全才可作为公共库存行参与分配。 */
    private static boolean hasFullDimensions(WmsInventory inventory) {
        return inventory.getWarehouseId() != null
                && inventory.getLocationId() != null
                && inventory.getBatchId() != null;
    }

    /** 仓库、库位、批次三者是否同时满足线边移库来源资格。 */
    private boolean isQualifiedSource(WmsWarehouse warehouse,WmsLocation location,WmsMaterialBatch batch,
                                      WmsInventory inventory) {
        return isQualifiedWarehouse(warehouse)
                && isQualifiedLocation(warehouse,location)
                && isQualifiedBatch(batch,inventory);
    }

    /** 来源仓库必须是启用的原料仓或半成品仓。 */
    private boolean isQualifiedWarehouse(WmsWarehouse warehouse) {
        return warehouse!=null
                && active(warehouse.getDeletedFlag())
                && "ENABLE".equals(warehouse.getStatus())
                && oneOf(warehouse.getWarehouseType(),"RAW","SEMI","SEMI_FINISHED");
    }

    /** 来源库位必须启用且与仓库一致，类型为普通库位。 */
    private boolean isQualifiedLocation(WmsWarehouse warehouse,WmsLocation location) {
        if (location == null || !active(location.getDeletedFlag())) {
            return false;
        }
        boolean usable = oneOf(location.getStatus(), "IDLE", "OCCUPIED", "ENABLE")
                && "NORMAL".equals(location.getLocationType());
        return usable && Objects.equals(warehouse.getWarehouseId(), location.getWarehouseId());
    }

    /** 来源批次必须物料一致、质检放行且状态可用。 */
    private boolean isQualifiedBatch(WmsMaterialBatch batch,WmsInventory inventory) {
        if (batch == null || !active(batch.getDeletedFlag())) {
            return false;
        }
        boolean released = oneOf(batch.getQualityStatus(), "PASS", "CONCESSION")
                && oneOf(batch.getStatus(), "AVAILABLE", "ENABLE");
        return released && Objects.equals(inventory.getMaterialId(), batch.getMaterialId());
    }

    /** 汇总与线边移库来源资格一致的公共可用库存；不包含锁定量、待检量及其他工单占用。 */
    public Map<Long, BigDecimal> qualifiedAvailable(Collection<Long> materialIds) {
        Map<Long, BigDecimal> result=new LinkedHashMap<>();
        for (QualifiedStock stock:qualifiedStock(materialIds)) {
            result.merge(stock.inventory().getMaterialId(),stock.inventory().getAvailableQuantity(),BigDecimal::add);
        }
        return result;
    }

    /** 当前需求仍按物料聚合，不能把不同子版本或库存/补产两种来源静默合并。 */
    public Map<Long,Long> childBomReferences(List<MdmBomItem> directItems) {
        Map<Long,Long> references=new LinkedHashMap<>();
        for (MdmBomItem item:directItems) {
            Long child=normalizeChildBomId(item.getChildBomId());
            ensureSameReference(references,item.getMaterialId(),child);
            references.put(item.getMaterialId(),child);
        }
        references.values().removeIf(Objects::isNull);
        return references;
    }

    /** 非正数的子BOM标识按“无子BOM”处理。 */
    private static Long normalizeChildBomId(Long childBomId) {
        if (childBomId!=null && childBomId<=0) return null;
        return childBomId;
    }

    /** 同一物料在同一批需求里只能引用同一个子BOM或同一种供给类型。 */
    private void ensureSameReference(Map<Long,Long> references,Long materialId,Long child) {
        if (!references.containsKey(materialId)) return;
        if (!Objects.equals(references.get(materialId),child)) {
            throw invalid("同一物料存在不同子BOM或供给类型，请拆分需求来源后再生成工单");
        }
    }

    /**
     * 组件可用生产资源。
     *
     * @param capacityFactor 这条产线折算给该组件产品的日产能倍数（能力行上的 capacity_factor）；
     *                       同一条线做整机与做半成品的单件工时不同，靠它把日产能换算成组件件数
     */
    public record ComponentResources(MdmProduct product,MdmBom bom,FactoryLine line,ProcessRoute route,
                                     BigDecimal capacityFactor) {

        /** 供只关心产线/路线的调用方使用，能力系数按 1（不放大）处理。 */
        public ComponentResources(MdmProduct product,MdmBom bom,FactoryLine line,ProcessRoute route) {
            this(product,bom,line,route,BigDecimal.ONE);
        }
    }

    /** 使用父BOM明确引用的子版本，并且只选择组件自身有效的产线、路线和正产能。 */
    public ComponentResources resolve(Long parentBomId,Long materialId,LocalDate plannedDate) {
        return resolve(parentBomId, materialId, plannedDate, null);
    }

    /**
     * 使用父BOM明确引用的子版本，并优先在父计划所在产线上找到该组件的生产能力。
     *
     * <p>一个订单只排进一条产线，就应当在这条产线上把整台产品做出来：灯头、灯臂、底座等组件
     * 与整机共用同一条线、同时开工，每产出一套立刻进入装配。因此这里先按 {@code preferredLineId}
     * 这个"产线 + 产品"组合找可用能力；只有该产线确实没有这个组件的能力时，才回退到组件自身
     * 配置的产线，避免把订单拆散到别的产线上占产能。</p>
     *
     * @param preferredLineId 希望优先使用的产线（通常是父计划所在产线），为 null 时按组件自身能力选线
     */
    public ComponentResources resolve(Long parentBomId,Long materialId,LocalDate plannedDate,Long preferredLineId) {
        List<MdmBomItem> directItems=items.selectList(new LambdaQueryWrapper<MdmBomItem>()
                .eq(MdmBomItem::getBomId,parentBomId).eq(MdmBomItem::getMaterialId,materialId)
                .eq(MdmBomItem::getDeletedFlag,0));
        Long childBomId=childBomReferences(directItems).get(materialId);
        if (childBomId==null) throw invalid("组件需求缺少父BOM明确引用的子版本");
        MdmBom bom=requireEffectiveBom(childBomId,plannedDate);
        MdmProduct product=requireComponentProduct(bom,materialId);
        if (preferredLineId!=null) {
            ComponentResources onPreferredLine=selectOnLine(product,bom,preferredLineId);
            if (onPreferredLine!=null) return onPreferredLine;
        }
        List<MfgLineProductCapability> candidates=loadCapabilities(product.getProductId());
        ComponentResources selected=selectByPriority(candidates,product,bom);
        if (selected==null) throw invalid("组件缺少启用产线、已发布自身路线或有效正产能，不能回退父产线");
        return selected;
    }

    /**
     * 在指定产线上为组件挑一条可用路线；该产线没有配置这个组件的能力就返回 null。
     *
     * <p>同一产线上该组件有多条可用路线时，取优先级最高（{@code priorityLevel} 最小）且唯一的那条，
     * 与 {@link #selectByPriority} 的口径保持一致。</p>
     */
    private ComponentResources selectOnLine(MdmProduct product,MdmBom bom,Long lineId) {
        List<MfgLineProductCapability> candidates=new ArrayList<>(capabilities.selectList(
                new LambdaQueryWrapper<MfgLineProductCapability>()
                        .eq(MfgLineProductCapability::getLineId,lineId)
                        .eq(MfgLineProductCapability::getProductId,product.getProductId())
                        .eq(MfgLineProductCapability::getDeletedFlag,0)));
        candidates.sort(Comparator.comparing(MfgLineProductCapability::getPriorityLevel,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return selectByPriority(candidates,product,bom);
    }

    /** 子BOM必须已发布、未删除且在计划日期有效期内。 */
    private MdmBom requireEffectiveBom(Long childBomId,LocalDate plannedDate) {
        MdmBom bom=boms.selectById(childBomId);
        boolean effectiveDateMissing=bom==null || bom.getEffectiveDate()==null;
        if (effectiveDateMissing) throw invalid("组件子BOM未发布、已删除或不在计划日期有效期内");
        boolean notReleased=!active(bom.getDeletedFlag()) || !"RELEASED".equals(bom.getStatus());
        boolean outOfRange=bom.getEffectiveDate().isAfter(plannedDate)
                || (bom.getEffectiveTo()!=null && bom.getEffectiveTo().isBefore(plannedDate));
        if (notReleased || outOfRange) throw invalid("组件子BOM未发布、已删除或不在计划日期有效期内");
        return bom;
    }

    /** 子BOM对应的产品必须启用，且产出物料与需求物料一致。 */
    private MdmProduct requireComponentProduct(MdmBom bom,Long materialId) {
        MdmProduct product=products.selectById(bom.getProductId());
        if (product==null) throw invalid("组件子BOM的产品与需求物料不匹配或产品未启用");
        boolean enabled=active(product.getDeletedFlag()) && "ENABLE".equals(product.getStatus());
        if (!enabled || !Objects.equals(materialId,product.getMaterialId())) {
            throw invalid("组件子BOM的产品与需求物料不匹配或产品未启用");
        }
        return product;
    }

    /** 读取组件已启用的产线能力，并按优先级升序排列。 */
    private List<MfgLineProductCapability> loadCapabilities(Long productId) {
        List<MfgLineProductCapability> candidates=new ArrayList<>(capabilities.selectList(
                new LambdaQueryWrapper<MfgLineProductCapability>()
                        .eq(MfgLineProductCapability::getProductId,productId)
                        .eq(MfgLineProductCapability::getStatus,"ENABLE")
                        .eq(MfgLineProductCapability::getDeletedFlag,0)));
        candidates.sort(Comparator.comparing(MfgLineProductCapability::getPriorityLevel,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return candidates;
    }

    /**
     * 按优先级选择组件产线与路线：只接受同一最高优先级下唯一的一对产线/路线。
     *
     * @return 选中的组件资源；没有任何可用能力时返回 null
     */
    private ComponentResources selectByPriority(List<MfgLineProductCapability> candidates,MdmProduct product,
                                                MdmBom bom) {
        ComponentResources selected=null;
        Integer priority=null;
        for (MfgLineProductCapability capability:candidates) {
            if (!isUsableCapability(capability,product.getProductId())) continue;
            FactoryLine line=lines.selectById(capability.getLineId());
            ProcessRoute route=routes.selectById(capability.getRouteId());
            if (!isUsableLine(line) || !isUsableRoute(route,product.getProductId())) continue;
            if (selected==null) {
                selected=new ComponentResources(product,bom,line,route,capability.getCapacityFactor());
                priority=capability.getPriorityLevel();
                continue;
            }
            if (!Objects.equals(priority,capability.getPriorityLevel())) break;
            if (!sameResource(selected,line,route)) {
                throw invalid("组件存在多个同优先级产线或路线，请先明确组件能力优先级");
            }
            if (selected.capacityFactor().compareTo(capability.getCapacityFactor())!=0) {
                throw invalid("组件同一产线存在多个不同产能系数的能力配置，请先明确组件能力优先级");
            }
        }
        return selected;
    }

    /** 能力行必须启用、属于该产品、优先级与产能系数有效且指向产线和路线。 */
    private boolean isUsableCapability(MfgLineProductCapability capability,Long productId) {
        boolean usableRow = active(capability.getDeletedFlag()) && "ENABLE".equals(capability.getStatus());
        return usableRow && matchesProduct(capability, productId) && hasValidBinding(capability);
    }

    /** 能力行必须指向该产品，且优先级与产能系数有效。 */
    private boolean matchesProduct(MfgLineProductCapability capability, Long productId) {
        return Objects.equals(productId, capability.getProductId())
                && capability.getPriorityLevel() != null
                && hasValidCapacityFactor(capability.getCapacityFactor());
    }

    /** 能力行必须同时绑定产线与工艺路线。 */
    private boolean hasValidBinding(MfgLineProductCapability capability) {
        return capability.getLineId() != null && capability.getRouteId() != null;
    }

    /** 产能系数必须落在 (0, MAX_CAPACITY_FACTOR] 区间内。 */
    private static boolean hasValidCapacityFactor(BigDecimal capacityFactor) {
        return capacityFactor != null
                && capacityFactor.signum() > 0
                && capacityFactor.compareTo(MAX_CAPACITY_FACTOR) <= 0;
    }

    /** 产线必须启用且有正日产能。 */
    private boolean isUsableLine(FactoryLine line) {
        if (line == null || !active(line.getDeletedFlag())) {
            return false;
        }
        boolean capacityReady = line.getDailyCapacity() != null && line.getDailyCapacity().signum() > 0;
        return capacityReady && oneOf(line.getStatus(), "ENABLE", "RUNNING");
    }

    /** 路线必须已发布且属于同一产品。 */
    private boolean isUsableRoute(ProcessRoute route,Long productId) {
        return route!=null
                && active(route.getDeletedFlag())
                && "RELEASED".equals(route.getStatus())
                && Objects.equals(productId,route.getProductId());
    }

    /** 同优先级的两条候选能力是否指向同一产线与同一路线。 */
    private static boolean sameResource(ComponentResources selected,FactoryLine line,ProcessRoute route) {
        return Objects.equals(selected.line().getLineId(),line.getLineId())
                && Objects.equals(selected.route().getRouteId(),route.getRouteId());
    }

    private static boolean active(Integer flag) { return Integer.valueOf(0).equals(flag); }
    private static boolean oneOf(String value,String... allowed) {
        return value!=null && Arrays.asList(allowed).contains(value);
    }
    /** 一次性加载并缓存：同一个 id 只查一次库，未命中时用 loader 补。 */
    private static <T> T cached(Map<Long,T> cache,Long id,java.util.function.Function<Long,T> loader) {
        return cache.computeIfAbsent(id, loader);
    }
    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),message);
    }
}
