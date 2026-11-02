package com.neuedu.ems.service.mdm;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ProductQuantityServiceTest {
    @Test void rejectsFractionalLampsButKeepsContinuousProducts() {
        var products=mock(MdmProductMapper.class);var materials=mock(MdmMaterialMapper.class);
        MdmProduct product=new MdmProduct();product.setProductId(1L);product.setMaterialId(2L);product.setStatus("ENABLE");product.setDeletedFlag(0);
        MdmMaterial material=new MdmMaterial();material.setMaterialId(2L);material.setStatus("ENABLE");material.setDeletedFlag(0);material.setQuantityMode("DISCRETE");material.setIssueStep(BigDecimal.ONE);
        when(products.selectById(1L)).thenReturn(product);when(materials.selectById(2L)).thenReturn(material);
        var service=new ProductQuantityService(products,materials,new MaterialQuantityPolicy());
        assertThrows(BusinessException.class,()->service.validate(1L,new BigDecimal("1.5")));
        assertDoesNotThrow(()->service.validate(1L,new BigDecimal("10")));
        material.setQuantityMode("CONTINUOUS");material.setIssueStep(new BigDecimal("0.0001"));
        assertDoesNotThrow(()->service.validate(1L,new BigDecimal("1.125")));
        assertThrows(BusinessException.class,()->service.validate(3L,BigDecimal.ONE));
    }
}
