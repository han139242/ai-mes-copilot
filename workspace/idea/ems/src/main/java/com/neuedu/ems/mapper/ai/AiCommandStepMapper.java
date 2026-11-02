package com.neuedu.ems.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.ai.AiCommandStep;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 写命令步骤流水 Mapper。
 */
@Mapper
public interface AiCommandStepMapper extends BaseMapper<AiCommandStep> {
}
