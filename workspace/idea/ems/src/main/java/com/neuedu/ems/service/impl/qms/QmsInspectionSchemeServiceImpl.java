package com.neuedu.ems.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.qms.QmsInspectionScheme;
import com.neuedu.ems.entity.qms.QmsInspectionSchemeItem;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeItemMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeMapper;
import com.neuedu.ems.service.qms.QmsInspectionSchemeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 检验方案管理 Service 实现类。
 */
@Service
public class QmsInspectionSchemeServiceImpl extends ServiceImpl<QmsInspectionSchemeMapper, QmsInspectionScheme> implements QmsInspectionSchemeService {
    @Autowired private com.neuedu.ems.service.qms.InspectionStandardReviewService reviewService;

    @Autowired
    private QmsInspectionSchemeItemMapper qmsInspectionSchemeItemMapper;

    /**
     * 分页查询检验方案列表，支持方案编码、名称、产品、工序、检验类型和状态筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param schemeCode 方案编码
     * @param schemeName 方案名称
     * @param productId 产品ID
     * @param operationId 工序ID
     * @param inspectionType 检验类型
     * @param status 方案状态
     * @return 分页结果
     */
    @Override
    public Page<QmsInspectionScheme> pageSchemes(Integer current, Integer size, String schemeCode, String schemeName,
                                                 Long productId, Long operationId, String inspectionType, String status) {
        Page<QmsInspectionScheme> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildSchemeQuery(schemeCode, schemeName, productId, operationId, inspectionType, status));
    }

    /**
     * 查询检验方案列表，支持方案编码、名称、产品、工序、检验类型和状态筛选。
     * @param schemeCode 方案编码
     * @param schemeName 方案名称
     * @param productId 产品ID
     * @param operationId 工序ID
     * @param inspectionType 检验类型
     * @param status 方案状态
     * @return 检验方案列表
     */
    @Override
    public List<QmsInspectionScheme> listSchemes(String schemeCode, String schemeName, Long productId, Long operationId,
                                                 String inspectionType, String status) {
        return baseMapper.selectList(buildSchemeQuery(schemeCode, schemeName, productId, operationId, inspectionType, status));
    }

    /**
     * 根据检验方案ID查询未删除的方案明细列表。
     * @param schemeId 检验方案ID
     * @return 检验方案明细列表
     */
    @Override
    public List<QmsInspectionSchemeItem> listSchemeItems(Long schemeId) {
        LambdaQueryWrapper<QmsInspectionSchemeItem> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QmsInspectionSchemeItem::getSchemeId, schemeId)
                .eq(QmsInspectionSchemeItem::getDeletedFlag, 0)
                .orderByAsc(QmsInspectionSchemeItem::getSequenceNo)
                .orderByDesc(QmsInspectionSchemeItem::getCreatedTime);
        return qmsInspectionSchemeItemMapper.selectList(queryWrapper);
    }

    /**
     * 根据检验方案ID查询未删除的检验方案信息。
     * @param schemeId 检验方案ID
     * @return 检验方案实体
     */
    @Override
    public QmsInspectionScheme getSchemeById(Long schemeId) {
        LambdaQueryWrapper<QmsInspectionScheme> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QmsInspectionScheme::getSchemeId, schemeId)
                .eq(QmsInspectionScheme::getDeletedFlag, 0);
        return baseMapper.selectOne(queryWrapper);
    }

    /**
     * 新增检验方案，自动设置删除标志为0。
     * @param scheme 检验方案实体
     * @return 是否成功
     */
    @Override
    public boolean saveScheme(QmsInspectionScheme scheme) {
        if(reviewService.controlled(scheme.getProductId()) && !"DRAFT".equals(scheme.getStatus()))
            throw new com.neuedu.ems.exception.BusinessException(com.neuedu.ems.common.ResultCode.BUSINESS_ERROR.getCode(),"受控检验方案须先建草稿，维护并确认标准后发布");
        if (scheme.getDeletedFlag() == null) {
            scheme.setDeletedFlag(0);
        }
        return baseMapper.insert(scheme) > 0;
    }

    /**
     * 更新检验方案信息，不允许修改删除标志。
     * @param scheme 检验方案实体
     * @return 是否成功
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public boolean updateScheme(QmsInspectionScheme scheme) {
        var existing=scheme.getSchemeId()==null?null:baseMapper.lockActive(scheme.getSchemeId());
        if(existing==null)return false;
        rejectCrossProductChange(scheme,existing);
        if(reviewService.controlled(existing.getProductId())) {
            Boolean stopped=stopReleasedControlledScheme(scheme,existing);
            if(stopped!=null)return stopped;
        }
        scheme.setDeletedFlag(null);
        boolean updated=baseMapper.updateById(scheme)>0;
        if(updated && reviewService.controlled(existing.getProductId()) && "RELEASED".equals(scheme.getStatus()))
            reviewService.assertSchemePublishable(scheme.getSchemeId());
        return updated;
    }
    /** 历史方案不允许转为受控产品方案，必须另建草稿。 */
    private void rejectCrossProductChange(QmsInspectionScheme scheme,QmsInspectionScheme existing){
        if(scheme.getProductId()!=null && !scheme.getProductId().equals(existing.getProductId())
                && reviewService.controlled(scheme.getProductId()))
            throw schemeError("不能将历史方案转为受控产品方案，请另建草稿");
    }
    /**
     * 受控方案不能换产品；已发布的受控方案只能停用。
     * @return 已按「停用」处理时返回是否成功，无需停用时返回 {@code null}
     */
    private Boolean stopReleasedControlledScheme(QmsInspectionScheme scheme,QmsInspectionScheme existing){
        if(scheme.getProductId()!=null && !scheme.getProductId().equals(existing.getProductId()))
            throw schemeError("受控方案不能转移到其它产品");
        if(!"RELEASED".equals(existing.getStatus()))return null;
        if(!"DISABLE".equals(scheme.getStatus()))throw schemeError("已发布受控方案只能停用，修订请另建版本");
        var stop=new QmsInspectionScheme();stop.setSchemeId(existing.getSchemeId());stop.setStatus("DISABLE");
        return baseMapper.updateById(stop)>0;
    }
    private com.neuedu.ems.exception.BusinessException schemeError(String message){
        return new com.neuedu.ems.exception.BusinessException(com.neuedu.ems.common.ResultCode.BUSINESS_ERROR.getCode(),message);
    }

    /**
     * 根据ID删除检验方案（软删除，将删除标志设为1）。
     * @param schemeId 检验方案ID
     * @return 是否成功
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public boolean removeSchemeById(Long schemeId) {
        reviewService.assertSchemeEditable(schemeId);
        QmsInspectionScheme scheme = new QmsInspectionScheme();
        scheme.setSchemeId(schemeId);
        scheme.setDeletedFlag(1);
        return baseMapper.updateById(scheme) > 0;
    }

    /**
     * 根据ID列表批量删除检验方案（软删除，将删除标志设为1）。
     * @param schemeIds 检验方案ID列表
     * @return 是否成功
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public boolean removeSchemesByIds(List<Long> schemeIds) {
        if (schemeIds == null || schemeIds.isEmpty()) {
            return false;
        }
        for(Long id:schemeIds)if(!removeSchemeById(id))throw new com.neuedu.ems.exception.BusinessException(com.neuedu.ems.common.ResultCode.NOT_FOUND);
        return true;
    }

    /**
     * 构建检验方案查询条件，默认过滤已删除记录。
     * @param schemeCode 方案编码，可选
     * @param schemeName 方案名称，可选
     * @param productId 产品ID，可选
     * @param operationId 工序ID，可选
     * @param inspectionType 检验类型，可选
     * @param status 方案状态，可选
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<QmsInspectionScheme> buildSchemeQuery(String schemeCode, String schemeName,
                                                                     Long productId, Long operationId,
                                                                     String inspectionType, String status) {
        LambdaQueryWrapper<QmsInspectionScheme> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(QmsInspectionScheme::getDeletedFlag, 0)
                .like(StringUtils.hasText(schemeCode), QmsInspectionScheme::getSchemeCode, schemeCode)
                .like(StringUtils.hasText(schemeName), QmsInspectionScheme::getSchemeName, schemeName)
                .eq(productId != null, QmsInspectionScheme::getProductId, productId)
                .eq(operationId != null, QmsInspectionScheme::getOperationId, operationId)
                .eq(StringUtils.hasText(inspectionType), QmsInspectionScheme::getInspectionType, inspectionType)
                .eq(StringUtils.hasText(status), QmsInspectionScheme::getStatus, status)
                .orderByDesc(QmsInspectionScheme::getCreatedTime);
        return queryWrapper;
    }
}
