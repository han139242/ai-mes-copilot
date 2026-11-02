package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.ai.AiCommandExecution;
import com.neuedu.ems.entity.ai.AiCommandSequence;
import com.neuedu.ems.entity.ai.AiCommandStep;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgSupplyAllocation;
import com.neuedu.ems.entity.mfg.MfgTask;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.mfg.MfgWorkOrderMaterial;
import com.neuedu.ems.entity.mfg.MfgWorkReport;
import com.neuedu.ems.entity.qms.QmsInspectionOrder;
import com.neuedu.ems.entity.qms.QmsInspectionResult;
import com.neuedu.ems.entity.qms.QmsInspectionSchemeItem;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.entity.wms.WmsInboundDetail;
import com.neuedu.ems.entity.wms.WmsInventory;
import com.neuedu.ems.entity.wms.WmsInboundOrder;
import com.neuedu.ems.entity.wms.WmsLocation;
import com.neuedu.ems.entity.wms.WmsOutboundDetail;
import com.neuedu.ems.entity.wms.WmsOutboundOrder;
import com.neuedu.ems.entity.wms.WmsStockMovement;
import com.neuedu.ems.entity.wms.WmsWarehouse;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import com.neuedu.ems.mapper.ai.AiCommandExecutionMapper;
import com.neuedu.ems.mapper.ai.AiCommandSequenceMapper;
import com.neuedu.ems.mapper.ai.AiCommandStepMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgSupplyAllocationMapper;
import com.neuedu.ems.mapper.mfg.MfgTaskMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMaterialMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkReportMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionOrderMapper;
import com.neuedu.ems.mapper.qms.QmsInspectionSchemeItemMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.mapper.wms.WmsInboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsInboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsInventoryMapper;
import com.neuedu.ems.mapper.wms.WmsLocationMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundDetailMapper;
import com.neuedu.ems.mapper.wms.WmsOutboundOrderMapper;
import com.neuedu.ems.mapper.wms.WmsStockMovementMapper;
import com.neuedu.ems.mapper.wms.WmsWarehouseMapper;
import com.neuedu.ems.service.ai.AiCommandService;
import com.neuedu.ems.service.mfg.MfgWorkOrderService;
import com.neuedu.ems.service.mfg.MfgWorkReportService;
import com.neuedu.ems.service.qms.QmsInspectionOrderService;
import com.neuedu.ems.service.wms.WmsInboundOrderService;
import com.neuedu.ems.service.wms.WmsOutboundOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 写命令服务实现。
 *
 * <p>三段式：preview 只读校验并落凭据，execute 凭凭据回放真实领域服务，全程写命令记录与步骤流水。
 * 命令记录的状态更新走独立事务，业务执行段单独包一个事务，避免失败时把审计记录一起回滚。</p>
 */
@Service
public class AiCommandServiceImpl implements AiCommandService {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger(AiCommandServiceImpl.class);

    /* ---------- 命令状态 ---------- */
    private static final String STATUS_PREVIEW = "PREVIEW";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_EXECUTING = "EXECUTING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_REJECTED = "REJECTED";
    /** 已结束、可计入成功率的分支状态。 */
    private static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of(
            STATUS_SUCCEEDED, STATUS_FAILED, STATUS_REJECTED, STATUS_EXPIRED);

    /* ---------- 步骤状态 ---------- */
    private static final String STEP_PENDING = "PENDING";
    private static final String STEP_RUNNING = "RUNNING";
    private static final String STEP_SUCCEEDED = "SUCCEEDED";
    private static final String STEP_FAILED = "FAILED";
    private static final String STEP_SKIPPED = "SKIPPED";
    /** 预览未通过时拿不到具体原因时的通用说明。 */
    private static final String DEFAULT_BLOCKING_REASON = "前置条件校验未通过";

    /* ---------- 动作编码 ---------- */
    public static final String ACTION_RELEASE_WORK_ORDER = "RELEASE_WORK_ORDER";
    public static final String ACTION_START_WORK_ORDER = "START_WORK_ORDER";
    public static final String ACTION_GENERATE_MATERIAL_ISSUE = "GENERATE_MATERIAL_ISSUE";
    public static final String ACTION_TRANSFER_TO_LINE_SIDE = "TRANSFER_TO_LINE_SIDE";
    public static final String ACTION_CONFIRM_OUTBOUND = "CONFIRM_OUTBOUND";
    public static final String ACTION_CONFIRM_INBOUND = "CONFIRM_INBOUND";
    public static final String ACTION_SUBMIT_INSPECTION = "SUBMIT_INSPECTION";
    public static final String ACTION_AUDIT_WORK_REPORT = "AUDIT_WORK_REPORT";
    public static final String ACTION_CREATE_PRODUCTION_ORDER = "CREATE_PRODUCTION_ORDER";
    public static final String ACTION_SCHEDULE_PRODUCTION_ORDER = "SCHEDULE_PRODUCTION_ORDER";
    public static final String ACTION_GENERATE_WORK_ORDER = "GENERATE_WORK_ORDER";
    public static final String ACTION_GENERATE_DISPATCH = "GENERATE_DISPATCH_ORDER";
    /** 派工单下达后才会生成工序任务，工单才能开工——这一步不能缺。 */
    public static final String ACTION_RELEASE_DISPATCH = "RELEASE_DISPATCH_ORDER";
    /** 报工：按工序任务提交合格/不良数量，之后才能审核。 */
    public static final String ACTION_SUBMIT_WORK_REPORT = "SUBMIT_WORK_REPORT";
    public static final String ACTION_START_TASK = "START_TASK";
    /** 第 7 阶段：把终检合格的产品批次生成生产入库单。 */
    public static final String ACTION_GENERATE_PRODUCTION_INBOUND = "GENERATE_PRODUCTION_INBOUND";
    /** 第 8 阶段：为完工订单生成销售出库（发货）单。 */
    public static final String ACTION_GENERATE_SALES_OUTBOUND = "GENERATE_SALES_OUTBOUND";
    /** 第 8 阶段：为发货单生成出货检验单。 */
    public static final String ACTION_CREATE_OUTGOING_INSPECTION = "CREATE_OUTGOING_INSPECTION";
    /** 第 8 阶段：确认发货，扣减成品库存。 */
    public static final String ACTION_CONFIRM_SALES_OUTBOUND = "CONFIRM_SALES_OUTBOUND";

    /* ---------- 目标类型 ---------- */
    private static final String TARGET_WORK_ORDER = "WORK_ORDER";
    private static final String TARGET_OUTBOUND = "OUTBOUND_ORDER";
    private static final String TARGET_INBOUND = "INBOUND_ORDER";
    private static final String TARGET_INSPECTION = "INSPECTION_ORDER";
    private static final String TARGET_WORK_REPORT = "WORK_REPORT";
    private static final String TARGET_PRODUCT = "PRODUCT";
    private static final String TARGET_PRODUCTION_ORDER = "PRODUCTION_ORDER";
    private static final String TARGET_PRODUCT_LOT = "PRODUCT_LOT";
    /** 派工单：下达后生成工序任务。 */
    private static final String TARGET_DISPATCH = "DISPATCH_ORDER";
    /** 工序任务：报工按任务提交。 */
    private static final String TARGET_TASK = "TASK";

    private static final long PREVIEW_TTL_MINUTES = 5;
    /** 候选单据一次最多列多少张，避免「帮我处理待入库单」把整张表拉给前端。 */
    private static final int CANDIDATE_LIMIT = 50;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("HH:00");

