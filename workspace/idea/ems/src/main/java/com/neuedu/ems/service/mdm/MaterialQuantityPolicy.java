package com.neuedu.ems.service.mdm;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** 合并理论需求后按物料粒度取整；不对长度、重量、工时使用全局整数规则。 */
@Service
public class MaterialQuantityPolicy {
    private static final BigDecimal MAX = new BigDecimal("99999999999999.9999");

    public void validateDefinition(MdmMaterial material) {
        if (!Set.of("DISCRETE", "CONTINUOUS").contains(mode(material))) {
            throw invalid("数量类型必须为 DISCRETE 或 CONTINUOUS");
        }
        BigDecimal step = step(material);
        if (step.signum() <= 0 || step.stripTrailingZeros().scale() > 4 || step.compareTo(MAX) > 0) {
            throw invalid("发料粒度必须大于 0，最多四位小数且不超过数据库数量范围");
        }
        if ("DISCRETE".equals(mode(material)) && step.stripTrailingZeros().scale() > 0) {
            throw invalid("离散物料的发料粒度必须为正整数");
        }
    }

    public void validateExecution(MdmMaterial material, BigDecimal quantity) {
        validateDefinition(material);
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(MAX) > 0) {
            throw invalid("执行数量必须大于 0 且不超过数据库数量范围");
        }
        if ("DISCRETE".equals(mode(material)) && quantity.stripTrailingZeros().scale() > 0) {
            throw invalid("离散物料的执行数量必须为整数");
        }
        if (quantity.remainder(step(material)).signum() != 0) {
            throw invalid("执行数量必须是物料最小粒度的整数倍");
        }
    }

    public BigDecimal executionDemand(MdmMaterial material, BigDecimal theoretical) {
        validateDefinition(material);
        if (theoretical == null || theoretical.signum() < 0 || theoretical.compareTo(MAX) > 0) {
            throw invalid("理论需求数量超出有效范围");
        }
        BigDecimal result = theoretical.divide(step(material), 0, RoundingMode.CEILING).multiply(step(material));
        if (result.compareTo(MAX) > 0) throw invalid("取整后需求数量超出数据库范围");
        return result.setScale("DISCRETE".equals(mode(material)) ? 0 : 4);
    }

    public String mode(MdmMaterial material) {
        return material.getQuantityMode() == null ? "CONTINUOUS" : material.getQuantityMode();
    }

    public BigDecimal step(MdmMaterial material) {
        if (material.getIssueStep() != null) {
            return material.getIssueStep();
        }
        return defaultStep(material);
    }

    /** 未配置发放步长时，离散物料按 1、连续物料按 0.0001 计。 */
    private BigDecimal defaultStep(MdmMaterial material) {
        if ("DISCRETE".equals(mode(material))) {
            return BigDecimal.ONE;
        }
        return new BigDecimal("0.0001");
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.PARAM_ERROR.getCode(), message);
    }
}
