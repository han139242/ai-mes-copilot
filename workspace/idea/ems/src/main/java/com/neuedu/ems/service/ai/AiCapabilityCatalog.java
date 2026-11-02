package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.AuthorizationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务能力目录：把系统里「能查什么、能办什么」用一份结构化清单描述出来，
 * 交给大模型做意图理解与计划生成。
 *
 * <p>设计约束：
 * 1. 这里只描述能力与参数，不包含任何实现。模型只能从目录里选择能力并给参数；
 * 2. 参数缺失时返回 clarify，由后端向用户补问，禁止模型自行编造单号、数量、批次与库位；
 * 3. 能力的真实执行、权限、数据范围、事务与幂等全部落在既有领域服务里。</p>
 */
public final class AiCapabilityCatalog {

    private AiCapabilityCatalog() {
    }

    /**
     * 能力类型。
     */
    public static final String KIND_QUERY = "query";
    public static final String KIND_COMMAND = "command";
    public static final String KIND_AUTOMATION = "automation";

    /**
     * 单个能力定义。
     *
     * @param code        能力编码，查询类等于意图编码，办理类等于动作编码
     * @param kind        类型：query 只读查询，command 业务办理
     * @param label       中文名称
     * @param description 能力说明，帮助模型判断何时使用
     * @param targetType  办理类的目标单据类型，查询类为空
     * @param params      参数定义
     */
    public record Capability(String code, String kind, String label, String description,
                             String targetType, List<Param> params) {
    }

    /**
     * 参数定义。
     *
     * @param name        参数名
     * @param type        类型：string/date/int/enum
     * @param required    是否必填
     * @param description 说明
     * @param allowed     枚举可选值，非枚举为空
     */
    public record Param(String name, String type, boolean required, String description, List<String> allowed) {
    }

    private static Param optional(String name, String type, String description) {
        return new Param(name, type, false, description, List.of());
    }

    private static Param required(String name, String type, String description) {
        return new Param(name, type, true, description, List.of());
    }

    private static Param enumParam(String name, boolean required, String description, List<String> allowed) {
        return new Param(name, "enum", required, description, allowed);
    }

    private static final List<String> WAREHOUSE_TYPES = List.of(
            "RAW", "LINE_SIDE", "SEMI_FINISHED", "FINISHED", "DEFECTIVE");

