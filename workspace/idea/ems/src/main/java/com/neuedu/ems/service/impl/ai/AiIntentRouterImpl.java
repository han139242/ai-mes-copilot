package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.entity.ai.AiColumn;
import com.neuedu.ems.entity.ai.AiQueryResult;
import com.neuedu.ems.service.ai.AiDataQueryService;
import com.neuedu.ems.service.ai.AiIntentRouter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 查询意图路由实现。
 * 规则优先：命中已知意图直接返回真实数据，不调用大模型；未命中才走大模型自由问答。
 */
@Component
public class AiIntentRouterImpl implements AiIntentRouter {

    /**
     * 意图编码。
     */
    public static final String INTENT_GENERAL = "general";
    public static final String INTENT_INVENTORY_FLOW = "inventory_flow";
    public static final String INTENT_INVENTORY_STOCK = "inventory_stock";
    /** 低于安全库存：问「哪些物料低于安全库存」时走这一条，不能被普通库存现有量抢走。 */
    public static final String INTENT_INVENTORY_BELOW_SAFETY = "inventory_below_safety";
    public static final String INTENT_WORK_ORDER = "work_order_detail";
    /** 开工条件核查：问「能不能开工 / 缺什么料 / 卡在哪」时走这一条，而不是只查工单全息。 */
    public static final String INTENT_WORK_ORDER_READINESS = "work_order_readiness";
    /** 开工处置方案：问「要怎么才能开工 / 先做什么 / 有什么方案」时给出有序方案与两路径比较。 */
    public static final String INTENT_WORK_ORDER_START_PLAN = "work_order_start_plan";
    public static final String INTENT_PRODUCTION = "production_daily";
    public static final String INTENT_DEFECT = "defect_analysis";
    public static final String INTENT_TRACE = "batch_trace";
    public static final String INTENT_SCHEDULE = "schedule_capacity";
    public static final String INTENT_ORDER = "order_progress";
    /** 订单排程推演：给出生产订单号又问「什么时候能做完 / 排在哪条线 / 换个排法」时走这一条。 */
    public static final String INTENT_ORDER_SCHEDULE_SIMULATION = "order_schedule_simulation";
    public static final String INTENT_EQUIPMENT = "equipment_impact";
    public static final String INTENT_CAPACITY = "capacity_feasibility";
    /** 良率/合格率：质量口径的比率问题，不能拿报工不良率顶替。 */
    public static final String INTENT_QUALITY_YIELD = "quality_yield";
    /** BOM 用量：问「这个产品一台用多少颗螺钉」「要多少套」时走这一条，不能落到通用资源查询只回条数。 */
    public static final String INTENT_BOM_REQUIREMENT = "bom_requirement";
    public static final String INTENT_WORK_ORDER_LIST = "work_order_list";
    public static final String INTENT_CUSTOMER_ORDER = "customer_order";
    public static final String INTENT_INSPECTION_PENDING = "inspection_pending";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{1,2}-\\d{1,2})\\s*(?:~|～|至|到|-{1,2}|—)\\s*(\\d{4}-\\d{1,2}-\\d{1,2})");
    private static final Pattern SINGLE_DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{1,2}-\\d{1,2})");
    private static final Pattern WORK_ORDER_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_-])((?:[A-Z][A-Z0-9]{0,7}-)?WO-\\d{8}-\\d{3,}|WO[A-Za-z0-9_-]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_-])((?:[A-Z][A-Z0-9]{0,7}-)?PO-\\d{8}-\\d{3,}|PO[A-Za-z0-9_-]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BATCH_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])((?:LOT|FG|RM|BATCH|PC)[A-Za-z0-9_-]*(?:-[A-Za-z0-9]+){2,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(\\d{2,6})\\s*(?:台|pcs|PCS|件)");
    /** 「再接 500」「追加 500」这类没有单位的口语数量。 */
    private static final Pattern EXTRA_QUANTITY_PATTERN = Pattern.compile("(?:再接|追加|加单|加)\\s*(\\d{2,6})");
    /** 「近N天」这类滚动区间。 */
    private static final Pattern RECENT_DAYS_PATTERN = Pattern.compile("近\\s*(\\d{1,2})\\s*天");

    private static final List<String> MATERIAL_STOP_WORDS = List.of(
            "这个月", "上个月", "本月", "下个月", "本周", "这周", "上周", "下周", "今天", "昨天", "前天",
            "近7天", "近七天", "近30天", "近三天", "最近一周", "最近三天",
            "收发存", "库存", "仓库存", "还有多少", "有多少", "剩下多少", "结存", "余额", "台账",
            "可用量", "可用数量", "可用库存", "现有量", "在库量", "库存量", "存量",
            "现在", "目前", "当前", "总共", "一共", "合计", "总量", "总体", "全部", "所有",
            "查一下", "查询", "看看", "看一下", "统计", "明细", "情况", "怎么样", "多少", "什么",
            "入库", "出库", "领料", "退料", "入库单", "出库单", "收料", "发料", "盘点", "调拨",
            "仓库", "原料仓", "原材料仓", "原材料库", "线边仓", "半成品仓", "成品仓", "隔离仓", "不合格仓",
            "里", "中", "内", "的", "了", "吗", "呢", "啊", "请", "帮我", "麻烦");

    private static final Pattern LOW_STOCK_QUERY_WORDS = Pattern.compile(
            "是否低于|低于安全库存|低于库存下限|低于阈值|低于下限|库存不足预警|安全库存|"
                    + "缺料预警|补货建议|补货预警|库存预警|低库存|哪些物料|哪几种物料|什么物料|"
                    + "所有物料|全部物料|各自缺多少|缺口(?:是)?多少|缺口|清单|低于|阈值");

    private static final List<String> WAREHOUSE_HINTS = List.of(
            "原料仓", "原材料仓", "原材料库", "线边仓", "半成品仓", "成品仓", "隔离仓", "不合格仓");

    /** BOM 用量提问里常见的中文物料名，用来从「螺钉要多少颗」里认出被问的是哪个物料。 */
    private static final List<String> BOM_MATERIAL_NAMES = List.of(
            "螺钉", "螺丝", "脚垫", "灯板", "铝板", "扩散板", "外壳", "驱动板", "触控面板",
            "传感器", "电源插座", "配重块", "底盖", "灯臂", "转轴", "线束", "电源适配器");

    private static final Map<String, String> WAREHOUSE_TYPE_BY_HINT = Map.of(
            "原料仓", "RAW",
            "原材料仓", "RAW",
            "原材料库", "RAW",
            "线边仓", "LINE_SIDE",
            "半成品仓", "SEMI_FINISHED",
            "成品仓", "FINISHED",
            "隔离仓", "DEFECTIVE",
            "不合格仓", "DEFECTIVE");

    private final AiDataQueryService dataQueryService;

    public AiIntentRouterImpl(AiDataQueryService dataQueryService) {
        this.dataQueryService = dataQueryService;
    }

    @Override
    public RouteResult route(String question, List<String> history, DateRange defaultRange) {
        if (!StringUtils.hasText(question)) {
            return new RouteResult(INTENT_GENERAL, Map.of(), false);
        }
        String text = question.trim();
        List<String> recentHistory = history == null ? List.of() : history;
        Map<String, Object> params = new LinkedHashMap<>();
        DateRange range = resolveDateRange(text, recentHistory, defaultRange);
        params.put("dateFrom", range.begin());
        params.put("dateTo", range.end());

        RouteResult matched = routeByDocument(text, recentHistory, params);
        if (matched == null) {
            matched = routeByTopic(text, recentHistory, params);
        }
        if (matched == null) {
            matched = routeByInventoryOrOrder(text, recentHistory, params);
        }
        if (matched == null) {
            matched = routeByInheritance(text, recentHistory, range, defaultRange);
        }
        return matched != null ? matched : new RouteResult(INTENT_GENERAL, params, false);
    }

    /** 单据号优先级最高：明确给出工单号或订单号时直接按单据维查询，其次是批次与产能测算。 */
    private RouteResult routeByDocument(String text, List<String> recentHistory, Map<String, Object> params) {
        RouteResult matched = routeByWorkOrder(text, params);
        if (matched == null) {
            matched = routeByOrderNo(text, params);
        }
        if (matched == null) {
            matched = routeByBatchTrace(text, recentHistory, params);
        }
        if (matched == null) {
            matched = routeByCapacity(text, recentHistory, params);
        }
        return matched;
    }

    private RouteResult routeByWorkOrder(String text, Map<String, Object> params) {
        String workOrderNo = workOrderLike(text);
        if (!StringUtils.hasText(workOrderNo)) {
            return null;
        }
        params.put("workOrderNo", workOrderNo);
        // 问「能不能开工 / 缺什么 / 卡在哪」时给的是判断和处置顺序，不是事实清单，
        // 走开工条件核查；其余关于这张工单的问题仍走工单全息。
        return new RouteResult(workOrderIntent(text), params, true);
    }

    /** 工单类问题的细分意图：开工条件核查 / 齐套性与卡点 / 工单全息。 */
    private String workOrderIntent(String text) {
        if (asksWorkOrderStartPlan(text)) {
            return INTENT_WORK_ORDER_START_PLAN;
        }
        if (asksWorkOrderReadiness(text)) {
            return INTENT_WORK_ORDER_READINESS;
        }
        return INTENT_WORK_ORDER;
    }

    private RouteResult routeByOrderNo(String text, Map<String, Object> params) {
        String orderNo = orderLike(text);
        if (!StringUtils.hasText(orderNo)) {
            return null;
        }
        params.put("orderNo", orderNo);
        return new RouteResult(orderIntent(text), params, true);
    }

    /**
     * 订单类问题的细分意图：问排程推演走推演，其余仍走订单进度。
     *
     * <p>推演回答的是「排在哪条线、什么时候能做完、换个排法会不会更快」，
     * 订单进度回答的是「已经做到哪一步」。两者都要求句子里出现生产订单号。</p>
     */
    private String orderIntent(String text) {
        if (asksScheduleSimulation(text)) {
            return INTENT_ORDER_SCHEDULE_SIMULATION;
        }
        return INTENT_ORDER;
    }

    /** 问「怎么排 / 排在哪 / 什么时候做完 / 换个排法」，判为排程推演。 */
    private boolean asksScheduleSimulation(String text) {
        boolean asksResult = containsAny(text, "什么时候能做完", "什么时候做完", "多久能做完", "多久做完",
                "预计什么时候", "能不能按期", "能不能赶上", "来得不及", "来得及吗", "交期来得及");
        boolean asksPlan = containsAny(text, "排在哪", "安排在哪", "哪条线", "换条线", "换个排法", "换个产线",
                "调整资源", "组件优先", "推演", "模拟", "排程方案", "怎么排", "排产方案", "先做组件");
        return asksResult || asksPlan;
    }

    /** 批次追溯。 */
    private RouteResult routeByBatchTrace(String text, List<String> recentHistory, Map<String, Object> params) {
        String batchNo = extractBatchNo(text, recentHistory);
        if (!StringUtils.hasText(batchNo)
                || !containsAny(text, "追溯", "批次", "哪批", "来源", "影响哪些", "去向")) {
            return null;
        }
        params.put("batchNo", batchNo);
        return new RouteResult(INTENT_TRACE, params, true);
    }

    /** 产能测算：产品 + 追加数量。 */
    private RouteResult routeByCapacity(String text, List<String> recentHistory, Map<String, Object> params) {
        Integer extraQuantity = extractExtraQuantity(text);
        if (extraQuantity == null
                || !containsAny(text, "再接", "追加", "加单", "能不能", "按期", "来得及", "插单")) {
            return null;
        }
        params.put("productKeyword", extractProductKeyword(text, recentHistory));
        params.put("extraQuantity", extraQuantity);
        return new RouteResult(INTENT_CAPACITY, params, true);
    }

    /** 设备、排程、不良、产量这四类按话题词判定。 */
    private RouteResult routeByTopic(String text, List<String> recentHistory, Map<String, Object> params) {
        RouteResult matched = routeByBomRequirement(text, recentHistory, params);
        if (matched == null) {
            matched = routeByEquipment(text, recentHistory, params);
        }
        if (matched == null) {
            matched = routeBySchedule(text, recentHistory, params);
        }
        if (matched == null) {
            matched = routeByDefectOrProduction(text, recentHistory, params);
        }
        return matched;
    }

    /**
     * BOM 用量：问「一台用多少」「一共要多少颗/套/个」时逐层展开 BOM 算数量。
     *
     * <p>这类问题以前落到通用数据查询，只回「BOM 命中 1 条」，用户要的数量一个没答。
     * 判据是「BOM/物料清单/用料」这类结构词，或「用多少」+ 具体物料量词，且句子里必须
     * 有产品型号或「台灯」这类产品指代，否则会把「领料还差多少」也抢过来。</p>
     */
    private RouteResult routeByBomRequirement(String text, List<String> recentHistory,
                                              Map<String, Object> params) {
        boolean structureWord = containsAny(text, "BOM", "bom", "物料清单", "用料清单", "单台用量", "用量");
        boolean amountAsk = containsAny(text, "多少颗", "多少套", "多少个", "多少件", "多少片", "多少块",
                "几颗", "几套", "几个", "几件", "一共要", "总共要", "要用多少", "用多少", "需要多少",
                "用量是多少", "单台");
        if (!structureWord && !amountAsk) {
            return null;
        }
        // 「可用量」「库存量」里也含「用量」两个字，但问的是库存不是 BOM；领料缺料同理。
        // 这两类否定条件合成一处，读起来就是「既不像问 BOM，或其实在问库存，都不归这条」。
        boolean aboutStock = containsAny(text, "领料", "缺料", "库存", "还有多少", "收发存", "流水",
                "可用量", "可用数量", "可用库存", "现有量", "在库量", "库存量", "存量");
        String product = aboutStock ? null : extractProductKeyword(text, recentHistory);
        // LAMP-M01 这类是物料编码，不是产品；用它去查 BOM 会查不到产品。
        boolean materialCode = StringUtils.hasText(product)
                && product.toUpperCase(Locale.ROOT).startsWith("LAMP-M");
        if (!StringUtils.hasText(product) || materialCode) {
            return null;
        }
        params.put("productKeyword", product);
        params.put("materialKeyword", extractBomMaterialKeyword(text));
        params.put("quantity", extractProduceQuantity(text));
        return new RouteResult(INTENT_BOM_REQUIREMENT, params, true);
    }

    /**
     * 从「一共要多少颗螺钉」「螺钉要多少」里抽出被问的物料。
     *
     * <p>先认 LAMP-Mxx 这类编码，再退回中文名称片段；抽不到就返回 null，
     * 由服务端把全部物料列出来，不能瞎猜一个物料名去过滤。</p>
     */
    private String extractBomMaterialKeyword(String text) {
        Matcher codeMatcher = Pattern.compile("LAMP-M\\d{2,}", Pattern.CASE_INSENSITIVE).matcher(text);
        if (codeMatcher.find()) {
            return codeMatcher.group().toUpperCase(Locale.ROOT);
        }
        for (String name : BOM_MATERIAL_NAMES) {
            if (text.contains(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 从「做 2 台」「生产 100 台」里抽生产台数；没有就返回 null，由服务端按 1 台算。
     */
    private BigDecimal extractProduceQuantity(String text) {
        Matcher matcher = Pattern.compile("(?:生产|做|组装|需要|要)\\s*(\\d{1,6})\\s*台").matcher(text);
        if (matcher.find()) {
            return new BigDecimal(matcher.group(1));
        }
        Matcher bare = Pattern.compile("(\\d{1,6})\\s*台").matcher(text);
        if (bare.find()) {
            return new BigDecimal(bare.group(1));
        }
        return null;
    }

    /** 设备故障影响。 */
    private RouteResult routeByEquipment(String text, List<String> recentHistory, Map<String, Object> params) {
        boolean equipmentWord = containsAny(text, "设备", "老化", "故障", "停机", "报修", "维修");
        boolean impactWord = containsAny(text, "影响", "哪些", "订单", "延期");
        if (!equipmentWord || !impactWord) {
            return null;
        }
        params.put("equipmentKeyword", extractEquipmentKeyword(text, recentHistory));
        return new RouteResult(INTENT_EQUIPMENT, params, true);
    }

    private RouteResult routeBySchedule(String text, List<String> recentHistory, Map<String, Object> params) {
        // 延期订单是排程追问：延续最近的排程时间和产线，只看延期，不另起话题。
        if (asksDelayedSchedule(text)) {
            params.put("warehouseType", null);
            params.put("lateOnly", Boolean.TRUE);
            putLineKeyword(params, text);
            inheritScheduleLine(params, recentHistory);
            return new RouteResult(INTENT_SCHEDULE, params, true);
        }
        // 排程与产能。
        if (!containsAny(text, "排程", "排产", "产能", "负荷", "产线", "甘特")) {
            return null;
        }
        params.put("warehouseType", null);
        putLineKeyword(params, text);
        return new RouteResult(INTENT_SCHEDULE, params, true);
    }

    private RouteResult routeByDefectOrProduction(String text, List<String> recentHistory,
                                                  Map<String, Object> params) {
        // 良率/合格率是质量口径的比率，必须走检验单统计。
        // 以前落到产量分支，回的是「末工序报工不良率 0.00%」，来料与过程检验判的不合格全不在里面。
        // 「不良率」字面上也含「良率」两字，但它问的是不良占比，得留给下面的不良分析。
        boolean yieldAsk = !containsAny(text, "不良率", "不良比例", "不合格率")
                && containsAny(text, "良率", "合格率", "一次合格", "合格比例", "pass rate");
        // 不良与质量。
        boolean defectAsk = containsAny(text, "不良", "缺陷", "质量", "报废", "返工", "不良率");
        // 产量与报工。
        boolean productionAsk = containsAny(text, "产量", "产出", "报工", "生产了多少", "做了多少", "完成多少");
        // 「L100 的不良情况」问的是某个产品，不抽出来就会把全厂不良一起报出去，
        // 用户看到的是别的型号的数字，还以为系统算错了；良率那条同理。
        if (yieldAsk || defectAsk) {
            params.put("productKeyword", extractProductKeyword(text, recentHistory));
        }
        String intent = null;
        if (yieldAsk) {
            intent = INTENT_QUALITY_YIELD;
        } else if (defectAsk) {
            intent = INTENT_DEFECT;
        } else if (productionAsk) {
            intent = INTENT_PRODUCTION;
        }
        return intent == null ? null : new RouteResult(intent, params, true);
    }

    /** 库存、待检、客户订单、工单清单这几类，判定顺序与原逻辑一致。 */
    private RouteResult routeByInventoryOrOrder(String text, List<String> recentHistory,
                                                Map<String, Object> params) {
        RouteResult matched = routeByInventoryBelowSafety(text, recentHistory, params);
        if (matched == null) {
            matched = routeByInventoryFlow(text, recentHistory, params);
        }
        if (matched == null) {
            matched = routeByInspectionPending(text, params);
        }
        if (matched == null) {
            matched = routeByCustomerOrder(text, recentHistory, params);
        }
        if (matched == null) {
            matched = routeByWorkOrderList(text, recentHistory, params);
        }
        if (matched == null) {
            matched = routeByInventoryStock(text, recentHistory, params);
        }
        return matched;
    }

    /** 收发存：必须同时出现库存语义与时间或仓库语义，避免误判成“库存还有多少”。 */
    /** 低于安全库存：安全库存语义优先于普通库存现有量，避免被“库存”二字抢走。 */
    private RouteResult routeByInventoryBelowSafety(String text, List<String> recentHistory,
                                                    Map<String, Object> params) {
        boolean safetyWord = containsAny(text, "安全库存", "低于安全库存", "低于库存下限", "低于阈值",
                "低于下限", "缺料预警", "补货建议", "补货预警", "库存预警", "低库存", "库存不足预警");
        if (!safetyWord) {
            return null;
        }
        // 明确问某段时间的收发存时仍按收发存口径回答，不抢这条。
        if (containsAny(text, "收发存", "流水", "台账")) {
            return null;
        }
        params.put("warehouseType", extractWarehouseType(text));
        String materialQuestion = LOW_STOCK_QUERY_WORDS.matcher(text).replaceAll(" ").trim();
        boolean overview = containsAny(text, "哪些", "哪几种", "什么物料", "所有物料", "全部物料", "清单");
        String keyword = materialKeywordFrom(materialQuestion);
        if (!StringUtils.hasText(keyword) && !overview) {
            List<String> materialHistory = recentHistory.stream()
                    .map(question -> LOW_STOCK_QUERY_WORDS.matcher(question).replaceAll(" ")).toList();
            keyword = extractMaterialKeyword(materialQuestion, materialHistory);
        }
        params.put("materialKeyword", keyword);
        return new RouteResult(INTENT_INVENTORY_BELOW_SAFETY, params, true);
    }

    /** 收发存：必须同时出现库存语义与时间或仓库语义，避免误判成“库存还有多少”。 */
    private RouteResult routeByInventoryFlow(String text, List<String> recentHistory,
                                             Map<String, Object> params) {
        boolean inventoryWord = containsAny(text, "收发存", "出入库", "入库", "出库", "流水", "台账", "结存");
        if (!inventoryWord) {
            return null;
        }
        if (!hasTimeWord(text) && !containsAny(text, "收发存")) {
            return null;
        }
        params.put("keyword", extractMaterialKeyword(text, recentHistory));
        params.put("warehouseType", extractWarehouseType(text));
        return new RouteResult(INTENT_INVENTORY_FLOW, params, true);
    }

    private boolean hasTimeWord(String text) {
        boolean word = containsAny(text, "本周", "这周", "上周", "本月", "这个月", "上个月", "今天", "昨天",
                "近7天", "近七天", "近30天", "最近一周", "最近三天", "日期");
        return word || DATE_RANGE_PATTERN.matcher(text).find();
    }

    /** 待检检验单：检验语义 + 未完成语义。 */
    private RouteResult routeByInspectionPending(String text, Map<String, Object> params) {
        boolean inspectionWord = containsAny(text, "检验单", "检验");
        boolean pendingWord = containsAny(text, "没做", "未做", "没完成", "未完成",
                "待检", "还没", "未检", "待验", "没验", "需要检", "要做");
        if (!inspectionWord || !pendingWord) {
            return null;
        }
        params.put("inspectionType", extractInspectionType(text));
        return new RouteResult(INTENT_INSPECTION_PENDING, params, true);
    }

    /** 客户订单清单：必须出现客户语义，避免把“这笔订单还差多少”当成客户查询。 */
    private RouteResult routeByCustomerOrder(String text, List<String> recentHistory,
                                             Map<String, Object> params) {
        boolean customerWord = containsAny(text, "客户", "经销商", "经销中心", "经销", "下单", "订货");
        boolean orderWord = containsAny(text, "订单", "订货", "下单", "没完工", "完工", "进度", "交期", "发货");
        if (!customerWord || !orderWord) {
            return null;
        }
        params.put("customerKeyword", extractCustomerKeyword(text, recentHistory));
        return new RouteResult(INTENT_CUSTOMER_ORDER, params, true);
    }

    /** 工单清单：按产品型号或状态查工单列表，排除领料、报工等明细类提问与省略追问。 */
    private RouteResult routeByWorkOrderList(String text, List<String> recentHistory,
                                            Map<String, Object> params) {
        boolean workOrderWord = containsAny(text, "工单");
        boolean detailAsk = containsAny(text, "领料", "用料", "缺料", "报工", "质检",
                "检验", "追溯", "入库", "出库", "发货");
        boolean ellipsis = isEllipsisFollowUp(text);
        if (!workOrderWord || detailAsk || ellipsis) {
            return null;
        }
        String productFilter = extractProductKeyword(text, recentHistory);
        boolean listWord = containsAny(text, "哪些", "所有", "全部", "清单", "列表", "几张", "多少张",
                "列出", "查看", "查询", "有没有", "进度", "到哪", "状态");
        if (!StringUtils.hasText(productFilter) && !listWord) {
            return null;
        }
        params.put("productKeyword", productFilter);
        params.put("status", extractWorkOrderStatus(text));
        return new RouteResult(INTENT_WORK_ORDER_LIST, params, true);
    }

    /** 当前库存明细。 */
    private RouteResult routeByInventoryStock(String text, List<String> recentHistory,
                                              Map<String, Object> params) {
        if (!containsAny(text, "库存", "存货", "还有多少", "剩多少", "结存", "可用量")) {
            return null;
        }
        params.put("materialKeyword", extractMaterialKeyword(text, recentHistory));
        params.put("warehouseCode", extractWarehouseCode(text));
        // 用户一般说「线边仓」「原料仓」这类中文仓库名，不会背仓库编码。
        // 只传编码会让「线边仓还有多少」退化成「全部仓库合计」，数字看起来对但其实口径错了。
        params.put("warehouseType", extractWarehouseType(text));
        return new RouteResult(INTENT_INVENTORY_STOCK, params, true);
    }

    /** 只在明确的省略追问中继承意图；不把天气等新话题错误带入业务查询。 */
    private RouteResult routeByInheritance(String text, List<String> recentHistory, DateRange range,
                                           DateRange defaultRange) {
        if (!isEllipsisFollowUp(text)) {
            return null;
        }
        for (int i = recentHistory.size() - 1; i >= 0 && i >= recentHistory.size() - 3; i--) {
            var previous = route(recentHistory.get(i), recentHistory.subList(0, i), defaultRange);
            if (previous.matched()) {
                return inheritRoute(previous, text, range);
            }
        }
        return null;
    }

    private RouteResult inheritRoute(RouteResult previous, String text, DateRange range) {
        Map<String, Object> inherited = new LinkedHashMap<>(previous.params());
        inherited.put("dateFrom", range.begin());
        inherited.put("dateTo", range.end());
        putLineKeyword(inherited, text);
        if (INTENT_SCHEDULE.equals(previous.intent()) && containsAny(text, "延期")) {
            inherited.put("lateOnly", Boolean.TRUE);
        }
        // 「那第一个呢」不是重复上一问，而是要看上一份结果里的某一条。
        // 只继承意图会让用户拿到一模一样的清单，追问等于白问。
        Integer pick = extractResultOrdinal(text);
        if (pick != null) {
            inherited.put("pickIndex", pick);
        }
        return new RouteResult(previous.intent(), inherited, true);
    }

    /** 「那…」「再看第二条」「最后一条」这类都是承接上一轮的省略追问。 */
    private boolean isEllipsisFollowUp(String text) {
        return ELLIPSIS_PREFIX.matcher(text).find();
    }

    private static final java.util.regex.Pattern ELLIPSIS_PREFIX = java.util.regex.Pattern.compile(
            "^(那|这笔|这个工单|这笔订单|这些|这批|按|只看|再看|看看|最后|第)");

    /** 抽取「第一个」「第二条」「最后一条」这类序号，未提及时返回 null。 */
    private Integer extractResultOrdinal(String text) {
        java.util.regex.Matcher matcher = RESULT_ORDINAL.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String number = matcher.group(1);
        if (number != null) {
            return CHINESE_ORDINALS.get(number);
        }
        // 命中「最后一条」，取结果集末尾一行。
        return LAST_RESULT;
    }

    /** 「最后一条」用负数表示，取行时按结果集末尾定位。 */
    private static final int LAST_RESULT = -1;

    private static final java.util.regex.Pattern RESULT_ORDINAL = java.util.regex.Pattern.compile(
            "(?:第\\s*([一二三四五六七八九十\\d]+)\\s*[个条张笔]?|(最后)\\s*(?:一)?[个条张笔])");

    private static final Map<String, Integer> CHINESE_ORDINALS = Map.ofEntries(
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("三", 3), Map.entry("四", 4),
            Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8),
            Map.entry("九", 9), Map.entry("十", 10),
            Map.entry("1", 1), Map.entry("2", 2), Map.entry("3", 3), Map.entry("4", 4),
            Map.entry("5", 5), Map.entry("6", 6), Map.entry("7", 7), Map.entry("8", 8),
            Map.entry("9", 9), Map.entry("10", 10));

    @Override
    public AiQueryResult execute(RouteResult route) {
        if (route == null || !route.matched()) {
            return null;
        }
        AiQueryResult result = executeIntent(route);
        Integer pick = route.integer("pickIndex", 0);
        if (result == null || pick == 0) {
            return result;
        }
        return narrowToPickedRow(result, pick);
    }

    /**
     * 追问「第一个」时把上一次的清单缩到那一行。
     * 只缩主表不动附加区块，用户还能看到汇总和图表，不至于只剩孤零零一行数字。
     */
    private AiQueryResult narrowToPickedRow(AiQueryResult result, int pick) {
        List<Map<String, Object>> rows = result.getRows();
        if (rows == null || rows.isEmpty()) {
            return result;
        }
        int index = pick == LAST_RESULT ? rows.size() - 1 : pick - 1;
        if (index < 0 || index >= rows.size()) {
            return new AiQueryResult(result.getTitle() + "（追问未命中）",
                    "上一次结果只有 " + rows.size() + " 条，没有第 " + Math.abs(pick) + " 条。可以改问具体编号。",
                    result.getColumns(), List.of(), result.getBlocks());
        }
        Map<String, Object> picked = rows.get(index);
        AiQueryResult narrowed = new AiQueryResult(result.getTitle(),
                describePickedRow(picked, index + 1, result.getColumns()),
                result.getColumns(), List.of(picked), result.getBlocks());
        return narrowed;
    }

    /** 把选中的那一行拼成一句人话，标题列用第一列的值兜底。 */
    private String describePickedRow(Map<String, Object> row, int ordinal, List<AiColumn> columns) {
        // 用列定义里的中文名，不要直接把 workOrderNo 这类英文字段名念给用户听。
        Map<String, String> labels = new LinkedHashMap<>();
        if (columns != null) {
            for (AiColumn column : columns) {
                labels.put(column.getProp(), column.getLabel());
            }
        }
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object value = entry.getValue();
            if (value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            if (detail.length() > 0) {
                detail.append('，');
            }
            detail.append(labels.getOrDefault(entry.getKey(), entry.getKey()))
                    .append(' ').append(value);
        }
        return "上一次结果的第 " + ordinal + " 条：" + detail + "。";
    }

    private AiQueryResult executeIntent(RouteResult route) {
        LocalDate from = route.date("dateFrom");
        LocalDate to = route.date("dateTo");
        return switch (route.intent()) {
            case INTENT_INVENTORY_FLOW -> dataQueryService.queryInventoryByMaterial(
                    route.string("keyword"), route.string("warehouseType"), from, to);
            case INTENT_INVENTORY_STOCK -> dataQueryService.queryInventoryByWarehouse(
                    route.string("warehouseCode"), route.string("warehouseType"), route.string("materialKeyword"));
            case INTENT_INVENTORY_BELOW_SAFETY -> dataQueryService.queryInventoryBelowSafety(
                    route.string("warehouseType"), route.string("materialKeyword"));
            case INTENT_WORK_ORDER -> dataQueryService.queryWorkOrderDetail(route.string("workOrderNo"));
            case INTENT_WORK_ORDER_READINESS -> dataQueryService.queryWorkOrderReadiness(route.string("workOrderNo"));
            case INTENT_WORK_ORDER_START_PLAN -> dataQueryService.queryWorkOrderStartPlan(route.string("workOrderNo"));
            case INTENT_PRODUCTION -> dataQueryService.queryProductionByDate(from, to);
            case INTENT_DEFECT -> dataQueryService.queryDefectByPeriod(from, to,
                    route.string("productKeyword"));
            case INTENT_TRACE -> dataQueryService.queryTraceByBatch(route.string("batchNo"));
            case INTENT_SCHEDULE -> dataQueryService.queryScheduleCapacity(from, to, route.string("lineKeyword"),
                    Boolean.TRUE.equals(route.params().get("lateOnly")));
            case INTENT_ORDER -> dataQueryService.queryOrderProgress(route.string("orderNo"));
            case INTENT_ORDER_SCHEDULE_SIMULATION -> dataQueryService.queryOrderScheduleSimulation(
                    route.string("orderNo"));
            case INTENT_EQUIPMENT -> dataQueryService.queryEquipmentImpact(
                    route.string("equipmentKeyword"), from, to);
            case INTENT_CAPACITY -> dataQueryService.queryCapacityFeasibility(
                    route.string("productKeyword"), route.integer("extraQuantity", 0), from, to);
            case INTENT_BOM_REQUIREMENT -> dataQueryService.queryBomRequirement(
                    route.string("productKeyword"), route.string("materialKeyword"),
                    route.decimal("quantity"), from);
            case INTENT_QUALITY_YIELD -> dataQueryService.queryQualityYield(
                    from, to, route.string("productKeyword"));
            case INTENT_WORK_ORDER_LIST -> dataQueryService.queryWorkOrdersByProduct(
                    route.string("productKeyword"), route.string("status"));
            case INTENT_CUSTOMER_ORDER -> dataQueryService.queryOrdersByCustomer(
                    route.string("customerKeyword"));
            case INTENT_INSPECTION_PENDING -> dataQueryService.queryPendingInspections(
                    route.string("inspectionType"));
            default -> null;
        };
    }

    @Override
    public List<String> suggestions(String intent) {
        return switch (intent == null ? INTENT_GENERAL : intent) {
            case INTENT_INVENTORY_FLOW -> List.of(
                    "这个月控制板的收发存按仓库展开",
                    "查一下主控驱动板的当前库存明细",
                    "本周产量和不良率是多少");
            case INTENT_INVENTORY_STOCK -> List.of(
                    "这个月控制板收发存",
                    "L200 控制底座在成品仓还有多少",
                    "这批传感器的批次追溯");
            case INTENT_INVENTORY_BELOW_SAFETY -> List.of(
                    "线边仓哪些物料低于安全库存",
                    "原料仓还有多少物料低于阈值",
                    "低库存物料各自缺多少");
            case INTENT_WORK_ORDER -> List.of(
                    "这笔工单的领料明细和缺料情况",
                    "查一下这个工单的报工和质检记录",
                    "这个工单属于哪个生产订单，交期是什么时候");
            case INTENT_WORK_ORDER_READINESS -> List.of(
                    "这张工单要先做什么才能开工",
                    "缺的组件对应哪几张子工单",
                    "生成这张工单的领料单");
            case INTENT_WORK_ORDER_START_PLAN -> List.of(
                    "把这几张组件工单下达",
                    "这张工单的物料还差多少",
                    "生成这张工单的领料单");
            case INTENT_PRODUCTION -> List.of(
                    "本周不良主要出在哪里",
                    "按工单看本周产量排名",
                    "这周的排程和产能怎么样");
            case INTENT_DEFECT -> List.of(
                    "这些不良涉及哪些工单和批次",
                    "按工序看不良分布",
                    "本周合格率和产量趋势");
            case INTENT_TRACE -> List.of(
                    "这批物料还用在哪些工单上",
                    "这批成品的销售去向",
                    "这批物料的检验记录");
            case INTENT_SCHEDULE -> List.of(
                    "L200 再接 500 台能不能按期",
                    "按产线看剩余产能",
                    "本周有哪些延期和冲突任务");
            case INTENT_ORDER -> List.of(
                    "这笔订单的工单领料情况",
                    "这笔订单的成品入库和发货记录",
                    "这笔订单还有多少没完工");
            case INTENT_ORDER_SCHEDULE_SIMULATION -> List.of(
                    "这笔订单排在哪条线什么时候能做完",
                    "换个产线会不会更快完工",
                    "先做组件再总装要顺延多久");
            case INTENT_EQUIPMENT -> List.of(
                    "这些受影响的订单交期还有多久",
                    "故障设备对应工位还有哪些未完成任务",
                    "本周排程产能占用情况");
            case INTENT_CAPACITY -> List.of(
                    "这个产品 BOM 还缺哪些物料",
                    "按产线看本周剩余产能",
                    "这个产品的可用库存和批次");
            case INTENT_BOM_REQUIREMENT -> List.of(
                    "做 10 台这个产品一共要多少颗螺钉",
                    "这个产品 BOM 里哪些是自制半成品",
                    "这个产品的物料现在还缺多少");
            case INTENT_QUALITY_YIELD -> List.of(
                    "这个月不合格品都有哪些",
                    "按工序看不良集中在哪",
                    "这个月产量是多少");
            case INTENT_WORK_ORDER_LIST -> List.of(
                    "这些工单的领料齐套情况怎么样",
                    "L200 还有哪些工单没完工",
                    "已完成工单的成品入库了吗");
            case INTENT_CUSTOMER_ORDER -> List.of(
                    "这些订单的成品入库和发货记录",
                    "这笔订单还有多少没完工",
                    "这个客户的订单交期还来得及吗");
            case INTENT_INSPECTION_PENDING -> List.of(
                    "来料检验还有哪些没做完",
                    "终检没完成的检验单有哪些",
                    "最近7天的不良品都在哪几道工序");
            default -> List.of(
                    "这个月控制板收发存",
                    "本周不良主要出在哪里",
                    "这周的排程和产能怎么样");
        };
    }

    /** 延期订单或延期任务，仍属于排程查询，而不是新的单据查询。 */
    private boolean asksDelayedSchedule(String text) {
        return containsAny(text, "延期") && containsAny(text, "订单", "任务", "哪些", "排程");
    }

    /**
     * 是不是在问「这张工单现在能不能开工、缺什么、卡在哪」。
     *
     * <p>这类问题要的是判断与处置顺序，工单全息那段事实摘要答不了。判据取「开工/开始生产」
     * 与「能不能/可以吗/缺什么/卡在哪/为什么不能」的组合，避免把「查一下这个工单的领料明细」
     * 也吸过来。</p>
     */
    private boolean asksWorkOrderReadiness(String text) {
        boolean asksStart = containsAny(text, "开工", "开始生产", "开始");
        boolean asksJudgement = containsAny(text, "能不能", "能否", "可不可以", "可以吗", "能开", "缺什么",
                "缺哪些", "卡在哪", "为什么不能", "为什么开不了", "还差什么", "齐套");
        return asksStart && asksJudgement;
    }

    /**
     * 问「要怎么才能开工 / 先做什么 / 给个方案」时给处置方案，而不是只给核查表。
     *
     * <p>与 {@link #asksWorkOrderReadiness} 的区别是：核查回答「能不能、卡在哪」，
     * 方案回答「按什么顺序做、要推进哪些单据、两条路径差多少」。两类都要求句子里出现工单号。</p>
     */
    private boolean asksWorkOrderStartPlan(String text) {
        boolean asksPlan = containsAny(text, "怎么才能", "怎样才能", "先做什么", "先做哪一步", "怎么办",
                "有什么方案", "给个方案", "处置方案", "推演", "怎么处理", "如何开工", "怎样开工",
                "要先", "该先", "步骤");
        boolean aboutWorkOrder = containsAny(text, "开工", "开始生产", "开始", "工单");
        return asksPlan && aboutWorkOrder;
    }

    /** 本轮没点产线时，从最近三轮排程对话里继承“几号线”。没有排程上下文则不继承。 */
    private void inheritScheduleLine(Map<String, Object> params, List<String> history) {
        if (params.containsKey("lineKeyword") || history == null || history.isEmpty()) {
            return;
        }
        ScheduleLineInheritance inheritance = scanScheduleLine(history);
        if (inheritance.scheduleContext() && StringUtils.hasText(inheritance.lineKeyword())) {
            params.put("lineKeyword", inheritance.lineKeyword());
        }
    }

    /** 扫最近三轮历史，记下是否出现排程上下文以及最后提到的产线。 */
    private ScheduleLineInheritance scanScheduleLine(List<String> history) {
        boolean scheduleContext = false;
        String inherited = null;
        int from = Math.max(0, history.size() - 3);
        for (int i = from; i < history.size(); i++) {
            String message = history.get(i);
            if (containsAny(message, "排程", "排产", "产能", "负荷", "甘特")) {
                scheduleContext = true;
            }
            String lineKeyword = extractLineKeyword(message);
            if (!StringUtils.hasText(lineKeyword)) {
                continue;
            }
            inherited = lineKeyword;
            if (message.startsWith("只看") || containsAny(message, "产线")) {
                scheduleContext = true;
            }
        }
        return new ScheduleLineInheritance(scheduleContext, inherited);
    }

    /** 产线继承扫描结果：是否处在排程话题里，以及被继承的产线关键词。 */
    private record ScheduleLineInheritance(boolean scheduleContext, String lineKeyword) {
    }

    /** 把“二号线”“2号线”这类产线收窄条件放进查询参数；没有则不覆盖已继承的产线。 */
    private void putLineKeyword(Map<String, Object> params, String text) {
        String lineKeyword = extractLineKeyword(text);
        if (StringUtils.hasText(lineKeyword)) {
            params.put("lineKeyword", lineKeyword);
        }
    }

    private String extractLineKeyword(String text) {
        String keyword = null;
        if (StringUtils.hasText(text)) {
            var matcher = java.util.regex.Pattern.compile("([0-9]{1,2}|[一二三四五六七八九十]+)号线").matcher(text);
            var named = java.util.regex.Pattern.compile("(?:只看|仅看|查看|查)([\\p{IsHan}A-Za-z0-9_-]{2,30}线)").matcher(text);
            if (matcher.find()) {
                keyword = matcher.group();
            } else if (named.find()) {
                keyword = named.group(1);
            }
        }
        return keyword;
    }

    /**
     * 解析日期范围。优先本轮显式表达，其次继承最近 3 轮用户消息，最后用前端上下文默认值。
     */
    private DateRange resolveDateRange(String text, List<String> history, DateRange defaultRange) {
        DateRange explicit = parseExplicitDateRange(text);
        if (explicit != null) {
            return explicit;
        }
        DateRange inherited = inheritDateRange(history);
        if (inherited != null) {
            return inherited;
        }
        return defaultOrCurrentWeek(defaultRange);
    }

    /** 沿用最近 3 轮里出现过的显式日期范围。 */
    private DateRange inheritDateRange(List<String> history) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0 && i >= history.size() - 3; i--) {
            DateRange inherited = parseExplicitDateRange(history.get(i));
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    /** 前端上下文没给日期时默认本周一到本周日。 */
    private DateRange defaultOrCurrentWeek(DateRange defaultRange) {
        if (defaultRange != null && defaultRange.begin() != null && defaultRange.end() != null) {
            return defaultRange;
        }
        LocalDate today = LocalDate.now();
        return new DateRange(today.with(DayOfWeek.MONDAY), today.with(DayOfWeek.SUNDAY));
    }

    /**
     * 解析文本中显式的相对或绝对日期范围。
     */
    private DateRange parseExplicitDateRange(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        LocalDate today = LocalDate.now();
        DateRange range = explicitRangeByDatePattern(text);
        if (range == null) {
            range = explicitRangeByRelativeWord(text, today);
        }
        if (range == null) {
            range = explicitRangeByRecentDays(text, today);
        }
        if (range == null) {
            range = explicitRangeBySingleDate(text);
        }
        return range;
    }

    /** 形如「2026-01-01 至 2026-01-31」的绝对区间；起止写反时自动对调。 */
    private DateRange explicitRangeByDatePattern(String text) {
        Matcher rangeMatcher = DATE_RANGE_PATTERN.matcher(text);
        if (!rangeMatcher.find()) {
            return null;
        }
        LocalDate begin = safeParse(rangeMatcher.group(1));
        LocalDate end = safeParse(rangeMatcher.group(2));
        if (begin == null || end == null) {
            return null;
        }
        return begin.isAfter(end) ? new DateRange(end, begin) : new DateRange(begin, end);
    }

    /** 「本周 / 上周 / 本月 / 今天」这类相对日期词，按声明顺序取第一条命中。 */
    private DateRange explicitRangeByRelativeWord(String text, LocalDate today) {
        for (RelativeDateRule rule : RELATIVE_DATE_RULES) {
            if (containsAny(text, rule.keywords().toArray(String[]::new))) {
                return rule.range().apply(today);
            }
        }
        return null;
    }

    /** 相对日期词到区间的换算规则，顺序即匹配优先级。 */
    private record RelativeDateRule(List<String> keywords,
                                   java.util.function.Function<LocalDate, DateRange> range) {
    }

    private static final List<RelativeDateRule> RELATIVE_DATE_RULES = List.of(
            new RelativeDateRule(List.of("本周", "这周", "本星期", "这个星期"),
                    today -> new DateRange(today.with(DayOfWeek.MONDAY), today.with(DayOfWeek.SUNDAY))),
            new RelativeDateRule(List.of("上周", "上星期"),
                    today -> new DateRange(today.with(DayOfWeek.MONDAY).minusWeeks(1),
                            today.with(DayOfWeek.MONDAY).minusWeeks(1).plusDays(6))),
            new RelativeDateRule(List.of("下周"),
                    today -> new DateRange(today.with(DayOfWeek.MONDAY).plusWeeks(1),
                            today.with(DayOfWeek.MONDAY).plusWeeks(1).plusDays(6))),
            new RelativeDateRule(List.of("本月", "这个月", "当月"),
                    today -> new DateRange(today.with(TemporalAdjusters.firstDayOfMonth()),
                            today.with(TemporalAdjusters.lastDayOfMonth()))),
            new RelativeDateRule(List.of("上个月", "上月"),
                    today -> {
                        LocalDate firstDay = today.with(TemporalAdjusters.firstDayOfMonth()).minusMonths(1);
                        return new DateRange(firstDay, firstDay.with(TemporalAdjusters.lastDayOfMonth()));
                    }),
            new RelativeDateRule(List.of("今天", "今日"), today -> new DateRange(today, today)),
            new RelativeDateRule(List.of("昨天", "昨日"),
                    today -> new DateRange(today.minusDays(1), today.minusDays(1))),
            new RelativeDateRule(List.of("前天"),
                    today -> new DateRange(today.minusDays(2), today.minusDays(2))));

    /** 「近N天 / 近7天 / 近30天」这类滚动区间。 */
    private DateRange explicitRangeByRecentDays(String text, LocalDate today) {
        Integer days = explicitRecentDays(text);
        if (days == null) {
            return null;
        }
        return new DateRange(today.minusDays(days - 1L), today);
    }

    /** 滚动天数：优先取「近N天」里的 N，其次按固定档位。 */
    private Integer explicitRecentDays(String text) {
        Matcher recent = RECENT_DAYS_PATTERN.matcher(text);
        if (recent.find()) {
            return Math.max(1, Integer.parseInt(recent.group(1)));
        }
        for (RecentDaysRule rule : RECENT_DAYS_RULES) {
            if (containsAny(text, rule.keywords().toArray(String[]::new))) {
                return rule.days();
            }
        }
        return null;
    }

    /** 固定滚动档位关键词。 */
    private record RecentDaysRule(List<String> keywords, int days) {
    }

    private static final List<RecentDaysRule> RECENT_DAYS_RULES = List.of(
            new RecentDaysRule(List.of("近7天", "近七天", "最近一周", "最近7天"), 7),
            new RecentDaysRule(List.of("近30天", "最近30天", "近一个月"), 30));

    /** 只给了一个日期时按当天区间处理。 */
    private DateRange explicitRangeBySingleDate(String text) {
        Matcher single = SINGLE_DATE_PATTERN.matcher(text);
        if (!single.find()) {
            return null;
        }
        LocalDate day = safeParse(single.group(1));
        return day == null ? null : new DateRange(day, day);
    }

    private LocalDate safeParse(String value) {
        try {
            String[] parts = value.split("-");
            return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 抽取物料关键词，优先本轮，其次从最近 3 轮继承。
     */
    private String extractMaterialKeyword(String text, List<String> history) {
        // 「那……呢」这类省略追问，先从历史里找；找不到再回落到本轮文本。
        if (text.matches("^(那|这个|这批).*(呢|吗|出库|入库).*") && !history.isEmpty()) {
            String previous = newestMaterialKeyword(history);
            if (StringUtils.hasText(previous)) {
                return previous;
            }
        }
        String keyword = materialKeywordFrom(text);
        if (StringUtils.hasText(keyword)) {
            return keyword;
        }
        return newestMaterialKeyword(history);
    }

    /** 从最近 3 轮里取最近一次抽到的物料关键词。 */
    private String newestMaterialKeyword(List<String> history) {
        for (int i = history.size() - 1; i >= 0 && i >= history.size() - 3; i--) {
            String inherited = materialKeywordFrom(history.get(i));
            if (StringUtils.hasText(inherited)) {
                return inherited;
            }
        }
        return null;
    }

    private String materialKeywordFrom(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String working = text;
        for (String hint : WAREHOUSE_HINTS) {
            working = working.replace(hint, " ");
        }
        working = DATE_RANGE_PATTERN.matcher(working).replaceAll(" ");
        working = working.replaceAll("\\d{4}-\\d{1,2}-\\d{1,2}", " ");
        working = working.replaceAll("[\\d]+\\s*(?:台|件|pcs|PCS)", " ");
        working = WORK_ORDER_PATTERN.matcher(working).replaceAll(" ");
        working = ORDER_PATTERN.matcher(working).replaceAll(" ");
        for (String stop : MATERIAL_STOP_WORDS) {
            working = working.replace(stop, " ");
        }
        // 剩余连续中文、字母或数字片段里取最长的候选作为物料关键词。
        Matcher matcher = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9][\\u4e00-\\u9fa5A-Za-z0-9\\-]*").matcher(working);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            if (candidate.length() < 2) {
                continue;
            }
            if (best == null || candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 抽取仓库类型编码。
     */
    private String extractWarehouseType(String text) {
        for (String hint : WAREHOUSE_HINTS) {
            if (text.contains(hint)) {
                return WAREHOUSE_TYPE_BY_HINT.get(hint);
            }
        }
        return null;
    }

    /**
     * 抽取仓库编码，支持用户直接输入 LAMP-WH-RAW 这类编码。
     */
    private String extractWarehouseCode(String text) {
        Matcher matcher = Pattern.compile("([A-Z]{2,}-WH-[A-Z]{2,})", Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /**
     * 抽取检验类型：来料、过程、终检、出货，未提及时返回 null 表示全部类型。
     */
    private String extractInspectionType(String text) {
        for (InspectionTypeRule rule : INSPECTION_TYPE_RULES) {
            if (containsAny(text, rule.keywords().toArray(String[]::new))) {
                return rule.code();
            }
        }
        return null;
    }

    /** 检验类型关键词到编码的映射，按来料、过程、终检、出货的优先顺序排列。 */
    private record InspectionTypeRule(String code, List<String> keywords) {
    }

    private static final List<InspectionTypeRule> INSPECTION_TYPE_RULES = List.of(
            new InspectionTypeRule("INCOMING", List.of("来料", "进料", "IQC", "iqc")),
            new InspectionTypeRule("PROCESS", List.of("过程", "工序", "IPQC", "ipqc")),
            new InspectionTypeRule("FINAL", List.of("终检", "末检", "成品检验", "FQC", "fqc")),
            new InspectionTypeRule("OUTGOING", List.of("出货", "发货检验", "OQC", "oqc")));

    /**
     * 抽取工单状态过滤词，未提及时返回 null 表示全部状态；
     * UNFINISHED 表示“没完工”这类否定表述，由查询侧排除已完成和已关闭。
     */
    private String extractWorkOrderStatus(String text) {
        for (WorkOrderStatusRule rule : WORK_ORDER_STATUS_RULES) {
            if (containsAny(text, rule.keywords().toArray(String[]::new))) {
                return rule.code();
            }
        }
        return null;
    }

    /** 工单状态关键词到状态编码的映射，按否定表述优先的顺序排列。 */
    private record WorkOrderStatusRule(String code, List<String> keywords) {
    }

    private static final List<WorkOrderStatusRule> WORK_ORDER_STATUS_RULES = List.of(
            // 「没开工」和「没完工」是两件事：前者一个工序都还没动，后者已经在做。
            // 放在最前面，避免被后面的否定表述抢走。
            new WorkOrderStatusRule("NOT_STARTED",
                    List.of("没开工", "未开工", "没有开工", "还没开工", "没开始", "未开始", "还没开始")),
            new WorkOrderStatusRule("UNFINISHED",
                    List.of("没完工", "未完工", "没做完", "未完成", "还没完成", "进行中", "在产")),
            new WorkOrderStatusRule("DRAFT", List.of("未下达", "草稿", "新建")),
            new WorkOrderStatusRule("RELEASED", List.of("已下达", "已放行")),
            new WorkOrderStatusRule("FINISHED", List.of("已完成", "已完工", "做完了")));

    /**
     * 抽取客户关键词：去掉订单、客户等通用词后保留最长的中文片段；本轮抽不到时回溯最近 3 轮，
     * 支持"这个客户还有多少没完工"这类省略追问。
     */
    private String extractCustomerKeyword(String text, List<String> history) {
        String keyword = customerKeywordFrom(text);
        if (StringUtils.hasText(keyword)) {
            return keyword;
        }
        for (int i = history.size() - 1; i >= 0 && i >= history.size() - 3; i--) {
            String inherited = customerKeywordFrom(history.get(i));
            if (StringUtils.hasText(inherited)) {
                return inherited;
            }
        }
        return null;
    }

    private String customerKeywordFrom(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String working = text;
        for (String stop : List.of("客户", "下单", "订货", "订单",
                "有哪些", "哪些", "多少", "几张", "几个", "还有", "没完工", "进度", "状态",
                "的", "了", "吗", "呢", "请", "帮我", "麻烦", "查一下", "看看", "这个", "那个")) {
            working = working.replace(stop, " ");
        }
        Matcher matcher = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9][\\u4e00-\\u9fa5A-Za-z0-9·\\-]*").matcher(working);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            if (candidate.length() < 2) {
                continue;
            }
            if (best == null || candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 抽取工单号，兼容用户只输入数字的情况。
     */
    private String workOrderLike(String text) {
        Matcher matcher = WORK_ORDER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group(1);
        // 只输入 WO 两个字母时不是有效单据号。
        return candidate.length() <= 2 ? null : candidate;
    }

    private String orderLike(String text) {
        Matcher matcher = ORDER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group(1);
        return candidate.length() <= 2 ? null : candidate;
    }

    /**
     * 抽取批次号，优先本轮，其次继承最近 3 轮。
     */
    private String extractBatchNo(String text, List<String> history) {
        String found = batchNoFrom(text);
        if (StringUtils.hasText(found)) {
            return found;
        }
        for (int i = history.size() - 1; i >= 0 && i >= history.size() - 3; i--) {
            String inherited = batchNoFrom(history.get(i));
            if (StringUtils.hasText(inherited)) {
                return inherited;
            }
        }
        return null;
    }

    private String batchNoFrom(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = BATCH_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 抽取追加数量，例如“再接 500 台”。
     */
    private Integer extractExtraQuantity(String text) {
        Integer quantity = parseQuantity(QUANTITY_PATTERN.matcher(text));
        if (quantity != null) {
            return quantity;
        }
        return parseQuantity(EXTRA_QUANTITY_PATTERN.matcher(text));
    }

    /** 从匹配器里取第一组数字，取不到或不是合法整数时返回 null。 */
    private Integer parseQuantity(Matcher matcher) {
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 抽取产品关键词，支持从上一轮继承。
     */
    private String extractProductKeyword(String text, List<String> history) {
        String found = productKeywordFrom(text);
        if (StringUtils.hasText(found)) {
            return found;
        }
        for (int i = history.size() - 1; i >= 0 && i >= history.size() - 3; i--) {
            String inherited = productKeywordFrom(history.get(i));
            if (StringUtils.hasText(inherited)) {
                return inherited;
            }
        }
        return null;
    }

    private String productKeywordFrom(String text) {
        // 先认完整产品编码。只说 L200 会连 LAMP-BASE-L200 这类组件一起命中，
        // 用户问「LAMP-L200 的工单」却看到底座工单，会以为筛选没生效。
        Matcher full = Pattern.compile("LAMP-(?:[A-Z]+-)?L\\d{3}|LAMP-[A-Z]+", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        Matcher shortCode = Pattern.compile("(L\\d{3}|M\\d{3}|T\\d{3})", Pattern.CASE_INSENSITIVE).matcher(text);
        String keyword;
        if (full.find()) {
            keyword = full.group().toUpperCase(Locale.ROOT);
        } else if (shortCode.find()) {
            keyword = shortCode.group(1).toUpperCase(Locale.ROOT);
        } else if (text.contains("台灯")) {
            // 说「台灯」而未指明型号时，默认按首批整机 L200 理解。
            keyword = "L200";
        } else {
            keyword = null;
        }
        return keyword;
    }

    /**
     * 抽取设备关键词，支持从上一轮继承。
     */
    private String extractEquipmentKeyword(String text, List<String> history) {
        String found = equipmentKeywordFrom(text);
        if (StringUtils.hasText(found)) {
            return found;
        }
        for (int i = history.size() - 1; i >= 0 && i >= history.size() - 3; i--) {
            String inherited = equipmentKeywordFrom(history.get(i));
            if (StringUtils.hasText(inherited)) {
                return inherited;
            }
        }
        return null;
    }

    private String equipmentKeywordFrom(String text) {
        Matcher codeMatcher = Pattern.compile("(EQ[A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (codeMatcher.find()) {
            return codeMatcher.group(1);
        }
        for (String keyword : List.of("老化", "测试", "装配", "封装", "焊接", "点胶", "注塑")) {
            if (text.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为前端补充最近一轮继承到的参数说明，便于界面显示查询口径。
     */
    public List<String> describeScope(RouteResult route) {
        List<String> scope = new ArrayList<>();
        if (route == null || route.params() == null) {
            return scope;
        }
        Object from = route.params().get("dateFrom");
        Object to = route.params().get("dateTo");
        if (from != null && to != null) {
            scope.add("时间范围：" + from + " 至 " + to);
        }
        return scope;
    }
}
