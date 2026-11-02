package com.neuedu.ems.mapper.mfg;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.mfg.MfgGenerateRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 生产工单生成命令幂等记录持久层接口。
 */
@Mapper
public interface MfgGenerateRequestMapper extends BaseMapper<MfgGenerateRequest> {
}