    /**
     * 全部只读查询能力。
     */
    public static List<Capability> queries() {
        return List.of(
                new Capability("inventory_flow", KIND_QUERY, "物料收发存",
                        "按物料名称、编码或品类查某段时间的期初、入库、出库、期末结存，可限定仓库类型",
                        null, List.of(
                        optional("keyword", "string", "物料名称、编码或品类关键词，如「控制板」「LAMP-HEAD」"),
                        enumParam("warehouseType", false, "仓库类型", WAREHOUSE_TYPES),
                        optional("dateFrom", "date", "统计开始日期 yyyy-MM-dd"),
                        optional("dateTo", "date", "统计结束日期 yyyy-MM-dd"))),
                new Capability("inventory_stock", KIND_QUERY, "库存现有量",
                        "查当前各仓库各库位的库存数量、可用量、占用量，可按仓库编码与物料关键词过滤",
                        null, List.of(
                        optional("warehouseCode", "string", "仓库编码，如 LAMP-WH-RAW"),
                        optional("materialKeyword", "string", "物料名称或编码关键词"))),
                new Capability("inventory_below_safety", KIND_QUERY, "低于安全库存",
                        "查当前哪些物料低于安全库存：逐条给出可用量、安全库存阈值、缺口数量与所属预警规则，"
                                + "阈值与自动化规则执行时同源，可用量按该仓库类型下全部仓库合计；"
                                + "只读，不生成风险与补货建议",
                        null, List.of(
                        enumParam("warehouseType", false, "仓库类型", WAREHOUSE_TYPES),
                        optional("materialKeyword", "string", "物料名称或编码关键词"))),
                new Capability("work_order_detail", KIND_QUERY, "工单全息",
                        "按工单号查工单基本信息、用料需求、领料明细、报工明细、质检单与产品批次",
                        null, List.of(required("workOrderNo", "string", "工单号，形如 WO-17-20261024..."))),
                new Capability("work_order_readiness", KIND_QUERY, "开工条件核查",
                        "按工单号判断现在能不能开工：逐条给出制造准备、单据状态、用料需求、物料齐套、"
                                + "工序任务五项是否通过，缺料时列出是组件件（附来源子工单号与状态）还是库存件，"
                                + "并按「先完成组件生产、再领料转线边、最后复核开工」给出处置顺序",
                        null, List.of(required("workOrderNo", "string", "工单号，形如 WO-17-20261024..."))),
                new Capability("work_order_start_plan", KIND_QUERY, "开工处置方案",
                        "按工单号给出「怎么才能开工」的有序处置方案：逐条列出要先做的事、作用对象、原因、涉及单据和执行方式，"
                                + "并把「直接开工」与「先补齐前置条件」两条路径做只读比较（标准工时量级、需推进单据数）。"
                                + "只算工艺路线标准工时，不含排队换型等待，不能当交期承诺",
                        null, List.of(required("workOrderNo", "string", "工单号，形如 WO-17-20261024..."))),
                new Capability("production_daily", KIND_QUERY, "日产量与不良",
                        "按日期范围查每日报工产量、合格数、不良数与不良率",
                        null, List.of(
                        optional("dateFrom", "date", "开始日期 yyyy-MM-dd"),
                        optional("dateTo", "date", "结束日期 yyyy-MM-dd"))),
                new Capability("defect_analysis", KIND_QUERY, "不良分析",
                        "按日期范围查不良数量排名靠前的物料与工序",
                        null, List.of(
                        optional("dateFrom", "date", "开始日期 yyyy-MM-dd"),
                        optional("dateTo", "date", "结束日期 yyyy-MM-dd"))),
                new Capability("batch_trace", KIND_QUERY, "批次追溯",
                        "按批次号正查去向或反查来源物料与工单",
                        null, List.of(required("batchNo", "string", "批次号"))),
                new Capability("schedule_capacity", KIND_QUERY, "排程与产能",
                        "查产线排程与已排负荷，可按产线名称或编码筛选",
                        null, List.of(
                        optional("productKeyword", "string", "产品名称或编码关键词"),
                        optional("lineKeyword", "string", "产线名称或编码，如整机总装测试线、二号线"),
                        optional("dateFrom", "date", "开始日期 yyyy-MM-dd"),
                        optional("dateTo", "date", "结束日期 yyyy-MM-dd"))),
                new Capability("order_progress", KIND_QUERY, "订单进度",
                        "按生产订单号查订单数量、计划、工单、入库与发货进度",
                        null, List.of(required("orderNo", "string", "生产订单号，形如 PO-..."))),
                new Capability("order_schedule_simulation", KIND_QUERY, "订单排程推演",
                        "按生产订单号只读比较现状、调整资源、组件优先三种排法的开工与完工时间，"
                                + "与手工排程共用同一套班次与产能口径；不含排队换型待料，不能当交期承诺",
                        null, List.of(required("orderNo", "string", "生产订单号，形如 PO-..."))),
                new Capability("equipment_impact", KIND_QUERY, "设备故障影响",
                        "查设备状态与故障对在制任务的影响",
                        null, List.of(
                        optional("equipmentKeyword", "string", "设备名称或编码关键词"),
                        optional("dateFrom", "date", "开始日期 yyyy-MM-dd"),
                        optional("dateTo", "date", "结束日期 yyyy-MM-dd"))),
                new Capability("capacity_feasibility", KIND_QUERY, "追加产能测算",
                        "判断在现有负荷下再接若干数量能否按期完成",
                        null, List.of(
                        optional("productKeyword", "string", "产品名称或编码关键词"),
                        optional("extraQuantity", "int", "追加数量"),
                        optional("dateFrom", "date", "开始日期 yyyy-MM-dd"),
                        optional("dateTo", "date", "结束日期 yyyy-MM-dd"))),
                new Capability("bom_requirement", KIND_QUERY, "BOM 用量",
                        "按产品与生产台数逐层展开 BOM，算出每种物料要多少：子 BOM 递归拆到叶子物料，"
                                + "按发料粒度取整并计入损耗率，与工单领料同一套算法。"
                                + "回答「一台要用多少颗螺钉」「做 2 台一共要多少套线束」这类用量问题",
                        null, List.of(
                        optional("productKeyword", "string", "产品名称或编码关键词，如 LAMP-L200"),
                        optional("materialKeyword", "string", "只关心某个物料时填，如 螺钉、LAMP-M18"),
                        optional("quantity", "number", "生产台数，不填按 1 台"))),
                new Capability("quality_yield", KIND_QUERY, "质量良率",
                        "按检验单统计本期合格率：抽检数、合格数、不合格数与合格率。"
                                + "回答「这个月良率多少」「合格率是多少」这类问题；"
                                + "不要用产量报工的不良率代替，报工只记末道工序登记的不良，"
                                + "来料与过程检验判的不合格不在里面",
                        null, List.of(
                        optional("dateFrom", "date", "开始日期 yyyy-MM-dd"),
                        optional("dateTo", "date", "结束日期 yyyy-MM-dd"),
                        optional("productKeyword", "string", "产品名称或编码关键词"))),
                new Capability("work_order_list", KIND_QUERY, "工单列表",
                        "按产品关键词与状态列工单清单及其进度",
                        null, List.of(
                        optional("productKeyword", "string", "产品关键词"),
                        optional("status", "string", "工单状态，如 DRAFT/RELEASED/RUNNING/FINISHED"))),
                new Capability("customer_order", KIND_QUERY, "客户订单",
                        "按客户关键词查订单与交付情况",
                        null, List.of(optional("customerKeyword", "string", "客户名称关键词"))),
                new Capability("inspection_pending", KIND_QUERY, "待检清单",
                        "查待检与待判定的检验单，可按检验类型过滤",
                        null, List.of(optional("inspectionType", "string",
                        "检验类型：INCOMING 来料 / PROCESS 过程 / FINAL 终检 / OUTGOING 出货"))),
                new Capability(DATA_QUERY, KIND_QUERY, "通用数据查询",
                        "固定报表覆盖不到的问题用这个能力直接查系统里的基础数据与业务单据，"
                                + "例如「系统里有哪些角色」「质检员是谁」「供应商有几家」「最近有什么异常」。"
                                + "resource 从【可查数据资源】里选，filters 按该资源列出的筛选项填",
                        null, List.of(
                        required("resource", "string", "资源编码，取值见【可查数据资源】"),
                        optional("filters", "object", "筛选条件，键取该资源列出的筛选项名"))));
    }

