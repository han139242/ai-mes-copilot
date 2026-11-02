package com.neuedu.ems.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.ai.AiAnomalyScan;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主动异常扫描记录 Mapper。
 */
@Mapper
public interface AiAnomalyScanMapper extends BaseMapper<AiAnomalyScan> {
}
