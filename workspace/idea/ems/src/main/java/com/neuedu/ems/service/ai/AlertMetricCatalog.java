package com.neuedu.ems.service.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预警指标目录。
 *
 * <p>系统能盯哪些事、每件事的默认限度是多少、命中后默认自动做什么，都集中在这里声明，
 * 前端直接读这份目录渲染"新建规则"的下拉与默认值，不需要两边各写一遍指标清单。
 * 新增一个指标只要：①在 {@code AlertMetricMapper} 加一条只读取数 SQL；
 * ②在这里加一条定义；③在 {@code AiAlertServiceImpl} 的分派表里登记。</p>
 */
public final class AlertMetricCatalog {

    /** 指标分类，用于前端分组展示。 */
    public static final String CATEGORY_MATERIAL = "MATERIAL";
    public static final String CATEGORY_PRODUCTION = "PRODUCTION";
    public static final String CATEGORY_EQUIPMENT = "EQUIPMENT";
    public static final String CATEGORY_QUALITY = "QUALITY";
    public static final String CATEGORY_PLANNING = "PLANNING";

    /**
     * 指标定义。
     *
     * @param code               指标编码
     * @param name               中文名称
     * @param category           分类
     * @param unit               单位：QUANTITY/HOURS/DAYS/COUNT/PERCENT
     * @param comparator         默认比较方式（LT 低于 / GT 高于）
     * @param warningThreshold   默认预警限度
     * @param criticalThreshold  默认严重限度，可为 null
     * @param routePath          前端处理入口
     * @param autoActions        默认自动动作，逗号分隔
     * @param description        指标口径说明，前端在规则表单里直接展示，避免使用者猜"这个数怎么算的"
     */
    public record Metric(String code, String name, String category, String unit, String comparator,
                         java.math.BigDecimal warningThreshold, java.math.BigDecimal criticalThreshold,
                         String routePath, String autoActions, String description) {
    }

