package com.neuedu.ems.entity.mfg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * mfg_command_request
 *
 * <p>生产与仓储命令的通用幂等记录。生成工单之外的领料单生成、领料转线边等命令
 * 也按"命令 + 请求键"登记请求摘要与结果主键，做到同一请求重放返回原结果、
 * 参数改变必须拒绝，不能静默重做一遍。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_command_request")
public class MfgCommandRequest {

    public static final String COMPLETED = "COMPLETED";

    /** 生成生产领料单。 */
    public static final String COMMAND_MATERIAL_ISSUE_GENERATE = "MATERIAL_ISSUE_GENERATE";
    /** 领料单转线边确认。 */
    public static final String COMMAND_LINE_SIDE_TRANSFER = "LINE_SIDE_TRANSFER";

    public static final String AGGREGATE_WORK_ORDER = "MFG_WORK_ORDER";
    public static final String AGGREGATE_OUTBOUND_ORDER = "WMS_OUTBOUND_ORDER";

    @TableId(value = "request_id", type = IdType.AUTO)
    private Long requestId;
    private String requestKey;
    private String command;
    private String aggregateType;
    private Long aggregateId;
    /** 请求摘要：参数变化时拒绝复用同一幂等键。 */
    private String requestDigest;
    private Long resultId;
    private Long operatorId;
    private String status;
    private String remark;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
