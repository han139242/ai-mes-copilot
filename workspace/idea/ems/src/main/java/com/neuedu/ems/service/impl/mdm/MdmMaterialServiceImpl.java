package com.neuedu.ems.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import com.neuedu.ems.mapper.mdm.MdmMaterialMapper;
import com.neuedu.ems.service.mdm.MdmMaterialService;
import com.neuedu.ems.service.mdm.MaterialQuantityPolicy;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.common.ResultCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 物料 Service 实现类。
 */
@Service
public class MdmMaterialServiceImpl extends ServiceImpl<MdmMaterialMapper, MdmMaterial> implements MdmMaterialService {
    @Autowired private MaterialQuantityPolicy quantityPolicy;

    @Override
    public Page<MdmMaterial> pageMaterials(Integer current, Integer size, String materialCode,
                                           String materialName, Long categoryId, String status) {
        Page<MdmMaterial> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildMaterialQuery(materialCode, materialName, categoryId, status));
    }

    @Override
    public MdmMaterial getMaterialById(Long materialId) {
        QueryWrapper<MdmMaterial> wrapper = new QueryWrapper<>();
        wrapper.eq("material_id", materialId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public boolean saveMaterial(MdmMaterial material) {
        quantityPolicy.validateDefinition(material);
        if (material.getDeletedFlag() == null) {
            material.setDeletedFlag(0);
        }
        return baseMapper.insert(material) > 0;
    }

    @Override
    public boolean updateMaterial(MdmMaterial material) {
        if (material.getMaterialId() == null) {
            return false;
        }
        MdmMaterial existing = getMaterialById(material.getMaterialId());
        if (existing == null) return false;
        MdmMaterial effective = new MdmMaterial();
        effective.setQuantityMode(material.getQuantityMode() == null ? existing.getQuantityMode() : material.getQuantityMode());
        effective.setIssueStep(material.getIssueStep() == null ? existing.getIssueStep() : material.getIssueStep());
        quantityPolicy.validateDefinition(effective);
        if ((!quantityPolicy.mode(existing).equals(quantityPolicy.mode(effective))
                || quantityPolicy.step(existing).compareTo(quantityPolicy.step(effective)) != 0)
                && baseMapper.movementCount(material.getMaterialId()) > 0)
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),"已产生库存流水的物料不能直接修改数量规则，请建立受控的新物料版本");
        material.setDeletedFlag(null);
        return baseMapper.updateById(material) > 0;
    }

    @Override
    public boolean removeMaterialById(Long materialId) {
        MdmMaterial material = new MdmMaterial();
        material.setMaterialId(materialId);
        material.setDeletedFlag(1);
        return baseMapper.updateById(material) > 0;
    }

    @Override
    public List<MdmMaterial> listMaterials(String materialCode, String materialName, Long categoryId, String status) {
        return baseMapper.selectList(buildMaterialQuery(materialCode, materialName, categoryId, status));
    }

    private QueryWrapper<MdmMaterial> buildMaterialQuery(String materialCode, String materialName,
                                                        Long categoryId, String status) {
        QueryWrapper<MdmMaterial> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(materialCode)) {
            wrapper.like("material_code", materialCode);
        }
        if (StringUtils.hasText(materialName)) {
            wrapper.like("material_name", materialName);
        }
        if (categoryId != null) {
            wrapper.eq("category_id", categoryId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("material_id");
        return wrapper;
    }
}
