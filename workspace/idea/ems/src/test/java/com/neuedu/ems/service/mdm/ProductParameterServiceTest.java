package com.neuedu.ems.service.mdm;

import com.neuedu.ems.entity.mdm.MdmProductParameter;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.exception.*;
import com.neuedu.ems.mapper.mdm.MdmProductParameterMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductParameterServiceTest {
    private final MdmProductParameterMapper mapper=mock(MdmProductParameterMapper.class);
    private final SysOperationLogMapper logs=mock(SysOperationLogMapper.class);
    private final ProductParameterService service=new ProductParameterService(mapper,logs,new PreparationValuePolicy());
    private MdmProductParameter row;
    @BeforeEach void setup(){
        row=new MdmProductParameter();row.setParameterId(1L);row.setVersion(3);row.setStatus("DRAFT");
        row.setValueType("DECIMAL");row.setParameterValue("12");row.setSourceReference("Q/LAMP-001-V0.1");
        when(mapper.lockActive(1L)).thenReturn(row);when(mapper.selectById(1L)).thenReturn(row);
        when(mapper.updateById(any(MdmProductParameter.class))).thenReturn(1);when(logs.insert(any(SysOperationLog.class))).thenReturn(1);
    }
    @Test void staleEditCannotOverwriteCurrentValue(){
        assertThrows(OptimisticLockConflictException.class,()->service.edit(1L,new ProductParameterService.Edit(2,"15","版本2"),1L));
        verify(mapper,never()).updateById(any(MdmProductParameter.class));
    }
    @Test void confirmationChecksContentAndWritesAudit(){
        service.review(1L,new ProductParameterService.Review(3),1L,true);
        verify(logs).insert(any(SysOperationLog.class));
    }
    @Test void placeholderConfirmationDoesNotMutate(){
        row.setSourceReference("待确认");
        assertThrows(BusinessException.class,()->service.review(1L,new ProductParameterService.Review(3),1L,true));
        verify(mapper,never()).updateById(any(MdmProductParameter.class));
    }
    @Test void confirmedParameterRequiresExplicitReopen(){
        row.setStatus("CONFIRMED");
        assertThrows(BusinessException.class,()->service.edit(1L,new ProductParameterService.Edit(3,"15","版本2"),1L));
        service.review(1L,new ProductParameterService.Review(3),1L,false);
    }
    @Test void failedAuditDoesNotReportSuccess(){
        when(logs.insert(any(SysOperationLog.class))).thenReturn(0);
        assertThrows(BusinessException.class,()->service.review(1L,new ProductParameterService.Review(3),1L,true));
    }
}
