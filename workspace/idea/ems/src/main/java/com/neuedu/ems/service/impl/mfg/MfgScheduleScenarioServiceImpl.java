package com.neuedu.ems.service.impl.mfg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.common.AiResponse;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.mdm.MdmProduct;
import com.neuedu.ems.entity.mfg.MfgLineCalendarRule;
import com.neuedu.ems.entity.mfg.MfgLineProductCapability;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgScheduleItem;
import com.neuedu.ems.entity.mfg.MfgScheduleScenario;
import com.neuedu.ems.entity.mfg.MfgWorkOrder;
import com.neuedu.ems.entity.org.OrgShift;
import com.neuedu.ems.entity.process.ProcessRoute;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.entity.sys.SysOperationLog;
import com.neuedu.ems.entity.sys.SysStatusChangeLog;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.factory.FactoryLineMapper;
import com.neuedu.ems.mapper.mfg.MfgLineCalendarRuleMapper;
import com.neuedu.ems.mapper.mfg.MfgLineProductCapabilityMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.mfg.MfgScheduleItemMapper;
import com.neuedu.ems.mapper.mfg.MfgScheduleScenarioMapper;
import com.neuedu.ems.mapper.mfg.MfgWorkOrderMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.mapper.org.OrgShiftMapper;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import com.neuedu.ems.mapper.process.ProcessRouteMapper;
import com.neuedu.ems.mapper.sys.SysOperationLogMapper;
import com.neuedu.ems.mapper.sys.SysStatusChangeLogMapper;
import com.neuedu.ems.service.ai.AiService;
import com.neuedu.ems.service.mfg.MfgScheduleScenarioService;
import com.neuedu.ems.vo.mfg.ManualScheduleBoard;
import com.neuedu.ems.vo.mfg.ManualScheduleBoardItem;
import com.neuedu.ems.vo.mfg.ManualScheduleBoardWorkingWindow;
import com.neuedu.ems.vo.mfg.ManualScheduleCandidateLine;
import com.neuedu.ems.vo.mfg.ManualScheduleDurationPreview;
import com.neuedu.ems.vo.mfg.ScheduleSimulationComparison;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 第一版可解释的确定性排程服务。
 *
 * <p>产线窗口使用日产能作为基准容量；产品能力系数将订单数量换算为基准容量消耗，
 * 周历能力系数则折算每日窗口容量。普通路线保留现有日产能模型；含批次设备的路线还要
 * 逐道排入完整装配件与连续批次窗口，负荷按单件工时及批次时长分别计算。</p>
 */
