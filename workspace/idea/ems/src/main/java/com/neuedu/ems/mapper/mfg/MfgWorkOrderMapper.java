package com.neuedu.ems.mapper.mfg;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 生产工单管理 Mapper 接口。
 */
@Mapper
public interface MfgWorkOrderMapper extends BaseMapper<MfgWorkOrder> {

    /**
     * 已下达工单及其实际下达时间。
     *
     * <p>实际下达时间取状态变更日志里首次变为 RELEASED 的时间，不用建单时间：
     * 工单常在创建后隔一段时间才下达，用建单时间会把等待下达的时长算成停滞。
     * 没有下达记录的旧单 releasedTime 为空，由调用方决定如何降级。</p>
     */
    @Select("""
            SELECT wo.work_order_id AS workOrderId,
                   wo.work_order_no AS workOrderNo,
                   wo.created_time AS createdTime,
                   released.changed_time AS releasedTime
            FROM mfg_work_order wo
            LEFT JOIN (
                SELECT l.biz_id AS biz_id, MIN(l.changed_time) AS changed_time
                FROM sys_status_change_log l
                WHERE l.deleted_flag = 0
                  AND l.biz_type = 'MFG_WORK_ORDER'
                  AND l.new_status = 'RELEASED'
                GROUP BY l.biz_id
            ) released ON released.biz_id = wo.work_order_id
            WHERE wo.deleted_flag = 0 AND wo.status = 'RELEASED'
            ORDER BY wo.work_order_id
            LIMIT 200
            """)
    List<Map<String, Object>> selectReleasedWorkOrders();
}
