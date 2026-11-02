package com.neuedu.ems.mapper.qms;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.qms.QmsInspectionScheme;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检验方案管理 Mapper 接口。
 */
@Mapper
public interface QmsInspectionSchemeMapper extends BaseMapper<QmsInspectionScheme> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM qms_inspection_scheme WHERE scheme_id=#{id} AND deleted_flag=0 FOR UPDATE")
    QmsInspectionScheme lockActive(@org.apache.ibatis.annotations.Param("id") Long id);
}
