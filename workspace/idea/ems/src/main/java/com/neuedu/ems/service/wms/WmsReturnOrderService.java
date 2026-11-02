package com.neuedu.ems.service.wms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.entity.wms.WmsReturnDetail;
import com.neuedu.ems.entity.wms.WmsReturnOrder;
import com.neuedu.ems.vo.wms.WmsReturnableDetailView;

import java.util.List;

/**
 * 退料单管理 Service 接口。
 */
public interface WmsReturnOrderService extends IService<WmsReturnOrder> {

    /**
     * 分页查询退料单，支持单号、类型、工单、申请人和审核状态筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 关键字，匹配退料单号
     * @param returnType 退料类型
     * @param workOrderId 工单ID
     * @param applicantId 申请人ID
     * @param auditStatus 审核状态
     * @return 退料单分页结果
     */
    Page<WmsReturnOrder> pageReturnOrders(Integer current, Integer size, String keyword, String returnType,
                                          Long workOrderId, Long applicantId, String auditStatus);

    /**
     * 查询退料单列表，支持单号、类型、工单、申请人和审核状态筛选。
     * @param keyword 关键字，匹配退料单号
     * @param returnType 退料类型
     * @param workOrderId 工单ID
     * @param applicantId 申请人ID
     * @param auditStatus 审核状态
     * @return 退料单列表
     */
    List<WmsReturnOrder> listReturnOrders(String keyword, String returnType, Long workOrderId, Long applicantId, String auditStatus);

    /**
     * 根据退料单ID查询详情。
     * @param returnId 退料单ID
     * @return 退料单实体
     */
    WmsReturnOrder getReturnOrderById(Long returnId);

    /**
     * 列出指定工单当前可退的来源领料明细，供退料界面一次性带出全部可退项。
     *
     * <p>只读取数据，不建单、不预占库存。可退量口径与保存退料单时的校验一致，
     * 因此界面列出的数量就是后端允许提交的上限。</p>
     * @param workOrderId 生产工单ID
     * @return 可退明细清单，没有可退项时返回空列表
     */
    List<WmsReturnableDetailView> listReturnableDetails(Long workOrderId);

    /**
     * 查询工单可退明细，并排除指定退料单已占用的数量。
     *
     * <p>编辑已有退料单时必须排除它自己，否则本单已填的数量会被重复扣掉，
     * 界面给出的上限比后端实际允许的更小。<p>
     * @param workOrderId 生产工单ID
     * @param excludedReturnId 要排除的退料单ID，新增时可传 null
     * @return 可退明细清单，没有可退项时返回空列表
     */
    List<WmsReturnableDetailView> listReturnableDetails(Long workOrderId, Long excludedReturnId);

    /**
     * 新增退料单并保存明细。
     * @param returnOrder 退料单实体
     * @param details 退料明细列表
     * @param operatorId 当前登录用户ID
     * @return 退料单ID
     */
    Long saveReturnOrderFull(WmsReturnOrder returnOrder, List<WmsReturnDetail> details, Long operatorId);

    /**
     * 更新退料单。
     * @param returnOrder 退料单实体
     * @param operatorId 当前登录用户ID
     * @return 是否成功
     */
    boolean updateReturnOrder(WmsReturnOrder returnOrder, Long operatorId);

    /**
     * 更新退料单并替换明细。
     * @param returnOrder 退料单实体
     * @param details 退料明细列表
     * @param operatorId 当前登录用户ID
     * @return 是否成功
     */
    boolean updateReturnOrderFull(WmsReturnOrder returnOrder, List<WmsReturnDetail> details, Long operatorId);

    /**
     * 根据ID软删除退料单和明细。
     * @param returnId 退料单ID
     * @return 是否成功
     */
    boolean removeReturnOrderById(Long returnId);

    void withdrawReturnOrder(Long returnId, Long operatorId, String remark);

    /**
     * 确认退料入库，回加库存并写入库存流水。
     * @param returnId 退料单ID
     * @param operatorId 操作人ID
     * @param remark 备注
     * @return 是否成功
     */
    boolean confirmReturnOrder(Long returnId, Long operatorId, String remark);
}
