package com.neuedu.ems.service.impl.ai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.neuedu.ems.entity.ai.AiAlertAction;
import com.neuedu.ems.entity.ai.AiAlertEvent;
import com.neuedu.ems.entity.ai.AiAlertRule;
import com.neuedu.ems.entity.ai.AiAlertScan;
import com.neuedu.ems.entity.eam.EamRepairOrder;
import com.neuedu.ems.entity.mfg.MfgException;
import com.neuedu.ems.entity.wms.WmsReplenishmentSuggestion;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.ai.AiAlertActionMapper;
import com.neuedu.ems.mapper.ai.AiAlertEventMapper;
import com.neuedu.ems.mapper.ai.AiAlertRuleMapper;
import com.neuedu.ems.mapper.ai.AiAlertScanMapper;
import com.neuedu.ems.mapper.ai.AlertMetricMapper;
import com.neuedu.ems.mapper.eam.EamRepairOrderMapper;
import com.neuedu.ems.mapper.mfg.MfgExceptionMapper;
import com.neuedu.ems.mapper.wms.WmsReplenishmentSuggestionMapper;
import com.neuedu.ems.service.ai.AlertStreamService;
import com.neuedu.ems.service.impl.sys.ResponsibilityNotificationSupport;
import com.neuedu.ems.service.wms.WmsReplenishmentSuggestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主动预警中心核心逻辑测试。
 *
 * <p>覆盖阈值判定、事件生命周期（新开/升级/持续命中/恢复关闭）、
 * 冷却去重、自动动作与"同一缺口不重复建补货建议"这几个真正会出问题的点。</p>
 */
class AiAlertServiceImplTest {

