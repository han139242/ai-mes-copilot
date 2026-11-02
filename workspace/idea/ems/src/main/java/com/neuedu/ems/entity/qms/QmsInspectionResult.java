package com.neuedu.ems.entity.qms;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * qms_inspection_result
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("qms_inspection_result")
public class QmsInspectionResult {

    @TableId(value = "result_id", type = IdType.AUTO)
    private Long resultId;
    private Long inspectionId;
    /** 件号：逐件全检时标记结果属于哪一件产品，例如 L200-001。 */
    private String pieceNo;
    private Long itemId;
    private String measuredValue;
    private String result;
    private String defectDescription;
    private Long photoFileId;
    private String remark;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