    /**
     * 业务单号识别。出库单实际用 MI-（领料）与 SO-（销售）前缀，不能只认 OUT-，
     * 否则「确认出库 MI-WO1-...」会被当成工单号或识别不出动作。
     * 前缀按长度优先排列，保证 MI-WO1-xxx 整体匹配而不是从中间的 WO 开始截断。
     */
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(?i)(?:[A-Z][A-Z0-9]{0,7}-(?=(?:MI|SI|SO|OUT|IN|WO|PO|PLN|PL|QI|WR|PU|DO|TK|NC|ST|RP|LP)-\\d{8}-\\d{3,}(?![A-Za-z0-9_-])))?"
                    + "(MI|SI|SO|OUT|IN|WO|PO|PLN|PL|QI|WR|PU|DO|TK|NC|ST|RP|LP)[A-Za-z0-9_-]*");
    private static final Pattern ID_PATTERN = Pattern.compile(
            "(?i)(?:ID|编号|序号|主键)\\s*[:：]?\\s*(\\d+)");
    private static final Pattern BARE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{4,}");
    /**
     * 出库单号前缀。领料出库用 MI-，销售出库用 SO-，历史数据与接口示例里的 OUT- 也兼容。
     */
    private static final List<String> OUTBOUND_PREFIXES = List.of("MI", "SI", "SO", "OUT");

    @Autowired private com.neuedu.ems.service.sys.BusinessNumberService numbers;
    private static final List<String> READ_MARKERS = List.of(
            "查询", "查看", "看看", "多少", "哪些", "统计", "报表", "分析", "为什么", "怎么", "是否", "有没有",
            "列表", "进度", "情况", "？", "?");
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    /** 无法识别动作时的统一提示，preview 与 previewExplicit 共用。 */
    private static final String UNRECOGNIZED_ACTION_MESSAGE =
            "没有识别出可执行的业务动作，目前支持下达工单、开始生产、生成领料单、领料转线边、"
                    + "确认出库、确认入库、提交检验、审核报工、生成成品入库单、生成销售出库单、确认发货";

    private static final List<ActionSpec> SPECS = List.of(
            new ActionSpec(ACTION_CREATE_PRODUCTION_ORDER, "创建生产订单", TARGET_PRODUCT,
                    "mfg:production-order:create", "根据明确的产品、客户、数量和交期创建生产订单草稿。",
                    "为客户 C001 创建 LAMP-L200 生产订单，数量 2 台，交期 2026-11-10"),
            new ActionSpec(ACTION_SCHEDULE_PRODUCTION_ORDER, "安排生产订单", TARGET_PRODUCTION_ORDER,
                    "mfg:schedule-scenario:confirm", "选择可用产线和时段，确认后生成生产计划。",
                    "为生产订单 PO-202610240001 排产"),
            new ActionSpec(ACTION_GENERATE_WORK_ORDER, "按订单生成工单", TARGET_PRODUCTION_ORDER,
                    "mfg:work-order:generate", "根据已生效的生产计划生成根工单及需要的组件工单。",
                    "为订单 C1-PO-20261031-006 生成工单"),
            new ActionSpec(ACTION_RELEASE_WORK_ORDER, "下达生产工单", TARGET_WORK_ORDER,
                    "mfg:work-order:release",
                    "把草稿工单下达为可执行工单，冻结物料清单与工艺路线快照。",
                    "下达工单 WO-20261024120000001"),
            new ActionSpec(ACTION_GENERATE_DISPATCH, "生成派工单", TARGET_WORK_ORDER,
                    "mfg:dispatch-order:generate", "按已下达工单和可用班组生成派工单。",
                    "为工单 WO-20261024120000001 生成派工单"),
            new ActionSpec(ACTION_RELEASE_DISPATCH, "下达派工单", TARGET_DISPATCH,
                    "mfg:dispatch-order:release",
                    "下达派工单并生成工序任务，工单才能开始生产。",
                    "下达派工单 C1-DO-20261031-008"),
            new ActionSpec(ACTION_START_TASK, "开工工序任务", TARGET_TASK,
                    "mfg:task:start", "开工指定工序任务，复用人员、工位、物料和前序工序校验。",
                    "开工工序任务 C1-TK-20261031-017"),
            new ActionSpec(ACTION_SUBMIT_WORK_REPORT, "提交报工", TARGET_TASK,
                    "mfg:work-report:create",
                    "按工序任务提交合格数量与不良数量，生成待审核报工单。",
                    "给工序任务 C1-TK-20261031-011 报工合格 2 件"),
            new ActionSpec(ACTION_START_WORK_ORDER, "开始生产", TARGET_WORK_ORDER,
                    "mfg:work-order:start",
                    "在用料齐套且工序任务待开工时启动工单，进入生产中。",
                    "开始生产 WO-20261024120000001"),
            new ActionSpec(ACTION_GENERATE_MATERIAL_ISSUE, "生成领料单", TARGET_WORK_ORDER,
                    "mfg:work-order:material-issue",
                    "按工单用料需求生成领料出库单，等待转线边与出库确认。",
                    "给 WO-20261024120000001 生成领料单"),
            new ActionSpec(ACTION_TRANSFER_TO_LINE_SIDE, "领料转线边", TARGET_OUTBOUND,
                    "wms:outbound-order:confirm",
                    "把领料单从原料仓转到线边仓库位，供产线直接取用。",
                    "把领料单 OUT-20261024120000002 转到线边仓"),
            new ActionSpec(ACTION_CONFIRM_OUTBOUND, "确认出库", TARGET_OUTBOUND,
                    "wms:outbound-order:confirm",
                    "确认出库单，扣减库存并生成库存流水。",
                    "确认出库 OUT-20261024120000002"),
            new ActionSpec(ACTION_CONFIRM_INBOUND, "确认入库", TARGET_INBOUND,
                    "wms:inbound-order:confirm",
                    "确认入库单，增加库存并生成库存流水。",
                    "确认入库 IN-20261024120000003"),
            new ActionSpec(ACTION_SUBMIT_INSPECTION, "提交检验结果", TARGET_INSPECTION,
                    "qms:inspection-order:submit",
                    "给草稿检验单录入样本与不合格数并提交结论。",
                    "把检验单 QI20261024120000004 判为合格"),
            new ActionSpec(ACTION_AUDIT_WORK_REPORT, "审核报工", TARGET_WORK_REPORT,
                    "mfg:work-report:audit",
                    "审核提交状态的报工单，通过或驳回。",
                    "审核通过报工 WR20261024120000005"),
            new ActionSpec(ACTION_GENERATE_PRODUCTION_INBOUND, "生成成品入库单", TARGET_PRODUCT_LOT,
                    "wms:inbound-order:create",
                    "把终检合格的产品批次生成生产入库单，等待确认入库。",
                    "把产品批次 C1-LP-20261031-007 生成成品入库单"),
            new ActionSpec(ACTION_GENERATE_SALES_OUTBOUND, "生成销售出库单", TARGET_PRODUCTION_ORDER,
                    "wms:sales-outbound:create",
                    "为已完工的生产订单生成销售出库（发货）单，等待出货检验与确认。",
                    "为生产订单 C1-PO-20261031-006 生成发货单"),
            new ActionSpec(ACTION_CREATE_OUTGOING_INSPECTION, "生成出货检验单", TARGET_OUTBOUND,
                    "wms:sales-outbound:inspection",
                    "为待确认的销售出库单生成出货检验单，检验合格后才能发运。",
                    "给发货单 C1-SI-20261031-001 生成出货检验"),
            new ActionSpec(ACTION_CONFIRM_SALES_OUTBOUND, "确认发货", TARGET_OUTBOUND,
                    "wms:sales-outbound:confirm",
                    "确认销售出库单，扣减成品库存并回写销售订单已发数量。",
                    "确认发货 C1-SI-20261031-001"));

    private static final Map<String, ActionSpec> SPEC_BY_ACTION = buildSpecIndex();

    @Autowired(required = false)
    private AiCommandExecutionMapper executionMapper;
    @Autowired(required = false)
    private com.neuedu.ems.mapper.mdm.MdmProductMapper aiProductMapper;
    @Autowired(required = false)
    private com.neuedu.ems.mapper.mdm.MdmCustomerMapper aiCustomerMapper;
    @Autowired(required = false)
    private com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper aiProductionOrderMapper;
    @Autowired(required = false)
    private com.neuedu.ems.service.ai.AiProductionOrderCreationService aiProductionOrderCreation;
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.neuedu.ems.service.ai.AiProductionScheduleService aiProductionSchedule;
    @Autowired(required = false)
    private AiCommandSequenceMapper sequenceMapper;
    @Autowired(required = false)
    private AiCommandStepMapper stepMapper;
    @Autowired(required = false)
    private MfgWorkOrderMapper workOrderMapper;
    @Autowired(required = false)
    private com.neuedu.ems.service.mfg.StockComponentSupplyService stockComponentSupply;
    @Autowired(required = false)
    private MfgProductionPlanMapper productionPlanMapper;
    @Autowired(required = false)
    private MfgWorkOrderMaterialMapper workOrderMaterialMapper;
    @Autowired(required = false)
    private MfgTaskMapper taskMapper;
    @Autowired(required = false)
    private com.neuedu.ems.service.mfg.MfgTaskService aiTaskService;
    @Autowired(required = false)
    private MfgWorkReportMapper workReportMapper;
    @Autowired(required = false)
    private WmsOutboundOrderMapper outboundOrderMapper;
    @Autowired(required = false)
    private WmsOutboundDetailMapper outboundDetailMapper;
    @Autowired(required = false)
    private WmsInboundOrderMapper inboundOrderMapper;
    @Autowired(required = false)
    private WmsInboundDetailMapper inboundDetailMapper;
    @Autowired(required = false)
    private WmsWarehouseMapper warehouseMapper;
    @Autowired(required = false)
    private WmsLocationMapper locationMapper;
    @Autowired(required = false)
    private WmsInventoryMapper inventoryMapper;
    @Autowired(required = false)
    private MfgSupplyAllocationMapper supplyAllocationMapper;
    @Autowired(required = false)
    private QmsInspectionOrderMapper inspectionOrderMapper;
    @Autowired(required = false)
    private QmsInspectionSchemeItemMapper inspectionSchemeItemMapper;
    /** 出货检验前置判断：产品是否配了已发布的 OUTGOING 方案。 */
    @Autowired(required = false)
    private com.neuedu.ems.mapper.qms.QmsInspectionSchemeMapper inspectionSchemeMapper;
    @Autowired(required = false)
    private SysOperationLogMapper operationLogMapper;
    /** 后置复核读状态流水：领域动作真正落库的状态变化是唯一可信的完成证据。 */
    @Autowired(required = false)
    private SysStatusChangeLogMapper statusChangeLogMapper;
    /** 后置复核读库存流水：涉及库存的动作必须能在流水里找到对应记录。 */
    @Autowired(required = false)
    private WmsStockMovementMapper stockMovementMapper;
    @Autowired(required = false)
    private MfgWorkOrderService workOrderService;
    @Autowired(required = false)
    private com.neuedu.ems.service.mfg.MfgDispatchOrderService aiDispatchService;
    @Autowired(required = false)
    private com.neuedu.ems.mapper.mfg.MfgDispatchOrderMapper aiDispatchMapper;
    @Autowired(required = false)
    private WmsOutboundOrderService outboundOrderService;
    @Autowired(required = false)
    private WmsInboundOrderService inboundOrderService;
    /** 第 8 阶段发货链路：生成销售出库、出货检验、确认发货。 */
    @Autowired(required = false)
    private com.neuedu.ems.service.wms.WmsSalesOutboundService salesOutboundService;
    /** 提交报工与报工选料：AI 报工时必须复用领域规则算用料，不能自己编耗用。 */
    @Autowired(required = false)
    private com.neuedu.ems.service.mfg.MfgWorkReportService workReportSubmitService;
    @Autowired(required = false)
    private com.neuedu.ems.service.mfg.WorkReportPreparationService workReportPreparationService;
    /** 第 7 阶段成品入库按产品批次定位，批次号前缀是 LP。 */
    @Autowired(required = false)
    private com.neuedu.ems.mapper.trace.TraceProductLotMapper productLotMapper;
    @Autowired(required = false)
    private QmsInspectionOrderService inspectionOrderService;
    @Autowired(required = false)
    private MfgWorkReportService workReportService;
    @Autowired(required = false)
    private ObjectMapper objectMapper;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    /* ==================== 解析 ==================== */

    @Override
    public String parseIntent(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.trim();
        // 只读问句不进入写命令分支，避免「查 WO-xxx 领料情况」被误判为生成领料单。
        if (containsAny(normalized, READ_MARKERS)) {
            return null;
        }
        return matchAction(normalized, CodePresence.of(normalized));
    }

    /**
     * 按优先级依次尝试各动作的匹配规则：先判最具体的动作，避免「领料」这类宽词抢走「转线边」。
     * 各匹配方法都是纯函数，无副作用，因此可以一次性求值后按顺序取第一个命中项。
     */
    private String matchAction(String text, CodePresence codes) {
        String[] candidates = {
                // 第 7、8 阶段：成品入库 → 发货。放最前面，否则「生成采购入库单」这类词会被
                // 「确认入库」抢走，而「生成出货检验单」里的「检验」也会被「提交检验」抢走。
                matchGenerateProductionInbound(text, codes),
                matchConfirmSalesOutbound(text, codes),
                matchCreateOutgoingInspection(text, codes),
                matchGenerateSalesOutbound(text, codes),
                matchReleaseDispatch(text, codes),
                matchStartTask(text, codes),
                matchSubmitWorkReport(text, codes),
                matchTransfer(text, codes),
                matchConfirmOutbound(text, codes),
                matchConfirmInbound(text, codes),
                matchSubmitInspection(text, codes),
                matchAuditWorkReport(text, codes),
                containsAny(text, "生成工单", "创建工单") ? ACTION_GENERATE_WORK_ORDER : null,
                containsAny(text, "生成派工单", "创建派工单", "给工单派工") ? ACTION_GENERATE_DISPATCH : null,
                matchRelease(text, codes),
                matchStart(text, codes),
                matchMaterialIssue(text, codes)
        };
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** 生成成品入库单：出现入库意图，且带产品批次号或明确提到成品入库。 */
    private String matchGenerateProductionInbound(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "生成成品入库", "成品入库", "生成生产入库", "生产入库单", "生成入库单");
        // 「确认入库」是另一件事，带「确认」时不抢。
        if (containsAny(text, "确认入库", "入库确认")) {
            return null;
        }
        return hinted && (codes.productLot() || codes.productionOrder()) ? ACTION_GENERATE_PRODUCTION_INBOUND : null;
    }

    /** 生成销售出库单（发货单）：明确说发货或销售出库，且带订单号或销售出库单号。 */
    private String matchGenerateSalesOutbound(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "生成发货单", "生成销售出库", "销售出库单", "发货单", "我要发货");
        if (containsAny(text, "确认发货", "发货确认")) {
            return null;
        }
        return hinted && (codes.productionOrder() || codes.outbound()) ? ACTION_GENERATE_SALES_OUTBOUND : null;
    }

    /** 生成出货检验单：明确说出货检验，且带销售出库单号。 */
    private String matchCreateOutgoingInspection(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "出货检验单", "出货检验", "生成出货检验");
        return hinted ? ACTION_CREATE_OUTGOING_INSPECTION : null;
    }

    /** 确认发货：明确说确认发货，且带销售出库单号。 */
    private String matchConfirmSalesOutbound(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "确认发货", "发货确认");
        return hinted && codes.outbound() ? ACTION_CONFIRM_SALES_OUTBOUND : null;
    }

    /**
     * 下达派工单：必须排在「下达工单」之前判断，否则「下达派工单」会被 release 抢走，
     * 结果是拿派工单号去查工单查不到。
     */
    private String matchReleaseDispatch(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "下达派工单", "下达派工", "派工单下达", "派发派工单");
        return hinted && (codes.dispatch() || text.contains("派工单")) ? ACTION_RELEASE_DISPATCH : null;
    }

    /**
     * 提交报工：说报工且带任务号或数量。必须排在「审核报工」之前，
     * 否则「给任务报工 2 件」里没有审核词倒也无妨，但「报工并审核」会被审核抢走。
     */
    private String matchStartTask(String text, CodePresence codes) {
        return containsAny(text, "开工", "开始", "启动") && (codes.task() || text.contains("工序任务"))
                ? ACTION_START_TASK : null;
    }

    private String matchSubmitWorkReport(String text, CodePresence codes) {
        boolean hinted = text.contains("报工") && !containsAny(text, "审核", "审批");
        return hinted && (codes.task() || codes.hasId()) ? ACTION_SUBMIT_WORK_REPORT : null;
    }

    /** 领料转线边：出现线边或移库意图，且带出库单号或明确提到领料单。 */
    private String matchTransfer(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "线边", "移库");
        return hinted && (codes.outbound() || text.contains("领料单")) ? ACTION_TRANSFER_TO_LINE_SIDE : null;
    }

    /** 确认出库：明确说确认出库，且带出库单号。 */
    private String matchConfirmOutbound(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "确认出库", "出库确认", "出库过账");
        return hinted && codes.outbound() ? ACTION_CONFIRM_OUTBOUND : null;
    }

    /** 确认入库：明确说确认入库，且带入库单号。 */
    private String matchConfirmInbound(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "确认入库", "入库确认", "入库过账");
        return hinted && codes.inbound() ? ACTION_CONFIRM_INBOUND : null;
    }

    /** 提交检验：必须带检验单号，且出现提交、判定类词或检验结论词。 */
    private String matchSubmitInspection(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "提交检验", "判为", "判定为", "检验结果", "检验单",
                "合格", "不合格", "让步");
        return codes.inspection() && hinted ? ACTION_SUBMIT_INSPECTION : null;
    }

    /** 审核报工：出现报工与审核类词，且带报工单号或主键编号。 */
    private String matchAuditWorkReport(String text, CodePresence codes) {
        boolean hinted = text.contains("报工") && containsAny(text, "审核", "审批");
        return hinted && (codes.report() || codes.hasId()) ? ACTION_AUDIT_WORK_REPORT : null;
    }

    /** 下达工单：出现下达、投产类词，且带工单号或提到工单。 */
    private String matchRelease(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "下达", "投产", "释放工单");
        return hinted && (codes.workOrder() || text.contains("工单")) ? ACTION_RELEASE_WORK_ORDER : null;
    }

    /** 开始生产：出现开工类词，且带工单号或提到工单。 */
    private String matchStart(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "开始生产", "开工", "启动工单", "开始工单");
        return hinted && (codes.workOrder() || text.contains("工单")) ? ACTION_START_WORK_ORDER : null;
    }

    /** 生成领料单：出现领料、备料类词，且必须带工单号。 */
    private String matchMaterialIssue(String text, CodePresence codes) {
        boolean hinted = containsAny(text, "生成领料单", "领料单生成", "领料", "备料");
        return hinted && codes.workOrder() ? ACTION_GENERATE_MATERIAL_ISSUE : null;
    }

    /**
     * 文本中出现的业务单号前缀集合。只做一次正则扫描，供各匹配规则复用，
     * 避免每个规则各扫一遍正则。
     */
    private record CodePresence(boolean workOrder, boolean outbound, boolean inbound,
                                boolean inspection, boolean report, boolean hasId,
                                boolean productLot, boolean productionOrder, boolean dispatch,
                                boolean task) {

        static CodePresence of(String text) {
            String upper = text.toUpperCase(Locale.ROOT);
            return new CodePresence(
                    hasPrefix(upper, "WO"),
                    hasAnyPrefix(upper, OUTBOUND_PREFIXES),
                    hasPrefix(upper, "IN"),
                    hasPrefix(upper, "QI"),
                    hasPrefix(upper, "WR"),
                    ID_PATTERN.matcher(text).find(),
                    // 产品批次号 C1-LP-…：成品入库按批次定位。
                    hasPrefix(upper, "LP"),
                    hasPrefix(upper, "PO"),
                    // 派工单号 C1-DO-…：下达派工单按它定位。
                    hasPrefix(upper, "DO"),
                    // 工序任务号 C1-TK-…：报工按它定位。
                    hasPrefix(upper, "TK"));
        }

        /** 文本中是否出现指定前缀的单号片段。 */
        private static boolean hasPrefix(String upper, String prefix) {
            return CODE_PATTERN.matcher(upper).results()
                    .anyMatch(r -> r.group(1).equalsIgnoreCase(prefix));
        }

        /** 文本中是否出现任意一个给定前缀的单号片段。 */
        private static boolean hasAnyPrefix(String upper, List<String> prefixes) {
            return CODE_PATTERN.matcher(upper).results()
                    .anyMatch(r -> prefixes.contains(r.group(1).toUpperCase(Locale.ROOT)));
        }
    }

    /** 文本是否包含任一关键词。 */
    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 文本是否包含关键词列表中的任意一个。 */
    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    /* ==================== 预览 ==================== */

    @Override
    public Map<String, Object> preview(AiCommandPreviewRequest request, AuthorizationContext authorization) {
        requireAssistant(authorization);
        if (request == null || !StringUtils.hasText(request.text())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请说出要执行的业务操作，例如「下达工单 WO-xxx」");
        }
        String text = request.text().trim();
        String action = parseIntent(text);
        if (action == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), UNRECOGNIZED_ACTION_MESSAGE);
        }
        ActionSpec spec = SPEC_BY_ACTION.get(action);
        requirePermission(authorization, spec.permission());
        requireGenerationReadPermissions(action, authorization);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", action);
        params.put("text", text);
        Target target = resolveTarget(action, text, params);
        if (ACTION_GENERATE_WORK_ORDER.equals(action)) prepareGeneration(target, params);
        if (ACTION_GENERATE_DISPATCH.equals(action)) prepareDispatch(target, params, text);
        return assemblePreview(spec, target, params, text, request.conversationId(), request.source(),
                authorization);
    }

    /**
     * 组装预览结果并落库：跑前置条件与影响评估，生成确认凭据，写入命令记录。
     * preview 与 previewExplicit 共用，保证两条入口的预览体结构完全一致。
     */
    private Map<String, Object> assemblePreview(ActionSpec spec, Target target, Map<String, Object> params,
                                                String text, String conversationId, String source,
                                                AuthorizationContext authorization) {
        List<Map<String, Object>> impacts = new ArrayList<>();
        List<Map<String, Object>> preconditions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        evaluate(spec, target, params, impacts, preconditions, warnings);

        boolean canExecute = preconditions.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(PREVIEW_TTL_MINUTES);
        String confirmationId = UUID.randomUUID().toString().replace("-", "");
        String requestHash = hashOf(params);

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("confirmationId", confirmationId);
        preview.put("action", spec.action());
        preview.put("actionLabel", spec.label());
        preview.put("targetType", spec.targetType());
        preview.put("targetNo", target.no());
        preview.put("targetId", target.id());
        preview.put("params", params);
        preview.put("impacts", impacts);
        preview.put("preconditions", preconditions);
        preview.put("warnings", warnings);
        preview.put("expiresAt", expiresAt.format(ISO));
        preview.put("status", STATUS_PREVIEW);
        preview.put("canExecute", canExecute);
        preview.put("requiredPermission", spec.permission());
        preview.put("recoverySteps",AiCommandRecovery.steps(spec.action(),target.no(),preconditions));

        AiCommandExecution record = new AiCommandExecution();
        record.setConfirmationId(confirmationId);
        record.setRequestHash(requestHash);
        record.setConversationId(trimToNull(conversationId));
        record.setUserId(authorization.userId());
        record.setRawText(text.length() > 500 ? text.substring(0, 500) : text);
        record.setIntent(spec.action());
        record.setAction(spec.action());
        record.setActionLabel(spec.label());
        record.setTargetType(spec.targetType());
        record.setTargetNo(target.no());
        record.setTargetId(target.id());
        record.setParamsJson(toJson(params));
        record.setPreviewJson(toJson(preview));
        record.setImpactsJson(toJson(impacts));
        record.setPreconditionsJson(toJson(preconditions));
        record.setStatus(STATUS_PREVIEW);
        record.setSource(StringUtils.hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : "API");
        record.setExpiresAt(expiresAt);
        record.setVersion(0);
        record.setDeletedFlag(0);
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        if (executionMapper != null) {
            executionMapper.insert(record);
        }
        preview.put("commandId", record.getCommandId());
        return preview;
    }

    /* ==================== 执行 ==================== */

    @Override
    public Map<String, Object> execute(AiCommandExecuteRequest request, AuthorizationContext authorization) {
        requireAssistant(authorization);
        if (request == null || !StringUtils.hasText(request.confirmationId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "缺少预览确认凭据，请先预览");
        }
        if (executionMapper == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "命令执行记录不可用");
        }
        AiCommandExecution record = loadCommandByConfirmation(request.confirmationId().trim());
        ActionSpec spec = SPEC_BY_ACTION.get(record.getAction());
        if (spec == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "该预览凭据对应的动作不受支持");
        }
        requirePermission(authorization, spec.permission());
        requireGenerationReadPermissions(spec.action(), authorization);
        verifyRequestedAction(request.action(), spec);

        Map<String, Object> params = paramsOf(record);
        String currentHash = hashOf(params);
        verifyRequestHash(record, currentHash);

        // 幂等判定放在状态与过期校验之前：同一凭据、同一幂等键的重复提交是客户端重试，
        // 应回放既有结果而不是报「不能重复执行」。
        String idempotencyKey = trimToNull(request.idempotencyKey());
        Map<String, Object> replayed = replayByIdempotencyKey(idempotencyKey, currentHash);
        if (replayed != null) {
            return replayed;
        }
        verifyExecutableState(record);

        long startedAt = System.currentTimeMillis();
        mutate(record, item -> {
            item.setStatus(STATUS_CONFIRMED);
            item.setIdempotencyKey(idempotencyKey);
        });
        mutate(record, item -> item.setStatus(STATUS_EXECUTING));

        Target target = reloadTarget(record, spec);
        ensurePreconditionsPass(record, spec, target, params, authorization, startedAt);

        BizResult biz = invokeDomain(record, spec, target, params, authorization, request, idempotencyKey,
                startedAt);
        verifyOutcome(record, spec, biz, startedAt, authorization);
        return completeExecution(record, biz, startedAt, authorization);
    }

    /** 按确认凭据取命令记录，取不到直接报失效。 */
    private AiCommandExecution loadCommandByConfirmation(String confirmationId) {
        AiCommandExecution record = executionMapper.selectOne(new QueryWrapper<AiCommandExecution>()
                .eq("confirmation_id", confirmationId)
                .eq("deleted_flag", 0)
                .last("limit 1"));
        if (record == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "预览凭据不存在或已失效，请重新预览");
        }
        return record;
    }

    /** 校验客户端声明的动作与预览动作一致，避免拿 A 动作的凭据执行 B 动作。 */
    private void verifyRequestedAction(String requestedAction, ActionSpec spec) {
        if (!StringUtils.hasText(requestedAction)) {
            return;
        }
        if (!spec.action().equalsIgnoreCase(requestedAction.trim())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "确认动作与预览动作不一致，预览为「" + spec.label() + "」");
        }
    }

    /** 参数摘要必须与预览时一致，不一致视为参数被篡改，凭据置为已拒绝。 */
    private void verifyRequestHash(AiCommandExecution record, String currentHash) {
        if (currentHash.equals(record.getRequestHash())) {
            return;
        }
        mutate(record, item -> item.setStatus(STATUS_REJECTED));
        throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                "预览参数已被改动，与确认凭据不一致，请重新预览");
    }

    /**
     * 幂等键命中时回放既有结果。同键不同参数视为冲突；同键但上次未成功则如实报失败原因。
     *
     * @return 需要回放的响应体；未命中幂等键时返回 null
     */
    private Map<String, Object> replayByIdempotencyKey(String idempotencyKey, String currentHash) {
        if (idempotencyKey == null) {
            return null;
        }
        AiCommandExecution replay = executionMapper.selectOne(new QueryWrapper<AiCommandExecution>()
                .eq("idempotency_key", idempotencyKey)
                .eq("deleted_flag", 0)
                .orderByAsc("command_id")
                .last("limit 1"));
        if (replay == null) {
            return null;
        }
        if (!currentHash.equals(replay.getRequestHash())) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                    "同一幂等键已用于不同参数的命令，不能复用");
        }
        // 失败的命令不能按成功回放：同键重试要看到原来的失败原因，而不是「操作成功」。
        if (!STATUS_SUCCEEDED.equals(replay.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    StringUtils.hasText(replay.getErrorMessage())
                            ? "该幂等键上次执行未成功：" + replay.getErrorMessage()
                            : "该幂等键上次执行未成功（状态 " + replay.getStatus() + "），请重新预览后再执行");
        }
        return replayResult(replay);
    }

    /** 状态与有效期校验：只有待确认的预览可以执行，过期则置为已过期。 */
    private void verifyExecutableState(AiCommandExecution record) {
        if (!STATUS_PREVIEW.equals(record.getStatus()) && !STATUS_CONFIRMED.equals(record.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "该命令当前状态为 " + record.getStatus() + "，不能重复执行");
        }
        if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(LocalDateTime.now())) {
            mutate(record, item -> item.setStatus(STATUS_EXPIRED));
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "预览已超过 5 分钟有效期，请重新预览");
        }
    }

    /**
     * 执行前重新按单号加载目标单据，保证前置条件校验用的是数据库当前状态而不是预览时的快照。
     */
    private Target reloadTarget(AiCommandExecution record, ActionSpec spec) {
        Target resolved = StringUtils.hasText(record.getTargetNo())
                ? loadByNo(spec.action(), record.getTargetNo()) : null;
        if (resolved == null) {
            return new Target(spec.targetType(), record.getTargetNo(), record.getTargetId(), null);
        }
        if (resolved.id() != null) {
            return resolved;
        }
        return new Target(resolved.type(), resolved.no(), record.getTargetId(), resolved.entity());
    }

    /** 前置条件不通过时写失败步骤、失败状态与操作日志，然后抛出业务异常。 */
    private void ensurePreconditionsPass(AiCommandExecution record, ActionSpec spec, Target target,
                                         Map<String, Object> params, AuthorizationContext authorization,
                                         long startedAt) {
        List<Map<String, Object>> preconditions = new ArrayList<>();
        evaluate(spec, target, params, new ArrayList<>(), preconditions, new ArrayList<>());
        List<Map<String, Object>> failed = preconditions.stream()
                .filter(item -> !Boolean.TRUE.equals(item.get("passed")))
                .toList();
        AiCommandStep precheck = startStep(record.getCommandId(), 1, "PRECHECK",
                spec.action() + ":" + record.getTargetNo(), spec.targetType(),
                record.getTargetNo(), record.getTargetId());
        if (failed.isEmpty()) {
            finishStep(precheck, STEP_SUCCEEDED, null);
            return;
        }
        String reason = "前置条件校验未通过：" + failed.stream()
                .map(item -> String.valueOf(item.get("label")))
                .reduce((a, b) -> a + "；" + b).orElse("");
        finishStep(precheck, STEP_FAILED, reason);
        markFailed(record, reason, startedAt);
        writeOperationLog(record, authorization.userId(), "失败：" + reason);
        throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), reason);
    }

    /** 调用真实领域服务，失败时把原因落到步骤、命令状态与操作日志。 */
    private BizResult invokeDomain(AiCommandExecution record, ActionSpec spec, Target target,
                                   Map<String, Object> params, AuthorizationContext authorization,
                                   AiCommandExecuteRequest request, String idempotencyKey, long startedAt) {
        AiCommandStep callStep = startStep(record.getCommandId(), 2, "DOMAIN_CALL",
                spec.action() + ":" + record.getTargetNo(), spec.targetType(),
                record.getTargetNo(), record.getTargetId());
        BizResult biz;
        try {
            String domainKey = (ACTION_CREATE_PRODUCTION_ORDER.equals(spec.action())
                    || ACTION_GENERATE_WORK_ORDER.equals(spec.action())
                    || ACTION_GENERATE_DISPATCH.equals(spec.action())
                    || ACTION_SCHEDULE_PRODUCTION_ORDER.equals(spec.action())) && idempotencyKey == null
                    ? record.getConfirmationId() : idempotencyKey;
            biz = inTransaction(() -> callDomain(spec, target, params, authorization.userId(),
                    trimToNull(request.remark()), domainKey));
        } catch (BusinessException e) {
            finishStep(callStep, STEP_FAILED, e.getMessage());
            markFailed(record, e.getMessage(), startedAt);
            writeOperationLog(record, authorization.userId(), "失败：" + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            String message = StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
            finishStep(callStep, STEP_FAILED, message);
            markFailed(record, message, startedAt);
            writeOperationLog(record, authorization.userId(), "失败：" + message);
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "执行失败：" + message);
        }
        finishStep(callStep, STEP_SUCCEEDED, null);
        bindStepBizNo(callStep, biz);
        return biz;
    }

    /** 领域调用拿到真实业务单号后回填到步骤流水上，便于按单号反查执行链。 */
    private void bindStepBizNo(AiCommandStep callStep, BizResult biz) {
        if (biz.bizNo() == null || stepMapper == null) {
            return;
        }
        AiCommandStep persisted = new AiCommandStep();
        persisted.setStepId(callStep.getStepId());
        persisted.setBizNo(biz.bizNo());
        persisted.setBizId(biz.bizId());
        persisted.setVersion(callStep.getVersion());
        stepMapper.updateById(persisted);
    }

    /**
     * 后置复核：领域调用返回成功不等于业务目标达成。
     *
     * <p>前置条件只保证「执行前允许做」，这里回查执行后的真实业务事实：
     * 主单据状态是否真的变了、状态流水有没有留下这次变更、涉及库存的动作有没有成对流水。
     * 复核不通过时命令记为失败并说明「业务已提交但复核未通过」，不能报成功。</p>
     */
    private void verifyOutcome(AiCommandExecution record, ActionSpec spec, BizResult biz, long startedAt,
                               AuthorizationContext authorization) {
        AiCommandStep checkStep = startStep(record.getCommandId(), 3, "POSTCHECK",
                spec.action() + ":" + record.getTargetNo(), spec.targetType(),
                record.getTargetNo(), record.getTargetId());
        String problem;
        try {
            problem = outcomeProblem(spec.action(), biz);
        } catch (RuntimeException e) {
            String detail = StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
            problem = "读取业务数据失败，无法核对执行结果：" + detail;
        }
        if (problem == null) {
            finishStep(checkStep, STEP_SUCCEEDED, null);
            return;
        }
        String reason = "业务已提交，但后置复核未通过：" + problem;
        finishStep(checkStep, STEP_FAILED, reason);
        markFailed(record, reason, startedAt);
        writeOperationLog(record, authorization.userId(), "失败：" + reason);
        throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), reason);
    }

    /** 按动作回查真实业务事实；返回 null 表示复核通过。 */
    private String outcomeProblem(String action, BizResult biz) {
        Long bizId = biz.bizId();
        if (bizId == null) {
            return "领域服务没有返回业务主键，无法核对落库结果";
        }
        return switch (action) {
            case ACTION_CREATE_PRODUCTION_ORDER -> {
                var order = aiProductionOrderMapper == null ? null : aiProductionOrderMapper.selectById(bizId);
                yield order != null && Integer.valueOf(0).equals(order.getDeletedFlag())
                        && BusinessStatus.Document.DRAFT.equals(order.getStatus()) ? null
                        : "生产订单不存在或不是草稿状态";
            }
            case ACTION_SCHEDULE_PRODUCTION_ORDER -> {
                var plan = productionPlanMapper == null ? null : productionPlanMapper.selectById(bizId);
                yield plan != null && Integer.valueOf(0).equals(plan.getDeletedFlag())
                        && BusinessStatus.Document.RELEASED.equals(plan.getStatus()) ? null
                        : "生产计划不存在或尚未生效";
            }
            case ACTION_GENERATE_WORK_ORDER -> workOrderOutcomeProblem(bizId,
                    BusinessStatus.Document.DRAFT, "GENERATE_WORK_ORDER");
            case ACTION_GENERATE_DISPATCH -> dispatchOutcomeProblem(bizId);
            case ACTION_RELEASE_WORK_ORDER -> workOrderOutcomeProblem(bizId,
                    BusinessStatus.Document.RELEASED, "RELEASE_WORK_ORDER");
            case ACTION_START_WORK_ORDER -> workOrderOutcomeProblem(bizId,
                    BusinessStatus.Production.RUNNING, "START_WORK_ORDER");
            case ACTION_GENERATE_MATERIAL_ISSUE -> materialIssueOutcomeProblem(bizId);
            case ACTION_TRANSFER_TO_LINE_SIDE -> transferOutcomeProblem(bizId);
            case ACTION_CONFIRM_OUTBOUND -> outboundOutcomeProblem(bizId);
            case ACTION_CONFIRM_INBOUND -> inboundOutcomeProblem(bizId);
            case ACTION_SUBMIT_INSPECTION -> inspectionOutcomeProblem(bizId);
            case ACTION_AUDIT_WORK_REPORT -> workReportOutcomeProblem(bizId);
            case ACTION_GENERATE_PRODUCTION_INBOUND -> inboundRowExistsProblem(bizId, "生产入库单");
            case ACTION_GENERATE_SALES_OUTBOUND -> salesOutboundOutcomeProblem(bizId, false);
            case ACTION_CREATE_OUTGOING_INSPECTION -> inspectionRowExistsProblem(bizId);
            case ACTION_RELEASE_DISPATCH -> dispatchReleasedProblem(bizId);
            case ACTION_START_TASK -> taskStartedProblem(bizId);
            case ACTION_SUBMIT_WORK_REPORT -> workReportSubmittedProblem(bizId);
            case ACTION_CONFIRM_SALES_OUTBOUND -> salesOutboundOutcomeProblem(bizId, true);
            default -> "动作 " + action + " 还没有配置后置复核规则";
        };
    }

    private String taskStartedProblem(Long taskId) {
        MfgTask task = taskMapper == null ? null : taskMapper.selectById(taskId);
        if (task == null || !BusinessStatus.Production.RUNNING.equals(task.getStatus())) {
            return "工序任务未进入生产中，请刷新后核对";
        }
        return statusLogProblem("MFG_TASK", taskId, BusinessStatus.Production.RUNNING, "START_TASK");
    }

    /** 工单类动作：状态必须已经变成目标状态，且留下对应状态流水。 */
    private String workOrderOutcomeProblem(Long workOrderId, String expectedStatus, String reason) {
        MfgWorkOrder order = workOrderMapper == null ? null : workOrderMapper.selectById(workOrderId);
        if (order == null) {
            return "工单 " + workOrderId + " 读不到";
        }
        if (!expectedStatus.equals(order.getStatus())) {
            return "工单 " + workOrderId + " 当前状态是 " + statusText(order.getStatus())
                    + "，不是预期的 " + statusText(expectedStatus);
        }
        return statusLogProblem("MFG_WORK_ORDER", workOrderId, expectedStatus, reason);
    }

    private String dispatchOutcomeProblem(Long id) {
        var dispatch = aiDispatchMapper == null ? null : aiDispatchMapper.selectById(id);
        if (dispatch == null || !Integer.valueOf(0).equals(dispatch.getDeletedFlag())
                || !"WAIT_DISPATCH".equals(dispatch.getStatus())) return "派工单不存在或状态异常";
        return statusLogProblem("MFG_DISPATCH_ORDER", id, "WAIT_DISPATCH", "GENERATE_DISPATCH_ORDER");
    }

    /** 提交报工后置复核：报工单存在且处于待审核。 */
    private String workReportSubmittedProblem(Long reportId) {
        MfgWorkReport report = workReportMapper == null ? null : workReportMapper.selectById(reportId);
        if (report == null || !Integer.valueOf(0).equals(report.getDeletedFlag())) {
            return "报工单 " + reportId + " 读不到";
        }
        if (!BusinessStatus.Document.SUBMITTED.equals(report.getAuditStatus())) {
            return "报工单状态是 " + statusText(report.getAuditStatus()) + "，不是待审核";
        }
        return null;
    }

    /** 下达派工单后置复核：状态必须变 RELEASED，且真的生成了工序任务。 */
    private String dispatchReleasedProblem(Long dispatchId) {
        var dispatch = aiDispatchMapper == null ? null : aiDispatchMapper.selectById(dispatchId);
        if (dispatch == null || !Integer.valueOf(0).equals(dispatch.getDeletedFlag())) {
            return "派工单 " + dispatchId + " 读不到";
        }
        if (!BusinessStatus.Document.RELEASED.equals(dispatch.getStatus())) {
            return "派工单状态是 " + statusText(dispatch.getStatus()) + "，不是已下达";
        }
        long tasks = countWorkOrderTasks(dispatch);
        if (tasks <= 0) {
            return "派工单已下达，但没有生成任何工序任务";
        }
        return null;
    }

    /** 生成领料单：单据存在、类型是生产领料、状态待确认、且有明细。 */
    private String materialIssueOutcomeProblem(Long outboundId) {
        WmsOutboundOrder order = outboundOrderMapper == null ? null : outboundOrderMapper.selectById(outboundId);
        Long details = order == null ? null : activeDetailCount(outboundId);
        String problem = null;
        if (order == null) {
            problem = "领料单 " + outboundId + " 读不到";
        } else if (!"MATERIAL_ISSUE".equals(order.getOutboundType())) {
            problem = "出库单 " + outboundId + " 类型是 " + order.getOutboundType() + "，不是生产领料";
        } else if (!BusinessStatus.Document.SUBMITTED.equals(order.getAuditStatus())) {
            problem = "领料单 " + outboundId + " 当前状态是 " + statusText(order.getAuditStatus())
                    + "，不是待确认";
        } else if (details == null || details == 0) {
            problem = "领料单 " + outboundId + " 没有任何有效明细";
        }
        return problem;
    }

    /** 领料转线边：单据已确认，且明细逐条成对（出、入各一条）写入转移流水。 */
    private String transferOutcomeProblem(Long outboundId) {
        WmsOutboundOrder order = outboundOrderMapper == null ? null : outboundOrderMapper.selectById(outboundId);
        Long details = order == null ? null : activeDetailCount(outboundId);
        Long movements = details == null ? null : movementCount(outboundId, "TRANSFER");
        long expected = details == null ? 0 : details * 2;
        long actual = movements == null ? 0 : movements;
        String problem = null;
        if (order == null) {
            problem = "领料单 " + outboundId + " 读不到";
        } else if (!BusinessStatus.Document.APPROVED.equals(order.getAuditStatus())) {
            problem = "领料单 " + outboundId + " 当前状态是 " + statusText(order.getAuditStatus())
                    + "，不是已确认";
        } else if (details == null || details == 0) {
            problem = "领料单 " + outboundId + " 没有任何有效明细，无法确认转出内容";
        } else if (actual < expected) {
            problem = "线边转移流水只有 " + actual + " 条，"
                    + details + " 条明细应有 " + expected + " 条（转出、转入各一条）";
        }
        if (problem != null) {
            return problem;
        }
        return statusLogProblem("WMS_OUTBOUND_ORDER", outboundId,
                BusinessStatus.Document.APPROVED, "TRANSFER_TO_LINE_SIDE");
    }

    /** 确认出库：单据已确认、有出库时间，且每条明细都有出库流水。 */
    private String outboundOutcomeProblem(Long outboundId) {
        WmsOutboundOrder order = outboundOrderMapper == null ? null : outboundOrderMapper.selectById(outboundId);
        Long details = order == null ? null : activeDetailCount(outboundId);
        Long movements = details == null ? null : movementCount(outboundId, "OUTBOUND");
        long actual = movements == null ? 0 : movements;
        String problem = null;
        if (order == null) {
            problem = "出库单 " + outboundId + " 读不到";
        } else if (!BusinessStatus.Document.APPROVED.equals(order.getAuditStatus())) {
            problem = "出库单 " + outboundId + " 当前状态是 " + statusText(order.getAuditStatus())
                    + "，不是已确认";
        } else if (order.getOutboundTime() == null) {
            problem = "出库单 " + outboundId + " 没有记录出库时间";
        } else if (details == null || details == 0) {
            problem = "出库单 " + outboundId + " 没有任何有效明细，无法确认扣减内容";
        } else if (actual < details) {
            problem = "出库流水只有 " + actual + " 条，"
                    + details + " 条明细应各有一条出库流水";
        }
        if (problem != null) {
            return problem;
        }
        return statusLogProblem("WMS_OUTBOUND_ORDER", outboundId,
                BusinessStatus.Document.APPROVED, "CONFIRM_OUTBOUND");
    }

    /** 确认入库：单据已确认，且每条明细都有入库流水。 */
    private String inboundOutcomeProblem(Long inboundId) {
        WmsInboundOrder order = inboundOrderMapper == null ? null : inboundOrderMapper.selectById(inboundId);
        Long details = order == null || inboundDetailMapper == null ? null : inboundDetailMapper.selectCount(
                new QueryWrapper<WmsInboundDetail>()
                        .eq("inbound_id", inboundId)
                        .eq("deleted_flag", 0));
        Long movements = details == null ? null : movementCount(inboundId, "INBOUND");
        long actual = movements == null ? 0 : movements;
        String problem = null;
        if (order == null) {
            problem = "入库单 " + inboundId + " 读不到";
        } else if (!BusinessStatus.Document.APPROVED.equals(order.getAuditStatus())) {
            problem = "入库单 " + inboundId + " 当前状态是 " + statusText(order.getAuditStatus())
                    + "，不是已确认";
        } else if (details == null || details == 0) {
            problem = "入库单 " + inboundId + " 没有任何有效明细，无法确认入库内容";
        } else if (actual < details) {
            problem = "入库流水只有 " + actual + " 条，"
                    + details + " 条明细应各有一条入库流水";
        }
        if (problem != null) {
            return problem;
        }
        return statusLogProblem("WMS_INBOUND_ORDER", inboundId,
                BusinessStatus.Document.APPROVED, "CONFIRM_INBOUND");
    }

    /** 提交检验：检验单已完成、结论有效。 */
    private String inspectionOutcomeProblem(Long inspectionId) {
        QmsInspectionOrder order = inspectionOrderMapper == null ? null : inspectionOrderMapper.selectById(inspectionId);
        String problem = null;
        if (order == null) {
            problem = "检验单 " + inspectionId + " 读不到";
        } else if (!BusinessStatus.Production.FINISHED.equals(order.getStatus())) {
            problem = "检验单 " + inspectionId + " 当前状态是 " + statusText(order.getStatus()) + "，不是已完成";
        } else if (!BusinessStatus.isInspectionResult(order.getInspectionResult())) {
            problem = "检验单 " + inspectionId + " 没有写入有效的检验结论";
        }
        if (problem != null) {
            return problem;
        }
        return statusLogProblem("QMS_INSPECTION_ORDER", inspectionId,
                BusinessStatus.Production.FINISHED, "SUBMIT_INSPECTION");
    }

    /** 审核报工：审核结论必须已经落库。 */
    private String workReportOutcomeProblem(Long reportId) {
        MfgWorkReport report = workReportMapper == null ? null : workReportMapper.selectById(reportId);
        if (report == null) {
            return "报工单 " + reportId + " 读不到";
        }
        if (!BusinessStatus.isApprovalResult(report.getAuditStatus())) {
            return "报工单 " + reportId + " 审核状态是 " + statusText(report.getAuditStatus())
                    + "，不是已审核";
        }
        return statusLogProblem("MFG_WORK_REPORT", reportId, report.getAuditStatus(), "AUDIT_WORK_REPORT");
    }

    /** 单据行存在即视为生成成功；领域服务只在真落库后才返回主键。 */
    private String inboundRowExistsProblem(Long inboundId, String label) {
        WmsInboundOrder order = inboundOrderMapper == null ? null : inboundOrderMapper.selectById(inboundId);
        if (order == null) {
            return label + " " + inboundId + " 读不到";
        }
        if (!Integer.valueOf(0).equals(order.getDeletedFlag())) {
            return label + " " + inboundId + " 已被删除";
        }
        return null;
    }

    /** 出货检验单存在且未删除。 */
    private String inspectionRowExistsProblem(Long inspectionId) {
        QmsInspectionOrder inspection = inspectionOrderMapper == null ? null
                : inspectionOrderMapper.selectById(inspectionId);
        if (inspection == null) {
            return "出货检验单 " + inspectionId + " 读不到";
        }
        if (!Integer.valueOf(0).equals(inspection.getDeletedFlag())) {
            return "出货检验单 " + inspectionId + " 已被删除";
        }
        return null;
    }

    /**
     * 销售出库的后置复核：生成时只要求单据存在且待确认，确认发货时要求状态已变 APPROVED
     * 且留下对应的库存流水。
     */
    private String salesOutboundOutcomeProblem(Long outboundId, boolean expectConfirmed) {
        WmsOutboundOrder order = outboundOrderMapper == null ? null : outboundOrderMapper.selectById(outboundId);
        if (order == null) {
            return "销售出库单 " + outboundId + " 读不到";
        }
        if (!Integer.valueOf(0).equals(order.getDeletedFlag())) {
            return "销售出库单 " + outboundId + " 已被删除";
        }
        if (!"SALE".equals(order.getOutboundType())) {
            return "出库单 " + outboundId + " 类型是 " + order.getOutboundType() + "，不是销售出库";
        }
        if (!expectConfirmed) {
            return BusinessStatus.Document.SUBMITTED.equals(order.getAuditStatus()) ? null
                    : "销售出库单 " + outboundId + " 状态是 " + statusText(order.getAuditStatus())
                            + "，不是待确认";
        }
        if (!BusinessStatus.Document.APPROVED.equals(order.getAuditStatus())) {
            return "销售出库单 " + outboundId + " 状态是 " + statusText(order.getAuditStatus())
                    + "，不是已确认";
        }
        // 销售出库的库存流水类型是 SALES_OUT（领料出库才是 OUTBOUND），不能混用。
        Long movements = movementCount(outboundId, "SALES_OUT");
        if (movements == null || movements <= 0) {
            return "销售出库单 " + outboundId + " 没有生成库存流水";
        }
        return null;
    }

    /** 有效明细条数。 */
    private Long activeDetailCount(Long outboundId) {
        if (outboundDetailMapper == null) {
            return null;
        }
        return outboundDetailMapper.selectCount(new QueryWrapper<WmsOutboundDetail>()
                .eq("outbound_id", outboundId)
                .eq("deleted_flag", 0));
    }

    /** 某张单据某类库存流水的条数。 */
    private Long movementCount(Long sourceOrderId, String movementType) {
        if (stockMovementMapper == null) {
            return null;
        }
        return stockMovementMapper.selectCount(new QueryWrapper<WmsStockMovement>()
                .eq("source_order_id", sourceOrderId)
                .eq("movement_type", movementType)
                .eq("deleted_flag", 0));
    }

    /** 状态流水核对：必须能找到这次变更的痕迹。 */
    private String statusLogProblem(String bizType, Long bizId, String expectedStatus, String reason) {
        if (statusChangeLogMapper == null) {
            return "缺少状态流水读取能力，无法核对 " + bizType + " " + bizId + " 的变更是否落库";
        }
        Long count = statusChangeLogMapper.selectCount(new QueryWrapper<SysStatusChangeLog>()
                .eq("biz_type", bizType)
                .eq("biz_id", bizId)
                .eq("new_status", expectedStatus)
                .eq("change_reason", reason)
                .eq("deleted_flag", 0));
        if (count == null || count == 0) {
            return "没有找到 " + bizType + " " + bizId + " 变成 " + statusText(expectedStatus)
                    + " 的状态流水（" + reason + "）";
        }
        return null;
    }

    /** 执行成功后写结果、耗时与操作日志，并组装响应体。 */
    private Map<String, Object> completeExecution(AiCommandExecution record, BizResult biz, long startedAt,
                                                  AuthorizationContext authorization) {
        long elapsed = System.currentTimeMillis() - startedAt;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bizNo", biz.bizNo());
        result.put("bizId", biz.bizId());
        result.put("summary", biz.summary());
        String resultJson = toJson(result);
        LocalDateTime executedAt = LocalDateTime.now();
        mutate(record, item -> {
            item.setStatus(STATUS_SUCCEEDED);
            item.setResultJson(resultJson);
            item.setExecutedAt(executedAt);
            item.setElapsedMs(elapsed);
            item.setErrorMessage(null);
        });
        writeOperationLog(record, authorization.userId(), "成功：" + biz.summary());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("commandId", record.getCommandId());
        response.put("confirmationId", record.getConfirmationId());
        response.put("action", record.getAction());
        response.put("actionLabel", record.getActionLabel());
        response.put("targetType", record.getTargetType());
        response.put("targetNo", record.getTargetNo());
        response.put("targetId", record.getTargetId());
        response.put("status", STATUS_SUCCEEDED);
        response.put("bizNo", biz.bizNo());
        response.put("bizId", biz.bizId());
        response.put("summary", biz.summary());
        response.put("elapsedMs", elapsed);
        response.put("executedAt", executedAt.format(ISO));
        response.put("steps", listSteps(record.getCommandId()));
        return response;
    }

    /* ==================== 多步序列 ==================== */

    /** 序列状态。复用单步的终态编码，额外补 PREVIEW 与 PARTIAL_FAILED。 */
    private static final String SEQUENCE_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    /**
     * 一句话多步的分句符。中文里「然后 / 接着 / 再」是主要顺序连接词，
     * 顿号与逗号用于「下达、开始生产、再生成领料单」这种并列式口语。
     */
    private static final Pattern SEQUENCE_SPLITTER = Pattern.compile("(?:然后|接着|之后|最后|再|并且|，|,|；|;|、)");

    @Override
    public Map<String, Object> previewSequence(String text, String conversationId, String source,
                                               Map<String, Object> context, AuthorizationContext authorization) {
        requireAssistant(authorization);
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "请说出要连续办理的业务，例如「给 WO-xxx 生成领料单然后转到线边仓」");
        }
        List<SequenceStepInput> steps = parseSequenceSteps(text.trim());
        if (steps.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), UNRECOGNIZED_ACTION_MESSAGE);
        }
        return previewSequenceExplicit(steps, text.trim(), conversationId, source, authorization);
    }

    @Override
    public Map<String, Object> previewSequenceIfMulti(String text, String conversationId, String source,
                                                     Map<String, Object> context,
                                                     AuthorizationContext authorization) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        List<SequenceStepInput> steps = parseSequenceSteps(text.trim());
        if (steps.size() < 2) {
            return null;
        }
        return previewSequenceExplicit(steps, text.trim(), conversationId, source, authorization);
    }

    @Override
    public Map<String, Object> previewSequenceExplicit(List<SequenceStepInput> steps, String text,
                                                       String conversationId, String source,
                                                       AuthorizationContext authorization) {
        requireAssistant(authorization);
        if (steps == null || steps.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), UNRECOGNIZED_ACTION_MESSAGE);
        }
        if (sequenceMapper == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "多步命令记录不可用");
        }
        String rawText = StringUtils.hasText(text) ? text.trim() : "";
        List<SequenceStepSpec> specs = buildSequenceSpecs(steps, rawText, authorization);
        // 先定序列ID再逐步骤预览，步骤记录才能直接挂上 sequence_id。
        String planId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(PREVIEW_TTL_MINUTES);
        // 步骤预览：有明确目标的步骤当场生成确认凭据；依赖上一步产出的步骤先标「等待上一步产出」，
        // 真正的前置校验等到执行时用上一步的真实单号再做，避免解析阶段误判失败。
        List<Map<String, Object>> stepViews = buildStepViews(specs, planId, rawText, conversationId, source,
                authorization);
        boolean overallCanExecute = stepViews.stream()
                .noneMatch(view -> Boolean.FALSE.equals(view.get("canExecute")));
        AiCommandSequence sequence = newSequence(planId, conversationId, rawText, source,
                specs.size(), expiresAt, specs, stepViews, authorization);
        sequenceMapper.insert(sequence);
        return sequencePreview(planId, rawText, stepViews, overallCanExecute, specs.size(), expiresAt);
    }

    /** 逐步骤生成预览视图：依赖上一步产出的先挂起，其余当场预览。 */
    private List<Map<String, Object>> buildStepViews(List<SequenceStepSpec> specs, String planId, String rawText,
                                                     String conversationId, String source,
                                                     AuthorizationContext authorization) {
        List<Map<String, Object>> stepViews = new ArrayList<>();
        for (SequenceStepSpec spec : specs) {
            Map<String, Object> view = spec.dependsOnPrevious()
                    ? pendingStepView(spec, specs)
                    : previewStepView(spec, planId, rawText, conversationId, source, authorization);
            stepViews.add(view);
        }
        return stepViews;
    }

    /** 组装待落库的序列主记录。 */
    private AiCommandSequence newSequence(String planId, String conversationId, String rawText, String source,
                                          int totalSteps, LocalDateTime expiresAt,
                                          List<SequenceStepSpec> specs, List<Map<String, Object>> stepViews,
                                          AuthorizationContext authorization) {
        AiCommandSequence sequence = new AiCommandSequence();
        sequence.setSequenceId(planId);
        sequence.setConversationId(trimToNull(conversationId));
        sequence.setUserId(authorization.userId());
        sequence.setRawText(rawText.length() > 500 ? rawText.substring(0, 500) : rawText);
        sequence.setStatus(STATUS_PREVIEW);
        sequence.setTotalSteps(totalSteps);
        sequence.setExecutedSteps(0);
        sequence.setPlanJson(toJson(sequencePlan(specs, stepViews)));
        sequence.setSource(StringUtils.hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : "API");
        sequence.setExpiresAt(expiresAt);
        sequence.setVersion(0);
        sequence.setDeletedFlag(0);
        LocalDateTime now = LocalDateTime.now();
        sequence.setCreatedTime(now);
        sequence.setUpdatedTime(now);
        return sequence;
    }

    /** 预览接口返回体。 */
    private Map<String, Object> sequencePreview(String planId, String rawText, List<Map<String, Object>> stepViews,
                                                boolean overallCanExecute, int totalSteps, LocalDateTime expiresAt) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("planId", planId);
        preview.put("rawText", rawText);
        preview.put("steps", stepViews);
        preview.put("overallCanExecute", overallCanExecute);
        preview.put("totalSteps", totalSteps);
        preview.put("status", STATUS_PREVIEW);
        preview.put("expiresAt", expiresAt.format(ISO));
        return preview;
    }

    /**
     * 把一句话切成有序步骤。中文口语里后续分句常省略单据号，所以会把前文出现的单号继承下来，
     * 否则「WO-xxx 下达、开始生产」里的第二句会因为缺单号被识别成「未识别动作」。
     */
    private List<SequenceStepInput> parseSequenceSteps(String text) {
        List<SequenceStepInput> steps = new ArrayList<>();
        String inheritedCode = null;
        String previousAction = null;
        for (String fragment : SEQUENCE_SPLITTER.split(text)) {
            String piece = fragment.trim();
            if (piece.isEmpty()) {
                continue;
            }
            String ownCode = extractCode(piece, null);
            String code = StringUtils.hasText(ownCode) ? ownCode : inheritedCode;
            String action = matchSequenceAction(piece, code, previousAction);
            if (action == null) {
                continue;
            }
            if (StringUtils.hasText(ownCode)) {
                inheritedCode = ownCode;
            }
            String targetNo = !StringUtils.hasText(ownCode) && usesProducedTarget(previousAction, action)
                    ? null : sequenceTargetNo(action, code, ownCode);
            steps.add(new SequenceStepInput(action, targetNo,
                    singletonTextParam(piece)));
            previousAction = action;
        }
        return steps;
    }

    /** 匹配分句动作，含「承接上一步转线边」的特例。 */
    private String matchSequenceAction(String piece, String code, String previousAction) {
        // 把继承来的单号拼进扫描文本，让各动作的匹配规则能命中「省略单号」的分句。
        String scan = StringUtils.hasText(code) ? piece + " " + code : piece;
        String action = matchAction(scan, CodePresence.of(scan));
        boolean transferHint = previousAction != null && containsAny(piece, "线边", "移库");
        if (action == null && transferHint) {
            // 「生成领料单然后转到线边仓」的第二个分句没有单号也没有「领料单」字样，
            // 语义上就是承接上一步产出的领料单，因此按转线边处理并标记依赖上一步。
            return ACTION_TRANSFER_TO_LINE_SIDE;
        }
        return action;
    }

    /** 转线边且本分句没写单号时不定目标，留给执行阶段用上一步产出。 */
    private String sequenceTargetNo(String action, String code, String ownCode) {
        if (ACTION_TRANSFER_TO_LINE_SIDE.equals(action) && !StringUtils.hasText(ownCode)) {
            return null;
        }
        return StringUtils.hasText(code) ? code : null;
    }

    /** 步骤参数只有一个原文 text。 */
    private Map<String, Object> singletonTextParam(String piece) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("text", piece);
        return params;
    }

    /**
     * 组装步骤定义：解析动作定义、判定是否依赖上一步产出、判断能否在预览阶段就落凭据。
     */
    private List<SequenceStepSpec> buildSequenceSpecs(List<SequenceStepInput> inputs, String rawText,
                                                      AuthorizationContext authorization) {
        List<SequenceStepSpec> specs = new ArrayList<>();
        String previousAction = null;
        int stepNo = 0;
        for (SequenceStepInput input : inputs) {
            stepNo++;
            ActionSpec spec = resolveSpec(input.action());
            requirePermission(authorization, spec.permission());
            boolean depends = !StringUtils.hasText(input.targetNo())
                    && usesProducedTarget(previousAction, spec.action());
            specs.add(new SequenceStepSpec(stepNo, spec.action(), spec.label(), spec.targetType(),
                    input.targetNo(), sequenceParams(spec, input, rawText), depends));
            previousAction = spec.action();
        }
        return specs;
    }

    /** 只允许已知且类型匹配的相邻单据产出关系，不猜测工单下的多张工序任务。 */
    private static boolean usesProducedTarget(String previousAction, String action) {
        if (previousAction == null || action == null) return false;
        return switch (previousAction) {
            case "GENERATE_MATERIAL_ISSUE" -> List.of("TRANSFER_TO_LINE_SIDE", "CONFIRM_OUTBOUND").contains(action);
            case "GENERATE_DISPATCH_ORDER" -> "RELEASE_DISPATCH_ORDER".equals(action);
            case "GENERATE_PRODUCTION_INBOUND" -> "CONFIRM_INBOUND".equals(action);
            case "GENERATE_SALES_OUTBOUND" -> List.of("CREATE_OUTGOING_INSPECTION", "CONFIRM_SALES_OUTBOUND").contains(action);
            default -> false;
        };
    }

    /** 合并动作定义与分句参数，形成步骤参数表。 */
    private Map<String, Object> sequenceParams(ActionSpec spec, SequenceStepInput input, String rawText) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", spec.action());
        params.put("text", StringUtils.hasText(rawText) ? rawText : spec.label());
        if (input.params() == null) {
            return params;
        }
        input.params().forEach((key, value) -> {
            if (!"action".equals(key) && value != null) {
                params.put(key, value);
            }
        });
        return params;
    }

    /** 有明确目标的步骤：当场走一遍单步预览，拿到确认凭据并把记录挂到序列上。 */
    private Map<String, Object> previewStepView(SequenceStepSpec spec, String planId, String rawText,
                                                String conversationId, String source,
                                                AuthorizationContext authorization) {
        Map<String, Object> preview = previewExplicit(spec.action(), spec.targetNo(), spec.params(),
                new AiCommandPreviewRequest(rawText, conversationId, source, spec.params()), authorization);
        bindSequence(commandIdOf(preview), spec.stepNo(), planId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stepNo", spec.stepNo());
        view.put("action", preview.get("action"));
        view.put("actionLabel", preview.get("actionLabel"));
        view.put("targetType", preview.get("targetType"));
        view.put("targetNo", preview.get("targetNo"));
        view.put("params", preview.get("params"));
        view.put("impacts", preview.get("impacts"));
        view.put("preconditions", preview.get("preconditions"));
        view.put("canExecute", preview.get("canExecute"));
        view.put("blockingReason", blockingReasonOf(preview));
        view.put("status", STEP_PENDING);
        view.put("confirmationId", preview.get("confirmationId"));
        view.put("dependsOnPrevious", false);
        return view;
    }

    /**
     * 依赖上一步产出的步骤：解析阶段还没有出库单号，这里不判失败，只标出等待上一步产出。
     */
    private Map<String, Object> pendingStepView(SequenceStepSpec spec, List<SequenceStepSpec> allSpecs) {
        List<Map<String, Object>> preconditions = new ArrayList<>();
        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("code", "STEP_DEPENDENCY");
        dependency.put("label", "等待上一步产出");
        dependency.put("passed", true);
        dependency.put("detail", "上一步「" + previousLabel(allSpecs, spec.stepNo()) + "」成功后会带入业务单号再校验");
        preconditions.add(dependency);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stepNo", spec.stepNo());
        view.put("action", spec.action());
        view.put("actionLabel", spec.actionLabel());
        view.put("targetType", spec.targetType());
        view.put("targetNo", null);
        view.put("params", spec.params());
        view.put("impacts", List.of());
        view.put("preconditions", preconditions);
        view.put("canExecute", true);
        view.put("blockingReason", null);
        view.put("status", STEP_PENDING);
        view.put("confirmationId", null);
        view.put("dependsOnPrevious", true);
        return view;
    }

    /** 取上一步的中文名，用于说明依赖关系。 */
    private String previousLabel(List<SequenceStepSpec> specs, int stepNo) {
        for (SequenceStepSpec spec : specs) {
            if (spec.stepNo() == stepNo - 1) {
                return spec.actionLabel();
            }
        }
        return "上一步";
    }

    /** 从预览体里取命令ID，取不到返回 null。 */
    private Long commandIdOf(Map<String, Object> preview) {
        Object value = preview == null ? null : preview.get("commandId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return longOf(value);
    }

    /** 从预览体里取阻断原因：取第一条未通过的前置条件说明。 */
    private String blockingReasonOf(Map<String, Object> preview) {
        if (Boolean.TRUE.equals(preview.get("canExecute"))) {
            return null;
        }
        return firstFailedPrecondition(preview.get("preconditions"));
    }

    /** 取第一条未通过的前置条件说明，没有就退回通用文案。 */
    private String firstFailedPrecondition(Object items) {
        if (!(items instanceof List<?> list)) {
            return DEFAULT_BLOCKING_REASON;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("passed"))) {
                return String.valueOf(map.get("label")) + "（" + map.get("detail") + "）";
            }
        }
        return DEFAULT_BLOCKING_REASON;
    }

    /** 把步骤记录挂到序列上：单步查询口径不变，只是多两个可空字段。 */
    private void bindSequence(Long commandId, int stepNo, String planId) {
        if (commandId == null || executionMapper == null) {
            return;
        }
        AiCommandExecution update = new AiCommandExecution();
        update.setCommandId(commandId);
        update.setSequenceId(planId);
        update.setSequenceStepNo(stepNo);
        update.setVersion(0);
        executionMapper.updateById(update);
    }

    /** 序列计划落库结构：步骤定义 + 预览视图，执行与续跑都从这里还原。 */
    private List<Map<String, Object>> sequencePlan(List<SequenceStepSpec> specs, List<Map<String, Object>> views) {
        List<Map<String, Object>> plan = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            SequenceStepSpec spec = specs.get(i);
            Map<String, Object> view = i < views.size() ? views.get(i) : Map.of();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepNo", spec.stepNo());
            item.put("action", spec.action());
            item.put("actionLabel", spec.actionLabel());
            item.put("targetType", spec.targetType());
            item.put("targetNo", spec.targetNo());
            item.put("params", spec.params());
            item.put("dependsOnPrevious", spec.dependsOnPrevious());
            item.put("confirmationId", view.get("confirmationId"));
            item.put("canExecute", view.get("canExecute"));
            item.put("blockingReason", view.get("blockingReason"));
            plan.add(item);
        }
        return plan;
    }

    @Override
    public Map<String, Object> executeSequence(AiCommandSequenceExecuteRequest request,
                                               AuthorizationContext authorization) {
        requireAssistant(authorization);
        AiCommandSequence sequence = requireExecutableSequence(request);
        List<Map<String, Object>> plan = readSequencePlan(sequence.getPlanJson());
        // 整序列幂等：同键同入参直接回放既有结果，同键不同入参直接冲突，绝不重复建单。
        String idempotencyKey = trimToNull(request.idempotencyKey());
        String currentHash = sequenceHash(sequence, plan);
        Map<String, Object> replayed = replaySequence(sequence, idempotencyKey, currentHash,
                request.startStepNo());
        if (replayed != null) {
            return replayed;
        }
        verifySequenceExecutable(sequence);
        long startedAt = System.currentTimeMillis();
        SequenceRunState state = runSequence(sequence, plan, request, authorization, idempotencyKey, currentHash);
        return finishSequence(sequence, plan, state, startedAt, authorization);
    }

    /** 校验请求并取回待执行的序列。 */
    private AiCommandSequence requireExecutableSequence(AiCommandSequenceExecuteRequest request) {
        if (request == null || !StringUtils.hasText(request.planId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "缺少多步计划ID，请先预览");
        }
        if (sequenceMapper == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "多步命令记录不可用");
        }
        AiCommandSequence sequence = loadSequence(request.planId().trim());
        List<Map<String, Object>> plan = readSequencePlan(sequence.getPlanJson());
        if (plan.isEmpty()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "该多步计划没有可执行的步骤");
        }
        return sequence;
    }

    /** 整序列幂等入参指纹。 */
    private String sequenceHash(AiCommandSequence sequence, List<Map<String, Object>> plan) {
        return hashOf(Map.of("planId", sequence.getSequenceId(), "plan", plan.toString()));
    }

    /** 执行序列时累积的状态。 */
    private static final class SequenceRunState {
        private final List<Map<String, Object>> stepResults = new ArrayList<>();
        private String previousBizNo;
        private String failureReason;
        private int executedSteps;
    }

    /** 按计划逐步骤执行，返回累积状态。 */
    private SequenceRunState runSequence(AiCommandSequence sequence, List<Map<String, Object>> plan,
                                         AiCommandSequenceExecuteRequest request,
                                         AuthorizationContext authorization,
                                         String idempotencyKey, String currentHash) {
        int totalSteps = plan.size();
        int startNo = resolveStartStepNo(sequence, request.startStepNo(), totalSteps);
        mutateSequence(sequence, item -> {
            item.setStatus(STATUS_EXECUTING);
            item.setIdempotencyKey(idempotencyKey);
            item.setRequestHash(currentHash);
            item.setCurrentStepNo(startNo);
        });
        SequenceRunState state = new SequenceRunState();
        for (Map<String, Object> step : plan) {
            if (intValue(step.get("stepNo")) < startNo) {
                // 续跑时把已成功步骤的结果原样带上，方便前端展示完整链路。
                restoreSucceededStep(sequence, step, state);
                continue;
            }
            if (!executeOneStep(sequence, step, request, authorization, state)) {
                break;
            }
        }
        return state;
    }

    /** 续跑时把已成功步骤的结果原样带上。 */
    private void restoreSucceededStep(AiCommandSequence sequence, Map<String, Object> step,
                                      SequenceRunState state) {
        Map<String, Object> done = succeededStepView(sequence.getSequenceId(), intValue(step.get("stepNo")));
        if (done == null) {
            return;
        }
        state.executedSteps++;
        state.previousBizNo = stringValue(done.get("bizNo"));
        state.stepResults.add(done);
    }

    /** 执行一步并并进状态；返回 false 表示该步失败应中断。 */
    private boolean executeOneStep(AiCommandSequence sequence, Map<String, Object> step,
                                   AiCommandSequenceExecuteRequest request,
                                   AuthorizationContext authorization,
                                   SequenceRunState state) {
        Map<String, Object> stepResult = executeSequenceStep(sequence, step, state.previousBizNo,
                authorization, request.remark());
        state.stepResults.add(stepResult);
        if (!Boolean.TRUE.equals(stepResult.get("ok"))) {
            state.failureReason = stringValue(stepResult.get("message"));
            return false;
        }
        state.executedSteps++;
        state.previousBizNo = stringValue(stepResult.get("bizNo"));
        final int succeededSoFar = state.executedSteps;
        mutateSequence(sequence, item -> item.setExecutedSteps(succeededSoFar));
        return true;
    }

    /** 收尾：更新序列终态、写操作日志并组装响应。 */
    private Map<String, Object> finishSequence(AiCommandSequence sequence, List<Map<String, Object>> plan,
                                               SequenceRunState state, long startedAt,
                                               AuthorizationContext authorization) {
        long elapsed = System.currentTimeMillis() - startedAt;
        boolean succeeded = state.failureReason == null && state.executedSteps == plan.size();
        String status = succeeded ? STATUS_SUCCEEDED : SEQUENCE_STATUS_PARTIAL_FAILED;
        closeSequence(sequence, state, succeeded, status, elapsed);
        writeSequenceLog(sequence, authorization.userId(), succeeded
                ? "成功：" + state.executedSteps + "/" + plan.size() + " 步"
                : "部分失败：" + state.executedSteps + "/" + plan.size() + " 步，" + state.failureReason);
        return sequenceRunResponse(sequence, plan, state, status, elapsed);
    }

    /** 落库序列终态。 */
    private void closeSequence(AiCommandSequence sequence, SequenceRunState state,
                               boolean succeeded, String status, long elapsed) {
        final String finalFailure = state.failureReason;
        final int finalExecutedSteps = state.executedSteps;
        mutateSequence(sequence, item -> {
            item.setStatus(status);
            item.setExecutedSteps(finalExecutedSteps);
            item.setElapsedMs(elapsed);
            item.setResultJson(toJson(state.stepResults));
            item.setErrorMessage(truncate(finalFailure, 500));
            if (succeeded) {
                item.setExecutedAt(LocalDateTime.now());
            }
        });
    }

    /** 执行接口返回体。 */
    private Map<String, Object> sequenceRunResponse(AiCommandSequence sequence, List<Map<String, Object>> plan,
                                                    SequenceRunState state, String status, long elapsed) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planId", sequence.getSequenceId());
        response.put("status", status);
        response.put("totalSteps", plan.size());
        response.put("executedSteps", state.executedSteps);
        response.put("steps", state.stepResults);
        response.put("elapsedMs", elapsed);
        response.put("executedAt", LocalDateTime.now().format(ISO));
        if (state.failureReason != null) {
            response.put("errorMessage", state.failureReason);
        }
        return response;
    }

    /**
     * 执行序列里的一步。依赖上一步产出的步骤此时已拿到真实业务单号，先按单号重新预览再执行，
     * 保证每一步都走完整的动作级权限与前置条件校验，不做任何跳过。
     */
    private Map<String, Object> executeSequenceStep(AiCommandSequence sequence, Map<String, Object> step,
                                                    String previousBizNo, AuthorizationContext authorization,
                                                    String remark) {
        int stepNo = intValue(step.get("stepNo"));
        String action = stringValue(step.get("action"));
        String targetNo = resolveStepTargetNo(step, previousBizNo);
        if (!StringUtils.hasText(targetNo)) {
            return sequenceStepResult(stepNo, action, null, false, "该步骤还没有可办理的业务单号", null, null);
        }
        Map<String, Object> params = readJsonMapQuietly(toJson(step.get("params")));
        return attemptSequenceStep(sequence, step, stepNo, action, targetNo, params, authorization, remark);
    }

    /** 单步执行 + 异常兜底，异常也折算成一条步骤结果。 */
    private Map<String, Object> attemptSequenceStep(AiCommandSequence sequence, Map<String, Object> step,
                                                    int stepNo, String action, String targetNo,
                                                    Map<String, Object> params, AuthorizationContext authorization,
                                                    String remark) {
        try {
            return runSequenceStep(sequence, step, stepNo, action, targetNo, params, authorization, remark);
        } catch (BusinessException e) {
            return sequenceStepResult(stepNo, action, targetNo, false, e.getMessage(), null, null);
        } catch (RuntimeException e) {
            return sequenceStepResult(stepNo, action, targetNo, false, "执行失败：" + failureText(e), null, null);
        }
    }

    /** 依赖上一步产出的步骤此时用上一步的真实单号补位。 */
    private String resolveStepTargetNo(Map<String, Object> step, String previousBizNo) {
        String targetNo = stringValue(step.get("targetNo"));
        boolean depends = Boolean.TRUE.equals(step.get("dependsOnPrevious"));
        if (depends && !StringUtils.hasText(targetNo)) {
            return previousBizNo;
        }
        return targetNo;
    }

    /** 单步正式执行：重新预览拿确认凭据，再把执行记录挂到序列上并执行。 */
    private Map<String, Object> runSequenceStep(AiCommandSequence sequence, Map<String, Object> step,
                                                int stepNo, String action, String targetNo,
                                                Map<String, Object> params, AuthorizationContext authorization,
                                                String remark) {
        String approvedConfirmation = stringValue(step.get("confirmationId"));
        if (StringUtils.hasText(approvedConfirmation) && !Boolean.TRUE.equals(step.get("dependsOnPrevious"))) {
            // 对已展示的步骤使用原预览，执行入口会再次核查权限、参数和业务前置条件。
            // 不重新选择产线、计划或其他用户已经确认过的参数。
            Map<String, Object> executed = execute(new AiCommandExecuteRequest(
                    approvedConfirmation, null, action, trimToNull(remark)), authorization);
            return sequenceStepResult(stepNo, action, targetNo, true, stringValue(executed.get("summary")),
                    stringValue(executed.get("bizNo")), longOf(executed.get("commandId")));
        }
        Map<String, Object> preview = previewExplicit(action, targetNo, params,
                new AiCommandPreviewRequest(sequence.getRawText(), sequence.getConversationId(),
                        sequence.getSource(), params), authorization);
        if (!Boolean.TRUE.equals(preview.get("canExecute"))) {
            return sequenceStepResult(stepNo, action, targetNo, false,
                    "前置条件校验未通过：" + blockingReasonOf(preview), null, null);
        }
        Long commandId = commandIdOf(preview);
        bindStepRecord(sequence.getSequenceId(), stepNo, commandId);
        Map<String, Object> executed = execute(new AiCommandExecuteRequest(
                stringValue(preview.get("confirmationId")), null, action, trimToNull(remark)), authorization);
        return sequenceStepResult(stepNo, action, targetNo, true, stringValue(executed.get("summary")),
                stringValue(executed.get("bizNo")), commandId);
    }

    /** 把本步的执行记录挂到序列上。 */
    private void bindStepRecord(String planId, int stepNo, Long commandId) {
        AiCommandExecution record = new AiCommandExecution();
        record.setCommandId(commandId);
        record.setSequenceId(planId);
        record.setSequenceStepNo(stepNo);
        record.setVersion(0);
        executionMapper.updateById(record);
    }

    /** 异常兜底文案：没有 message 时退回异常类型名。 */
    private String failureText(RuntimeException e) {
        if (StringUtils.hasText(e.getMessage())) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    /** 单步执行结果的统一结构。 */
    private Map<String, Object> sequenceStepResult(int stepNo, String action, String targetNo, boolean ok,
                                                   String message, String bizNo, Long commandId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stepNo", stepNo);
        result.put("action", action);
        result.put("targetNo", targetNo);
        result.put("ok", ok);
        result.put("status", ok ? STEP_SUCCEEDED : STEP_FAILED);
        result.put("message", message);
        result.put("bizNo", bizNo);
        result.put("commandId", commandId);
        result.put("bizRef", ok ? targetRouteForAction(action, bizNo) : null);
        return result;
    }

    /** 成功步骤的业务跳转地址：能定位到具体单据的用 focus 参数。 */
    private String targetRouteForAction(String action, String bizNo) {
        if (!StringUtils.hasText(bizNo)) {
            return null;
        }
        return switch (action) {
            case ACTION_RELEASE_WORK_ORDER, ACTION_START_WORK_ORDER -> "/mfg/work-orders?keyword=" + bizNo;
            // 生成领料单产出的是出库单，跳转要落到出库单列表而不是工单列表。
            case ACTION_GENERATE_MATERIAL_ISSUE, ACTION_TRANSFER_TO_LINE_SIDE, ACTION_CONFIRM_OUTBOUND ->
                    "/wms/outbound-orders?keyword=" + bizNo;
            case ACTION_CONFIRM_INBOUND -> "/wms/inbound-orders?keyword=" + bizNo;
            case ACTION_SUBMIT_INSPECTION -> "/qms/inspection-orders?keyword=" + bizNo;
            case ACTION_AUDIT_WORK_REPORT -> "/mfg/work-reports?keyword=" + bizNo;
            case ACTION_RELEASE_DISPATCH -> "/mfg/dispatch-orders?keyword=" + bizNo;
            case ACTION_START_TASK -> "/mfg/tasks?keyword=" + bizNo;
            case ACTION_SUBMIT_WORK_REPORT -> "/mfg/work-reports?keyword=" + bizNo;
            case ACTION_GENERATE_PRODUCTION_INBOUND -> "/wms/inbound-orders?keyword=" + bizNo;
            case ACTION_GENERATE_SALES_OUTBOUND, ACTION_CREATE_OUTGOING_INSPECTION,
                    ACTION_CONFIRM_SALES_OUTBOUND -> "/wms/sales-outbound?keyword=" + bizNo;
            default -> null;
        };
    }

    /** 续跑时回读已成功步骤的真实结果，保证响应里能看到完整链路。 */
    private Map<String, Object> succeededStepView(String planId, int stepNo) {
        if (executionMapper == null) {
            return null;
        }
        AiCommandExecution record = executionMapper.selectOne(new QueryWrapper<AiCommandExecution>()
                .eq("sequence_id", planId).eq("sequence_step_no", stepNo).eq("deleted_flag", 0)
                .orderByDesc("command_id").last("limit 1"));
        if (record == null || !STATUS_SUCCEEDED.equals(record.getStatus())) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stepNo", stepNo);
        result.put("action", record.getAction());
        result.put("targetNo", record.getTargetNo());
        result.put("ok", true);
        result.put("status", STEP_SUCCEEDED);
        result.put("message", "该步骤此前已执行成功");
        result.put("bizNo", resultBizNo(record));
        result.put("commandId", record.getCommandId());
        result.put("bizRef", targetRouteForAction(record.getAction(), resultBizNo(record)));
        return result;
    }

    /** 从结果 JSON 里取业务单号，取不到退回目标单号。 */
    private String resultBizNo(AiCommandExecution record) {
        Map<String, Object> result = readJsonMapQuietly(record.getResultJson());
        if (result != null && result.get("bizNo") != null) {
            return String.valueOf(result.get("bizNo"));
        }
        return record.getTargetNo();
    }

    @Override
    public Map<String, Object> getSequence(String planId) {
        if (!StringUtils.hasText(planId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "多步计划ID不能为空");
        }
        if (sequenceMapper == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "多步命令记录不可用");
        }
        AiCommandSequence sequence = loadSequence(planId.trim());
        List<Map<String, Object>> plan = readSequencePlan(sequence.getPlanJson());
        Map<Integer, Map<String, Object>> executed = executedSteps(sequence.getResultJson());
        List<Map<String, Object>> steps = new ArrayList<>();
        boolean overallCanExecute = true;
        for (Map<String, Object> step : plan) {
            Map<String, Object> view = sequenceStepView(step, executed.get(intValue(step.get("stepNo"))));
            if (Boolean.FALSE.equals(step.get("canExecute"))) {
                overallCanExecute = false;
            }
            steps.add(view);
        }
        return sequenceDetailView(sequence, steps, overallCanExecute);
    }

    /** 把执行结果按步骤号索引。 */
    private Map<Integer, Map<String, Object>> executedSteps(String resultJson) {
        Map<Integer, Map<String, Object>> executed = new LinkedHashMap<>();
        for (Map<String, Object> row : readSequenceResults(resultJson)) {
            executed.put(intValue(row.get("stepNo")), row);
        }
        return executed;
    }

    /** 序列详情的单个步骤视图：执行过的取执行结果，没执行的取计划值。 */
    private Map<String, Object> sequenceStepView(Map<String, Object> step, Map<String, Object> row) {
        int stepNo = intValue(step.get("stepNo"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stepNo", stepNo);
        view.put("action", step.get("action"));
        view.put("actionLabel", step.get("actionLabel"));
        view.put("targetType", step.get("targetType"));
        view.put("targetNo", rowValue(row, step, "targetNo"));
        view.put("params", step.get("params"));
        view.put("dependsOnPrevious", step.get("dependsOnPrevious"));
        view.put("canExecute", step.get("canExecute"));
        view.put("blockingReason", step.get("blockingReason"));
        view.put("status", rowOrNull(row, "status", STEP_PENDING));
        view.put("message", rowOrNull(row, "message", null));
        view.put("bizNo", rowOrNull(row, "bizNo", null));
        view.put("commandId", rowOrNull(row, "commandId", null));
        view.put("bizRef", rowOrNull(row, "bizRef", null));
        return view;
    }

    /** 有执行结果行就从行里取，否则用给定的默认值。 */
    private Object rowOrNull(Map<String, Object> row, String key, Object fallback) {
        if (row == null) {
            return fallback;
        }
        return row.get(key);
    }

    /** 有执行结果时优先取执行结果，否则退回计划里的值。 */
    private Object rowValue(Map<String, Object> row, Map<String, Object> step, String key) {
        if (row == null) {
            return step.get(key);
        }
        return row.get(key);
    }

    /** 序列详情返回体。 */
    private Map<String, Object> sequenceDetailView(AiCommandSequence sequence, List<Map<String, Object>> steps,
                                                   boolean overallCanExecute) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", sequence.getSequenceId());
        data.put("rawText", sequence.getRawText());
        data.put("status", sequence.getStatus());
        data.put("totalSteps", sequence.getTotalSteps());
        data.put("executedSteps", sequence.getExecutedSteps());
        data.put("currentStepNo", sequence.getCurrentStepNo());
        data.put("overallCanExecute", overallCanExecute);
        data.put("steps", steps);
        data.put("errorMessage", sequence.getErrorMessage());
        data.put("elapsedMs", sequence.getElapsedMs());
        data.put("expiresAt", isoOrNull(sequence.getExpiresAt()));
        data.put("executedAt", isoOrNull(sequence.getExecutedAt()));
        return data;
    }

    /** 空时间返回 null，否则格式化。 */
    private String isoOrNull(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.format(ISO);
    }

    /** 按 planId 取序列记录，取不到直接报失效。 */
    private AiCommandSequence loadSequence(String planId) {
        AiCommandSequence sequence = sequenceMapper.selectOne(new QueryWrapper<AiCommandSequence>()
                .eq("sequence_id", planId).eq("deleted_flag", 0).last("limit 1"));
        if (sequence == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "多步计划不存在或已失效，请重新预览");
        }
        return sequence;
    }

    /**
     * 整序列幂等回放。只有已成功的序列才直接回放；部分失败的序列在显式给出续跑步骤时允许继续，
     * 否则返回既有结果并提示从失败步骤继续，避免「点一次又从头办一遍」。
     */
    private Map<String, Object> replaySequence(AiCommandSequence sequence, String idempotencyKey,
                                               String currentHash, Integer startStepNo) {
        AiCommandSequence replay = idempotencyKey == null ? null : findReplaySequence(idempotencyKey);
        if (replay == null) {
            return null;
        }
        if (sequence.getSequenceId().equals(replay.getSequenceId())) {
            // 同一个序列自己重放：已全部成功的直接回放既有结果，绝不重跑；
            // 部分失败的放行，由 resolveStartStepNo 自动跳过已成功的步骤接着办。
            return replayedIfSucceeded(replay);
        }
        requireSamePlan(currentHash, replay);
        return replayView(replay, startStepNo);
    }

    /** 同一幂等键不能指向不同计划。 */
    private void requireSamePlan(String currentHash, AiCommandSequence replay) {
        if (!currentHash.equals(replay.getRequestHash())) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(),
                    "同一幂等键已用于不同的多步计划，不能复用");
        }
    }

    /** 跨计划复用的幂等键：已成功的直接回放；部分失败且给了续跑步骤号则放行继续执行。 */
    private Map<String, Object> replayView(AiCommandSequence replay, Integer startStepNo) {
        if (STATUS_SUCCEEDED.equals(replay.getStatus())) {
            return sequenceResultView(replay, true);
        }
        if (SEQUENCE_STATUS_PARTIAL_FAILED.equals(replay.getStatus()) && startStepNo != null) {
            return null;
        }
        Map<String, Object> view = sequenceResultView(replay, true);
        view.put("message", "该多步计划此前部分失败，可用 startStepNo 从失败步骤继续");
        return view;
    }

    /** 按幂等键取既有序列。 */
    private AiCommandSequence findReplaySequence(String idempotencyKey) {
        return sequenceMapper.selectOne(new QueryWrapper<AiCommandSequence>()
                .eq("idempotency_key", idempotencyKey).eq("deleted_flag", 0)
                .orderByAsc("sequence_id").last("limit 1"));
    }

    /** 已成功才回放，否则返回 null 让调用方继续执行。 */
    private Map<String, Object> replayedIfSucceeded(AiCommandSequence replay) {
        if (STATUS_SUCCEEDED.equals(replay.getStatus())) {
            return sequenceResultView(replay, true);
        }
        return null;
    }

    /** 序列执行结果的回放视图。 */
    private Map<String, Object> sequenceResultView(AiCommandSequence sequence, boolean replayed) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", sequence.getSequenceId());
        view.put("status", sequence.getStatus());
        view.put("totalSteps", sequence.getTotalSteps());
        view.put("executedSteps", sequence.getExecutedSteps());
        view.put("steps", readSequenceResults(sequence.getResultJson()));
        view.put("elapsedMs", sequence.getElapsedMs());
        view.put("executedAt", sequence.getExecutedAt() == null ? null : sequence.getExecutedAt().format(ISO));
        view.put("replayed", replayed);
        if (StringUtils.hasText(sequence.getErrorMessage())) {
            view.put("errorMessage", sequence.getErrorMessage());
        }
        return view;
    }

    /** 序列状态与有效期校验：过期置 EXPIRED；已成功的序列不再允许重复执行。 */
    private void verifySequenceExecutable(AiCommandSequence sequence) {
        if (STATUS_SUCCEEDED.equals(sequence.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "该多步计划已全部执行成功，不能重复执行");
        }
        if (STATUS_EXPIRED.equals(sequence.getStatus()) || STATUS_REJECTED.equals(sequence.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "该多步计划当前状态为 " + sequence.getStatus() + "，请重新预览");
        }
        if (sequence.getExpiresAt() != null && sequence.getExpiresAt().isBefore(LocalDateTime.now())) {
            mutateSequence(sequence, item -> item.setStatus(STATUS_EXPIRED));
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "多步预览已超过 5 分钟有效期，请重新预览");
        }
    }

    /**
     * 决定从第几步开始。用户显式给了 startStepNo 就用它；否则自动跳过已成功的步骤，
     * 这样「失败后点继续」不需要用户自己数第几步。
     */
    private int resolveStartStepNo(AiCommandSequence sequence, Integer startStepNo, int totalSteps) {
        if (startStepNo != null && startStepNo >= 1) {
            return Math.min(startStepNo, totalSteps);
        }
        return resumeStepNo(sequence, totalSteps);
    }

    /** 自动续跑：部分失败时挑第一个还没成功的步骤。 */
    private int resumeStepNo(AiCommandSequence sequence, int totalSteps) {
        if (!SEQUENCE_STATUS_PARTIAL_FAILED.equals(sequence.getStatus())) {
            return 1;
        }
        return firstUnsucceededStep(sequence.getSequenceId(), totalSteps);
    }

    /** 顺序探测第一个没有成功记录的步骤，全都成功则退回最后一步。 */
    private int firstUnsucceededStep(String planId, int totalSteps) {
        int candidate = 1;
        while (candidate <= totalSteps && succeededStepView(planId, candidate) != null) {
            candidate++;
        }
        return candidate > totalSteps ? totalSteps : candidate;
    }

    /**
     * 序列状态更新走独立事务，并在更新后重新读回最新 version。
     *
     * <p>乐观锁插件在 updateById 成功后会把实体 version 自增，但同一请求里如果还要再更新一次，
     * 手里这份实体的 version 就可能是旧的，因此每次更新后都重新 selectById 拿最新值。</p>
     */
    private void mutateSequence(AiCommandSequence sequence, java.util.function.Consumer<AiCommandSequence> action) {
        action.accept(sequence);
        if (sequence.getVersion() == null) {
            sequence.setVersion(0);
        }
        inNewTransaction(() -> sequenceMapper.updateById(sequence));
        AiCommandSequence latest = sequenceMapper.selectById(sequence.getSequenceId());
        if (latest != null) {
            sequence.setVersion(latest.getVersion());
            sequence.setStatus(latest.getStatus());
            sequence.setExecutedSteps(latest.getExecutedSteps());
        }
    }

    /** 序列执行也写一条操作日志，便于在监控页按动作名反查。 */
    private void writeSequenceLog(AiCommandSequence sequence, Long userId, String summary) {
        if (operationLogMapper == null) {
            return;
        }
        SysOperationLog log = new SysOperationLog();
        log.setUserId(userId);
        log.setOperationType("AI_COMMAND_SEQUENCE");
        log.setModuleCode("ai_agent");
        log.setBizId((long) (sequence.getSequenceId().hashCode() & Integer.MAX_VALUE));
        log.setOperationTime(LocalDateTime.now());
        log.setCreatedBy(userId);
        log.setCreatedTime(LocalDateTime.now());
        log.setRemark(trimToNull(("多步命令[" + sequence.getRawText() + "] " + summary)));
        log.setDeletedFlag(0);
        operationLogMapper.insert(log);
    }

    /** 读取序列计划里的步骤定义，JSON 损坏时返回空列表。 */
    private List<Map<String, Object>> readSequencePlan(String json) {
        return readJsonList(json);
    }

    /** 读取序列结果里的步骤结果，JSON 损坏时返回空列表。 */
    private List<Map<String, Object>> readSequenceResults(String json) {
        return readJsonList(json);
    }

    /** 宽松地把 JSON 文本读成 Map 列表。 */
    private List<Map<String, Object>> readJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return mapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 序列里一个步骤的完整定义。 */
    private record SequenceStepSpec(int stepNo, String action, String actionLabel, String targetType,
                                    String targetNo, Map<String, Object> params, boolean dependsOnPrevious) {
    }

    /* ==================== 查询与监控 ==================== */

    @Override
    public Page<AiCommandExecution> pageCommands(Integer current, Integer size, String status, String action,
                                                 Long userId, String keyword, LocalDateTime beginTime,
                                                 LocalDateTime endTime) {
        Page<AiCommandExecution> page = new Page<>(pageNoOf(current), pageSizeOf(size));
        QueryWrapper<AiCommandExecution> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(action)) {
            wrapper.eq("action", action.trim().toUpperCase(Locale.ROOT));
        }
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            wrapper.and(query -> query.like("target_no", value)
                    .or().like("raw_text", value)
                    .or().like("action_label", value)
                    .or().like("confirmation_id", value));
        }
        if (beginTime != null) {
            wrapper.ge("created_time", beginTime);
        }
        if (endTime != null) {
            wrapper.le("created_time", endTime);
        }
        wrapper.orderByDesc("command_id");
        if (executionMapper == null) {
            return page;
        }
        return executionMapper.selectPage(page, wrapper);
    }

    /** 页码归一：空值或非正数都按第 1 页处理。 */
    private static int pageNoOf(Integer current) {
        return current == null || current < 1 ? 1 : current;
    }

    /** 每页条数归一：空值或非正数按 20 条，超过 200 条按 200 条封顶。 */
    private static int pageSizeOf(Integer size) {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 200);
    }

    @Override
    public Map<String, Object> getCommandDetail(Long commandId) {
        if (executionMapper == null || commandId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "命令ID不能为空");
        }
        AiCommandExecution command = executionMapper.selectOne(new QueryWrapper<AiCommandExecution>()
                .eq("command_id", commandId).eq("deleted_flag", 0));
        if (command == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "命令记录不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("command", command);
        detail.put("steps", listSteps(commandId));
        return detail;
    }

    @Override
    public Map<String, Object> stats() {
        List<AiCommandExecution> all = loadAllCommands();
        // 只把真正进入执行阶段的记录计入统计口径；PREVIEW/CONFIRMED 还只是预览，
        // 混进来会让成功率被大量未执行预览稀释成个位数，监控面板失真。
        List<AiCommandExecution> executed = all.stream().filter(this::isExecuted).toList();
        long total = executed.size();
        long previewCount = all.stream().filter(this::isPendingPreview).count();
        long succeeded = count(executed, STATUS_SUCCEEDED);
        long failed = count(executed, STATUS_FAILED);
        long rejected = count(executed, STATUS_REJECTED);
        long expired = count(executed, STATUS_EXPIRED);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("succeeded", succeeded);
        stats.put("failed", failed);
        stats.put("rejected", rejected);
        stats.put("expired", expired);
        stats.put("successRate", successRateOf(succeeded, total));
        stats.put("avgElapsedMs", Math.round(avgElapsedOf(executed) * 10D) / 10D);
        stats.put("byAction", buildByAction(executed));
        stats.put("byStatus", buildByStatus(all));
        stats.put("recent24h", buildRecent24h(executed));
        stats.put("topTargets", buildTopTargets(all));
        // 预览态数量单独给出，监控面板可以显示「另有 N 条待确认预览」，
        // 但成功率只按已执行记录计算。
        stats.put("previewCount", previewCount);
        stats.put("previewing", previewCount);
        return stats;
    }

    /** 读取全部未删除的命令记录，按主键倒序。 */
    private List<AiCommandExecution> loadAllCommands() {
        if (executionMapper == null) {
            return List.of();
        }
        return executionMapper.selectList(new QueryWrapper<AiCommandExecution>()
                .eq("deleted_flag", 0)
                .orderByDesc("command_id"));
    }

    /** 记录是否已进入终态，计入成功率统计。 */
    private boolean isExecuted(AiCommandExecution item) {
        return TERMINAL_STATUSES.contains(nullToDash(item.getStatus()));
    }

    /** 记录是否仍停留在预览或待确认状态。 */
    private boolean isPendingPreview(AiCommandExecution item) {
        String status = nullToDash(item.getStatus());
        return STATUS_PREVIEW.equals(status) || STATUS_CONFIRMED.equals(status);
    }

    /** 成功率，保留一位小数。 */
    private static double successRateOf(long succeeded, long total) {
        return total == 0 ? 0D : Math.round(succeeded * 1000D / total) / 10D;
    }

    /** 已执行记录的平均耗时。 */
    private static double avgElapsedOf(List<AiCommandExecution> executed) {
        return executed.stream()
                .filter(item -> item.getElapsedMs() != null)
                .mapToLong(AiCommandExecution::getElapsedMs)
                .average().orElse(0D);
    }

    /** 按状态汇总全部记录条数。 */
    private static Map<String, Long> buildByStatus(List<AiCommandExecution> all) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (AiCommandExecution item : all) {
            byStatus.merge(nullToDash(item.getStatus()), 1L, Long::sum);
        }
        return byStatus;
    }

    /** 按动作分组统计已执行记录，未出现过的动作不输出。 */
    private List<Map<String, Object>> buildByAction(List<AiCommandExecution> executed) {
        List<Map<String, Object>> byAction = new ArrayList<>();
        for (ActionSpec spec : SPECS) {
            List<AiCommandExecution> group = executed.stream()
                    .filter(item -> spec.action().equals(item.getAction()))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            byAction.add(actionRow(spec, group));
        }
        return byAction;
    }

    /** 单个动作的统计行。 */
    private static Map<String, Object> actionRow(ActionSpec spec, List<AiCommandExecution> group) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("action", spec.action());
        row.put("actionLabel", spec.label());
        row.put("total", (long) group.size());
        row.put("succeeded", group.stream().filter(i -> STATUS_SUCCEEDED.equals(i.getStatus())).count());
        row.put("failed", group.stream().filter(i -> STATUS_FAILED.equals(i.getStatus())).count());
        return row;
    }

    /** 最近 24 小时按整点分桶的执行情况，始终返回 24 个桶。 */
    private List<Map<String, Object>> buildRecent24h(List<AiCommandExecution> executed) {
        LocalDateTime startHour = LocalDateTime.now().minusHours(23).withMinute(0).withSecond(0).withNano(0);
        List<Map<String, Object>> recent24h = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            recent24h.add(emptyHourPoint(startHour.plusHours(i)));
        }
        for (AiCommandExecution item : executed) {
            int index = hourIndexOf(startHour, item.getCreatedTime());
            if (index < 0) {
                continue;
            }
            accumulateHourPoint(recent24h.get(index), item);
        }
        return recent24h;
    }

    /** 一个空的小时桶。 */
    private static Map<String, Object> emptyHourPoint(LocalDateTime hour) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("hour", hour.format(HOUR_LABEL));
        point.put("total", 0L);
        point.put("succeeded", 0L);
        point.put("failed", 0L);
        return point;
    }

    /** 记录落在 24 小时窗口内的第几个桶，窗口外返回 -1。 */
    private static int hourIndexOf(LocalDateTime startHour, LocalDateTime createdTime) {
        if (createdTime == null || createdTime.isBefore(startHour)) {
            return -1;
        }
        long index = Duration.between(startHour, createdTime.withMinute(0).withSecond(0).withNano(0)).toHours();
        return index < 0 || index >= 24 ? -1 : (int) index;
    }

    /** 把一条记录累加进对应小时桶。 */
    private static void accumulateHourPoint(Map<String, Object> point, AiCommandExecution item) {
        point.put("total", (Long) point.get("total") + 1);
        if (STATUS_SUCCEEDED.equals(item.getStatus())) {
            point.put("succeeded", (Long) point.get("succeeded") + 1);
        }
        if (STATUS_FAILED.equals(item.getStatus())) {
            point.put("failed", (Long) point.get("failed") + 1);
        }
    }

    /** 被操作最多的前 5 个目标单据。 */
    private static List<Map<String, Object>> buildTopTargets(List<AiCommandExecution> all) {
        Map<String, long[]> targetCounter = new LinkedHashMap<>();
        for (AiCommandExecution item : all) {
            if (!StringUtils.hasText(item.getTargetNo())) {
                continue;
            }
            String key = nullToDash(item.getTargetType()) + "|" + item.getTargetNo();
            targetCounter.computeIfAbsent(key, k -> new long[]{0L})[0]++;
        }
        return targetCounter.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(5)
                .map(AiCommandServiceImpl::topTargetRow)
                .toList();
    }

    /** 单个目标单据的统计行。 */
    private static Map<String, Object> topTargetRow(Map.Entry<String, long[]> entry) {
        String[] parts = entry.getKey().split("\\|", 2);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("targetType", parts[0]);
        row.put("targetNo", parts.length > 1 ? parts[1] : null);
        row.put("count", entry.getValue()[0]);
        return row;
    }

    @Override
    public List<Map<String, Object>> capabilities() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ActionSpec spec : SPECS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", spec.action());
            row.put("actionLabel", spec.label());
            row.put("description", spec.description());
            row.put("targetType", spec.targetType());
            row.put("permission", spec.permission());
            row.put("example", spec.example());
            list.add(row);
        }
        return list;
    }

    /* ==================== 候选单据与批量办理 ==================== */

    @Override
    public Map<String, Object> candidates(String capability, Map<String, Object> params,
                                          AuthorizationContext authorization) {
        requireAssistant(authorization);
        ActionSpec spec = resolveSpec(capability);
        requirePermission(authorization, spec.permission());
        requireGenerationReadPermissions(spec.action(), authorization);

        List<Target> targets = listCandidateTargets(spec.action(), candidateScope(params));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Target target : targets) {
            candidates.add(candidateView(spec, target, params));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capability", spec.action());
        data.put("actionLabel", spec.label());
        data.put("candidates", candidates);
        data.put("total", candidates.size());
        return data;
    }

    /**
     * 把一张候选单据转成前端可展示的条目：带上真实前置条件结论与阻断原因，
     * 让用户先看清哪几张能办、哪几张被卡住，而不是盲点批量确认。
     */
    private Map<String, Object> candidateView(ActionSpec spec, Target target, Map<String, Object> params) {
        List<Map<String, Object>> impacts = new ArrayList<>();
        List<Map<String, Object>> preconditions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> evaluateParams = new LinkedHashMap<>();
        if (params != null) {
            evaluateParams.putAll(params);
        }
        evaluateParams.put("action", spec.action());
        bindTargetIds(target,evaluateParams);
        if (ACTION_GENERATE_DISPATCH.equals(spec.action())) prepareDispatch(target, evaluateParams, "");
        if (ACTION_GENERATE_WORK_ORDER.equals(spec.action())) prepareGeneration(target,evaluateParams);
        fillActionParams(spec.action(),"",evaluateParams);
        evaluate(spec, target, evaluateParams, impacts, preconditions, warnings);

        boolean canExecute = preconditions.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bizNo", target.no());
        row.put("bizType", spec.targetType());
        row.put("bizId", target.id());
        row.put("label", spec.label() + " " + nullToDash(target.no()));
        row.put("detail", firstFailedDetail(preconditions, canExecute));
        row.put("status", entityStatus(target.entity()));
        row.put("canExecute", canExecute);
        row.put("blockingReason", canExecute ? null : firstFailedDetail(preconditions, false));
        row.put("preconditions", preconditions);
        return row;
    }

    /** 取第一条未通过的前置条件说明，作为阻断原因。 */
    private static String firstFailedDetail(List<Map<String, Object>> preconditions, boolean canExecute) {
        if (canExecute) {
            return "前置检查全部通过";
        }
        for (Map<String, Object> item : preconditions) {
            if (!Boolean.TRUE.equals(item.get("passed"))) {
                Object detail = item.get("detail");
                return detail == null ? "前置检查未通过" : detail.toString();
            }
        }
        return "前置检查未通过";
    }

    /** 目标实体的当前状态文本，用于候选列表展示。 */
    private static String entityStatus(Object entity) {
        return switch (entity) {
            case MfgWorkOrder order -> order.getStatus();
            case WmsOutboundOrder outbound -> outbound.getAuditStatus();
            case WmsInboundOrder inbound -> inbound.getAuditStatus();
            case QmsInspectionOrder inspection -> inspection.getStatus();
            case MfgWorkReport report -> report.getAuditStatus();
            case null, default -> null;
        };
    }

    /**
     * 按动作列出当前处于可办理状态的候选单据。查询条件只放「状态允许」这一层，
     * 更细的前置条件（时间窗、齐套、库位类型）交给 evaluate 逐张判。
     */
    /** 先限定本订单的真实单据，再分页；空范围不退回全库。 */
    private List<String> candidateScope(Map<String,Object> params) {
        if(params==null || !params.containsKey("targetNos")) return null;
        if(!(params.get("targetNos") instanceof List<?> values) || values.size()>1000)
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),"待办单据范围无效，请重新读取当前订单");
        return values.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(String::trim).filter(StringUtils::hasText).distinct().toList();
    }

    private List<Target> listCandidateTargets(String action, List<String> scope) {
        if(scope!=null && scope.isEmpty()) return List.of();
        return switch (action) {
            case ACTION_RELEASE_WORK_ORDER -> workOrderCandidates(
                    List.of(BusinessStatus.Document.DRAFT, BusinessStatus.Document.APPROVED),scope);
            case ACTION_START_WORK_ORDER -> workOrderCandidates(List.of(BusinessStatus.Document.RELEASED),scope);
            case ACTION_GENERATE_DISPATCH -> workOrderCandidates(List.of(BusinessStatus.Document.RELEASED),scope);
            case ACTION_GENERATE_MATERIAL_ISSUE -> workOrderCandidates(
                    List.of(BusinessStatus.Document.RELEASED, BusinessStatus.Production.RUNNING),scope);
            case ACTION_GENERATE_WORK_ORDER -> finishedProductionOrderCandidates(scope);
            case ACTION_TRANSFER_TO_LINE_SIDE -> outboundCandidates(true,scope);
            case ACTION_CONFIRM_OUTBOUND -> outboundCandidates(false,scope);
            case ACTION_CONFIRM_INBOUND -> inboundCandidates(scope);
            case ACTION_SUBMIT_INSPECTION -> inspectionCandidates(scope);
            case ACTION_AUDIT_WORK_REPORT -> workReportCandidates(scope);
            case ACTION_GENERATE_PRODUCTION_INBOUND -> releasedProductLotCandidates(scope);
            case ACTION_GENERATE_SALES_OUTBOUND -> finishedProductionOrderCandidates(scope);
            case ACTION_CREATE_OUTGOING_INSPECTION, ACTION_CONFIRM_SALES_OUTBOUND -> salesOutboundCandidates(scope);
            case ACTION_RELEASE_DISPATCH -> waitDispatchCandidates(scope);
            case ACTION_START_TASK -> startableTaskCandidates(scope);
            case ACTION_SUBMIT_WORK_REPORT -> reportableTaskCandidates(scope);
            default -> List.of();
        };
    }

    /**
     * 成品入库候选：终检合格/让步放行、且还没生成入库单的产品批次。
     *
     * <p>已生成待确认入库单的批次仍列为候选，因为领域服务对同批次会直接复用那张单据，
     * 用户重复点不会造出第二张。</p>
     */
    private List<Target> releasedProductLotCandidates(List<String> scope) {
        if (productLotMapper == null) {
            return List.of();
        }
        List<com.neuedu.ems.entity.trace.TraceProductLot> lots = productLotMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.trace.TraceProductLot>()
                        .in(com.neuedu.ems.entity.trace.TraceProductLot::getStatus,
                                List.of(BusinessStatus.Quality.PASS, BusinessStatus.Quality.CONCESSION))
                        .in(scope != null, com.neuedu.ems.entity.trace.TraceProductLot::getLotNo, scope)
                        .eq(com.neuedu.ems.entity.trace.TraceProductLot::getDeletedFlag, 0)
                        .orderByAsc(com.neuedu.ems.entity.trace.TraceProductLot::getProductLotId)
                        .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (com.neuedu.ems.entity.trace.TraceProductLot lot : lots) {
            targets.add(new Target(TARGET_PRODUCT_LOT, lot.getLotNo(), lot.getProductLotId(), lot));
        }
        return targets;
    }

    /** 发货候选：已下达/生产中/已完成的订单都列出，能不能发以预览校验为准。 */
    private List<Target> finishedProductionOrderCandidates(List<String> scope) {
        if (aiProductionOrderMapper == null) {
            return List.of();
        }
        List<com.neuedu.ems.entity.mfg.MfgProductionOrder> orders = aiProductionOrderMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgProductionOrder>()
                        .in(com.neuedu.ems.entity.mfg.MfgProductionOrder::getStatus,
                                List.of(BusinessStatus.Document.RELEASED, BusinessStatus.Production.RUNNING,
                                        BusinessStatus.Production.FINISHED))
                        .in(scope != null, com.neuedu.ems.entity.mfg.MfgProductionOrder::getProductionOrderNo, scope)
                        .eq(com.neuedu.ems.entity.mfg.MfgProductionOrder::getDeletedFlag, 0)
                        .orderByAsc(com.neuedu.ems.entity.mfg.MfgProductionOrder::getProductionOrderId)
                        .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (com.neuedu.ems.entity.mfg.MfgProductionOrder order : orders) {
            targets.add(new Target(TARGET_PRODUCTION_ORDER, order.getProductionOrderNo(),
                    order.getProductionOrderId(), order));
        }
        return targets;
    }

    private List<Target> startableTaskCandidates(List<String> scope) {
        if (taskMapper == null) return List.of();
        List<MfgTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getStatus, BusinessStatus.Production.WAIT_START)
                .in(scope != null, MfgTask::getTaskNo, scope)
                        .eq(MfgTask::getDeletedFlag, 0).orderByAsc(MfgTask::getTaskId)
                .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (MfgTask task : tasks) targets.add(new Target(TARGET_TASK, task.getTaskNo(), task.getTaskId(), task));
        return targets;
    }

    /** 可报工的工序任务：已开工、且还没报满。 */
    private List<Target> reportableTaskCandidates(List<String> scope) {
        if (taskMapper == null) {
            return List.of();
        }
        List<MfgTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<MfgTask>()
                .in(MfgTask::getStatus, List.of("RUNNING", "WAIT_START", "STARTED"))
                .in(scope != null, MfgTask::getTaskNo, scope)
                        .eq(MfgTask::getDeletedFlag, 0)
                .orderByAsc(MfgTask::getTaskId)
                .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (MfgTask task : tasks) {
            targets.add(new Target(TARGET_TASK, task.getTaskNo(), task.getTaskId(), task));
        }
        return targets;
    }

    /** 待下达的派工单：已生成但还没下发，下发后才会产生工序任务。 */
    private List<Target> waitDispatchCandidates(List<String> scope) {
        if (aiDispatchMapper == null) {
            return List.of();
        }
        List<com.neuedu.ems.entity.mfg.MfgDispatchOrder> orders = aiDispatchMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgDispatchOrder>()
                        .eq(com.neuedu.ems.entity.mfg.MfgDispatchOrder::getStatus, "WAIT_DISPATCH")
                        .in(scope != null, com.neuedu.ems.entity.mfg.MfgDispatchOrder::getDispatchNo, scope)
                        .eq(com.neuedu.ems.entity.mfg.MfgDispatchOrder::getDeletedFlag, 0)
                        .orderByAsc(com.neuedu.ems.entity.mfg.MfgDispatchOrder::getDispatchId)
                        .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (com.neuedu.ems.entity.mfg.MfgDispatchOrder dispatch : orders) {
            targets.add(new Target(TARGET_DISPATCH, dispatch.getDispatchNo(),
                    dispatch.getDispatchId(), dispatch));
        }
        return targets;
    }

    /** 销售出库候选：待确认的 SALE 类型出库单。 */
    private List<Target> salesOutboundCandidates(List<String> scope) {
        if (outboundOrderMapper == null) {
            return List.of();
        }
        List<WmsOutboundOrder> orders = outboundOrderMapper.selectList(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getAuditStatus, BusinessStatus.Document.SUBMITTED)
                .eq(WmsOutboundOrder::getOutboundType, "SALE")
                .in(scope != null, WmsOutboundOrder::getOutboundNo, scope)
                        .eq(WmsOutboundOrder::getDeletedFlag, 0)
                .orderByAsc(WmsOutboundOrder::getOutboundId)
                .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (WmsOutboundOrder order : orders) {
            targets.add(new Target(TARGET_OUTBOUND, order.getOutboundNo(), order.getOutboundId(), order));
        }
        return targets;
    }

    /** 按状态取工单候选。 */
    private List<Target> workOrderCandidates(List<String> statuses, List<String> scope) {
        if (workOrderMapper == null) {
            return List.of();
        }
        List<MfgWorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<MfgWorkOrder>()
                .in(MfgWorkOrder::getStatus, statuses)
                .in(scope != null, MfgWorkOrder::getWorkOrderNo, scope)
                        .eq(MfgWorkOrder::getDeletedFlag, 0)
                .orderByAsc(MfgWorkOrder::getWorkOrderId)
                .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (MfgWorkOrder order : orders) {
            targets.add(new Target(TARGET_WORK_ORDER, order.getWorkOrderNo(), order.getWorkOrderId(), order));
        }
        return targets;
    }

    /**
     * 取出库单候选。转线边只取领料类，确认出库取所有已提交且非销售出库的单据。
     */
    private List<Target> outboundCandidates(boolean materialIssueOnly, List<String> scope) {
        if (outboundOrderMapper == null) {
            return List.of();
        }
        LambdaQueryWrapper<WmsOutboundOrder> wrapper = new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getAuditStatus, BusinessStatus.Document.SUBMITTED)
                .in(scope != null, WmsOutboundOrder::getOutboundNo, scope)
                        .eq(WmsOutboundOrder::getDeletedFlag, 0)
                .orderByAsc(WmsOutboundOrder::getOutboundId)
                .last("limit " + CANDIDATE_LIMIT);
        if (materialIssueOnly) {
            wrapper.eq(WmsOutboundOrder::getOutboundType, "MATERIAL_ISSUE");
        }
        List<WmsOutboundOrder> orders = outboundOrderMapper.selectList(wrapper);
        List<Target> targets = new ArrayList<>();
        for (WmsOutboundOrder order : orders) {
            targets.add(new Target(TARGET_OUTBOUND, order.getOutboundNo(), order.getOutboundId(), order));
        }
        return targets;
    }

    /** 取已提交待确认的入库单候选。 */
    private List<Target> inboundCandidates(List<String> scope) {
        if (inboundOrderMapper == null) {
            return List.of();
        }
        List<WmsInboundOrder> orders = inboundOrderMapper.selectList(new LambdaQueryWrapper<WmsInboundOrder>()
                .eq(WmsInboundOrder::getAuditStatus, BusinessStatus.Document.SUBMITTED)
                .in(scope != null, WmsInboundOrder::getInboundNo, scope)
                        .eq(WmsInboundOrder::getDeletedFlag, 0)
                .orderByAsc(WmsInboundOrder::getInboundId)
                .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (WmsInboundOrder order : orders) {
            targets.add(new Target(TARGET_INBOUND, order.getInboundNo(), order.getInboundId(), order));
        }
        return targets;
    }

    /** 取草稿状态的检验单候选。 */
    private List<Target> inspectionCandidates(List<String> scope) {
        if (inspectionOrderMapper == null) {
            return List.of();
        }
        List<QmsInspectionOrder> orders = inspectionOrderMapper.selectList(
                new LambdaQueryWrapper<QmsInspectionOrder>()
                        .eq(QmsInspectionOrder::getStatus, BusinessStatus.Document.DRAFT)
                        .in(scope != null, QmsInspectionOrder::getInspectionNo, scope)
                        .eq(QmsInspectionOrder::getDeletedFlag, 0)
                        .orderByAsc(QmsInspectionOrder::getInspectionId)
                        .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (QmsInspectionOrder order : orders) {
            targets.add(new Target(TARGET_INSPECTION, order.getInspectionNo(), order.getInspectionId(), order));
        }
        return targets;
    }

    /** 取已提交待审核的报工单候选。 */
    private List<Target> workReportCandidates(List<String> scope) {
        if (workReportMapper == null) {
            return List.of();
        }
        List<MfgWorkReport> reports = workReportMapper.selectList(new LambdaQueryWrapper<MfgWorkReport>()
                .eq(MfgWorkReport::getAuditStatus, BusinessStatus.Document.SUBMITTED)
                .in(scope != null, MfgWorkReport::getReportNo, scope)
                        .eq(MfgWorkReport::getDeletedFlag, 0)
                .orderByAsc(MfgWorkReport::getReportId)
                .last("limit " + CANDIDATE_LIMIT));
        List<Target> targets = new ArrayList<>();
        for (MfgWorkReport report : reports) {
            targets.add(new Target(TARGET_WORK_REPORT, report.getReportNo(), report.getReportId(), report));
        }
        return targets;
    }

    @Override
    public Map<String, Object> executeBatch(AiCommandBatchRequest request, AuthorizationContext authorization) {
        requireAssistant(authorization);
        List<BatchItem> items = batchItemsOf(request);
        if (items.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "请给出要办理的单据，可以传确认凭据或「动作 + 单据号」");
        }
        String remark = request == null ? null : request.remark();
        String batchKey = request == null ? null : trimToNull(request.idempotencyKey());

        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0;
        for (BatchItem item : items) {
            Map<String, Object> row = executeBatchItem(item, batchKey, remark, authorization);
            if (Boolean.TRUE.equals(row.get("ok"))) {
                succeeded++;
            }
            results.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", results.size());
        data.put("succeeded", succeeded);
        data.put("failed", results.size() - succeeded);
        data.put("items", results);
        return data;
    }

    /** 批量请求既支持 confirmationIds 简写，也支持 items 明细，这里合并成统一清单。 */
    private List<BatchItem> batchItemsOf(AiCommandBatchRequest request) {
        List<BatchItem> items = new ArrayList<>();
        if (request == null) {
            return items;
        }
        addExplicitItems(items, request.items());
        addConfirmationItems(items, request.confirmationIds());
        return items;
    }

    /** 追加明细项，跳过空项。 */
    private void addExplicitItems(List<BatchItem> items, List<BatchItem> explicitItems) {
        if (explicitItems == null) {
            return;
        }
        for (BatchItem item : explicitItems) {
            if (item != null) {
                items.add(item);
            }
        }
    }

    /** 追加 confirmationIds 简写项，跳过空白。 */
    private void addConfirmationItems(List<BatchItem> items, List<String> confirmationIds) {
        if (confirmationIds == null) {
            return;
        }
        for (String confirmationId : confirmationIds) {
            if (StringUtils.hasText(confirmationId)) {
                items.add(new BatchItem(confirmationId, null, null, null));
            }
        }
    }

    /**
     * 办理批量里的一项。每一项都走完整的预览 + 执行链路，失败只影响自己，
     * 因此不会出现「批量确认跳过业务检查」的情况。
     */
    private Map<String, Object> executeBatchItem(BatchItem item, String batchKey, String remark,
                                                 AuthorizationContext authorization) {
        String bizNo = StringUtils.hasText(item.targetNo()) ? item.targetNo().trim() : null;
        try {
            String confirmationId = StringUtils.hasText(item.confirmationId())
                    ? item.confirmationId().trim() : previewBatchItem(item, authorization);
            if (!StringUtils.hasText(bizNo)) {
                bizNo = targetNoOfConfirmation(confirmationId);
            }
            Map<String, Object> executed = execute(new AiCommandExecuteRequest(
                    confirmationId, deriveIdempotencyKey(batchKey, confirmationId, bizNo),
                    item.action(), remark), authorization);
            return batchItemResult(bizNo, executed, null);
        } catch (BusinessException e) {
            return batchItemResult(bizNo, null, e.getMessage());
        } catch (RuntimeException e) {
            return batchItemResult(bizNo, null, "办理失败：" + e.getMessage());
        }
    }

    /** 批量项没有确认凭据时，先按「动作 + 单据号」生成预览，拿到凭据再执行。 */
    private String previewBatchItem(BatchItem item, AuthorizationContext authorization) {
        Map<String, Object> preview = previewExplicit(item.action(), item.targetNo(), item.params(),
                new AiCommandPreviewRequest(item.action(), null, "API", item.params()), authorization);
        return String.valueOf(preview.get("confirmationId"));
    }

    /** 按确认凭据回查单据号，用于在结果里告诉用户办的是哪张单。 */
    private String targetNoOfConfirmation(String confirmationId) {
        try {
            return loadCommandByConfirmation(confirmationId).getTargetNo();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 每项派生的幂等键：批次键 + 确认凭据，保证重跑同一批不会重复建单。 */
    private String deriveIdempotencyKey(String batchKey, String confirmationId, String bizNo) {
        if (!StringUtils.hasText(batchKey)) {
            return null;
        }
        String tail = StringUtils.hasText(confirmationId) ? confirmationId : String.valueOf(bizNo);
        return batchKey.trim() + "-" + tail;
    }

    /** 批量里单项的结果行。 */
    private Map<String, Object> batchItemResult(String bizNo, Map<String, Object> executed, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bizNo", bizNo);
        row.put("ok", executed != null);
        row.put("message", executed != null ? textOf(executed.get("summary")) : message);
        row.put("commandId", executed == null ? null : executed.get("commandId"));
        row.put("bizRef", executed == null ? null : targetRoute(textOf(executed.get("targetType")),
                executed.get("bizId")));
        return row;
    }

    /** 办理成功后前端可直接打开的精确单据路由。 */
    private String targetRoute(String targetType, Object bizId) {
        if (bizId == null) {
            return null;
        }
        String base = switch (targetType == null ? "" : targetType) {
            case TARGET_WORK_ORDER -> "/mfg/work-orders";
            case TARGET_OUTBOUND -> "/wms/outbound-orders";
            case TARGET_INBOUND -> "/wms/inbound-orders";
            case TARGET_INSPECTION -> "/qms/inspection-orders";
            case TARGET_WORK_REPORT -> "/mfg/work-reports";
            default -> null;
        };
        return base == null ? null : base + "?focus=" + bizId;
    }

    private static String textOf(Object value) {
        return value == null ? null : value.toString();
    }

    /* ==================== 目标解析 ==================== */

    /** 从自然语言里定位目标单据，并把单据主键写进参数表。 */
    private Target resolveTarget(String action, String text, Map<String, Object> params) {
        String code = extractCode(text, expectedPrefix(action));
        Target target = code != null ? loadByNo(action, code) : lookupByLike(action, text);
        if (target == null) {
            // 识别不到真实单据时只记下单号，后续前置条件会明确给出「未找到目标单据」。
            target = new Target(targetTypeOf(action), code, null, null);
            params.put("targetNo", code);
        } else {
            params.put("targetNo", target.no());
            params.put("targetId", target.id());
            bindTargetIds(target, params);
        }
        fillActionParams(action, text, params);
        return target;
    }

    private String expectedPrefix(String action) {
        return switch (action) {
            case ACTION_GENERATE_WORK_ORDER -> "PO";
            case ACTION_RELEASE_WORK_ORDER, ACTION_START_WORK_ORDER, ACTION_GENERATE_MATERIAL_ISSUE, ACTION_GENERATE_DISPATCH -> "WO";
            case ACTION_TRANSFER_TO_LINE_SIDE, ACTION_CONFIRM_OUTBOUND -> OUTBOUND_PREFIXES.get(0);
            case ACTION_CONFIRM_INBOUND -> "IN";
            case ACTION_SUBMIT_INSPECTION -> "QI";
            case ACTION_AUDIT_WORK_REPORT -> "WR";
            // 第 7 阶段按产品批次出库，批次号是 C1-LP-…；第 8 阶段的发货单是 C1-SI-…（销售出库）。
            case ACTION_GENERATE_PRODUCTION_INBOUND -> "LP";
            case ACTION_GENERATE_SALES_OUTBOUND -> "PO";
            case ACTION_CREATE_OUTGOING_INSPECTION, ACTION_CONFIRM_SALES_OUTBOUND -> "SI";
            // 派工单号是 C1-DO-…。
            case ACTION_RELEASE_DISPATCH -> "DO";
            // 工序任务号是 C1-TK-…。
            case ACTION_SUBMIT_WORK_REPORT, ACTION_START_TASK -> "TK";
            default -> "";
        };
    }

    private String extractCode(String text, String prefix) {
        String upper = text.toUpperCase(Locale.ROOT);
        String matched = firstCodeWithPrefixes(upper, prefixesFor(prefix));
        if (matched != null) {
            return matched;
        }
        return firstCode(upper);
    }

    /**
     * 出库单有三种前缀，按 MI → SO → OUT 依次尝试；工单号里也可能出现 WO 片段，
     * 所以必须按动作期望的前缀整体匹配，不能取第一个命中的片段。
     */
    private List<String> prefixesFor(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            // 不指定前缀时不做前缀过滤，直接取文本里第一个命中的单号。
            return List.of();
        }
        if (OUTBOUND_PREFIXES.contains(prefix)) {
            return OUTBOUND_PREFIXES;
        }
        return List.of(prefix);
    }

    /** 按给定前缀顺序找第一个命中；前缀为空列表时直接返回 null。 */
    private String firstCodeWithPrefixes(String upper, List<String> prefixes) {
        for (String candidate : prefixes) {
            String matched = firstCodeWithPrefix(upper, candidate);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    /** 找第一个前缀匹配的单号。 */
    private String firstCodeWithPrefix(String upper, String candidate) {
        Matcher matcher = CODE_PATTERN.matcher(upper);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(candidate)) {
                return matcher.group();
            }
        }
        return null;
    }

    /** 不做前缀过滤，取文本里第一个单号。 */
    private String firstCode(String upper) {
        Matcher matcher = CODE_PATTERN.matcher(upper);
        return matcher.find() ? matcher.group() : null;
    }

    /** 按单号前缀定位单据类型，查不到返回 null。 */
    private Target loadByNo(String action, String code) {
        if (ACTION_CREATE_PRODUCTION_ORDER.equals(action)) {
            return loadProductByCode(code);
        }
        String canonical = numbers.canonical(code);
        if (ACTION_SCHEDULE_PRODUCTION_ORDER.equals(action)
                || ACTION_GENERATE_WORK_ORDER.equals(action)
                || ACTION_GENERATE_SALES_OUTBOUND.equals(action)) {
            return loadProductionOrderByNo(canonical);
        }
        return loadByPrefix(canonical);
    }

    /** 创建生产订单这条命令认的是产品编码，不是单号。 */
    private Target loadProductByCode(String code) {
        if (aiProductMapper == null || !StringUtils.hasText(code)) return null;
        var products = aiProductMapper.selectList(new LambdaQueryWrapper<com.neuedu.ems.entity.mdm.MdmProduct>()
                .eq(com.neuedu.ems.entity.mdm.MdmProduct::getProductCode, code.trim())
                .eq(com.neuedu.ems.entity.mdm.MdmProduct::getDeletedFlag, 0));
        if (products == null || products.size() != 1) return null;
        var product = products.getFirst();
        return new Target(TARGET_PRODUCT, product.getProductCode(), product.getProductId(), product);
    }

    private Target loadProductionOrderByNo(String code) {
        if (aiProductionOrderMapper == null) return null;
        var order = aiProductionOrderMapper.selectOne(new LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgProductionOrder>()
                .eq(com.neuedu.ems.entity.mfg.MfgProductionOrder::getProductionOrderNo, code)
                .eq(com.neuedu.ems.entity.mfg.MfgProductionOrder::getDeletedFlag, 0).last("limit 1"));
        return order == null ? null : new Target(TARGET_PRODUCTION_ORDER,
                order.getProductionOrderNo(), order.getProductionOrderId(), order);
    }

    /** 其余命令按单号前缀分流到各自的查询。 */
    private Target loadByPrefix(String canonical) {
        Matcher numberMatcher = CODE_PATTERN.matcher(canonical);
        String prefix = numberMatcher.matches()
                ? numberMatcher.group(1).toUpperCase(Locale.ROOT)
                : canonical.toUpperCase(Locale.ROOT);
        String kind = leadingDocumentKind(prefix);
        if (kind != null) {
            return findLeadingDocument(kind, canonical);
        }
        return loadByOtherPrefix(prefix, canonical);
    }

    /**
     * 工单、批次、任务、派工先于出库认领。
     * 产品批次 C1-LP-… 如果落到出库分支，会被 OUTBOUND_PREFIXES 里的 SO 抢走。
     */
    private static String leadingDocumentKind(String prefix) {
        String kind = null;
        if (prefix.startsWith("WO")) {
            kind = "WO";
        } else if (prefix.startsWith("LP")) {
            kind = "LP";
        } else if (prefix.startsWith("TK")) {
            kind = "TK";
        } else if (prefix.startsWith("DO")) {
            kind = "DO";
        } else if (OUTBOUND_PREFIXES.stream().anyMatch(prefix::startsWith)) {
            kind = "OUT";
        }
        return kind;
    }

    /** 按已认领的前缀取单据。 */
    private Target findLeadingDocument(String kind, String code) {
        return switch (kind) {
            case "WO" -> findWorkOrderByNo(code);
            case "LP" -> findProductLotByNo(code);
            case "TK" -> findTaskByNo(code);
            case "DO" -> findDispatchByNo(code);
            default -> findOutboundByNo(code);
        };
    }

    /** 其余单据类型：入库 / 检验 / 报工。 */
    private Target loadByOtherPrefix(String prefix, String code) {
        String kind = otherDocumentKind(prefix);
        if (kind == null) {
            return null;
        }
        return findByDocumentKind(kind, code);
    }

    /** 其余单据的类型编码，认不出返回 null。 */
    private static String otherDocumentKind(String prefix) {
        String kind = null;
        if (prefix.startsWith("IN")) {
            kind = "IN";
        } else if (prefix.startsWith("QI")) {
            kind = "QI";
        } else if (prefix.startsWith("WR")) {
            kind = "WR";
        }
        return kind;
    }

    /** 按类型编码取对应单据。 */
    private Target findByDocumentKind(String kind, String code) {
        return switch (kind) {
            case "IN" -> findInboundByNo(code);
            case "QI" -> findInspectionByNo(code);
            default -> findWorkReportByNo(code);
        };
    }

    /** 按工单号精确取工单。 */
    private Target findWorkOrderByNo(String code) {
        if (workOrderMapper == null) {
            return null;
        }
        MfgWorkOrder order = workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderNo, code).eq(MfgWorkOrder::getDeletedFlag, 0)
                .last("limit 1"));
        return order == null ? null : new Target(TARGET_WORK_ORDER, order.getWorkOrderNo(),
                order.getWorkOrderId(), order);
    }

    /** 按出库单号精确取出库单。 */
    private Target findOutboundByNo(String code) {
        if (outboundOrderMapper == null) {
            return null;
        }
        WmsOutboundOrder outbound = outboundOrderMapper.selectOne(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getOutboundNo, code).eq(WmsOutboundOrder::getDeletedFlag, 0)
                .last("limit 1"));
        return outbound == null ? null : new Target(TARGET_OUTBOUND, outbound.getOutboundNo(),
                outbound.getOutboundId(), outbound);
    }

    /** 按入库单号精确取入库单。 */
    private Target findInboundByNo(String code) {
        if (inboundOrderMapper == null) {
            return null;
        }
        WmsInboundOrder inbound = inboundOrderMapper.selectOne(new LambdaQueryWrapper<WmsInboundOrder>()
                .eq(WmsInboundOrder::getInboundNo, code).eq(WmsInboundOrder::getDeletedFlag, 0)
                .last("limit 1"));
        return inbound == null ? null : new Target(TARGET_INBOUND, inbound.getInboundNo(),
                inbound.getInboundId(), inbound);
    }

    /** 按检验单号精确取检验单。 */
    private Target findInspectionByNo(String code) {
        if (inspectionOrderMapper == null) {
            return null;
        }
        QmsInspectionOrder inspection = inspectionOrderMapper.selectOne(
                new LambdaQueryWrapper<QmsInspectionOrder>()
                        .eq(QmsInspectionOrder::getInspectionNo, code)
                        .eq(QmsInspectionOrder::getDeletedFlag, 0)
                        .last("limit 1"));
        return inspection == null ? null : new Target(TARGET_INSPECTION, inspection.getInspectionNo(),
                inspection.getInspectionId(), inspection);
    }

    /** 按产品批次号精确取批次，用于第 7 阶段成品入库。 */
    private Target findProductLotByNo(String code) {
        if (productLotMapper == null) {
            return null;
        }
        com.neuedu.ems.entity.trace.TraceProductLot lot = productLotMapper.selectOne(
                new LambdaQueryWrapper<com.neuedu.ems.entity.trace.TraceProductLot>()
                        .eq(com.neuedu.ems.entity.trace.TraceProductLot::getLotNo, code)
                        .eq(com.neuedu.ems.entity.trace.TraceProductLot::getDeletedFlag, 0)
                        .last("limit 1"));
        return lot == null ? null : new Target(TARGET_PRODUCT_LOT, lot.getLotNo(),
                lot.getProductLotId(), lot);
    }

    /** 按工序任务号精确取任务。 */
    private Target findTaskByNo(String code) {
        if (taskMapper == null) {
            return null;
        }
        MfgTask task = taskMapper.selectOne(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getTaskNo, code)
                .eq(MfgTask::getDeletedFlag, 0)
                .last("limit 1"));
        return task == null ? null : new Target(TARGET_TASK, task.getTaskNo(), task.getTaskId(), task);
    }

    /** 按派工单号精确取派工单。 */
    private Target findDispatchByNo(String code) {
        if (aiDispatchMapper == null) {
            return null;
        }
        com.neuedu.ems.entity.mfg.MfgDispatchOrder dispatch = aiDispatchMapper.selectOne(
                new LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgDispatchOrder>()
                        .eq(com.neuedu.ems.entity.mfg.MfgDispatchOrder::getDispatchNo, code)
                        .eq(com.neuedu.ems.entity.mfg.MfgDispatchOrder::getDeletedFlag, 0)
                        .last("limit 1"));
        return dispatch == null ? null : new Target(TARGET_DISPATCH, dispatch.getDispatchNo(),
                dispatch.getDispatchId(), dispatch);
    }

    /** 按报工单号精确取报工单。 */
    private Target findWorkReportByNo(String code) {
        if (workReportMapper == null) {
            return null;
        }
        MfgWorkReport report = workReportMapper.selectOne(new LambdaQueryWrapper<MfgWorkReport>()
                .eq(MfgWorkReport::getReportNo, code).eq(MfgWorkReport::getDeletedFlag, 0)
                .last("limit 1"));
        return report == null ? null : new Target(TARGET_WORK_REPORT, report.getReportNo(),
                report.getReportId(), report);
    }

    /**
     * 识别不到标准单号时，用业务表按单号 LIKE 反查，绝不猜测主键。
     */
    private Target lookupByLike(String action, String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = BARE_TOKEN.matcher(text.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.chars().anyMatch(Character::isDigit)) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return null;
        }
        for (String token : tokens) {
            Target found = switch (action) {
                case ACTION_RELEASE_WORK_ORDER, ACTION_START_WORK_ORDER, ACTION_GENERATE_MATERIAL_ISSUE ->
                        likeWorkOrder(token);
                case ACTION_TRANSFER_TO_LINE_SIDE, ACTION_CONFIRM_OUTBOUND -> likeOutbound(token);
                case ACTION_CONFIRM_INBOUND -> likeInbound(token);
                case ACTION_SUBMIT_INSPECTION -> likeInspection(token);
                case ACTION_AUDIT_WORK_REPORT -> likeWorkReport(token);
                default -> null;
            };
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Target likeWorkOrder(String token) {
        if (workOrderMapper == null) {
            return null;
        }
        MfgWorkOrder order = workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .like(MfgWorkOrder::getWorkOrderNo, token).eq(MfgWorkOrder::getDeletedFlag, 0)
                .orderByAsc(MfgWorkOrder::getWorkOrderId).last("limit 1"));
        return order == null ? null : new Target(TARGET_WORK_ORDER, order.getWorkOrderNo(),
                order.getWorkOrderId(), order);
    }

    private Target likeOutbound(String token) {
        if (outboundOrderMapper == null) {
            return null;
        }
        WmsOutboundOrder outbound = outboundOrderMapper.selectOne(new LambdaQueryWrapper<WmsOutboundOrder>()
                .like(WmsOutboundOrder::getOutboundNo, token).eq(WmsOutboundOrder::getDeletedFlag, 0)
                .orderByAsc(WmsOutboundOrder::getOutboundId).last("limit 1"));
        return outbound == null ? null : new Target(TARGET_OUTBOUND, outbound.getOutboundNo(),
                outbound.getOutboundId(), outbound);
    }

    private Target likeInbound(String token) {
        if (inboundOrderMapper == null) {
            return null;
        }
        WmsInboundOrder inbound = inboundOrderMapper.selectOne(new LambdaQueryWrapper<WmsInboundOrder>()
                .like(WmsInboundOrder::getInboundNo, token).eq(WmsInboundOrder::getDeletedFlag, 0)
                .orderByAsc(WmsInboundOrder::getInboundId).last("limit 1"));
        return inbound == null ? null : new Target(TARGET_INBOUND, inbound.getInboundNo(),
                inbound.getInboundId(), inbound);
    }

    private Target likeInspection(String token) {
        if (inspectionOrderMapper == null) {
            return null;
        }
        QmsInspectionOrder inspection = inspectionOrderMapper.selectOne(
                new LambdaQueryWrapper<QmsInspectionOrder>()
                        .like(QmsInspectionOrder::getInspectionNo, token)
                        .eq(QmsInspectionOrder::getDeletedFlag, 0)
                        .orderByAsc(QmsInspectionOrder::getInspectionId).last("limit 1"));
        return inspection == null ? null : new Target(TARGET_INSPECTION, inspection.getInspectionNo(),
                inspection.getInspectionId(), inspection);
    }

    private Target likeWorkReport(String token) {
        if (workReportMapper == null) {
            return null;
        }
        MfgWorkReport report = workReportMapper.selectOne(new LambdaQueryWrapper<MfgWorkReport>()
                .like(MfgWorkReport::getReportNo, token).eq(MfgWorkReport::getDeletedFlag, 0)
                .orderByAsc(MfgWorkReport::getReportId).last("limit 1"));
        return report == null ? null : new Target(TARGET_WORK_REPORT, report.getReportNo(),
                report.getReportId(), report);
    }

    private String targetTypeOf(String action) {
        ActionSpec spec = SPEC_BY_ACTION.get(action);
        return spec == null ? null : spec.targetType();
    }

    /* ==================== 显式预览（供大模型计划器调用） ==================== */

    @Override
    public Map<String, Object> previewExplicit(String action, String targetNo, Map<String, Object> planParams,
                                               AiCommandPreviewRequest request, AuthorizationContext authorization) {
        requireAssistant(authorization);
        ActionSpec spec = resolveSpec(action);
        requirePermission(authorization, spec.permission());
        requireGenerationReadPermissions(spec.action(), authorization);
        String text = request == null || !StringUtils.hasText(request.text())
                ? spec.label() : request.text().trim();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", spec.action());
        params.put("text", text);
        Target target = resolveExplicitTarget(spec, targetNo, text, params);
        applyPlanParams(params, planParams);
        if (ACTION_CREATE_PRODUCTION_ORDER.equals(spec.action())) {
            resolveCreationCustomer(params, text);
        } else if (ACTION_SCHEDULE_PRODUCTION_ORDER.equals(spec.action())) {
            prepareScheduleCandidate(target, params, text);
        } else if (ACTION_GENERATE_WORK_ORDER.equals(spec.action())) {
            prepareGeneration(target, params);
        } else if (ACTION_GENERATE_DISPATCH.equals(spec.action())) {
            prepareDispatch(target, params, text);
        }
        fillActionParams(spec.action(), text, params);

        return assemblePreview(spec, target, params, text,
                request == null ? null : request.conversationId(),
                request == null ? null : request.source(), authorization);
    }

    /* ==================== 结构化预览（供前端表单直接提交） ==================== */

    @Override
    public Map<String, Object> previewStructured(String action, Map<String, Object> formParams,
                                                 AiCommandPreviewRequest request, AuthorizationContext authorization) {
        requireAssistant(authorization);
        ActionSpec spec = resolveSpec(action);
        requirePermission(authorization, spec.permission());
        Map<String, Object> form = formParams == null ? Map.of() : formParams;
        if (ACTION_CREATE_PRODUCTION_ORDER.equals(spec.action())) {
            return previewStructuredCreation(form, request, authorization);
        }
        if (ACTION_SCHEDULE_PRODUCTION_ORDER.equals(spec.action())) {
            return previewStructuredSchedule(form, request, authorization);
        }
        // 其余动作的必填项本来就是一张单据号，直接交给显式预览，不另开一套校验。
        String targetNo = trimToNull(stringParam(form, "targetNo"));
        Map<String, Object> planParams = new LinkedHashMap<>(form);
        planParams.remove("targetNo");
        return previewExplicit(spec.action(), targetNo, planParams, request, authorization);
    }

    /**
     * 排产的表单预览：把下拉选中的生产订单和产线还原成一句业务描述，再走显式预览。
     *
     * <p>时段不取表单值：产线一旦选定，具体时段仍由排程领域服务算最早可排窗口，
     * 这样表单入口和对话入口排出来的时间段完全一致。</p>
     */
    private Map<String, Object> previewStructuredSchedule(Map<String, Object> form,
                                                          AiCommandPreviewRequest request,
                                                          AuthorizationContext authorization) {
        String orderNo = trimToNull(stringParam(form, "orderNo"));
        if (!StringUtils.hasText(orderNo)) {
            orderNo = trimToNull(stringParam(form, "targetNo"));
        }
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请先选择要排产的生产订单");
        }
        String lineCode = trimToNull(stringParam(form, "lineCode"));
        String text = StringUtils.hasText(lineCode)
                ? "为生产订单 " + orderNo + " 排产，指定产线 " + lineCode
                : "为生产订单 " + orderNo + " 排产";
        Map<String, Object> planParams = new LinkedHashMap<>();
        if (StringUtils.hasText(lineCode)) {
            planParams.put("lineCode", lineCode);
        }
        AiCommandPreviewRequest synthesized = new AiCommandPreviewRequest(text,
                request == null ? null : request.conversationId(),
                request == null ? null : request.source(),
                request == null ? null : request.context());
        return previewExplicit(ACTION_SCHEDULE_PRODUCTION_ORDER, orderNo, planParams, synthesized, authorization);
    }

    /**
     * 创建生产订单的表单预览：把下拉选中的产品、客户与手填的数量、交期还原成一句业务描述，
     * 再走显式预览。这样「产品已启用、客户已启用、数量、交期」四项前置检查的判定口径与对话入口完全一致。
     */
    private Map<String, Object> previewStructuredCreation(Map<String, Object> form,
                                                          AiCommandPreviewRequest request,
                                                          AuthorizationContext authorization) {
        String productCode = trimToNull(stringParam(form, "productCode"));
        String customerCode = trimToNull(stringParam(form, "customerCode"));
        BigDecimal quantity = decimalOf(form.get("orderQuantity"));
        String deliveryDate = trimToNull(stringParam(form, "deliveryDate"));
        if (!StringUtils.hasText(productCode)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请先选择要生产的产品");
        }
        if (!StringUtils.hasText(customerCode)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请先选择客户");
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "订单数量必须是大于 0 的整数");
        }
        if (!StringUtils.hasText(deliveryDate)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请选择交付日期");
        }
        try {
            java.time.LocalDate.parse(deliveryDate);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "交付日期格式应为 yyyy-MM-dd");
        }
        String quantityText = quantity.stripTrailingZeros().toPlainString();
        String text = "创建 " + productCode + " 生产订单，客户 " + customerCode
                + "，数量 " + quantityText + " 台，交期 " + deliveryDate;
        Map<String, Object> planParams = new LinkedHashMap<>();
        planParams.put("customerCode", customerCode);
        planParams.put("orderQuantity", quantityText);
        planParams.put("deliveryDate", deliveryDate);
        AiCommandPreviewRequest synthesized = new AiCommandPreviewRequest(text,
                request == null ? null : request.conversationId(),
                request == null ? null : request.source(),
                request == null ? null : request.context());
        return previewExplicit(ACTION_CREATE_PRODUCTION_ORDER, productCode, planParams, synthesized, authorization);
    }

    @Override
    public Map<String, Object> createOrderOptions(AuthorizationContext authorization) {
        requireAssistant(authorization);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("products", productOptions());
        data.put("customers", customerOptions());
        return data;
    }

    /**
     * 排产表单的可选值：不带单号时只给待排产订单清单；带单号时再算该订单可用的产线与最早时段。
     *
     * <p>产线和时段一律由排程领域服务实时计算，前端只负责展示和选择，不允许自己填。</p>
     */
    @Override
    public Map<String, Object> scheduleOptions(String orderNo, AuthorizationContext authorization) {
        requireAssistant(authorization);
        ActionSpec spec = resolveSpec(ACTION_SCHEDULE_PRODUCTION_ORDER);
        requirePermission(authorization, spec.permission());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orders", schedulableOrderOptions());
        String code = trimToNull(orderNo);
        if (!StringUtils.hasText(code)) {
            data.put("order", null);
            data.put("lines", List.of());
            return data;
        }
        Target target = loadByNo(ACTION_SCHEDULE_PRODUCTION_ORDER, code);
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(),
                    "没有找到单号为 " + code + " 的生产订单，请核对单号后再试");
        }
        Map<String, Object> order = new LinkedHashMap<>();
        if (target.entity() instanceof com.neuedu.ems.entity.mfg.MfgProductionOrder value) {
            order.put("productionOrderId", value.getProductionOrderId());
            order.put("productionOrderNo", value.getProductionOrderNo());
            order.put("productId", value.getProductId());
            order.put("orderQuantity", value.getOrderQuantity());
            order.put("deliveryDate", value.getDeliveryDate() == null ? null : value.getDeliveryDate().toString());
            order.put("status", value.getStatus());
        }
        data.put("order", order);
        data.put("lines", scheduleLineOptions(target));
        return data;
    }

    /** 待排产订单：新建状态且还没生成生产计划的那几张。 */
    private List<Map<String, Object>> schedulableOrderOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        if (aiProductionOrderMapper == null) {
            return options;
        }
        List<com.neuedu.ems.entity.mfg.MfgProductionOrder> rows = aiProductionOrderMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgProductionOrder>()
                        .eq(com.neuedu.ems.entity.mfg.MfgProductionOrder::getStatus, BusinessStatus.Document.DRAFT)
                        .eq(com.neuedu.ems.entity.mfg.MfgProductionOrder::getDeletedFlag, 0)
                        .orderByAsc(com.neuedu.ems.entity.mfg.MfgProductionOrder::getProductionOrderId)
                        .last("limit " + CANDIDATE_LIMIT));
        if (rows == null) {
            return options;
        }
        for (com.neuedu.ems.entity.mfg.MfgProductionOrder order : rows) {
            // 已经有生产计划的订单再排一次必然被领域服务拒绝，这里先剔掉，避免用户选了才发现不能办。
            if (hasProductionPlan(order.getProductionOrderId())) {
                continue;
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("productionOrderId", order.getProductionOrderId());
            option.put("productionOrderNo", order.getProductionOrderNo());
            option.put("productId", order.getProductId());
            option.put("orderQuantity", order.getOrderQuantity());
            option.put("deliveryDate", order.getDeliveryDate() == null ? null : order.getDeliveryDate().toString());
            options.add(option);
        }
        return options;
    }

    private boolean hasProductionPlan(Long productionOrderId) {
        if (productionOrderId == null || productionPlanMapper == null) {
            return false;
        }
        Long count = productionPlanMapper.selectCount(
                new LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgProductionPlan>()
                        .eq(com.neuedu.ems.entity.mfg.MfgProductionPlan::getProductionOrderId, productionOrderId)
                        .eq(com.neuedu.ems.entity.mfg.MfgProductionPlan::getDeletedFlag, 0));
        return count != null && count > 0;
    }

    /** 订单可用的产线与最早可排时段，全部来自排程领域服务。 */
    private List<Map<String, Object>> scheduleLineOptions(Target target) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (target.id() == null || aiProductionSchedule == null) {
            return options;
        }
        List<com.neuedu.ems.vo.mfg.ManualScheduleCandidateLine> lines;
        try {
            lines = aiProductionSchedule.preview(target.id());
        } catch (BusinessException ex) {
            return options;
        }
        if (lines == null) {
            return options;
        }
        for (com.neuedu.ems.vo.mfg.ManualScheduleCandidateLine line : lines) {
            if (line == null || line.getLineId() == null || line.getEarliestStartTime() == null
                    || line.getEarliestEndTime() == null) {
                continue;
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("lineId", line.getLineId());
            option.put("lineCode", line.getLineCode());
            option.put("lineName", line.getLineName());
            LocalDateTime start = line.getEarliestStartTime().withNano(0);
            LocalDateTime end = line.getEarliestEndTime();
            if (end.getNano() != 0) end = end.withNano(0).plusSeconds(1);
            option.put("plannedStartTime", start.toString());
            option.put("plannedEndTime", end.toString());
            options.add(option);
        }
        return options;
    }

    private List<Map<String, Object>> productOptions() {
        if (aiProductMapper == null) {
            return List.of();
        }
        List<com.neuedu.ems.entity.mdm.MdmProduct> rows = aiProductMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.mdm.MdmProduct>()
                        .eq(com.neuedu.ems.entity.mdm.MdmProduct::getStatus, BusinessStatus.Common.ENABLE)
                        .eq(com.neuedu.ems.entity.mdm.MdmProduct::getDeletedFlag, 0)
                        .orderByAsc(com.neuedu.ems.entity.mdm.MdmProduct::getProductCode)
                        .last("limit " + CANDIDATE_LIMIT));
        List<Map<String, Object>> options = new ArrayList<>();
        if (rows == null) {
            return options;
        }
        for (com.neuedu.ems.entity.mdm.MdmProduct product : rows) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("productId", product.getProductId());
            option.put("productCode", product.getProductCode());
            option.put("productName", product.getProductName());
            option.put("productModel", product.getProductModel());
            options.add(option);
        }
        return options;
    }

    private List<Map<String, Object>> customerOptions() {
        if (aiCustomerMapper == null) {
            return List.of();
        }
        List<com.neuedu.ems.entity.mdm.MdmCustomer> rows = aiCustomerMapper.selectList(
                new LambdaQueryWrapper<com.neuedu.ems.entity.mdm.MdmCustomer>()
                        .eq(com.neuedu.ems.entity.mdm.MdmCustomer::getStatus, BusinessStatus.Common.ENABLE)
                        .eq(com.neuedu.ems.entity.mdm.MdmCustomer::getDeletedFlag, 0)
                        .orderByAsc(com.neuedu.ems.entity.mdm.MdmCustomer::getCustomerCode)
                        .last("limit " + CANDIDATE_LIMIT));
        List<Map<String, Object>> options = new ArrayList<>();
        if (rows == null) {
            return options;
        }
        for (com.neuedu.ems.entity.mdm.MdmCustomer customer : rows) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("customerId", customer.getCustomerId());
            option.put("customerCode", customer.getCustomerCode());
            option.put("customerName", customer.getCustomerName());
            options.add(option);
        }
        return options;
    }

    /** 按动作编码取能力定义，编码为空或不认识时报参数错误。 */
    private ActionSpec resolveSpec(String action) {
        if (!StringUtils.hasText(action)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "缺少要办理的业务动作");
        }
        ActionSpec spec = SPEC_BY_ACTION.get(action.trim().toUpperCase(Locale.ROOT));
        if (spec == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), UNRECOGNIZED_ACTION_MESSAGE);
        }
        return spec;
    }

    /**
     * 解析显式预览的目标单据。计划已经给出明确单据号时，必须先按号查库确认存在，查不到就报错，绝不新建。
     */
    private Target resolveExplicitTarget(ActionSpec spec, String targetNo, String text,
                                         Map<String, Object> params) {
        if (ACTION_CREATE_PRODUCTION_ORDER.equals(spec.action())
                && (!StringUtils.hasText(targetNo) || !text.contains(targetNo.trim()))) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请在本次消息中明确提供产品编码");
        }
        if (!StringUtils.hasText(targetNo)) {
            return resolveTarget(spec.action(), text, params);
        }
        String code = targetNo.trim();
        Target target = loadByNo(spec.action(), code);
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(),
                    "没有找到单号为 " + code + " 的" + targetTypeLabel(spec.targetType())
                            + "，请核对单号后再试");
        }
        params.put("targetNo", target.no());
        params.put("targetId", target.id());
        bindTargetIds(target, params);
        return target;
    }

    /** 客户主键只能由服务端根据本轮明确提供的客户编码解析。 */
    private void resolveCreationCustomer(Map<String, Object> params, String text) {
        params.remove("customerId");
        String code = stringParam(params, "customerCode");
        if (!StringUtils.hasText(code) || !text.contains(code) || aiCustomerMapper == null) return;
        var matches = aiCustomerMapper.selectList(new LambdaQueryWrapper<com.neuedu.ems.entity.mdm.MdmCustomer>()
                .eq(com.neuedu.ems.entity.mdm.MdmCustomer::getCustomerCode, code)
                .eq(com.neuedu.ems.entity.mdm.MdmCustomer::getDeletedFlag, 0));
        if (matches != null && matches.size() == 1
                && BusinessStatus.Common.ENABLE.equals(matches.getFirst().getStatus())) {
            params.put("customerId", matches.getFirst().getCustomerId());
        }
    }

    /** 排程候选由领域服务实时计算，不能采用模型虚构的产线或时间。 */
    private void prepareScheduleCandidate(Target target, Map<String, Object> params, String text) {
        params.remove("lineId");
        params.remove("plannedStartTime");
        params.remove("plannedEndTime");
        String requestedLine = stringParam(params, "lineCode");
        if (StringUtils.hasText(requestedLine) && !text.contains(requestedLine)) {
            params.put("scheduleProblem", "请明确指定产线编码，或不指定产线由系统推荐");
            return;
        }
        if (target.id() == null || aiProductionSchedule == null) {
            params.put("scheduleProblem", "生产订单或排程服务不可用");
            return;
        }
        try {
            var candidates = aiProductionSchedule.preview(target.id());
            var candidate = candidates.stream().filter(line -> !StringUtils.hasText(requestedLine)
                    || requestedLine.equalsIgnoreCase(line.getLineCode())).findFirst().orElse(null);
            if (candidate == null || candidate.getLineId() == null || candidate.getEarliestStartTime() == null
                    || candidate.getEarliestEndTime() == null) {
                params.put("scheduleProblem", "没有符合条件的可用产线和时段，请先检查产能与排程");
                return;
            }
            params.put("lineId", candidate.getLineId());
            params.put("lineCode", candidate.getLineCode());
            params.put("lineName", candidate.getLineName());
            LocalDateTime start = candidate.getEarliestStartTime().withNano(0);
            LocalDateTime end = candidate.getEarliestEndTime();
            if (end.getNano() != 0) end = end.withNano(0).plusSeconds(1);
            params.put("plannedStartTime", start.toString());
            params.put("plannedEndTime", end.toString());
        } catch (BusinessException ex) {
            params.put("scheduleProblem", ex.getMessage());
        }
    }

    /**
     * 计划给出的显式参数优先，覆盖正则推导的默认值；但单号与主键不允许被覆盖。
     */
    private void applyPlanParams(Map<String, Object> params, Map<String, Object> planParams) {
        if (planParams == null) {
            return;
        }
        planParams.forEach((key, value) -> {
            if (!isOverridableParam(key, value)) {
                return;
            }
            params.put(key, coerceParam(key, value));
        });
    }

    /** 判断计划参数是否可以写入：空值、单号、主键与动作名一律不覆盖。 */
    private boolean isOverridableParam(String key, Object value) {
        if (value == null || isProtectedParamKey(key)) {
            return false;
        }
        return !(value instanceof String string && !StringUtils.hasText(string));
    }

    /** 这几个 key 由执行链路自己维护，计划参数不许覆盖。 */
    private static boolean isProtectedParamKey(String key) {
        return "targetNo".equals(key) || "targetId".equals(key)
                || "action".equals(key) || "text".equals(key);
    }

    /**
     * 计划参数按能力目录声明的类型做一次轻量规整，避免字符串数字导致后续比较异常。
     */
    private Object coerceParam(String key, Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        return coerceText(key, text.trim());
    }

    /** 字符串参数按 key 语义决定是否转数字，转不了就保留原文本。 */
    private Object coerceText(String key, String trimmed) {
        if (!isNumericParamKey(key)) {
            return trimmed;
        }
        return parseDecimalQuietly(trimmed);
    }

    /** 数量与主键类参数才按数字处理。 */
    private boolean isNumericParamKey(String key) {
        return key.endsWith("Quantity") || key.endsWith("Id") || "extraQuantity".equals(key);
    }

    /** 能转就转成 BigDecimal，转不了保留原文。 */
    private Object parseDecimalQuietly(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    /** 把目标单据自身的主键与单号写进参数表，供领域服务调用时使用。 */
    private void bindTargetIds(Target target, Map<String, Object> params) {
        switch (target.entity()) {
            case MfgWorkOrder workOrder -> {
                params.put("workOrderId", workOrder.getWorkOrderId());
                params.put("workOrderNo", workOrder.getWorkOrderNo());
            }
            case WmsOutboundOrder outbound -> {
                params.put("outboundId", outbound.getOutboundId());
                params.put("outboundNo", outbound.getOutboundNo());
                params.put("workOrderId", outbound.getWorkOrderId());
            }
            case WmsInboundOrder inbound -> {
                params.put("inboundId", inbound.getInboundId());
                params.put("inboundNo", inbound.getInboundNo());
            }
            case QmsInspectionOrder inspection -> {
                params.put("inspectionId", inspection.getInspectionId());
                params.put("inspectionNo", inspection.getInspectionNo());
            }
            case MfgWorkReport report -> {
                params.put("reportId", report.getReportId());
                params.put("reportNo", report.getReportNo());
            }
            case com.neuedu.ems.entity.mfg.MfgDispatchOrder dispatch -> {
                params.put("dispatchId", dispatch.getDispatchId());
                params.put("dispatchNo", dispatch.getDispatchNo());
                params.put("workOrderId", dispatch.getWorkOrderId());
            }
            case MfgTask task -> {
                params.put("taskId", task.getTaskId());
                params.put("taskNo", task.getTaskNo());
                params.put("workOrderId", task.getWorkOrderId());
            }
            default -> {
                // 目标尚未解析成实体时没有主键可写，前置条件会给出未找到单据的结论。
            }
        }
    }

    private String targetTypeLabel(String targetType) {
        return switch (targetType == null ? "" : targetType) {
            case TARGET_WORK_ORDER -> "生产工单";
            case TARGET_OUTBOUND -> "出库单";
            case TARGET_INBOUND -> "入库单";
            case TARGET_INSPECTION -> "检验单";
            case TARGET_WORK_REPORT -> "报工单";
            case TARGET_PRODUCT -> "产品";
            case TARGET_PRODUCTION_ORDER -> "生产订单";
            case TARGET_PRODUCT_LOT -> "产品批次";
            case TARGET_DISPATCH -> "派工单";
            case TARGET_TASK -> "工序任务";
            default -> "单据";
        };
    }

    /* ==================== 动作参数抽取 ==================== */

    /** 按动作补齐执行参数，识别不到的单据号不会被猜出来。 */
    private void fillActionParams(String action, String text, Map<String, Object> params) {
        switch (action) {
            case ACTION_GENERATE_MATERIAL_ISSUE -> fillIssueWarehouse(text,params);
            case ACTION_TRANSFER_TO_LINE_SIDE -> fillLineSideLocation(params);
            case ACTION_SUBMIT_INSPECTION -> fillInspectionParams(text, params);
            case ACTION_AUDIT_WORK_REPORT -> fillAuditResult(text, params);
            case ACTION_SUBMIT_WORK_REPORT -> fillReportQuantities(text, params);
            case ACTION_GENERATE_PRODUCTION_INBOUND -> fillFinishedStockParams(params, false);
            case ACTION_GENERATE_SALES_OUTBOUND -> fillFinishedStockParams(params, true);
            default -> {
                // 其余动作不需要额外参数，单据主键已在 bindTargetIds 里带齐。
            }
        }
    }

    /** 只采用明确填写的数量，不能把尚未生产的剩余量当成实际报工。 */
    private void fillReportQuantities(String text, Map<String, Object> params) {
        if (decimalOf(params.get("qualifiedQuantity")) == null && text != null) {
            Matcher match = Pattern.compile("合格(?:数量|数)?\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)").matcher(text);
            if (match.find()) params.put("qualifiedQuantity", new BigDecimal(match.group(1)));
        }
        if (decimalOf(params.get("defectQuantity")) == null && text != null) {
            Matcher match = Pattern.compile("(?:不良|不合格)(?:数量|数)?\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)").matcher(text);
            if (match.find()) params.put("defectQuantity", new BigDecimal(match.group(1)));
        }
        if (decimalOf(params.get("qualifiedQuantity")) != null) {
            params.putIfAbsent("defectQuantity", BigDecimal.ZERO);
        }
    }

    /** 明确仓库优先；歧义或无匹配不能静默退回原料仓。 */
    private void fillIssueWarehouse(String text,Map<String, Object> params) {
        String type=textOf(params.get("warehouseType")),words=text==null?"":text;
        if(words.contains("半成品")) type="SEMI_FINISHED";
        else if(words.contains("原料")||words.contains("原材料")) type="RAW";
        if(longOf(params.get("warehouseId"))!=null) {
            if(StringUtils.hasText(type)) params.put("warehouseType",type);
            WmsWarehouse selected=loadWarehouse(longOf(params.get("warehouseId")));
            if(selected!=null) params.put("warehouseName",selected.getWarehouseName());
            return;
        }
        String code=textOf(params.get("warehouseCode"));
        Matcher mentioned=Pattern.compile("[A-Za-z0-9]+-WH-[A-Za-z0-9-]+",Pattern.CASE_INSENSITIVE).matcher(words);
        if(mentioned.find()) code=mentioned.group();
        if(!StringUtils.hasText(type)&&!StringUtils.hasText(code)) {
            Long id=firstIssueableWarehouseId();if(id!=null)params.put("warehouseId",id);return;
        }
        if(warehouseMapper==null) {params.put("warehouseProblem","仓库数据暂不可用，请重试，未改用其他仓库");return;}
        String wantedType=type,wantedCode=code;
        var found=warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getDeletedFlag,0).eq(WmsWarehouse::getStatus,BusinessStatus.Common.ENABLE))
                .stream().filter(w->!StringUtils.hasText(wantedType)||wantedType.equalsIgnoreCase(w.getWarehouseType()))
                .filter(w->!StringUtils.hasText(wantedCode)||wantedCode.equalsIgnoreCase(w.getWarehouseCode())).toList();
        if(found.size()!=1) {params.put("warehouseProblem",found.isEmpty()?"没有找到指定的启用仓库，请核对类型或编码；未改用原料仓":"该类型有多个仓库，请明确仓库编码后再预览");return;}
        params.put("warehouseId",found.getFirst().getWarehouseId());
        params.put("warehouseName",found.getFirst().getWarehouseName());
        params.put("warehouseType",found.getFirst().getWarehouseType());
    }

    /** 转线边使用实际消耗工单的产线推荐库位，明确选择的库位仍由领域服务核查。 */
    private void fillLineSideLocation(Map<String, Object> params) {
        if (longOf(params.get("targetLocationId")) != null) {
            return;
        }
        Long outboundId=longOf(params.get("outboundId"));
        if(outboundId!=null && outboundOrderService!=null) {
            com.neuedu.ems.service.wms.LineSideLocationService.Preparation preparation;
            try { preparation=outboundOrderService.prepareLineSideTransfer(outboundId); }
            catch(BusinessException error) {params.put("targetLocationProblem",error.getMessage());return;}
            if(preparation!=null) {
                if(preparation.recommendedLocationId()!=null) params.put("targetLocationId",preparation.recommendedLocationId());
                else params.put("targetLocationProblem",preparation.explanation());
                return;
            }
        }
        Long locationId = firstLineSideLocationId();
        if (locationId != null) {
            params.put("targetLocationId", locationId);
        }
    }

    /**
     * 第 7、8 阶段默认带出成品仓与库位。
     *
     * <p>入库和发货都发生在成品仓，用户不需要自己选仓库；只有一处成品仓时直接填上，
     * 有多处时留给弹窗或后续动作覆盖。销售出库另外带上客户，避免领域服务报「客户不能为空」。</p>
     */
    private void fillFinishedStockParams(Map<String, Object> params, boolean salesOutbound) {
        Long warehouseId = longOf(params.get("warehouseId"));
        if (warehouseId == null) {
            warehouseId = firstFinishedWarehouseId();
            if (warehouseId != null) {
                params.put("warehouseId", warehouseId);
            }
        }
        if (longOf(params.get("locationId")) == null) {
            Long locationId = firstFinishedLocationId(warehouseId);
            if (locationId != null) {
                params.put("locationId", locationId);
            }
        }
        if (!salesOutbound || longOf(params.get("customerId")) != null) {
            return;
        }
        Long orderId = longOf(params.get("targetId"));
        if (orderId != null && aiProductionOrderMapper != null) {
            var order = aiProductionOrderMapper.selectById(orderId);
            if (order != null && order.getCustomerId() != null) {
                params.put("customerId", order.getCustomerId());
            }
        }
    }

    /** 从自然语言里抽取检验样本、合格与不合格数量，以及结论。 */
    private void fillInspectionParams(String text, Map<String, Object> params) {
        BigDecimal qualified = decimalAfter(text, "合格");
        BigDecimal unqualified = decimalAfter(text, "不合格");
        BigDecimal sample = resolveSampleQuantity(text, qualified, unqualified);
        // 数量齐全时结论由领域服务按不合格率自动判定，不能再叠加关键词结论，
        // 否则「不合格 0」里的「不合格」会把 PASS 误判成 FAIL。
        boolean quantitiesProvided = sample != null && qualified != null && unqualified != null;
        if (!quantitiesProvided) {
            String result = parseInspectionResult(text);
            if (result != null) {
                params.put("inspectionResult", result);
            }
        }
        putIfNotNull(params, "sampleQuantity", sample);
        putIfNotNull(params, "qualifiedQuantity", qualified);
        putIfNotNull(params, "unqualifiedQuantity", unqualified);
    }

    /** 样本数：优先取「样本」「抽样」后的数字，都没写时用合格数加不合格数。 */
    private BigDecimal resolveSampleQuantity(String text, BigDecimal qualified, BigDecimal unqualified) {
        BigDecimal sample = decimalAfter(text, "样本");
        if (sample == null) {
            sample = decimalAfter(text, "抽样");
        }
        if (sample == null && qualified != null && unqualified != null) {
            sample = qualified.add(unqualified);
        }
        return sample;
    }

    /** 从自然语言里抽取报工审核结论。 */
    private void fillAuditResult(String text, Map<String, Object> params) {
        String auditResult = parseAuditResult(text);
        if (auditResult != null) {
            params.put("auditResult", auditResult);
        }
    }

    /** 值非空时才写入参数表。 */
    private static void putIfNotNull(Map<String, Object> params, String key, Object value) {
        if (value != null) {
            params.put(key, value);
        }
    }

    /** 检验结论关键词规则：按顺序命中，先命中先生效。 */
    private record InspectionResultRule(String result, List<String> keywords) {
    }

    private static final List<InspectionResultRule> INSPECTION_RESULT_RULES = List.of(
            new InspectionResultRule(BusinessStatus.Quality.CONCESSION, List.of("让步")),
            new InspectionResultRule(BusinessStatus.Quality.FAIL, List.of("不合格", "判为不良")),
            new InspectionResultRule(BusinessStatus.Quality.PASS, List.of("合格", "判为良品")));

    private String parseInspectionResult(String text) {
        for (InspectionResultRule rule : INSPECTION_RESULT_RULES) {
            if (containsAny(text, rule.keywords())) {
                return rule.result();
            }
        }
        return null;
    }

    private String parseAuditResult(String text) {
        if (text.contains("不通过") || text.contains("驳回") || text.contains("审核不合格")) {
            return BusinessStatus.Document.REJECTED;
        }
        if (text.contains("通过") || text.contains("审核合格") || text.contains("同意")) {
            return BusinessStatus.Document.APPROVED;
        }
        return null;
    }

    private BigDecimal decimalAfter(String text, String keyword) {
        Pattern pattern = Pattern.compile(Pattern.quote(keyword) + "\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return new BigDecimal(matcher.group(1));
        }
        return null;
    }

    /* ==================== 前置条件与影响 ==================== */

    private void evaluate(ActionSpec spec, Target target, Map<String, Object> params,
                          List<Map<String, Object>> impacts, List<Map<String, Object>> preconditions,
                          List<String> warnings) {
        switch (spec.action()) {
            case ACTION_CREATE_PRODUCTION_ORDER -> evaluateCreateProductionOrder(target, params, preconditions, impacts);
            case ACTION_SCHEDULE_PRODUCTION_ORDER -> evaluateScheduleProductionOrder(target, params, preconditions, impacts);
            case ACTION_GENERATE_WORK_ORDER -> evaluateGenerateWorkOrder(target, params, preconditions, impacts);
            case ACTION_GENERATE_DISPATCH -> evaluateGenerateDispatch(target, params, preconditions, impacts);
            case ACTION_RELEASE_WORK_ORDER -> evaluateRelease(target, preconditions, impacts, warnings);
            case ACTION_START_WORK_ORDER -> evaluateStart(target, preconditions, impacts, warnings);
            case ACTION_GENERATE_MATERIAL_ISSUE -> evaluateMaterialIssue(target, params, preconditions, impacts, warnings);
            case ACTION_TRANSFER_TO_LINE_SIDE -> evaluateTransfer(target, params, preconditions, impacts, warnings);
            case ACTION_CONFIRM_OUTBOUND -> evaluateConfirmOutbound(target, preconditions, impacts, warnings);
            case ACTION_CONFIRM_INBOUND -> evaluateConfirmInbound(target, preconditions, impacts, warnings);
            case ACTION_SUBMIT_INSPECTION -> evaluateInspection(target, params, preconditions, impacts, warnings);
            case ACTION_AUDIT_WORK_REPORT -> evaluateWorkReport(target, params, preconditions, impacts, warnings);
            case ACTION_GENERATE_PRODUCTION_INBOUND ->
                    evaluateGenerateProductionInbound(target, params, preconditions, impacts, warnings);
            case ACTION_GENERATE_SALES_OUTBOUND ->
                    evaluateGenerateSalesOutbound(target, params, preconditions, impacts, warnings);
            case ACTION_CREATE_OUTGOING_INSPECTION ->
                    evaluateCreateOutgoingInspection(target, preconditions, impacts, warnings);
            case ACTION_CONFIRM_SALES_OUTBOUND ->
                    evaluateConfirmSalesOutbound(target, preconditions, impacts, warnings);
            case ACTION_RELEASE_DISPATCH -> evaluateReleaseDispatch(target, preconditions, impacts, warnings);
            case ACTION_START_TASK -> evaluateStartTask(target, preconditions, impacts, warnings);
            case ACTION_SUBMIT_WORK_REPORT ->
                    evaluateSubmitWorkReport(target, params, preconditions, impacts, warnings);
            default -> addPrecondition(preconditions, "ACTION_SUPPORTED", "动作受支持", false, "未知动作");
        }
    }

    /**
     * 第 7 阶段：终检合格批次 → 生产入库单。
     *
     * <p>前置条件按领域服务的校验顺序排：先看批次是否放行，再看成品仓/库位是否就绪，
     * 让用户在点确认之前就知道缺什么，而不是执行后被拒。</p>
     */
    private void evaluateGenerateProductionInbound(Target target, Map<String, Object> params,
                                                   List<Map<String, Object>> preconditions,
                                                   List<Map<String, Object>> impacts, List<String> warnings) {
        com.neuedu.ems.entity.trace.TraceProductLot lot =
                target != null && target.entity() instanceof com.neuedu.ems.entity.trace.TraceProductLot entity
                        ? entity : null;
        addPrecondition(preconditions, "LOT", "产品批次存在且未删除", lot != null,
                lot == null ? "未找到产品批次" : "批次 " + lot.getLotNo());
        boolean released = lot != null && (BusinessStatus.Quality.PASS.equals(lot.getStatus())
                || BusinessStatus.Quality.CONCESSION.equals(lot.getStatus()));
        addPrecondition(preconditions, "LOT_RELEASED", "批次已终检合格或让步放行", released,
                lot == null ? "无批次状态" : "当前状态：" + statusText(lot.getStatus()));
        Long warehouseId = firstFinishedWarehouseId();
        addPrecondition(preconditions, "FINISHED_WAREHOUSE", "存在启用的成品仓", warehouseId != null,
                warehouseId == null ? "没有启用的成品仓，请先维护仓库资料" : "成品仓ID：" + warehouseId);
        Long locationId = firstFinishedLocationId(warehouseId);
        addPrecondition(preconditions, "FINISHED_LOCATION", "成品仓存在可用库位", locationId != null,
                locationId == null ? "成品仓没有可用库位" : "库位ID：" + locationId);
        addImpact(impacts, "MEDIUM", "生成生产入库单后等待确认入库，确认才会增加成品库存。");
        addImpact(impacts, "LOW", "同一批次已有待确认入库单时会直接复用，不会重复建单。");
    }

    /**
     * 第 8 阶段：已完工订单 → 销售出库（发货）单。
     *
     * <p>不重复实现领域服务的库存分配，只把最容易卡住的几项显式列出：订单状态、
     * 工单是否全部完工、客户是否关联、成品仓是否存在。</p>
     */
    private void evaluateGenerateSalesOutbound(Target target, Map<String, Object> params,
                                               List<Map<String, Object>> preconditions,
                                               List<Map<String, Object>> impacts, List<String> warnings) {
        com.neuedu.ems.entity.mfg.MfgProductionOrder order =
                target != null && target.entity() instanceof com.neuedu.ems.entity.mfg.MfgProductionOrder entity
                        ? entity : null;
        addPrecondition(preconditions, "ORDER", "生产订单存在且未删除", order != null,
                order == null ? "未找到生产订单" : "订单 " + order.getProductionOrderNo());
        boolean shippable = order != null && (BusinessStatus.Document.RELEASED.equals(order.getStatus())
                || BusinessStatus.Production.RUNNING.equals(order.getStatus())
                || BusinessStatus.Production.FINISHED.equals(order.getStatus()));
        addPrecondition(preconditions, "ORDER_STATUS", "订单已下达或已完工", shippable,
                order == null ? "无订单状态" : "当前状态：" + statusText(order.getStatus()));
        boolean customerReady = order != null && order.getCustomerId() != null;
        addPrecondition(preconditions, "ORDER_CUSTOMER", "订单已关联客户", customerReady,
                customerReady ? "客户ID：" + order.getCustomerId() : "订单没有客户，无法生成发货单");
        String unfinished = unfinishedWorkOrderText(order);
        addPrecondition(preconditions, "WORK_ORDERS_FINISHED", "订单生产与组件供给已完成",
                unfinished == null, unfinished == null ? "工单全部完工" : unfinished);
        Long warehouseId = firstFinishedWarehouseId();
        addPrecondition(preconditions, "FINISHED_WAREHOUSE", "存在启用的成品仓", warehouseId != null,
                warehouseId == null ? "没有启用的成品仓，请先维护仓库资料" : "成品仓ID：" + warehouseId);
        addImpact(impacts, "MEDIUM", "生成销售出库单后需先出货检验、再确认发货，发货会扣减成品库存。");
        addImpact(impacts, "LOW", "同一生产订单已有有效发货单时会直接复用。");
    }

    /** 出货检验：出库单必须待确认，且产品配了已发布的出货检验方案。 */
    private void evaluateCreateOutgoingInspection(Target target, List<Map<String, Object>> preconditions,
                                                  List<Map<String, Object>> impacts, List<String> warnings) {
        WmsOutboundOrder outbound = target.entity() instanceof WmsOutboundOrder order ? order : null;
        addPrecondition(preconditions, "OUTBOUND_EXISTS", "销售出库单存在且未删除", outbound != null,
                describeOutboundFound(outbound, target));
        addPrecondition(preconditions, "OUTBOUND_SALE", "单据类型为销售出库", outboundIsSale(outbound),
                outbound == null ? "无单据类型" : "当前类型：" + outbound.getOutboundType());
        addPrecondition(preconditions, "OUTBOUND_SUBMITTED", "出库单状态为 SUBMITTED（待确认）",
                isSubmitted(outbound), describeAuditStatus(outbound));
        String schemeDetail = outgoingSchemeDetail(outbound);
        addPrecondition(preconditions, "OUTGOING_SCHEME", "产品已配置已发布的出货检验方案",
                schemeDetail.startsWith("已发布"), schemeDetail);
        addImpact(impacts, "MEDIUM", "生成出货检验单后需录入检验结果，检验合格才能确认发货。");
    }

    /** 确认发货：必须已过出货检验，且明细齐全。 */
    private void evaluateConfirmSalesOutbound(Target target, List<Map<String, Object>> preconditions,
                                              List<Map<String, Object>> impacts, List<String> warnings) {
        WmsOutboundOrder outbound = target.entity() instanceof WmsOutboundOrder order ? order : null;
        addPrecondition(preconditions, "OUTBOUND_EXISTS", "销售出库单存在且未删除", outbound != null,
                describeOutboundFound(outbound, target));
        addPrecondition(preconditions, "OUTBOUND_SALE", "单据类型为销售出库", outboundIsSale(outbound),
                outbound == null ? "无单据类型" : "当前类型：" + outbound.getOutboundType());
        addPrecondition(preconditions, "OUTBOUND_SUBMITTED", "出库单状态为 SUBMITTED（待确认）",
                isSubmitted(outbound), describeAuditStatus(outbound));
        addPrecondition(preconditions, "OUTGOING_INSPECTION_PASSED", "出货检验已完成且合格",
                outgoingInspectionPassed(outbound), outgoingInspectionDetail(outbound));
        long details = countOutboundDetails(outbound);
        addPrecondition(preconditions, "OUTBOUND_DETAIL", "存在有效出库明细", details > 0,
                "明细条数：" + details);
        addImpact(impacts, "HIGH", "确认后扣减成品库存、生成库存流水并回写销售订单已发数量，不可撤销。");
        addImpact(impacts, "LOW", "操作会写入命令记录与系统操作日志。");
        warnings.add("确认发货会真实扣减成品库存，请核对数量与去向。");
    }

    /** 下达派工单：必须先有 WAIT_DISPATCH 派工单，且还没生成工序任务。 */
    private void evaluateReleaseDispatch(Target target, List<Map<String, Object>> preconditions,
                                         List<Map<String, Object>> impacts, List<String> warnings) {
        com.neuedu.ems.entity.mfg.MfgDispatchOrder dispatch =
                target != null && target.entity() instanceof com.neuedu.ems.entity.mfg.MfgDispatchOrder entity
                        ? entity : null;
        addPrecondition(preconditions, "DISPATCH_EXISTS", "派工单存在且未删除", dispatch != null,
                dispatch == null ? "未找到派工单" : "派工单 " + dispatch.getDispatchNo());
        boolean waiting = dispatch != null && "WAIT_DISPATCH".equals(dispatch.getStatus());
        addPrecondition(preconditions, "DISPATCH_STATUS", "派工单状态为待下达", waiting,
                dispatch == null ? "无派工状态" : "当前状态：" + statusText(dispatch.getStatus()));
        boolean hasTeam = dispatch != null && dispatch.getTeamId() != null;
        addPrecondition(preconditions, "DISPATCH_TEAM", "派工单已指定班组", hasTeam,
                hasTeam ? "班组ID：" + dispatch.getTeamId() : "派工单没有班组，无法下达");
        long existing = countWorkOrderTasks(dispatch);
        addPrecondition(preconditions, "NO_ACTIVE_TASKS", "该派工单还没有工序任务", existing == 0,
                existing == 0 ? "可下达" : "已存在 " + existing + " 条工序任务，无需重复下达");
        addImpact(impacts, "HIGH", "下达后按工艺路线生成工序任务，工单才能开始生产。");
        addImpact(impacts, "LOW", "操作会写入命令记录与系统操作日志。");
    }

    /** 派工单所属工单已有的工序任务条数。 */
    private long countWorkOrderTasks(com.neuedu.ems.entity.mfg.MfgDispatchOrder dispatch) {
        if (dispatch == null || dispatch.getWorkOrderId() == null || taskMapper == null) {
            return 0L;
        }
        return taskMapper.selectCount(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getWorkOrderId, dispatch.getWorkOrderId())
                .eq(MfgTask::getDeletedFlag, 0));
    }

    private void evaluateStartTask(Target target, List<Map<String, Object>> preconditions,
                                   List<Map<String, Object>> impacts, List<String> warnings) {
        MfgTask task = target != null && target.entity() instanceof MfgTask entity ? entity : null;
        addPrecondition(preconditions, "TASK_EXISTS", "工序任务存在", task != null,
                task == null ? "未找到工序任务" : "任务 " + task.getTaskNo());
        addPrecondition(preconditions, "TASK_WAIT_START", "任务处于待开工状态",
                task != null && BusinessStatus.Production.WAIT_START.equals(task.getStatus()),
                task == null ? "无任务状态" : "当前状态：" + statusText(task.getStatus()));
        if (task != null && task.getStationId() != null && taskMapper != null) {
            long running = taskMapper.selectCount(new LambdaQueryWrapper<MfgTask>()
                    .eq(MfgTask::getStationId, task.getStationId())
                    .eq(MfgTask::getStatus, BusinessStatus.Production.RUNNING)
                    .eq(MfgTask::getDeletedFlag, 0).ne(MfgTask::getTaskId, task.getTaskId()));
            addPrecondition(preconditions, "TASK_STATION_FREE", "工位当前没有其他生产中的任务", running == 0,
                    running == 0 ? "工位空闲" : "工位被其他生产任务占用，请先处理占用任务或更换合适工位");
        } else {
            addPrecondition(preconditions, "TASK_STATION_FREE", "已分配可核查工位", false,
                    "任务没有可核查的工位，请先核对派工");
        }
        addImpact(impacts, "HIGH", "开工后任务进入生产中，可按真实完成情况报工。");
        if (task != null) {
            boolean ready = false;
            String detail = "前序核查服务不可用，请稍后重新检查";
            if (aiTaskService != null) {
                try {
                    aiTaskService.checkTaskPredecessors(task.getTaskId());
                    ready = true;
                    detail = "冻结路线前序已完成且合格数量满足";
                } catch (BusinessException e) {
                    detail = e.getMessage();
                }
            }
            addPrecondition(preconditions, "TASK_PREDECESSORS", "冻结路线前序与合格数量满足开工条件", ready, detail);
        }
        warnings.add("执行时还会检查操作人员、工位、物料和前序工序；未通过时不会开工。");
    }

    /** 提交报工：任务必须在生产中、还有剩余可报数量，且本次数量大于 0。 */
    private void evaluateSubmitWorkReport(Target target, Map<String, Object> params,
                                          List<Map<String, Object>> preconditions,
                                          List<Map<String, Object>> impacts, List<String> warnings) {
        MfgTask task = target != null && target.entity() instanceof MfgTask entity ? entity : null;
        addPrecondition(preconditions, "TASK_EXISTS", "工序任务存在且未删除", task != null,
                task == null ? "未找到工序任务" : "工序任务 " + task.getTaskNo());
        boolean running = task != null && "RUNNING".equals(task.getStatus());
        addPrecondition(preconditions, "TASK_RUNNING", "工序任务处于生产中", running,
                task == null ? "无任务状态" : "当前状态：" + statusText(task.getStatus()));
        BigDecimal planned = task == null ? BigDecimal.ZERO : zeroOf(task.getPlannedQuantity());
        BigDecimal done = task == null ? BigDecimal.ZERO : zeroOf(task.getCompletedQuantity());
        BigDecimal remaining = planned.subtract(done);
        boolean hasRemaining = remaining.signum() > 0;
        addPrecondition(preconditions, "TASK_REMAINING", "还有剩余可报数量", hasRemaining,
                "计划 " + plainQuantity(planned) + "，已报 " + plainQuantity(done)
                        + "，剩余 " + plainQuantity(remaining));
        BigDecimal qualified = decimalOf(params.get("qualifiedQuantity"));
        BigDecimal defect = decimalOf(params.get("defectQuantity"));
        addPrecondition(preconditions, "REPORT_ACTUAL_QUANTITY", "已明确本次实际合格数量",
                qualified != null, qualified == null ? "请提供本次实际合格数量和不良数量" : "已提供合格数量");
        BigDecimal total = zeroOf(qualified).add(zeroOf(defect));
        addPrecondition(preconditions, "REPORT_QUANTITY", "本次合格和不良数量不能为负且合计大于 0",
                qualified != null && qualified.signum() >= 0 && zeroOf(defect).signum() >= 0 && total.signum() > 0,
                "本次合格 " + plainQuantity(zeroOf(qualified)) + "，不良 " + plainQuantity(zeroOf(defect)));
        boolean withinRemaining = total.signum() > 0 && total.compareTo(remaining) <= 0;
        addPrecondition(preconditions, "REPORT_WITHIN_REMAINING", "本次数量不超过剩余可报数量", withinRemaining,
                withinRemaining ? "可提交" : total.signum() <= 0 ? "请先填写大于零的实际报工数量"
                        : "本次 " + plainQuantity(total) + " 超过剩余 " + plainQuantity(remaining));
        if (task != null && withinRemaining) {
            try {
                var materials = reportMaterials(task.getTaskId(), total);
                String materialSnapshot = materials.stream()
                        .map(material -> material.getMaterialId() + ":" + material.getBatchId() + ":"
                                + plainQuantity(material.getUsedQuantity()))
                        .sorted().collect(java.util.stream.Collectors.joining("|"));
                Object previousSnapshot = params.putIfAbsent("reportMaterialSnapshot", materialSnapshot);
                boolean unchanged = previousSnapshot == null || materialSnapshot.equals(previousSnapshot.toString());
                addPrecondition(preconditions, "REPORT_MATERIAL_PREPARATION", "用料批次准备成功", true,
                        materials.isEmpty() ? "本工序无直接投料明细" : "请核对以下建议用料");
                addPrecondition(preconditions, "REPORT_MATERIAL_UNCHANGED", "用料批次与预览一致", unchanged,
                        unchanged ? "用料未变化" : "用料已变化，请重新预览并确认");
                for (var material : materials) {
                    addImpact(impacts, "MEDIUM", "建议用料：物料 " + material.getMaterialId()
                            + "，批次 " + material.getBatchId() + "，数量 " + plainQuantity(material.getUsedQuantity()));
                }
                warnings.add("建议用料按合格与不良总数计算，确认前请核对实际批次和数量；执行时会重新检查。");
            } catch (RuntimeException error) {
                addPrecondition(preconditions, "REPORT_MATERIAL_PREPARATION", "用料批次准备成功", false,
                        "用料准备失败：" + error.getMessage());
            }
        }
        addImpact(impacts, "HIGH", "提交后生成待审核报工单，并联动工单完工进度。");
        addImpact(impacts, "LOW", "审核通过后才会进入工单合格数量。");
    }

    /** 出库单是否是销售出库。 */
    private static boolean outboundIsSale(WmsOutboundOrder outbound) {
        return outbound != null && "SALE".equals(outbound.getOutboundType());
    }

    /** 该订单下未完工工单的说明，全部完工返回 null。 */
    private String unfinishedWorkOrderText(com.neuedu.ems.entity.mfg.MfgProductionOrder order) {
        if (order == null || workOrderMapper == null || productionPlanMapper == null
                || order.getProductionOrderId() == null) {
            return null;
        }
        List<MfgProductionPlan> plans = productionPlanMapper.selectList(
                new LambdaQueryWrapper<MfgProductionPlan>()
                        .eq(MfgProductionPlan::getProductionOrderId, order.getProductionOrderId())
                        .eq(MfgProductionPlan::getDeletedFlag, 0));
        if (plans.isEmpty()) {
            return "订单尚未排产，没有生产工单";
        }
        List<Long> planIds = plans.stream().map(MfgProductionPlan::getPlanId).toList();
        List<MfgWorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<MfgWorkOrder>()
                .in(MfgWorkOrder::getPlanId, planIds)
                .eq(MfgWorkOrder::getDeletedFlag, 0));
        List<String> pending = new ArrayList<>();
        for (MfgWorkOrder item : orders) {
            if (!BusinessStatus.Production.FINISHED.equals(item.getStatus())
                    && (stockComponentSupply==null || !stockComponentSupply.fulfilledForShipment(item))) {
                pending.add(item.getWorkOrderNo() + "(" + statusText(item.getStatus()) + ")");
            }
        }
        return pending.isEmpty() ? null : "未完工工单：" + String.join("、", pending);
    }

    /** 出货检验方案说明。判断是否已发布时用 startsWith。 */
    private String outgoingSchemeDetail(WmsOutboundOrder outbound) {
        if (outbound == null || outbound.getSourceProductionOrderId() == null
                || aiProductionOrderMapper == null || inspectionSchemeMapper == null) {
            return "缺少来源订单，无法判断出货检验方案";
        }
        var order = aiProductionOrderMapper.selectById(outbound.getSourceProductionOrderId());
        if (order == null) {
            return "来源生产订单不存在";
        }
        Long schemes = inspectionSchemeMapper.selectCount(
                new LambdaQueryWrapper<com.neuedu.ems.entity.qms.QmsInspectionScheme>()
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionScheme::getProductId, order.getProductId())
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionScheme::getInspectionType, "OUTGOING")
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionScheme::getStatus,
                                BusinessStatus.Document.RELEASED)
                        .eq(com.neuedu.ems.entity.qms.QmsInspectionScheme::getDeletedFlag, 0));
        return schemes != null && schemes > 0
                ? "已发布出货检验方案 " + schemes + " 个" : "没有已发布的出货检验方案";
    }

    /** 出货检验是否已完成且合格。 */
    private boolean outgoingInspectionPassed(WmsOutboundOrder outbound) {
        if (outbound == null || outbound.getInspectionId() == null || inspectionOrderMapper == null) {
            return false;
        }
        QmsInspectionOrder inspection = inspectionOrderMapper.selectById(outbound.getInspectionId());
        if (inspection == null || !Integer.valueOf(0).equals(inspection.getDeletedFlag())) {
            return false;
        }
        return "FINISHED".equals(inspection.getStatus())
                && (BusinessStatus.Quality.PASS.equals(inspection.getInspectionResult())
                || BusinessStatus.Quality.CONCESSION.equals(inspection.getInspectionResult()));
    }

    /** 出货检验状态说明。 */
    private String outgoingInspectionDetail(WmsOutboundOrder outbound) {
        if (outbound == null || outbound.getInspectionId() == null) {
            return "尚未生成出货检验单";
        }
        if (inspectionOrderMapper == null) {
            return "检验单查询不可用";
        }
        QmsInspectionOrder inspection = inspectionOrderMapper.selectById(outbound.getInspectionId());
        if (inspection == null) {
            return "检验单不存在或已删除";
        }
        return "检验单 " + inspection.getInspectionNo() + " 状态："
                + statusText(inspection.getStatus()) + "，结论：" + statusText(inspection.getInspectionResult());
    }

    private void evaluateGenerateWorkOrder(Target target, Map<String, Object> params,
                                           List<Map<String, Object>> preconditions, List<Map<String, Object>> impacts) {
        boolean orderExists = target != null && target.id() != null;
        addPrecondition(preconditions, "ORDER", "生产订单存在", orderExists, orderExists ? target.no() : "未找到生产订单");
        Long selectedPlan = longOf(params.get("generationPlanId"));
        boolean planMatches = false;
        if (orderExists && selectedPlan != null && productionPlanMapper != null && workOrderMapper != null) {
            var plan = productionPlanMapper.selectById(selectedPlan);
            if (plan != null && java.util.Objects.equals(plan.getProductionOrderId(), target.id())
                    && Integer.valueOf(0).equals(plan.getDeletedFlag())
                    && !"COMPONENT".equals(plan.getPlanType())
                    && BusinessStatus.Document.RELEASED.equals(plan.getStatus())) {
                Long count = workOrderMapper.selectCount(new LambdaQueryWrapper<MfgWorkOrder>()
                        .eq(MfgWorkOrder::getPlanId, selectedPlan).eq(MfgWorkOrder::getDeletedFlag, 0));
                planMatches = count != null && count == 0;
            }
        }
        addPrecondition(preconditions, "PLAN", "唯一已生效计划尚未生成工单", planMatches,
                planMatches ? "计划已核对；执行时再次校验物料、工艺和资源。" : "未找到唯一可生成工单的计划，或计划已生成工单。");
        addImpact(impacts, "HIGH", "确认后生成根工单及所需组件工单，并可能预留物料；不会自动报工或填写检验结果。");
        addImpact(impacts, "MEDIUM", "生成组件工单时会按当前资源重新落实排产时段，可能调整关联计划；生成后以最新计划和工单时段为准。");
    }

    private void prepareGeneration(Target target, Map<String, Object> params) {
        params.remove("generationPlanId");
        if (target == null || target.id() == null || productionPlanMapper == null) return;
        var plans = productionPlanMapper.selectList(new LambdaQueryWrapper<MfgProductionPlan>()
                .eq(MfgProductionPlan::getProductionOrderId, target.id())
                .eq(MfgProductionPlan::getStatus, BusinessStatus.Document.RELEASED)
                .eq(MfgProductionPlan::getDeletedFlag, 0));
        if (plans == null) return;
        var roots = plans.stream().filter(plan -> !"COMPONENT".equals(plan.getPlanType())).toList();
        if (roots.size() == 1) {
            params.put("generationPlanId", roots.getFirst().getPlanId());
            params.put("generationPlanNo", roots.getFirst().getPlanNo());
        }
    }

    private void requireGenerationReadPermissions(String action, AuthorizationContext authorization) {
        if (ACTION_GENERATE_WORK_ORDER.equals(action) || ACTION_GENERATE_DISPATCH.equals(action)) {
            // 本入口尚未接入订单级数据范围过滤；不向受限范围账号放开跨范围生成。
            if (authorization == null || (!authorization.isSuperAdmin() && !"ALL".equals(authorization.dataScope()))) {
                throw new DataScopeAccessDeniedException();
            }
            requirePermission(authorization, "mfg:production-order:view");
            requirePermission(authorization, "mfg:production-plan:view");
            requirePermission(authorization, "mfg:work-order:view");
        }
    }

    private void prepareDispatch(Target target, Map<String, Object> params, String text) {
        params.remove("dispatchTeamId");
        params.remove("dispatchTeamName");
        params.remove("dispatchProblem");
        if (target == null || target.id() == null || aiDispatchService == null) {
            params.put("dispatchProblem", "工单或派工服务不可用");
            return;
        }
        try {
            var preparation = aiDispatchService.previewDispatch(target.id());
            aiDispatchService.validateDispatchMaterials(target.id());
            if (!preparation.blockers().isEmpty()) {
                params.put("dispatchProblem", String.join("；", preparation.blockers()));
                return;
            }
            var teams = preparation.teams();
            String requested = stringParam(params, "teamName");
            if (StringUtils.hasText(requested) && (text == null || !text.contains(requested))) requested = null;
            final String explicitName = requested;
            var eligible = StringUtils.hasText(explicitName)
                    ? teams.stream().filter(team -> explicitName.equals(team.teamName())).toList() : teams;
            if (eligible.size() != 1) {
                params.put("dispatchProblem", eligible.isEmpty() && StringUtils.hasText(explicitName)
                        ? "指定班组不在可用候选中" : "请选择班组：" + teams.stream()
                        .map(team -> team.teamName()).collect(java.util.stream.Collectors.joining("、")));
                return;
            }
            var team = eligible.getFirst();
            params.put("dispatchTeamId", team.teamId());
            params.put("dispatchTeamName", team.teamName());
            params.put("dispatchStart", preparation.plannedStartTime() == null ? "" : preparation.plannedStartTime().toString());
            params.put("dispatchDue", preparation.dueTime() == null ? "" : preparation.dueTime().toString());
        } catch (BusinessException ex) {
            params.put("dispatchProblem", ex.getMessage());
        }
    }

    private void evaluateGenerateDispatch(Target target, Map<String, Object> params,
                                          List<Map<String, Object>> preconditions, List<Map<String, Object>> impacts) {
        MfgWorkOrder order = target != null && target.entity() instanceof MfgWorkOrder wo ? wo : null;
        boolean released = order != null && BusinessStatus.Document.RELEASED.equals(order.getStatus());
        addPrecondition(preconditions, "WORK_ORDER", "工单已下达", released,
                order == null ? "工单不存在" : "当前状态：" + statusText(order.getStatus()));
        Long teamId = longOf(params.get("dispatchTeamId"));
        String problem = stringParam(params, "dispatchProblem");
        if (released && teamId != null && aiDispatchService != null) {
            try {
                var current = aiDispatchService.previewDispatch(order.getWorkOrderId());
                aiDispatchService.validateDispatchMaterials(order.getWorkOrderId());
                if (!current.blockers().isEmpty()) problem = String.join("；", current.blockers());
                else if (current.teams().stream().noneMatch(team -> java.util.Objects.equals(team.teamId(), teamId)))
                    problem = "所选班组当前不可用，请重新预览";
            } catch (BusinessException ex) { problem = ex.getMessage(); }
        } else if (!StringUtils.hasText(problem)) problem = "未选定有效班组或派工服务不可用";
        addPrecondition(preconditions, "PREPARATION", "班组、产能及本级用料满足派工条件",
                !StringUtils.hasText(problem), StringUtils.hasText(problem) ? problem : "条件已检查，执行时再次核对");
        Long count = null;
        if (order != null && aiDispatchMapper != null) {
            count = aiDispatchMapper.selectCount(new LambdaQueryWrapper<com.neuedu.ems.entity.mfg.MfgDispatchOrder>()
                    .eq(com.neuedu.ems.entity.mfg.MfgDispatchOrder::getWorkOrderId, order.getWorkOrderId())
                    .eq(com.neuedu.ems.entity.mfg.MfgDispatchOrder::getDeletedFlag, 0)
                    .notIn(com.neuedu.ems.entity.mfg.MfgDispatchOrder::getStatus,
                            BusinessStatus.Document.REJECTED, BusinessStatus.Document.CLOSED));
        }
        addPrecondition(preconditions, "DUPLICATE", "工单没有有效派工单", count != null && count == 0,
                count == null ? "无法检查派工单" : count > 0 ? "工单已有有效派工单" : "未发现有效派工单");
        addImpact(impacts, "HIGH", "确认后为工单和所选班组生成待派发的派工单；不会自动报工或填写检验结果。");
    }

    private void evaluateScheduleProductionOrder(Target target, Map<String, Object> params,
                                                  List<Map<String, Object>> preconditions,
                                                  List<Map<String, Object>> impacts) {
        var order = target.entity() instanceof com.neuedu.ems.entity.mfg.MfgProductionOrder value ? value : null;
        addPrecondition(preconditions, "ORDER", "生产订单为新建状态", order != null
                        && BusinessStatus.Document.DRAFT.equals(order.getStatus()),
                order == null ? "生产订单不存在" : "当前状态：" + statusText(order.getStatus()));
        String problem = stringParam(params, "scheduleProblem");
        Long lineId = longOf(params.get("lineId"));
        String start = stringParam(params, "plannedStartTime");
        String end = stringParam(params, "plannedEndTime");
        if (!StringUtils.hasText(problem) && order != null && lineId != null && aiProductionSchedule != null) {
            try {
                aiProductionSchedule.validate(order.getProductionOrderId(), lineId,
                        LocalDateTime.parse(start), LocalDateTime.parse(end));
            } catch (RuntimeException ex) {
                problem = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "无法校验排程";
            }
        } else if (!StringUtils.hasText(problem)) {
            problem = "产线或排程服务不可用";
        }
        addPrecondition(preconditions, "SCHEDULE", "产线、时段和产能可用", !StringUtils.hasText(problem),
                StringUtils.hasText(problem) ? problem : stringParam(params, "lineName") + "：" + start + " 至 " + end);
        addImpact(impacts, "HIGH", "确认后生成已生效的生产计划并下达订单；不会自动报工或改变库存。");
    }

    private void evaluateCreateProductionOrder(Target target, Map<String, Object> params,
                                                List<Map<String, Object>> preconditions,
                                                List<Map<String, Object>> impacts) {
        var product = target.entity() instanceof com.neuedu.ems.entity.mdm.MdmProduct p ? p : null;
        addPrecondition(preconditions, "PRODUCT", "产品已启用", product != null
                && BusinessStatus.Common.ENABLE.equals(product.getStatus()), product == null ? "产品不存在" : product.getProductCode());
        Long customerId = longOf(params.get("customerId"));
        var customer = aiCustomerMapper == null || customerId == null ? null : aiCustomerMapper.selectById(customerId);
        addPrecondition(preconditions, "CUSTOMER", "客户已启用", customer != null
                        && Integer.valueOf(0).equals(customer.getDeletedFlag())
                        && BusinessStatus.Common.ENABLE.equals(customer.getStatus()),
                customer == null ? "请明确提供有效客户编码" : customer.getCustomerName());
        BigDecimal quantity = decimalOf(params.get("orderQuantity"));
        String rawText = stringParam(params, "text");
        boolean quantityProvided = isQuantityMentionedInText(quantity, rawText);
        addPrecondition(preconditions, "QUANTITY", "订单数量为正整数", quantityProvided,
                quantityProvided ? quantity.toPlainString() + " 台" : "请在本次消息中明确提供正整数数量，例如 2 台");
        String date = stringParam(params, "deliveryDate");
        boolean validDate;
        try { LocalDate.parse(date == null ? "" : date); validDate = StringUtils.hasText(rawText) && rawText.contains(date); }
        catch (java.time.format.DateTimeParseException ex) { validDate = false; }
        addPrecondition(preconditions, "DELIVERY_DATE", "交期有效", validDate,
                validDate ? date : "请提供明确的交期");
        addImpact(impacts, "MEDIUM", "创建生产订单草稿，尚未排产或改变库存。");
    }

    /**
     * 数量是否由用户在本条消息里明确说出口。
     *
     * <p>只有「正整数 + 原文里带着这个数字加单位」才算确认，避免模型凭空补一个数量就当用户授权。</p>
     */
    private static boolean isQuantityMentionedInText(BigDecimal quantity, String rawText) {
        if (quantity == null || quantity.signum() <= 0 || !StringUtils.hasText(rawText)) {
            return false;
        }
        if (quantity.stripTrailingZeros().scale() > 0) {
            return false;
        }
        String literal = quantity.stripTrailingZeros().toPlainString();
        return Pattern.compile("(?<![0-9.])" + Pattern.quote(literal) + "\\s*(台|盏|件|个)")
                .matcher(rawText).find();
    }

    private void evaluateRelease(Target target, List<Map<String, Object>> preconditions,
                                 List<Map<String, Object>> impacts, List<String> warnings) {
        MfgWorkOrder order = target.entity() instanceof MfgWorkOrder wo ? wo : null;
        addPrecondition(preconditions, "WORK_ORDER_EXISTS", "工单存在且未删除", order != null,
                describeWorkOrderFound(order, target));
        String status = order == null ? null : order.getStatus();
        addPrecondition(preconditions, "WORK_ORDER_STATUS", "工单状态为 DRAFT 或 APPROVED",
                isDraftOrApproved(status), "当前状态：" + statusText(status));
        addPrecondition(preconditions, "EXECUTION_BASIS", "工单已关联生产计划、物料清单与工艺路线",
                hasExecutionBasis(order), describeExecutionBasis(order));
        MfgProductionPlan plan = loadPlan(order);
        addPrecondition(preconditions, "SOURCE_PLAN_RELEASED", "来源生产计划已生效", isPlanReleased(plan),
                describeSourcePlan(plan));
        boolean windowOk = isPlanWindowValid(plan);
        addPrecondition(preconditions, "PLAN_TIME_WINDOW", "生产计划时间窗仍然有效", windowOk,
                describePlanWindow(plan, windowOk));
        long materialCount = countMaterialRequirements(order);
        addPrecondition(preconditions, "MATERIAL_REQUIREMENT", "工单存在有效用料需求", materialCount > 0,
                "用料需求条数：" + materialCount);
        addReleaseImpacts(impacts, status);
        addApprovedWarning(warnings, status);
    }

    /** 工单是否已关联生产计划、物料清单与工艺路线。 */
    private static boolean hasExecutionBasis(MfgWorkOrder order) {
        if (order == null) {
            return false;
        }
        return order.getPlanId() != null && order.getBomId() != null && order.getRouteId() != null;
    }

    /** 执行依据校验的说明文案。 */
    private static String describeExecutionBasis(MfgWorkOrder order) {
        if (hasExecutionBasis(order)) {
            return "执行依据完整";
        }
        return "缺少生产计划、物料清单或工艺路线";
    }

    /** 来源计划校验的说明文案。 */
    private static String describeSourcePlan(MfgProductionPlan plan) {
        if (plan == null) {
            return "来源生产计划不存在";
        }
        return "计划 " + plan.getPlanNo() + " 状态：" + statusText(plan.getStatus());
    }

    /** 已审批态工单下达时的提示。 */
    private static void addApprovedWarning(List<String> warnings, String status) {
        if (BusinessStatus.Document.APPROVED.equals(status)) {
            warnings.add("该工单当前为 APPROVED，下达后将直接进入可生产状态。");
        }
    }

    /** 工单状态是否为可下达的草稿或已审批态。 */
    private static boolean isDraftOrApproved(String status) {
        return BusinessStatus.Document.DRAFT.equals(status) || BusinessStatus.Document.APPROVED.equals(status);
    }

    /** 按工单关联的计划ID取来源生产计划，取不到返回 null。 */
    private MfgProductionPlan loadPlan(MfgWorkOrder order) {
        if (order == null || order.getPlanId() == null || productionPlanMapper == null) {
            return null;
        }
        return productionPlanMapper.selectOne(new LambdaQueryWrapper<MfgProductionPlan>()
                .eq(MfgProductionPlan::getPlanId, order.getPlanId())
                .eq(MfgProductionPlan::getDeletedFlag, 0).last("limit 1"));
    }

    /** 来源计划是否已生效。 */
    private static boolean isPlanReleased(MfgProductionPlan plan) {
        // 与 MfgWorkOrderServiceImpl.releaseWorkOrder 的口径保持一致：已生效或已开工都算。
        // 只认 RELEASED 会让「组件工单先开工、计划转 RUNNING」之后的整机根工单永远下达不了。
        return plan != null && List.of(BusinessStatus.Document.RELEASED, BusinessStatus.Production.RUNNING)
                .contains(plan.getStatus());
    }

    /**
     * 计划时间窗是否仍然有效。
     * 与 MfgWorkOrderServiceImpl.releaseWorkOrder 的口径保持一致：开始时间不能已过、结束时间不能已到。
     */
    private static boolean isPlanWindowValid(MfgProductionPlan plan) {
        if (plan == null || plan.getPlannedStartTime() == null || plan.getPlannedEndTime() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return !plan.getPlannedStartTime().isBefore(now.minusMinutes(2)) && plan.getPlannedEndTime().isAfter(now);
    }

    /** 计划时间窗的说明文案。 */
    private static String describePlanWindow(MfgProductionPlan plan, boolean windowOk) {
        if (plan == null) {
            return "缺少计划时间";
        }
        return "计划时间 " + fmt(plan.getPlannedStartTime()) + " ~ " + fmt(plan.getPlannedEndTime())
                + (windowOk ? "" : "，已过期，请先重新排程");
    }

    /** 统计工单的有效用料需求条数。 */
    private long countMaterialRequirements(MfgWorkOrder order) {
        if (order == null || workOrderMaterialMapper == null) {
            return 0L;
        }
        return workOrderMaterialMapper.selectCount(new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                .eq(MfgWorkOrderMaterial::getWorkOrderId, order.getWorkOrderId())
                .eq(MfgWorkOrderMaterial::getDeletedFlag, 0));
    }

    /** 下达工单的影响说明。 */
    private static void addReleaseImpacts(List<Map<String, Object>> impacts, String status) {
        addImpact(impacts, "HIGH", "工单状态由 " + statusText(status) + " 变为 RELEASED，之后可开始生产。");
        addImpact(impacts, "MEDIUM", "下达时会冻结物料清单、工艺路线与产品参数快照，后续执行只读快照。");
        addImpact(impacts, "LOW", "操作会写入命令记录与系统操作日志，可在监控页追溯。");
    }

    private void evaluateStart(Target target, List<Map<String, Object>> preconditions,
                               List<Map<String, Object>> impacts, List<String> warnings) {
        MfgWorkOrder order = target.entity() instanceof MfgWorkOrder wo ? wo : null;
        addPrecondition(preconditions, "WORK_ORDER_EXISTS", "工单存在且未删除", order != null,
                describeWorkOrderFound(order, target));
        String status = order == null ? null : order.getStatus();
        addPrecondition(preconditions, "WORK_ORDER_STATUS", "工单状态为 RELEASED",
                BusinessStatus.Document.RELEASED.equals(status), "当前状态：" + statusText(status));
        List<MfgWorkOrderMaterial> requirements = listMaterialRequirements(order);
        boolean kitReady = isKitReady(requirements);
        addPrecondition(preconditions, "MATERIAL_KIT", "用料已齐套（已发数量不少于需求数量）", kitReady,
                describeKit(requirements, kitReady));
        List<MfgTask> tasks = listWorkOrderTasks(order);
        boolean taskReady = isTaskReady(tasks);
        addPrecondition(preconditions, "TASK_READY", "工序任务已生成且全部待开工", taskReady,
                describeTasks(tasks));
        addImpact(impacts, "HIGH", "工单状态由 RELEASED 变为 RUNNING，产线进入生产。");
        addImpact(impacts, "LOW", "操作会写入命令记录与系统操作日志。");
        if (!taskReady) {
            warnings.add("工序任务未就绪时开始生产会被领域服务拒绝。");
        }
    }

    /** 工序任务校验的说明文案。 */
    private static String describeTasks(List<MfgTask> tasks) {
        if (tasks.isEmpty()) {
            return "尚未生成工序任务";
        }
        return "工序任务 " + tasks.size() + " 条";
    }

    /** 工单的用料需求明细。 */
    private List<MfgWorkOrderMaterial> listMaterialRequirements(MfgWorkOrder order) {
        if (order == null || workOrderMaterialMapper == null) {
            return List.of();
        }
        return workOrderMaterialMapper.selectList(new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                .eq(MfgWorkOrderMaterial::getWorkOrderId, order.getWorkOrderId())
                .eq(MfgWorkOrderMaterial::getDeletedFlag, 0));
    }

    /** 用料是否齐套：存在需求且每一项已发数量都不少于需求数量。 */
    private static boolean isKitReady(List<MfgWorkOrderMaterial> requirements) {
        return !requirements.isEmpty() && requirements.stream().allMatch(item ->
                item.getIssuedQuantity() != null && item.getRequiredQuantity() != null
                        && item.getIssuedQuantity().compareTo(item.getRequiredQuantity()) >= 0);
    }

    /** 齐套校验的说明文案。 */
    private static String describeKit(List<MfgWorkOrderMaterial> requirements, boolean kitReady) {
        if (requirements.isEmpty()) {
            return "没有用料需求记录";
        }
        return "共 " + requirements.size() + " 项用料，齐套判断：" + (kitReady ? "通过" : "存在未发足项");
    }

    /** 工单下的工序任务。 */
    private List<MfgTask> listWorkOrderTasks(MfgWorkOrder order) {
        if (order == null || taskMapper == null) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<MfgTask>()
                .eq(MfgTask::getWorkOrderId, order.getWorkOrderId())
                .eq(MfgTask::getDeletedFlag, 0));
    }

    /** 工序任务是否已生成且全部处于待开工状态。 */
    private static boolean isTaskReady(List<MfgTask> tasks) {
        return !tasks.isEmpty() && tasks.stream()
                .allMatch(task -> BusinessStatus.Production.WAIT_START.equals(task.getStatus()));
    }

    private void evaluateMaterialIssue(Target target, Map<String, Object> params,
                                       List<Map<String, Object>> preconditions,
                                       List<Map<String, Object>> impacts, List<String> warnings) {
        MfgWorkOrder order = target.entity() instanceof MfgWorkOrder wo ? wo : null;
        addPrecondition(preconditions, "WORK_ORDER_EXISTS", "工单存在且未删除", order != null,
                describeWorkOrderFound(order, target));
        String status = order == null ? null : order.getStatus();
        addPrecondition(preconditions, "WORK_ORDER_STATUS", "工单状态为 RELEASED 或 RUNNING",
                isReleasedOrRunning(status), "当前状态：" + statusText(status));
        WmsWarehouse warehouse = loadWarehouse(longOf(params.get("warehouseId")));
        boolean warehouseOk = isIssueableWarehouse(warehouse) && !StringUtils.hasText(textOf(params.get("warehouseProblem")))
                && (!StringUtils.hasText(textOf(params.get("warehouseType")))
                    || textOf(params.get("warehouseType")).equalsIgnoreCase(warehouse.getWarehouseType()));
        addPrecondition(preconditions, "WAREHOUSE_VALID", "领料仓库为启用中的原料仓或半成品仓", warehouseOk,
                StringUtils.hasText(textOf(params.get("warehouseProblem")))?textOf(params.get("warehouseProblem")):
                    warehouseOk?describeWarehouse(warehouse):"仓库与指定供给方式不一致或未启用，请重新选择；未改用其他仓库");
        long shortage = countMaterialShortage(order);
        addPrecondition(preconditions, "MATERIAL_SHORTAGE", "存在未领足的用料需求", shortage > 0,
                "未领足用料 " + shortage + " 项");
        // 「还有东西要领」不等于「库存够领」。原来只判前者，导致卡片四项全绿、点确认却必然
        // 被领料单的齐套校验拦下。这里按工单与仓库真实算一遍可分配量，缺多少直接说清。
        String stockGap = validateDomainMaterialAvailability(order, warehouse);
        addPrecondition(preconditions, "MATERIAL_AVAILABLE", "仓库供给份额可按实际领料规则分配",
                stockGap == null, describeStockAvailability(stockGap));
        long submitted = countSubmittedMaterialIssue(order);
        addImpact(impacts, "HIGH", "按工单用料缺口生成一张领料出库单，状态为 SUBMITTED，等待转线边与出库确认。");
        addImpact(impacts, "LOW", "领料单生成时不扣库存；库存组件先完成领用复验，再执行「领料转线边」，扣减所选仓库并形成线边占用。");
        if (submitted > 0) {
            warnings.add("该工单已有 " + submitted + " 张待确认领料单，重复生成会直接复用原单。");
        }
    }

    /** 工单存在性校验的说明文案。 */
    private static String describeWorkOrderFound(MfgWorkOrder order, Target target) {
        if (order == null) {
            return "未找到目标工单 " + target.no();
        }
        return "工单 " + order.getWorkOrderNo() + " 已找到";
    }

    /** 出库单存在性校验的说明文案。 */
    private static String describeOutboundFound(WmsOutboundOrder outbound, Target target) {
        if (outbound == null) {
            return "未找到目标出库单 " + target.no();
        }
        return "出库单 " + outbound.getOutboundNo() + " 已找到";
    }

    /** 入库单存在性校验的说明文案。 */
    private static String describeInboundFound(WmsInboundOrder inbound, Target target) {
        if (inbound == null) {
            return "未找到目标入库单 " + target.no();
        }
        return "入库单 " + inbound.getInboundNo() + " 已找到";
    }

    /** 检验单存在性校验的说明文案。 */
    private static String describeInspectionFound(QmsInspectionOrder inspection, Target target) {
        if (inspection == null) {
            return "未找到目标检验单 " + target.no();
        }
        return "检验单 " + inspection.getInspectionNo() + " 已找到";
    }

    /** 报工单存在性校验的说明文案。 */
    private static String describeReportFound(MfgWorkReport report, Target target) {
        if (report == null) {
            return "未找到目标报工单 " + target.no();
        }
        return "报工单 " + report.getReportNo() + " 已找到";
    }

    /** 库存是否够领的说明文案。 */
    private static String describeStockAvailability(String stockGap) {
        if (stockGap == null) {
            return "本次需要从仓库领取的物料可分配；组件工单供给仍需按实际生产交接";
        }
        return stockGap;
    }

    /** 与实际生成领料单使用相同的批次、库位和预留分配规则。 */
    private String validateDomainMaterialAvailability(MfgWorkOrder order, WmsWarehouse warehouse) {
        if (outboundOrderService == null || order == null || warehouse == null) {
            return "无法核对库存：领料服务或目标数据不可用，请检查后重试";
        }
        try {
            outboundOrderService.validateMaterialIssueAvailability(order.getWorkOrderId(), warehouse.getWarehouseId());
            return null;
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
    }

    /** 工单状态是否为已下达或生产中。 */
    private static boolean isReleasedOrRunning(String status) {
        return BusinessStatus.Document.RELEASED.equals(status) || BusinessStatus.Production.RUNNING.equals(status);
    }

    /** 按主键取仓库，取不到返回 null。 */
    private WmsWarehouse loadWarehouse(Long warehouseId) {
        if (warehouseId == null || warehouseMapper == null) {
            return null;
        }
        return warehouseMapper.selectOne(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseId, warehouseId)
                .eq(WmsWarehouse::getDeletedFlag, 0).last("limit 1"));
    }

    /** 仓库是否可用于领料：启用中，且为原料仓或半成品仓。 */
    private static boolean isIssueableWarehouse(WmsWarehouse warehouse) {
        return warehouse != null
                && BusinessStatus.Common.ENABLE.equals(warehouse.getStatus())
                && ("RAW".equals(warehouse.getWarehouseType()) || "SEMI_FINISHED".equals(warehouse.getWarehouseType()));
    }

    /** 领料仓库校验的说明文案。 */
    private static String describeWarehouse(WmsWarehouse warehouse) {
        if (warehouse == null) {
            return "没有可用的原料仓";
        }
        return "仓库 " + warehouse.getWarehouseName() + " 类型 " + warehouse.getWarehouseType()
                + " 状态 " + statusText(warehouse.getStatus());
    }

    /**
     * 按仓库真实可分配量算本工单还缺哪些料，够则返回 null，缺则返回一句能直接给用户看的话。
     * 口径与领料单生成时保持一致：本工单预留优先，其余看公共可用量且批次必须已质检放行。
     * 可选的源仓库不止单张单据上指定的那一个——领料服务会按原料仓、半成品仓逐仓兜底，
     * 这里也要按同一批仓库合计，否则明明能领的工单会被判成缺料。
     */
    private String describeStockGap(MfgWorkOrder order, WmsWarehouse warehouse, Long warehouseId) {
        if (!canDescribeStockGap(order, warehouse, warehouseId)) {
            return "无法核对库存：工单、仓库或库存数据服务不可用，请检查后重试";
        }
        List<MfgWorkOrderMaterial> requirements = loadStockGapRequirements(order);
        if (requirements.isEmpty()) {
            return "无法核对库存：未查询到工单用料需求，请检查后重试";
        }
        List<String> gaps = collectStockGaps(order, warehouseId, requirements);
        return gaps.isEmpty() ? null
                : warehouse.getWarehouseName() + "及同类型仓库库存不足：" + String.join("；", gaps)
                + "。补料或先生产组件工单后再来生成领料单";
    }

    /** 汇总工单在可选源仓库里的全部缺料说明。 */
    private List<String> collectStockGaps(MfgWorkOrder order, Long warehouseId,
                                          List<MfgWorkOrderMaterial> requirements) {
        List<Long> sourceWarehouseIds = issueableWarehouseIds(warehouseId);
        Map<Long, BigDecimal> reserved = reservedQuantityByMaterial(order.getWorkOrderId());
        List<String> gaps = new ArrayList<>();
        for (MfgWorkOrderMaterial requirement : requirements) {
            String gap = materialGapText(requirement, sourceWarehouseIds, reserved);
            if (gap != null) {
                gaps.add(gap);
            }
        }
        return gaps;
    }

    /** 描述缺料所需的前置依赖是否齐备。 */
    private boolean canDescribeStockGap(MfgWorkOrder order, WmsWarehouse warehouse, Long warehouseId) {
        boolean targetReady = order != null && warehouse != null && warehouseId != null;
        return targetReady && hasStockGapMappers();
    }

    /** 缺料计算依赖的四个 Mapper 是否都注入了（部分场景下按需装载，可能为空）。 */
    private boolean hasStockGapMappers() {
        return workOrderMaterialMapper != null && inventoryMapper != null
                && warehouseMapper != null && supplyAllocationMapper != null;
    }

    /** 工单的有效用料需求。 */
    private List<MfgWorkOrderMaterial> loadStockGapRequirements(MfgWorkOrder order) {
        List<MfgWorkOrderMaterial> requirements = workOrderMaterialMapper.selectList(
                new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                        .eq(MfgWorkOrderMaterial::getWorkOrderId, order.getWorkOrderId())
                        .eq(MfgWorkOrderMaterial::getDeletedFlag, 0));
        if (requirements == null) {
            return List.of();
        }
        return requirements;
    }

    /** 单个物料的缺口说明，不缺返回 null。 */
    private String materialGapText(MfgWorkOrderMaterial requirement, List<Long> sourceWarehouseIds,
                                   Map<Long, BigDecimal> reserved) {
        Long materialId = requirement.getMaterialId();
        if (materialId == null) {
            return null;
        }
        BigDecimal required = zero(requirement.getRequiredQuantity());
        BigDecimal issued = zero(requirement.getIssuedQuantity());
        BigDecimal outstanding = required.subtract(issued);
        return outstanding.signum() <= 0 ? null : missingMaterialText(materialId, outstanding, sourceWarehouseIds, reserved);
    }

    /** 该物料在可选源仓库里的缺口文案，够用返回 null。 */
    private String missingMaterialText(Long materialId, BigDecimal outstanding, List<Long> sourceWarehouseIds,
                                       Map<Long, BigDecimal> reserved) {
        BigDecimal available = reserved.getOrDefault(materialId, BigDecimal.ZERO);
        for (WmsInventory inventory : availableInventories(sourceWarehouseIds, materialId)) {
            available = available.add(zero(inventory.getAvailableQuantity()));
        }
        BigDecimal missing = outstanding.subtract(available);
        return missing.signum() <= 0 ? null
                : "物料 " + materialId + " 缺 " + missing.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** 该物料在可选源仓库里的可用库存明细。 */
    private List<WmsInventory> availableInventories(List<Long> sourceWarehouseIds, Long materialId) {
        List<WmsInventory> inventories = inventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .in(WmsInventory::getWarehouseId, sourceWarehouseIds)
                .eq(WmsInventory::getMaterialId, materialId)
                .gt(WmsInventory::getAvailableQuantity, BigDecimal.ZERO)
                .eq(WmsInventory::getWorkOrderId, 0L)
                .eq(WmsInventory::getDeletedFlag, 0));
        if (inventories == null) {
            return List.of();
        }
        return inventories;
    }

    /** 与领料服务一致的可选源仓库：指定的仓库排最前，其余启用的原料仓与半成品仓兜底。 */
    private List<Long> issueableWarehouseIds(Long preferredWarehouseId) {
        List<Long> ids = new ArrayList<>();
        if (preferredWarehouseId != null) {
            ids.add(preferredWarehouseId);
        }
        if (warehouseMapper == null) {
            return ids;
        }
        List<WmsWarehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .in(WmsWarehouse::getWarehouseType, List.of("RAW", "SEMI_FINISHED"))
                .eq(WmsWarehouse::getStatus, BusinessStatus.Common.ENABLE)
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .orderByAsc(WmsWarehouse::getWarehouseId));
        for (WmsWarehouse item : warehouses == null ? List.<WmsWarehouse>of() : warehouses) {
            if (!ids.contains(item.getWarehouseId())) {
                ids.add(item.getWarehouseId());
            }
        }
        return ids;
    }

    /** 本工单已预留的数量，按物料汇总。 */
    private Map<Long, BigDecimal> reservedQuantityByMaterial(Long workOrderId) {
        if (supplyAllocationMapper == null || workOrderId == null) {
            return Map.of();
        }
        List<MfgSupplyAllocation> allocations = supplyAllocationMapper.selectList(
                new LambdaQueryWrapper<MfgSupplyAllocation>()
                        .eq(MfgSupplyAllocation::getWorkOrderId, workOrderId)
                        .eq(MfgSupplyAllocation::getDeletedFlag, 0));
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (MfgSupplyAllocation allocation : allocations == null ? List.<MfgSupplyAllocation>of() : allocations) {
            if (allocation.getMaterialId() == null || !"RESERVED".equals(allocation.getStatus())) {
                continue;
            }
            result.merge(allocation.getMaterialId(), zero(allocation.getReservedQuantity()), BigDecimal::add);
        }
        return result;
    }

    /** 空值按 0 处理。 */
    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 统计未领足的用料需求条数。 */
    private long countMaterialShortage(MfgWorkOrder order) {
        if (order == null || workOrderMaterialMapper == null) {
            return 0L;
        }
        return workOrderMaterialMapper.selectCount(new LambdaQueryWrapper<MfgWorkOrderMaterial>()
                .eq(MfgWorkOrderMaterial::getWorkOrderId, order.getWorkOrderId())
                .eq(MfgWorkOrderMaterial::getDeletedFlag, 0)
                .apply("issued_quantity < required_quantity"));
    }

    /** 统计该工单已存在的待确认领料单数量。 */
    private long countSubmittedMaterialIssue(MfgWorkOrder order) {
        if (order == null || outboundOrderMapper == null) {
            return 0L;
        }
        return outboundOrderMapper.selectCount(new LambdaQueryWrapper<WmsOutboundOrder>()
                .eq(WmsOutboundOrder::getWorkOrderId, order.getWorkOrderId())
                .eq(WmsOutboundOrder::getOutboundType, "MATERIAL_ISSUE")
                .eq(WmsOutboundOrder::getAuditStatus, BusinessStatus.Document.SUBMITTED)
                .eq(WmsOutboundOrder::getDeletedFlag, 0));
    }

    private void evaluateTransfer(Target target, Map<String, Object> params,
                                  List<Map<String, Object>> preconditions,
                                  List<Map<String, Object>> impacts, List<String> warnings) {
        WmsOutboundOrder outbound = target.entity() instanceof WmsOutboundOrder order ? order : null;
        addPrecondition(preconditions, "OUTBOUND_EXISTS", "领料单存在且未删除", outbound != null,
                describeOutboundFound(outbound, target));
        addPrecondition(preconditions, "OUTBOUND_TYPE", "单据类型为领料出库（MATERIAL_ISSUE）",
                isMaterialIssue(outbound), describeOutboundType(outbound));
        addPrecondition(preconditions, "OUTBOUND_AUDIT", "领料单状态为 SUBMITTED（待确认）",
                isSubmitted(outbound), describeAuditStatus(outbound));
        MfgWorkOrder order = loadRelatedWorkOrder(outbound);
        addPrecondition(preconditions, "WORK_ORDER_STATUS", "关联工单处于 RELEASED 或 RUNNING",
                order != null && isReleasedOrRunning(order.getStatus()), describeRelatedWorkOrder(order));
        WmsLocation location = loadLocation(longOf(params.get("targetLocationId")));
        WmsWarehouse locationWarehouse = loadWarehouse(location == null ? null : location.getWarehouseId());
        addPrecondition(preconditions, "TARGET_LOCATION", "目标库位为线边仓下的正常库位",
                isLineSideLocation(location, locationWarehouse),
                describeTransferTarget(location, locationWarehouse, params));
        if(outbound!=null && location!=null && outboundOrderService!=null) {
            try {
                outboundOrderService.validateLineSideTransfer(outbound.getOutboundId(),location.getLocationId());
                addPrecondition(preconditions,"TRANSFER_ELIGIBILITY","领用检验、上级工单和实际转线边条件通过",true,"按正式业务规则核对，执行时再次校验");
            } catch(RuntimeException error) {
                String reason=error instanceof BusinessException?error.getMessage():"暂时无法检查转线边条件，请稍后重新检查；未执行移库。持续失败时请联系管理员。";
                if(!(error instanceof BusinessException)) LOGGER.warn("Line-side preflight failed for outbound {}",outbound.getOutboundNo(),error);
                addPrecondition(preconditions,"TRANSFER_ELIGIBILITY","领用检验、上级工单和实际转线边条件通过",false,reason);
            }
        } else addPrecondition(preconditions,"TRANSFER_ELIGIBILITY","正式转线边条件可核对",false,"本轮无法核对转线边业务条件，请恢复服务后重新检查，未执行移库");
        addImpact(impacts, "HIGH", "领料单从所选供给仓移库到线边仓，库存地点发生变化，总量不变。");
        addImpact(impacts, "MEDIUM", "移库完成即扣减供给仓可用量、按实际归属工单增加线边占用，并把领料单置为已确认。");
        if (outbound != null && BusinessStatus.Document.APPROVED.equals(outbound.getAuditStatus())) {
            warnings.add("该领料单已确认过，重复执行会按重放处理。");
        }
    }

    /** 出库单是否为生产领料类型。 */
    private static boolean isMaterialIssue(WmsOutboundOrder outbound) {
        return outbound != null && "MATERIAL_ISSUE".equals(outbound.getOutboundType());
    }

    /** 出库单是否为待确认状态。 */
    private static boolean isSubmitted(WmsOutboundOrder outbound) {
        return outbound != null && BusinessStatus.Document.SUBMITTED.equals(outbound.getAuditStatus());
    }

    /** 出库单类型的说明文案。 */
    private static String describeOutboundType(WmsOutboundOrder outbound) {
        return outbound == null ? "无单据类型" : "当前类型：" + outbound.getOutboundType();
    }

    /** 出库单审核状态的说明文案。 */
    private static String describeAuditStatus(WmsOutboundOrder outbound) {
        return outbound == null ? "无审核状态" : "当前状态：" + statusText(outbound.getAuditStatus());
    }

    /** 取出库单关联的工单，取不到返回 null。 */
    private MfgWorkOrder loadRelatedWorkOrder(WmsOutboundOrder outbound) {
        if (outbound == null || outbound.getWorkOrderId() == null || workOrderMapper == null) {
            return null;
        }
        return workOrderMapper.selectOne(new LambdaQueryWrapper<MfgWorkOrder>()
                .eq(MfgWorkOrder::getWorkOrderId, outbound.getWorkOrderId())
                .eq(MfgWorkOrder::getDeletedFlag, 0).last("limit 1"));
    }

    /** 关联工单校验的说明文案。 */
    private static String describeRelatedWorkOrder(MfgWorkOrder order) {
        if (order == null) {
            return "领料单未关联工单";
        }
        return "工单 " + order.getWorkOrderNo() + " 状态：" + statusText(order.getStatus());
    }

    /** 按主键取库位，取不到返回 null。 */
    private WmsLocation loadLocation(Long locationId) {
        if (locationId == null || locationMapper == null) {
            return null;
        }
        return locationMapper.selectOne(new LambdaQueryWrapper<WmsLocation>()
                .eq(WmsLocation::getLocationId, locationId)
                .eq(WmsLocation::getDeletedFlag, 0).last("limit 1"));
    }

    /** 库位是否可用作线边移库目标：正常库位、状态可用，且所属仓库为启用中的线边仓。 */
    private static boolean isLineSideLocation(WmsLocation location, WmsWarehouse locationWarehouse) {
        return isUsableLocation(location) && isEnabledLineSideWarehouse(locationWarehouse);
    }

    /** 库位本身可用：类型是普通库位，状态是空闲/占用/启用之一。 */
    private static boolean isUsableLocation(WmsLocation location) {
        return location != null && "NORMAL".equals(location.getLocationType())
                && List.of("IDLE", "OCCUPIED", BusinessStatus.Common.ENABLE).contains(location.getStatus());
    }

    /** 库位所属仓库是启用中的线边仓。 */
    private static boolean isEnabledLineSideWarehouse(WmsWarehouse locationWarehouse) {
        return locationWarehouse != null
                && BusinessStatus.Common.ENABLE.equals(locationWarehouse.getStatus())
                && "LINE_SIDE".equals(locationWarehouse.getWarehouseType());
    }

    /** 目标库位校验的说明文案。 */
    private static String describeLocation(WmsLocation location, WmsWarehouse locationWarehouse) {
        if (location == null) {
            return "没有可用的线边仓库位";
        }
        return "库位 " + location.getLocationCode() + "（" + location.getLocationType() + "/"
                + location.getStatus() + "），仓库类型 "
                + (locationWarehouse == null ? "未知" : locationWarehouse.getWarehouseType());
    }

    /**
     * 转线边目标库位的说明文案。
     *
     * <p>没带库位不代表现场没有库位：推荐为空往往是因为本工单产线有多个候选，需要人来挑。
     * 这时必须把推荐侧的真实原因说出来，不能统一报「没有可用的线边仓库位」让人照着去建库位。</p>
     */
    private static String describeTransferTarget(WmsLocation location, WmsWarehouse locationWarehouse,
                                                 Map<String, Object> params) {
        String problem = params == null ? null : textOf(params.get("targetLocationProblem"));
        if (!StringUtils.hasText(problem)) {
            return describeLocation(location, locationWarehouse);
        }
        return StringUtils.hasText(textOf(params.get("targetLocationId")))
                ? describeLocation(location, locationWarehouse)
                : "未指定目标库位：" + problem;
    }

    private void evaluateConfirmOutbound(Target target, List<Map<String, Object>> preconditions,
                                         List<Map<String, Object>> impacts, List<String> warnings) {
        WmsOutboundOrder outbound = target.entity() instanceof WmsOutboundOrder order ? order : null;
        addPrecondition(preconditions, "OUTBOUND_EXISTS", "出库单存在且未删除", outbound != null,
                describeOutboundFound(outbound, target));
        addPrecondition(preconditions, "OUTBOUND_AUDIT", "出库单状态为 SUBMITTED（待确认）",
                isSubmitted(outbound), describeAuditStatus(outbound));
        addPrecondition(preconditions, "OUTBOUND_TYPE", "非销售出库单",
                outboundNotSale(outbound), describeOutboundType(outbound));
        // 生产领料必须走「领料转线边」：转线边会同时扣原料仓、加线边占用并把单据置为已确认。
        // 直接用「确认出库」只扣原料仓、不产生线边占用，后续报工会因线边无料而失败，闭环被打断。
        addPrecondition(preconditions, "OUTBOUND_NOT_MATERIAL_ISSUE", "生产领料单请改用「领料转线边」",
                outboundNotMaterialIssue(outbound), describeOutboundIssueHint(outbound));
        addPrecondition(preconditions, "OUTBOUND_WAREHOUSE", "出库仓库已指定",
                outboundHasWarehouse(outbound), describeOutboundWarehouse(outbound));
        long details = countOutboundDetails(outbound);
        addPrecondition(preconditions, "OUTBOUND_DETAIL", "存在有效出库明细", details > 0,
                "明细条数：" + details);
        addImpact(impacts, "HIGH", "确认后按明细扣减库存余额并生成库存流水，该动作不可撤销。");
        addImpact(impacts, "LOW", "操作会写入命令记录与系统操作日志。");
        warnings.add("确认出库会真实扣减库存，请核对明细与数量。");
    }

    /** 出库单是否不是销售出库。 */
    private static boolean outboundNotSale(WmsOutboundOrder outbound) {
        return outbound != null && !"SALE".equals(outbound.getOutboundType());
    }

    /** 出库单是否不是生产领料。 */
    private static boolean outboundNotMaterialIssue(WmsOutboundOrder outbound) {
        return outbound != null && !"MATERIAL_ISSUE".equals(outbound.getOutboundType());
    }

    /** 出库单是否已指定出库仓库。 */
    private static boolean outboundHasWarehouse(WmsOutboundOrder outbound) {
        return outbound != null && outbound.getWarehouseId() != null;
    }

    /** 生产领料提示。 */
    private static String describeOutboundIssueHint(WmsOutboundOrder outbound) {
        if (outbound == null) {
            return "无单据类型";
        }
        return materialIssueHint(outbound);
    }

    /** 出库仓库说明。 */
    private static String describeOutboundWarehouse(WmsOutboundOrder outbound) {
        if (outbound == null) {
            return "缺少出库仓库";
        }
        if (outbound.getWarehouseId() == null) {
            return "缺少出库仓库";
        }
        return "仓库ID：" + outbound.getWarehouseId();
    }

    /** 出库明细条数。 */
    private long countOutboundDetails(WmsOutboundOrder outbound) {
        if (outbound == null) {
            return 0L;
        }
        if (outboundDetailMapper == null) {
            return 0L;
        }
        return outboundDetailMapper.selectCount(new LambdaQueryWrapper<WmsOutboundDetail>()
                .eq(WmsOutboundDetail::getOutboundId, outbound.getOutboundId())
                .eq(WmsOutboundDetail::getDeletedFlag, 0));
    }

    private void evaluateConfirmInbound(Target target, List<Map<String, Object>> preconditions,
                                        List<Map<String, Object>> impacts, List<String> warnings) {
        WmsInboundOrder inbound = target.entity() instanceof WmsInboundOrder order ? order : null;
        addPrecondition(preconditions, "INBOUND_EXISTS", "入库单存在且未删除", inbound != null,
                describeInboundFound(inbound, target));
        addPrecondition(preconditions, "INBOUND_AUDIT", "入库单状态为 SUBMITTED（待确认）",
                inboundSubmitted(inbound), describeInboundAudit(inbound));
        addPrecondition(preconditions, "INBOUND_WAREHOUSE", "入库仓库已指定",
                inboundHasWarehouse(inbound), describeInboundWarehouse(inbound));
        long details = countInboundDetails(inbound);
        addPrecondition(preconditions, "INBOUND_DETAIL", "存在有效入库明细", details > 0,
                "明细条数：" + details);
        addImpact(impacts, "HIGH", "确认后按明细增加库存余额并生成库存流水。");
        addImpact(impacts, "LOW", "操作会写入命令记录与系统操作日志。");
        warnings.add("确认入库会真实增加库存，请核对批次与库位。");
    }

    /** 入库单是否处于待确认状态。 */
    private static boolean inboundSubmitted(WmsInboundOrder inbound) {
        if (inbound == null) {
            return false;
        }
        return BusinessStatus.Document.SUBMITTED.equals(inbound.getAuditStatus());
    }

    /** 入库单是否已指定入库仓库。 */
    private static boolean inboundHasWarehouse(WmsInboundOrder inbound) {
        if (inbound == null) {
            return false;
        }
        return inbound.getWarehouseId() != null;
    }

    /** 入库审核状态说明。 */
    private static String describeInboundAudit(WmsInboundOrder inbound) {
        if (inbound == null) {
            return "无审核状态";
        }
        return "当前状态：" + statusText(inbound.getAuditStatus());
    }

    /** 入库仓库说明。 */
    private static String describeInboundWarehouse(WmsInboundOrder inbound) {
        if (inbound == null) {
            return "缺少入库仓库";
        }
        if (inbound.getWarehouseId() == null) {
            return "缺少入库仓库";
        }
        return "仓库ID：" + inbound.getWarehouseId();
    }

    /** 入库明细条数。 */
    private long countInboundDetails(WmsInboundOrder inbound) {
        if (inbound == null) {
            return 0L;
        }
        if (inboundDetailMapper == null) {
            return 0L;
        }
        return inboundDetailMapper.selectCount(new LambdaQueryWrapper<WmsInboundDetail>()
                .eq(WmsInboundDetail::getInboundId, inbound.getInboundId())
                .eq(WmsInboundDetail::getDeletedFlag, 0));
    }

    private void evaluateInspection(Target target, Map<String, Object> params,
                                    List<Map<String, Object>> preconditions,
                                    List<Map<String, Object>> impacts, List<String> warnings) {
        QmsInspectionOrder inspection = target.entity() instanceof QmsInspectionOrder order ? order : null;
        addPrecondition(preconditions, "INSPECTION_EXISTS", "检验单存在且未删除", inspection != null,
                describeInspectionFound(inspection, target));
        addPrecondition(preconditions, "INSPECTION_STATUS", "检验单状态为 DRAFT",
                isDraftInspection(inspection), describeInspectionStatus(inspection));
        BigDecimal sample = decimalOf(params.get("sampleQuantity"));
        BigDecimal qualified = decimalOf(params.get("qualifiedQuantity"));
        BigDecimal unqualified = decimalOf(params.get("unqualifiedQuantity"));
        boolean quantitiesProvided = sample != null || qualified != null || unqualified != null;
        String result = stringParam(params, "inspectionResult");
        if (quantitiesProvided) {
            addPrecondition(preconditions, "INSPECTION_QUANTITY", "样本数等于合格数加不合格数且样本大于零",
                    isQuantityConsistent(sample, qualified, unqualified),
                    "样本 " + sample + "，合格 " + qualified + "，不合格 " + unqualified);
        } else {
            addPrecondition(preconditions, "INSPECTION_RESULT", "检验结论为 PASS、FAIL 或 CONCESSION",
                    BusinessStatus.isInspectionResult(result), describeResult(result));
        }
        addImpact(impacts, "HIGH", "检验单写入样本与不合格数并提交结论，状态由 DRAFT 变为已完成。");
        addImpact(impacts, "MEDIUM", "不合格比例超过阈值时系统会自动判为 FAIL。");
        if (requiresPieceInspection(inspection)) {
            // 逐件全检的单子 AI 没有逐件实测数据，预览就必须判成不可直接提交，
            // 不能让用户点了确认才在服务端被拦下来。
            addPrecondition(preconditions, "INSPECTION_PIECE_RESULTS", "逐件检验结果已由质检员填报", false,
                    "该方案要求逐件全检，请由质检员逐件填写实际结果后提交。");
            warnings.add("本方案要求逐件全检，请填写每件产品的实际检验结果。");
        } else if (!quantitiesProvided) {
            warnings.add("未提供样本与不合格数时，只按给定结论提交，建议补充数量以保证可追溯。");
        }
    }

    /** 检验单是否处于草稿状态。 */
    private static boolean isDraftInspection(QmsInspectionOrder inspection) {
        return inspection != null && BusinessStatus.Document.DRAFT.equals(inspection.getStatus());
    }

    /** 检验单状态说明。 */
    private static String describeInspectionStatus(QmsInspectionOrder inspection) {
        if (inspection == null) {
            return "无状态";
        }
        return "当前状态：" + statusText(inspection.getStatus());
    }

    /** 样本数是否等于合格数加不合格数，且样本大于零。 */
    private static boolean isQuantityConsistent(BigDecimal sample, BigDecimal qualified, BigDecimal unqualified) {
        if (sample == null || qualified == null || unqualified == null) {
            return false;
        }
        boolean samplePositive = sample.compareTo(BigDecimal.ZERO) > 0;
        boolean qualifiedNonNegative = qualified.compareTo(BigDecimal.ZERO) >= 0;
        boolean unqualifiedNonNegative = unqualified.compareTo(BigDecimal.ZERO) >= 0;
        boolean sumMatches = qualified.add(unqualified).compareTo(sample) == 0;
        return samplePositive && qualifiedNonNegative && unqualifiedNonNegative && sumMatches;
    }

    /** 检验结论说明。 */
    private static String describeResult(String result) {
        if (result == null) {
            return "结论：未给出";
        }
        return "结论：" + result;
    }

    /** 判断检验单绑定的方案里是否存在要求全检的检验项。 */
    private boolean requiresPieceInspection(QmsInspectionOrder inspection) {
        if (inspection == null || inspection.getSchemeId() == null || inspectionSchemeItemMapper == null) {
            return false;
        }
        List<QmsInspectionSchemeItem> rows = inspectionSchemeItemMapper.selectList(
                new LambdaQueryWrapper<QmsInspectionSchemeItem>()
                        .eq(QmsInspectionSchemeItem::getSchemeId, inspection.getSchemeId())
                        .eq(QmsInspectionSchemeItem::getDeletedFlag, 0));
        return rows != null && rows.stream().anyMatch(row -> "ALL".equals(row.getSamplingMode()));
    }

    private void evaluateWorkReport(Target target, Map<String, Object> params,
                                    List<Map<String, Object>> preconditions,
                                    List<Map<String, Object>> impacts, List<String> warnings) {
        MfgWorkReport report = target.entity() instanceof MfgWorkReport entity ? entity : null;
        addPrecondition(preconditions, "REPORT_EXISTS", "报工单存在且未删除", report != null,
                describeReportFound(report, target));
        addPrecondition(preconditions, "REPORT_AUDIT_STATUS", "报工单状态为 SUBMITTED（待审核）",
                isSubmittedReport(report), describeReportAudit(report));
        String auditResult = stringParam(params, "auditResult");
        addPrecondition(preconditions, "AUDIT_RESULT", "审核结论为 APPROVED 或 REJECTED",
                BusinessStatus.isApprovalResult(auditResult), describeResult(auditResult));
        addImpact(impacts, "MEDIUM", "写入报工审核结论，通过后计入完成数量，驳回则退回重报。");
        addImpact(impacts, "LOW", "操作会写入命令记录与系统操作日志。");
    }

    /** 报工单是否处于待审核状态。 */
    private static boolean isSubmittedReport(MfgWorkReport report) {
        return report != null && BusinessStatus.Document.SUBMITTED.equals(report.getAuditStatus());
    }

    /** 报工单审核状态说明。 */
    private static String describeReportAudit(MfgWorkReport report) {
        if (report == null) {
            return "无审核状态";
        }
        return "当前状态：" + statusText(report.getAuditStatus());
    }

    /* ==================== 领域服务调用 ==================== */

    private BizResult callDomain(ActionSpec spec, Target target, Map<String, Object> params,
                                 Long userId, String remark, String idempotencyKey) {
        Long targetId = resolveTargetId(target, params);
        return switch (spec.action()) {
            case ACTION_CREATE_PRODUCTION_ORDER -> {
                if (aiProductionOrderCreation == null) throw new BusinessException(ResultCode.FAILED.getCode(), "订单服务不可用");
                var order = aiProductionOrderCreation.create(targetId, longOf(params.get("customerId")),
                        decimalOf(params.get("orderQuantity")), LocalDate.parse(stringParam(params, "deliveryDate")),
                        userId, idempotencyKey);
                yield new BizResult(order.getProductionOrderNo(), order.getProductionOrderId(),
                        "已创建生产订单草稿 " + order.getProductionOrderNo());
            }
            case ACTION_SCHEDULE_PRODUCTION_ORDER -> {
                if (aiProductionSchedule == null) throw new BusinessException(ResultCode.FAILED.getCode(), "排程服务不可用");
                var plan = aiProductionSchedule.confirm(targetId, longOf(params.get("lineId")),
                        LocalDateTime.parse(stringParam(params, "plannedStartTime")),
                        LocalDateTime.parse(stringParam(params, "plannedEndTime")), userId, idempotencyKey);
                yield new BizResult(plan.getPlanNo(), plan.getPlanId(), "已生成生产计划 " + plan.getPlanNo());
            }
            case ACTION_GENERATE_WORK_ORDER -> {
                Long planId = longOf(params.get("generationPlanId"));
                if (planId == null || workOrderService == null) throw new BusinessException(ResultCode.FAILED.getCode(), "可用计划或工单服务不可用");
                MfgWorkOrder request = new MfgWorkOrder();
                request.setRequestKey(idempotencyKey);
                request.setRemark(remark);
                MfgWorkOrder order = workOrderService.generateWorkOrder(planId, request, userId);
                yield new BizResult(order.getWorkOrderNo(), order.getWorkOrderId(), "已生成根工单 " + order.getWorkOrderNo());
            }
            case ACTION_GENERATE_DISPATCH -> {
                if (aiDispatchService == null) throw new BusinessException(ResultCode.FAILED.getCode(), "派工服务不可用");
                var request = new com.neuedu.ems.entity.mfg.MfgDispatchOrder();
                request.setTeamId(longOf(params.get("dispatchTeamId")));
                request.setRemark(remark);
                var dispatch = aiDispatchService.generateDispatchOrder(targetId, request, userId);
                yield new BizResult(dispatch.getDispatchNo(), dispatch.getDispatchId(), "已生成派工单 " + dispatch.getDispatchNo());
            }
            case ACTION_RELEASE_WORK_ORDER -> releaseWorkOrder(targetId, userId, remark);
            case ACTION_START_WORK_ORDER -> startWorkOrder(targetId, userId);
            case ACTION_GENERATE_MATERIAL_ISSUE -> generateMaterialIssue(targetId, params, userId, remark,
                    idempotencyKey);
            case ACTION_TRANSFER_TO_LINE_SIDE -> transferToLineSide(target, targetId, params, userId, remark,
                    idempotencyKey);
            case ACTION_CONFIRM_OUTBOUND -> confirmOutbound(target, targetId, userId, remark);
            case ACTION_CONFIRM_INBOUND -> confirmInbound(target, targetId, userId, remark);
            case ACTION_SUBMIT_INSPECTION -> submitInspection(target, targetId, params, userId, remark);
            case ACTION_AUDIT_WORK_REPORT -> auditWorkReport(target, targetId, params, userId, remark);
            case ACTION_GENERATE_PRODUCTION_INBOUND ->
                    generateProductionInbound(targetId, params, userId, remark);
            case ACTION_GENERATE_SALES_OUTBOUND ->
                    generateSalesOutbound(targetId, params, userId, remark);
            case ACTION_CREATE_OUTGOING_INSPECTION ->
                    createOutgoingInspection(targetId, userId);
            case ACTION_CONFIRM_SALES_OUTBOUND ->
                    confirmSalesOutbound(targetId, userId, remark);
            case ACTION_RELEASE_DISPATCH -> releaseDispatch(targetId, userId, remark);
            case ACTION_START_TASK -> startTask(target, targetId, userId);
            case ACTION_SUBMIT_WORK_REPORT ->
                    submitWorkReport(target, targetId, params, userId, remark, idempotencyKey);
            default -> throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "不支持的写动作");
        };
    }

    /** 目标主键优先取参数里的 targetId，其次取目标单据自身的主键。 */
    private Long resolveTargetId(Target target, Map<String, Object> params) {
        Long targetId = longOf(params.get("targetId"));
        if (targetId == null) {
            targetId = target.id();
        }
        if (targetId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "目标单据不存在，无法执行");
        }
        return targetId;
    }

    private BizResult startTask(Target target, Long taskId, Long userId) {
        if (aiTaskService == null || !aiTaskService.startTask(taskId, null, userId)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "工序任务开工未生效");
        }
        return new BizResult(target.no(), taskId, "工序任务 " + target.no() + " 已开工");
    }

    /** 下达生产工单。 */
    private BizResult releaseWorkOrder(Long targetId, Long userId, String remark) {
        MfgWorkOrder order = workOrderService.releaseWorkOrder(targetId, userId, remark);
        return new BizResult(order.getWorkOrderNo(), order.getWorkOrderId(),
                "工单 " + order.getWorkOrderNo() + " 已下达");
    }

    /** 开始生产。 */
    private BizResult startWorkOrder(Long targetId, Long userId) {
        MfgWorkOrder order = workOrderService.startWorkOrder(targetId, userId);
        return new BizResult(order.getWorkOrderNo(), order.getWorkOrderId(),
                "工单 " + order.getWorkOrderNo() + " 已开始生产");
    }

    /** 按工单用料缺口生成领料单。 */
    private BizResult generateMaterialIssue(Long targetId, Map<String, Object> params, Long userId,
                                            String remark, String idempotencyKey) {
        Long workOrderId = longOf(params.get("workOrderId"));
        if (workOrderId == null) {
            workOrderId = targetId;
        }
        Long warehouseId = longOf(params.get("warehouseId"));
        if (warehouseId == null) {
            warehouseId = firstIssueableWarehouseId();
        }
        if (warehouseId == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "没有可用的原料仓，无法生成领料单");
        }
        WmsOutboundOrder outbound = outboundOrderService.generateMaterialIssue(
                workOrderId, warehouseId, userId, remark, idempotencyKey);
        return new BizResult(outbound.getOutboundNo(), outbound.getOutboundId(),
                "已生成领料单 " + outbound.getOutboundNo());
    }

    /** 领料单转线边仓。 */
    private BizResult transferToLineSide(Target target, Long targetId, Map<String, Object> params,
                                         Long userId, String remark, String idempotencyKey) {
        Long locationId = longOf(params.get("targetLocationId"));
        if (locationId == null) {
            locationId = firstLineSideLocationId();
        }
        if (locationId == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "没有可用的线边仓库位");
        }
        // 移库被拒绝时业务层直接抛异常，这里不需要再判返回值。
        outboundOrderService.transferMaterialIssueToLineSide(
                targetId, locationId, userId, remark, idempotencyKey);
        return new BizResult(target.no(), targetId, "领料单 " + target.no() + " 已转线边仓");
    }

    /** 确认出库。 */
    private BizResult confirmOutbound(Target target, Long targetId, Long userId, String remark) {
        boolean ok = outboundOrderService.confirmOutboundOrder(targetId, userId, remark);
        if (!ok) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "出库单确认未生效");
        }
        return new BizResult(target.no(), targetId, "出库单 " + target.no() + " 已确认出库");
    }

    /** 确认入库。 */
    private BizResult confirmInbound(Target target, Long targetId, Long userId, String remark) {
        boolean ok = inboundOrderService.confirmInboundOrder(targetId, userId, remark);
        if (!ok) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "入库单确认未生效");
        }
        return new BizResult(target.no(), targetId, "入库单 " + target.no() + " 已确认入库");
    }

    /** 第 7 阶段：把终检合格批次生成生产入库单（待确认）。 */
    private BizResult generateProductionInbound(Long targetId, Map<String, Object> params,
                                                Long userId, String remark) {
        if (inboundOrderService == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "入库服务不可用");
        }
        var order = inboundOrderService.generateProductionInbound(targetId,
                longOf(params.get("warehouseId")), longOf(params.get("locationId")), userId, remark);
        if (order == null || order.getInboundId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "生产入库单生成未生效");
        }
        return new BizResult(order.getInboundNo(), order.getInboundId(),
                "已生成生产入库单 " + order.getInboundNo() + "，确认后才会增加成品库存");
    }

    /** 第 8 阶段：为已完工订单生成销售出库（发货）单。 */
    private BizResult generateSalesOutbound(Long targetId, Map<String, Object> params,
                                            Long userId, String remark) {
        if (salesOutboundService == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "销售出库服务不可用");
        }
        var order = salesOutboundService.generateSalesOutbound(targetId,
                longOf(params.get("customerId")), longOf(params.get("warehouseId")),
                longOf(params.get("locationId")), remark, userId);
        if (order == null || order.getOutboundId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "销售出库单生成未生效");
        }
        return new BizResult(order.getOutboundNo(), order.getOutboundId(),
                "已生成销售出库单 " + order.getOutboundNo() + "，需出货检验合格后确认发货");
    }

    /** 出库单生成出货检验单：检验单落库后返回它的单号。 */
    private BizResult createOutgoingInspection(Long targetId, Long userId) {
        if (salesOutboundService == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "销售出库服务不可用");
        }
        Long inspectionId = salesOutboundService.createOutgoingInspection(targetId, userId);
        if (inspectionId == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "出货检验单生成未生效");
        }
        String inspectionNo = inspectionOrderMapper == null ? null
                : inspectionOrderMapper.selectById(inspectionId) == null ? null
                : inspectionOrderMapper.selectById(inspectionId).getInspectionNo();
        return new BizResult(inspectionNo == null ? "#" + inspectionId : inspectionNo, inspectionId,
                "已生成出货检验单，录入检验结果合格后才能确认发货");
    }

    /** 确认发货：扣减成品库存并回写销售订单已发数量。 */
    private BizResult confirmSalesOutbound(Long targetId, Long userId, String remark) {
        if (salesOutboundService == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "销售出库服务不可用");
        }
        boolean ok = salesOutboundService.confirmSalesOutbound(targetId, userId, remark);
        if (!ok) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "确认发货未生效");
        }
        String outboundNo = outboundOrderMapper == null ? null
                : outboundOrderMapper.selectById(targetId) == null ? null
                : outboundOrderMapper.selectById(targetId).getOutboundNo();
        return new BizResult(outboundNo == null ? "#" + targetId : outboundNo, targetId,
                "已确认发货，成品库存与销售订单已发数量已更新");
    }

    /**
     * 提交报工：按工序任务生成待审核报工单。
     *
     * <p>用料明细由报工准备服务按任务和数量算出来，AI 不能凭空编耗用量；这里只把
     * 用户明确给出的合格/不良数量带过去。</p>
     */
    private BizResult submitWorkReport(Target target, Long targetId, Map<String, Object> params,
                                       Long userId, String remark, String idempotencyKey) {
        if (workReportSubmitService == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "报工服务不可用");
        }
        MfgWorkReport report = new MfgWorkReport();
        report.setTaskId(targetId);
        report.setOperatorId(userId);
        report.setQualifiedQuantity(zeroOf(decimalOf(params.get("qualifiedQuantity"))));
        report.setDefectQuantity(zeroOf(decimalOf(params.get("defectQuantity"))));
        report.setRemark(StringUtils.hasText(remark) ? remark : "AI 工作台报工");
        List<com.neuedu.ems.entity.mfg.MfgReportMaterial> materials =
                reportMaterials(targetId, report.getQualifiedQuantity().add(report.getDefectQuantity()));
        Long reportId = workReportSubmitService.submitWorkReport(report, materials, userId);
        if (reportId == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "报工未生成记录");
        }
        MfgWorkReport saved = workReportMapper == null ? null : workReportMapper.selectById(reportId);
        String reportNo = saved == null || saved.getReportNo() == null ? "#" + reportId : saved.getReportNo();
        return new BizResult(reportNo, reportId,
                "已提交报工 " + reportNo + "，合格 " + plainQuantity(report.getQualifiedQuantity())
                        + "，不良 " + plainQuantity(report.getDefectQuantity()) + "，等待审核");
    }

    /** 按任务和本次总产出量准备批次；准备失败不得静默提交空明细。 */
    private List<com.neuedu.ems.entity.mfg.MfgReportMaterial> reportMaterials(Long taskId, BigDecimal quantity) {
        if (workReportPreparationService == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "报工用料准备服务不可用，请稍后重试");
        }
        var prepared = workReportPreparationService.prepare(taskId, null, quantity);
        if (prepared == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "报工用料准备未返回结果，请稍后重试");
        }
        if (prepared.warnings() != null && prepared.warnings().stream()
                .anyMatch(warning -> warning.contains("缺少可用领料"))) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "报工用料不足，请先补料转线边，再核对实际用料");
        }
        List<com.neuedu.ems.entity.mfg.MfgReportMaterial> materials = new ArrayList<>();
        for (Object line : materialLinesOf(prepared)) {
            com.neuedu.ems.entity.mfg.MfgReportMaterial material =
                    new com.neuedu.ems.entity.mfg.MfgReportMaterial();
            material.setMaterialId(longOf(fieldOf(line, "materialId")));
            material.setBatchId(longOf(fieldOf(line, "batchId")));
            material.setUsedQuantity(decimalOf(fieldOf(line, "suggestedQuantity")));
            material.setTotalUsedQuantity(material.getUsedQuantity());
            if (material.getMaterialId() == null || material.getBatchId() == null || material.getUsedQuantity() == null) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "报工用料批次明细不完整，请核对后重试");
            }
            if (material.getUsedQuantity().signum() > 0) materials.add(material);
        }
        return materials;
    }

    /** 反射取准备结果里的 materials 列表，避免把 VO 结构硬绑到命令服务上。 */
    private static List<?> materialLinesOf(Object prepared) {
        Object value = fieldOf(prepared, "materials");
        return value instanceof List<?> list ? list : List.of();
    }

    /** 反射取 record 或 POJO 的属性，取不到返回 null。 */
    private static Object fieldOf(Object source, String name) {
        if (source == null) {
            return null;
        }
        try {
            java.lang.reflect.Method getter = source.getClass().getMethod(name);
            return getter.invoke(source);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /** 下达派工单：生成工序任务，工单随后才能开工。 */
    private BizResult releaseDispatch(Long targetId, Long userId, String remark) {
        if (aiDispatchService == null) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "派工服务不可用");
        }
        List<MfgTask> tasks = aiDispatchService.releaseDispatchOrder(targetId, userId, remark);
        var dispatch = aiDispatchMapper == null ? null : aiDispatchMapper.selectById(targetId);
        String dispatchNo = dispatch == null || dispatch.getDispatchNo() == null
                ? "#" + targetId : dispatch.getDispatchNo();
        int taskCount = tasks == null ? 0 : tasks.size();
        return new BizResult(dispatchNo, targetId,
                "已下达派工单 " + dispatchNo + "，生成 " + taskCount + " 道工序任务");
    }

    /** 提交检验结果，数量齐全时由领域服务按不合格率自动判定结论。 */
    private BizResult submitInspection(Target target, Long targetId, Map<String, Object> params,
                                       Long userId, String remark) {
        String result = stringParam(params, "inspectionResult");
        BigDecimal sample = decimalOf(params.get("sampleQuantity"));
        BigDecimal qualified = decimalOf(params.get("qualifiedQuantity"));
        BigDecimal unqualified = decimalOf(params.get("unqualifiedQuantity"));
        // 不传逐件结果：方案要求逐件全检时由领域服务拒绝并提示补齐，
        // 这里不能替用户编出每件都合格的实测记录。
        boolean ok = inspectionOrderService.submitInspectionAutoPieces(
                targetId, result, remark, userId, sample, qualified, unqualified, null);
        if (!ok) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(),
                    "检验单提交未生效，请确认状态为 DRAFT 且数量合法");
        }
        return new BizResult(target.no(), targetId, "检验单 " + target.no() + " 已提交检验结果");
    }

    /** 审核报工，结论必须是 APPROVED 或 REJECTED。 */
    private BizResult auditWorkReport(Target target, Long targetId, Map<String, Object> params,
                                      Long userId, String remark) {
        String auditResult = stringParam(params, "auditResult");
        if (!BusinessStatus.isApprovalResult(auditResult)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "审核结论必须是 APPROVED 或 REJECTED");
        }
        boolean ok = workReportService.auditWorkReport(targetId, auditResult, remark, userId);
        if (!ok) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "报工审核未生效");
        }
        return new BizResult(target.no(), targetId, "报工单 " + target.no() + " 已审核为 " + auditResult);
    }

    /** 取字符串参数，缺失时返回 null。 */
    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /* ==================== 辅助 ==================== */

    private void requireAssistant(AuthorizationContext authorization) {
        if (authorization == null || !authorization.hasPermission("ai:assistant:use")) {
            throw new DataScopeAccessDeniedException();
        }
    }

    private void requirePermission(AuthorizationContext authorization, String permission) {
        if (authorization == null || !authorization.hasPermission(permission)) {
            throw new DataScopeAccessDeniedException();
        }
        if ("mfg:schedule-scenario:confirm".equals(permission) && !hasConfirmScenarioCompanions(authorization)) {
            throw new DataScopeAccessDeniedException();
        }
    }

    /**
     * 确认排程还要求同时具备查看、新建、修改排程以及查看生产订单的权限。
     *
     * <p>确认动作会把结果落成正式计划，只有单独一个 confirm 权限不足以承担这个后果。</p>
     */
    private static boolean hasConfirmScenarioCompanions(AuthorizationContext authorization) {
        return authorization.hasPermission("mfg:schedule-scenario:view")
                && authorization.hasPermission("mfg:schedule-scenario:create")
                && authorization.hasPermission("mfg:schedule-scenario:update")
                && authorization.hasPermission("mfg:production-order:view");
    }

    private Long firstIssueableWarehouseId() {
        if (warehouseMapper == null) {
            return null;
        }
        List<WmsWarehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getStatus, BusinessStatus.Common.ENABLE)
                .in(WmsWarehouse::getWarehouseType, List.of("RAW", "SEMI_FINISHED"))
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .orderByAsc(WmsWarehouse::getWarehouseId));
        if (warehouses.isEmpty()) {
            return null;
        }
        return warehouses.stream()
                .filter(item -> "RAW".equals(item.getWarehouseType()))
                .map(WmsWarehouse::getWarehouseId)
                .findFirst()
                .orElse(warehouses.get(0).getWarehouseId());
    }

    private Long firstLineSideLocationId() {
        if (warehouseMapper == null || locationMapper == null) {
            return null;
        }
        List<WmsWarehouse> lineSide = warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getStatus, BusinessStatus.Common.ENABLE)
                .eq(WmsWarehouse::getWarehouseType, "LINE_SIDE")
                .eq(WmsWarehouse::getDeletedFlag, 0));
        if (lineSide.isEmpty()) {
            return null;
        }
        List<Long> warehouseIds = lineSide.stream().map(WmsWarehouse::getWarehouseId).toList();
        List<WmsLocation> locations = locationMapper.selectList(new LambdaQueryWrapper<WmsLocation>()
                .in(WmsLocation::getWarehouseId, warehouseIds)
                .eq(WmsLocation::getLocationType, "NORMAL")
                .in(WmsLocation::getStatus, List.of("IDLE", "OCCUPIED", BusinessStatus.Common.ENABLE))
                .eq(WmsLocation::getDeletedFlag, 0)
                .orderByAsc(WmsLocation::getLocationId));
        return locations.isEmpty() ? null : locations.get(0).getLocationId();
    }

    /** 第 7、8 阶段的成品仓：唯一一个启用的 FINISHED 类型仓库。 */
    private Long firstFinishedWarehouseId() {
        if (warehouseMapper == null) {
            return null;
        }
        List<WmsWarehouse> warehouses = warehouseMapper.selectList(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getStatus, BusinessStatus.Common.ENABLE)
                .eq(WmsWarehouse::getWarehouseType, "FINISHED")
                .eq(WmsWarehouse::getDeletedFlag, 0)
                .orderByAsc(WmsWarehouse::getWarehouseId));
        return warehouses.isEmpty() ? null : warehouses.get(0).getWarehouseId();
    }

    /** 成品仓里第一个可用库位；仓库为空时直接返回 null。 */
    private Long firstFinishedLocationId(Long warehouseId) {
        if (warehouseId == null || locationMapper == null) {
            return null;
        }
        List<WmsLocation> locations = locationMapper.selectList(new LambdaQueryWrapper<WmsLocation>()
                .eq(WmsLocation::getWarehouseId, warehouseId)
                .eq(WmsLocation::getLocationType, "NORMAL")
                .in(WmsLocation::getStatus, List.of("IDLE", "OCCUPIED", BusinessStatus.Common.ENABLE))
                .eq(WmsLocation::getDeletedFlag, 0)
                .orderByAsc(WmsLocation::getLocationId));
        return locations.isEmpty() ? null : locations.get(0).getLocationId();
    }

    /** 取出命令参数：优先用预览体里的 params，取不到再退回 params_json。 */
    private Map<String, Object> paramsOf(AiCommandExecution record) {
        Map<String, Object> fromPreview = paramsFromPreviewJson(record.getPreviewJson());
        if (fromPreview != null) {
            return fromPreview;
        }
        Map<String, Object> fromParams = readJsonMapQuietly(record.getParamsJson());
        // 落到空参数时摘要校验必然失败，属于安全兜底。
        return fromParams == null ? new LinkedHashMap<>() : fromParams;
    }

    /** 从预览体里取 params 字段，预览体缺失或损坏时返回 null。 */
    private Map<String, Object> paramsFromPreviewJson(String previewJson) {
        Map<String, Object> preview = readJsonMapQuietly(previewJson);
        if (preview == null) {
            return null;
        }
        if (!(preview.get("params") instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /** 宽松地把 JSON 文本读成 Map，文本为空或解析失败时返回 null。 */
    private Map<String, Object> readJsonMapQuietly(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return mapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception ignored) {
            return null;
        }
    }

    private String hashOf(Map<String, Object> params) {
        try {
            return sha256(mapper().writeValueAsString(canonicalize(params)));
        } catch (Exception e) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "命令参数摘要计算失败");
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "摘要算法不可用");
        }
    }

    /**
     * 命令记录状态变更：每次都用独立事务提交，业务段回滚不会带走审计状态。
     */
    private void mutate(AiCommandExecution record, java.util.function.Consumer<AiCommandExecution> action) {
        action.accept(record);
        if (record.getVersion() == null) {
            record.setVersion(0);
        }
        inNewTransaction(() -> {
            if (executionMapper != null) {
                executionMapper.updateById(record);
            }
        });
        // 乐观锁插件在 updateById 成功后已把实体 version 自增，这里不能再手动加一次。
    }

    private void markFailed(AiCommandExecution record, String message, long startedAt) {
        String readable = message == null ? "执行失败" : message;
        if (readable.length() > 500) {
            readable = readable.substring(0, 500);
        }
        String finalMessage = readable;
        mutate(record, item -> {
            item.setStatus(STATUS_FAILED);
            item.setErrorMessage(finalMessage);
            item.setElapsedMs(System.currentTimeMillis() - startedAt);
            item.setExecutedAt(LocalDateTime.now());
        });
    }

    private AiCommandStep startStep(Long commandId, int stepNo, String capability, String digest,
                                    String bizType, String bizNo, Long bizId) {
        AiCommandStep step = new AiCommandStep();
        step.setCommandId(commandId);
        step.setStepNo(stepNo);
        step.setCapability(capability);
        step.setInputDigest(truncate(digest, 500));
        step.setStatus(STEP_RUNNING);
        step.setBizType(bizType);
        step.setBizNo(bizNo);
        step.setBizId(bizId);
        step.setStartedAt(LocalDateTime.now());
        step.setVersion(0);
        step.setDeletedFlag(0);
        if (stepMapper != null) {
            stepMapper.insert(step);
        }
        return step;
    }

    private void finishStep(AiCommandStep step, String status, String errorMessage) {
        LocalDateTime endedAt = LocalDateTime.now();
        step.setStatus(status);
        step.setEndedAt(endedAt);
        step.setErrorMessage(truncate(errorMessage, 500));
        if (step.getStartedAt() == null) {
            step.setElapsedMs(null);
        } else {
            step.setElapsedMs(Duration.between(step.getStartedAt(), endedAt).toMillis());
        }
        if (stepMapper != null && step.getStepId() != null) {
            stepMapper.updateById(step);
        }
    }

    private List<Map<String, Object>> listSteps(Long commandId) {
        if (stepMapper == null || commandId == null) {
            return List.of();
        }
        List<AiCommandStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AiCommandStep>()
                .eq(AiCommandStep::getCommandId, commandId)
                .eq(AiCommandStep::getDeletedFlag, 0)
                .orderByAsc(AiCommandStep::getStepNo));
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiCommandStep step : steps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stepId", step.getStepId());
            row.put("stepNo", step.getStepNo());
            row.put("capability", step.getCapability());
            row.put("inputDigest", step.getInputDigest());
            row.put("status", step.getStatus());
            row.put("bizType", step.getBizType());
            row.put("bizNo", step.getBizNo());
            row.put("bizId", step.getBizId());
            row.put("errorMessage", step.getErrorMessage());
            row.put("startedAt", step.getStartedAt() == null ? null : step.getStartedAt().format(ISO));
            row.put("endedAt", step.getEndedAt() == null ? null : step.getEndedAt().format(ISO));
            row.put("elapsedMs", step.getElapsedMs());
            list.add(row);
        }
        return list;
    }

    private Map<String, Object> replayResult(AiCommandExecution record) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("commandId", record.getCommandId());
        response.put("confirmationId", record.getConfirmationId());
        response.put("action", record.getAction());
        response.put("actionLabel", record.getActionLabel());
        response.put("targetType", record.getTargetType());
        response.put("targetNo", record.getTargetNo());
        response.put("targetId", record.getTargetId());
        response.put("status", record.getStatus());
        response.put("replayed", true);
        response.put("result", parseJsonMap(record.getResultJson()));
        response.put("elapsedMs", record.getElapsedMs());
        response.put("executedAt", record.getExecutedAt() == null ? null : record.getExecutedAt().format(ISO));
        response.put("steps", listSteps(record.getCommandId()));
        return response;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return mapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void writeOperationLog(AiCommandExecution record, Long userId, String outcome) {
        if (operationLogMapper == null) {
            return;
        }
        String remark = "AI命令[" + record.getActionLabel() + "] 目标 " + record.getTargetNo()
                + " 结果：" + outcome;
        if (remark.length() > 500) {
            remark = remark.substring(0, 500);
        }
        SysOperationLog log = new SysOperationLog();
        log.setUserId(userId);
        log.setOperationType("AI_COMMAND_EXECUTE");
        log.setModuleCode("ai_agent");
        log.setBizId(record.getCommandId());
        log.setOperationTime(LocalDateTime.now());
        log.setRemark(remark);
        log.setVersion(0);
        log.setDeletedFlag(0);
        operationLogMapper.insert(log);
    }

    /**
     * 业务执行段包一层事务：领域服务失败时业务改动回滚，命令记录与步骤在事务外已落库。
     */
    private BizResult inTransaction(java.util.function.Supplier<BizResult> supplier) {
        if (transactionManager == null) {
            return supplier.get();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template.execute(status -> supplier.get());
    }

    /**
     * 命令记录状态更新使用独立事务，保证业务失败时失败原因仍然落库。
     */
    private void inNewTransaction(Runnable action) {
        if (transactionManager == null) {
            action.run();
            return;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.execute(status -> {
            action.run();
            return null;
        });
    }

    private ObjectMapper mapper() {
        return objectMapper == null ? FALLBACK_MAPPER : objectMapper;
    }

    private String toJson(Object value) {
        try {
            return mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.FAILED.getCode(), "命令数据序列化失败");
        }
    }

    private static long count(List<AiCommandExecution> items, String status) {
        return items.stream().filter(item -> status.equals(item.getStatus())).count();
    }

    private static String nullToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static String materialIssueHint(WmsOutboundOrder outbound) {
        if ("MATERIAL_ISSUE".equals(outbound.getOutboundType())) {
            return "该单是生产领料，直接确认出库不会形成线边占用，请改用「领料转线边」";
        }
        return "非生产领料单，可直接确认出库";
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return parseLongQuietly(String.valueOf(value).trim());
    }

    /** null 当 0 处理的数量取值。 */
    private static BigDecimal zeroOf(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 数量去掉无意义的尾零再显示，避免出现 2.0000 这种。 */
    private static String plainQuantity(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    private static BigDecimal decimalOf(Object value) {
        BigDecimal direct = directDecimal(value);
        if (direct != null || value == null) {
            return direct;
        }
        return parseDecimalOrNull(String.valueOf(value).trim());
    }

    /** 已是数字类型的直接取值，否则返回 null。 */
    private static BigDecimal directDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }

    /** 转 Long，转不了返回 null。 */
    private static Long parseLongQuietly(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 转 BigDecimal，转不了返回 null。 */
    private static BigDecimal parseDecimalOrNull(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String statusText(String status) {
        if (!StringUtils.hasText(status)) {
            return "未知";
        }
        return switch (status) {
            case BusinessStatus.Document.DRAFT -> "DRAFT（草稿）";
            case BusinessStatus.Document.SUBMITTED -> "SUBMITTED（待确认）";
            case BusinessStatus.Document.APPROVED -> "APPROVED（已确认）";
            case BusinessStatus.Document.RELEASED -> "RELEASED（已下达）";
            case BusinessStatus.Production.RUNNING -> "RUNNING（生产中）";
            case BusinessStatus.Production.FINISHED -> "FINISHED（已完工）";
            case BusinessStatus.Common.ENABLE -> "ENABLE（启用）";
            default -> status;
        };
    }

    private static String fmt(LocalDateTime value) {
        return value == null ? "未设置" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static void addImpact(List<Map<String, Object>> impacts, String level, String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("level", level);
        item.put("text", text);
        impacts.add(item);
    }

    private static void addPrecondition(List<Map<String, Object>> preconditions, String code, String label,
                                        boolean passed, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", label);
        item.put("passed", passed);
        item.put("detail", detail);
        preconditions.add(item);
    }

    private static Map<String, ActionSpec> buildSpecIndex() {
        Map<String, ActionSpec> index = new LinkedHashMap<>();
        SPECS.forEach(spec -> index.put(spec.action(), spec));
        return Map.copyOf(index);
    }

    private record ActionSpec(String action, String label, String targetType, String permission,
                              String description, String example) {
    }

    private record Target(String type, String no, Long id, Object entity) {
    }

    private record BizResult(String bizNo, Long bizId, String summary) {
    }
}