    /**
     * 通用数据查询的能力编码。十几个固定报表覆盖不到的问题，走这个能力查白名单资源。
     */
    public static final String DATA_QUERY = "data_query";

    /**
     * 全部业务办理能力。targetNo 缺失时必须补问，不能猜。
     */
    public static List<Capability> commands() {
        return List.of(
                new Capability("CREATE_PRODUCTION_ORDER", KIND_COMMAND, "创建生产订单",
                        "根据用户明确提供的产品编码、客户编码、数量和交期创建生产订单草稿；缺任何信息先补问",
                        "PRODUCT", List.of(required("targetNo", "string", "用户明确提供的产品编码"),
                        required("customerCode", "string", "用户明确提供的客户编码"),
                        required("orderQuantity", "number", "订单数量"),
                        required("deliveryDate", "date", "交期 yyyy-MM-dd"))),
                new Capability("SCHEDULE_PRODUCTION_ORDER", KIND_COMMAND, "安排生产订单",
                        "为明确指定的待排生产订单选择可用产线和时段，先展示方案，确认后生成正式生产计划",
                        "PRODUCTION_ORDER", List.of(required("targetNo", "string", "生产订单号 PO-..."),
                        optional("lineCode", "string", "用户明确指定的产线编码，不指定则推荐最早完工产线"))),
                new Capability("GENERATE_WORK_ORDER", KIND_COMMAND, "按订单生成工单",
                        "为生产订单的唯一已生效根计划生成根工单及所需组件工单；先预览并确认",
                        "PRODUCTION_ORDER", List.of(required("targetNo", "string", "生产订单号 PO-..."))),
                new Capability("RELEASE_WORK_ORDER", KIND_COMMAND, "下达生产工单",
                        "把草稿状态的工单下达为可执行状态，会冻结执行快照",
                        "WORK_ORDER", List.of(required("targetNo", "string", "工单号 WO-..."))),
                new Capability("GENERATE_DISPATCH_ORDER", KIND_COMMAND, "生成派工单",
                        "按已下达工单与可用班组生成待派发派工单；检查班组、工时和实际用料，需确认",
                        "WORK_ORDER", List.of(required("targetNo", "string", "工单号 WO-..."),
                        optional("teamName", "string", "用户明确指定的班组名称"))),
                new Capability("RELEASE_DISPATCH_ORDER", KIND_COMMAND, "下达派工单",
                        "下达派工单并生成工序任务", "DISPATCH_ORDER",
                        List.of(required("targetNo", "string", "派工单号 DO-..."))),
                new Capability("START_TASK", KIND_COMMAND, "开工工序任务",
                        "开工指定工序任务，执行人员、工位、物料与前序校验", "TASK",
                        List.of(required("targetNo", "string", "工序任务号 TK-..."))),
                new Capability("SUBMIT_WORK_REPORT", KIND_COMMAND, "提交报工",
                        "按真实完成数量提交报工", "TASK",
                        List.of(required("targetNo", "string", "工序任务号 TK-..."),
                                required("qualifiedQuantity", "number", "合格数量"),
                                optional("defectQuantity", "number", "不良数量"))),
                new Capability("START_WORK_ORDER", KIND_COMMAND, "开始生产",
                        "把已下达的工单置为生产中",
                        "WORK_ORDER", List.of(required("targetNo", "string", "工单号 WO-..."))),
                new Capability("GENERATE_MATERIAL_ISSUE", KIND_COMMAND, "生成领料单",
                        "原料仓领本级原料；组件可从半成品仓整份领用，逐件复验通过后转线边替代组件生产。先下达上级与本组件工单",
                        "WORK_ORDER", List.of(required("targetNo", "string", "工单号 WO-..."),
                                optional("warehouseId","integer","明确选择的领料仓库ID"),
                                optional("warehouseCode","string","明确指定的仓库编码"),
                                enumParam("warehouseType",false,"供给仓库类型",List.of("RAW","SEMI_FINISHED")))),
                new Capability("TRANSFER_TO_LINE_SIDE", KIND_COMMAND, "领料转线边",
                        "把已提交的领料单成对移库到线边仓，原仓减、线边按工单占用加",
                        "OUTBOUND_ORDER", List.of(required("targetNo", "string", "领料单号 MI-..."))),
                new Capability("CONFIRM_OUTBOUND", KIND_COMMAND, "确认出库",
                        "确认待确认的出库单，真实扣减库存并写流水",
                        "OUTBOUND_ORDER", List.of(required("targetNo", "string", "出库单号 MI-/SO-/OUT-"))),
                new Capability("CONFIRM_INBOUND", KIND_COMMAND, "确认入库",
                        "确认待确认的入库单，真实增加库存并写流水",
                        "INBOUND_ORDER", List.of(required("targetNo", "string", "入库单号 IN-..."))),
                new Capability("SUBMIT_INSPECTION", KIND_COMMAND, "提交检验结果",
                        "提交检验单的抽样、合格、不合格数量，结论由领域服务按不合格率判定",
                        "INSPECTION_ORDER", List.of(
                        required("targetNo", "string", "检验单号 QI-..."),
                        optional("sampleQuantity", "int", "抽样数量"),
                        optional("qualifiedQuantity", "int", "合格数量"),
                        optional("unqualifiedQuantity", "int", "不合格数量"),
                        enumParam("inspectionResult", false, "结论", List.of("PASS", "FAIL", "CONCESSION")))),
                new Capability("GENERATE_PRODUCTION_INBOUND", KIND_COMMAND, "生成成品入库单",
                        "为终检放行的产品批次生成生产入库单", "PRODUCT_LOT",
                        List.of(required("targetNo", "string", "产品批次号 LP-..."))),
                new Capability("GENERATE_SALES_OUTBOUND", KIND_COMMAND, "生成销售出库单",
                        "为符合条件的订单生成发货单", "PRODUCTION_ORDER",
                        List.of(required("targetNo", "string", "生产订单号 PO-..."))),
                new Capability("CREATE_OUTGOING_INSPECTION", KIND_COMMAND, "生成出货检验单",
                        "为销售出库单生成出货检验单", "OUTBOUND_ORDER",
                        List.of(required("targetNo", "string", "销售出库单号 SI-..."))),
                new Capability("CONFIRM_SALES_OUTBOUND", KIND_COMMAND, "确认发货",
                        "出货检验完成并符合业务规则后确认发货", "OUTBOUND_ORDER",
                        List.of(required("targetNo", "string", "销售出库单号 SI-..."))),
                new Capability("AUDIT_WORK_REPORT", KIND_COMMAND, "审核报工",
                        "审核通过或驳回报工单，通过后扣减线边实耗并登记产出",
                        "WORK_REPORT", List.of(
                        required("targetNo", "string", "报工单号 WR-..."),
                        enumParam("auditResult", true, "审核结论", List.of("APPROVED", "REJECTED")))));
    }

