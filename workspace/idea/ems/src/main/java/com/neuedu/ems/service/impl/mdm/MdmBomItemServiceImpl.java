package com.neuedu.ems.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.mdm.MdmBomItem;
import com.neuedu.ems.mapper.mdm.MdmBomItemMapper;
import com.neuedu.ems.service.mdm.MdmBomItemService;
import com.neuedu.ems.service.mdm.BomEditGuard;
import com.neuedu.ems.service.mdm.BomExpansionService;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BOM 明细 Service 实现类。
 */
@Service
public class MdmBomItemServiceImpl extends ServiceImpl<MdmBomItemMapper, MdmBomItem> implements MdmBomItemService {
    @Autowired private BomEditGuard editGuard;
    @Autowired private BomExpansionService expansion;
    @Autowired private com.neuedu.ems.mapper.process.ProcessOperationMapper operations;

    @Override
    public Page<MdmBomItem> pageBomItems(Integer current, Integer size, Long bomId, Long materialId) {
        Page<MdmBomItem> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildBomItemQuery(bomId, materialId));
    }

    @Override
    public MdmBomItem getBomItemById(Long bomItemId) {
        QueryWrapper<MdmBomItem> wrapper = new QueryWrapper<>();
        wrapper.eq("bom_item_id", bomItemId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveBomItem(MdmBomItem item) {
        if (item == null || item.getBomId() == null) throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),"所属 BOM 不能为空");
        var parent = editGuard.draft(item.getBomId());
        if(Long.valueOf(0).equals(item.getConsumptionOperationId()))item.setConsumptionOperationId(null);
        validateConsumptionOperation(item.getConsumptionOperationId());
        item.setBomItemId(null);
        if (Long.valueOf(0).equals(item.getChildBomId())) item.setChildBomId(null);
        if (item.getDeletedFlag() == null) {
            item.setDeletedFlag(0);
        }
        boolean saved = baseMapper.insert(item) > 0;
        if (saved) expansion.expand(parent.getBomId(), java.math.BigDecimal.ONE,
                parent.getEffectiveDate(), false);
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateBomItem(MdmBomItem item) {
        if (item.getBomItemId() == null) {
            return false;
        }
        MdmBomItem existing = getBomItemById(item.getBomItemId());
        if (existing == null) return false;
        var parent = editGuard.draft(existing.getBomId());
        boolean clearConsumption=Long.valueOf(0).equals(item.getConsumptionOperationId());
        if(clearConsumption)item.setConsumptionOperationId(null);
        validateConsumptionOperation(item.getConsumptionOperationId());
        if (item.getBomId() != null && !item.getBomId().equals(existing.getBomId()))
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),"不能将明细移动到另一个 BOM");
        if (item.getVersion() == null) item.setVersion(existing.getVersion());
        item.setDeletedFlag(null);
        boolean updated;
        if (Long.valueOf(0).equals(item.getChildBomId()) || clearConsumption) {
            var wrapper=new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MdmBomItem>().eq("bom_item_id",item.getBomItemId());
            if(Long.valueOf(0).equals(item.getChildBomId())) { item.setChildBomId(null);wrapper.set("child_bom_id",null); }
            if(clearConsumption)wrapper.set("consumption_operation_id",null);
            updated = baseMapper.update(item,wrapper) > 0;
        } else updated = baseMapper.updateById(item) > 0;
        if (updated) expansion.expand(parent.getBomId(), java.math.BigDecimal.ONE, parent.getEffectiveDate(), false);
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeBomItemById(Long bomItemId) {
        MdmBomItem existing = getBomItemById(bomItemId);
        if (existing == null) return false;
        editGuard.draft(existing.getBomId());
        MdmBomItem item = new MdmBomItem();
        item.setBomItemId(bomItemId);
        item.setVersion(existing.getVersion());
        item.setDeletedFlag(1);
        return baseMapper.updateById(item) > 0;
    }

    @Override
    public List<MdmBomItem> listBomItems(Long bomId, Long materialId) {
        return baseMapper.selectList(buildBomItemQuery(bomId, materialId));
    }

    private QueryWrapper<MdmBomItem> buildBomItemQuery(Long bomId, Long materialId) {
        QueryWrapper<MdmBomItem> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (bomId != null) {
            wrapper.eq("bom_id", bomId);
        }
        if (materialId != null) {
            wrapper.eq("material_id", materialId);
        }
        wrapper.orderByAsc("sequence_no", "bom_item_id");
        return wrapper;
    }

    private void validateConsumptionOperation(Long id) {
        if(id==null)return;
        var operation=operations.selectById(id);
        if(id<=0 || operation==null || !Integer.valueOf(0).equals(operation.getDeletedFlag())
                || !"ENABLE".equals(operation.getStatus()))
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),"投料工序不存在、已删除或未启用");
    }
}
