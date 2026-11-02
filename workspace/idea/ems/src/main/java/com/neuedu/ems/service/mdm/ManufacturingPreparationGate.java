package com.neuedu.ems.service.mdm;

import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 受控产品的执行前校验。
 *
 * <p>截至 V1.8 执行链已贯通：工单生成时冻结 BOM/路线/参数/检验依据快照，领料确认时成对移库到线边仓
 * 并按工单占用，报工审核扣减线边占用并形成产出，终检放行后由生产入库转入成品仓。
 * 因此这里不再拦截受控产品，只保留「产品可用」这一层前置校验；
 * 数量、库存、批次、权限等业务约束由各执行命令在事务内自行校验。</p>
 */
@Service
public class ManufacturingPreparationGate {
    private final MdmProductMapper products;

    public ManufacturingPreparationGate(MdmProductMapper products) {
        this.products = products;
    }

    public void assertExecutionSupported(Long productId) {
        if (productId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "产品不能为空");
        }
        var product = products.selectById(productId);
        if (product == null || !Integer.valueOf(0).equals(product.getDeletedFlag())) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "产品不存在或已删除");
        }
        if (!"ENABLE".equals(product.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "产品未启用，不能下达生产");
        }
        if (product.getMaterialId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "产品未关联成品物料，不能下达生产");
        }
    }
}
