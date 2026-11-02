package com.neuedu.ems.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 主动异常扫描记录：每轮扫描的结论、条数与耗时，供监控页追溯。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_anomaly_scan")
public class AiAnomalyScan {

    /** 扫描主键。 */
    @TableId(value = "scan_id", type = IdType.AUTO)
    private Long scanId;

    /** 扫描编号。 */
    private String scanNo;

    /** 触发方式：SCHEDULE 定时、MANUAL 手工。 */
    private String triggerType;

    /** 执行状态：SUCCEEDED 成功、FAILED 失败。 */
    private String status;

    /** 本轮检测到的异常条数。 */
    private Integer detectedCount;

    /** 本轮新登记异常条数。 */
    private Integer openedCount;

    /** 本轮自动关闭异常条数。 */
    private Integer closedCount;

    /** 本轮发出通知条数。 */
    private Integer notifiedCount;

    /** 耗时毫秒。 */
    private Long elapsedMs;

    /** 扫描结论。 */
    private String summary;

    /** 失败原因。 */
    private String errorMessage;

    /** 乐观锁版本号。 */
    private Integer version;

    /** 创建人。 */
    private Long createdBy;

    /** 创建时间。 */
    private LocalDateTime createdTime;

    /** 更新人。 */
    private Long updatedBy;

    /** 更新时间。 */
    private LocalDateTime updatedTime;

    /** 软删除标记，0 未删 1 已删。 */
    private Integer deletedFlag;
}
