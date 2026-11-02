package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import java.util.List;
import java.util.Map;

/** 统一查询入口继承原业务权限，跨部门聚合只允许 ALL 数据范围。 */
public final class AiQueryAccessPolicy {
    private AiQueryAccessPolicy() {}
    private static final Map<String, List<String>> REQUIRED = Map.ofEntries(
            Map.entry("inventory_flow", List.of("wms:inventory:view", "wms:stock-movement:view")),
            Map.entry("inventory_stock", List.of("wms:inventory:view")),
            Map.entry("inventory_below_safety", List.of("wms:inventory:view")),
            Map.entry("work_order_detail", List.of("mfg:work-order:view", "wms:outbound-order:view")),
            Map.entry("work_order_readiness", List.of("mfg:work-order:view", "wms:outbound-order:view")),
            Map.entry("work_order_start_plan", List.of("mfg:work-order:view", "wms:outbound-order:view")),
            Map.entry("production_daily", List.of("mfg:work-report:view")),
            Map.entry("defect_analysis", List.of("qms:inspection-order:view", "mfg:work-report:view")),
            Map.entry("batch_trace", List.of("trace:material-usage:reverse-trace")),
            Map.entry("schedule_capacity", List.of("mfg:production-plan:view")),
            Map.entry("order_progress", List.of("mfg:production-order:view", "mfg:production-plan:view",
                    "mfg:work-order:view", "wms:inbound-order:view", "wms:outbound-order:view")),
            Map.entry("order_schedule_simulation", List.of("mfg:production-order:view", "mfg:production-plan:view")),
            Map.entry("equipment_impact", List.of("eam:equipment:view", "mfg:task:view")),
            Map.entry("capacity_feasibility", List.of("mfg:production-plan:view", "wms:inventory:view", "mdm:bom:view")),
            Map.entry("bom_requirement", List.of("mdm:bom:view")),
            Map.entry("quality_yield", List.of("qms:inspection-order:view")),
            Map.entry("work_order_list", List.of("mfg:work-order:view")),
            Map.entry("customer_order", List.of("mfg:production-order:view")),
            Map.entry("inspection_pending", List.of("qms:inspection-order:view")));

    /** 写命令预览的意图编码。 */
    public static final String INTENT_COMMAND_PREVIEW = "command_preview";

    public static void requireIntent(AuthorizationContext context, String intent) {
        if (context == null || !context.hasPermission("ai:assistant:use")) throw new DataScopeAccessDeniedException();
        // 写命令只要求助手使用权，具体动作权限（下达、出库、检验等）由 AiCommandService 按动作逐个校验。
        if (INTENT_COMMAND_PREVIEW.equals(intent)) return;
        var permissions = REQUIRED.get(intent);
        if (permissions == null) return;
        if (!context.isSuperAdmin() && !"ALL".equals(context.dataScope())) throw new DataScopeAccessDeniedException();
        if (permissions.stream().anyMatch(permission -> !context.hasPermission(permission))) throw new DataScopeAccessDeniedException();
    }

    /**
     * 只判断账号是否具备某个查询能力的权限，不抛异常，供能力清单过滤使用。
     */
    public static boolean allowed(AuthorizationContext context, String intent) {
        if (!hasAssistantAccess(context)) {
            return false;
        }
        var permissions = REQUIRED.get(intent);
        if (permissions == null) {
            return true;
        }
        return hasFullDataScope(context) && permissions.stream().allMatch(context::hasPermission);
    }

    /** 助手使用权是所有查询能力的共同前置条件。 */
    private static boolean hasAssistantAccess(AuthorizationContext context) {
        return context != null && context.hasPermission("ai:assistant:use");
    }

    /** 跨部门聚合只对超管或 ALL 数据范围放开。 */
    private static boolean hasFullDataScope(AuthorizationContext context) {
        return context.isSuperAdmin() || "ALL".equals(context.dataScope());
    }

    public static void requireModule(AuthorizationContext context, String module) {
        String normalized = module == null ? "general" : module.trim().toLowerCase(java.util.Locale.ROOT);
        requireIntent(context, switch (normalized) {
            case "inventory", "wms" -> "inventory_stock";
            case "production", "mfg" -> "production_daily";
            case "quality", "qms" -> "defect_analysis";
            case "equipment", "eam" -> "equipment_impact";
            case "trace" -> "batch_trace";
            default -> "general";
        });
        if ("dashboard".equals(normalized) && (!context.hasPermission("dashboard:summary:view")
                || !context.isSuperAdmin() && !"ALL".equals(context.dataScope()))) throw new DataScopeAccessDeniedException();
    }
}
