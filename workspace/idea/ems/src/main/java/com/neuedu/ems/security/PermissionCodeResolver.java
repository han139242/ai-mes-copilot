package com.neuedu.ems.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将实际 API 路径和 HTTP 方法解析为 sys_menu.permission_code。
 */
@Component
public class PermissionCodeResolver {

    private static final Set<String> PERMISSION_EXEMPT_PATHS = Set.of(
            "/api/auth/info", "/api/auth/logout", "/api/hello");

    private static final Set<String> DOMAINS = Set.of(
            "sys", "org", "mdm", "process", "factory", "mfg", "wms", "qms", "eam", "trace");

    private static final Map<String, String> RESOURCE_ALIASES = Map.of(
            "wms/replenishment-suggestion", "replenishment");

    /** 通用三段式路径：/api/{域}/{资源}/{后续任意段}。 */
    private static final Pattern API_PATTERN = Pattern.compile("^/api/([a-z]+)/([a-z0-9-]+)(?:/.*)?$");

    private static final List<SpecialRule> SPECIAL_RULES = List.of(
            rule("POST", "/api/mdm/product-parameter/[^/]+/(confirm|reopen)", "mdm:product-parameter:confirm"),
            rule("POST", "/api/qms/inspection-standard/[^/]+/(confirm|reopen)", "qms:inspection-standard:confirm"),
            rule("GET", "/api/mdm/bom/[^/]+/explosion", "mdm:bom:expand"),
            rule("PUT", "/api/sys/user/[^/]+/reset-password", "sys:user:reset-password"),
            rule("PUT", "/api/sys/user/[^/]+/roles", "sys:user:assign-role"),
            rule("PUT", "/api/sys/role/[^/]+/menus", "sys:role:assign-menu"),
            rule("PUT", "/api/sys/notification/read-all", "sys:notification:read"),
            rule("PUT", "/api/sys/notification/[^/]+/read", "sys:notification:read"),
            rule("PUT", "/api/sys/notification/[^/]+/ack", "sys:notification:ack"),
            rule("GET", "/api/sys/notification/audit/page", "sys:notification:audit"),
            rule("POST", "/api/sys/file/upload", "sys:file:upload"),
            rule("GET", "/api/sys/file/download/[^/]+", "sys:file:download"),
            rule("POST", "/api/mfg/andon-alert/trigger", "mfg:andon-alert:trigger"),
            rule("PUT", "/api/mfg/andon-alert/[^/]+/close", "mfg:andon-alert:close"),
            rule("PUT", "/api/mfg/exception/[^/]+/handle", "mfg:exception:handle"),
            rule("PUT", "/api/mfg/production-order/[^/]+/release", "mfg:production-order:release"),
            rule("PUT", "/api/mfg/production-order/[^/]+/withdraw", "mfg:production-order:withdraw"),
            rule("PUT", "/api/mfg/sales-order/[^/]+/confirm", "mfg:sales-order:confirm"),
            rule("PUT", "/api/mfg/sales-order/[^/]+/withdraw", "mfg:sales-order:withdraw"),
            rule("PUT", "/api/mfg/purchase-order/[^/]+/release", "mfg:purchase-order:release"),
            rule("PUT", "/api/mfg/purchase-order/[^/]+/withdraw", "mfg:purchase-order:withdraw"),
            rule("POST", "/api/mfg/production-plan/generate/[^/]+", "mfg:production-plan:generate"),
            rule("PUT", "/api/mfg/production-plan/[^/]+/release", "mfg:production-plan:release"),
            rule("PUT", "/api/mfg/production-plan/[^/]+/withdraw", "mfg:production-plan:withdraw"),
            rule("POST", "/api/mfg/work-order/generate/[^/]+", "mfg:work-order:generate"),
            rule("POST", "/api/mfg/work-order/[^/]+/generate-material-issue", "mfg:work-order:material-issue"),
            rule("PUT", "/api/mfg/work-order/[^/]+/sync-component-handover", "mfg:work-order:material-issue"),
            rule("PUT", "/api/mfg/work-order/[^/]+/release", "mfg:work-order:release"),
            rule("PUT", "/api/mfg/work-order/[^/]+/withdraw", "mfg:work-order:withdraw"),
            rule("POST", "/api/mfg/dispatch-order/generate/[^/]+", "mfg:dispatch-order:generate"),
            rule("GET", "/api/mfg/dispatch-order/preview/[^/]+", "mfg:dispatch-order:generate"),
            rule("PUT", "/api/mfg/dispatch-order/[^/]+/release", "mfg:dispatch-order:release"),
            rule("PUT", "/api/mfg/dispatch-order/[^/]+/withdraw", "mfg:dispatch-order:withdraw"),
            rule("PUT", "/api/mfg/work-order/[^/]+/start", "mfg:work-order:start"),
            rule("PUT", "/api/mfg/task/[^/]+/start", "mfg:task:start"),
            rule("PUT", "/api/mfg/task/[^/]+/withdraw", "mfg:task:withdraw"),
            rule("PUT", "/api/mfg/task/[^/]+/finish", "mfg:task:finish"),
            rule("PUT", "/api/mfg/task/[^/]+/controlled-close", "mfg:task:controlled-close"),
            rule("PUT", "/api/mfg/task/[^/]+/resolve-defects", "mfg:task:controlled-close"),
            rule("POST", "/api/mfg/work-report", "mfg:work-report:submit"),
            rule("POST", "/api/mfg/work-report/submit", "mfg:work-report:submit"),
            rule("POST", "/api/mfg/work-report/full", "mfg:work-report:submit"),
            rule("PUT", "/api/mfg/work-report/[^/]+/audit", "mfg:work-report:audit"),
            rule("PUT", "/api/mfg/work-report/[^/]+/withdraw", "mfg:work-report:withdraw"),
            rule("POST", "/api/mfg/schedule-scenario/[^/]+/validate", "mfg:schedule-scenario:validate"),
            rule("POST", "/api/mfg/schedule-scenario/[^/]+/orders/[^/]+/ai-advice", "mfg:schedule-scenario:ai-advice"),
            rule("POST", "/api/mfg/schedule-scenario/[^/]+/ai-advice", "mfg:schedule-scenario:ai-advice"),
            rule("POST", "/api/mfg/schedule-scenario/manual-board/ai-advice", "mfg:schedule-scenario:ai-advice"),
            rule("PUT", "/api/mfg/schedule-scenario/[^/]+/confirm", "mfg:schedule-scenario:confirm"),
            rule("PUT", "/api/mfg/schedule-scenario/[^/]+/items/[^/]+/confirm", "mfg:schedule-scenario:confirm"),
            rule("POST", "/api/wms/inbound-order/generate-production/[^/]+", "wms:inbound-order:create"),
            rule("PUT", "/api/wms/inbound-order/[^/]+/confirm", "wms:inbound-order:confirm"),
            rule("PUT", "/api/wms/inbound-order/[^/]+/withdraw", "wms:inbound-order:withdraw"),
            rule("PUT", "/api/wms/outbound-order/[^/]+/confirm", "wms:outbound-order:confirm"),
            rule("PUT", "/api/wms/outbound-order/[^/]+/transfer-to-line-side", "wms:outbound-order:confirm"),
            rule("PUT", "/api/wms/outbound-order/[^/]+/withdraw", "wms:outbound-order:withdraw"),
            rule("PUT", "/api/wms/sales-outbound/[^/]+/confirm", "wms:sales-outbound:confirm"),
            rule("POST", "/api/wms/sales-outbound/[^/]+/inspection", "wms:sales-outbound:inspection"),
            rule("PUT", "/api/org/team-schedule/[^/]+/release", "org:team-schedule:release"),
            rule("PUT", "/api/wms/return-order/[^/]+/confirm", "wms:return-order:confirm"),
            rule("PUT", "/api/wms/return-order/[^/]+/withdraw", "wms:return-order:withdraw"),
            rule("PUT", "/api/wms/stocktake-order/[^/]+/finish", "wms:stocktake-order:finish"),
            rule("POST", "/api/wms/replenishment-suggestion/generate", "wms:replenishment:generate"),
            rule("PUT", "/api/qms/inspection-order/[^/]+/submit(?:-piece)?", "qms:inspection-order:submit"),
            rule("PUT", "/api/qms/inspection-order/[^/]+/reopen-production-defects", "qms:inspection-order:withdraw"),
            rule("PUT", "/api/qms/inspection-order/[^/]+/withdraw", "qms:inspection-order:withdraw"),
            rule("POST", "/api/qms/inspection-result/batch", "qms:inspection-result:batch-save"),
            rule("PUT", "/api/qms/nonconforming-order/[^/]+/approve", "qms:nonconforming-order:approve"),
            rule("POST", "/api/qms/nonconforming-order/generate-from-inspection/[^/]+", "qms:nonconforming-order:generate"),
            rule("PUT", "/api/qms/nonconforming-order/[^/]+/complete-disposition", "qms:nonconforming-order:complete"),
            rule("PUT", "/api/eam/repair-order/[^/]+/assign", "eam:repair-order:assign"),
            rule("PUT", "/api/eam/repair-order/[^/]+/close", "eam:repair-order:close"),
            rule("POST", "/api/trace/barcode/generate", "trace:barcode:generate"),
            rule("GET", "/api/trace/barcode/scan/[^/]+", "trace:barcode:scan"),
            rule("GET", "/api/trace/product-lot/lot-no/[^/]+", "trace:product-lot:trace"),
            rule("GET", "/api/trace/material-usage/batch/[^/]+", "trace:material-usage:reverse-trace")
    );

