package com.neuedu.ems.service.mdm;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.MdmProductParameter;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.exception.OptimisticLockConflictException;
import com.neuedu.ems.mapper.mdm.MdmProductParameterMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class ProductParameterService {
    private final MdmProductParameterMapper mapper;
    private final SysOperationLogMapper logs;
    private final PreparationValuePolicy policy;
    public ProductParameterService(MdmProductParameterMapper mapper,SysOperationLogMapper logs,PreparationValuePolicy policy){
        this.mapper=mapper;this.logs=logs;this.policy=policy;
    }
    public record Edit(Integer version,String parameterValue,String sourceReference){}
    public record Review(Integer version){}

    @Transactional(rollbackFor=Exception.class)
    public MdmProductParameter edit(Long id,Edit request,Long actor) {
        if(request==null)throw invalid("参数内容不能为空");
        MdmProductParameter row=locked(id,request.version());
        if(!"DRAFT".equals(row.getStatus()))throw invalid("已确认参数请先退回草稿，再修改并重新确认");
        String value=bounded(request.parameterValue(),255,"参数值");
        String source=bounded(request.sourceReference(),500,"参数依据");
        // 草稿可以留空，确认命令才要求完整依据。
        var update=new MdmProductParameter();update.setParameterId(id);update.setVersion(row.getVersion());
        update.setParameterValue(value);update.setSourceReference(source);update.setUpdatedBy(actor);
        requireUpdated(mapper.updateById(update));audit(id,"EDIT_PARAMETER",actor);
        return mapper.selectById(id);
    }

    @Transactional(rollbackFor=Exception.class)
    public MdmProductParameter review(Long id,Review request,Long actor,boolean confirm) {
        if(request==null)throw invalid("请提交当前版本号");
        MdmProductParameter row=locked(id,request.version());
        String expected=confirm?"DRAFT":"CONFIRMED";
        if(!expected.equals(row.getStatus()))throw invalid("参数状态已变化，请刷新后重试");
        if(confirm)policy.validateParameter(row.getValueType(),row.getParameterValue(),row.getSourceReference());
        var update=new MdmProductParameter();update.setParameterId(id);update.setVersion(row.getVersion());
        update.setStatus(confirm?"CONFIRMED":"DRAFT");update.setUpdatedBy(actor);
        requireUpdated(mapper.updateById(update));audit(id,confirm?"CONFIRM_PARAMETER":"REOPEN_PARAMETER",actor);
        return mapper.selectById(id);
    }
    private MdmProductParameter locked(Long id,Integer version) {
        var row=mapper.lockActive(id);
        if(row==null)throw invalid("产品参数不存在或已删除");
        if(version==null || !version.equals(row.getVersion()))throw new OptimisticLockConflictException();
        return row;
    }
    private String bounded(String value,int maximum,String label){
        String text=value==null?"":value.trim();if(text.length()>maximum)throw invalid(label+"长度超出限制");return text;
    }
    private void requireUpdated(int count){if(count!=1)throw new OptimisticLockConflictException();}
    private void audit(Long id,String action,Long actor){
        if(actor==null)throw invalid("操作人不能为空");
        SysOperationLog log=new SysOperationLog();log.setUserId(actor);log.setOperationType(action);
        log.setModuleCode("MDM_PRODUCT_PARAMETER");log.setBizId(id);log.setOperationTime(LocalDateTime.now());
        log.setRemark("受控参数版本变更，依据与内容保存于参数记录");log.setDeletedFlag(0);
        if(logs.insert(log)!=1)throw new BusinessException(ResultCode.FAILED);
    }
    private BusinessException invalid(String message){return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),message);}
}
