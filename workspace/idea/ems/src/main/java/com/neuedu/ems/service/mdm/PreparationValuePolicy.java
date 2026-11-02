package com.neuedu.ems.service.mdm;

import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Set;

/** 仅检查输入完整性和数值格式；真实规格依据由有权限的人员负责确认。 */
@Component
public class PreparationValuePolicy {
    public boolean hasEvidence(String value) {
        return value!=null && !value.isBlank()
                && !value.matches("(?is).*(待确认|待测|未知|未确定|TBD|PENDING|待填写).*");
    }
    public void requireEvidence(String value,String label) {
        if (!hasEvidence(value)) throw invalid(label+"不能为空或待确认占位内容");
    }
    public void validateParameter(String type,String value,String source) {
        requireEvidence(value,"参数值"); requireEvidence(source,"参数依据");
        if (!Set.of("TEXT","DECIMAL","INTEGER").contains(type)) throw invalid("不支持的参数类型");
        if (!"TEXT".equals(type)) {
            if(!value.trim().matches("[0-9]+(\\.[0-9]+)?"))throw invalid("参数值请使用普通十进制格式，不使用指数或特殊值");
            BigDecimal number=number(value,"参数值");
            if(number.signum()<=0 || number.compareTo(new BigDecimal("99999999999999.9999"))>0 || number.stripTrailingZeros().scale()>4)
                throw invalid("数值参数必须为正数，最多四位小数且不超过十八位有效数字");
            if("INTEGER".equals(type) && number.stripTrailingZeros().scale()>0)
                throw invalid("该参数必须为正整数");
        }
    }
    public void validateStandard(String nominal,String lower,String upper,String rule,String source) {
        requireEvidence(nominal,"检验标准值");requireEvidence(rule,"判定规则");requireEvidence(source,"检验依据");
        BigDecimal low=lower==null || lower.isBlank()?null:number(lower,"下限");
        BigDecimal high=upper==null || upper.isBlank()?null:number(upper,"上限");
        if(low!=null && high!=null && low.compareTo(high)>0)throw invalid("检验下限不能大于上限");
        if(low!=null || high!=null) {
            BigDecimal expected=number(nominal,"有数值限值的标准值");
            if(low!=null && expected.compareTo(low)<0 || high!=null && expected.compareTo(high)>0)
                throw invalid("标准值必须位于上下限范围内");
        }
    }
    private BigDecimal number(String value,String label) {
        try {return new BigDecimal(value.trim());}
        catch(RuntimeException e){throw invalid(label+"必须是有效十进制数值");}
    }
    private BusinessException invalid(String message){return new BusinessException(ResultCode.PARAM_ERROR.getCode(),message);}
}
