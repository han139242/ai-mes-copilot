package com.neuedu.ems.service.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.MdmBom;
import com.neuedu.ems.entity.mdm.MdmBomItem;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmBomItemMapper;
import com.neuedu.ems.mapper.mdm.MdmBomMapper;
import org.springframework.stereotype.Service;

/** 发布与明细修改共用父行锁，避免发布同时加入未校验的明细。 */
@Service
public class BomEditGuard {
    private final MdmBomMapper boms;
    private final MdmBomItemMapper items;
    public BomEditGuard(MdmBomMapper boms, MdmBomItemMapper items) { this.boms=boms; this.items=items; }
    public MdmBom draft(Long id) {
        MdmBom bom = boms.lockActive(id);
        if (bom == null) throw invalid("BOM 不存在或已删除");
        if (!"DRAFT".equals(bom.getStatus())) throw invalid("仅草稿 BOM 可以修改；已发布版本请另建新版本");
        return bom;
    }
    public void ensureUnreferenced(Long id) {
        if (items.selectCount(new QueryWrapper<MdmBomItem>().eq("child_bom_id",id).eq("deleted_flag",0)) > 0)
            throw invalid("该 BOM 已被父 BOM 引用，不能停用或删除");
    }
    public void archiveDraftItems(Long id) {
        items.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MdmBomItem>()
                .eq("bom_id",id).eq("deleted_flag",0).set("deleted_flag",1).setSql("version=version+1"));
    }
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),message); }
}
