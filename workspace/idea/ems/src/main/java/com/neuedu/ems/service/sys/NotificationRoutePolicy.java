package com.neuedu.ems.service.sys;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class NotificationRoutePolicy {

    private static final Set<String> ALLOWED_ROUTES = Set.of(
            "/mfg/tasks",
            "/mfg/work-reports",
            "/mfg/work-orders",
            "/mfg/production-plans",
            "/mfg/andon-alerts",
            "/mfg/resource-conflicts",
            "/mfg/schedule-workbench",
            "/qms/inspection-orders",
            "/qms/nonconforming-orders",
            "/wms/inbound-orders",
            "/wms/outbound-orders",
            "/wms/return-orders",
            "/wms/stocktakes",
            "/wms/replenishment",
            "/wms/inventory",
            "/wms/material-batches",
            "/eam/equipment",
            "/eam/repair-orders",
            "/eam/maintenance-plans",
            "/eam/spot-check-plans",
            "/ai/alert",
            // 自动化规则的失败/待处置通知会带这条前端路由，缺了就会被 outbox 投递策略丢弃。
            "/ai/automation"
    );

    public boolean isAllowed(String routePath) {
        return routePath != null && ALLOWED_ROUTES.contains(routePath);
    }
}