    public Optional<String> resolve(HttpServletRequest request) {
        return resolve(request.getMethod(), request.getRequestURI());
    }

    public Optional<String> resolve(String method, String requestUri) {
        String path = normalize(requestUri);
        String special = resolveSpecial(method, path);
        if (special != null) {
            return Optional.of(special);
        }
        String fixed = resolveFixedPrefix(path);
        if (fixed != null) {
            return Optional.of(fixed);
        }
        return resolveByPattern(method, path);
    }

    /**
     * 按显式规则匹配权限码，显式规则优先于通用解析。
     *
     * @param method 请求方法
     * @param path   规范化后的请求路径
     * @return 命中的权限码，未命中返回 null
     */
    private String resolveSpecial(String method, String path) {
        for (SpecialRule rule : SPECIAL_RULES) {
            if (rule.method().equalsIgnoreCase(method) && rule.path().matcher(path).matches()) {
                return rule.permissionCode();
            }
        }
        return null;
    }

    /**
     * 处理整段前缀即确定权限码的接口，例如智能助手与首页看板。
     *
     * @param path 规范化后的请求路径
     * @return 权限码，未命中返回 null
     */
    private String resolveFixedPrefix(String path) {
        if (path.equals("/api/ai") || path.startsWith("/api/ai/")) {
            return "ai:assistant:use";
        }
        if (path.equals("/api/dashboard") || path.startsWith("/api/dashboard/")) {
            return "dashboard:summary:view";
        }
        return null;
    }

