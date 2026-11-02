package com.neuedu.ems.service.mdm;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 正式业务写入前统一校验数量，不修约用户输入，不影响长度、重量等连续量。 */
@Service
@RequiredArgsConstructor
public class MaterialExecutionQuantityService {
    private final MdmMaterialMapper materials;
    private final MaterialQuantityPolicy policy;

    public void validate(Long materialId, BigDecimal quantity, boolean allowZero) {
        var material = materialId == null ? null : materials.selectById(materialId);
        validateMaterial(material, quantity, allowZero);
    }

    /** 库存写命令等待业务锁后，读取并锁定最新物料粒度，避免使用事务早期快照。 */
    public void validateForUpdate(Long materialId, BigDecimal quantity, boolean allowZero) {
        var material = materialId == null ? null : materials.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.neuedu.ems.entity.mdm.MdmMaterial>()
                .eq(com.neuedu.ems.entity.mdm.MdmMaterial::getMaterialId, materialId).last("FOR UPDATE"));
        validateMaterial(material, quantity, allowZero);
    }

    private void validateMaterial(com.neuedu.ems.entity.mdm.MdmMaterial material, BigDecimal quantity, boolean allowZero) {
        if (material == null || !Integer.valueOf(0).equals(material.getDeletedFlag())
                || !"ENABLE".equals(material.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "执行物料不存在或未启用");
        }
        if (allowZero && quantity != null && quantity.signum() == 0) {
            policy.validateDefinition(material);
        } else {
            policy.validateExecution(material, quantity);
        }
    }
}
