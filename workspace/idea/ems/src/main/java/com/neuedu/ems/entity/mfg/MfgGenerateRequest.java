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
 * mfg_generate_request
 *
 * <p>生产工单生成命令的幂等记录。同一幂等键且请求摘要一致时重放原结果；
 * 同一幂等键但参数改变必须拒绝，不能静默复用。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("mfg_generate_request")
public class MfgGenerateRequest {

    public static final String COMPLETED = "COMPLETED";

    @TableId(value = "request_id", type = IdType.AUTO)
    private Long requestId;
    private String requestKey;
    private Long planId;
    private String command;
    /** 请求摘要：参数变化时拒绝复用同一幂等键。 */
    private String requestDigest;
    private Long rootWorkOrderId;
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
