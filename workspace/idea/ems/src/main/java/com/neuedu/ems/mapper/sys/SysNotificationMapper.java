package com.neuedu.ems.mapper.sys;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.sys.SysNotification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SysNotificationMapper extends BaseMapper<SysNotification> {

    @Insert("""
            INSERT IGNORE INTO sys_notification
              (event_key, event_type, category, severity, title, summary, biz_type, biz_id, biz_no,
               route_path, source_user_id, source_employee_id, occurred_time, expire_time, status,
               payload_json, remark, version, created_by, created_time, updated_by, updated_time, deleted_flag)
            VALUES
              (#{eventKey}, #{eventType}, #{category}, #{severity}, #{title}, #{summary}, #{bizType}, #{bizId}, #{bizNo},
               #{routePath}, #{sourceUserId}, #{sourceEmployeeId}, #{occurredTime}, #{expireTime}, #{status},
               #{payloadJson}, #{remark}, #{version}, #{createdBy}, #{createdTime}, #{updatedBy}, #{updatedTime}, #{deletedFlag})
            """)
    int insertIgnore(SysNotification notification);

    /**
     * 同一条业务事件的重复通知就地刷新，不新增一行。
     * <p>
     * 预警每 30 秒扫一轮，持续命中的事件在冷却到期后还会再次通知。
     * 如果每次都插一行新通知，消息中心就会被同一条预警刷屏——用户看到的是
     * "同一个物料 465 条未读"。这里按 {@code event_key} 覆盖摘要、级别、
     * 发生时间与未读状态，让它在收件箱里始终只占一行，同时保留"最近一次
     * 通知时间"给收件人判断新鲜度。
     * </p>
     */
    @Update("""
            UPDATE sys_notification
            SET severity = #{severity}, title = #{title}, summary = #{summary},
                route_path = #{routePath}, occurred_time = #{occurredTime},
                payload_json = #{payloadJson}, status = 'ACTIVE',
                updated_by = #{updatedBy}, updated_time = #{updatedTime}, version = version + 1
            WHERE event_key = #{eventKey} AND deleted_flag = 0
            """)
    int refresh(SysNotification notification);

    /**
     * 同一条预警再次提醒时，把已经读过的人重新标为未读，让它真的"再响一次"。
     * <p>
     * 只动未确认的收件人：确认（ACKNOWLEDGED）代表"我知道并接手了"，
     * 不该因为指标继续恶化就被悄悄撤回到未读；已读未确认则说明用户看过但没处理，
     * 情况还在恶化时应当重新提醒。
     * </p>
     */
    @Update("""
            UPDATE sys_notification_recipient r
            JOIN sys_notification n ON n.notification_id = r.notification_id AND n.deleted_flag = 0
            SET r.read_status = 'UNREAD', r.read_time = NULL, r.delivered_time = #{deliveredTime},
                r.updated_time = #{deliveredTime}, r.version = r.version + 1
            WHERE n.event_key = #{eventKey} AND r.deleted_flag = 0
              AND r.read_status = 'READ' AND r.ack_status <> 'ACKNOWLEDGED'
            """)
    int reopenUnacknowledgedRecipients(@Param("eventKey") String eventKey,
                                       @Param("deliveredTime") LocalDateTime deliveredTime);
}