    /**
     * 生成给大模型的能力清单文本。只包含编码、名称、说明与参数，不含任何实现细节。
     */
    public static String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("【只读查询能力】\n");
        for (Capability capability : queries()) {
            builder.append("- ").append(capability.code()).append("（").append(capability.label()).append("）：")
                    .append(capability.description());
            appendParams(builder, capability.params());
            builder.append('\n');
        }
        builder.append("【业务办理能力】\n");
        for (Capability capability : commands()) {
            builder.append("- ").append(capability.code()).append("（").append(capability.label()).append("）：")
                    .append(capability.description());
            appendParams(builder, capability.params());
            builder.append('\n');
        }
        builder.append(AiDataResourceCatalog.describe());
        return builder.toString();
    }

    private static void appendParams(StringBuilder builder, List<Param> params) {
        if (params.isEmpty()) {
            return;
        }
        builder.append("；参数：");
        for (int index = 0; index < params.size(); index++) {
            Param param = params.get(index);
            if (index > 0) {
                builder.append("、");
            }
            builder.append(param.name());
            if (param.required()) {
                builder.append("(必填)");
            }
            builder.append("=").append(param.description());
            if (!param.allowed().isEmpty()) {
                builder.append("，可选值 ").append(String.join("/", param.allowed()));
            }
        }
    }

    /**
     * 判断编码是否是已知查询能力。
     */
    public static boolean isQuery(String code) {
        return queries().stream().anyMatch(item -> item.code().equals(code));
    }

    /**
     * 判断编码是否是已知办理动作。
     */
    public static boolean isCommand(String code) {
        return commands().stream().anyMatch(item -> item.code().equals(code));
    }

    /**
     * 面向前端的统一能力清单：按当前账号权限过滤，只给出有权限的能力。
     * 返回结构与 {@link Capability} 一一对应，前端据此渲染能力分组与快捷问句占位。
     */
    public static List<Map<String, Object>> capabilities(AuthorizationContext authorization) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Capability capability : queries()) {
            if (!allowed(authorization, capability.code())) {
                continue;
            }
            result.add(toView(capability, KIND_QUERY));
        }
        for (Capability capability : commands()) {
            result.add(toView(capability, KIND_COMMAND));
        }
        return result;
    }

    private static Map<String, Object> toView(Capability capability, String kind) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", capability.code());
        view.put("kind", kind);
        view.put("label", capability.label());
        view.put("description", capability.description());
        view.put("targetType", capability.targetType() == null ? "" : capability.targetType());
        List<Map<String, Object>> params = new ArrayList<>();
        for (Param param : capability.params()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", param.name());
            item.put("type", param.type());
            item.put("required", param.required());
            item.put("description", param.description());
            item.put("allowed", param.allowed());
            params.add(item);
        }
        view.put("params", params);
        return view;
    }

    /**
     * 查询类能力要按账号权限过滤；办理类能力在预览阶段由 AiCommandService 逐动作校验，
     * 这里只过滤掉连 ai:assistant:use 都没有的账号，避免泄露能力清单。
     */
    private static boolean allowed(AuthorizationContext authorization, String code) {
        return AiQueryAccessPolicy.allowed(authorization, code);
    }
}
