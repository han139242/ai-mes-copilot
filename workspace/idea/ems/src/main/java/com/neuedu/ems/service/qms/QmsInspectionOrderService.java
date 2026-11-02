package com.neuedu.ems.service.qms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.qms.QmsInspectionResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * 检验单 Service 接口。
 */
public interface QmsInspectionOrderService extends IService<QmsInspectionOrder> {
    /** 仅由已落库的半成品领料明细发起领用复验，不能空白创建。 */
    QmsInspectionOrder createStockReleaseInspection(Long outboundId, Long detailId, Long schemeId, Long operatorId);

    /**
     * 分页查询检验单，支持单号、类型、方案、工单、任务、报工、批次、检验员、状态和结果筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 检验单号关键字
     * @param inspectionType 检验类型
     * @param schemeId 方案ID
     * @param workOrderId 工单ID
     * @param taskId 任务ID
     * @param reportId 报工ID
     * @param batchId 批次ID
     * @param inspectorId 检验员ID
     * @param status 状态
     * @param inspectionResult 检验结果
     * @return 检验单分页结果
     */
    Page<QmsInspectionOrder> pageInspectionOrders(Integer current, Integer size, String keyword, String inspectionType,
                                                  Long schemeId, Long workOrderId, Long taskId, Long reportId,
                                                  Long batchId, Long productLotId, Long inspectorId,
                                                  String status, String inspectionResult);

    /**
     * 查询检验单列表，支持单号、类型、方案、工单、任务、报工、批次、检验员、状态和结果筛选。
     * @param keyword 检验单号关键字
     * @param inspectionType 检验类型
     * @param schemeId 方案ID
     * @param workOrderId 工单ID
     * @param taskId 任务ID
     * @param reportId 报工ID
     * @param batchId 批次ID
     * @param inspectorId 检验员ID
     * @param status 状态
     * @param inspectionResult 检验结果
     * @return 检验单列表
     */
    List<QmsInspectionOrder> listInspectionOrders(String keyword, String inspectionType, Long schemeId, Long workOrderId,
                                                  Long taskId, Long reportId, Long batchId, Long productLotId, Long inspectorId,
                                                  String status, String inspectionResult);

    /**
     * 根据ID查询未删除的检验单。
     * @param inspectionId 检验单ID
     * @return 检验单实体
     */
    QmsInspectionOrder getInspectionOrderById(Long inspectionId);

    /**
     * 新增检验单。
     * @param inspectionOrder 检验单实体
     * @return 是否成功
     */
    boolean saveInspectionOrder(QmsInspectionOrder inspectionOrder);

    /**
     * 新增检验单和检验结果明细。
     * @param inspectionOrder 检验单实体
     * @param results 检验结果列表
     * @return 检验单ID
     */
    Long saveInspectionOrderFull(QmsInspectionOrder inspectionOrder, List<QmsInspectionResult> results);

    /**
     * 修改检验单。
     * @param inspectionOrder 检验单实体
     * @return 是否成功
     */
    boolean updateInspectionOrder(QmsInspectionOrder inspectionOrder);

    /**
     * 修改检验单并替换检验结果明细。
     * @param inspectionOrder 检验单实体
     * @param results 检验结果列表
     * @return 是否成功
     */
    boolean updateInspectionOrderFull(QmsInspectionOrder inspectionOrder, List<QmsInspectionResult> results);

    /**
     * 根据ID软删除检验单和检验结果。
     * @param inspectionId 检验单ID
     * @return 是否成功
     */
    boolean removeInspectionOrderById(Long inspectionId);

    void withdrawInspectionOrder(Long inspectionId, Long operatorId, String remark);

    /**
     * 提交检验单结果并结束检验单。
     * @param inspectionId 检验单ID
     * @param inspectionResult 检验结果
     * @param remark 备注
     * @param operatorId 操作人ID
     * @return 是否成功
     */
    boolean submitInspectionOrder(Long inspectionId, String inspectionResult, String remark, Long operatorId);

    /**
     * 提交带汇总数量的检验记录并结束检验单。
     * @param inspectionId 检验单ID
     * @param inspectionResult 检验结果
     * @param remark 备注
     * @param operatorId 操作人ID
     * @param sampleQuantity 抽检数量
     * @param qualifiedQuantity 合格数量
     * @param unqualifiedQuantity 不合格数量
     * @return 是否成功
     */
    boolean submitInspectionOrder(Long inspectionId, String inspectionResult, String remark, Long operatorId,
                                  BigDecimal sampleQuantity, BigDecimal qualifiedQuantity, BigDecimal unqualifiedQuantity);

    /**
     * 提交检验结果的统一入口：需要逐件全检时只接受真实逐件结果，不需要时退回汇总提交。
     *
     * 方案里有全检项时，每件每项都必须有人真检过，所以此时必须传入 pieceResults；
     * 不传就明确报错要求补充，不能自己按件数编出「合格」台账——编出来的台账会让人
     * 以为这批货真检过，比办不成危害大得多。不需要逐件时按原汇总口径提交。
     *
     * @param inspectionId 检验单ID
     * @param inspectionResult 检验结论，空时由汇总数量判定（逐件时由结果汇总得出）
     * @param remark 备注
     * @param operatorId 操作人ID
     * @param sampleQuantity 抽样数量
     * @param qualifiedQuantity 合格数量
     * @param unqualifiedQuantity 不合格数量
     * @param pieceResults 真实逐件检验结果，方案要求逐件时必填
     * @return 是否成功
     */
    boolean submitInspectionAutoPieces(Long inspectionId, String inspectionResult, String remark, Long operatorId,
                                       BigDecimal sampleQuantity, BigDecimal qualifiedQuantity,
                                       BigDecimal unqualifiedQuantity, List<QmsInspectionResult> pieceResults);

    /**
     * 逐件全检提交：每件产品的每个必检项都必须有结果，缺项直接拒绝。
     * 件结论由逐件结果汇总得出，检验单的合格/不合格数量也从件结论统计，
     * 避免只填汇总数量而没有逐件依据。
     *
     * @param inspectionId 检验单ID
     * @param results      逐件检验结果，必须带 pieceNo 和 itemId
     * @param remark       备注
     * @param operatorId   操作人ID
     * @return 是否成功
     */
    boolean submitPieceInspection(Long inspectionId, List<QmsInspectionResult> results, String remark, Long operatorId);

    /**
     * 查询某张检验单的逐件检验结果，按件号、检验项排序，供页面与追溯核对。
     *
     * @param inspectionId 检验单ID
     * @return 逐件结果列表
     */
    List<QmsInspectionResult> listPieceResults(Long inspectionId);

    QmsInspectionOrder reopenProductionDefects(Long inspectionId,Long userId,String reason);
}
