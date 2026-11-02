package com.neuedu.ems.entity.mdm;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 受控产品参数：未知值保持空，确认必须有依据；不保存虚构实测值。 */
@Data
@TableName("mdm_product_parameter")
public class MdmProductParameter {
    @TableId(value="parameter_id",type=IdType.AUTO)
    private Long parameterId;
    private Long productId;
    private String parameterCode;
    private String parameterName;
    private String valueType;
    private String parameterValue;
    private String unitLabel;
    private String sourceReference;
    private Integer requiredFlag;
    private String status;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
