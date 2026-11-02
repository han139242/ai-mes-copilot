package com.neuedu.ems.service.mdm;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ManufacturingExecutionGateTest {
    /**
     * V1.8 起执行链已贯通（快照冻结、线边移库、投料消耗、全检适配），
     * 受控产品可以进入执行环节，前置校验只校验产品本身可用。
     */
    @Test void enabledControlledProductEntersExecutionChain() {
        var mapper=mock(MdmProductMapper.class); var product=product(1);
        when(mapper.selectById(1L)).thenReturn(product);
        assertDoesNotThrow(()->new ManufacturingPreparationGate(mapper).assertExecutionSupported(1L));
    }
    @Test void validHistoricalProductRemainsExecutable() {
        var mapper=mock(MdmProductMapper.class); when(mapper.selectById(1L)).thenReturn(product(0));
        assertDoesNotThrow(()->new ManufacturingPreparationGate(mapper).assertExecutionSupported(1L));
    }
    @Test void missingProductMaterialOrDisabledStatusAreRejected() {
        var mapper=mock(MdmProductMapper.class);
        assertThrows(BusinessException.class,()->new ManufacturingPreparationGate(mapper).assertExecutionSupported(null));
        assertThrows(BusinessException.class,()->new ManufacturingPreparationGate(mapper).assertExecutionSupported(1L));
        var noMaterial=product(1); noMaterial.setMaterialId(null);
        when(mapper.selectById(2L)).thenReturn(noMaterial);
        var error=assertThrows(BusinessException.class,()->new ManufacturingPreparationGate(mapper).assertExecutionSupported(2L));
        assertTrue(error.getMessage().contains("成品物料"));
        var disabled=product(1); disabled.setStatus("DISABLE");
        when(mapper.selectById(3L)).thenReturn(disabled);
        assertThrows(BusinessException.class,()->new ManufacturingPreparationGate(mapper).assertExecutionSupported(3L));
    }
    private MdmProduct product(int controlled) {
        var product=new MdmProduct(); product.setStatus("ENABLE"); product.setDeletedFlag(0);
        product.setMaterialId(1L); product.setPreparationControlled(controlled); return product;
    }
}
