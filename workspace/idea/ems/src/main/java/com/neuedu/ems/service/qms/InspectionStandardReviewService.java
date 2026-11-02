package com.neuedu.ems.service.qms;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.qms.*;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.exception.OptimisticLockConflictException;
import com.neuedu.ems.mapper.qms.*;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.service.mdm.PreparationValuePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class InspectionStandardReviewService {
    private final QmsInspectionStandardMapper standards;
    private final QmsInspectionSchemeMapper schemes;
    private final QmsInspectionSchemeItemMapper items;
    private final MdmProductMapper products;
    private final QmsInspectionItemMapper inspectionItems;
    private final com.neuedu.ems.mapper.mdm.MdmUnitMapper units;
    private final SysOperationLogMapper logs;
    private final PreparationValuePolicy policy;
    public InspectionStandardReviewService(QmsInspectionStandardMapper standards,QmsInspectionSchemeMapper schemes,
            QmsInspectionSchemeItemMapper items,MdmProductMapper products,QmsInspectionItemMapper inspectionItems,
            com.neuedu.ems.mapper.mdm.MdmUnitMapper units,SysOperationLogMapper logs,PreparationValuePolicy policy){
        this.standards=standards;this.schemes=schemes;this.items=items;this.products=products;this.inspectionItems=inspectionItems;this.units=units;this.logs=logs;this.policy=policy;
    }
    public boolean controlled(Long productId){
        if(productId==null)return false;
        var product=products.selectById(productId);
        return product!=null && Integer.valueOf(1).equals(product.getPreparationControlled());
    }
    public void requireDraft(QmsInspectionStandard row){
        if("CONFIRMED".equals(row.getConfirmationStatus()))throw invalid("已确认检验标准不可直接修改或删除，请先退回草稿");
    }
    public void assertSchemeEditable(Long schemeId){
        var scheme=schemes.lockActive(schemeId);
        if(scheme==null)throw invalid("检验方案不存在或已删除");
        if(controlled(scheme.getProductId()) && "RELEASED".equals(scheme.getStatus()))throw invalid("已发布受控方案的明细不可修改，请先停用或另建版本");
    }
    public void assertSchemeReady(Long schemeId){verifyScheme(schemeId,false);}
    public void assertSchemePublishable(Long schemeId){verifyScheme(schemeId,true);}
    private void verifyScheme(Long schemeId,boolean lock){
        QmsInspectionScheme scheme=schemes.selectById(schemeId);
        if(scheme==null || !Integer.valueOf(0).equals(scheme.getDeletedFlag()))throw invalid("检验方案不存在或已删除");
        if(!controlled(scheme.getProductId()))return;
        var lines=items.selectList(new QueryWrapper<QmsInspectionSchemeItem>().eq("scheme_id",schemeId).eq("deleted_flag",0));
        if(lines.isEmpty())throw invalid("受控检验方案缺少检验项目");
        for(var line:lines)verifySchemeItem(scheme,line,lock);
    }
    private void verifySchemeItem(QmsInspectionScheme scheme,QmsInspectionSchemeItem line,boolean lock){
        var standard=resolveStandard(line.getStandardId(),lock);
        assertStandardConfirmed(standard);
        assertStandardMatchesScheme(scheme,line,standard);
        validateSampling(line);
        policy.validateStandard(standard.getStandardValue(),standard.getLowerLimit(),standard.getUpperLimit(),standard.getJudgementRule(),standard.getSourceReference());
        validateItemAndUnit(standard);
    }
    /** 需要加锁校验时用 lockActive，否则按主键读取；标准未挂接时返回 null 交给后续校验驳回。 */
    private QmsInspectionStandard resolveStandard(Long standardId,boolean lock){
        if(standardId==null)return null;
        return lock?standards.lockActive(standardId):standards.selectById(standardId);
    }
    private void assertStandardConfirmed(QmsInspectionStandard standard){
        if(standard==null || !Integer.valueOf(0).equals(standard.getDeletedFlag())
                || !"CONFIRMED".equals(standard.getConfirmationStatus()))throw invalid("检验标准尚未确认或已删除");
    }
    private void assertStandardMatchesScheme(QmsInspectionScheme scheme,QmsInspectionSchemeItem line,QmsInspectionStandard standard){
        if(!scheme.getProductId().equals(standard.getProductId())
                || !java.util.Objects.equals(line.getItemId(),standard.getItemId()))
            throw invalid("检验标准的产品、项目或工序与方案不匹配");
        if(standard.getOperationId()!=null && !java.util.Objects.equals(scheme.getOperationId(),standard.getOperationId()))
            throw invalid("检验标准的产品、项目或工序与方案不匹配");
    }
    private void validateSampling(QmsInspectionSchemeItem line){
        String sampling=line.getSamplingMode()==null?"FIXED":line.getSamplingMode();
        if("ALL".equals(sampling)) {
            if(line.getSampleQuantity()==null || line.getSampleQuantity().signum()!=0)throw invalid("全检模式固定抽样数量须为 0，执行时按实际批量计算");
            return;
        }
        if(!"FIXED".equals(sampling) || line.getSampleQuantity()==null || line.getSampleQuantity().signum()<=0 || line.getSampleQuantity().stripTrailingZeros().scale()>0)
            throw invalid("固定抽样数量必须为正整数");
    }
    /**
     * 检验执行前的可执行性校验。
     * <p>方案必须是已发布状态；受控台灯方案的逐件全检已随执行链打通，不再在此拦截。</p>
     */
    public void assertInspectionExecutionSupported(Long schemeId){
        if(schemeId==null)return;
        var scheme=schemes.selectById(schemeId);
        if(scheme==null || !Integer.valueOf(0).equals(scheme.getDeletedFlag()))throw invalid("检验方案不存在或已删除");
        if(!"RELEASED".equals(scheme.getStatus()))throw invalid("检验方案尚未发布，不能创建或提交检验实绩");
    }
    @Transactional(rollbackFor=Exception.class)
    public QmsInspectionStandard review(Long id,Integer version,Long actor,boolean confirm){
        QmsInspectionStandard row=standards.lockActive(id);
        if(row==null)throw invalid("检验标准不存在或已删除");
        if(version==null || !version.equals(row.getVersion()))throw new OptimisticLockConflictException();
        if(confirm)confirmStandard(row);else reopenStandard(row);
        var update=new QmsInspectionStandard();update.setStandardId(id);update.setVersion(row.getVersion());
        update.setConfirmationStatus(confirm?"CONFIRMED":"DRAFT");update.setUpdatedBy(actor);
        if(standards.updateById(update)!=1)throw new OptimisticLockConflictException();
        SysOperationLog log=new SysOperationLog();log.setUserId(actor);log.setOperationType(confirm?"CONFIRM_STANDARD":"REOPEN_STANDARD");
        log.setModuleCode("QMS_INSPECTION_STANDARD");log.setBizId(id);log.setOperationTime(LocalDateTime.now());
        log.setRemark("确认的是设计检验依据，不是实物检验结果或认证证书");log.setDeletedFlag(0);
        if(actor==null || logs.insert(log)!=1)throw new BusinessException(ResultCode.FAILED);
        return standards.selectById(id);
    }
    private void confirmStandard(QmsInspectionStandard row){
        if(!"DRAFT".equals(row.getConfirmationStatus()))throw invalid("仅草稿检验标准可确认");
        policy.validateStandard(row.getStandardValue(),row.getLowerLimit(),row.getUpperLimit(),row.getJudgementRule(),row.getSourceReference());
        validateItemAndUnit(row);
    }
    private void reopenStandard(QmsInspectionStandard row){
        if(!"CONFIRMED".equals(row.getConfirmationStatus()))throw invalid("仅已确认检验标准可退回");
        // 不让已发布方案在不知情的情况下使用退回标准。
        var refs=items.selectList(new QueryWrapper<QmsInspectionSchemeItem>().eq("standard_id",row.getStandardId()).eq("deleted_flag",0));
        for(var ref:refs)assertStandardNotUsedByReleasedScheme(ref.getSchemeId());
    }
    private void assertStandardNotUsedByReleasedScheme(Long schemeId){
        var scheme=schemes.selectById(schemeId);
        if(scheme!=null && Integer.valueOf(0).equals(scheme.getDeletedFlag()) && "RELEASED".equals(scheme.getStatus()))
            throw invalid("该标准被已发布检验方案引用，请先停用相关方案再退回");
    }
    private void validateItemAndUnit(QmsInspectionStandard standard){
        var item=standard.getItemId()==null?null:inspectionItems.selectById(standard.getItemId());
        if(item==null || !Integer.valueOf(0).equals(item.getDeletedFlag()))throw invalid("检验项目不存在或已删除");
        boolean numeric=standard.getLowerLimit()!=null && !standard.getLowerLimit().isBlank()
                || standard.getUpperLimit()!=null && !standard.getUpperLimit().isBlank();
        if(numeric && item.getUnitId()==null)throw invalid("数值检验项目必须定义单位");
        if(item.getUnitId()!=null){var unit=units.selectById(item.getUnitId());
            if(unit==null || !Integer.valueOf(0).equals(unit.getDeletedFlag()) || !"ENABLE".equals(unit.getStatus()))throw invalid("检验单位不存在、已删除或未启用");
        }
    }
    private BusinessException invalid(String message){return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),message);}
}
