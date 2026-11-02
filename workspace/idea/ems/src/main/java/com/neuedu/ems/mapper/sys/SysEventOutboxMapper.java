package com.neuedu.ems.mapper.sys;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.sys.SysEventOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SysEventOutboxMapper extends BaseMapper<SysEventOutbox> {

    @Insert("""
            INSERT IGNORE INTO sys_event_outbox
              (event_key, aggregate_type, aggregate_id, event_type, payload_json, status, retry_count,
               next_retry_time, last_error, occurred_time, processed_time, remark, version,
               created_by, created_time, updated_by, updated_time, deleted_flag)
            VALUES
              (#{eventKey}, #{aggregateType}, #{aggregateId}, #{eventType}, #{payloadJson}, #{status}, #{retryCount},
               #{nextRetryTime}, #{lastError}, #{occurredTime}, #{processedTime}, #{remark}, #{version},
               #{createdBy}, #{createdTime}, #{updatedBy}, #{updatedTime}, #{deletedFlag})
            """)
    int insertIgnore(SysEventOutbox outbox);

    @Update("""
            UPDATE sys_event_outbox
            SET status = 'PROCESSING', updated_time = #{now}, version = version + 1
            WHERE outbox_id = #{outboxId} AND deleted_flag = 0
              AND status IN ('PENDING', 'FAILED')
              AND retry_count < #{maxRetries}
              AND (next_retry_time IS NULL OR next_retry_time <= #{now})
            """)
    int claim(@Param("outboxId") Long outboxId, @Param("now") LocalDateTime now,
              @Param("maxRetries") int maxRetries);

    @Update("""
            UPDATE sys_event_outbox
            SET status = 'FAILED', next_retry_time = #{now},
                last_error = 'PROCESSING_TIMEOUT', updated_time = #{now}, version = version + 1
            WHERE deleted_flag = 0 AND status = 'PROCESSING' AND updated_time < #{staleBefore}
            """)
    int resetStale(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    /**
     * 周期性重复事件再次触发时，把已有的投递记录重新放回待投递队列。
     * <p>
     * 预警这类事件用固定的事件键（{@code ALERT:<预警编号>}），第二次提醒会撞上
     * {@code uk_sys_event_outbox_event_key}，{@code INSERT IGNORE} 静默丢掉，
     * 表现为"冷却到期后不再提醒"。这里把原记录的内容替换成最新载荷并重置重试状态，
     * 让它重新走一遍投递；投递端按同一事件键刷新收件箱里的那一行，不会新增记录。
     * </p>
     */
    @Update("""
            UPDATE sys_event_outbox
            SET payload_json = #{payloadJson}, status = 'PENDING', retry_count = 0,
                next_retry_time = NULL, last_error = NULL, processed_time = NULL,
                occurred_time = #{occurredTime}, updated_by = #{updatedBy}, updated_time = #{now},
                version = version + 1
            WHERE event_key = #{eventKey} AND deleted_flag = 0
            """)
    int revive(@Param("eventKey") String eventKey, @Param("payloadJson") String payloadJson,
               @Param("occurredTime") LocalDateTime occurredTime, @Param("updatedBy") Long updatedBy,
               @Param("now") LocalDateTime now);
}
