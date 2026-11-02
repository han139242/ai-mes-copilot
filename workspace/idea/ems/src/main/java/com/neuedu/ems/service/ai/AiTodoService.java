package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.AuthorizationContext;

import java.util.Map;

/**
 * 我的待办聚合服务。
 *
 * <p>待办来源必须是真实业务状态（单据状态、缺料数量、计划时间、设备状态等），
 * 不是「通知已读未读」。每条待办附带可直接办理的动作，动作按「单据状态 + 账号权限 +
 * 已实现的写动作」三重过滤，办不了的动作不会出现在列表里。</p>
 */
public interface AiTodoService {

    /**
     * 聚合当前账号的待办。
     *
     * @param authorization 当前登录上下文，用于动作级权限过滤
     * @return 包含 items（待办明细）、counts（分类计数，供角标使用）、total 与 generatedAt 的数据
     */
    Map<String, Object> myTodos(AuthorizationContext authorization);
}