    /**
     * 按「域/资源/动作」三段式通用规则解析权限码。
     *
     * @param method 请求方法
     * @param path   规范化后的请求路径
     * @return 权限码，路径不符合通用规则时返回空
     */
    private Optional<String> resolveByPattern(String method, String path) {
        java.util.regex.Matcher matcher = API_PATTERN.matcher(path);
        if (!matcher.matches() || !DOMAINS.contains(matcher.group(1))) {
            return Optional.empty();
        }
        String domain = matcher.group(1);
        String resource = RESOURCE_ALIASES.getOrDefault(domain + "/" + matcher.group(2), matcher.group(2));
        String action = resolveAction(method, path);
        return action == null ? Optional.empty() : Optional.of(domain + ":" + resource + ":" + action);
    }

    public boolean isPermissionExempt(String requestUri) {
        return PERMISSION_EXEMPT_PATHS.contains(normalize(requestUri));
    }

    /**
     * 把请求方法映射为权限动作，导出接口单独归为 export。
     *
     * @param method 请求方法
     * @param path   规范化后的请求路径
     * @return 动作名，不支持的方法返回 null
     */
    private String resolveAction(String method, String path) {
        String normalized = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "GET" -> path.endsWith("/excel") ? "export" : "view";
            case "POST" -> "create";
            case "PUT", "PATCH" -> "update";
            case "DELETE" -> "delete";
            default -> null;
        };
    }

    private String normalize(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return "";
        }
        String path = requestUri.split("\\?", 2)[0];
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static SpecialRule rule(String method, String pathPattern, String permissionCode) {
        return new SpecialRule(method, Pattern.compile("^" + pathPattern + "$"), permissionCode);
    }

    private record SpecialRule(String method, Pattern path, String permissionCode) {
    }
}
