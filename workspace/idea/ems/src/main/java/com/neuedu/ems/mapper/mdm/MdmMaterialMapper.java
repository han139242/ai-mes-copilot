package com.neuedu.ems.mapper.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mdm.MdmMaterial;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/**
 * 物料 Mapper 接口。
 */
@Mapper
public interface MdmMaterialMapper extends BaseMapper<MdmMaterial> {
    @Select("SELECT COUNT(*) FROM wms_stock_movement WHERE material_id=#{id}")
    Long movementCount(@Param("id") Long id);
}
