package com.neuedu.ems.service.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.neuedu.ems.entity.ai.AiTaskDefinition;
import com.neuedu.ems.entity.ai.AiTaskInstance;

import java.util.List;
import java.util.Map;

/**
 * AI 多步任务 Service 接口。
 *
 * <p>把「一件事办完接着办下一件」做成可配置的链路：定义负责描述步骤，
 * 实例负责幂等与断点，步骤流水负责每一步的结果单据与失败原因。</p>
 */
public interface AiTaskService {

    /** 概览指标。 */
    Map<String, Object> overview();

    /** 定义分页。 */
    Page<AiTaskDefinition> pageDefinitions(Integer current, Integer size, String status, String keyword);

    /** 定义详情。 */
    AiTaskDefinition getDefinition(Long definitionId);

    /** 新建定义，返回定义ID。 */
    Long saveDefinition(AiTaskDefinition definition);

    /** 修改定义。 */
    boolean updateDefinition(AiTaskDefinition definition);

    /** 软删除定义。 */
    boolean removeDefinition(Long definitionId);

    /** 变更定义状态。 */
    boolean changeStatus(Long definitionId, String status);

    /** 预览：只读展示步骤与触发方式，不执行。 */
    Map<String, Object> preview(Long definitionId);

    /** 手动触发一次，返回实例详情。 */
    Map<String, Object> run(Long definitionId, Long operatorId);

    /** 事件触发：按事件键与事件源执行所有匹配的已启用定义，返回执行的实例数。 */
    int runByEvent(String eventKey, Long aggregateId, String aggregateType, String bizNo);

    /** 定时入口：执行到期的 SCHEDULE 定义，并续跑可恢复的中断实例。 */
    int runScheduled();

    /** 启动恢复：把上次进程遗留的 RUNNING 实例标记为可从当前步续跑。 */
    int recoverInterrupted();

    /** 实例分页。 */
    Page<AiTaskInstance> pageInstances(Integer current, Integer size, Long definitionId, String status);

    /** 实例详情，含步骤流水。 */
    Map<String, Object> getInstanceDetail(Long instanceId);

    /** 把开工处置方案落成一条草稿任务定义：先下达来源计划、再下达组件工单、最后复查开工条件。 */
    Map<String, Object> createDefinitionFromStartPlan(String workOrderNo, Long operatorId);

    /** 续跑一个中断实例。 */
    Map<String, Object> resume(Long instanceId, Long operatorId);

    /** 取消一个还在跑或等人补充的实例；已成功完成的不能取消。 */
    Map<String, Object> cancel(Long instanceId, Long operatorId, String reason);

    /** 超时收敛：把超过各自 deadline_time 仍停在 RUNNING 的实例标记为 TIMEOUT，返回处理条数。 */
    int expireOverdue();

    /** 可用步骤能力清单。 */
    List<Map<String, Object>> stepCapabilities();
}
