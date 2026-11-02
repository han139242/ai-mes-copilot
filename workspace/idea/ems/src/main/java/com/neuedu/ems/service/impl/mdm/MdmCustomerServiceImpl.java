package com.neuedu.ems.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.mdm.MdmCustomer;
import com.neuedu.ems.mapper.mdm.MdmCustomerMapper;
import com.neuedu.ems.security.phone.PhoneWriteValue;
import com.neuedu.ems.security.phone.SensitivePhoneField;
import com.neuedu.ems.security.phone.SensitivePhoneService;
import com.neuedu.ems.service.mdm.MdmCustomerService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 客户 Service 实现类。
 */
@Service
public class MdmCustomerServiceImpl extends ServiceImpl<MdmCustomerMapper, MdmCustomer> implements MdmCustomerService {

    @Override
    public Page<MdmCustomer> pageCustomers(Integer current, Integer size, String keyword, String status) {
        Page<MdmCustomer> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildCustomerQuery(keyword, status));
    }

    @Override
    public MdmCustomer getCustomerById(Long customerId) {
        QueryWrapper<MdmCustomer> wrapper = new QueryWrapper<>();
        wrapper.eq("customer_id", customerId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public boolean saveCustomer(MdmCustomer customer, String rawContactPhone) {
        prepareNumberPrefix(customer);
        if (customer.getDeletedFlag() == null) {
            customer.setDeletedFlag(0);
        }
        applyPhone(customer, sensitivePhoneService.prepareCreate(SensitivePhoneField.CUSTOMER, rawContactPhone));
        if(baseMapper.insert(customer)==0)return false;
        if(customer.getNumberPrefix()==null) {
            if(customer.getCustomerId()==null)throw new IllegalStateException("客户创建后未返回主键");
            customer.setNumberPrefix(com.neuedu.ems.service.sys.BusinessNumberService.defaultPrefix(customer.getCustomerId()));
            prepareNumberPrefix(customer);
            MdmCustomer prefix=new MdmCustomer();prefix.setCustomerId(customer.getCustomerId());prefix.setNumberPrefix(customer.getNumberPrefix());
            if(baseMapper.updateById(prefix)!=1)throw new IllegalStateException("客户编号前缀保存失败");
        }
        return true;
    }

    @Override
    public boolean updateCustomer(MdmCustomer customer, String rawContactPhone) {
        if (customer.getCustomerId() == null) {
            return false;
        }
        prepareNumberPrefix(customer);
        PhoneWriteValue phoneWrite = sensitivePhoneService.prepareUpdate(SensitivePhoneField.CUSTOMER, rawContactPhone);
        applyPhone(customer, phoneWrite);
        customer.setDeletedFlag(null);
        if (phoneWrite.clear()) {
            UpdateWrapper<MdmCustomer> wrapper = new UpdateWrapper<MdmCustomer>()
                    .eq("customer_id", customer.getCustomerId())
                    .eq("deleted_flag", 0)
                    .set("contact_phone", null)
                    .set("contact_phone_lookup_hash", null);
            return baseMapper.update(customer, wrapper) > 0;
        }
        return baseMapper.updateById(customer) > 0;
    }

    @Override
    public boolean removeCustomerById(Long customerId) {
        MdmCustomer customer = new MdmCustomer();
        customer.setCustomerId(customerId);
        customer.setDeletedFlag(1);
        return baseMapper.updateById(customer) > 0;
    }

    @Override
    public List<MdmCustomer> listCustomers(String keyword, String status) {
        return baseMapper.selectList(buildCustomerQuery(keyword, status));
    }

    private QueryWrapper<MdmCustomer> buildCustomerQuery(String keyword, String status) {
        QueryWrapper<MdmCustomer> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(keyword)) {
            String phoneLookupHash = sensitivePhoneService.lookupHash(keyword).orElse(null);
            wrapper.and(item -> item.like("customer_code", keyword)
                    .or()
                    .like("customer_name", keyword)
                    .or()
                    .like("contact_name", keyword)
                    .or(phoneLookupHash != null)
                    .eq(phoneLookupHash != null, "contact_phone_lookup_hash", phoneLookupHash));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("customer_id");
        return wrapper;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private SensitivePhoneService sensitivePhoneService;

    private void prepareNumberPrefix(MdmCustomer customer) {
        String prefix=customer.getNumberPrefix();
        if(prefix==null || prefix.isBlank()){customer.setNumberPrefix(null);return;}
        prefix=prefix.trim().toUpperCase(java.util.Locale.ROOT);
        if(!prefix.matches("[A-Z][A-Z0-9]{0,7}") || "SYS".equals(prefix))
            throw new com.neuedu.ems.exception.BusinessException(com.neuedu.ems.common.ResultCode.PARAM_ERROR.getCode(),"编号前缀须为 1～8 位大写字母或数字，以字母开头；SYS 保留给公共业务");
        QueryWrapper<MdmCustomer> query=new QueryWrapper<MdmCustomer>().eq("number_prefix",prefix);
        if(customer.getCustomerId()!=null)query.ne("customer_id",customer.getCustomerId());
        if(baseMapper.selectCount(query)>0)
            throw new com.neuedu.ems.exception.BusinessException(com.neuedu.ems.common.ResultCode.BUSINESS_ERROR.getCode(),"客户编号前缀已被使用，请选择其他前缀");
        customer.setNumberPrefix(prefix);
    }

    private void applyPhone(MdmCustomer customer, PhoneWriteValue phoneWrite) {
        if (!phoneWrite.changed() || phoneWrite.clear()) {
            return;
        }
        customer.setContactPhone(phoneWrite.encryptedValue());
        customer.setContactPhoneLookupHash(phoneWrite.lookupHash());
    }
}
