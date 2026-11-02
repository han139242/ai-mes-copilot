package com.neuedu.ems.service.mdm;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

/** 生产需求按产品对应物料的数量口径校验，PC 和直接 API 共用。 */
@Service
public class ProductQuantityService {
    private final MdmProductMapper products;
    private final MdmMaterialMapper materials;
    private final MaterialQuantityPolicy policy;
    public ProductQuantityService(MdmProductMapper products,MdmMaterialMapper materials,MaterialQuantityPolicy policy) {
        this.products=products;this.materials=materials;this.policy=policy;
    }
    public void validate(Long productId,BigDecimal quantity) {
        validate(productId,quantity,false);
    }
    public void validate(Long productId,BigDecimal quantity,boolean allowZero) {
        var product=products.selectById(productId);
        if (product==null || !Integer.valueOf(0).equals(product.getDeletedFlag()) || !"ENABLE".equals(product.getStatus()))
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),"生产产品不存在或已停用");
        var material=materials.selectById(product.getMaterialId());
        if (material==null || !Integer.valueOf(0).equals(material.getDeletedFlag()) || !"ENABLE".equals(material.getStatus()))
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),"产品对应物料不存在或已停用");
        if(allowZero && quantity!=null && quantity.signum()==0)policy.validateDefinition(material);
        else policy.validateExecution(material,quantity);
    }
}
