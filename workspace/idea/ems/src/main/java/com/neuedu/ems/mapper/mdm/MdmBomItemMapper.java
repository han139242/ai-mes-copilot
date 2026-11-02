package com.neuedu.ems.mapper.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mdm.MdmBomItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * BOM 明细 Mapper 接口。
 */
@Mapper
public interface MdmBomItemMapper extends BaseMapper<MdmBomItem> {
    @Select("SELECT * FROM mdm_bom_item WHERE bom_id=#{bomId} AND deleted_flag=0 ORDER BY sequence_no,bom_item_id")
    List<MdmBomItem> activeItems(@Param("bomId") Long bomId);
}