    private static final List<Metric> METRICS = List.of(
            new Metric("MATERIAL_AVAILABLE", "原料可用库存", CATEGORY_MATERIAL, "QUANTITY", "LT",
                    new java.math.BigDecimal("50"), new java.math.BigDecimal("10"),
                    "/wms/inventory", "NOTIFY,REPLENISH",
                    "统计该物料在公共库存（未被线边占用）中已放行可用批次的可用数量之和，不含待检与锁定。"),
            new Metric("BATCH_AGING", "批次呆滞天数", CATEGORY_MATERIAL, "DAYS", "GT",
                    new java.math.BigDecimal("90"), null,
                    "/wms/material-batches", "NOTIFY",
                    "已放行可用、且仍有可用余量的批次，按入库日期到今天的自然天数计算。"),
            new Metric("BATCH_INSPECTION_PENDING", "待检批次积压时长", CATEGORY_MATERIAL, "HOURS", "GT",
                    new java.math.BigDecimal("24"), new java.math.BigDecimal("48"),
                    "/qms/inspection-orders", "NOTIFY,EXCEPTION",
                    "已入库但仍未放行（质量状态非 PASS/CONCESSION）的批次，按入库至今的小时数计算。"),
            new Metric("WORK_ORDER_SHORTAGE", "工单缺料种数", CATEGORY_MATERIAL, "COUNT", "GT",
                    new java.math.BigDecimal("0"), null,
                    "/mfg/work-orders", "NOTIFY,EXCEPTION",
                    "工单用料需求里未覆盖数量大于 0 的物料种数；大于 0 即表示该工单还不齐套。"),
            new Metric("WORK_ORDER_IDLE", "工单下达未开工时长", CATEGORY_PRODUCTION, "HOURS", "GT",
                    new java.math.BigDecimal("12"), new java.math.BigDecimal("36"),
                    "/mfg/work-orders", "NOTIFY,EXCEPTION",
                    "工单状态为已下达（RELEASED）且创建至今超过限度小时仍未开工。"),
            new Metric("WORK_ORDER_OVERDUE", "工单超计划完工时长", CATEGORY_PRODUCTION, "HOURS", "GT",
                    new java.math.BigDecimal("0"), new java.math.BigDecimal("24"),
                    "/mfg/work-orders", "NOTIFY,EXCEPTION",
                    "工单所属计划的计划完工时间已过，但工单仍未完成的超出小时数。"),
            new Metric("TASK_STALLED", "任务执行停滞时长", CATEGORY_PRODUCTION, "HOURS", "GT",
                    new java.math.BigDecimal("8"), new java.math.BigDecimal("24"),
                    "/mfg/tasks", "NOTIFY",
                    "任务状态为执行中（RUNNING），但最后更新时间距现在超过限度小时。"),
            new Metric("ANDON_UNRESPONDED", "安灯告警未响应时长", CATEGORY_PRODUCTION, "HOURS", "GT",
                    new java.math.BigDecimal("1"), new java.math.BigDecimal("4"),
                    "/mfg/andon-alerts", "NOTIFY,EXCEPTION",
                    "安灯告警处于告警中（ACTIVE）状态，按触发时间至今的小时数计算。"),
            new Metric("PLAN_DELAYED", "生产计划超期时长", CATEGORY_PRODUCTION, "HOURS", "GT",
                    new java.math.BigDecimal("0"), new java.math.BigDecimal("24"),
                    "/mfg/production-plans", "NOTIFY,EXCEPTION",
                    "计划状态仍为已下达（RELEASED），但计划完工时间已过的超出小时数。"),
            new Metric("EQUIPMENT_FAULT", "设备故障持续时长", CATEGORY_EQUIPMENT, "HOURS", "GT",
                    new java.math.BigDecimal("2"), new java.math.BigDecimal("8"),
                    "/eam/equipment", "NOTIFY,REPAIR,EXCEPTION",
                    "设备当前状态为故障（FAULT）或维修中（REPAIRING）的持续小时数。"),
            new Metric("REPAIR_PENDING", "维修未接单时长", CATEGORY_EQUIPMENT, "HOURS", "GT",
                    new java.math.BigDecimal("2"), new java.math.BigDecimal("8"),
                    "/eam/repair-orders", "NOTIFY,EXCEPTION",
                    "维修单状态为已提交（SUBMITTED），按报修时间至今的小时数计算。"),
            new Metric("REPAIR_WAIT_ACCEPTANCE", "维修待验收超时", CATEGORY_EQUIPMENT, "HOURS", "GT",
                    new java.math.BigDecimal("12"), new java.math.BigDecimal("48"),
                    "/eam/repair-orders", "NOTIFY",
                    "维修单已完工但停在待验收（WAIT_ACCEPTANCE）状态的小时数。"),
            new Metric("MAINTENANCE_OVERDUE", "保养计划超期天数", CATEGORY_EQUIPMENT, "DAYS", "GT",
                    new java.math.BigDecimal("0"), new java.math.BigDecimal("7"),
                    "/eam/maintenance-plans", "NOTIFY,REPAIR",
                    "按天周期的启用保养计划，超出应保养日期（上次保养的下次时间或建单时间加周期）的天数。"),
            new Metric("SPOT_CHECK_OVERDUE", "点检计划超期天数", CATEGORY_EQUIPMENT, "DAYS", "GT",
                    new java.math.BigDecimal("1"), new java.math.BigDecimal("7"),
                    "/eam/spot-check-plans", "NOTIFY,REPAIR",
                    "启用中的点检计划，距最近一次点检记录（没有则按建单时间）的天数。"),
            new Metric("INSPECTION_OVERDUE", "检验单超期未提交", CATEGORY_QUALITY, "HOURS", "GT",
                    new java.math.BigDecimal("8"), new java.math.BigDecimal("24"),
                    "/qms/inspection-orders", "NOTIFY,EXCEPTION",
                    "检验单状态仍为草稿（DRAFT），按建单至今的小时数计算。"),
            new Metric("NONCONFORMING_OPEN", "不合格品未处置时长", CATEGORY_QUALITY, "HOURS", "GT",
                    new java.math.BigDecimal("24"), new java.math.BigDecimal("72"),
                    "/qms/nonconforming-orders", "NOTIFY,EXCEPTION",
                    "不合格品单未关闭，按建单至今的小时数计算。"),
            new Metric("SCHEDULE_CONFLICT", "排程冲突项数", CATEGORY_PLANNING, "COUNT", "GT",
                    new java.math.BigDecimal("0"), new java.math.BigDecimal("5"),
                    "/mfg/schedule-workbench", "NOTIFY,EXCEPTION",
                    "排程场景中状态为冲突（CONFLICT）的排程项数量，按场景汇总。"),
            new Metric("LINE_LOAD_RATIO", "产线未来7天负荷率", CATEGORY_PLANNING, "PERCENT", "GT",
                    new java.math.BigDecimal("100"), new java.math.BigDecimal("130"),
                    "/mfg/schedule-workbench", "NOTIFY",
                    "未来 7 天已下达计划量 ÷（日产能 × 7）× 100，超过 100% 表示按当前日产能排不下。"),
            new Metric("REPLENISHMENT_PENDING", "补货建议未处理时长", CATEGORY_PLANNING, "HOURS", "GT",
                    new java.math.BigDecimal("24"), new java.math.BigDecimal("72"),
                    "/wms/replenishment", "NOTIFY",
                    "补货建议未关闭，按创建至今的小时数计算。")
    );

    private static final Map<String, Metric> BY_CODE = new LinkedHashMap<>();

    static {
        for (Metric metric : METRICS) {
            BY_CODE.put(metric.code(), metric);
        }
    }

    private AlertMetricCatalog() {
    }

    /** 全部指标定义，按声明顺序返回。 */
    public static List<Metric> all() {
        return METRICS;
    }

    /** 按编码取指标定义，未知编码返回 null。 */
    public static Metric get(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /** 指标编码是否已登记。 */
    public static boolean exists(String code) {
        return code != null && BY_CODE.containsKey(code);
    }
}
