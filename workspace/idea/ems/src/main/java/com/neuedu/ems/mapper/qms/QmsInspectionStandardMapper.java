package com.neuedu.ems.mapper.qms;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.qms.QmsInspectionStandard;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检验标准管理 Mapper 接口。
 */
@Mapper
public interface QmsInspectionStandardMapper extends BaseMapper<QmsInspectionStandard> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM qms_inspection_standard WHERE standard_id=#{id} AND deleted_flag=0 FOR UPDATE")
    QmsInspectionStandard lockActive(@org.apache.ibatis.annotations.Param("id") Long id);
}
