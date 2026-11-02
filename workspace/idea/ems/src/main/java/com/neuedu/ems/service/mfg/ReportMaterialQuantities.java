package com.neuedu.ems.service.mfg;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgReportMaterial;
import com.neuedu.ems.exception.BusinessException;
import java.math.BigDecimal;

/** 新客户端按含报废总量填报，归一为兼容历史数据的非报废量+报废量。 */
public final class ReportMaterialQuantities {
    private ReportMaterialQuantities() {}

    public static void normalize(MfgReportMaterial row) {
        if (!row.hasDeclaredTotalUsedQuantity()) return;
        BigDecimal total = row.getTotalUsedQuantity();
        BigDecimal scrap = row.getScrapQuantity() == null ? BigDecimal.ZERO : row.getScrapQuantity();
        if (total.signum() < 0 || scrap.signum() < 0 || scrap.compareTo(total) > 0) {
            throw invalid("其中报废数量不能大于使用总量，数量均须非负");
        }
        BigDecimal nonScrap = total.subtract(scrap);
        if (row.getUsedQuantity() != null && row.getUsedQuantity().compareTo(nonScrap) != 0) {
            throw invalid("使用总量与旧口径用量不一致，请填写使用总量及其中报废数量");
        }
        row.setUsedQuantity(nonScrap);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ResultCode.PARAM_ERROR.getCode(), message);
    }
}
