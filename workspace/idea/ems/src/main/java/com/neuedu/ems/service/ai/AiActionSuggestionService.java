package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.AuthorizationContext;

import java.util.List;
import java.util.Map;

/**
 * 回答附带的操作入口建议。
 *
 * <p>只推荐「现在真的能点、点了真的能办」的动作：候选动作必须有真实实现，
 * 且目标单据当前状态允许执行、当前账号也有对应权限。查不到可办单据时返回空列表，
 * 宁可少给按钮，也不给点了会失败的按钮。</p>
 */
public interface AiActionSuggestionService {

    /**
     * 操作入口建议。
     *
     * @param intent        本轮意图编码
     * @param params        本轮抽取到的参数
     * @param rows          查询结果的主表行（可能为空）
     * @param authorization 当前登录上下文，用于权限与数据范围过滤
     * @return 可点的操作入口列表，没有可办动作时返回空列表
     */
    List<ActionSuggestion> suggest(String intent, Map<String, Object> params,
                                   List<Map<String, Object>> rows, AuthorizationContext authorization);

    /**
     * 一个操作入口。
     *
     * @param code   动作编码；查看类动作用 {@link #KIND_VIEW} 前缀的编码
     * @param label  按钮文案
     * @param kind   类型：command 办理 / view 查看
     * @param params 执行或跳转所需参数
     * @param route  查看类动作的前端路由，办理类为空
     */
    record ActionSuggestion(String code, String label, String kind,
                            Map<String, Object> params, String route) {

        /** 办理类动作。 */
        public static final String KIND_COMMAND = "command";
        /** 查看类动作。 */
        public static final String KIND_VIEW = "view";
    }
}
