package com.neuedu.ems.service.impl.ai;

import java.util.*;
import java.util.regex.Pattern;

/** 只依据本轮失败条件给恢复入口；入口仍须重新预览和确认。 */
final class AiCommandRecovery {
    private AiCommandRecovery() {}
    static List<Map<String, Object>> steps(String action, String target, List<Map<String, Object>> checks) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var check : checks) {
            if (Boolean.TRUE.equals(check.get("passed"))) {
                continue;
            }
            String code = String.valueOf(check.get("code"));
            String detail = Objects.toString(check.get("detail"), "");
            Recovery recovery = recovery(code, detail, target);
            if (seen.add(recovery.question())) {
                result.add(Map.of("label", recovery.label(), "detail", detail, "question", recovery.question()));
            }
            if (result.size() == 3) {
                break;
            }
        }
        return result;
    }

    private record Recovery(String label, String question) { }

    private static Recovery recovery(String code, String detail, String target) {
        var inspection = Pattern.compile("(?:[A-Za-z0-9]+-)?QI-[A-Za-z0-9-]+").matcher(detail);
        if (inspection.find()) {
            return new Recovery("填写关联检验结果", "提交检验 " + inspection.group());
        }
        if (detail.contains("上级") && detail.contains("下达")) {
            return new Recovery("先下达上级工单", "查询 " + target + " 的上级工单，并给出下达操作");
        }
        return recoveryForCode(code, detail, target);
    }

    private static Recovery recoveryForCode(String code, String detail, String target) {
        return switch(code) {
            case "WORK_ORDER_STATUS" ->
                detail.contains("DRAFT") ? new Recovery("先下达这张工单", "下达工单 " + target) : fallbackRecovery(target);
            case "TARGET_LOCATION" ->
                new Recovery("选择本产线线边库位", "查询 " + target + " 所属产线的线边库位，并给出可用的转线边目标库位");
            case "MATERIAL_AVAILABLE", "MATERIAL_KIT" ->
                new Recovery("核对库存、领料与线边供给", "查询 " + target + " 还缺哪些物料、哪些已领到线边，并给出处理步骤");
            case "TASK_READY" ->
                new Recovery("核对派工和工序任务", "查询 " + target + " 的派工和工序任务，下一步怎么办");
            default ->
                recoveryForCategory(code, target);
        };
    }

    private static Recovery recoveryForCategory(String code, String target) {
        if (code.contains("WAREHOUSE")) {
            return new Recovery("重新选择领料仓库", "查询 " + target + " 的工单类型和领用物料，核对原料仓或半成品仓后重新预览");
        }
        if (code.contains("PREDECESSOR")) {
            return new Recovery("先完成前序报工和审核", "查询 " + target + " 尚未完成的前序任务及报工审核");
        }
        return recoveryForResult(code, target);
    }

    private static Recovery recoveryForResult(String code, String target) {
        if (code.contains("PIECE") || code.contains("INSPECTION")) {
            return new Recovery("填写实际检验结果", "提交检验 " + target);
        }
        if (code.contains("REPORT") || code.contains("QUANTITY")) {
            return new Recovery("补齐实际报工数量与用料", "给 " + target + " 提交报工");
        }
        return fallbackRecovery(target);
    }

    private static Recovery fallbackRecovery(String target) {
        return new Recovery("核对阻断原因", "查询 " + target + " 的当前状态和下一步");
    }
}
