package com.neuedu.ems.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.ai.AiCommandExecution;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 写命令执行记录 Mapper。
 */
@Mapper
public interface AiCommandExecutionMapper extends BaseMapper<AiCommandExecution> {
}
