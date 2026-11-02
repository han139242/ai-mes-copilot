package com.neuedu.ems.service.ai;

import java.util.List;
import java.util.Map;

/**
 * 大模型给出的结构化执行计划。
 *
 * <p>模型只负责「听懂」并产出计划，真正的校验与执行由后端完成。</p>
 *
 * <p>一句话多步办理时，{@code steps} 里给出有序的多个步骤，{@code capability} 仍保留第一步的动作，
 * 这样只认单步的老调用方不需要改动也能继续工作。</p>
 *
 * @param mode        计划类型：query 查询 / command 办理 / clarify 需要补问 / chat 直接回答
 * @param capability  能力编码，mode 为 query/command 时有值
 * @param params      能力参数，缺省为空
 * @param clarify     需要补问时给用户看的问题
 * @param answer      mode 为 chat 时的回答正文
 * @param suggestions 追问建议
 * @param reason      模型给出的选择理由，用于排查
 * @param steps       多步计划的步骤列表，单步或非办理计划为空
 */
public record AiPlan(String mode, String capability, Map<String, Object> params,
                     String clarify, String answer, List<String> suggestions, String reason,
                     List<Step> steps) {

    public static final String MODE_QUERY = "query";
    public static final String MODE_COMMAND = "command";
    public static final String MODE_CLARIFY = "clarify";
    public static final String MODE_CHAT = "chat";

    /**
     * 兼容只产出单步计划的老构造方式：steps 为空。
     */
    public AiPlan(String mode, String capability, Map<String, Object> params,
                  String clarify, String answer, List<String> suggestions, String reason) {
        this(mode, capability, params, clarify, answer, suggestions, reason, List.of());
    }

    /**
     * 读取字符串参数。
     */
    public String string(String key) {
        Object value = params == null ? null : params.get(key);
        return value == null ? null : value.toString().trim();
    }

    public boolean isQuery() {
        return MODE_QUERY.equals(mode);
    }

    public boolean isCommand() {
        return MODE_COMMAND.equals(mode);
    }

    public boolean isClarify() {
        return MODE_CLARIFY.equals(mode);
    }

    /**
     * 是否是多步办理计划：办理类型且给出两步以上。
     */
    public boolean isMultiStepCommand() {
        return isCommand() && steps != null && steps.size() > 1;
    }

    /**
     * 计划里的一步。
     *
     * @param capability 该步的能力编码
     * @param targetNo   目标单据号，可空（由上一步产出时为空）
     * @param params     该步参数，可空
     */
    public record Step(String capability, String targetNo, Map<String, Object> params) {
    }
}
