package com.neuedu.ems.service.ai;

import com.neuedu.ems.entity.ai.AiChatMessage;

import java.util.List;
import java.util.Map;

/**
 * 自然语言计划器：把用户那句话交给大模型，产出一份结构化计划。
 *
 * <p>模型只做两件事：从能力目录里选一个能力，或判断需要向用户补问什么。
 * 参数是否齐全、对象是否存在、有没有权限、能不能执行，全部由后端判断。</p>
 */
public interface AiPlannerService {

    /**
     * 生成计划。
     *
     * @param question 用户本轮问题
     * @param history  最近几轮对话，用于理解省略的主语
     * @return 计划；模型不可用或返回不可解析时返回 null，由调用方退回规则路由
     */
    AiPlan plan(String question, List<AiChatMessage> history);

    /**
     * 看过前几轮工具的**真实返回值**之后，再决定下一步：继续查、直接答、还是补问。
     *
     * <p>这一步是「模型看不到数据库」与「按结果决定下一步」之间的桥：后端把已经查到的
     * 结果（列名、命中条数、少量样例行）作为观测交给模型，模型据此判断现有数据够不够回答问题。
     * 给模型的仍然只是查询结果的投影，不含 SQL、表名与敏感列。</p>
     *
     * @param question     用户本轮问题
     * @param observations 已经执行过的查询与它们的真实结果摘要，按执行先后排列
     * @return 下一步计划；模型不可用、未启用或返回不可解析时返回 null，调用方保留已有结果
     */
    AiPlan revise(String question, List<Map<String, Object>> observations);

    /**
     * 模型是否可用于规划。
     */
    boolean available();
}
