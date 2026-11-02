package com.neuedu.ems.entity.sys;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 历史单号转换记录；单据主键、条码和冻结快照继续保留。 */
@Data
@TableName("sys_document_number_alias")
public class SysDocumentNumberAlias {
    @TableId(value="alias_id", type=IdType.AUTO)
    private Long aliasId;
    private String documentType;
    private Long bizId;
    private String originalNumber;
    private String currentNumber;
    private LocalDateTime changedTime;
}