@Service
public class MfgScheduleScenarioServiceImpl
        extends ServiceImpl<MfgScheduleScenarioMapper, MfgScheduleScenario>
        implements MfgScheduleScenarioService {
    @Autowired private com.neuedu.ems.service.sys.BusinessNumberService numbers;


    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_CALCULATED = "CALCULATED";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_RELEASED = "RELEASED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_FINISHED = "FINISHED";
    private static final String STATUS_ENABLE = "ENABLE";
    private static final String STATUS_RUNNING = "RUNNING";
    /** 会占用产线产能的计划状态：草稿与已下达都算占用，在制（RUNNING）正在占着设备，同样不能漏。 */
    private static final List<String> OCCUPYING_PLAN_STATUSES =
            List.of(STATUS_DRAFT, STATUS_RELEASED, STATUS_RUNNING);
    private static final String ITEM_STATUS_SCHEDULED = "SCHEDULED";
    private static final String ITEM_STATUS_CONFLICT = "CONFLICT";
    private static final String DECISION_SOURCE_RULE_ENGINE = "RULE_ENGINE";
    private static final String DECISION_SOURCE_MANUAL = "MANUAL";
    private static final String ALGORITHM_CODE = "RULE_V1";
    private static final String ALGORITHM_CODE_MANUAL_BOARD = "MANUAL_BOARD";
    private static final String AI_BATCH_SCENARIO_PREFIX = "AI-BATCH-";
    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int CAPACITY_SCALE = 8;
    private static final long MANUAL_CANDIDATE_CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int MANUAL_CANDIDATE_CACHE_MAX_SIZE = 2048;
    private static final int MANUAL_BOARD_AI_ADVICE_ORDER_LIMIT = 12;
    private static final int MANUAL_BOARD_AI_ADVICE_LINE_LIMIT = 3;
    /** 推算结束时间时向后搜索的最大天数；超过说明这笔订单短期排不下。 */
    private static final int MAX_PREVIEW_DAYS = 60;
    private static final DateTimeFormatter MANUAL_BOARD_AI_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 计划号被占用时最多尝试的后缀编号。 */
    private static final int MAX_PLAN_NO_SUFFIX = 99;
    private static final DateTimeFormatter PLAN_NO_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final Map<ManualCandidateGroupKey, ManualCandidateListCacheEntry> manualCandidateListCache =
            new ConcurrentHashMap<>();

    @Autowired
    private MfgScheduleItemMapper mfgScheduleItemMapper;

    @Autowired
    private MfgProductionOrderMapper mfgProductionOrderMapper;

    @Autowired
    private MfgProductionPlanMapper mfgProductionPlanMapper;

    @Autowired
    private SysStatusChangeLogMapper sysStatusChangeLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private AiService aiService;

    @Autowired
    private MfgLineProductCapabilityMapper mfgLineProductCapabilityMapper;

    @Autowired
    private MfgLineCalendarRuleMapper mfgLineCalendarRuleMapper;

    @Autowired
    private FactoryLineMapper factoryLineMapper;

    @Autowired
    private ProcessRouteMapper processRouteMapper;

    @Autowired
    private ProcessRouteItemMapper processRouteItemMapper;

    @Autowired
    private OrgShiftMapper orgShiftMapper;

    @Autowired
    private MfgWorkOrderMapper mfgWorkOrderMapper;

    @Autowired
    private MdmProductMapper mdmProductMapper;

    @Override
    public Page<MfgScheduleScenario> pageScenarios(Integer current, Integer size, String keyword, String status) {
        Page<MfgScheduleScenario> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildScenarioQuery(keyword, status));
    }

    @Override
    public MfgScheduleScenario getScenarioById(Long scenarioId) {
        if (scenarioId == null) {
            return null;
        }
        QueryWrapper<MfgScheduleScenario> wrapper = new QueryWrapper<>();
        wrapper.eq("scenario_id", scenarioId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public List<MfgScheduleItem> listScenarioItems(Long scenarioId) {
        if (scenarioId == null || getScenarioById(scenarioId) == null) {
            throw notFound("排程方案不存在或已删除");
        }
        QueryWrapper<MfgScheduleItem> wrapper = new QueryWrapper<>();
        wrapper.eq("scenario_id", scenarioId);
        wrapper.eq("deleted_flag", 0);
        wrapper.orderByAsc("sequence_no");
        wrapper.orderByAsc("schedule_item_id");
        return mfgScheduleItemMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleScenario openManualDraft(Long operatorId) {
        if (operatorId == null) {
            throw invalid("当前登录用户不能为空");
        }
        QueryWrapper<MfgScheduleScenario> query = new QueryWrapper<>();
        query.eq("deleted_flag", 0)
                .eq("algorithm_code", ALGORITHM_CODE_MANUAL_BOARD)
                .eq("created_by", operatorId)
                .in("status", STATUS_DRAFT, STATUS_CALCULATED)
                .orderByDesc("order_count")
                .orderByDesc("updated_time")
                .orderByDesc("scenario_id");
        List<MfgScheduleScenario> existingDrafts = baseMapper.selectList(query);
        for (MfgScheduleScenario existingDraft : existingDrafts) {
            if (!isAiBatchScenario(existingDraft)) {
                return existingDraft;
            }
        }

        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        MfgScheduleScenario scenario = new MfgScheduleScenario();
        scenario.setScenarioNo(numbers.next("SC",null));
        scenario.setScenarioName("手工编排草案");
        scenario.setHorizonStart(now.toLocalDate().atStartOfDay());
        scenario.setHorizonEnd(now.toLocalDate().plusDays(30).atTime(23, 59, 59));
        scenario.setAlgorithmCode(ALGORITHM_CODE_MANUAL_BOARD);
        scenario.setStatus(STATUS_DRAFT);
        scenario.setOrderCount(0);
        scenario.setScheduledOrderCount(0);
        scenario.setConflictOrderCount(0);
        scenario.setLateOrderCount(0);
        scenario.setTotalLoadMinutes(BigDecimal.ZERO);
        scenario.setVersion(0);
        scenario.setCreatedBy(operatorId);
        scenario.setCreatedTime(now);
        scenario.setUpdatedBy(operatorId);
        scenario.setUpdatedTime(now);
        scenario.setDeletedFlag(0);
        if (baseMapper.insert(scenario) == 0 || scenario.getScenarioId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }
        insertOperationLog(scenario.getScenarioId(), "OPEN_MANUAL_SCHEDULE_DRAFT", "mfg_schedule_scenario",
                operatorId, "创建手工编排草案", now);
        return scenario;
    }

    @Override
    public ManualScheduleBoard getManualScheduleBoard(Long operatorId) {
        if (operatorId == null) {
            throw invalid("当前登录用户不能为空");
        }
        List<MfgScheduleScenario> scenarios = listEditableManualScenarios();
        Map<Long, MfgScheduleScenario> scenarioMap = scenarios.stream()
                .collect(Collectors.toMap(MfgScheduleScenario::getScenarioId, item -> item));
        MfgScheduleScenario workspaceDraft = findWorkspaceDraft(scenarios, operatorId);
        List<MfgScheduleItem> manualItems = listEditableManualItems(scenarioMap.keySet());
        MfgScheduleScenario activeAiBatch = findActiveAiBatch(scenarios, operatorId, manualItems);
        List<MfgProductionPlan> allPlans = listAllPlans();
        List<MfgProductionPlan> visiblePlans = visiblePlans(allPlans);
        List<MfgScheduleItem> visibleManualItems = visibleManualItems(manualItems, visiblePlans);
        Map<Long, MfgProductionOrder> orderMap = loadBoardOrders(visibleManualItems, allPlans);
        List<ManualScheduleBoardItem> timelineItems = buildTimelineItems(visibleManualItems, visiblePlans, scenarioMap, orderMap);
        LocalDateTime horizonStart = resolveHorizonStart(timelineItems, workspaceDraft);
        LocalDateTime horizonEnd = resolveHorizonEnd(timelineItems, workspaceDraft, horizonStart);
        ManualScheduleBoard board = new ManualScheduleBoard();
        board.setWorkspaceDraft(workspaceDraft);
        board.setActiveAiBatch(activeAiBatch);
        board.setTimelineItems(timelineItems);
        board.setWorkingWindows(buildManualBoardWorkingWindows(horizonStart, horizonEnd));
        board.setPendingOrders(buildPendingOrders(allPlans));
        board.setHorizonStart(horizonStart);
        board.setHorizonEnd(horizonEnd);
        fillBoardCounts(board, visibleManualItems, visiblePlans, scenarioMap, timelineItems);
        return board;
    }

    /** 当前用户的手工编排工作区草案（排除 AI 批次草案）。 */
    private MfgScheduleScenario findWorkspaceDraft(List<MfgScheduleScenario> scenarios, Long operatorId) {
        return scenarios.stream()
                .filter(item -> Objects.equals(item.getCreatedBy(), operatorId))
                .filter(item -> !isAiBatchScenario(item))
                .findFirst()
                .orElse(null);
    }

    /** 当前用户仍在编辑中的 AI 一键排产草案。 */
    private MfgScheduleScenario findActiveAiBatch(List<MfgScheduleScenario> scenarios, Long operatorId,
                                                  List<MfgScheduleItem> manualItems) {
        Set<Long> scenarioIdsWithEditableItems = manualItems.stream()
                .map(MfgScheduleItem::getScenarioId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return scenarios.stream()
                .filter(item -> Objects.equals(item.getCreatedBy(), operatorId))
                .filter(this::isAiBatchScenario)
                .filter(item -> scenarioIdsWithEditableItems.contains(item.getScenarioId()))
                .findFirst()
                .orElse(null);
    }

    /** 读取全部未删除的生产计划。 */
    private List<MfgProductionPlan> listAllPlans() {
        QueryWrapper<MfgProductionPlan> planQuery = new QueryWrapper<>();
        planQuery.eq("deleted_flag", 0);
        return mfgProductionPlanMapper.selectList(planQuery);
    }

    /** 看板展示未关闭的各执行阶段计划，生产中与已完成计划不能从排程总览消失。 */
    private List<MfgProductionPlan> visiblePlans(List<MfgProductionPlan> allPlans) {
        return allPlans.stream()
                .filter(item -> item.getStatus()!=null && List.of(STATUS_DRAFT, STATUS_RELEASED, STATUS_RUNNING, "FINISHED").contains(item.getStatus()))
                .toList();
    }

    /** 同一订单已有正式生产计划时，旧的手工排程明细不再展示。 */
    private List<MfgScheduleItem> visibleManualItems(List<MfgScheduleItem> manualItems,
                                                     List<MfgProductionPlan> visiblePlans) {
        Set<Long> visiblePlanOrderIds = visiblePlans.stream().map(MfgProductionPlan::getProductionOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        // A formal production plan is authoritative if legacy or stale manual data still exists for the same order.
        return manualItems.stream()
                .filter(item -> !visiblePlanOrderIds.contains(item.getProductionOrderId()))
                .toList();
    }

    /** 一次性加载看板涉及的生产订单。 */
    private Map<Long, MfgProductionOrder> loadBoardOrders(List<MfgScheduleItem> visibleManualItems,
                                                          List<MfgProductionPlan> allPlans) {
        Set<Long> orderIds = new HashSet<>();
        visibleManualItems.stream().map(MfgScheduleItem::getProductionOrderId)
                .filter(Objects::nonNull).forEach(orderIds::add);
        allPlans.stream().map(MfgProductionPlan::getProductionOrderId)
                .filter(Objects::nonNull).forEach(orderIds::add);
        return loadProductionOrders(orderIds);
    }

    /** 合并手工排程明细与正式计划，按开始时间排序成时间轴。 */
    private List<ManualScheduleBoardItem> buildTimelineItems(List<MfgScheduleItem> visibleManualItems,
                                                             List<MfgProductionPlan> visiblePlans,
                                                             Map<Long, MfgScheduleScenario> scenarioMap,
                                                             Map<Long, MfgProductionOrder> orderMap) {
        List<ManualScheduleBoardItem> timelineItems = new ArrayList<>();
        for (MfgScheduleItem item : visibleManualItems) {
            MfgScheduleScenario scenario = scenarioMap.get(item.getScenarioId());
            timelineItems.add(toManualBoardItem(item, scenario, orderMap.get(item.getProductionOrderId())));
        }
        for (MfgProductionPlan plan : visiblePlans) {
            timelineItems.add(toProductionPlanBoardItem(plan, orderMap.get(plan.getProductionOrderId())));
        }
        timelineItems.sort(Comparator
                .comparing(ManualScheduleBoardItem::getPlannedStartTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ManualScheduleBoardItem::getSourceType)
                .thenComparing(item -> item.getScheduleItemId() == null ? Long.MAX_VALUE : item.getScheduleItemId()));
        return timelineItems;
    }

    /** 看板起点取最早排程开始时间，没有排程时退回工作区草案或今天。 */
    private LocalDateTime resolveHorizonStart(List<ManualScheduleBoardItem> timelineItems,
                                              MfgScheduleScenario workspaceDraft) {
        return timelineItems.stream().map(ManualScheduleBoardItem::getPlannedStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo)
                .orElse(workspaceDraft == null ? LocalDate.now(SCHEDULE_ZONE).atStartOfDay()
                        : workspaceDraft.getHorizonStart());
    }

    /** 看板终点取最晚排程结束时间，没有排程时退回工作区草案或起点后 30 天。 */
    private LocalDateTime resolveHorizonEnd(List<ManualScheduleBoardItem> timelineItems,
                                            MfgScheduleScenario workspaceDraft, LocalDateTime horizonStart) {
        return timelineItems.stream().map(ManualScheduleBoardItem::getPlannedEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo)
                .orElse(workspaceDraft == null ? horizonStart.plusDays(30)
                        : workspaceDraft.getHorizonEnd());
    }

    /** 待排订单：草稿且尚未生成任何未关闭生产计划。 */
    private List<MfgProductionOrder> buildPendingOrders(List<MfgProductionPlan> allPlans) {
        Set<Long> plannedOrderIds = allPlans.stream()
                .filter(plan -> !STATUS_CLOSED.equals(plan.getStatus()))
                .map(MfgProductionPlan::getProductionOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return listDraftOrdersWithoutPlan().stream()
                .filter(order -> !plannedOrderIds.contains(order.getProductionOrderId()))
                .toList();
    }

    /** 统计看板各项数量。 */
    private void fillBoardCounts(ManualScheduleBoard board, List<MfgScheduleItem> visibleManualItems,
                                 List<MfgProductionPlan> visiblePlans,
                                 Map<Long, MfgScheduleScenario> scenarioMap,
                                 List<ManualScheduleBoardItem> timelineItems) {
        board.setManualItemCount(visibleManualItems.size());
        board.setAiBatchItemCount((int) visibleManualItems.stream()
                .filter(item -> isAiBatchScenario(scenarioMap.get(item.getScenarioId()))).count());
        board.setProductionPlanCount(visiblePlans.size());
        board.setConflictCount((int) visibleManualItems.stream()
                .filter(item -> ITEM_STATUS_CONFLICT.equals(item.getStatus())).count());
        board.setLateCount((int) timelineItems.stream()
                .filter(item -> item.getTardinessMinutes() != null && item.getTardinessMinutes() > 0).count());
    }

    private List<ManualScheduleBoardWorkingWindow> buildManualBoardWorkingWindows(LocalDateTime horizonStart,
                                                                                    LocalDateTime horizonEnd) {
        if (horizonStart == null || horizonEnd == null || !horizonEnd.isAfter(horizonStart)) {
            return List.of();
        }
        List<FactoryLine> runningLines = factoryLineMapper.selectList(activeLineQuery()).stream()
                .filter(line -> line.getLineId() != null && STATUS_RUNNING.equals(line.getStatus()))
                .toList();
        if (runningLines.isEmpty()) {
            return List.of();
        }
        Map<Long, List<MfgLineCalendarRule>> rulesByLine = mfgLineCalendarRuleMapper
                .selectList(activeCalendarRuleQuery()).stream()
                .collect(Collectors.groupingBy(MfgLineCalendarRule::getLineId));
        Map<Long, OrgShift> shiftsById = orgShiftMapper.selectList(activeShiftQuery()).stream()
                .collect(Collectors.toMap(OrgShift::getShiftId, shift -> shift));
        List<ManualScheduleBoardWorkingWindow> result = new ArrayList<>();
        for (FactoryLine line : runningLines) {
            for (CapacityWindow window : buildLineWindows(line,
                    rulesByLine.getOrDefault(line.getLineId(), List.of()), shiftsById, horizonStart, horizonEnd)) {
                ManualScheduleBoardWorkingWindow value = new ManualScheduleBoardWorkingWindow();
                value.setLineId(line.getLineId());
                value.setStartTime(window.startTime());
                value.setEndTime(window.endTime());
                result.add(value);
            }
        }
        return result;
    }

    @Override
    public AiResponse generateManualScheduleBoardAdvice(Long operatorId) {
        ManualScheduleBoard board = getManualScheduleBoard(operatorId);
        return aiService.getScheduleScenarioAdvice(buildManualBoardAiSummary(board));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleScenario generateManualAiBatch(Long operatorId) {
        if (operatorId == null) {
            throw invalid("当前登录用户不能为空");
        }
        assertNoPendingAiBatch(operatorId);
        List<MfgProductionOrder> orders = requireAiBatchOrders();
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        MfgScheduleScenario scenario = createAiBatchScenario(operatorId, now);
        List<MfgScheduleItem> occupancy = new ArrayList<>(listGlobalEditableManualItems());
        List<MfgScheduleItem> generatedItems = new ArrayList<>();
        for (int index = 0; index < orders.size(); index++) {
            generatedItems.add(createAiBatchItem(scenario, orders.get(index), index + 1, orders.size(),
                    occupancy, operatorId, now));
        }
        refreshManualScenarioSummary(scenario, STATUS_DRAFT, operatorId, now, generatedItems);
        invalidateCandidateCache(scenario.getScenarioId());
        insertOperationLog(scenario.getScenarioId(), "GENERATE_MANUAL_AI_BATCH", "mfg_schedule_scenario",
                operatorId, "AI一键排产生成可撤回草案", now);
        return getActiveScenario(scenario.getScenarioId());
    }

    /** 同一用户同时只能有一份未确认的一键排产草案。 */
    private void assertNoPendingAiBatch(Long operatorId) {
        if (findActiveManualAiBatch(operatorId) != null) {
            throw invalid("已有未确认的一键排产草案，请先确认或撤回后再重新生成");
        }
    }

    /** 取本次可一键排产的待排订单，没有则拒绝生成。 */
    private List<MfgProductionOrder> requireAiBatchOrders() {
        List<MfgProductionOrder> orders = listDraftOrdersAvailableForAiBatch();
        if (orders.isEmpty()) {
            throw invalid("当前没有可一键排产的待排订单");
        }
        return orders;
    }

    /** 新建一份 AI 一键排产草案头。 */
    private MfgScheduleScenario createAiBatchScenario(Long operatorId, LocalDateTime now) {
        LocalDateTime horizonStart = now.plusHours(1).withMinute(0).withSecond(0).withNano(0);
        MfgScheduleScenario scenario = new MfgScheduleScenario();
        scenario.setScenarioNo(AI_BATCH_SCENARIO_PREFIX + operatorId + "-"
                + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
        scenario.setScenarioName("AI一键排产草案");
        scenario.setHorizonStart(horizonStart);
        scenario.setHorizonEnd(horizonStart.toLocalDate().plusDays(30).atTime(23, 59, 59));
        scenario.setAlgorithmCode(ALGORITHM_CODE_MANUAL_BOARD);
        scenario.setStatus(STATUS_DRAFT);
        scenario.setOrderCount(0);
        scenario.setScheduledOrderCount(0);
        scenario.setConflictOrderCount(0);
        scenario.setLateOrderCount(0);
        scenario.setTotalLoadMinutes(BigDecimal.ZERO);
        scenario.setRemark("AI一键排产生成的可撤回草案，确认前不会创建正式生产计划");
        scenario.setVersion(0);
        scenario.setCreatedBy(operatorId);
        scenario.setCreatedTime(now);
        scenario.setUpdatedBy(operatorId);
        scenario.setUpdatedTime(now);
        scenario.setDeletedFlag(0);
        if (baseMapper.insert(scenario) == 0 || scenario.getScenarioId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }
        return scenario;
    }

    /** 为单张订单生成排程明细：入参不合格或没有可用时段时落成冲突明细。 */
    private MfgScheduleItem createAiBatchItem(MfgScheduleScenario scenario, MfgProductionOrder order,
                                              int sequenceNo, int orderCount, List<MfgScheduleItem> occupancy,
                                              Long operatorId, LocalDateTime now) {
        String inputProblem = validateOrderInput(order);
        List<ManualScheduleCandidateLine> candidates = inputProblem == null
                ? buildEligibleLines(scenario, order, occupancy) : List.of();
        MfgScheduleItem item;
        if (inputProblem != null) {
            item = createAiBatchConflictItem(scenario, order, sequenceNo, orderCount, inputProblem, operatorId, now);
        } else if (candidates.isEmpty()) {
            item = createAiBatchConflictItem(scenario, order, sequenceNo, orderCount,
                    "没有满足产线能力、班次日历、有效产能和当前占用条件的可用时间", operatorId, now);
        } else {
            item = createAiBatchScheduledItem(scenario, order, candidates.getFirst(), sequenceNo, orderCount,
                    operatorId, now);
        }
        if (mfgScheduleItemMapper.insert(item) == 0 || item.getScheduleItemId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }
        if (ITEM_STATUS_SCHEDULED.equals(item.getStatus())) {
            occupancy.add(item);
        }
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleScenario withdrawManualAiBatch(Long scenarioId, Integer version, Long operatorId) {
        MfgScheduleScenario scenario = requireManualAiBatch(scenarioId, version, operatorId);
        List<MfgScheduleItem> items = listActiveScenarioItems(scenarioId);
        if (items.isEmpty()) {
            throw invalid("当前一键排产草案没有可撤回的时间块");
        }
        if (items.stream().anyMatch(item -> item.getPlanId() != null || STATUS_CONFIRMED.equals(item.getStatus()))) {
            throw invalid("本次一键排产已有时间块生成正式计划，不能按草案撤回");
        }

        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        for (MfgScheduleItem item : items) {
            MfgScheduleItem update = new MfgScheduleItem();
            update.setScheduleItemId(item.getScheduleItemId());
            update.setVersion(item.getVersion());
            update.setDeletedFlag(1);
            update.setUpdatedBy(operatorId);
            update.setUpdatedTime(now);
            if (mfgScheduleItemMapper.updateById(update) == 0) {
                throw conflict("一键排产草案已被其他操作修改，请刷新后重试");
            }
        }

        MfgScheduleScenario update = new MfgScheduleScenario();
        update.setScenarioId(scenarioId);
        update.setVersion(scenario.getVersion());
        update.setStatus(STATUS_CANCELLED);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw conflict("一键排产草案已被其他操作修改，请刷新后重试");
        }
        invalidateCandidateCache(scenarioId);
        insertStatusChangeLog(scenarioId, scenario.getStatus(), STATUS_CANCELLED, "MFG_SCHEDULE_SCENARIO",
                "WITHDRAW_MANUAL_AI_BATCH", operatorId, "撤回AI一键排产草案", now);
        insertOperationLog(scenarioId, "WITHDRAW_MANUAL_AI_BATCH", "mfg_schedule_scenario",
                operatorId, "撤回AI一键排产草案", now);
        return getActiveScenario(scenarioId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleScenario confirmManualAiBatch(Long scenarioId, Integer version, String remark, Long operatorId) {
        MfgScheduleScenario scenario = requireManualAiBatch(scenarioId, version, operatorId);
        List<MfgScheduleItem> items = listActiveScenarioItems(scenarioId);
        assertAiBatchConfirmable(items);
        List<ConfirmationEntry> entries = validateAndLoadConfirmationEntries(scenario, items);
        assertAiBatchDelayRemarkPresent(items, remark);
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        String normalizedRemark = StringUtils.hasText(remark) ? remark.trim() : "确认AI一键排产草案";
        for (ConfirmationEntry entry : entries) {
            confirmAiBatchEntry(scenario, entry, operatorId, normalizedRemark, now);
        }
        markAiBatchConfirmed(scenario, scenarioId, operatorId, normalizedRemark, now);
        return getActiveScenario(scenarioId);
    }

    /** 一键排产草案必须非空、无冲突、且尚未生成任何正式计划。 */
    private void assertAiBatchConfirmable(List<MfgScheduleItem> items) {
        if (items.isEmpty()) {
            throw invalid("当前一键排产草案没有可确认的时间块");
        }
        if (items.stream().anyMatch(item -> ITEM_STATUS_CONFLICT.equals(item.getStatus()))) {
            throw invalid("本次一键排产仍存在冲突订单，请先撤回或人工处理后再确认");
        }
        if (items.stream().anyMatch(item -> item.getPlanId() != null || STATUS_CONFIRMED.equals(item.getStatus()))) {
            throw invalid("本次一键排产已有时间块生成正式计划，请刷新后查看");
        }
    }

    /** 一键排产存在预计延期订单时必须填写延期风险说明。 */
    private void assertAiBatchDelayRemarkPresent(List<MfgScheduleItem> items, String remark) {
        boolean hasDelay = items.stream()
                .anyMatch(item -> item.getTardinessMinutes() != null && item.getTardinessMinutes() > 0);
        if (hasDelay && !StringUtils.hasText(remark)) {
            throw invalid("本次一键排产存在预计延期订单，请填写延期风险确认说明");
        }
    }

    /** 为单条一键排产明细生成正式计划、回写明细并释放订单。 */
    private void confirmAiBatchEntry(MfgScheduleScenario scenario, ConfirmationEntry entry,
                                     Long operatorId, String remark, LocalDateTime now) {
        MfgScheduleItem item = entry.item();
        MfgProductionPlan plan = createProductionPlan(scenario, item, operatorId, now);
        plan.setRemark("由AI一键排产草案 " + scenario.getScenarioNo() + " 确认生成");
        if (mfgProductionPlanMapper.insert(plan) == 0 || plan.getPlanId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }
        MfgScheduleItem itemUpdate = new MfgScheduleItem();
        itemUpdate.setScheduleItemId(item.getScheduleItemId());
        itemUpdate.setPlanId(plan.getPlanId());
        itemUpdate.setStatus(STATUS_CONFIRMED);
        itemUpdate.setVersion(item.getVersion());
        itemUpdate.setUpdatedBy(operatorId);
        itemUpdate.setUpdatedTime(now);
        if (mfgScheduleItemMapper.updateById(itemUpdate) == 0) {
            throw conflict("一键排产明细已被其他操作修改，请刷新后重试");
        }
        releaseOrderForConfirmedPlan(entry.order(), operatorId, remark, now, "CONFIRM_MANUAL_AI_BATCH");
        insertStatusChangeLog(plan.getPlanId(), null, STATUS_RELEASED, "MFG_PRODUCTION_PLAN",
                "GENERATE_FROM_MANUAL_AI_BATCH", operatorId, remark, now);
        insertStatusChangeLog(item.getScheduleItemId(), item.getStatus(), STATUS_CONFIRMED, "MFG_SCHEDULE_ITEM",
                "CONFIRM_MANUAL_AI_BATCH", operatorId, remark, now);
    }

    /** 全部明细确认成功后把一键排产草案置为已确认。 */
    private void markAiBatchConfirmed(MfgScheduleScenario scenario, Long scenarioId,
                                      Long operatorId, String remark, LocalDateTime now) {
        MfgScheduleScenario update = new MfgScheduleScenario();
        update.setScenarioId(scenarioId);
        update.setVersion(scenario.getVersion());
        update.setStatus(STATUS_CONFIRMED);
        update.setConfirmedBy(operatorId);
        update.setConfirmedTime(now);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw conflict("一键排产草案已被其他操作修改，请刷新后重试");
        }
        invalidateCandidateCache(scenarioId);
        insertStatusChangeLog(scenarioId, scenario.getStatus(), STATUS_CONFIRMED, "MFG_SCHEDULE_SCENARIO",
                "CONFIRM_MANUAL_AI_BATCH", operatorId, remark, now);
        insertOperationLog(scenarioId, "CONFIRM_MANUAL_AI_BATCH", "mfg_schedule_scenario",
                operatorId, remark, now);
    }

    @Override
    public List<ManualScheduleCandidateLine> listEligibleLines(Long scenarioId, Long productionOrderId) {
        MfgScheduleScenario scenario = requireManualScenario(scenarioId, false);
        MfgProductionOrder order = requireDraftOrderWithoutPlan(productionOrderId);
        List<ManualScheduleCandidateLine> cached = findCachedEligibleLines(scenario, order);
        if (cached != null) {
            return cached;
        }
        List<ManualScheduleCandidateLine> candidates = buildEligibleLines(scenario, order);
        cacheEligibleLines(scenario, order, candidates);
        return candidates;
    }

    @Override
    public List<ManualScheduleCandidateLine> listEligibleLinesForOrder(Long productionOrderId) {
        MfgProductionOrder order = requireDraftOrderWithoutPlan(productionOrderId);
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        MfgScheduleScenario transientScenario = new MfgScheduleScenario();
        // 与 buildEligibleLines 保持一致：起点用当前时刻，避免给出已经过去的 08:00 班次起点。
        transientScenario.setHorizonStart(now);
        transientScenario.setHorizonEnd(now.toLocalDate().plusDays(30).atTime(23, 59, 59));
        return buildEligibleLines(transientScenario, order);
    }

    @Override
    public ManualScheduleCandidateLine validatePlanningPlacement(Long productionOrderId, Long lineId,
                                                                  LocalDateTime plannedStartTime,
                                                                  LocalDateTime plannedEndTime) {
        MfgProductionOrder order = requireDraftOrderWithoutPlan(productionOrderId);
        MfgScheduleScenario transientScenario = new MfgScheduleScenario();
        transientScenario.setHorizonStart(plannedStartTime);
        transientScenario.setHorizonEnd(plannedEndTime);
        MfgScheduleItem transientItem = new MfgScheduleItem();
        transientItem.setProductionOrderId(productionOrderId);
        transientItem.setLineId(lineId);
        transientItem.setPlannedStartTime(plannedStartTime);
        transientItem.setPlannedEndTime(plannedEndTime);
        String reason = validateManualPlacement(transientScenario, transientItem, order, lineId,
                plannedStartTime, plannedEndTime, List.of());
        if (reason != null) {
            throw invalid(reason);
        }
        return resolveManualCapability(order, lineId, plannedStartTime, plannedEndTime);
    }

    @Override
    public ManualScheduleDurationPreview previewManualDuration(Long scenarioId, Long scheduleItemId, Long lineId,
                                                               LocalDateTime plannedStartTime) {
        if (plannedStartTime == null) {
            throw invalid("请先填写计划开始时间");
        }
        MfgScheduleItem item = requireManualItem(scenarioId, scheduleItemId);
        MfgProductionOrder order = requireDraftOrderWithoutPlan(item.getProductionOrderId());
        if (lineId == null) {
            throw invalid("请选择生产产线");
        }
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        // 开始时间不能退到今天之前，也不能落在比"现在"更早的时刻；用较早者兜底避免算出过去的结束时间。
        LocalDateTime startTime = plannedStartTime.isBefore(now) ? now : plannedStartTime;

        SchedulingContext context = loadSchedulingContext(startTime,
                startTime.toLocalDate().plusDays(MAX_PREVIEW_DAYS).atTime(23, 59, 59));
        FactoryLine line = context.activeLines().get(lineId);
        if (line == null || !STATUS_RUNNING.equals(line.getStatus())) {
            throw invalid("产线不存在、已删除或未处于 RUNNING 状态");
        }
        ManualScheduleCandidateLine capability = resolveManualCapability(order, lineId, startTime,
                startTime.plusMinutes(1));

        // 从开始时间往后取足够长的窗口，按同一套产能与批次约束分配，得到真实的结束时刻。
        List<CapacityWindow> windows = buildLineWindows(line,
                context.calendarRulesByLine().getOrDefault(lineId, List.of()), context.shiftsById(),
                startTime, startTime.toLocalDate().plusDays(MAX_PREVIEW_DAYS).atTime(23, 59, 59));
        occupyWindowsUntil(windows, startTime);
        LineScheduleState state = new LineScheduleState(windows);
        BigDecimal requiredCapacity = order.getOrderQuantity().divide(capability.getCapacityFactor(),
                CAPACITY_SCALE, RoundingMode.HALF_UP);
        RouteWorkload workload = context.routeWorkloads().get(capability.getRouteId());
        Allocation allocation = state.allocate(requiredCapacity, workload, order.getOrderQuantity());
        if (allocation == null) {
            throw invalid("按当前产线班次与产能，未来 " + MAX_PREVIEW_DAYS + " 天内排不下这笔订单，请更换产线或调整数量");
        }

        ManualScheduleDurationPreview preview = new ManualScheduleDurationPreview();
        preview.setStartTime(allocation.startTime());
        preview.setEndTime(allocation.endTime());
        preview.setWorkingMinutes(calculateWorkingMinutes(windows, allocation.startTime(), allocation.endTime()));
        preview.setEstimatedMinutes(capability.getEstimatedMinutes());
        preview.setExplanation(explainDuration(workload));
        return preview;
    }

    /** 带整批装配与老化的订单要说明预留了完整窗口，否则说明会顺延。 */
    private static String explainDuration(RouteWorkload workload) {
        if (workload != null && workload.hasBatch()) {
            return "按产线班次产能折算，并为整批装配与连续老化保留完整窗口";
        }
        return "按产线班次产能折算，跨越下班与停工时段会顺延";
    }

    /**
     * 预览时段时把窗口在开始时间之前的部分视为已占用，
     * 保证推算出的分配起点不会落到"现在"之前。
     */
    private void occupyWindowsUntil(List<CapacityWindow> windows, LocalDateTime startTime) {
        for (CapacityWindow window : windows) {
            if (window.endTime().isAfter(startTime)) {
                window.occupyUntil(startTime);
            }
        }
    }

    @Override
    public ScheduleSimulationComparison simulateOrderSchedule(Long productionOrderId) {
        if (productionOrderId == null) {
            throw invalid("请先选择生产订单");
        }
        MfgProductionOrder order = getActiveProductionOrder(productionOrderId);
        if (order == null) {
            throw notFound("生产订单不存在或已删除");
        }
        if (order.getProductId() == null || !isPositive(order.getOrderQuantity())) {
            throw invalid("生产订单缺少有效产品或数量");
        }

        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        LocalDateTime horizonEnd = now.plusDays(MAX_PREVIEW_DAYS).withHour(23).withMinute(59).withSecond(59);
        // 已经做满的订单不能再推「什么时候开工」，否则会把已交付的订单说成还没生产。
        ScheduleSimulationComparison finished = finishedComparison(order, now);
        if (finished != null) {
            return finished;
        }
        SchedulingContext context = loadSchedulingContext(now, horizonEnd);
        List<MfgProductionPlan> activePlans = listActiveProductionPlans();
        // 现状口径把其它草案里已排的明细和已下达的计划都当作已占用，避免推出比实际更早的开工时间。
        List<MfgScheduleItem> occupancy = listGlobalEditableManualItems();

        ScheduleSimulationComparison comparison = new ScheduleSimulationComparison();
        comparison.setProductionOrderId(order.getProductionOrderId());
        comparison.setProductionOrderNo(order.getProductionOrderNo());
        comparison.setQuantity(order.getOrderQuantity());
        comparison.setDeliveryDate(order.getDeliveryDate());
        comparison.setOrderStatus(order.getStatus());
        comparison.setSimulatedAt(now);
        comparison.setProductName(productNameOf(order.getProductId()));
        comparison.setAssumptions(List.of(
                "按产线班次、日产能与工艺路线标准工时折算，含批次设备的整批装配与连续老化窗口",
                "起点取当前时刻，已排的其它草案明细与已下达计划按占用处理",
                "只读推演，不写排程明细、不生成生产计划"));

        List<String> missing = new ArrayList<>();
        List<ScheduleTrial> trials = simulateTrials(order, context, occupancy, activePlans, horizonEnd, missing);
        List<ScheduleSimulationComparison.Plan> plans = new ArrayList<>();

        ScheduleTrial current = trials.isEmpty() ? null : trials.get(0);
        plans.add(currentPlan(current));
        if (trials.size() < 2) {
            missing.add("该产品在当前时间窗内只有一条可用产线，换线口径无法试算");
        }
        plans.add(resourceShiftPlan(current, trials));
        plans.add(componentFirstPlan(order, current));

        comparison.setPlans(plans);
        comparison.setMissing(missing);
        comparison.setConclusion(conclusionOf(plans, order));
        return comparison;
    }

    /**
     * 逐条可用产线试算同一笔订单，按完工时间从早到晚排序；结果即「候选方案」。
     */
    private List<ScheduleTrial> simulateTrials(MfgProductionOrder order, SchedulingContext context,
                                               List<MfgScheduleItem> occupancy, List<MfgProductionPlan> activePlans,
                                               LocalDateTime horizonEnd, List<String> missing) {
        List<ScheduleTrial> trials = new ArrayList<>();
        boolean anyCapability = false;
        for (MfgLineProductCapability capability : context.capabilitiesByProduct()
                .getOrDefault(order.getProductId(), List.of())) {
            FactoryLine line = context.activeLines().get(capability.getLineId());
            ProcessRoute route = context.releasedRoutes().get(capability.getRouteId());
            RouteWorkload workload = context.routeWorkloads().get(capability.getRouteId());
            if (route == null || workload == null) {
                continue;
            }
            if (line == null || !STATUS_RUNNING.equals(line.getStatus())) {
                missing.add(String.format("产线 %s 未处于运行状态，未纳入试算", capability.getLineId()));
                continue;
            }
            anyCapability = true;
            LocalDateTime availableAt = lineAvailableAt(LocalDateTime.now(SCHEDULE_ZONE), line, occupancy,
                    activePlans);
            List<CapacityWindow> windows = buildLineWindows(line,
                    context.calendarRulesByLine().getOrDefault(line.getLineId(), List.of()),
                    context.shiftsById(), availableAt, horizonEnd);
            if (windows.isEmpty()) {
                missing.add(String.format("产线 %s 在推演区间内没有可用班次窗口", line.getLineCode()));
                continue;
            }
            LineScheduleState state = new LineScheduleState(windows);
            BigDecimal requiredCapacity = order.getOrderQuantity().divide(capability.getCapacityFactor(),
                    CAPACITY_SCALE, RoundingMode.HALF_UP);
            Allocation allocation = state.allocate(requiredCapacity, workload, order.getOrderQuantity());
            if (allocation == null) {
                missing.add(String.format("产线 %s 在 %d 天内排不下这笔订单，按不可行处理",
                        line.getLineCode(), MAX_PREVIEW_DAYS));
                continue;
            }
            trials.add(new ScheduleTrial(line, capability, workload, allocation,
                    calculateWorkingMinutes(windows, allocation.startTime(), allocation.endTime()),
                    workload.estimate(order.getOrderQuantity()), order.getDeliveryDate()));
        }
        if (!anyCapability) {
            missing.add("该产品没有处于运行状态的启用产线能力，无法推演开工时间");
        }
        if (!missing.isEmpty() && trials.isEmpty()) {
            missing.add("缺少可用产能模型，本次不估算完工时间");
        }
        trials.sort(Comparator.comparing((ScheduleTrial trial) -> trial.allocation().endTime())
                .thenComparing(trial -> trial.capability().getPriorityLevel(), Comparator.nullsLast(Integer::compareTo))
                .thenComparing(trial -> trial.line().getLineId()));
        return trials;
    }

    private ScheduleSimulationComparison.Plan currentPlan(ScheduleTrial trial) {
        ScheduleSimulationComparison.Plan plan = newPlan("CURRENT", "现状口径",
                "按当前产线优先级与占用，最早能开工的时间");
        if (trial == null) {
            plan.setFeasible(false);
            plan.setBlockers(List.of("未来 " + MAX_PREVIEW_DAYS + " 天内没有产线排得下这笔订单"));
            return plan;
        }
        fillPlan(plan, trial);
        return plan;
    }

    /**
     * 换线口径：在现状选中的产线之外，找完工最早的另一条可用产线。
     */
    private ScheduleSimulationComparison.Plan resourceShiftPlan(ScheduleTrial current, List<ScheduleTrial> trials) {
        ScheduleSimulationComparison.Plan plan = newPlan("RESOURCE_SHIFT", "调整资源",
                "把订单换到另一条可用产线，比较完工时间是否提前");
        ScheduleTrial alternative = null;
        for (ScheduleTrial trial : trials) {
            if (current != null && Objects.equals(trial.line().getLineId(), current.line().getLineId())) {
                continue;
            }
            if (alternative == null) {
                alternative = trial;
            }
        }
        if (alternative == null) {
            plan.setFeasible(false);
            plan.setBlockers(List.of(current == null
                    ? "没有可试算的产线，无法比较换线效果"
                    : "该产品只有一条可用产线，没有换线余量"));
            return plan;
        }
        fillPlan(plan, alternative);
        if (current != null) {
            long diff = Duration.between(alternative.allocation().endTime(), current.allocation().endTime()).toMinutes();
            plan.setNotes(List.of(diff > 0
                    ? "换到 " + alternative.line().getLineCode() + " 可比现状提前 " + diff + " 分钟完工"
                    : "换到 " + alternative.line().getLineCode() + " 不会更早完工，现状口径已是最优"));
            plan.setDescription(plan.getDescription() + "；当前比较对象为 " + alternative.line().getLineCode());
        }
        return plan;
    }

    /**
     * 组件优先口径：先让组件子工单占住产线，根工单顺延到组件完工之后。
     */
    private ScheduleSimulationComparison.Plan componentFirstPlan(MfgProductionOrder order, ScheduleTrial current) {
        ScheduleSimulationComparison.Plan plan = newPlan("COMPONENT_FIRST", "组件优先",
                "先做组件子工单、再做整机总装，比较根工单的开工与完工时间");
        List<MfgWorkOrder> components = pendingComponents(order);
        List<String> earlyBlockers = componentFirstBlockers(components, current);
        if (!earlyBlockers.isEmpty()) {
            plan.setFeasible(false);
            plan.setBlockers(earlyBlockers);
            return plan;
        }
        List<String> notes = new ArrayList<>();
        LocalDateTime componentEnd = null;
        for (MfgWorkOrder component : components) {
            if (component.getPlannedEndTime() != null) {
                componentEnd = maxTime(componentEnd, component.getPlannedEndTime());
            }
            notes.add(String.format("组件工单 %s 计划 %s ~ %s，状态 %s",
                    component.getWorkOrderNo(),
                    component.getPlannedStartTime() == null ? "未排" : component.getPlannedStartTime().toString(),
                    component.getPlannedEndTime() == null ? "未排" : component.getPlannedEndTime().toString(),
                    component.getStatus()));
        }
        if (componentEnd == null) {
            plan.setFeasible(false);
            // 先把阻塞原因建成可变列表再加明细，直接 setBlockers(List.of(..)) 之后 add 会抛
            // UnsupportedOperationException，让整次推演 500。
            List<String> blockers = new ArrayList<>();
            blockers.add("组件子工单还没有计划起止时间，无法确定根工单可开工时刻");
            blockers.addAll(notes);
            plan.setBlockers(blockers);
            return plan;
        }
        fillPlan(plan, current);
        LocalDateTime shiftedStart = maxTime(current.allocation().startTime(), componentEnd);
        long shiftMinutes = current.allocation().startTime() == null ? 0L
                : Duration.between(current.allocation().startTime(), shiftedStart).toMinutes();
        plan.setStartTime(shiftedStart);
        plan.setEndTime(shiftMinutes > 0 ? current.allocation().endTime().plusMinutes(shiftMinutes)
                : current.allocation().endTime());
        notes.add(shiftMinutes > 0
                ? "根工单要等组件完工，开工顺延 " + shiftMinutes + " 分钟"
                : "组件预计完工早于根工单可开工时刻，组件优先不额外顺延");
        plan.setNotes(notes);
        return plan;
    }

    /** 该订单下还没完工的组件子工单，按计划展开顺序返回。 */
    private List<MfgWorkOrder> pendingComponents(MfgProductionOrder order) {
        QueryWrapper<MfgWorkOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        wrapper.inSql("plan_id", "SELECT plan_id FROM mfg_production_plan WHERE deleted_flag = 0 "
                + "AND production_order_id = " + order.getProductionOrderId());
        wrapper.isNotNull("parent_work_order_id");
        wrapper.notIn("status", List.of("FINISHED", "CLOSED"));
        return mfgWorkOrderMapper.selectList(wrapper);
    }

    /** 组件优先在展开之前就能判定的不可行原因；为空表示可以继续推算。 */
    private static List<String> componentFirstBlockers(List<MfgWorkOrder> components, ScheduleTrial current) {
        if (components.isEmpty()) {
            return List.of("该订单还没有未完工的组件子工单，组件优先口径无法试算",
                    "需要先下达生产计划并展开 BOM 组件工单，再做这一口径的推演");
        }
        if (current == null) {
            return List.of("没有可用的整机产线窗口，无法推算组件优先后的根工单时间");
        }
        return List.of();
    }

    /**
     * 已经做满或已关闭的订单不推开工时间，直接给出「不需要再排」的结论。
     * 返回 null 表示订单还在生产过程中，继续正常推演。
     */
    private ScheduleSimulationComparison finishedComparison(MfgProductionOrder order, LocalDateTime now) {
        String status = order.getStatus();
        boolean closed = STATUS_CLOSED.equals(status) || STATUS_FINISHED.equals(status);
        BigDecimal completed = completedRootQuantity(order.getProductionOrderId());
        boolean quantityDone = isPositive(order.getOrderQuantity()) && completed != null
                && completed.compareTo(order.getOrderQuantity()) >= 0;
        if (!closed && !quantityDone) {
            return null;
        }
        ScheduleSimulationComparison comparison = new ScheduleSimulationComparison();
        comparison.setProductionOrderId(order.getProductionOrderId());
        comparison.setProductionOrderNo(order.getProductionOrderNo());
        comparison.setQuantity(order.getOrderQuantity());
        comparison.setDeliveryDate(order.getDeliveryDate());
        comparison.setOrderStatus(status);
        comparison.setSimulatedAt(now);
        comparison.setProductName(productNameOf(order.getProductId()));
        comparison.setAssumptions(List.of("只读推演，不写排程明细、不生成生产计划"));
        comparison.setMissing(new ArrayList<>());

        ScheduleSimulationComparison.Plan plan = newPlan("COMPLETED", "已完工",
                "该订单的整机工单已经做满，不再推演开工与完工时间");
        plan.setFeasible(false);
        plan.setBlockers(List.of(String.format("订单已完工 %s / %s，不需要再安排生产",
                scaleText(completed), scaleText(order.getOrderQuantity()))));
        comparison.setPlans(List.of(plan));
        comparison.setConclusion(String.format("订单 %s 已完工 %s / %s，不需要再排产；如果要再生产请另建生产订单",
                order.getProductionOrderNo(), scaleText(completed), scaleText(order.getOrderQuantity())));
        return comparison;
    }

    /** 该订单整机根工单的累计完工数量；组件子工单不计入。 */
    private BigDecimal completedRootQuantity(Long productionOrderId) {
        QueryWrapper<MfgWorkOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        wrapper.inSql("plan_id", "SELECT plan_id FROM mfg_production_plan WHERE deleted_flag = 0 "
                + "AND production_order_id = " + productionOrderId);
        wrapper.and(nested -> nested.isNull("work_order_type").or().eq("work_order_type", "ROOT"));
        wrapper.ne("status", STATUS_CLOSED);
        BigDecimal total = BigDecimal.ZERO;
        for (MfgWorkOrder workOrder : mfgWorkOrderMapper.selectList(wrapper)) {
            if (workOrder.getCompletedQuantity() != null) {
                total = total.add(workOrder.getCompletedQuantity());
            }
        }
        return total;
    }

    /** 数量按去除多余零的口径展示，避免出现 2.0000 / 2 两种写法混排。 */
    private String scaleText(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private ScheduleSimulationComparison.Plan newPlan(String code, String label, String description) {
        ScheduleSimulationComparison.Plan plan = new ScheduleSimulationComparison.Plan();
        plan.setCode(code);
        plan.setLabel(label);
        plan.setDescription(description);
        plan.setFeasible(true);
        plan.setBlockers(new ArrayList<>());
        plan.setNotes(new ArrayList<>());
        return plan;
    }

    private void fillPlan(ScheduleSimulationComparison.Plan plan, ScheduleTrial trial) {
        plan.setLineId(trial.line().getLineId());
        plan.setLineCode(trial.line().getLineCode());
        plan.setLineName(trial.line().getLineName());
        plan.setStartTime(trial.allocation().startTime());
        plan.setEndTime(trial.allocation().endTime());
        plan.setWorkingMinutes(trial.workingMinutes());
        plan.setEstimatedMinutes(trial.estimatedMinutes());
        plan.setMeetsDelivery(meetsDelivery(trial.allocation().endTime(), trial.deliveryDate()));
    }

    /** 交期为空时不做判断，返回 null，避免把未知当成满足。 */
    private Boolean meetsDelivery(LocalDateTime endTime, LocalDate deliveryDate) {
        if (endTime == null || deliveryDate == null) {
            return null;
        }
        return !endTime.toLocalDate().isAfter(deliveryDate);
    }

    private String productNameOf(Long productId) {
        MdmProduct product = mdmProductMapper.selectById(productId);
        return product == null ? "" : product.getProductName();
    }

    private String conclusionOf(List<ScheduleSimulationComparison.Plan> plans, MfgProductionOrder order) {
        ScheduleSimulationComparison.Plan current = plans.get(0);
        if (!current.isFeasible()) {
            return "未来 " + MAX_PREVIEW_DAYS + " 天内这条订单没有可排产线，建议先核查产线运行状态、周历与产能系数";
        }
        StringBuilder text = new StringBuilder();
        text.append(current.getLineName()).append(" 最早 ")
                .append(current.getStartTime().format(MANUAL_BOARD_AI_TIME_FORMATTER))
                .append(" 开工，按产能 ")
                .append(current.getEndTime().format(MANUAL_BOARD_AI_TIME_FORMATTER))
                .append(" 做完");
        if (order.getDeliveryDate() != null) {
            boolean inTime = !current.getEndTime().toLocalDate().isAfter(order.getDeliveryDate());
            text.append(inTime ? "，早于交期 " : "，晚于交期 ").append(order.getDeliveryDate()).append(" 完成");
        }
        for (ScheduleSimulationComparison.Plan plan : plans) {
            if (!plan.isFeasible()) {
                continue;
            }
            if ("RESOURCE_SHIFT".equals(plan.getCode()) && plan.getNotes() != null && !plan.getNotes().isEmpty()) {
                text.append("；").append(plan.getNotes().get(0));
            }
            if ("COMPONENT_FIRST".equals(plan.getCode()) && plan.getNotes() != null && !plan.getNotes().isEmpty()) {
                text.append("；").append(plan.getNotes().get(plan.getNotes().size() - 1));
            }
        }
        return text.toString();
    }

    /**
     * 单条产线的试算结果，只在推演内部使用。
     * estimatedMinutes 是该产线的工艺工时折算，deliveryDate 用来判断是否满足交期，为空表示订单没填交期。
     */
    private record ScheduleTrial(FactoryLine line, MfgLineProductCapability capability, RouteWorkload workload,
                                 Allocation allocation, long workingMinutes, BigDecimal estimatedMinutes,
                                 LocalDate deliveryDate) {
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleItem addManualItem(Long scenarioId, Integer scenarioVersion, Long productionOrderId,
                                         Long lineId, Long operatorId) {
        MfgScheduleScenario scenario = requireManualScenario(scenarioId, false);
        assertNotAiBatchScenario(scenario);
        assertScenarioVersion(scenario, scenarioVersion);
        MfgProductionOrder order = requireDraftOrderWithoutPlan(productionOrderId);
        if (lineId == null) {
            throw invalid("请选择生产产线");
        }
        ensureOrderNotInEditableScenario(productionOrderId, scenarioId);
        List<MfgScheduleItem> activeItems = listActiveScenarioItems(scenarioId);
        ManualScheduleCandidateLine candidate = buildEligibleLines(scenario, order).stream()
                .filter(item -> Objects.equals(item.getLineId(), lineId))
                .findFirst()
                .orElseThrow(() -> invalid("所选产线不具备当前订单的有效生产能力或可用时段"));

        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        MfgScheduleItem item = new MfgScheduleItem();
        item.setScenarioId(scenarioId);
        item.setProductionOrderId(order.getProductionOrderId());
        item.setProductId(order.getProductId());
        item.setRouteId(candidate.getRouteId());
        item.setLineId(candidate.getLineId());
        item.setSequenceNo(nextSequenceNo(activeItems));
        item.setPlannedQuantity(order.getOrderQuantity());
        item.setPlannedStartTime(candidate.getEarliestStartTime());
        item.setPlannedEndTime(candidate.getEarliestEndTime());
        item.setEstimatedMinutes(candidate.getEstimatedMinutes());
        item.setPriorityScore(BigDecimal.ZERO);
        item.setTardinessMinutes(calculateTardiness(order.getDeliveryDate(), candidate.getEarliestEndTime()));
        item.setDecisionSource(DECISION_SOURCE_MANUAL);
        item.setStatus(ITEM_STATUS_SCHEDULED);
        item.setExplanation("人工选择产线后按最早可用时段加入草案");
        item.setVersion(0);
        item.setCreatedBy(operatorId);
        item.setCreatedTime(now);
        item.setUpdatedBy(operatorId);
        item.setUpdatedTime(now);
        item.setDeletedFlag(0);
        MfgScheduleItem removedItem = findRemovedScenarioOrderItem(scenarioId, productionOrderId);
        if (removedItem == null) {
            if (mfgScheduleItemMapper.insert(item) == 0 || item.getScheduleItemId() == null) {
                throw new BusinessException(ResultCode.FAILED);
            }
        } else {
            restoreRemovedManualItem(item, removedItem);
        }
        List<MfgScheduleItem> itemsForSummary = new ArrayList<>(activeItems);
        itemsForSummary.add(item);
        refreshManualScenarioSummary(scenario, STATUS_DRAFT, operatorId, now, itemsForSummary);
        invalidateCandidateCache(scenarioId);
        insertOperationLog(item.getScheduleItemId(), "ADD_MANUAL_SCHEDULE_ITEM", "mfg_schedule_item",
                operatorId, "订单加入手工排程草案", now);
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleItem updateManualPlacement(Long scenarioId, Long scheduleItemId, Integer scenarioVersion,
                                                  Integer itemVersion, Long lineId, LocalDateTime plannedStartTime,
                                                  LocalDateTime plannedEndTime, String remark, Long operatorId) {
        MfgScheduleScenario scenario = requireManualScenario(scenarioId, false);
        assertNotAiBatchScenario(scenario);
        assertScenarioVersion(scenario, scenarioVersion);
        MfgScheduleItem item = requireManualItem(scenarioId, scheduleItemId);
        if (!Objects.equals(item.getVersion(), itemVersion)) {
            throw conflict("排程已更新，请刷新排程总览后重新操作");
        }
        MfgProductionOrder order = requireDraftOrderWithoutPlan(item.getProductionOrderId());
        String reason = validateManualPlacement(scenario, item, order, lineId, plannedStartTime, plannedEndTime,
                listActiveScenarioItems(scenarioId));
        if (reason != null) {
            throw invalid(reason);
        }
        ManualScheduleCandidateLine capability = resolveManualCapability(order, lineId, plannedStartTime, plannedEndTime);
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        MfgScheduleItem update = new MfgScheduleItem();
        update.setScheduleItemId(scheduleItemId);
        update.setVersion(itemVersion);
        update.setLineId(lineId);
        update.setRouteId(capability.getRouteId());
        update.setPlannedStartTime(plannedStartTime);
        update.setPlannedEndTime(plannedEndTime);
        update.setEstimatedMinutes(capability.getEstimatedMinutes());
        update.setTardinessMinutes(calculateTardiness(order.getDeliveryDate(), plannedEndTime));
        update.setDecisionSource(DECISION_SOURCE_MANUAL);
        update.setStatus(ITEM_STATUS_SCHEDULED);
        update.setConflictReason(null);
        update.setExplanation("人工编辑产线与计划时间");
        update.setRemark(trimToNull(remark));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (mfgScheduleItemMapper.updateById(update) == 0) {
            throw conflict("排程已更新，请刷新排程总览后重新操作");
        }
        refreshManualScenarioSummary(scenario, STATUS_DRAFT, operatorId, now);
        invalidateCandidateCache(scenarioId);
        insertOperationLog(scheduleItemId, "UPDATE_MANUAL_SCHEDULE_PLACEMENT", "mfg_schedule_item",
                operatorId, "编辑手工排程时间块", now);
        return requireManualItem(scenarioId, scheduleItemId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleScenario removeManualItem(Long scenarioId, Long scheduleItemId, Integer scenarioVersion,
                                                Integer itemVersion, Long operatorId) {
        MfgScheduleScenario scenario = requireManualScenario(scenarioId, false);
        assertNotAiBatchScenario(scenario);
        assertScenarioVersion(scenario, scenarioVersion);
        MfgScheduleItem item = requireManualItem(scenarioId, scheduleItemId);
        if (!Objects.equals(item.getVersion(), itemVersion)) {
            throw conflict("排程已更新，请刷新排程总览后重新操作");
        }
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        MfgScheduleItem update = new MfgScheduleItem();
        update.setScheduleItemId(scheduleItemId);
        update.setVersion(itemVersion);
        update.setDeletedFlag(1);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (mfgScheduleItemMapper.updateById(update) == 0) {
            throw conflict("排程已更新，请刷新排程总览后重新操作");
        }
        refreshManualScenarioSummary(scenario, STATUS_DRAFT, operatorId, now);
        invalidateCandidateCache(scenarioId);
        insertOperationLog(scheduleItemId, "REMOVE_MANUAL_SCHEDULE_ITEM", "mfg_schedule_item",
                operatorId, "订单移出手工排程草案", now);
        return getActiveScenario(scenarioId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgProductionPlan confirmManualItem(Long scenarioId, Long scheduleItemId, Integer scenarioVersion,
                                               Integer itemVersion, String remark, Long operatorId) {
        requireAllPresent("方案、排程明细、版本和确认人不能为空",
                scenarioId, scheduleItemId, scenarioVersion, itemVersion, operatorId);
        MfgScheduleScenario scenario = requireManualScenario(scenarioId, false);
        assertNotAiBatchScenario(scenario);
        assertScenarioVersion(scenario, scenarioVersion);
        MfgScheduleItem item = requireManualItem(scenarioId, scheduleItemId);
        if (!Objects.equals(item.getVersion(), itemVersion)) {
            throw conflict("排程已更新，请刷新排程总览后重新操作");
        }
        validateScheduledItem(item, new HashSet<>());
        if (hasActivePlan(item.getProductionOrderId())) {
            throw invalid("该订单已生成生产计划，请刷新排程总览查看");
        }
        MfgProductionOrder order = getActiveProductionOrder(item.getProductionOrderId());
        if (order == null) {
            throw notFound("排程明细关联的生产订单不存在或已删除");
        }
        if (!STATUS_DRAFT.equals(order.getStatus()) || order.getVersion() == null) {
            throw invalid("该订单不是待排新建状态或状态已变化，不能再生成生产计划，请刷新后重新选择");
        }

        List<MfgScheduleItem> scenarioItems = listActiveScenarioItems(scenarioId);
        String validationReason = validateManualItem(scenario, item, scenarioItems);
        if (validationReason != null) {
            throw invalid("当前计划不可确认：" + validationReason);
        }

        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        MfgProductionPlan plan = createProductionPlan(scenario, item, operatorId, now);
        String normalizedRemark = trimToNull(remark);
        if (normalizedRemark != null) {
            plan.setRemark(normalizedRemark);
        }
        if (mfgProductionPlanMapper.insert(plan) == 0 || plan.getPlanId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }

        MfgScheduleItem itemUpdate = new MfgScheduleItem();
        itemUpdate.setScheduleItemId(scheduleItemId);
        itemUpdate.setPlanId(plan.getPlanId());
        itemUpdate.setStatus(STATUS_CONFIRMED);
        itemUpdate.setVersion(itemVersion);
        itemUpdate.setUpdatedBy(operatorId);
        itemUpdate.setUpdatedTime(now);
        if (mfgScheduleItemMapper.updateById(itemUpdate) == 0) {
            throw conflict("排程已更新，请刷新排程总览后重新操作");
        }
        releaseOrderForConfirmedPlan(order, operatorId, normalizedRemark, now,
                "CONFIRM_MANUAL_SCHEDULE_ITEM");

        List<MfgScheduleItem> remainingItems = scenarioItems.stream()
                .filter(existing -> !Objects.equals(existing.getScheduleItemId(), scheduleItemId))
                .filter(existing -> existing.getPlanId() == null)
                .filter(existing -> !STATUS_CONFIRMED.equals(existing.getStatus()))
                .toList();
        refreshManualScenarioSummary(scenario, STATUS_DRAFT, operatorId, now, remainingItems);
        insertStatusChangeLog(plan.getPlanId(), null, STATUS_RELEASED, "MFG_PRODUCTION_PLAN",
                "CONFIRM_MANUAL_SCHEDULE_ITEM", operatorId, normalizedRemark, now);
        insertStatusChangeLog(item.getScheduleItemId(), item.getStatus(), STATUS_CONFIRMED, "MFG_SCHEDULE_ITEM",
                "CONFIRM_MANUAL_SCHEDULE_ITEM", operatorId, normalizedRemark, now);
        insertOperationLog(scheduleItemId, "CONFIRM_MANUAL_SCHEDULE_ITEM", "mfg_schedule_item",
                operatorId, normalizedRemark, now);
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleScenario validateManualDraft(Long scenarioId, Integer scenarioVersion, Long operatorId) {
        MfgScheduleScenario scenario = requireManualScenario(scenarioId, false);
        assertNotAiBatchScenario(scenario);
        assertScenarioVersion(scenario, scenarioVersion);
        List<MfgScheduleItem> items = listActiveScenarioItems(scenarioId);
        if (items.isEmpty()) {
            throw invalid("手工草案没有可校验的订单");
        }
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        for (MfgScheduleItem item : items) {
            refreshManualItemValidation(scenario, item, items, operatorId, now);
        }
        refreshManualScenarioSummary(getActiveScenario(scenarioId), STATUS_CALCULATED, operatorId, now);
        invalidateCandidateCache(scenarioId);
        insertOperationLog(scenarioId, "VALIDATE_MANUAL_SCHEDULE_DRAFT", "mfg_schedule_scenario",
                operatorId, "校验手工排程草案", now);
        return getActiveScenario(scenarioId);
    }

    /** 重新校验单条明细，状态或冲突原因有变化时回写。 */
    private void refreshManualItemValidation(MfgScheduleScenario scenario, MfgScheduleItem item,
                                             List<MfgScheduleItem> items, Long operatorId, LocalDateTime now) {
        String reason = validateManualItem(scenario, item, items);
        String expectedStatus = reason == null ? ITEM_STATUS_SCHEDULED : ITEM_STATUS_CONFLICT;
        boolean unchanged = Objects.equals(item.getStatus(), expectedStatus)
                && Objects.equals(item.getConflictReason(), reason);
        if (unchanged) {
            return;
        }
        MfgScheduleItem update = new MfgScheduleItem();
        update.setScheduleItemId(item.getScheduleItemId());
        update.setVersion(item.getVersion());
        update.setStatus(expectedStatus);
        update.setConflictReason(reason);
        update.setTardinessMinutes(resolveItemTardiness(item));
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (mfgScheduleItemMapper.updateById(update) == 0) {
            throw conflict("排程明细已被其他用户修改，请刷新后重试");
        }
    }

    /** 明细的延期分钟数，没有结束时间时记为 0。 */
    private long resolveItemTardiness(MfgScheduleItem item) {
        if (item.getPlannedEndTime() == null) {
            return 0L;
        }
        LocalDate deliveryDate = requireDraftOrderWithoutPlan(item.getProductionOrderId()).getDeliveryDate();
        return calculateTardiness(deliveryDate, item.getPlannedEndTime());
    }

    @Override
    public AiResponse generateOrderAiAdvice(Long scenarioId, Long productionOrderId, Integer scenarioVersion) {
        MfgScheduleScenario scenario = requireManualScenario(scenarioId, false);
        assertScenarioVersion(scenario, scenarioVersion);
        MfgProductionOrder order = requireDraftOrderWithoutPlan(productionOrderId);
        List<ManualScheduleCandidateLine> candidates = buildEligibleLines(scenario, order);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scenarioStatus", scenario.getStatus());
        summary.put("orderProductId", order.getProductId());
        summary.put("orderQuantity", order.getOrderQuantity());
        summary.put("deliveryDate", order.getDeliveryDate());
        summary.put("candidateLines", candidates.stream().map(candidate -> {
            // 产线未配置车间、能力未配置优先级时 workshopId / priorityLevel 为 null，
            // 不能用 Map.of（不接受 null 值），否则 AI 排程建议接口直接 500。
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineId", candidate.getLineId());
            line.put("workshopId", candidate.getWorkshopId());
            line.put("priorityLevel", candidate.getPriorityLevel());
            line.put("estimatedMinutes", candidate.getEstimatedMinutes());
            line.put("earliestStartTime", candidate.getEarliestStartTime());
            line.put("earliestEndTime", candidate.getEarliestEndTime());
            return line;
        }).toList());
        summary.put("draftItemCount", listActiveScenarioItems(scenarioId).size());
        return aiService.getScheduleScenarioAdvice(summary);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleScenario calculateScenario(MfgScheduleScenario request) {
        validateCalculationRequest(request);
        String scenarioNo = request.getScenarioNo().trim();
        ensureScenarioNoAvailable(scenarioNo);
        List<MfgProductionOrder> orders = listDraftOrdersWithoutPlan();
        validateSchedulableOrders(orders);
        MfgScheduleScenario scenario = createDraftScenario(request, scenarioNo);
        if (baseMapper.insert(scenario) == 0 || scenario.getScenarioId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }
        SchedulingContext context = loadSchedulingContext(scenario.getHorizonStart(), scenario.getHorizonEnd());
        ScenarioTally tally = scheduleAllOrders(scenario, orders, context);
        applyScenarioTally(scenario, orders.size(), tally);
        if (baseMapper.updateById(scenario) == 0) {
            throw new BusinessException(ResultCode.FAILED);
        }
        return scenario;
    }

    /** 逐单排程并写入明细，同时累计已排、冲突、延期与总负荷。 */
    private ScenarioTally scheduleAllOrders(MfgScheduleScenario scenario, List<MfgProductionOrder> orders,
                                            SchedulingContext context) {
        Map<Long, LineScheduleState> lineStates = new HashMap<>();
        ScenarioTally tally = new ScenarioTally();
        for (int index = 0; index < orders.size(); index++) {
            MfgScheduleItem item = scheduleOrder(scenario, orders.get(index), index + 1, orders.size(), context, lineStates);
            if (mfgScheduleItemMapper.insert(item) == 0) {
                throw new BusinessException(ResultCode.FAILED);
            }
            tally.accept(item);
        }
        return tally;
    }

    /** 把排程统计结果回写到方案头。 */
    private void applyScenarioTally(MfgScheduleScenario scenario, int orderCount, ScenarioTally tally) {
        scenario.setStatus(STATUS_CALCULATED);
        scenario.setOrderCount(orderCount);
        scenario.setScheduledOrderCount(tally.scheduledCount);
        scenario.setConflictOrderCount(tally.conflictCount);
        scenario.setLateOrderCount(tally.lateCount);
        scenario.setTotalLoadMinutes(tally.totalLoadMinutes.setScale(2, RoundingMode.HALF_UP));
    }

    /** 排程结果的计数器：已排数量、冲突数量、延期数量与总负荷。 */
    private static final class ScenarioTally {
        private int scheduledCount;
        private int conflictCount;
        private int lateCount;
        private BigDecimal totalLoadMinutes = BigDecimal.ZERO;

        /** 累计单条明细的排程结果。 */
        void accept(MfgScheduleItem item) {
            if (!ITEM_STATUS_SCHEDULED.equals(item.getStatus())) {
                conflictCount++;
                return;
            }
            scheduledCount++;
            totalLoadMinutes = totalLoadMinutes.add(item.getEstimatedMinutes());
            if (item.getTardinessMinutes() != null && item.getTardinessMinutes() > 0) {
                lateCount++;
            }
        }
    }

    /**
     * 确认排程方案，将所有已排程明细原子转换为正式生产计划。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfgScheduleScenario confirmScenario(Long scenarioId, Integer version, Long operatorId, String remark) {
        if (scenarioId == null || version == null || operatorId == null) {
            throw invalid("方案、版本和确认人不能为空");
        }
        MfgScheduleScenario scenario = requireConfirmableScenario(scenarioId, version);
        List<MfgScheduleItem> items = listActiveScenarioItems(scenarioId);
        List<ConfirmationEntry> entries = validateAndLoadConfirmationEntries(scenario, items);
        assertDelayRemarkPresent(items, remark);
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        for (ConfirmationEntry entry : entries) {
            confirmScenarioEntry(scenario, entry, operatorId, remark, now);
        }
        markScenarioConfirmed(scenario, scenarioId, operatorId, remark, now);
        return scenario;
    }

    /** 确认前校验方案状态与版本：AI 草案、非 CALCULATED 与版本不一致都不能确认。 */
    private MfgScheduleScenario requireConfirmableScenario(Long scenarioId, Integer version) {
        MfgScheduleScenario scenario = getActiveScenario(scenarioId);
        if (scenario == null) {
            throw notFound("排程方案不存在或已删除");
        }
        if (isAiBatchScenario(scenario)) {
            throw invalid("AI一键排产草案只能通过批次确认入口生成正式生产计划");
        }
        if (!STATUS_CALCULATED.equals(scenario.getStatus())) {
            throw invalid("只有 CALCULATED 状态的排程方案可以确认");
        }
        if (!Objects.equals(version, scenario.getVersion())) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(), "排程方案已被其他用户修改，请刷新后重试");
        }
        return scenario;
    }

    /** 方案存在预计延期订单时必须填写延期风险说明。 */
    private void assertDelayRemarkPresent(List<MfgScheduleItem> items, String remark) {
        boolean hasDelay = items.stream()
                .anyMatch(item -> item.getTardinessMinutes() != null && item.getTardinessMinutes() > 0);
        if (hasDelay && !StringUtils.hasText(remark)) {
            throw invalid("方案存在预计延期订单，请填写延期风险确认说明");
        }
    }

    /** 为单条排程明细生成生产计划、回写明细并释放订单。 */
    private void confirmScenarioEntry(MfgScheduleScenario scenario, ConfirmationEntry entry,
                                      Long operatorId, String remark, LocalDateTime now) {
        MfgScheduleItem item = entry.item();
        MfgProductionPlan plan = createProductionPlan(scenario, item, operatorId, now);
        if (mfgProductionPlanMapper.insert(plan) == 0 || plan.getPlanId() == null) {
            throw new BusinessException(ResultCode.FAILED);
        }
        markItemConfirmed(item, plan.getPlanId(), operatorId, now);
        releaseOrderForConfirmedPlan(entry.order(), operatorId, remark, now, "CONFIRM_SCHEDULE_SCENARIO");
        insertStatusChangeLog(plan.getPlanId(), null, STATUS_RELEASED, "MFG_PRODUCTION_PLAN",
                "GENERATE_FROM_SCHEDULE_SCENARIO", operatorId, remark, now);
        insertStatusChangeLog(item.getScheduleItemId(), ITEM_STATUS_SCHEDULED, STATUS_CONFIRMED, "MFG_SCHEDULE_ITEM",
                "CONFIRM_SCHEDULE_SCENARIO", operatorId, remark, now);
    }

    /** 把排程明细置为已确认并绑定生产计划。 */
    private void markItemConfirmed(MfgScheduleItem item, Long planId, Long operatorId, LocalDateTime now) {
        MfgScheduleItem itemUpdate = new MfgScheduleItem();
        itemUpdate.setScheduleItemId(item.getScheduleItemId());
        itemUpdate.setPlanId(planId);
        itemUpdate.setStatus(STATUS_CONFIRMED);
        itemUpdate.setVersion(item.getVersion());
        itemUpdate.setUpdatedBy(operatorId);
        itemUpdate.setUpdatedTime(now);
        if (mfgScheduleItemMapper.updateById(itemUpdate) == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(), "排程明细已被其他用户修改，请重新计算方案");
        }
    }

    /** 全部明细确认成功后把方案置为已确认。 */
    private void markScenarioConfirmed(MfgScheduleScenario scenario, Long scenarioId,
                                       Long operatorId, String remark, LocalDateTime now) {
        scenario.setStatus(STATUS_CONFIRMED);
        scenario.setConfirmedBy(operatorId);
        scenario.setConfirmedTime(now);
        scenario.setUpdatedBy(operatorId);
        scenario.setUpdatedTime(now);
        if (baseMapper.updateById(scenario) == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(), "排程方案已被其他用户修改，请刷新后重试");
        }
        insertStatusChangeLog(scenarioId, STATUS_CALCULATED, STATUS_CONFIRMED, "MFG_SCHEDULE_SCENARIO",
                "CONFIRM_SCHEDULE_SCENARIO", operatorId, remark, now);
        insertOperationLog(scenarioId, "CONFIRM_SCHEDULE_SCENARIO", "mfg_schedule_scenario", operatorId, remark, now);
    }

    /**
     * 读取方案脱敏汇总并生成 AI 风险建议。AI 调用完成后才写回建议字段，避免模型不可用时产生半成品数据。
     */
    @Override
    public AiResponse generateAiAdvice(Long scenarioId, Integer version) {
        if (scenarioId == null || version == null) {
            throw invalid("方案和版本不能为空");
        }
        MfgScheduleScenario scenario = requireCalculatedScenario(scenarioId, version,
                "只有 CALCULATED 状态的排程方案可以生成 AI 风险建议");
        List<MfgScheduleItem> items = listActiveScenarioItems(scenarioId);
        AiResponse response = aiService.getScheduleScenarioAdvice(buildAiAdviceSummary(scenario, items));
        if (response == null || !StringUtils.hasText(response.getAnswer())) {
            throw new BusinessException(ResultCode.FAILED);
        }

        scenario.setAiAdvice(formatAiAdvice(response));
        scenario.setAiModel(response.getModel());
        scenario.setAiGeneratedTime(response.getGeneratedAt() == null ? LocalDateTime.now() : response.getGeneratedAt());
        if (baseMapper.updateById(scenario) == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(), "排程方案已被其他用户修改，请刷新后重试");
        }
        return response;
    }

    private Map<String, Object> buildAiAdviceSummary(MfgScheduleScenario scenario, List<MfgScheduleItem> items) {
        Map<String, Object> scenarioSummary = buildScenarioSummary(scenario);
        Map<Long, LineAiLoad> loadsByLine = new TreeMap<>();
        Map<String, Integer> conflictReasons = new TreeMap<>();
        for (MfgScheduleItem item : items == null ? List.<MfgScheduleItem>of() : items) {
            if (ITEM_STATUS_CONFLICT.equals(item.getStatus())) {
                String reason = StringUtils.hasText(item.getConflictReason())
                        ? item.getConflictReason().trim() : "未说明冲突原因";
                conflictReasons.merge(reason, 1, Integer::sum);
                continue;
            }
            if (!ITEM_STATUS_SCHEDULED.equals(item.getStatus()) || item.getLineId() == null) {
                continue;
            }
            LineAiLoad load = loadsByLine.computeIfAbsent(item.getLineId(), LineAiLoad::new);
            load.add(item);
        }

        List<Map<String, Object>> lineLoads = new ArrayList<>();
        for (LineAiLoad load : loadsByLine.values()) {
            lineLoads.add(load.toSummary());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scenario", scenarioSummary);
        summary.put("lineLoads", lineLoads);
        summary.put("conflictReasons", conflictReasons);
        return summary;
    }

    /** 方案头的脱敏摘要。 */
    private Map<String, Object> buildScenarioSummary(MfgScheduleScenario scenario) {
        Map<String, Object> scenarioSummary = new LinkedHashMap<>();
        scenarioSummary.put("algorithmCode", scenario.getAlgorithmCode());
        scenarioSummary.put("horizonStart", scenario.getHorizonStart());
        scenarioSummary.put("horizonEnd", scenario.getHorizonEnd());
        scenarioSummary.put("orderCount", scenario.getOrderCount());
        scenarioSummary.put("scheduledOrderCount", scenario.getScheduledOrderCount());
        scenarioSummary.put("conflictOrderCount", scenario.getConflictOrderCount());
        scenarioSummary.put("lateOrderCount", scenario.getLateOrderCount());
        scenarioSummary.put("totalLoadMinutes", scenario.getTotalLoadMinutes());
        return scenarioSummary;
    }

    /** 读取指定状态且版本一致的方案，供 AI 建议入口复用。 */
    private MfgScheduleScenario requireCalculatedScenario(Long scenarioId, Integer version, String statusMessage) {
        MfgScheduleScenario scenario = getActiveScenario(scenarioId);
        if (scenario == null) {
            throw notFound("排程方案不存在或已删除");
        }
        if (!STATUS_CALCULATED.equals(scenario.getStatus())) {
            throw invalid(statusMessage);
        }
        if (!Objects.equals(version, scenario.getVersion())) {
            throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(), "排程方案已被其他用户修改，请刷新后重试");
        }
        return scenario;
    }

    private String formatAiAdvice(AiResponse response) {
        StringBuilder advice = new StringBuilder(response.getAnswer().trim());
        if (response.getSuggestions() != null) {
            for (String suggestion : response.getSuggestions()) {
                appendSuggestion(advice, suggestion);
            }
        }
        return advice.toString();
    }

    /** 把一条非空建议追加为独立一行，空建议忽略。 */
    private void appendSuggestion(StringBuilder advice, String suggestion) {
        if (!StringUtils.hasText(suggestion)) return;
        advice.append(System.lineSeparator()).append("- ").append(suggestion.trim());
    }

    private MfgScheduleScenario getActiveScenario(Long scenarioId) {
        QueryWrapper<MfgScheduleScenario> wrapper = new QueryWrapper<>();
        wrapper.eq("scenario_id", scenarioId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    private boolean isAiBatchScenario(MfgScheduleScenario scenario) {
        return scenario != null && scenario.getScenarioNo() != null
                && scenario.getScenarioNo().startsWith(AI_BATCH_SCENARIO_PREFIX);
    }

    private void assertNotAiBatchScenario(MfgScheduleScenario scenario) {
        if (isAiBatchScenario(scenario)) {
            throw invalid("AI一键排产草案只能使用整批确认或整批撤回，不能按普通手工安排单独操作");
        }
    }

    private List<MfgScheduleItem> listActiveScenarioItems(Long scenarioId) {
        QueryWrapper<MfgScheduleItem> wrapper = new QueryWrapper<>();
        wrapper.eq("scenario_id", scenarioId);
        wrapper.eq("deleted_flag", 0);
        wrapper.orderByAsc("sequence_no");
        wrapper.orderByAsc("schedule_item_id");
        return mfgScheduleItemMapper.selectList(wrapper);
    }

    private List<MfgScheduleScenario> listEditableManualScenarios() {
        QueryWrapper<MfgScheduleScenario> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0)
                .eq("algorithm_code", ALGORITHM_CODE_MANUAL_BOARD)
                .in("status", STATUS_DRAFT, STATUS_CALCULATED)
                .orderByDesc("order_count")
                .orderByDesc("updated_time")
                .orderByDesc("scenario_id");
        return baseMapper.selectList(wrapper);
    }

    private List<MfgScheduleItem> listEditableManualItems(Set<Long> scenarioIds) {
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<MfgScheduleItem> wrapper = new QueryWrapper<>();
        wrapper.in("scenario_id", scenarioIds)
                .eq("deleted_flag", 0)
                .isNull("plan_id")
                .in("status", ITEM_STATUS_SCHEDULED, ITEM_STATUS_CONFLICT)
                .orderByAsc("planned_start_time")
                .orderByAsc("schedule_item_id");
        return mfgScheduleItemMapper.selectList(wrapper);
    }

    private boolean isEditableManualOccupancy(MfgScheduleItem item) {
        return item != null
                && item.getPlanId() == null
                && (ITEM_STATUS_SCHEDULED.equals(item.getStatus()) || ITEM_STATUS_CONFLICT.equals(item.getStatus()));
    }

    private List<MfgScheduleItem> listGlobalEditableManualItems() {
        Set<Long> scenarioIds = listEditableManualScenarios().stream()
                .map(MfgScheduleScenario::getScenarioId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return listEditableManualItems(scenarioIds);
    }

    private List<MfgProductionPlan> listActiveProductionPlans() {
        QueryWrapper<MfgProductionPlan> wrapper = new QueryWrapper<>();
        // RUNNING 也必须算占用：在制计划正占着这条线，漏掉它会把新订单排到同一时段，
        // 现场就会出现两张工单抢一台设备的情况。
        wrapper.eq("deleted_flag", 0).in("status", OCCUPYING_PLAN_STATUSES);
        return mfgProductionPlanMapper.selectList(wrapper);
    }

    private Map<Long, MfgProductionOrder> loadProductionOrders(Set<Long> productionOrderIds) {
        if (productionOrderIds == null || productionOrderIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<MfgProductionOrder> wrapper = new QueryWrapper<>();
        wrapper.in("production_order_id", productionOrderIds).eq("deleted_flag", 0);
        return mfgProductionOrderMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(MfgProductionOrder::getProductionOrderId, item -> item));
    }

    private ManualScheduleBoardItem toManualBoardItem(MfgScheduleItem item, MfgScheduleScenario scenario,
                                                       MfgProductionOrder order) {
        ManualScheduleBoardItem result = new ManualScheduleBoardItem();
        result.setSourceType("MANUAL");
        result.setAiBatch(isAiBatchScenario(scenario));
        result.setScenarioId(item.getScenarioId());
        result.setScenarioNo(scenario == null ? null : scenario.getScenarioNo());
        result.setScenarioVersion(scenario == null ? null : scenario.getVersion());
        result.setScheduleItemId(item.getScheduleItemId());
        result.setItemVersion(item.getVersion());
        result.setProductionOrderId(item.getProductionOrderId());
        result.setProductionOrderNo(order == null ? null : order.getProductionOrderNo());
        Long productId = item.getProductId();
        if (productId == null && order != null) {
            productId = order.getProductId();
        }
        result.setProductId(productId);
        result.setLineId(item.getLineId());
        result.setPlannedQuantity(item.getPlannedQuantity());
        result.setPlannedStartTime(item.getPlannedStartTime());
        result.setPlannedEndTime(item.getPlannedEndTime());
        result.setTardinessMinutes(item.getTardinessMinutes());
        result.setStatus(item.getStatus());
        result.setConflictReason(item.getConflictReason());
        result.setRemark(item.getRemark());
        result.setEditable(true);
        return result;
    }

    private ManualScheduleBoardItem toProductionPlanBoardItem(MfgProductionPlan plan, MfgProductionOrder order) {
        ManualScheduleBoardItem result = new ManualScheduleBoardItem();
        result.setSourceType("PLAN");
        result.setAiBatch(false);
        result.setPlanId(plan.getPlanId());
        result.setPlanNo(plan.getPlanNo());
        result.setProductionOrderId(plan.getProductionOrderId());
        result.setProductionOrderNo(order == null ? null : order.getProductionOrderNo());
        result.setProductId(order == null ? null : order.getProductId());
        result.setLineId(plan.getLineId());
        result.setPlannedQuantity(plan.getPlannedQuantity());
        result.setPlannedStartTime(plan.getPlannedStartTime());
        result.setPlannedEndTime(plan.getPlannedEndTime());
        result.setTardinessMinutes(order == null ? 0L
                : calculateTardiness(order.getDeliveryDate(), plan.getPlannedEndTime()));
        result.setStatus(plan.getStatus());
        result.setRemark(plan.getRemark());
        result.setEditable(false);
        return result;
    }

    private Map<String, Object> buildManualBoardAiSummary(ManualScheduleBoard board) {
        Map<Long, BoardLineAiLoad> loadsByLine = new TreeMap<>();
        Map<String, Integer> conflictReasons = new TreeMap<>();
        for (ManualScheduleBoardItem item : board.getTimelineItems()) {
            accumulateBoardLoad(item, loadsByLine, conflictReasons);
        }
        List<MfgProductionOrder> unassignedPendingOrders = unassignedPendingOrders(board);
        Map<String, Object> scope = buildBoardScope(board, unassignedPendingOrders);
        Map<String, Object> pending = buildPendingSummary(unassignedPendingOrders);
        List<Map<String, Object>> pendingPlacementOptions = buildManualBoardPendingPlacementOptions(board,
                unassignedPendingOrders);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("analysisType", "MANUAL_SCHEDULE_BOARD_ADVICE");
        summary.put("scope", scope);
        summary.put("lineLoads", loadsByLine.values().stream().map(BoardLineAiLoad::toSummary).toList());
        summary.put("pendingOrders", pending);
        summary.put("pendingPlacementOptions", pendingPlacementOptions);
        summary.put("conflictReasons", conflictReasons);
        return summary;
    }

    /** 按产线累计负荷，并按冲突原因计数。 */
    private void accumulateBoardLoad(ManualScheduleBoardItem item, Map<Long, BoardLineAiLoad> loadsByLine,
                                     Map<String, Integer> conflictReasons) {
        if (item.getLineId() != null) {
            loadsByLine.computeIfAbsent(item.getLineId(), BoardLineAiLoad::new).add(item);
        }
        if (StringUtils.hasText(item.getConflictReason())) {
            conflictReasons.merge(item.getConflictReason().trim(), 1, Integer::sum);
        }
    }

    /** 尚未被手工排程覆盖的待排订单。 */
    private List<MfgProductionOrder> unassignedPendingOrders(ManualScheduleBoard board) {
        Set<Long> manualOrderIds = board.getTimelineItems().stream()
                .filter(item -> "MANUAL".equals(item.getSourceType()))
                .map(ManualScheduleBoardItem::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return board.getPendingOrders().stream()
                .filter(order -> !manualOrderIds.contains(order.getProductionOrderId()))
                .toList();
    }

    /** 看板整体范围摘要。 */
    private Map<String, Object> buildBoardScope(ManualScheduleBoard board, List<MfgProductionOrder> unassignedPendingOrders) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("horizonStart", board.getHorizonStart());
        scope.put("horizonEnd", board.getHorizonEnd());
        scope.put("manualItemCount", board.getManualItemCount());
        scope.put("productionPlanCount", board.getProductionPlanCount());
        scope.put("pendingOrderCount", unassignedPendingOrders.size());
        scope.put("conflictCount", board.getConflictCount());
        scope.put("lateCount", board.getLateCount());
        return scope;
    }

    /** 待排订单的数量、品种与最早交期摘要。 */
    private Map<String, Object> buildPendingSummary(List<MfgProductionOrder> unassignedPendingOrders) {
        BigDecimal pendingQuantity = unassignedPendingOrders.stream()
                .map(MfgProductionOrder::getOrderQuantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate earliestDeliveryDate = unassignedPendingOrders.stream()
                .map(MfgProductionOrder::getDeliveryDate).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        long pendingProductCount = unassignedPendingOrders.stream()
                .map(MfgProductionOrder::getProductId).filter(Objects::nonNull).distinct().count();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("orderCount", unassignedPendingOrders.size());
        pending.put("productCount", pendingProductCount);
        pending.put("totalQuantity", pendingQuantity);
        pending.put("earliestDeliveryDate", earliestDeliveryDate);
        return pending;
    }

    private List<Map<String, Object>> buildManualBoardPendingPlacementOptions(ManualScheduleBoard board,
                                                                                List<MfgProductionOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        MfgScheduleScenario adviceScenario = resolveAdviceScenario(board);
        List<MfgScheduleItem> globalManualItems = listGlobalEditableManualItems();
        List<MfgProductionOrder> prioritizedOrders = prioritizePendingOrders(orders);
        List<Map<String, Object>> options = new ArrayList<>();
        for (int index = 0; index < prioritizedOrders.size(); index++) {
            options.add(buildPlacementOption(adviceScenario, prioritizedOrders.get(index), index + 1, globalManualItems));
        }
        return options;
    }

    /** AI 建议用的方案上下文：没有工作区草案时按看板范围临时构造。 */
    private MfgScheduleScenario resolveAdviceScenario(ManualScheduleBoard board) {
        MfgScheduleScenario adviceScenario = board.getWorkspaceDraft();
        if (adviceScenario != null) {
            return adviceScenario;
        }
        MfgScheduleScenario transientScenario = new MfgScheduleScenario();
        transientScenario.setHorizonStart(board.getHorizonStart());
        transientScenario.setHorizonEnd(board.getHorizonEnd());
        return transientScenario;
    }

    /** 待排订单按交期、订单号排序并截断到建议条数上限。 */
    private List<MfgProductionOrder> prioritizePendingOrders(List<MfgProductionOrder> orders) {
        return orders.stream()
                .sorted(Comparator.comparing(MfgProductionOrder::getDeliveryDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(MfgProductionOrder::getProductionOrderNo,
                                Comparator.nullsLast(String::compareTo)))
                .limit(MANUAL_BOARD_AI_ADVICE_ORDER_LIMIT)
                .toList();
    }

    /** 组装单张待排订单的候选产线建议。 */
    private Map<String, Object> buildPlacementOption(MfgScheduleScenario adviceScenario, MfgProductionOrder order,
                                                     int reference, List<MfgScheduleItem> globalManualItems) {
        List<ManualScheduleCandidateLine> candidates = buildEligibleLines(adviceScenario, order, globalManualItems).stream()
                .limit(MANUAL_BOARD_AI_ADVICE_LINE_LIMIT)
                .toList();
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("reference", "P" + reference);
        option.put("productId", order.getProductId());
        option.put("orderQuantity", order.getOrderQuantity());
        option.put("deliveryDate", order.getDeliveryDate());
        option.put("candidateLineOptions", formatManualBoardAiCandidateOptions(candidates));
        return option;
    }

    private String formatManualBoardAiCandidateOptions(List<ManualScheduleCandidateLine> candidates) {
        if (candidates.isEmpty()) {
            return "当前没有可直接安排的产线窗口；请优先释放最早可用产线，或协调可替代产线后再安排。";
        }
        return candidates.stream()
                .map(candidate -> {
                    String line = StringUtils.hasText(candidate.getLineCode())
                            ? candidate.getLineCode()
                            : "产线" + candidate.getLineId();
                    String startTime = formatManualBoardAiTime(candidate.getEarliestStartTime());
                    String endTime = formatManualBoardAiTime(candidate.getEarliestEndTime());
                    String workingMinutes = candidate.getMinimumWorkingMinutes() == null
                            ? "--"
                            : candidate.getMinimumWorkingMinutes().toString();
                    String priority = candidate.getPriorityLevel() == null
                            ? "--"
                            : candidate.getPriorityLevel().toString();
                    return line + "：最早可排 " + startTime
                            + "，预计完成 " + endTime
                            + "，建议预留 " + workingMinutes + " 分钟产线工时"
                            + "，优先级 " + priority;
                })
                .collect(Collectors.joining("；"));
    }

    private String formatManualBoardAiTime(LocalDateTime time) {
        return time == null ? "--" : time.format(MANUAL_BOARD_AI_TIME_FORMATTER);
    }

    private List<ConfirmationEntry> validateAndLoadConfirmationEntries(MfgScheduleScenario scenario,
                                                                         List<MfgScheduleItem> items) {
        if (items == null || items.isEmpty()) {
            throw invalid("排程方案没有可确认的排程明细");
        }
        List<ConfirmationEntry> entries = new ArrayList<>();
        Set<Long> productionOrderIds = new HashSet<>();
        for (MfgScheduleItem item : items) {
            validateScheduledItem(item, productionOrderIds);
            if (hasActivePlan(item.getProductionOrderId())) {
                throw invalid("生产订单已存在有效生产计划，不能重复确认");
            }
            MfgProductionOrder order = getActiveProductionOrder(item.getProductionOrderId());
            if (order == null) {
                throw notFound("排程明细关联的生产订单不存在或已删除");
            }
            if (!STATUS_DRAFT.equals(order.getStatus())) {
                throw invalid("生产订单不是待排新建状态或状态已变化，不能确认排程方案");
            }
            if (order.getVersion() == null) {
                throw invalid("生产订单缺少版本号，不能确认排程方案");
            }
            String placementProblem = validateManualPlacement(scenario, item, order, item.getLineId(),
                    item.getPlannedStartTime(), item.getPlannedEndTime(), items);
            if (placementProblem != null) {
                throw invalid("生产订单 " + order.getProductionOrderNo() + " 排程已失效：" + placementProblem);
            }
            entries.add(new ConfirmationEntry(item, order));
        }
        return entries;
    }

    private void validateScheduledItem(MfgScheduleItem item, Set<Long> productionOrderIds) {
        requireConfirmableItem(item);
        assertItemStatusScheduled(item);
        assertItemNotPlanned(item);
        assertOrderUnique(item.getProductionOrderId(), productionOrderIds);
        assertItemScheduleComplete(item);
        assertItemVersionPresent(item);
        assertItemStartNotPast(item.getPlannedStartTime());
    }

    /** 排程明细必须存在且带主键。 */
    private void requireConfirmableItem(MfgScheduleItem item) {
        if (item == null || item.getScheduleItemId() == null) {
            throw invalid("排程明细数据不完整");
        }
    }

    /** 冲突明细与未排程明细都不能进入确认。 */
    private void assertItemStatusScheduled(MfgScheduleItem item) {
        if (ITEM_STATUS_CONFLICT.equals(item.getStatus())) {
            throw invalid("方案存在 CONFLICT 排程明细，不能确认");
        }
        if (!ITEM_STATUS_SCHEDULED.equals(item.getStatus())) {
            throw invalid("排程明细不是 SCHEDULED 状态，不能确认");
        }
    }

    /** 已生成生产计划的明细不能重复确认。 */
    private void assertItemNotPlanned(MfgScheduleItem item) {
        if (item.getPlanId() != null) {
            throw invalid("排程明细已关联生产计划，不能重复确认");
        }
    }

    /** 同一生产订单在方案内只能出现一次。 */
    private void assertOrderUnique(Long productionOrderId, Set<Long> productionOrderIds) {
        if (productionOrderId == null || !productionOrderIds.add(productionOrderId)) {
            throw invalid("排程方案存在重复或缺失的生产订单");
        }
    }

    /** 已排程明细必须有产线、正数序号与数量、合法的起止时间。 */
    private void assertItemScheduleComplete(MfgScheduleItem item) {
        boolean linePresent = item.getLineId() != null;
        boolean sequenceValid = item.getSequenceNo() != null && item.getSequenceNo() > 0;
        if (!linePresent || !sequenceValid || !isQuantityAndTimeValid(item)) {
            throw invalid("已排程明细缺少有效的计划产线、数量或时间");
        }
    }

    /** 数量为正且起止时间有效。 */
    private boolean isQuantityAndTimeValid(MfgScheduleItem item) {
        return isPositive(item.getPlannedQuantity())
                && isOrderedRange(item.getPlannedStartTime(), item.getPlannedEndTime());
    }

    /** 数量非空且大于零。 */
    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /** 起止时间都非空且结束晚于开始。 */
    private boolean isOrderedRange(LocalDateTime start, LocalDateTime end) {
        return start != null && end != null && end.isAfter(start);
    }

    /** 明细必须带版本号才能按乐观锁确认。 */
    private void assertItemVersionPresent(MfgScheduleItem item) {
        if (item.getVersion() == null) {
            throw invalid("排程明细缺少版本号，不能确认");
        }
    }

    /** 计划开始时间过早（已过去超过两分钟）视为失效。 */
    private void assertItemStartNotPast(LocalDateTime plannedStartTime) {
        if (plannedStartTime.isBefore(LocalDateTime.now(SCHEDULE_ZONE).minusMinutes(2))) {
            throw invalid("排程明细的计划开始时间已过，请重新编排并校验");
        }
    }

    private boolean hasActivePlan(Long productionOrderId) {
        QueryWrapper<MfgProductionPlan> wrapper = new QueryWrapper<>();
        wrapper.eq("production_order_id", productionOrderId);
        wrapper.eq("deleted_flag", 0);
        wrapper.ne("status", STATUS_CLOSED);
        return mfgProductionPlanMapper.selectCount(wrapper) > 0;
    }

    private MfgProductionOrder getActiveProductionOrder(Long productionOrderId) {
        QueryWrapper<MfgProductionOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("production_order_id", productionOrderId);
        wrapper.eq("deleted_flag", 0);
        return mfgProductionOrderMapper.selectOne(wrapper);
    }

    private MfgProductionPlan createProductionPlan(MfgScheduleScenario scenario, MfgScheduleItem item,
                                                    Long operatorId, LocalDateTime now) {
        MfgProductionPlan plan = new MfgProductionPlan();
        plan.setPlanNo(numbers.nextFor("PL","PRODUCTION_ORDER",item.getProductionOrderId()));
        plan.setProductionOrderId(item.getProductionOrderId());
        plan.setLineId(item.getLineId());
        plan.setPlannedStartTime(item.getPlannedStartTime());
        plan.setPlannedEndTime(item.getPlannedEndTime());
        plan.setPlannedQuantity(item.getPlannedQuantity());
        plan.setStatus(STATUS_RELEASED);
        plan.setRemark("由排程方案 " + scenario.getScenarioNo() + " 确认生成");
        plan.setVersion(0);
        plan.setCreatedBy(operatorId);
        plan.setCreatedTime(now);
        plan.setUpdatedBy(operatorId);
        plan.setUpdatedTime(now);
        plan.setDeletedFlag(0);
        return plan;
    }

    private void insertStatusChangeLog(Long bizId, String oldStatus, String newStatus, String bizType,
                                       String reason, Long operatorId, String remark, LocalDateTime changedTime) {
        SysStatusChangeLog statusLog = new SysStatusChangeLog();
        statusLog.setBizType(bizType);
        statusLog.setBizId(bizId);
        statusLog.setOldStatus(oldStatus);
        statusLog.setNewStatus(newStatus);
        statusLog.setChangeReason(reason);
        statusLog.setChangedBy(operatorId);
        statusLog.setChangedTime(changedTime);
        statusLog.setCreatedBy(operatorId);
        statusLog.setCreatedTime(changedTime);
        statusLog.setUpdatedBy(operatorId);
        statusLog.setUpdatedTime(changedTime);
        statusLog.setRemark(remark);
        statusLog.setDeletedFlag(0);
        if (sysStatusChangeLogMapper.insert(statusLog) == 0) {
            throw new BusinessException(ResultCode.FAILED);
        }
    }

    private void insertOperationLog(Long bizId, String operationType, String moduleCode,
                                    Long operatorId, String remark, LocalDateTime operationTime) {
        SysOperationLog operationLog = new SysOperationLog();
        operationLog.setUserId(operatorId);
        operationLog.setOperationType(operationType);
        operationLog.setModuleCode(moduleCode);
        operationLog.setBizId(bizId);
        operationLog.setOperationTime(operationTime);
        operationLog.setCreatedBy(operatorId);
        operationLog.setCreatedTime(operationTime);
        operationLog.setUpdatedBy(operatorId);
        operationLog.setUpdatedTime(operationTime);
        operationLog.setRemark(remark);
        operationLog.setDeletedFlag(0);
        if (sysOperationLogMapper.insert(operationLog) == 0) {
            throw new BusinessException(ResultCode.FAILED);
        }
    }

    private MfgScheduleScenario requireManualScenario(Long scenarioId, boolean requireDraft) {
        MfgScheduleScenario scenario = getActiveScenario(scenarioId);
        if (scenario == null || !ALGORITHM_CODE_MANUAL_BOARD.equals(scenario.getAlgorithmCode())) {
            throw notFound("手工排程草案不存在或已删除");
        }
        if (requireDraft && !STATUS_DRAFT.equals(scenario.getStatus())) {
            throw invalid("当前临时安排已不能继续编辑，请刷新排程总览后重新操作");
        }
        if (!STATUS_DRAFT.equals(scenario.getStatus()) && !STATUS_CALCULATED.equals(scenario.getStatus())) {
            throw invalid("当前临时安排已不能继续操作，请刷新排程总览后重新选择");
        }
        return scenario;
    }

    private void assertScenarioVersion(MfgScheduleScenario scenario, Integer version) {
        if (version == null || !Objects.equals(version, scenario.getVersion())) {
            throw conflict("排程已更新，请刷新排程总览后重新操作");
        }
    }

    private MfgScheduleScenario requireManualAiBatch(Long scenarioId, Integer version, Long operatorId) {
        if (scenarioId == null || version == null || operatorId == null) {
            throw invalid("一键排产草案、版本和操作人不能为空");
        }
        MfgScheduleScenario scenario = requireOwnedAiBatch(scenarioId, operatorId);
        assertAiBatchOperable(scenario);
        assertScenarioVersion(scenario, version);
        return scenario;
    }

    /** 取当前操作人自己的 AI 一键排产草案。 */
    private MfgScheduleScenario requireOwnedAiBatch(Long scenarioId, Long operatorId) {
        MfgScheduleScenario scenario = getActiveScenario(scenarioId);
        if (scenario == null || !ALGORITHM_CODE_MANUAL_BOARD.equals(scenario.getAlgorithmCode())
                || !isAiBatchScenario(scenario)) {
            throw notFound("一键排产草案不存在或已删除");
        }
        if (!Objects.equals(scenario.getCreatedBy(), operatorId)) {
            throw invalid("只能操作自己生成的一键排产草案");
        }
        return scenario;
    }

    /** 草案必须仍处于可操作的草稿或已计算状态。 */
    private void assertAiBatchOperable(MfgScheduleScenario scenario) {
        if (!STATUS_DRAFT.equals(scenario.getStatus()) && !STATUS_CALCULATED.equals(scenario.getStatus())) {
            throw invalid("当前一键排产草案已不能继续操作，请刷新排程总览");
        }
    }

    private MfgScheduleScenario findActiveManualAiBatch(Long operatorId) {
        QueryWrapper<MfgScheduleScenario> query = new QueryWrapper<>();
        query.eq("deleted_flag", 0)
                .eq("algorithm_code", ALGORITHM_CODE_MANUAL_BOARD)
                .eq("created_by", operatorId)
                .in("status", STATUS_DRAFT, STATUS_CALCULATED)
                .orderByDesc("updated_time")
                .orderByDesc("scenario_id");
        for (MfgScheduleScenario scenario : baseMapper.selectList(query)) {
            if (isAiBatchScenario(scenario)
                    && !listEditableManualItems(Set.of(scenario.getScenarioId())).isEmpty()) {
                return scenario;
            }
        }
        return null;
    }

    private List<MfgProductionOrder> listDraftOrdersAvailableForAiBatch() {
        Set<Long> occupiedOrderIds = listGlobalEditableManualItems().stream()
                .map(MfgScheduleItem::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return listDraftOrdersWithoutPlan().stream()
                .filter(order -> !occupiedOrderIds.contains(order.getProductionOrderId()))
                .toList();
    }

    private MfgScheduleItem createAiBatchScheduledItem(MfgScheduleScenario scenario, MfgProductionOrder order,
                                                       ManualScheduleCandidateLine candidate, int sequenceNo,
                                                       int totalOrderCount, Long operatorId, LocalDateTime now) {
        MfgScheduleItem item = createAiBatchBaseItem(scenario, order, sequenceNo, totalOrderCount, operatorId, now);
        item.setRouteId(candidate.getRouteId());
        item.setLineId(candidate.getLineId());
        item.setPlannedStartTime(candidate.getEarliestStartTime());
        item.setPlannedEndTime(candidate.getEarliestEndTime());
        item.setEstimatedMinutes(candidate.getEstimatedMinutes());
        item.setTardinessMinutes(calculateTardiness(order.getDeliveryDate(), candidate.getEarliestEndTime()));
        item.setStatus(ITEM_STATUS_SCHEDULED);
        item.setExplanation("AI一键排产按交期顺序自动选择最早可用产线；适配优先级="
                + candidate.getPriorityLevel() + "，产品能力系数=" + candidate.getCapacityFactor());
        return item;
    }

    private MfgScheduleItem createAiBatchConflictItem(MfgScheduleScenario scenario, MfgProductionOrder order,
                                                      int sequenceNo, int totalOrderCount, String reason,
                                                      Long operatorId, LocalDateTime now) {
        MfgScheduleItem item = createAiBatchBaseItem(scenario, order, sequenceNo, totalOrderCount, operatorId, now);
        item.setStatus(ITEM_STATUS_CONFLICT);
        item.setConflictReason(reason);
        item.setTardinessMinutes(0L);
        item.setExplanation("AI一键排产未能生成可确认时间块：" + reason);
        return item;
    }

    private MfgScheduleItem createAiBatchBaseItem(MfgScheduleScenario scenario, MfgProductionOrder order,
                                                  int sequenceNo, int totalOrderCount,
                                                  Long operatorId, LocalDateTime now) {
        MfgScheduleItem item = new MfgScheduleItem();
        item.setScenarioId(scenario.getScenarioId());
        item.setProductionOrderId(order.getProductionOrderId());
        item.setProductId(order.getProductId());
        item.setSequenceNo(sequenceNo);
        item.setPlannedQuantity(order.getOrderQuantity());
        item.setPriorityScore(BigDecimal.valueOf(totalOrderCount - sequenceNo + 1L));
        item.setDecisionSource(DECISION_SOURCE_MANUAL);
        item.setRemark("AI一键排产批次：" + scenario.getScenarioNo());
        item.setVersion(0);
        item.setCreatedBy(operatorId);
        item.setCreatedTime(now);
        item.setUpdatedBy(operatorId);
        item.setUpdatedTime(now);
        item.setDeletedFlag(0);
        return item;
    }

    private MfgProductionOrder requireDraftOrderWithoutPlan(Long productionOrderId) {
        if (productionOrderId == null) {
            throw invalid("生产订单不能为空");
        }
        MfgProductionOrder order = getActiveProductionOrder(productionOrderId);
        if (order == null) {
            throw notFound("生产订单不存在或已删除");
        }
        if (hasActivePlan(productionOrderId)) {
            throw invalid("该订单已生成生产计划，请在生产计划中查看或调整");
        }
        if (!STATUS_DRAFT.equals(order.getStatus())) {
            throw invalid("只有新建且尚未生成生产计划的订单可以手工排程");
        }
        if (order.getProductId() == null || order.getOrderQuantity() == null
                || order.getOrderQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw invalid("生产订单缺少有效产品或数量");
        }
        return order;
    }

    private MfgScheduleItem requireManualItem(Long scenarioId, Long scheduleItemId) {
        if (scheduleItemId == null) {
            throw invalid("排程明细不能为空");
        }
        QueryWrapper<MfgScheduleItem> query = new QueryWrapper<>();
        query.eq("schedule_item_id", scheduleItemId).eq("scenario_id", scenarioId).eq("deleted_flag", 0);
        MfgScheduleItem item = mfgScheduleItemMapper.selectOne(query);
        if (item == null) {
            throw notFound("排程明细不存在或已删除");
        }
        if (!DECISION_SOURCE_MANUAL.equals(item.getDecisionSource())) {
            throw invalid("当前明细不是手工编排记录");
        }
        return item;
    }

    /**
     * 找出该订单在本草案里可以复用的旧排程明细。
     * 除了明确删除过的明细，还要把"计划已经撤回"的明细算进来：
     * 计划被撤回后置为 CLOSED（或者被软删除），但当时的历史数据里明细仍留着 plan_id，
     * 既不会出现在排程总览（plan_id 不为空、状态是 CONFIRMED），又会拦住订单重新加入。
     * 这类明细要能直接复用，才不会残留一个永远占位的幽灵排程项。
     */
    private MfgScheduleItem findRemovedScenarioOrderItem(Long scenarioId, Long productionOrderId) {
        QueryWrapper<MfgScheduleItem> query = new QueryWrapper<>();
        query.eq("scenario_id", scenarioId)
                .eq("production_order_id", productionOrderId)
                .orderByAsc("schedule_item_id");
        List<MfgScheduleItem> candidates = mfgScheduleItemMapper.selectList(query);
        if (candidates.isEmpty()) {
            return null;
        }
        Map<Long, MfgProductionPlan> plansById = loadPlansById(candidates);
        for (MfgScheduleItem candidate : candidates) {
            if (isStaleScheduleItem(candidate, plansById)) {
                return candidate;
            }
        }
        return null;
    }

    /** 批量取出排程明细关联的计划，供"计划是否已失效"的判断复用。 */
    private Map<Long, MfgProductionPlan> loadPlansById(List<MfgScheduleItem> items) {
        Set<Long> planIds = items.stream().map(MfgScheduleItem::getPlanId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (planIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<MfgProductionPlan> query = new QueryWrapper<>();
        query.in("plan_id", planIds);
        return mfgProductionPlanMapper.selectList(query).stream()
                .collect(Collectors.toMap(MfgProductionPlan::getPlanId, plan -> plan, (left, right) -> left));
    }

    /**
     * 明细是否已经失效：已删除，或者它关联的生产计划已经撤回/删除。
     * 失效明细不再占住订单的排程位置。
     */
    private boolean isStaleScheduleItem(MfgScheduleItem item, Map<Long, MfgProductionPlan> plansById) {
        boolean stale;
        if (item == null) {
            stale = true;
        } else if (isDeleted(item.getDeletedFlag())) {
            stale = true;
        } else if (item.getPlanId() == null) {
            stale = false;
        } else {
            MfgProductionPlan plan = plansById.get(item.getPlanId());
            stale = plan == null || isDeleted(plan.getDeletedFlag()) || STATUS_CLOSED.equals(plan.getStatus());
        }
        return stale;
    }

    private boolean isDeleted(Integer deletedFlag) {
        return Integer.valueOf(1).equals(deletedFlag);
    }

    /** 入参中任一为 null 时抛出参数错误，错误文案由调用方给出。 */
    private void requireAllPresent(String message, Object... values) {
        for (Object value : values) {
            if (value == null) {
                throw invalid(message);
            }
        }
    }

    private void restoreRemovedManualItem(MfgScheduleItem item, MfgScheduleItem removedItem) {
        if (!DECISION_SOURCE_MANUAL.equals(removedItem.getDecisionSource())) {
            throw invalid("当前草案中的历史排程明细不是人工编排记录，不能重新加入");
        }
        int currentVersion = removedItem.getVersion() == null ? 0 : removedItem.getVersion();
        UpdateWrapper<MfgScheduleItem> update = new UpdateWrapper<>();
        update.eq("schedule_item_id", removedItem.getScheduleItemId())
                .eq("version", currentVersion)
                .set("product_id", item.getProductId())
                .set("route_id", item.getRouteId())
                .set("line_id", item.getLineId())
                .set("plan_id", null)
                .set("sequence_no", item.getSequenceNo())
                .set("planned_quantity", item.getPlannedQuantity())
                .set("planned_start_time", item.getPlannedStartTime())
                .set("planned_end_time", item.getPlannedEndTime())
                .set("estimated_minutes", item.getEstimatedMinutes())
                .set("priority_score", item.getPriorityScore())
                .set("tardiness_minutes", item.getTardinessMinutes())
                .set("decision_source", item.getDecisionSource())
                .set("status", item.getStatus())
                .set("conflict_reason", null)
                .set("explanation", item.getExplanation())
                .set("remark", null)
                .set("deleted_flag", 0)
                .set("version", currentVersion + 1)
                .set("updated_by", item.getUpdatedBy())
                .set("updated_time", item.getUpdatedTime());
        if (mfgScheduleItemMapper.update(null, update) == 0) {
            throw conflict("排程明细已被其他用户修改，请刷新后重试");
        }
        item.setScheduleItemId(removedItem.getScheduleItemId());
        item.setVersion(currentVersion + 1);
        item.setCreatedBy(removedItem.getCreatedBy());
        item.setCreatedTime(removedItem.getCreatedTime());
    }

    private void ensureOrderNotInEditableScenario(Long productionOrderId, Long currentScenarioId) {
        QueryWrapper<MfgScheduleItem> itemQuery = new QueryWrapper<>();
        itemQuery.eq("production_order_id", productionOrderId).eq("deleted_flag", 0);
        List<MfgScheduleItem> items = mfgScheduleItemMapper.selectList(itemQuery);
        Map<Long, MfgProductionPlan> plansById = loadPlansById(items);
        for (MfgScheduleItem item : items) {
            // 计划已撤回（CLOSED）或被删除的明细已经失效，不能再占住订单的排程位置。
            if (isStaleScheduleItem(item, plansById)) {
                continue;
            }
            if (Objects.equals(item.getScenarioId(), currentScenarioId)) {
                throw invalid("该订单已在当前排程中，无需重复添加");
            }
            MfgScheduleScenario scenario = getActiveScenario(item.getScenarioId());
            if (scenario != null && ALGORITHM_CODE_MANUAL_BOARD.equals(scenario.getAlgorithmCode())
                    && (STATUS_DRAFT.equals(scenario.getStatus()) || STATUS_CALCULATED.equals(scenario.getStatus()))) {
                throw invalid("该订单已在排程总览中，无需重复添加");
            }
        }
    }

    private int nextSequenceNo(List<MfgScheduleItem> items) {
        return items.stream().map(MfgScheduleItem::getSequenceNo)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
    }

    private List<ManualScheduleCandidateLine> buildEligibleLines(MfgScheduleScenario scenario, MfgProductionOrder order) {
        return buildEligibleLines(scenario, order, listGlobalEditableManualItems());
    }

    private List<ManualScheduleCandidateLine> buildEligibleLines(MfgScheduleScenario scenario, MfgProductionOrder order,
                                                                  List<MfgScheduleItem> items) {
        LocalDateTime now = LocalDateTime.now(SCHEDULE_ZONE);
        // 最早可用时刻不能退回到当天零点：那是班次开始，不是"现在"。
        // 用 now 当起点，buildLineWindows 会把今天已经过去的班次裁掉，
        // 正在进行的班次按剩余时长折算产能，排出来的开始时间才落在当前时刻之后。
        LocalDateTime horizonStart = maxTime(scenario.getHorizonStart(), now);
        LocalDateTime horizonEnd = scenario.getHorizonEnd() == null || scenario.getHorizonEnd().isBefore(horizonStart.plusDays(30))
                ? horizonStart.plusDays(30).withHour(23).withMinute(59).withSecond(59) : scenario.getHorizonEnd();
        SchedulingContext context = loadSchedulingContext(horizonStart, horizonEnd);
        List<MfgProductionPlan> activePlans = listActiveProductionPlans();
        List<ManualScheduleCandidateLine> result = new ArrayList<>();
        for (MfgLineProductCapability capability : context.capabilitiesByProduct()
                .getOrDefault(order.getProductId(), List.of())) {
            FactoryLine line = context.activeLines().get(capability.getLineId());
            ProcessRoute route = context.releasedRoutes().get(capability.getRouteId());
            RouteWorkload workload = context.routeWorkloads().get(capability.getRouteId());
            if (!isUsableCapability(line, route, workload, order, capability)) {
                continue;
            }
            LocalDateTime lineAvailableAt = lineAvailableAt(horizonStart, line, items, activePlans);
            List<CapacityWindow> lineWindows = buildLineWindows(line,
                    context.calendarRulesByLine().getOrDefault(line.getLineId(), List.of()), context.shiftsById(),
                    lineAvailableAt, horizonEnd);
            LineScheduleState state = new LineScheduleState(lineWindows);
            BigDecimal requiredCapacity = order.getOrderQuantity().divide(capability.getCapacityFactor(),
                    CAPACITY_SCALE, RoundingMode.HALF_UP);
            Allocation allocation = state.allocate(requiredCapacity, workload, order.getOrderQuantity());
            if (allocation == null) {
                continue;
            }
            ManualScheduleCandidateLine candidate = new ManualScheduleCandidateLine();
            candidate.setLineId(line.getLineId());
            candidate.setLineCode(line.getLineCode());
            candidate.setLineName(line.getLineName());
            candidate.setWorkshopId(line.getWorkshopId());
            candidate.setRouteId(route.getRouteId());
            candidate.setPriorityLevel(capability.getPriorityLevel());
            candidate.setCapacityFactor(capability.getCapacityFactor());
            candidate.setEstimatedMinutes(workload.estimate(order.getOrderQuantity()));
            candidate.setEarliestStartTime(allocation.startTime());
            candidate.setEarliestEndTime(allocation.endTime());
            candidate.setMinimumWorkingMinutes(calculateWorkingMinutes(lineWindows,
                    allocation.startTime(), allocation.endTime()));
            result.add(candidate);
        }
        result.sort(Comparator.comparing(ManualScheduleCandidateLine::getEarliestEndTime)
                .thenComparing(ManualScheduleCandidateLine::getPriorityLevel, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(ManualScheduleCandidateLine::getLineId));
        return result;
    }

    /** 产线可用性：产线在运行、路线属于该产品、有工时模型、产能系数为正。 */
    private boolean isUsableCapability(FactoryLine line, ProcessRoute route, RouteWorkload workload,
                                       MfgProductionOrder order, MfgLineProductCapability capability) {
        return isRunningLine(line) && isRouteOfProduct(route, order) && workload != null
                && isPositive(capability.getCapacityFactor());
    }

    /** 产线存在且处于运行状态。 */
    private boolean isRunningLine(FactoryLine line) {
        return line != null && STATUS_RUNNING.equals(line.getStatus());
    }

    /** 工艺路线属于该生产订单的产品。 */
    private boolean isRouteOfProduct(ProcessRoute route, MfgProductionOrder order) {
        return route != null && Objects.equals(route.getProductId(), order.getProductId());
    }

    /** 产线最早可开工时刻：取时间窗起点、已有明细与在制计划的最晚结束时间。 */
    private LocalDateTime lineAvailableAt(LocalDateTime horizonStart, FactoryLine line,
                                           List<MfgScheduleItem> items, List<MfgProductionPlan> activePlans) {
        LocalDateTime lineAvailableAt = horizonStart;
        for (MfgScheduleItem item : items) {
            if (Objects.equals(item.getLineId(), line.getLineId()) && item.getPlannedEndTime() != null) {
                lineAvailableAt = maxTime(lineAvailableAt, item.getPlannedEndTime());
            }
        }
        for (MfgProductionPlan plan : activePlans) {
            if (Objects.equals(plan.getLineId(), line.getLineId()) && plan.getPlannedEndTime() != null) {
                lineAvailableAt = maxTime(lineAvailableAt, plan.getPlannedEndTime());
            }
        }
        return lineAvailableAt;
    }

    private void cacheEligibleLines(MfgScheduleScenario scenario, MfgProductionOrder order,
                                    List<ManualScheduleCandidateLine> candidates) {
        long now = System.nanoTime();
        if (manualCandidateListCache.size() > MANUAL_CANDIDATE_CACHE_MAX_SIZE) {
            manualCandidateListCache.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
            if (manualCandidateListCache.size() > MANUAL_CANDIDATE_CACHE_MAX_SIZE) {
                manualCandidateListCache.clear();
            }
        }
        long expiresAt = now + MANUAL_CANDIDATE_CACHE_TTL_NANOS;
        ManualCandidateGroupKey groupKey = new ManualCandidateGroupKey(scenario.getScenarioId(), scenario.getVersion(),
                order.getProductionOrderId());
        manualCandidateListCache.put(groupKey,
                new ManualCandidateListCacheEntry(List.copyOf(candidates), expiresAt));
    }

    private List<ManualScheduleCandidateLine> findCachedEligibleLines(MfgScheduleScenario scenario,
                                                                       MfgProductionOrder order) {
        ManualCandidateGroupKey key = new ManualCandidateGroupKey(scenario.getScenarioId(), scenario.getVersion(),
                order.getProductionOrderId());
        ManualCandidateListCacheEntry entry = manualCandidateListCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtNanos() <= System.nanoTime()) {
            manualCandidateListCache.remove(key, entry);
            return null;
        }
        return entry.candidates();
    }

    private void invalidateCandidateCache(Long scenarioId) {
        manualCandidateListCache.clear();
    }

    private ManualScheduleCandidateLine resolveManualCapability(MfgProductionOrder order, Long lineId,
                                                                LocalDateTime startTime, LocalDateTime endTime) {
        if (lineId == null || startTime == null || endTime == null) {
            throw invalid("产线和计划时间不能为空");
        }
        SchedulingContext context = loadSchedulingContext(startTime.toLocalDate().atStartOfDay(),
                endTime.toLocalDate().plusDays(1).atStartOfDay());
        FactoryLine line = context.activeLines().get(lineId);
        if (line == null || !STATUS_RUNNING.equals(line.getStatus())) {
            throw invalid("产线不存在、已删除或未处于 RUNNING 状态");
        }
        for (MfgLineProductCapability capability : context.capabilitiesByProduct()
                .getOrDefault(order.getProductId(), List.of())) {
            if (!isCapableOfLine(capability, lineId)) {
                continue;
            }
            ProcessRoute route = context.releasedRoutes().get(capability.getRouteId());
            RouteWorkload workload = context.routeWorkloads().get(capability.getRouteId());
            if (!isRouteUsableForOrder(route, workload, order)) {
                continue;
            }
            ManualScheduleCandidateLine result = new ManualScheduleCandidateLine();
            result.setLineId(lineId);
            result.setLineCode(line.getLineCode());
            result.setLineName(line.getLineName());
            result.setWorkshopId(line.getWorkshopId());
            result.setRouteId(route.getRouteId());
            result.setPriorityLevel(capability.getPriorityLevel());
            result.setCapacityFactor(capability.getCapacityFactor());
            result.setEstimatedMinutes(workload.estimate(order.getOrderQuantity()));
            return result;
        }
        throw invalid("所选产线没有该产品的启用能力或已发布路线");
    }

    /** 能力记录是否属于目标产线且产能系数为正。 */
    private boolean isCapableOfLine(MfgLineProductCapability capability, Long lineId) {
        boolean sameLine = Objects.equals(capability.getLineId(), lineId);
        boolean positiveFactor = capability.getCapacityFactor() != null
                && capability.getCapacityFactor().compareTo(BigDecimal.ZERO) > 0;
        return sameLine && positiveFactor;
    }

    /** 路线属于该产品且有可用工时模型。 */
    private boolean isRouteUsableForOrder(ProcessRoute route, RouteWorkload workload, MfgProductionOrder order) {
        boolean routeUsable = route != null && Objects.equals(route.getProductId(), order.getProductId());
        return routeUsable && workload != null;
    }

    private String validateManualPlacement(MfgScheduleScenario scenario, MfgScheduleItem currentItem,
                                           MfgProductionOrder order, Long lineId, LocalDateTime startTime,
                                           LocalDateTime endTime, List<MfgScheduleItem> allItems) {
        String message = validatePlacementWindow(scenario, startTime, endTime);
        if (message == null) {
            message = validatePlacementCapacity(order, lineId, startTime, endTime);
        }
        if (message == null) {
            message = validatePlacementOverlap(currentItem, lineId, startTime, endTime, allItems);
        }
        return message;
    }

    /** 校验计划时间本身：起止顺序、是否已过、是否落在排程窗口内。 */
    private String validatePlacementWindow(MfgScheduleScenario scenario, LocalDateTime startTime, LocalDateTime endTime) {
        String message = null;
        if (!isOrderedRange(startTime, endTime)) {
            message = "计划开始时间必须早于结束时间";
        } else if (startTime.isBefore(LocalDateTime.now(SCHEDULE_ZONE).minusMinutes(2))) {
            message = "计划开始时间已过，请重新安排未来有效时间";
        } else if (scenario.getHorizonStart() != null && startTime.isBefore(scenario.getHorizonStart())) {
            message = "计划开始时间早于排程窗口";
        } else if (isBeyondHorizonEnd(scenario, endTime)) {
            message = "计划结束时间超出排程窗口";
        }
        return message;
    }

    /** 手工看板的结束时间不受排程窗口上限约束，其余算法都要校验。 */
    private boolean isBeyondHorizonEnd(MfgScheduleScenario scenario, LocalDateTime endTime) {
        return !ALGORITHM_CODE_MANUAL_BOARD.equals(scenario.getAlgorithmCode())
                && scenario.getHorizonEnd() != null && endTime.isAfter(scenario.getHorizonEnd());
    }

    private String validatePlacementCapacity(MfgProductionOrder order, Long lineId, LocalDateTime startTime,
                                             LocalDateTime endTime) {
        String message = null;
        try {
            ManualScheduleCandidateLine capability = resolveManualCapability(order, lineId, startTime, endTime);
            message = checkPlacementCapacity(order, lineId, startTime, endTime, capability);
        } catch (BusinessException e) {
            message = e.getMessage();
        }
        return message;
    }

    /** 校验产线班次与产能是否足够容纳这张订单。 */
    private String checkPlacementCapacity(MfgProductionOrder order, Long lineId, LocalDateTime startTime,
                                          LocalDateTime endTime, ManualScheduleCandidateLine capability) {
        SchedulingContext context = loadSchedulingContext(startTime.toLocalDate().atStartOfDay(),
                endTime.toLocalDate().plusDays(1).atStartOfDay());
        FactoryLine line = context.activeLines().get(lineId);
        List<CapacityWindow> calendarWindows = buildLineWindows(line,
                context.calendarRulesByLine().getOrDefault(lineId, List.of()), context.shiftsById(),
                startTime.toLocalDate().atStartOfDay(), endTime.toLocalDate().plusDays(1).atStartOfDay());
        String message = validateCalendarWindow(calendarWindows, startTime, endTime);
        if (message == null) {
            message = validateCapacityAmount(order, line, context, lineId, startTime, endTime, capability);
        }
        return message;
    }

    /** 起止时间都必须落在产线有效班次内。 */
    private String validateCalendarWindow(List<CapacityWindow> calendarWindows, LocalDateTime startTime,
                                          LocalDateTime endTime) {
        boolean startInCalendar = calendarWindows.stream().anyMatch(window ->
                !startTime.isBefore(window.startTime()) && startTime.isBefore(window.endTime()));
        boolean endInCalendar = calendarWindows.stream().anyMatch(window ->
                endTime.isAfter(window.startTime()) && !endTime.isAfter(window.endTime()));
        String message = null;
        if (!startInCalendar) {
            message = "计划开始时间必须落在产线有效班次内；计划可跨越下班或停工时段";
        } else if (!endInCalendar) {
            message = "计划结束时间必须落在产线有效班次内；计划可跨越下班或停工时段";
        }
        return message;
    }

    /** 计划时段的有效产能与整批窗口是否够用。 */
    private String validateCapacityAmount(MfgProductionOrder order, FactoryLine line, SchedulingContext context,
                                          Long lineId, LocalDateTime startTime, LocalDateTime endTime,
                                          ManualScheduleCandidateLine capability) {
        BigDecimal availableCapacity = buildLineWindows(line,
                context.calendarRulesByLine().getOrDefault(lineId, List.of()), context.shiftsById(), startTime, endTime)
                .stream().map(CapacityWindow::capacity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal requiredCapacity = order.getOrderQuantity().divide(capability.getCapacityFactor(),
                CAPACITY_SCALE, RoundingMode.HALF_UP);
        String message = null;
        if (availableCapacity.compareTo(requiredCapacity) < 0) {
            message = "计划时段内的产线有效产能不足";
        } else {
            message = validateBatchWindow(order, line, context, lineId, startTime, endTime, requiredCapacity,
                    capability.getRouteId());
        }
        return message;
    }

    /** 有批次工序的路线还要求整批老化窗口能完整放进计划时段。 */
    private String validateBatchWindow(MfgProductionOrder order, FactoryLine line, SchedulingContext context,
                                       Long lineId, LocalDateTime startTime, LocalDateTime endTime,
                                       BigDecimal requiredCapacity, Long routeId) {
        RouteWorkload workload = context.routeWorkloads().get(routeId);
        String message = null;
        if (workload != null && workload.hasBatch()) {
            LineScheduleState state = new LineScheduleState(buildLineWindows(line,
                    context.calendarRulesByLine().getOrDefault(lineId, List.of()), context.shiftsById(), startTime, endTime));
            if (state.allocate(requiredCapacity, workload, order.getOrderQuantity()) == null) {
                message = "计划时段无法容纳完整装配及连续批次工序，请保留每批完整的老化窗口";
            }
        }
        return message;
    }

    /** 与草案中其他人工编排明细、以及已发布生产计划的时段重叠校验。 */
    private String validatePlacementOverlap(MfgScheduleItem currentItem, Long lineId, LocalDateTime startTime,
                                            LocalDateTime endTime, List<MfgScheduleItem> allItems) {
        String message = validateDraftOverlap(currentItem, lineId, startTime, endTime, allItems);
        if (message == null) {
            message = validatePublishedPlanOverlap(lineId, startTime, endTime);
        }
        return message;
    }

    private String validateDraftOverlap(MfgScheduleItem currentItem, Long lineId, LocalDateTime startTime,
                                        LocalDateTime endTime, List<MfgScheduleItem> allItems) {
        List<MfgScheduleItem> occupiedItems = new ArrayList<>(allItems);
        occupiedItems.addAll(listGlobalEditableManualItems());
        for (MfgScheduleItem other : occupiedItems) {
            if (!isEditableManualOccupancy(other)) {
                continue;
            }
            if (isSameScheduleItem(other, currentItem)) {
                continue;
            }
            if (Objects.equals(other.getLineId(), lineId) && overlaps(startTime, endTime,
                    other.getPlannedStartTime(), other.getPlannedEndTime())) {
                return "计划时间与当前草案中的其他订单重叠";
            }
        }
        return null;
    }

    /** 是否是同一条排程明细（自己和自己不算冲突）。 */
    private boolean isSameScheduleItem(MfgScheduleItem other, MfgScheduleItem currentItem) {
        return other.getScheduleItemId() != null && currentItem.getScheduleItemId() != null
                && Objects.equals(other.getScheduleItemId(), currentItem.getScheduleItemId());
    }

    private String validatePublishedPlanOverlap(Long lineId, LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<MfgProductionPlan> planQuery = new QueryWrapper<>();
        planQuery.eq("line_id", lineId).eq("deleted_flag", 0).in("status", OCCUPYING_PLAN_STATUSES);
        for (MfgProductionPlan plan : mfgProductionPlanMapper.selectList(planQuery)) {
            if (overlaps(startTime, endTime, plan.getPlannedStartTime(), plan.getPlannedEndTime())) {
                return "计划时间与已有生产计划重叠（含草稿与在制）";
            }
        }
        return null;
    }

    private long calculateWorkingMinutes(List<CapacityWindow> windows, LocalDateTime startTime,
                                         LocalDateTime endTime) {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return 0L;
        }
        long seconds = 0L;
        for (CapacityWindow window : windows) {
            LocalDateTime overlapStart = startTime.isAfter(window.startTime()) ? startTime : window.startTime();
            LocalDateTime overlapEnd = endTime.isBefore(window.endTime()) ? endTime : window.endTime();
            if (overlapEnd.isAfter(overlapStart)) {
                seconds += Duration.between(overlapStart, overlapEnd).toSeconds();
            }
        }
        return (seconds + 59L) / 60L;
    }

    private String validateManualItem(MfgScheduleScenario scenario, MfgScheduleItem item,
                                      List<MfgScheduleItem> allItems) {
        try {
            MfgProductionOrder order = requireDraftOrderWithoutPlan(item.getProductionOrderId());
            return validateManualPlacement(scenario, item, order, item.getLineId(), item.getPlannedStartTime(),
                    item.getPlannedEndTime(), allItems);
        } catch (BusinessException e) {
            return e.getMessage();
        }
    }

    private boolean overlaps(LocalDateTime start, LocalDateTime end, LocalDateTime otherStart, LocalDateTime otherEnd) {
        if (!isCompleteRange(start, end) || !isCompleteRange(otherStart, otherEnd)) {
            return false;
        }
        return start.isBefore(otherEnd) && end.isAfter(otherStart);
    }

    /** 起止时间都不为空（不校验先后，重叠判断沿用原有语义）。 */
    private boolean isCompleteRange(LocalDateTime start, LocalDateTime end) {
        return start != null && end != null;
    }

    private void refreshManualScenarioSummary(MfgScheduleScenario scenario, String targetStatus,
                                              Long operatorId, LocalDateTime now) {
        refreshManualScenarioSummary(scenario, targetStatus, operatorId, now,
                listActiveScenarioItems(scenario.getScenarioId()));
    }

    private void refreshManualScenarioSummary(MfgScheduleScenario scenario, String targetStatus,
                                              Long operatorId, LocalDateTime now, List<MfgScheduleItem> items) {
        SummaryTally tally = tallyItems(items);
        MfgScheduleScenario update = new MfgScheduleScenario();
        update.setScenarioId(scenario.getScenarioId());
        update.setVersion(scenario.getVersion());
        update.setStatus(targetStatus);
        update.setOrderCount(items.size());
        update.setScheduledOrderCount(tally.scheduled());
        update.setConflictOrderCount(tally.conflicts());
        update.setLateOrderCount(tally.late());
        update.setTotalLoadMinutes(tally.load().setScale(2, RoundingMode.HALF_UP));
        applyHorizonRange(update, scenario, tally.earliest(), tally.latest());
        update.setAiAdvice(null);
        update.setAiModel(null);
        update.setAiGeneratedTime(null);
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (baseMapper.updateById(update) == 0) {
            throw conflict("手工排程草案已被其他用户修改，请刷新后重试");
        }
    }

    /** 汇总明细的已排程数、冲突数、迟交数、总工时与起止时间。 */
    private SummaryTally tallyItems(List<MfgScheduleItem> items) {
        int scheduled = 0;
        int conflicts = 0;
        int late = 0;
        BigDecimal load = BigDecimal.ZERO;
        LocalDateTime earliest = null;
        LocalDateTime latest = null;
        for (MfgScheduleItem item : items) {
            if (ITEM_STATUS_CONFLICT.equals(item.getStatus())) {
                conflicts++;
                continue;
            }
            scheduled++;
            load = load.add(minutesOf(item));
            if (isLateItem(item)) {
                late++;
            }
            earliest = earlierOf(earliest, item.getPlannedStartTime());
            latest = laterOf(latest, item.getPlannedEndTime());
        }
        return new SummaryTally(scheduled, conflicts, late, load, earliest, latest);
    }

    private BigDecimal minutesOf(MfgScheduleItem item) {
        return item.getEstimatedMinutes() == null ? BigDecimal.ZERO : item.getEstimatedMinutes();
    }

    private boolean isLateItem(MfgScheduleItem item) {
        return item.getTardinessMinutes() != null && item.getTardinessMinutes() > 0;
    }

    private LocalDateTime earlierOf(LocalDateTime current, LocalDateTime candidate) {
        return candidate != null && (current == null || candidate.isBefore(current)) ? candidate : current;
    }

    private LocalDateTime laterOf(LocalDateTime current, LocalDateTime candidate) {
        return candidate != null && (current == null || candidate.isAfter(current)) ? candidate : current;
    }

    /** 手工草案是滚动工作区，保留已有范围并在排程越界时自动延展；其它算法直接跟随明细范围。 */
    private void applyHorizonRange(MfgScheduleScenario update, MfgScheduleScenario scenario,
                                   LocalDateTime earliest, LocalDateTime latest) {
        if (ALGORITHM_CODE_MANUAL_BOARD.equals(scenario.getAlgorithmCode())) {
            update.setHorizonStart(earliest == null ? scenario.getHorizonStart()
                    : minTime(scenario.getHorizonStart(), earliest));
            update.setHorizonEnd(latest == null ? scenario.getHorizonEnd()
                    : maxTime(scenario.getHorizonEnd(), latest));
        } else {
            update.setHorizonStart(earliest == null ? scenario.getHorizonStart() : earliest);
            update.setHorizonEnd(latest == null ? scenario.getHorizonEnd() : latest);
        }
    }

    private record SummaryTally(int scheduled, int conflicts, int late, BigDecimal load,
                                LocalDateTime earliest, LocalDateTime latest) {
    }

    private LocalDateTime maxTime(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        return left.isAfter(right) ? left : right;
    }

    private LocalDateTime minTime(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        return left.isBefore(right) ? left : right;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private MfgScheduleItem scheduleOrder(MfgScheduleScenario scenario, MfgProductionOrder order, int sequenceNo,
                                          int totalOrderCount, SchedulingContext context,
                                          Map<Long, LineScheduleState> lineStates) {
        MfgScheduleItem item = new MfgScheduleItem();
        item.setScenarioId(scenario.getScenarioId());
        item.setProductionOrderId(order.getProductionOrderId());
        item.setProductId(order.getProductId());
        item.setSequenceNo(sequenceNo);
        item.setPlannedQuantity(order.getOrderQuantity());
        item.setPriorityScore(BigDecimal.valueOf(totalOrderCount - sequenceNo + 1L));
        item.setDecisionSource(DECISION_SOURCE_RULE_ENGINE);
        item.setTardinessMinutes(0L);
        item.setDeletedFlag(0);

        String inputProblem = validateOrderInput(order);
        if (inputProblem != null) {
            return conflict(item, inputProblem);
        }
        return allocateScheduleItem(scenario, order, context, lineStates, item);
    }

    /** 为订单分配候选产线并写回明细；无能力配置或没有可用候选时转成冲突明细。 */
    private MfgScheduleItem allocateScheduleItem(MfgScheduleScenario scenario, MfgProductionOrder order,
                                                 SchedulingContext context, Map<Long, LineScheduleState> lineStates,
                                                 MfgScheduleItem item) {
        MfgScheduleItem scheduled;
        List<MfgLineProductCapability> capabilities = context.capabilitiesByProduct()
                .getOrDefault(order.getProductId(), List.of());
        if (capabilities.isEmpty()) {
            scheduled = conflict(item, "未配置启用的产线产品能力");
        } else {
            CandidateProbe probe = probeCandidates(scenario, order, capabilities, context, lineStates);
            if (probe.winner() == null) {
                scheduled = conflict(item, probe.failureReason());
            } else {
                applyWinner(item, order, probe.winner(), lineStates);
                scheduled = item;
            }
        }
        return scheduled;
    }

    /** 逐条产线能力试排，记录各前置条件是否具备，并选出最优候选。 */
    private CandidateProbe probeCandidates(MfgScheduleScenario scenario, MfgProductionOrder order,
                                           List<MfgLineProductCapability> capabilities, SchedulingContext context,
                                           Map<Long, LineScheduleState> lineStates) {
        boolean hasRunningLine = false;
        boolean hasReleasedRoute = false;
        boolean hasRouteWorkTime = false;
        boolean hasCalendar = false;
        CandidateAllocation winner = null;

        for (MfgLineProductCapability capability : capabilities) {
            FactoryLine line = context.activeLines().get(capability.getLineId());
            if (line == null || !STATUS_RUNNING.equals(line.getStatus())) {
                continue;
            }
            hasRunningLine = true;

            ProcessRoute route = context.releasedRoutes().get(capability.getRouteId());
            if (route == null || !Objects.equals(route.getProductId(), order.getProductId())) {
                continue;
            }
            hasReleasedRoute = true;

            RouteWorkload workload = context.routeWorkloads().get(route.getRouteId());
            if (workload == null) {
                continue;
            }
            hasRouteWorkTime = true;

            LineScheduleState state = lineStates.computeIfAbsent(line.getLineId(), lineId ->
                    new LineScheduleState(buildLineWindows(line, context.calendarRulesByLine().getOrDefault(lineId, List.of()),
                            context.shiftsById(), scenario.getHorizonStart(), scenario.getHorizonEnd())));
            if (state.isEmpty()) {
                continue;
            }
            hasCalendar = true;

            CandidateAllocation candidate = tryAllocate(order, capability, route, workload, state);
            if (candidate != null && isPreferred(candidate, winner)) {
                winner = candidate;
            }
        }
        String failureReason = winner == null
                ? noCandidateReason(hasRunningLine, hasReleasedRoute, hasRouteWorkTime, hasCalendar) : null;
        return new CandidateProbe(winner, failureReason);
    }

    /** 在该产线状态上模拟一次分配，产能不足或系数无效时返回 null。 */
    private CandidateAllocation tryAllocate(MfgProductionOrder order, MfgLineProductCapability capability,
                                            ProcessRoute route, RouteWorkload workload, LineScheduleState state) {
        if (capability.getCapacityFactor() == null || capability.getCapacityFactor().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal requiredBaseCapacity = order.getOrderQuantity()
                .divide(capability.getCapacityFactor(), CAPACITY_SCALE, RoundingMode.HALF_UP);
        LineScheduleState simulation = state.copy();
        Allocation allocation = simulation.allocate(requiredBaseCapacity, workload, order.getOrderQuantity());
        if (allocation == null) {
            return null;
        }
        BigDecimal estimatedMinutes = workload.estimate(order.getOrderQuantity());
        return new CandidateAllocation(capability, route, simulation, allocation, estimatedMinutes);
    }

    /** 没有可用候选时，按最先缺失的前置条件给出冲突原因。 */
    private String noCandidateReason(boolean hasRunningLine, boolean hasReleasedRoute, boolean hasRouteWorkTime,
                                     boolean hasCalendar) {
        String reason;
        if (!hasRunningLine) {
            reason = "候选产线不存在、已删除或未处于 RUNNING 状态";
        } else if (!hasReleasedRoute) {
            reason = "候选产线未配置已发布且匹配产品的工艺路线";
        } else if (!hasRouteWorkTime) {
            reason = "工艺路线缺少有效标准工时";
        } else if (!hasCalendar) {
            reason = "候选产线在排程窗口内没有有效周历";
        } else {
            reason = "排程窗口有效产能不足";
        }
        return reason;
    }

    private void applyWinner(MfgScheduleItem item, MfgProductionOrder order, CandidateAllocation winner,
                             Map<Long, LineScheduleState> lineStates) {
        lineStates.put(winner.capability().getLineId(), winner.stateAfterAllocation());
        item.setRouteId(winner.route().getRouteId());
        item.setLineId(winner.capability().getLineId());
        item.setPlannedStartTime(winner.allocation().startTime());
        item.setPlannedEndTime(winner.allocation().endTime());
        item.setEstimatedMinutes(winner.estimatedMinutes());
        item.setTardinessMinutes(calculateTardiness(order.getDeliveryDate(), winner.allocation().endTime()));
        item.setStatus(ITEM_STATUS_SCHEDULED);
        item.setExplanation("按交期顺序计算；选择最早完成的候选产线，适配优先级="
                + winner.capability().getPriorityLevel() + "，产品能力系数=" + winner.capability().getCapacityFactor());
    }

    private record CandidateProbe(CandidateAllocation winner, String failureReason) {
    }

    /** 候选优劣：先比结束时间，再比优先级、产线、能力主键，全部升序取最小。 */
    private boolean isPreferred(CandidateAllocation candidate, CandidateAllocation currentWinner) {
        boolean preferred;
        if (currentWinner == null) {
            preferred = true;
        } else {
            preferred = isBetterCandidate(candidate, currentWinner);
        }
        return preferred;
    }

    private boolean isBetterCandidate(CandidateAllocation candidate, CandidateAllocation currentWinner) {
        int comparison = candidate.allocation().endTime().compareTo(currentWinner.allocation().endTime());
        if (comparison == 0) {
            comparison = Integer.compare(candidate.capability().getPriorityLevel(),
                    currentWinner.capability().getPriorityLevel());
        }
        if (comparison == 0) {
            comparison = Long.compare(candidate.capability().getLineId(), currentWinner.capability().getLineId());
        }
        if (comparison == 0) {
            comparison = Long.compare(candidate.capability().getCapabilityId(),
                    currentWinner.capability().getCapabilityId());
        }
        return comparison < 0;
    }

    private MfgScheduleItem conflict(MfgScheduleItem item, String reason) {
        item.setStatus(ITEM_STATUS_CONFLICT);
        item.setConflictReason(reason);
        item.setExplanation("未生成部分排程：" + reason);
        item.setLineId(null);
        item.setRouteId(null);
        item.setPlannedStartTime(null);
        item.setPlannedEndTime(null);
        item.setEstimatedMinutes(null);
        item.setTardinessMinutes(0L);
        return item;
    }

    private String validateOrderInput(MfgProductionOrder order) {
        String problem;
        if (order.getProductionOrderId() == null) {
            problem = "生产订单主键不能为空";
        } else if (order.getProductId() == null) {
            problem = "生产订单缺少产品";
        } else if (!isPositive(order.getOrderQuantity())) {
            problem = "生产订单数量必须大于 0";
        } else if (order.getDeliveryDate() == null) {
            problem = "生产订单缺少交付日期";
        } else {
            problem = null;
        }
        return problem;
    }

    private void validateSchedulableOrders(List<MfgProductionOrder> orders) {
        for (MfgProductionOrder order : orders) {
            String problem = validateOrderInput(order);
            if (problem != null) {
                throw invalid("生产订单 " + (order.getProductionOrderNo() == null ? "<未编号>" : order.getProductionOrderNo())
                        + " 数据不完整：" + problem);
            }
        }
    }

    private long calculateTardiness(LocalDate deliveryDate, LocalDateTime plannedEndTime) {
        LocalDateTime deadline = deliveryDate.atTime(LocalTime.MAX);
        if (!plannedEndTime.isAfter(deadline)) {
            return 0L;
        }
        return Duration.between(deadline, plannedEndTime).toMinutes();
    }

    private MfgScheduleScenario createDraftScenario(MfgScheduleScenario request, String scenarioNo) {
        MfgScheduleScenario scenario = new MfgScheduleScenario();
        scenario.setScenarioNo(scenarioNo);
        scenario.setScenarioName(request.getScenarioName().trim());
        scenario.setHorizonStart(request.getHorizonStart());
        scenario.setHorizonEnd(request.getHorizonEnd());
        scenario.setAlgorithmCode(ALGORITHM_CODE);
        scenario.setStatus(STATUS_DRAFT);
        scenario.setOrderCount(0);
        scenario.setScheduledOrderCount(0);
        scenario.setConflictOrderCount(0);
        scenario.setLateOrderCount(0);
        scenario.setTotalLoadMinutes(BigDecimal.ZERO);
        scenario.setRemark(request.getRemark());
        scenario.setDeletedFlag(0);
        return scenario;
    }

    private void validateCalculationRequest(MfgScheduleScenario request) {
        if (!hasScenarioIdentity(request)) {
            throw invalid("方案编号和方案名称不能为空");
        }
        if (!isOrderedRange(request.getHorizonStart(), request.getHorizonEnd())) {
            throw invalid("排程窗口结束时间必须晚于开始时间");
        }
        if (request.getHorizonStart().isBefore(LocalDateTime.now(SCHEDULE_ZONE).minusMinutes(2))) {
            throw invalid("排程窗口开始时间已过，请选择未来有效时间");
        }
    }

    /** 方案编号与名称都必须非空。 */
    private boolean hasScenarioIdentity(MfgScheduleScenario request) {
        return request != null && StringUtils.hasText(request.getScenarioNo())
                && StringUtils.hasText(request.getScenarioName());
    }

    private void ensureScenarioNoAvailable(String scenarioNo) {
        QueryWrapper<MfgScheduleScenario> wrapper = new QueryWrapper<>();
        wrapper.eq("scenario_no", scenarioNo);
        wrapper.eq("deleted_flag", 0);
        if (baseMapper.selectCount(wrapper) > 0) {
            throw invalid("排程方案编号已存在");
        }
    }

    private void releaseOrderForConfirmedPlan(MfgProductionOrder order, Long operatorId, String remark,
                                              LocalDateTime now, String changeReason) {
        MfgProductionOrder update = new MfgProductionOrder();
        update.setProductionOrderId(order.getProductionOrderId());
        update.setStatus(STATUS_RELEASED);
        update.setVersion(order.getVersion());
        update.setUpdatedBy(operatorId);
        update.setUpdatedTime(now);
        if (mfgProductionOrderMapper.updateById(update) == 0) {
            throw conflict("订单状态已更新，请刷新排程总览后重新操作");
        }
        insertStatusChangeLog(order.getProductionOrderId(), order.getStatus(), STATUS_RELEASED,
                "MFG_PRODUCTION_ORDER", changeReason, operatorId, remark, now);
        insertOperationLog(order.getProductionOrderId(), changeReason, "mfg_production_order",
                operatorId, remark, now);
    }

    private List<MfgProductionOrder> listDraftOrdersWithoutPlan() {
        QueryWrapper<MfgProductionOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        wrapper.eq("status", STATUS_DRAFT);
        wrapper.notInSql("production_order_id",
                "SELECT production_order_id FROM mfg_production_plan "
                        + "WHERE deleted_flag = 0 AND status <> 'CLOSED' AND production_order_id IS NOT NULL");
        wrapper.orderByAsc("delivery_date");
        wrapper.orderByAsc("production_order_no");
        wrapper.orderByAsc("production_order_id");
        return mfgProductionOrderMapper.selectList(wrapper);
    }

    private SchedulingContext loadSchedulingContext(LocalDateTime horizonStart, LocalDateTime horizonEnd) {
        List<MfgLineProductCapability> capabilities = mfgLineProductCapabilityMapper.selectList(activeCapabilityQuery());
        Map<Long, List<MfgLineProductCapability>> capabilitiesByProduct = capabilities.stream()
                .collect(Collectors.groupingBy(MfgLineProductCapability::getProductId));

        Map<Long, FactoryLine> activeLines = factoryLineMapper.selectList(activeLineQuery()).stream()
                .collect(Collectors.toMap(FactoryLine::getLineId, line -> line));
        Map<Long, ProcessRoute> releasedRoutes = processRouteMapper.selectList(releasedRouteQuery()).stream()
                .collect(Collectors.toMap(ProcessRoute::getRouteId, route -> route));
        Map<Long, RouteWorkload> routeWorkloads = buildRouteWorkloads();
        Map<Long, List<MfgLineCalendarRule>> calendarRulesByLine = mfgLineCalendarRuleMapper
                .selectList(activeCalendarRuleQuery()).stream()
                .collect(Collectors.groupingBy(MfgLineCalendarRule::getLineId));
        Map<Long, OrgShift> shiftsById = orgShiftMapper.selectList(activeShiftQuery()).stream()
                .collect(Collectors.toMap(OrgShift::getShiftId, shift -> shift));

        return new SchedulingContext(capabilitiesByProduct, activeLines, releasedRoutes, routeWorkloads,
                calendarRulesByLine, shiftsById, horizonStart, horizonEnd);
    }

    private Map<Long, RouteWorkload> buildRouteWorkloads() {
        Map<Long, List<ProcessRouteItem>> itemsByRoute = processRouteItemMapper.selectList(activeRouteItemQuery()).stream()
                .filter(item -> item.getRouteId() != null)
                .collect(Collectors.groupingBy(ProcessRouteItem::getRouteId));
        Map<Long, RouteWorkload> result = new HashMap<>();
        for (Map.Entry<Long, List<ProcessRouteItem>> entry : itemsByRoute.entrySet()) {
            List<ProcessRouteItem> items = entry.getValue();
            if (!hasUsableWorkload(items)) {
                continue;
            }
            List<ProcessRouteItem> ordered = items.stream().sorted(Comparator.comparing(
                    ProcessRouteItem::getSequenceNo, Comparator.nullsLast(Integer::compareTo))).toList();
            result.put(entry.getKey(), new RouteWorkload(ordered));
        }
        return result;
    }

    /** 路线是否可用于排程：每道工序都给出合法的产能口径，任一道缺失就整条路线不参与。 */
    private static boolean hasUsableWorkload(List<ProcessRouteItem> items) {
        if (items.isEmpty()) {
            return false;
        }
        for (ProcessRouteItem item : items) {
            if (!hasUsableWorkload(item)) {
                return false;
            }
        }
        return true;
    }

    /** 单道工序的产能口径：批量设备看批量产能与批量时长，其余看标准工时。 */
    private static boolean hasUsableWorkload(ProcessRouteItem item) {
        if ("BATCH_EQUIPMENT".equals(item.getResourceType())) {
            return positive(item.getBatchCapacity()) && positive(item.getBatchDurationMinutes())
                    && item.getBatchCapacity().stripTrailingZeros().scale() <= 0;
        }
        return positive(item.getStandardTime());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private List<CapacityWindow> buildLineWindows(FactoryLine line, List<MfgLineCalendarRule> rules,
                                                   Map<Long, OrgShift> shiftsById, LocalDateTime horizonStart,
                                                   LocalDateTime horizonEnd) {
        if (line.getDailyCapacity() == null || line.getDailyCapacity().compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        List<CapacityWindow> windows = new ArrayList<>();
        LocalDate date = horizonStart.toLocalDate();
        LocalDate endDate = horizonEnd.toLocalDate();
        while (!date.isAfter(endDate)) {
            appendDayWindows(line, latestRulesForDate(rules, date), shiftsById, date, horizonStart, horizonEnd, windows);
            date = date.plusDays(1);
        }
        windows.sort(Comparator.comparing(CapacityWindow::startTime));
        return windows;
    }

    /** 单日产线窗口：按班次折算产能，裁掉窗口外的时段，班次跨天时结束时间顺延一天。 */
    private void appendDayWindows(FactoryLine line, Map<Long, MfgLineCalendarRule> rulesByShift,
                                  Map<Long, OrgShift> shiftsById, LocalDate date, LocalDateTime horizonStart,
                                  LocalDateTime horizonEnd, List<CapacityWindow> windows) {
        List<ShiftSegment> daySegments = new ArrayList<>();
        long fullDaySeconds = 0L;
        for (MfgLineCalendarRule rule : rulesByShift.values()) {
            ShiftSpan span = shiftSpan(date, rule, shiftsById, horizonStart, horizonEnd);
            if (span == null) {
                continue;
            }
            fullDaySeconds += span.fullSeconds();
            if (span.segment() != null) {
                daySegments.add(span.segment());
            }
        }
        appendSegmentWindows(line, daySegments, fullDaySeconds, windows);
    }

    /** 一个班次当天的完整时长与落在排程窗口内的区段；班次无效时返回 null。 */
    private record ShiftSpan(long fullSeconds, ShiftSegment segment) {
    }

    /** 单个班次在当天的完整时长和有效区段；班次无效时返回 null，无交集时区段为 null。 */
    private ShiftSpan shiftSpan(LocalDate date, MfgLineCalendarRule rule, Map<Long, OrgShift> shiftsById,
                                LocalDateTime horizonStart, LocalDateTime horizonEnd) {
        OrgShift shift = shiftsById.get(rule.getShiftId());
        if (!isUsableShift(shift, rule)) {
            return null;
        }
        LocalDateTime fullStart = inScheduleZone(date, shift.getStartTime());
        LocalDateTime fullEnd = inScheduleZone(date, shift.getEndTime());
        if (shift.getEndTime().isBefore(shift.getStartTime())) {
            fullEnd = fullEnd.plusDays(1);
        }
        if (!fullEnd.isAfter(fullStart)) {
            return null;
        }
        long fullShiftSeconds = Duration.between(fullStart, fullEnd).toSeconds();
        ShiftSegment segment = clipToHorizon(fullStart, fullEnd, rule.getCapacityFactor(), horizonStart, horizonEnd);
        return new ShiftSpan(fullShiftSeconds, segment);
    }

    private ShiftSegment clipToHorizon(LocalDateTime fullStart, LocalDateTime fullEnd, BigDecimal capacityFactor,
                                       LocalDateTime horizonStart, LocalDateTime horizonEnd) {
        long fullShiftSeconds = Duration.between(fullStart, fullEnd).toSeconds();
        LocalDateTime start = fullStart.isBefore(horizonStart) ? horizonStart : fullStart;
        LocalDateTime end = fullEnd.isAfter(horizonEnd) ? horizonEnd : fullEnd;
        return end.isAfter(start) ? new ShiftSegment(start, end, fullShiftSeconds, capacityFactor) : null;
    }

    /** 班次主数据完整且产能系数为正才参与折算。 */
    private boolean isUsableShift(OrgShift shift, MfgLineCalendarRule rule) {
        return hasShiftTime(shift) && isPositive(rule.getCapacityFactor());
    }

    /** 班次存在且起止时间完整。 */
    private boolean hasShiftTime(OrgShift shift) {
        return shift != null && shift.getStartTime() != null && shift.getEndTime() != null;
    }

    /** 按当日总班次时长把日产能分摊到各有效区段。 */
    private void appendSegmentWindows(FactoryLine line, List<ShiftSegment> daySegments, long fullDaySeconds,
                                      List<CapacityWindow> windows) {
        if (fullDaySeconds <= 0) {
            return;
        }
        for (ShiftSegment segment : daySegments) {
            long clippedSeconds = Duration.between(segment.startTime(), segment.endTime()).toSeconds();
            BigDecimal baseCapacity = line.getDailyCapacity()
                    .multiply(segment.capacityFactor())
                    .multiply(BigDecimal.valueOf(clippedSeconds))
                    .divide(BigDecimal.valueOf(fullDaySeconds), CAPACITY_SCALE, RoundingMode.HALF_UP);
            if (baseCapacity.compareTo(BigDecimal.ZERO) > 0) {
                windows.add(new CapacityWindow(segment.startTime(), segment.endTime(), baseCapacity, BigDecimal.ZERO));
            }
        }
    }

    private Map<Long, MfgLineCalendarRule> latestRulesForDate(List<MfgLineCalendarRule> rules, LocalDate date) {
        Map<Long, MfgLineCalendarRule> result = new HashMap<>();
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        for (MfgLineCalendarRule rule : rules) {
            if (!appliesToDate(rule, dayOfWeek, date)) {
                continue;
            }
            MfgLineCalendarRule existing = result.get(rule.getShiftId());
            if (existing == null || rule.getEffectiveFrom().isAfter(existing.getEffectiveFrom())) {
                result.put(rule.getShiftId(), rule);
            }
        }
        return result;
    }

    /** 周历规则是否命中指定日期：星期匹配且生效区间覆盖当天。 */
    private boolean appliesToDate(MfgLineCalendarRule rule, DayOfWeek dayOfWeek, LocalDate date) {
        if (!Objects.equals(rule.getDayOfWeek(), dayOfWeek.getValue()) || rule.getEffectiveFrom() == null) {
            return false;
        }
        if (rule.getEffectiveFrom().isAfter(date)) {
            return false;
        }
        return rule.getEffectiveTo() == null || !rule.getEffectiveTo().isBefore(date);
    }

    private LocalDateTime inScheduleZone(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, SCHEDULE_ZONE).toLocalDateTime();
    }

    private QueryWrapper<MfgScheduleScenario> buildScenarioQuery(String keyword, String status) {
        QueryWrapper<MfgScheduleScenario> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(item -> item.like("scenario_no", keyword).or().like("scenario_name", keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        wrapper.orderByDesc("scenario_id");
        return wrapper;
    }

    private QueryWrapper<MfgLineProductCapability> activeCapabilityQuery() {
        QueryWrapper<MfgLineProductCapability> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        wrapper.eq("status", STATUS_ENABLE);
        wrapper.orderByAsc("priority_level");
        wrapper.orderByAsc("capability_id");
        return wrapper;
    }

    private QueryWrapper<FactoryLine> activeLineQuery() {
        QueryWrapper<FactoryLine> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        return wrapper;
    }

    private QueryWrapper<ProcessRoute> releasedRouteQuery() {
        QueryWrapper<ProcessRoute> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        wrapper.eq("status", STATUS_RELEASED);
        return wrapper;
    }

    private QueryWrapper<ProcessRouteItem> activeRouteItemQuery() {
        QueryWrapper<ProcessRouteItem> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        wrapper.orderByAsc("sequence_no");
        wrapper.orderByAsc("route_item_id");
        return wrapper;
    }

    private QueryWrapper<MfgLineCalendarRule> activeCalendarRuleQuery() {
        QueryWrapper<MfgLineCalendarRule> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        wrapper.eq("status", STATUS_ENABLE);
        return wrapper;
    }

    private QueryWrapper<OrgShift> activeShiftQuery() {
        QueryWrapper<OrgShift> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        return wrapper;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.PARAM_ERROR.getCode(), message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ResultCode.DATA_CONFLICT.getCode(), message);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ResultCode.NOT_FOUND.getCode(), message);
    }

    private record ManualCandidateGroupKey(Long scenarioId, Integer scenarioVersion, Long productionOrderId) {
    }

    private record ManualCandidateListCacheEntry(List<ManualScheduleCandidateLine> candidates, long expiresAtNanos) {
    }

    private record SchedulingContext(Map<Long, List<MfgLineProductCapability>> capabilitiesByProduct,
                                     Map<Long, FactoryLine> activeLines,
                                     Map<Long, ProcessRoute> releasedRoutes,
                                     Map<Long, RouteWorkload> routeWorkloads,
                                     Map<Long, List<MfgLineCalendarRule>> calendarRulesByLine,
                                     Map<Long, OrgShift> shiftsById,
                                     LocalDateTime horizonStart,
                                     LocalDateTime horizonEnd) {
    }

    private record CandidateAllocation(MfgLineProductCapability capability, ProcessRoute route,
                                       LineScheduleState stateAfterAllocation, Allocation allocation,
                                       BigDecimal estimatedMinutes) {
    }

    private record ConfirmationEntry(MfgScheduleItem item, MfgProductionOrder order) {
    }

    private static final class BoardLineAiLoad {
        private final Long lineId;
        private int manualItemCount;
        private int productionPlanCount;
        private int lateCount;
        private BigDecimal totalPlannedQuantity = BigDecimal.ZERO;
        private long totalPlannedMinutes;
        private LocalDateTime earliestStartTime;
        private LocalDateTime latestEndTime;

        private BoardLineAiLoad(Long lineId) {
            this.lineId = lineId;
        }

        private void add(ManualScheduleBoardItem item) {
            if ("PLAN".equals(item.getSourceType())) {
                productionPlanCount++;
            } else {
                manualItemCount++;
            }
            if (item.getPlannedQuantity() != null) {
                totalPlannedQuantity = totalPlannedQuantity.add(item.getPlannedQuantity());
            }
            if (item.getPlannedStartTime() != null && item.getPlannedEndTime() != null
                    && item.getPlannedEndTime().isAfter(item.getPlannedStartTime())) {
                totalPlannedMinutes += Duration.between(item.getPlannedStartTime(), item.getPlannedEndTime()).toMinutes();
            }
            if (item.getTardinessMinutes() != null && item.getTardinessMinutes() > 0) {
                lateCount++;
            }
            if (item.getPlannedStartTime() != null
                    && (earliestStartTime == null || item.getPlannedStartTime().isBefore(earliestStartTime))) {
                earliestStartTime = item.getPlannedStartTime();
            }
            if (item.getPlannedEndTime() != null
                    && (latestEndTime == null || item.getPlannedEndTime().isAfter(latestEndTime))) {
                latestEndTime = item.getPlannedEndTime();
            }
        }

        private Map<String, Object> toSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("lineId", lineId);
            summary.put("manualItemCount", manualItemCount);
            summary.put("productionPlanCount", productionPlanCount);
            summary.put("lateCount", lateCount);
            summary.put("totalPlannedQuantity", totalPlannedQuantity);
            summary.put("totalPlannedMinutes", totalPlannedMinutes);
            summary.put("earliestStartTime", earliestStartTime);
            summary.put("latestEndTime", latestEndTime);
            return summary;
        }
    }

    private static final class LineAiLoad {
        private final Long lineId;
        private int scheduledOrderCount;
        private int lateOrderCount;
        private BigDecimal totalPlannedQuantity = BigDecimal.ZERO;
        private BigDecimal totalEstimatedMinutes = BigDecimal.ZERO;
        private LocalDateTime earliestStartTime;
        private LocalDateTime latestEndTime;

        private LineAiLoad(Long lineId) {
            this.lineId = lineId;
        }

        private void add(MfgScheduleItem item) {
            scheduledOrderCount++;
            if (item.getPlannedQuantity() != null) {
                totalPlannedQuantity = totalPlannedQuantity.add(item.getPlannedQuantity());
            }
            if (item.getEstimatedMinutes() != null) {
                totalEstimatedMinutes = totalEstimatedMinutes.add(item.getEstimatedMinutes());
            }
            if (item.getTardinessMinutes() != null && item.getTardinessMinutes() > 0) {
                lateOrderCount++;
            }
            if (item.getPlannedStartTime() != null
                    && (earliestStartTime == null || item.getPlannedStartTime().isBefore(earliestStartTime))) {
                earliestStartTime = item.getPlannedStartTime();
            }
            if (item.getPlannedEndTime() != null
                    && (latestEndTime == null || item.getPlannedEndTime().isAfter(latestEndTime))) {
                latestEndTime = item.getPlannedEndTime();
            }
        }

        private Map<String, Object> toSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("lineId", lineId);
            summary.put("scheduledOrderCount", scheduledOrderCount);
            summary.put("lateOrderCount", lateOrderCount);
            summary.put("totalPlannedQuantity", totalPlannedQuantity);
            summary.put("totalEstimatedMinutes", totalEstimatedMinutes);
            summary.put("earliestStartTime", earliestStartTime);
            summary.put("latestEndTime", latestEndTime);
            return summary;
        }
    }

    private record Allocation(LocalDateTime startTime, LocalDateTime endTime) {
    }

    /** 单件工时与批次设备时长各按自己的单位计算，不能给老化虚填单件工时。 */
    private record RouteWorkload(List<ProcessRouteItem> steps) {
        boolean hasBatch() {
            return steps.stream().anyMatch(step -> "BATCH_EQUIPMENT".equals(step.getResourceType()));
        }

        BigDecimal estimate(BigDecimal quantity) {
            BigDecimal minutes = BigDecimal.ZERO;
            for (ProcessRouteItem step : steps) {
                minutes = minutes.add("BATCH_EQUIPMENT".equals(step.getResourceType())
                        ? quantity.divide(step.getBatchCapacity(), 0, RoundingMode.CEILING)
                            .multiply(step.getBatchDurationMinutes())
                        : quantity.multiply(step.getStandardTime()));
            }
            return minutes.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private record ShiftSegment(LocalDateTime startTime, LocalDateTime endTime, long fullSeconds,
                                BigDecimal capacityFactor) {
    }

    private static final class LineScheduleState {
        private final List<CapacityWindow> windows;

        private LineScheduleState(List<CapacityWindow> windows) {
            this.windows = windows;
        }

        private boolean isEmpty() {
            return windows.isEmpty();
        }

        private LineScheduleState copy() {
            return new LineScheduleState(windows.stream().map(CapacityWindow::copy).toList());
        }

        private Allocation allocate(BigDecimal capacity, RouteWorkload workload, BigDecimal quantity) {
            if (!workload.hasBatch()) {
                return allocate(capacity);
            }
            if (!isWholePositive(quantity)) {
                return null;
            }
            return allocateBatch(capacity, workload, quantity);
        }

        private Allocation allocateBatch(BigDecimal capacity, RouteWorkload workload, BigDecimal quantity) {
            // 日产能与实际班次时间仍须同时满足，原有资源占用由各分配器维护。
            Allocation capacityAllocation = copy().allocate(capacity);
            if (capacityAllocation == null) {
                return null;
            }
            Allocation timeAllocation = allocateByShiftTime(workload, quantity);
            if (timeAllocation == null) {
                return null;
            }
            return mergeAllocation(capacityAllocation, timeAllocation);
        }

        /** 按班次时间逐道工序占用窗口；任何一道排不下就整体失败。 */
        private Allocation allocateByShiftTime(RouteWorkload workload, BigDecimal quantity) {
            LineScheduleState timeState = copy();
            LocalDateTime start = null;
            LocalDateTime cursor = null;
            for (ProcessRouteItem step : workload.steps()) {
                StepFill fill = fillStep(timeState, step, quantity, start, cursor);
                if (fill.remaining().signum() > 0) {
                    return null;
                }
                start = fill.start();
                cursor = fill.cursor();
            }
            return start == null || cursor == null ? null : new Allocation(start, cursor);
        }

        /** 把一道工序的数量铺到当前状态的窗口里；铺不完时剩余量大于零。 */
        private static StepFill fillStep(LineScheduleState state, ProcessRouteItem step, BigDecimal quantity,
                                         LocalDateTime start, LocalDateTime cursor) {
            boolean batch = "BATCH_EQUIPMENT".equals(step.getResourceType());
            BigDecimal remaining = batch
                    ? quantity.divide(step.getBatchCapacity(), 0, RoundingMode.CEILING) : quantity;
            long blockSeconds = blockSecondsOf(step, batch);
            LocalDateTime runningStart = start;
            LocalDateTime runningCursor = cursor;
            for (CapacityWindow window : state.windows) {
                if (remaining.signum() == 0) {
                    break;
                }
                WindowTake taken = takeFromWindow(window, remaining, blockSeconds, runningCursor);
                if (taken.quantity().signum() <= 0) {
                    continue;
                }
                if (runningStart == null) {
                    runningStart = taken.freeStart();
                }
                runningCursor = taken.cursor();
                remaining = remaining.subtract(taken.quantity());
            }
            return new StepFill(remaining, runningStart, runningCursor);
        }

        /**
         * 在一个窗口里尽量多排；排不下时数量为零，游标保持原值。
         *
         * <p>游标是上一道工序的结束点，窗口起点早于它就从游标开始，避免前后工序交叠。</p>
         */
        private static WindowTake takeFromWindow(CapacityWindow window, BigDecimal remaining, long blockSeconds,
                                                 LocalDateTime cursor) {
            LocalDateTime freeStart = window.timeAt(window.usedCapacity());
            if (cursor != null && freeStart.isBefore(cursor)) {
                freeStart = cursor;
            }
            long freeSeconds = Duration.between(freeStart, window.endTime()).toSeconds();
            if (freeSeconds < blockSeconds) {
                return new WindowTake(BigDecimal.ZERO, freeStart, cursor);
            }
            BigDecimal take = remaining.min(BigDecimal.valueOf(freeSeconds / blockSeconds));
            if (take.signum() <= 0) {
                return new WindowTake(BigDecimal.ZERO, freeStart, cursor);
            }
            LocalDateTime cursorAfter = freeStart.plusSeconds(take.longValueExact() * blockSeconds);
            window.occupyUntil(cursorAfter);
            return new WindowTake(take, freeStart, cursorAfter);
        }

        /** 单道工序排完后的剩余量、首次开始时间与累计游标。 */
        private record StepFill(BigDecimal remaining, LocalDateTime start, LocalDateTime cursor) {
        }

        /** 单个窗口里实际占走的数量、可用起点与占用后的游标。 */
        private record WindowTake(BigDecimal quantity, LocalDateTime freeStart, LocalDateTime cursor) {
        }

        /** 单次占用的秒数：批量设备用批量时长，连续工序用标准工时。 */
        private static long blockSecondsOf(ProcessRouteItem step, boolean batch) {
            BigDecimal minutes = batch ? step.getBatchDurationMinutes() : step.getStandardTime();
            return minutes.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.CEILING).longValueExact();
        }

        /**
         * 合并产能与时间两个视角的结论，并把整条产线覆盖到结束。
         *
         * <p>起始取更早的那个，结束取更晚的那个；随后把覆盖期间的窗口一并占用，
         * 避免后单插进本单的老化等待区间。</p>
         */
        private Allocation mergeAllocation(Allocation capacityAllocation, Allocation timeAllocation) {
            LocalDateTime start = timeAllocation.startTime().isBefore(capacityAllocation.startTime())
                    ? timeAllocation.startTime() : capacityAllocation.startTime();
            LocalDateTime end = timeAllocation.endTime().isAfter(capacityAllocation.endTime())
                    ? timeAllocation.endTime() : capacityAllocation.endTime();
            for (CapacityWindow window : windows) {
                window.occupyUntil(end);
            }
            return new Allocation(start, end);
        }

        /** 必须是正整数件数：批量设备按整批排产，半件无法排。 */
        private static boolean isWholePositive(BigDecimal quantity) {
            return quantity != null && quantity.signum() > 0 && quantity.stripTrailingZeros().scale() <= 0;
        }

        private Allocation allocate(BigDecimal requiredBaseCapacity) {
            BigDecimal remaining = requiredBaseCapacity;
            LocalDateTime start = null;
            LocalDateTime end = null;
            for (CapacityWindow window : windows) {
                BigDecimal available = window.capacity().subtract(window.usedCapacity());
                if (available.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal consumed = remaining.min(available);
                if (start == null) {
                    start = window.timeAt(window.usedCapacity());
                }
                window.consume(consumed);
                end = window.timeAt(window.usedCapacity());
                remaining = remaining.subtract(consumed);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    return new Allocation(start, end);
                }
            }
            return null;
        }
    }

    private static final class CapacityWindow {
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private final BigDecimal capacity;
        private BigDecimal usedCapacity;

        private CapacityWindow(LocalDateTime startTime, LocalDateTime endTime, BigDecimal capacity, BigDecimal usedCapacity) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.capacity = capacity;
            this.usedCapacity = usedCapacity;
        }

        private LocalDateTime startTime() {
            return startTime;
        }

        private LocalDateTime endTime() {
            return endTime;
        }

        private BigDecimal capacity() {
            return capacity;
        }

        private BigDecimal usedCapacity() {
            return usedCapacity;
        }

        private void consume(BigDecimal amount) {
            usedCapacity = usedCapacity.add(amount);
        }

        private void occupyUntil(LocalDateTime end) {
            if (!end.isAfter(startTime)) return;
            long seconds = Duration.between(startTime, end.isBefore(endTime) ? end : endTime).toSeconds();
            long total = Duration.between(startTime, endTime).toSeconds();
            if (total <= 0) return;
            BigDecimal occupied = capacity.multiply(BigDecimal.valueOf(seconds))
                    .divide(BigDecimal.valueOf(total), CAPACITY_SCALE, RoundingMode.CEILING);
            usedCapacity = usedCapacity.max(occupied.min(capacity));
        }

        private LocalDateTime timeAt(BigDecimal used) {
            long durationSeconds = Duration.between(startTime, endTime).toSeconds();
            if (durationSeconds <= 0 || capacity.compareTo(BigDecimal.ZERO) <= 0) {
                return startTime;
            }
            // 秒级取整用四舍五入：容量本身按 CAPACITY_SCALE 截过，比例再向上取整会把
            // 千分之一秒的误差放大成整秒，进而在"工时分钟数"上多算一分钟。
            long offsetSeconds = used.divide(capacity, CAPACITY_SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(durationSeconds))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
            return startTime.plusSeconds(Math.min(Math.max(offsetSeconds, 0L), durationSeconds));
        }

        private CapacityWindow copy() {
            return new CapacityWindow(startTime, endTime, capacity, usedCapacity);
        }
    }
}
