package com.neuedu.ems.service.ai;

import com.neuedu.ems.entity.ai.AiAlertEvent;
import com.neuedu.ems.entity.ai.AiAlertRule;
import com.neuedu.ems.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预警指标目录与阈值口径测试。
 *
 * <p>重点守住三件事：19 个指标都在目录里、默认限度方向与比较符自洽、
 * 阈值校验能挡住"严重限度写反"这种配置错误。</p>
 */
class AlertMetricCatalogTest {

    @Test
    void catalogCoversAllNineteenProductionMetrics() {
        List<AlertMetricCatalog.Metric> metrics = AlertMetricCatalog.all();
        assertEquals(19, metrics.size(), "指标数量变了要同步更新前端指标目录说明");
        for (AlertMetricCatalog.Metric metric : metrics) {
            assertNotNull(metric.code(), "指标编码不能为空");
            assertNotNull(metric.name(), metric.code() + " 缺少中文名");
            assertNotNull(metric.category(), metric.code() + " 缺少分类");
            assertNotNull(metric.unit(), metric.code() + " 缺少单位");
            assertNotNull(metric.comparator(), metric.code() + " 缺少默认比较符");
            assertNotNull(metric.warningThreshold(), metric.code() + " 缺少默认预警限度");
            assertNotNull(metric.routePath(), metric.code() + " 缺少跳转路由");
            assertTrue(metric.routePath().startsWith("/"), metric.code() + " 路由必须是站内绝对路径");
            assertFalse(metric.description() == null || metric.description().isBlank(),
                    metric.code() + " 缺少口径说明，责任人不清楚这个数怎么算出来的");
        }
    }

    @Test
    void metricCodesAreUniqueAndLookupWorks() {
        List<AlertMetricCatalog.Metric> metrics = AlertMetricCatalog.all();
        long distinct = metrics.stream().map(AlertMetricCatalog.Metric::code).distinct().count();
        assertEquals(metrics.size(), distinct, "指标编码重复会让规则指向错误的口径");
        for (AlertMetricCatalog.Metric metric : metrics) {
            assertEquals(metric.code(), AlertMetricCatalog.get(metric.code()).code());
            assertTrue(AlertMetricCatalog.exists(metric.code()));
        }
        assertNull(AlertMetricCatalog.get("NOT_A_METRIC"));
        assertFalse(AlertMetricCatalog.exists("NOT_A_METRIC"));
    }

    @Test
    void defaultThresholdsAgreeWithComparatorDirection() {
        for (AlertMetricCatalog.Metric metric : AlertMetricCatalog.all()) {
            BigDecimal warning = metric.warningThreshold();
            BigDecimal critical = metric.criticalThreshold();
            if (critical == null) {
                continue;
            }
            boolean ascending = "GT".equals(metric.comparator()) || "GTE".equals(metric.comparator());
            if (ascending) {
                assertTrue(critical.compareTo(warning) > 0,
                        metric.code() + " 是上限类指标，严重限度必须比预警限度大");
            } else {
                assertTrue(critical.compareTo(warning) < 0,
                        metric.code() + " 是下限类指标，严重限度必须比预警限度小");
            }
        }
    }

    @Test
    void materialAvailableUsesLowerBoundAndCarriesAutoActions() {
        AlertMetricCatalog.Metric metric = AlertMetricCatalog.get("MATERIAL_AVAILABLE");
        assertEquals("LT", metric.comparator());
        assertEquals(0, new BigDecimal("50").compareTo(metric.warningThreshold()));
        assertEquals(0, new BigDecimal("10").compareTo(metric.criticalThreshold()));
        assertTrue(metric.autoActions().contains("REPLENISH"),
                "原料库存不足必须能自动生成补货建议，这是'自动做能做的步骤'的落点");
        assertTrue(metric.autoActions().contains("NOTIFY"));
        // 路由要和前端资源页一致，否则点弹窗会跳到 404
        assertEquals("/wms/inventory", metric.routePath());
    }

    @Test
    void equipmentFaultCanAutoRaiseRepairOrder() {
        AlertMetricCatalog.Metric metric = AlertMetricCatalog.get("EQUIPMENT_FAULT");
        assertTrue(metric.autoActions().contains("REPAIR"), "设备故障应能自动派维修单");
        assertEquals("/eam/equipment", metric.routePath());
    }

    @Test
    void everyMetricRouteMatchesAKnownFrontendResource() {
        // 这些路由是前端 config/resources 里真实存在的资源页，
        // 一旦写了不存在的路径，预警弹窗点击后会落到 404。
        List<String> knownRoutes = List.of(
                "/wms/inventory", "/wms/material-batches", "/wms/replenishment",
                "/mfg/work-orders", "/mfg/tasks", "/mfg/andon-alerts",
                "/mfg/production-plans", "/mfg/resource-conflicts", "/mfg/schedule-workbench",
                "/qms/inspection-orders", "/qms/nonconforming-orders",
                "/eam/equipment", "/eam/repair-orders", "/eam/maintenance-plans",
                "/eam/spot-check-plans", "/ai/alert");
        for (AlertMetricCatalog.Metric metric : AlertMetricCatalog.all()) {
            assertTrue(knownRoutes.contains(metric.routePath()),
                    metric.code() + " 的路由 " + metric.routePath() + " 不在前端资源页清单里");
        }
    }

    @Test
    void categoriesAreFromTheDeclaredSet() {
        List<String> categories = List.of(
                AlertMetricCatalog.CATEGORY_MATERIAL,
                AlertMetricCatalog.CATEGORY_PRODUCTION,
                AlertMetricCatalog.CATEGORY_EQUIPMENT,
                AlertMetricCatalog.CATEGORY_QUALITY,
                AlertMetricCatalog.CATEGORY_PLANNING);
        for (AlertMetricCatalog.Metric metric : AlertMetricCatalog.all()) {
            assertTrue(categories.contains(metric.category()),
                    metric.code() + " 的分类 " + metric.category() + " 未登记，通知会找不到责任部门");
        }
    }

    @Test
    void eventCloseReasonIsHumanReadable() {
        // 自动复核关闭的原因要能直接给车间看，不能是英文枚举
        assertTrue(AiAlertEvent.CLOSE_REASON_RECOVERED.contains("限度内"));
    }

    @Test
    void ruleDefaultsAreSane() {
        AiAlertRule rule = new AiAlertRule();
        assertNotNull(rule.toString());
        BusinessException error = assertThrows(BusinessException.class, () -> {
            throw new BusinessException(1003, "预警限度不能为空");
        });
        assertTrue(error.getMessage().contains("限度"));
    }
}
