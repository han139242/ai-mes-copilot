package com.neuedu.ems.mapper.mfg;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mfg.MfgSupplyAllocation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供给分配与库存预留持久层接口。
 */
@Mapper
public interface MfgSupplyAllocationMapper extends BaseMapper<MfgSupplyAllocation> {
}
