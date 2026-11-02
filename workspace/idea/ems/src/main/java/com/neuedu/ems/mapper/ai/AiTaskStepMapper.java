package com.neuedu.ems.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.ai.AiTaskStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * AiTaskStep Mapper。
 */
@Mapper
public interface AiTaskStepMapper extends BaseMapper<AiTaskStep> {

    /**
     * 统计某个任务定义从某时刻起实际执行过的步骤次数，用于「每日调用量上限」。
     *
     * <p>按步骤计而不是按实例计：一条定义如果配了五步，跑一次就是五次领域服务调用，
     * 按实例数限流会让多步定义悄悄突破调用预算。</p>
     */
    @Select("SELECT COUNT(*) FROM ai_task_step s "
            + "JOIN ai_task_instance i ON i.instance_id = s.instance_id "
            + "WHERE s.deleted_flag = 0 AND i.deleted_flag = 0 "
            + "AND i.definition_id = #{definitionId} "
            + "AND s.created_time >= #{from}")
    long countStepsSince(@Param("definitionId") Long definitionId, @Param("from") LocalDateTime from);
}