    private AiAlertRuleMapper ruleMapper;
    private AiAlertEventMapper eventMapper;
    private AiAlertActionMapper actionMapper;
    private AiAlertScanMapper scanMapper;
    private AlertMetricMapper metricMapper;
    private MfgExceptionMapper exceptionMapper;
    private EamRepairOrderMapper repairOrderMapper;
    private WmsReplenishmentSuggestionService replenishmentService;
    private WmsReplenishmentSuggestionMapper replenishmentSuggestionMapper;
    private ResponsibilityNotificationSupport notificationSupport;
    private AlertStreamService streamService;
    private AiAlertServiceImpl service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 取列名依赖实体元数据，先注册一次，否则会报 lambda cache 缺失
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(AiAlertScan.class, AiAlertRule.class, AiAlertEvent.class,
                AiAlertAction.class, WmsReplenishmentSuggestion.class, MfgException.class, EamRepairOrder.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(configuration, entity.getSimpleName()), entity);
        }
        ruleMapper = mock(AiAlertRuleMapper.class);
        eventMapper = mock(AiAlertEventMapper.class);
        actionMapper = mock(AiAlertActionMapper.class);
        scanMapper = mock(AiAlertScanMapper.class);
        metricMapper = mock(AlertMetricMapper.class);
        exceptionMapper = mock(MfgExceptionMapper.class);
        repairOrderMapper = mock(EamRepairOrderMapper.class);
        replenishmentService = mock(WmsReplenishmentSuggestionService.class);
        replenishmentSuggestionMapper = mock(WmsReplenishmentSuggestionMapper.class);
        notificationSupport = mock(ResponsibilityNotificationSupport.class);
        streamService = mock(AlertStreamService.class);
        service = new AiAlertServiceImpl(ruleMapper, eventMapper, actionMapper, scanMapper, metricMapper,
                exceptionMapper, repairOrderMapper, replenishmentService, replenishmentSuggestionMapper,
                notificationSupport, streamService);

        when(scanMapper.insert(any(AiAlertScan.class))).thenAnswer(inv -> {
            AiAlertScan scan = inv.getArgument(0);
            scan.setScanId(7L);
            return 1;
        });
        when(scanMapper.updateById(any(AiAlertScan.class))).thenReturn(1);
        when(scanMapper.selectOne(any())).thenReturn(null);
        when(eventMapper.insert(any(AiAlertEvent.class))).thenAnswer(inv -> {
            AiAlertEvent event = inv.getArgument(0);
            event.setAlertId(31L);
            return 1;
        });
        when(eventMapper.updateById(any(AiAlertEvent.class))).thenReturn(1);
        when(eventMapper.selectList(any())).thenReturn(List.of());
        when(actionMapper.insert(any(AiAlertAction.class))).thenReturn(1);
        when(ruleMapper.update(isNull(), any())).thenReturn(1);
        when(replenishmentSuggestionMapper.selectOne(any())).thenReturn(null);
        when(notificationSupport.departmentManager(anyString())).thenReturn(12L);
        when(notificationSupport.target(anyLong(), anyString(), anyString()))
                .thenAnswer(inv -> new com.neuedu.ems.common.NotificationEventPayload.RecipientTarget(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(streamService.broadcast(anyString(), any())).thenReturn(1);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private AiAlertRule rule(String code, String comparator, String warning, String critical,
                             String actions, int cooldown) {
        AiAlertRule rule = new AiAlertRule();
        rule.setRuleId(5L);
        rule.setRuleNo("RULE-" + code);
        rule.setRuleName("测试规则-" + code);
        rule.setMetricCode(code);
        rule.setScopeType(AiAlertRule.SCOPE_ALL);
        rule.setComparator(comparator);
        rule.setWarningThreshold(new BigDecimal(warning));
        rule.setCriticalThreshold(critical == null ? null : new BigDecimal(critical));
        rule.setThresholdUnit("QUANTITY");
        rule.setAutoActions(actions);
        rule.setAcknowledgmentRequired(1);
        rule.setCooldownMinutes(cooldown);
        rule.setStatus(AiAlertRule.STATUS_ENABLED);
        rule.setVersion(0);
        return rule;
    }

    private Map<String, Object> row(long bizId, String bizNo, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bizId", bizId);
        row.put("bizNo", bizNo);
        row.put("subjectName", "物料" + bizId);
        row.put("extraText", "测试数据");
        row.put("metricValue", new BigDecimal(value));
        return row;
    }

    private void givenRules(AiAlertRule... rules) {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rules));
    }

    private void givenMaterialRows(Map<String, Object>... rows) {
        when(metricMapper.materialAvailable(isNull())).thenReturn(List.of(rows));
    }

    // ------------------------------------------------------------------
    // 阈值与严重度
    // ------------------------------------------------------------------

    @Test
    void scanOpensAlertWhenValueFallsBelowWarningThreshold() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30));
        givenMaterialRows(row(1L, "M-1", "40"));

        Map<String, Object> result = service.scan(9L);

        assertEquals(1, result.get("openedCount"));
        assertEquals(1, result.get("notifiedCount"));
        ArgumentCaptor<AiAlertEvent> captor = ArgumentCaptor.forClass(AiAlertEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(AiAlertEvent.SEVERITY_WARNING, captor.getValue().getSeverity());
        assertEquals("AL-5-1", captor.getValue().getAlertNo());
        assertEquals(AiAlertEvent.STATUS_OPEN, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getSummary());
        assertTrue(captor.getValue().getSummary().contains("预警限度"));
    }

    @Test
    void valueBelowCriticalThresholdEscalatesSeverityImmediately() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30));
        givenMaterialRows(row(1L, "M-1", "5"));

        service.scan(9L);

        ArgumentCaptor<AiAlertEvent> captor = ArgumentCaptor.forClass(AiAlertEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(AiAlertEvent.SEVERITY_CRITICAL, captor.getValue().getSeverity());
    }

    @Test
    void valueAboveWarningThresholdProducesNoEvent() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30));
        givenMaterialRows(row(1L, "M-1", "80"));

        Map<String, Object> result = service.scan(9L);

        assertEquals(0, result.get("openedCount"));
        assertEquals(1, result.get("evaluatedCount"), "对象仍会被评估，只是没有越过限度");
        verify(eventMapper, never()).insert(any(AiAlertEvent.class));
    }

    @Test
    void upperBoundMetricUsesGreaterThanComparison() {
        givenRules(rule("EQUIPMENT_FAULT", "GT", "2", "8", "NOTIFY", 30));
        Map<String, Object> faulty = row(3L, "EQ-3", "9");
        when(metricMapper.equipmentFault()).thenReturn(List.of(faulty));

        Map<String, Object> result = service.scan(9L);

        assertEquals(1, result.get("openedCount"));
        ArgumentCaptor<AiAlertEvent> captor = ArgumentCaptor.forClass(AiAlertEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(AiAlertEvent.SEVERITY_CRITICAL, captor.getValue().getSeverity());
    }

    // ------------------------------------------------------------------
    // 生命周期：持续命中、升级、恢复关闭
    // ------------------------------------------------------------------

    @Test
    void scheduledScanSkipsRuleUntilItsOwnScanIntervalElapses() {
        AiAlertRule rule = rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30);
        rule.setIntervalMinutes(10);
        rule.setLastScanTime(LocalDateTime.now().minusMinutes(2));
        givenRules(rule);
        givenMaterialRows(row(1L, "M-1", "5"));

        service.scanScheduled();

        verify(metricMapper, org.mockito.Mockito.never()).materialAvailable(org.mockito.ArgumentMatchers.any());
        // 一条规则都没到期 = 空转轮：不取数，也不在扫描台账里留一行。
        // 否则 60 秒心跳会把台账刷成"规则 0 条、对象 0 个"的流水账，真正取过数的轮次被埋掉。
        verify(scanMapper, never()).insert(any(AiAlertScan.class));
        verify(scanMapper, never()).updateById(any(AiAlertScan.class));
    }

    @Test
    void idleScheduledScanReportsWhyItDidNotPersistARecord() {
        AiAlertRule rule = rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30);
        rule.setIntervalMinutes(10);
        rule.setLastScanTime(LocalDateTime.now().minusMinutes(2));
        givenRules(rule);
        givenMaterialRows(row(1L, "M-1", "5"));

        int opened = service.scanScheduled();

        assertEquals(0, opened);
        verify(scanMapper, never()).insert(any(AiAlertScan.class));
    }

    @Test
    void scheduledScanRunsRuleAgainOnceItsOwnScanIntervalElapsed() {
        AiAlertRule rule = rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30);
        rule.setIntervalMinutes(10);
        rule.setLastScanTime(LocalDateTime.now().minusMinutes(11));
        givenRules(rule);
        givenMaterialRows(row(1L, "M-1", "5"));

        service.scanScheduled();

        ArgumentCaptor<AiAlertScan> captor = ArgumentCaptor.forClass(AiAlertScan.class);
        verify(scanMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getRuleCount(), "已经过了自己的扫描间隔，本轮要正常取数");
        assertEquals(1, captor.getValue().getOpenedCount());
    }

    @Test
    void manualScanIgnoresTheIntervalSoPeopleCanForceARefresh() {
        AiAlertRule rule = rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30);
        rule.setIntervalMinutes(60);
        rule.setLastScanTime(LocalDateTime.now().minusMinutes(1));
        givenRules(rule);
        givenMaterialRows(row(1L, "M-1", "5"));

        Map<String, Object> result = service.scan(9L);

        assertEquals(1, result.get("ruleCount"), "手工点立即扫描是人的明确意图，不该被间隔挡住");
        assertEquals(1, result.get("openedCount"));
    }

    @Test
    void repeatedHitInsideCooldownDoesNotNotifyAgainButCountsOccurrence() {
        AiAlertRule rule = rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30);
        givenRules(rule);
        givenMaterialRows(row(1L, "M-1", "40"));
        AiAlertEvent existing = new AiAlertEvent();
        existing.setAlertId(31L);
        existing.setAlertNo("AL-5-1");
        existing.setScopeKey("1");
        existing.setRuleId(5L);
        existing.setSeverity(AiAlertEvent.SEVERITY_WARNING);
        existing.setStatus(AiAlertEvent.STATUS_OPEN);
        existing.setOccurrenceCount(1);
        existing.setLastNotifiedTime(LocalDateTime.now().minusMinutes(1));
        existing.setVersion(3);
        when(eventMapper.selectOne(any())).thenReturn(existing);

        Map<String, Object> result = service.scan(9L);

        assertEquals(1, result.get("repeatedCount"));
        assertEquals(0, result.get("openedCount"));
        assertEquals(0, result.get("notifiedCount"), "冷却期内不能重复弹窗");
        ArgumentCaptor<AiAlertEvent> captor = ArgumentCaptor.forClass(AiAlertEvent.class);
        verify(eventMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getOccurrenceCount());
        assertEquals(3, captor.getValue().getVersion(), "更新要带原版本号走乐观锁");
    }

    @Test
    void severityRiseOnRepeatedHitIsReportedAsEscalation() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 0));
        givenMaterialRows(row(1L, "M-1", "5"));
        AiAlertEvent existing = new AiAlertEvent();
        existing.setAlertId(31L);
        existing.setAlertNo("AL-5-1");
        existing.setScopeKey("1");
        existing.setRuleId(5L);
        existing.setSeverity(AiAlertEvent.SEVERITY_WARNING);
        existing.setStatus(AiAlertEvent.STATUS_OPEN);
        existing.setOccurrenceCount(2);
        existing.setVersion(4);
        when(eventMapper.selectOne(any())).thenReturn(existing);

        Map<String, Object> result = service.scan(9L);

        assertEquals(1, result.get("escalatedCount"));
        ArgumentCaptor<AiAlertEvent> captor = ArgumentCaptor.forClass(AiAlertEvent.class);
        // 升级后紧接着要回写通知时间，所以 updateById 会被调用两次；第一条才是升级本身
        verify(eventMapper, atLeastOnce()).updateById(captor.capture());
        AiAlertEvent escalatedUpdate = captor.getAllValues().get(0);
        assertEquals(AiAlertEvent.SEVERITY_CRITICAL, escalatedUpdate.getSeverity());
    }

    @Test
    void recoveredMetricClosesOpenEventWithReadableReason() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30));
        // 本轮没有命中任何对象 → 之前那条 OPEN 事件要自动复核关闭
        givenMaterialRows();
        AiAlertEvent open = new AiAlertEvent();
        open.setAlertId(31L);
        open.setAlertNo("AL-5-1");
        open.setScopeKey("1");
        open.setRuleId(5L);
        open.setStatus(AiAlertEvent.STATUS_OPEN);
        open.setSeverity(AiAlertEvent.SEVERITY_WARNING);
        open.setVersion(2);
        when(eventMapper.selectList(any())).thenReturn(List.of(open));

        Map<String, Object> result = service.scan(9L);

        assertEquals(1, result.get("closedCount"));
        ArgumentCaptor<AiAlertEvent> captor = ArgumentCaptor.forClass(AiAlertEvent.class);
        verify(eventMapper).updateById(captor.capture());
        assertEquals(AiAlertEvent.STATUS_CLOSED, captor.getValue().getStatus());
        assertEquals(AiAlertEvent.CLOSE_REASON_RECOVERED, captor.getValue().getCloseReason());
        // 复核关闭要留一条 VERIFY 动作，方便追溯是谁在什么时候关掉的
        ArgumentCaptor<AiAlertAction> action = ArgumentCaptor.forClass(AiAlertAction.class);
        verify(actionMapper, atLeastOnce()).insert(action.capture());
        assertTrue(action.getAllValues().stream()
                .anyMatch(a -> AiAlertAction.TYPE_VERIFY.equals(a.getActionType())));
    }

    @Test
    void recoveredMetricAlsoClosesItsAutoRegisteredException() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "EXCEPTION", 30));
        givenMaterialRows();
        AiAlertEvent open = new AiAlertEvent();
        open.setAlertId(31L);
        open.setAlertNo("AL-5-1");
        open.setScopeKey("1");
        open.setRuleId(5L);
        open.setStatus(AiAlertEvent.STATUS_OPEN);
        open.setSeverity(AiAlertEvent.SEVERITY_WARNING);
        open.setVersion(2);
        when(eventMapper.selectList(any())).thenReturn(List.of(open));
        MfgException auto = new MfgException();
        auto.setExceptionId(21L);
        auto.setExceptionNo("EXC-ALERT-AL-5-1");
        auto.setStatus("SUBMITTED");
        when(exceptionMapper.selectOne(any())).thenReturn(auto);

        service.scan(9L);

        // 预警复核关闭时异常台账不能还挂着 SUBMITTED，否则待办里永远清不掉
        ArgumentCaptor<MfgException> captor = ArgumentCaptor.forClass(MfgException.class);
        verify(exceptionMapper).updateById(captor.capture());
        assertEquals("CLOSED", captor.getValue().getStatus());
        assertEquals(21L, captor.getValue().getExceptionId());
        assertNotNull(captor.getValue().getHandleResult());
    }

    @Test
    void closedEventReopensWhenProblemComesBack() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30));
        givenMaterialRows(row(1L, "M-1", "5"));
        AiAlertEvent closed = new AiAlertEvent();
        closed.setAlertId(31L);
        closed.setAlertNo("AL-5-1");
        closed.setScopeKey("1");
        closed.setRuleId(5L);
        closed.setStatus(AiAlertEvent.STATUS_CLOSED);
        closed.setSeverity(AiAlertEvent.SEVERITY_WARNING);
        closed.setOccurrenceCount(3);
        closed.setVersion(6);
        when(eventMapper.selectOne(any())).thenReturn(closed);

        Map<String, Object> result = service.scan(9L);

        assertEquals(1, result.get("openedCount"));
        ArgumentCaptor<AiAlertEvent> captor = ArgumentCaptor.forClass(AiAlertEvent.class);
        verify(eventMapper, atLeastOnce()).updateById(captor.capture());
        AiAlertEvent reopened = captor.getAllValues().get(0);
        assertEquals(AiAlertEvent.STATUS_OPEN, reopened.getStatus());
        assertEquals(4, reopened.getOccurrenceCount());
        verify(eventMapper, never()).insert(any(AiAlertEvent.class));
    }

    // ------------------------------------------------------------------
    // 自动动作
    // ------------------------------------------------------------------

    @Test
    void replenishSkippedWhenPendingSuggestionAlreadyExists() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "REPLENISH", 30));
        givenMaterialRows(row(1L, "M-1", "5"));
        WmsReplenishmentSuggestion pending = new WmsReplenishmentSuggestion();
        pending.setSuggestionId(88L);
        pending.setSuggestionNo("RPS-88");
        when(replenishmentSuggestionMapper.selectOne(any())).thenReturn(pending);

        service.scan(9L);

        // 同一缺口不能一轮一轮刷出新的补货建议
        verify(replenishmentService, never()).generateSuggestion(any(), any(), any());
        ArgumentCaptor<AiAlertAction> action = ArgumentCaptor.forClass(AiAlertAction.class);
        verify(actionMapper, atLeastOnce()).insert(action.capture());
        assertTrue(action.getAllValues().stream()
                .anyMatch(a -> AiAlertAction.TYPE_REPLENISH.equals(a.getActionType())
                        && AiAlertAction.STATUS_SKIPPED.equals(a.getActionStatus())));
    }

    @Test
    void replenishCreatesSuggestionWhenNothingPending() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "REPLENISH", 30));
        givenMaterialRows(row(1L, "M-1", "5"));
        when(replenishmentService.generateSuggestion(eq(1L), isNull(), any())).thenReturn(66L);

        service.scan(9L);

        verify(replenishmentService).generateSuggestion(eq(1L), isNull(), any());
        ArgumentCaptor<AiAlertAction> action = ArgumentCaptor.forClass(AiAlertAction.class);
        verify(actionMapper, atLeastOnce()).insert(action.capture());
        assertTrue(action.getAllValues().stream()
                .anyMatch(a -> AiAlertAction.TYPE_REPLENISH.equals(a.getActionType())
                        && AiAlertAction.STATUS_SUCCEEDED.equals(a.getActionStatus())));
    }

    @Test
    void repairActionOnlyRunsForEquipmentFaultMetric() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "REPAIR", 30));
        givenMaterialRows(row(1L, "M-1", "5"));

        service.scan(9L);

        // 原料库存不足不该去派维修单
        verify(repairOrderMapper, never()).insert(any(EamRepairOrder.class));
    }

    @Test
    void exceptionActionSkipsWhenOpenRecordExists() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "EXCEPTION", 30));
        givenMaterialRows(row(1L, "M-1", "5"));
        MfgException existing = new MfgException();
        existing.setExceptionId(21L);
        existing.setExceptionNo("EXC-ALERT-AL-5-1");
        existing.setStatus("SUBMITTED");
        when(exceptionMapper.selectOne(any())).thenReturn(existing);

        service.scan(9L);

        verify(exceptionMapper, never()).insert(any(MfgException.class));
        ArgumentCaptor<AiAlertAction> action = ArgumentCaptor.forClass(AiAlertAction.class);
        verify(actionMapper, atLeastOnce()).insert(action.capture());
        assertTrue(action.getAllValues().stream()
                .anyMatch(a -> AiAlertAction.TYPE_EXCEPTION.equals(a.getActionType())
                        && AiAlertAction.STATUS_SKIPPED.equals(a.getActionStatus())));
    }

    // ------------------------------------------------------------------
    // 幂等与失败处理
    // ------------------------------------------------------------------

    @Test
    void sameScanKeyReturnsOriginalResultWithoutRescanning() {
        AiAlertScan duplicated = new AiAlertScan();
        duplicated.setScanId(7L);
        duplicated.setScanNo("MANUAL:1");
        duplicated.setStatus(AiAlertScan.STATUS_SUCCEEDED);
        duplicated.setOpenedCount(3);
        when(scanMapper.selectOne(any())).thenReturn(duplicated);

        Map<String, Object> result = service.scan(9L);

        assertEquals(Boolean.TRUE, result.get("idempotent"));
        assertEquals(3, result.get("openedCount"));
        verify(ruleMapper, never()).selectList(any());
    }

    @Test
    void scanMarksFailedAndRethrowsWhenMetricQueryBlowsUp() {
        givenRules(rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30));
        when(metricMapper.materialAvailable(isNull()))
                .thenThrow(new IllegalStateException("数据库连接中断"));

        assertThrows(IllegalStateException.class, () -> service.scan(9L));

        ArgumentCaptor<AiAlertScan> captor = ArgumentCaptor.forClass(AiAlertScan.class);
        verify(scanMapper).updateById(captor.capture());
        assertEquals(AiAlertScan.STATUS_FAILED, captor.getValue().getStatus());
        assertTrue(captor.getValue().getErrorMessage().contains("数据库连接中断"));
    }

    @Test
    void scopeFilterOnlyMatchesTheConfiguredObject() {
        AiAlertRule rule = rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30);
        rule.setScopeType(AiAlertRule.SCOPE_MATERIAL);
        rule.setScopeValue("2");
        givenRules(rule);
        givenMaterialRows(row(1L, "M-1", "5"), row(2L, "M-2", "5"));

        Map<String, Object> result = service.scan(9L);

        assertEquals(1, result.get("openedCount"), "作用范围只应命中配置的那个物料");
        ArgumentCaptor<AiAlertEvent> captor = ArgumentCaptor.forClass(AiAlertEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(2L, captor.getValue().getBizId());
    }

    // ------------------------------------------------------------------
    // 规则维护
    // ------------------------------------------------------------------

    @Test
    void saveRuleRejectsMissingWarningThreshold() {
        AiAlertRule rule = new AiAlertRule();
        rule.setMetricCode("MATERIAL_AVAILABLE");
        rule.setWarningThreshold(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.saveRule(rule, 9L));

        assertTrue(error.getMessage().contains("限度"));
        verify(ruleMapper, never()).insert(any(AiAlertRule.class));
    }

    @Test
    void saveRuleRejectsReversedThresholdsForLowerBoundMetric() {
        AiAlertRule rule = rule("MATERIAL_AVAILABLE", "LT", "50", "80", "NOTIFY", 30);

        BusinessException error = assertThrows(BusinessException.class, () -> service.saveRule(rule, 9L));

        assertTrue(error.getMessage().contains("严重限度"));
    }

    @Test
    void saveRuleRejectsReversedThresholdsForUpperBoundMetric() {
        AiAlertRule rule = rule("EQUIPMENT_FAULT", "GT", "8", "2", "NOTIFY", 30);

        BusinessException error = assertThrows(BusinessException.class, () -> service.saveRule(rule, 9L));

        assertTrue(error.getMessage().contains("严重限度"));
    }

    @Test
    void saveRuleFillsDefaultsFromMetricCatalog() {
        AiAlertRule rule = new AiAlertRule();
        rule.setMetricCode("MATERIAL_AVAILABLE");
        rule.setWarningThreshold(new BigDecimal("60"));
        when(ruleMapper.insert(any(AiAlertRule.class))).thenAnswer(inv -> {
            AiAlertRule saved = inv.getArgument(0);
            saved.setRuleId(41L);
            return 1;
        });

        Long id = service.saveRule(rule, 9L);

        assertEquals(41L, id);
        assertEquals("LT", rule.getComparator(), "比较符要按指标默认值补上");
        assertEquals("QUANTITY", rule.getThresholdUnit());
        assertEquals(AiAlertRule.STATUS_ENABLED, rule.getStatus());
        assertEquals(30, rule.getCooldownMinutes(), "冷却时间要有默认值，否则每轮扫描都弹窗");
        assertEquals(0, rule.getHitCount());
        assertTrue(rule.getRuleNo().startsWith("AR-MATERIAL_AVAILABLE-"));
    }

    @Test
    void unknownMetricIsRejectedInsteadOfSilentlyIgnored() {
        AiAlertRule rule = new AiAlertRule();
        rule.setMetricCode("NO_SUCH_METRIC");
        rule.setWarningThreshold(BigDecimal.ONE);

        BusinessException error = assertThrows(BusinessException.class, () -> service.saveRule(rule, 9L));

        assertTrue(error.getMessage().contains("指标"));
    }

    @Test
    void changeStatusRejectsUnknownStatusValue() {
        AiAlertRule existing = rule("MATERIAL_AVAILABLE", "LT", "50", "10", "NOTIFY", 30);
        when(ruleMapper.selectById(5L)).thenReturn(existing);

        assertThrows(BusinessException.class, () -> service.changeStatus(5L, "WHATEVER", 9L));
    }

    @Test
    void overviewReportsMetricCountSoTheUiCanExplainCoverage() {
        when(ruleMapper.selectCount(any())).thenReturn(2L);
        when(eventMapper.selectCount(any())).thenReturn(0L);
        when(scanMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> overview = service.overview();

        assertEquals(19, overview.get("metricCount"));
        assertNotNull(overview.get("ruleCount"));
    }

    @Test
    void pendingSummaryReturnsZeroesWhenNothingIsOpen() {
        when(eventMapper.selectCount(any())).thenReturn(0L);
        when(eventMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> pending = service.pendingSummary();

        assertEquals(0L, pending.get("open"));
        assertEquals(0L, pending.get("critical"));
        assertNull(pending.get("latest"));
    }
}
