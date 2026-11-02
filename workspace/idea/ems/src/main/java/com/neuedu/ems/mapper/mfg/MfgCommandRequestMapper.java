package com.neuedu.ems.mapper.mfg;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mfg.MfgCommandRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 生产与仓储命令请求幂等记录持久层接口。
 */
@Mapper
public interface MfgCommandRequestMapper extends BaseMapper<MfgCommandRequest> {
}
