package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.entity.ai.AiChatMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 准备语义判断需要的约束和页面定位线索，不替代业务校验。 */
final class AiConversationUnderstanding {
    private static final Pattern COMPLEX = Pattern.compile("然后|接着|之后|顺便|并且|先别|先不|暂时不|不要|不是|而是|换成|改成|上面|刚才|前面|第[一二三四五六七八九十0-9]+个|只想|只看|只查");
    private static final Pattern QUERY_ONLY = Pattern.compile("(?:只|仅)(?:想)?(?:查|看|了解|知道|分析|比较|评估|推演)|(?:不|暂不|先不)(?:办理|操作|修改)");
    private static final Pattern NEGATED_ACTION = Pattern.compile("(?:先别|先不|暂时不|暂时不要|不要|别)(?:\\s*(?:再|继续|帮我|替我|直接|自动|马上|立刻)){0,3}\\s*(?:下达|生成|创建|安排|排产|领料|移库|开工|报工|入库|出库|发货|审核|改)");
    private static final Pattern LATER_COMMAND = Pattern.compile("(?:然后|接着|之后|而是|改成|改为|还要|随后|再)\\s*(?:请|帮我|替我)?\\s*(?:把[^，。；]{0,100})?(?:下达|生成|创建|安排|排产|领料|移库|开工|报工|入库|出库|发货|审核)");
    private static final List<String> REFERENCES = List.of("workOrderNo", "orderNo", "productionOrderNo", "taskNo",
            "dispatchOrderNo", "inspectionNo", "inboundNo", "outboundNo", "productCode", "materialCode", "warehouseCode");

    private AiConversationUnderstanding() { }

    static boolean preferSemanticPlan(String question, Map<String, Object> context) {
        if (!StringUtils.hasText(question)) return false;
        boolean completedFollowup = question.matches("(?s).*(?:完了|完成了|做好了|已完成|已经).*(?:然后|接下来|下一步).*")
                && !hasLaterCommand(question);
        boolean constrained = !completedFollowup && COMPLEX.matcher(question).find();
        boolean pageReference = !referenceHints(context).isEmpty()
                && question.matches("(?s).*(?:这张|这个|这笔|该单|当前|这里|本单).*");
        return constrained || pageReference;
    }

    static boolean queryOnly(String question) {
        if (!StringUtils.hasText(question)) return false;
        if (hasLaterCommand(question)) return false;
        // 不将「先预览、别执行」误当成禁止预览；限制的是具体业务动作。
        return QUERY_ONLY.matcher(question).find()
                || (NEGATED_ACTION.matcher(question).find()
                && question.matches("(?s).*(?:看看|查|了解|分析|模拟|推演|比较|评估).*"));
    }

    private static boolean hasLaterCommand(String question) {
        var matcher = LATER_COMMAND.matcher(question);
        while (matcher.find()) {
            String prefix = question.substring(0, matcher.start());
            int boundary = Math.max(prefix.lastIndexOf('，'), Math.max(prefix.lastIndexOf('。'), prefix.lastIndexOf('；')));
            String clausePrefix = prefix.substring(boundary + 1);
            if (!clausePrefix.matches("(?s).*(?:不要|别|不用|不必|不需要|先不)[^，。；]{0,16}")) return true;
        }
        return false;
    }

    static boolean hasWorkOrderHint(Map<String, Object> context) {
        String number = referenceHints(context).get("workOrderNo");
        return number != null && number.matches("(?i)(?:[A-Z0-9]+-)?WO-[A-Z0-9-]+");
    }

    static List<AiChatMessage> planningHistory(List<AiChatMessage> history, Map<String, Object> context) {
        Map<String, String> hints = referenceHints(context);
        String path = context != null && context.get("pagePath") instanceof String value
                && value.matches("/[A-Za-z0-9/_-]{0,150}") ? value : "";
        if (hints.isEmpty() && path.isEmpty()) return history;
        List<AiChatMessage> result = new ArrayList<>();
        result.add(new AiChatMessage("user", "本轮页面定位线索（不是业务事实，也不是执行授权；本轮明确指定的对象优先）："
                + hints + (path.isEmpty() ? "" : ", pagePath=" + path)));
        int from = Math.max(0, history.size() - 18);
        result.addAll(history.subList(from, history.size()));
        return result;
    }

    private static Map<String, String> referenceHints(Map<String, Object> context) {
        Map<String, String> result = new LinkedHashMap<>();
        if (context == null) return result;
        for (String key : REFERENCES) {
            Object value = context.get(key);
            if (value instanceof String text && text.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
                result.put(key, text.trim());
            }
        }
        return result;
    }
}
