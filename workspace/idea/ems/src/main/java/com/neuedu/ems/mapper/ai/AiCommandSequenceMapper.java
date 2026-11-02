package com.neuedu.ems.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.ai.AiCommandSequence;
import org.apache.ibatis.annotations.Mapper;

/**
 * 多步命令序列记录 Mapper。
 */
@Mapper
public interface AiCommandSequenceMapper extends BaseMapper<AiCommandSequence> {
}
