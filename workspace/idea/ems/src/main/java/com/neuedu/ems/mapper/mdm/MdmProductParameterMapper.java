package com.neuedu.ems.mapper.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mdm.MdmProductParameter;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MdmProductParameterMapper extends BaseMapper<MdmProductParameter> {
    @Select("SELECT * FROM mdm_product_parameter WHERE parameter_id=#{id} AND deleted_flag=0 FOR UPDATE")
    MdmProductParameter lockActive(@Param("id") Long id);
}
