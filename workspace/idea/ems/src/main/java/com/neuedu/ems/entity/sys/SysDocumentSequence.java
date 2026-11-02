package com.neuedu.ems.entity.sys;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 永久保留的每日业务流水，删除单据不回收已用序号。 */
@Data
@TableName("sys_document_sequence")
public class SysDocumentSequence {
    @TableId(value="sequence_id", type=IdType.AUTO)
    private Long sequenceId;
    private String scopePrefix;
    private String documentType;
    private LocalDate businessDate;
    private Long currentValue;
    private LocalDateTime updatedTime;
}
