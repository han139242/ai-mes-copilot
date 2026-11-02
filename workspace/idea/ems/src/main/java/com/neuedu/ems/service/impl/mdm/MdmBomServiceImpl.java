package com.neuedu.ems.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.mdm.MdmBom;
import com.neuedu.ems.mapper.mdm.MdmBomMapper;
import com.neuedu.ems.service.mdm.MdmBomService;
import com.neuedu.ems.service.mdm.BomEditGuard;
import com.neuedu.ems.service.mdm.BomExpansionService;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * BOM Service 实现类。
 */
@Service
public class MdmBomServiceImpl extends ServiceImpl<MdmBomMapper, MdmBom> implements MdmBomService {
    @Autowired private BomEditGuard editGuard;
    @Autowired private BomExpansionService expansion;
    @Autowired private com.neuedu.ems.mapper.mdm.MdmBomItemMapper bomItems;
    @Autowired private com.neuedu.ems.mapper.mdm.MdmProductMapper products;

    @Override
    public Page<MdmBom> pageBoms(Integer current, Integer size, String bomCode, Long productId, String status) {
        Page<MdmBom> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildBomQuery(bomCode, productId, status));
    }

    @Override
    public MdmBom getBomById(Long bomId) {
        QueryWrapper<MdmBom> wrapper = new QueryWrapper<>();
        wrapper.eq("bom_id", bomId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public boolean saveBom(MdmBom bom) {
        if (bom == null || !StringUtils.hasText(bom.getBomCode()) || !StringUtils.hasText(bom.getBomVersion()) || bom.getProductId() == null)
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "BOM 编码、版本和产品不能为空");
        if (bom.getStatus() != null && !"DRAFT".equals(bom.getStatus()))
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "新 BOM 必须先保存为草稿，维护明细后发布");
        bom.setBomId(null);
        bom.setStatus("DRAFT");
        if (bom.getDeletedFlag() == null) {
            bom.setDeletedFlag(0);
        }
        return baseMapper.insert(bom) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateBom(MdmBom bom) {
        if (bom.getBomId() == null) {
            return false;
        }
        MdmBom existing = editGuard.draft(bom.getBomId());
        String nextStatus = resolveNextStatus(bom);
        if ("DISABLE".equals(nextStatus)) {
            editGuard.ensureUnreferenced(bom.getBomId());
        }
        java.time.LocalDate from = pickDate(bom.getEffectiveDate(), existing.getEffectiveDate());
        java.time.LocalDate to = pickDate(bom.getEffectiveTo(), existing.getEffectiveTo());
        assertEffectiveWindow(from, to);
        if ("RELEASED".equals(nextStatus)) {
            assertReleasable(bom, existing, from);
            closeEarlierReleasedVersions(pickProductId(bom, existing), existing.getBomId(), from);
        }
        if (bom.getVersion() == null) {
            bom.setVersion(existing.getVersion());
        }
        bom.setDeletedFlag(null);
        boolean updated = baseMapper.updateById(bom) > 0;
        if (updated && "RELEASED".equals(nextStatus)) {
            expansion.expand(bom.getBomId(), java.math.BigDecimal.ONE, from, false);
        }
        return updated;
    }

    /** 未显式指定状态时保持草稿口径。 */
    private String resolveNextStatus(MdmBom bom) {
        String nextStatus = pickText(bom.getStatus(), "DRAFT");
        if (!java.util.Set.of("DRAFT", "RELEASED", "DISABLE").contains(nextStatus)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "无效 BOM 状态");
        }
        return nextStatus;
    }

    /** 入参未带值时沿用库内已有值。 */
    private String pickText(String incoming, String fallback) {
        return incoming == null ? fallback : incoming;
    }

    /** 入参未带值时沿用库内已有值。 */
    private java.time.LocalDate pickDate(java.time.LocalDate incoming, java.time.LocalDate fallback) {
        return incoming == null ? fallback : incoming;
    }

    /** 生效区间不能反向，发布态还必须有生效日期。 */
    private void assertEffectiveWindow(java.time.LocalDate from, java.time.LocalDate to) {
        boolean reversed = from != null && to != null && to.isBefore(from);
        if (reversed) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "失效日期不能早于生效日期");
        }
    }

    /** 受控 BOM 发布前必须补齐生效日期与各直接物料的投料工序。 */
    private void assertReleasable(MdmBom bom, MdmBom existing, java.time.LocalDate from) {
        if (from == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "发布 BOM 必须指定生效日期");
        }
        Long productId = pickProductId(bom, existing);
        var product = products.selectById(productId);
        if (product == null || !Integer.valueOf(1).equals(product.getPreparationControlled())) {
            return;
        }
        if (missingConsumptionOperation(existing.getBomId())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "受控BOM发布前必须绑定各直接物料的投料工序");
        }
    }

    /** 未指定产品时沿用原 BOM 的产品。 */
    private Long pickProductId(MdmBom bom, MdmBom existing) {
        return bom.getProductId() == null ? existing.getProductId() : bom.getProductId();
    }

    /**
     * 新版本发布后，同一产品更早的已发布 BOM 只把失效日收到新生效日前一天。
     * 不改旧版本状态和明细，避免同一天出现两套生效结构。
     */
    private void closeEarlierReleasedVersions(Long productId, Long currentBomId, java.time.LocalDate from) {
        if (productId == null || from == null) {
            return;
        }
        var others = baseMapper.selectList(new QueryWrapper<MdmBom>()
                .eq("product_id", productId)
                .eq("deleted_flag", 0)
                .eq("status", "RELEASED")
                .ne("bom_id", currentBomId));
        for (MdmBom other : others) {
            if (other.getEffectiveTo() != null && other.getEffectiveTo().isBefore(from)) {
                continue;
            }
            if (other.getEffectiveDate() != null && !other.getEffectiveDate().isBefore(from)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                        "已有同日或更晚的已发布 BOM，请把新生效日安排在旧版本之后");
            }
            MdmBom close = new MdmBom();
            close.setBomId(other.getBomId());
            close.setVersion(other.getVersion());
            close.setEffectiveTo(from.minusDays(1));
            if (baseMapper.updateById(close) == 0) {
                throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(), "原 BOM 已被其他人修改，请刷新后重试");
            }
        }
    }

    /** 是否还有直接物料未绑定投料工序。 */
    private boolean missingConsumptionOperation(Long bomId) {
        QueryWrapper<com.neuedu.ems.entity.mdm.MdmBomItem> query = new QueryWrapper<>();
        query.eq("bom_id", bomId).eq("deleted_flag", 0).isNull("consumption_operation_id");
        return bomItems.selectCount(query) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeBomById(Long bomId) {
        MdmBom existing = editGuard.draft(bomId);
        editGuard.ensureUnreferenced(bomId);
        MdmBom bom = new MdmBom();
        bom.setBomId(bomId);
        bom.setVersion(existing.getVersion());
        bom.setDeletedFlag(1);
        boolean removed = baseMapper.updateById(bom) > 0;
        if (removed) editGuard.archiveDraftItems(bomId);
        return removed;
    }

    @Override
    public List<MdmBom> listBoms(String bomCode, Long productId, String status) {
        return baseMapper.selectList(buildBomQuery(bomCode, productId, status));
    }

    private QueryWrapper<MdmBom> buildBomQuery(String bomCode, Long productId, String status) {
        QueryWrapper<MdmBom> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(bomCode)) {
            wrapper.like("bom_code", bomCode);
        }
        if (productId != null) {
            wrapper.eq("product_id", productId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("bom_id");
        return wrapper;
    }
}
