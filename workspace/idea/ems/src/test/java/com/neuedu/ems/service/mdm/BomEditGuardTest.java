package com.neuedu.ems.service.mdm;
import com.neuedu.ems.entity.mdm.MdmBom;
import com.neuedu.ems.mapper.mdm.MdmBomMapper;
import com.neuedu.ems.mapper.mdm.MdmBomItemMapper;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;
class BomEditGuardTest {
    @Test void publishedVersionsCannotBeEdited() {
        var boms=mock(MdmBomMapper.class);var items=mock(MdmBomItemMapper.class);
        var bom=new MdmBom();bom.setStatus("RELEASED");when(boms.lockActive(1L)).thenReturn(bom);
        assertThrows(BusinessException.class,()->new BomEditGuard(boms,items).draft(1L));
    }
    @Test void referencedVersionsCannotBeRemoved() {
        var items=mock(MdmBomItemMapper.class);when(items.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class,()->new BomEditGuard(mock(MdmBomMapper.class),items).ensureUnreferenced(1L));
    }
}
