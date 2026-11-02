package com.neuedu.ems.service.wms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.wms.WmsLocationMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.factory.FactoryLineMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 专线优先，共享库位兜底；不猜测其他产线的库位。 */
@Service
@RequiredArgsConstructor
public class LineSideLocationService {
    private final WmsLocationMapper locations;
    private final WmsWarehouseMapper warehouses;
    private final MfgWorkOrderMapper workOrders;
    private final MfgProductionPlanMapper plans;
    private final FactoryLineMapper lines;

    public record Preparation(Long workOrderId, String workOrderNo, Long planId, Long lineId,
                              String lineName, Long recommendedLocationId, List<WmsLocation> locations,
                              String explanation, Map<Long,String> warehouseNames) {}

    public Preparation prepare(Long workOrderId) {
        MfgWorkOrder order = workOrders.selectById(workOrderId);
        if (order == null || !Integer.valueOf(0).equals(order.getDeletedFlag())) throw invalid("生产工单不存在");
        var plan = plans.selectById(order.getPlanId());
        if (plan == null || !Integer.valueOf(0).equals(plan.getDeletedFlag())) throw invalid("工单没有有效生产计划");
        var line = lines.selectById(plan.getLineId());
        List<WmsLocation> all = locations.selectList(new LambdaQueryWrapper<WmsLocation>()
                .eq(WmsLocation::getDeletedFlag, 0).eq(WmsLocation::getLocationType, "NORMAL")
                .in(WmsLocation::getStatus, "IDLE", "OCCUPIED", "ENABLE")
                .inSql(WmsLocation::getWarehouseId, "SELECT warehouse_id FROM wms_warehouse WHERE warehouse_type='LINE_SIDE' AND status='ENABLE' AND deleted_flag=0")
                .orderByAsc(WmsLocation::getLocationId));
        var dedicated = all.stream().filter(l -> Objects.equals(l.getLineId(), plan.getLineId())).toList();
        var shared = all.stream().filter(l -> l.getLineId() == null).toList();
        List<WmsLocation> eligible = new ArrayList<>(dedicated);
        eligible.addAll(shared);
        String explanation = locationExplanation(eligible, dedicated);
        Map<Long,String> names = new LinkedHashMap<>();
        if (!eligible.isEmpty()) warehouses.selectBatchIds(eligible.stream().map(WmsLocation::getWarehouseId).distinct().toList())
                .forEach(w -> names.put(w.getWarehouseId(),w.getWarehouseName()));
        Long recommended = recommendLocation(dedicated, shared);
        return new Preparation(order.getWorkOrderId(), order.getWorkOrderNo(), plan.getPlanId(), plan.getLineId(),
                line == null ? "产线未配置" : line.getLineName(), recommended, List.copyOf(eligible), explanation, names);
    }

    /**
     * 只有唯一候选时才预选，多个候选交给现场按实际存放位置挑。
     *
     * <p>本产线专有库位优先；没有专有库位但恰好只有一个共享库位时才推荐共享库位。</p>
     */
    private static Long recommendLocation(List<WmsLocation> dedicated, List<WmsLocation> shared) {
        if (dedicated.size() == 1) {
            return dedicated.getFirst().getLocationId();
        }
        if (dedicated.isEmpty() && shared.size() == 1) {
            return shared.getFirst().getLocationId();
        }
        return null;
    }

    /** 可用线边库位的说明文案，按「无候选 / 仅共享 / 有专有」三档。 */
    private static String locationExplanation(List<WmsLocation> eligible, List<WmsLocation> dedicated) {
        if (eligible.isEmpty()) {
            return "没有本产线或共享线边库位，请先在库位管理维护所属产线";
        }
        if (dedicated.isEmpty()) {
            return "使用共享线边库位；物料仍按本工单独立占用，不与其他工单混用";
        }
        return "优先推荐本产线的线边库位；共享库位也可按实际存放位置选择，物料始终按工单独立占用";
    }

    public void requireAllowed(Long workOrderId, Long locationId) {
        if (prepare(workOrderId).locations().stream().noneMatch(l -> Objects.equals(l.getLocationId(), locationId)))
            throw invalid("目标库位不属于本工单产线，请刷新后选择本产线的线边库位");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), message);
    }
}
