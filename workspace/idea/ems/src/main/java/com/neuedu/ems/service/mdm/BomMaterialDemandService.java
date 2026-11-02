package com.neuedu.ems.service.mdm;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.MdmBomItem;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一个工单的直接需求：先汇总完整精度的理论用量，再按物料最小粒度向上取整。 */
@Service
@RequiredArgsConstructor
public class BomMaterialDemandService {
    private final MdmMaterialMapper materials;
    private final MaterialQuantityPolicy quantities;

    public Map<Long, BigDecimal> calculate(List<MdmBomItem> items, BigDecimal plannedQuantity) {
        if (plannedQuantity == null || plannedQuantity.signum() <= 0 || items == null || items.isEmpty()) {
            throw invalid("生产数量必须大于0，且BOM须有有效直接用料");
        }
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        for (MdmBomItem item : items) {
            BigDecimal loss = lossOf(item);
            requireValidItem(item, loss);
            totals.merge(item.getMaterialId(), demandOf(item, plannedQuantity, loss), BigDecimal::add);
        }
        totals.replaceAll((id, amount) -> {
            var material = materials.selectById(id);
            if (material == null || !Integer.valueOf(0).equals(material.getDeletedFlag())
                    || !"ENABLE".equals(material.getStatus())) throw invalid("BOM引用物料不存在或未启用");
            return quantities.executionDemand(material, amount);
        });
        return totals;
    }

    /** 未配置损耗率按 0 处理。 */
    private BigDecimal lossOf(MdmBomItem item) {
        return item.getLossRate() == null ? BigDecimal.ZERO : item.getLossRate();
    }

    /** 直接物料与用量必须有效，损耗率须落在 0 到 1 之间。 */
    private void requireValidItem(MdmBomItem item, BigDecimal loss) {
        boolean validQuantity = item.getQuantity() != null && item.getQuantity().signum() > 0;
        boolean validLoss = loss.signum() >= 0 && loss.compareTo(BigDecimal.ONE) <= 0;
        if (item.getMaterialId() == null || !validQuantity || !validLoss) {
            throw invalid("BOM物料与用量必须有效，损耗率须在0到1之间");
        }
    }

    /** 单条物料的含损耗需求数量。 */
    private BigDecimal demandOf(MdmBomItem item, BigDecimal plannedQuantity, BigDecimal loss) {
        BigDecimal baseQuantity = plannedQuantity.multiply(item.getQuantity());
        return baseQuantity.multiply(BigDecimal.ONE.add(loss));
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.PARAM_ERROR.getCode(), message);
    }
}
