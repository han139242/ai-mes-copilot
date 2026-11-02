package com.neuedu.ems.mapper.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mdm.MdmBom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/**
 * BOM Mapper 接口。
 */
@Mapper
public interface MdmBomMapper extends BaseMapper<MdmBom> {
    @Select("SELECT * FROM mdm_bom WHERE bom_id=#{id} AND deleted_flag=0 FOR UPDATE")
    MdmBom lockActive(@Param("id") Long id);
}
