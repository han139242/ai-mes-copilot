package com.neuedu.ems.service.mfg;

import com.neuedu.ems.entity.factory.FactoryLine;
import com.neuedu.ems.entity.mfg.MfgLineCalendarRule;
import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.org.OrgShift;
import com.neuedu.ems.entity.process.ProcessRoute;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mfg.MfgLineCalendarRuleMapper;
import com.neuedu.ems.mapper.mfg.MfgProductionPlanMapper;
import com.neuedu.ems.mapper.org.OrgShiftMapper;
import com.neuedu.ems.mapper.process.ProcessRouteItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 组件补产的可执行窗口计算：按组件自身产线的周历、班次与日产能排产，不复制父计划时间。 */
class ComponentCapacityServiceTest {
    @Test
    void laborThenAWholeBatchFitsAnExactFreeWindowWithoutLosingOneSecond() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "6"), batch(2, 50, 60)));
        when(plans.selectList(any())).thenReturn(List.of(
                booking(91L, LocalTime.of(9, 24, 30), LocalTime.of(11, 0))));
        var result = service.plan(line(), route(), BigDecimal.ONE,
                LocalDateTime.of(DAY, LocalTime.of(8, 18, 30)), null);
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(8, 18, 30)), result.start());
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(9, 24, 30)), result.end());
        assertEquals(0, BigDecimal.ONE.compareTo(result.produced()));
    }
    @Test
    void laterBookingDoesNotShiftWorkOutOfAnEarlierFreeWindow() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        MfgProductionPlan booking = booking(91L, LocalTime.of(12, 0), LocalTime.of(13, 0));
        when(plans.selectList(any())).thenReturn(List.of(booking));
        var result = service.plan(line(), route(), BigDecimal.ONE, LocalDateTime.of(DAY, LocalTime.of(8, 0)), null);
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(8, 0)), result.start());
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(8, 6)), result.end());
    }

    @Test
    void overlappingBookingsConsumeTheirUnionAndKeepProductionOutsideBookedTime() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        when(plans.selectList(any())).thenReturn(List.of(
                booking(91L, LocalTime.of(9, 0), LocalTime.of(11, 0)),
                booking(92L, LocalTime.of(10, 0), LocalTime.of(12, 0))));
        var result = service.plan(line(), route(), new BigDecimal("50"),
                LocalDateTime.of(DAY, LocalTime.of(8, 0)), null);
        assertEquals(0, new BigDecimal("50").compareTo(result.produced()));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(8, 0)), result.start());
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(16, 0)), result.end());
    }

    private MfgProductionPlan booking(long id, LocalTime start, LocalTime end) {
        MfgProductionPlan result = new MfgProductionPlan();
        result.setPlanId(id); result.setLineId(11L); result.setStatus("RELEASED");
        result.setPlannedStartTime(LocalDateTime.of(DAY, start));
        result.setPlannedEndTime(LocalDateTime.of(DAY, end));
        return result;
    }

    @Mock private ProcessRouteItemMapper routeItems;
    @Mock private MfgLineCalendarRuleMapper calendarRules;
    @Mock private OrgShiftMapper shifts;
    @Mock private MfgProductionPlanMapper plans;

    private ComponentCapacityService service;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 26); // 周一

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ComponentCapacityService(routeItems, calendarRules, shifts, plans);
        when(plans.selectList(any())).thenReturn(List.of());
    }

    private FactoryLine line() {
        FactoryLine line = new FactoryLine();
        line.setLineId(11L);
        line.setLineCode("LAMP-LINE-HEAD");
        line.setDailyCapacity(new BigDecimal("80"));
        line.setStatus("RUNNING");
        return line;
    }

    private ProcessRoute route() {
        ProcessRoute route = new ProcessRoute();
        route.setRouteId(8L);
        return route;
    }

    private ProcessRouteItem labor(long id, String minutes) {
        ProcessRouteItem item = new ProcessRouteItem();
        item.setRouteItemId(id);
        item.setRouteId(8L);
        item.setSequenceNo((int) id);
        item.setResourceType("LABOR");
        item.setStandardTime(new BigDecimal(minutes));
        return item;
    }

    private ProcessRouteItem batch(long id, int capacity, int minutes) {
        ProcessRouteItem item = new ProcessRouteItem();
        item.setRouteItemId(id);
        item.setRouteId(8L);
        item.setSequenceNo((int) id);
        item.setResourceType("BATCH_EQUIPMENT");
        item.setStandardTime(BigDecimal.ZERO);
        item.setBatchCapacity(new BigDecimal(capacity));
        item.setBatchDurationMinutes(new BigDecimal(minutes));
        return item;
    }

    /** 单班 08:00-16:00（480 分钟），日产能 80 件。 */
    private void singleDayShift() {
        MfgLineCalendarRule rule = new MfgLineCalendarRule();
        rule.setCalendarRuleId(1L);
        rule.setLineId(11L);
        rule.setShiftId(4L);
        rule.setDayOfWeek(DAY.getDayOfWeek().getValue());
        rule.setEffectiveFrom(DAY.minusDays(30));
        rule.setCapacityFactor(BigDecimal.ONE);
        rule.setStatus("ENABLE");
        rule.setDeletedFlag(0);
        when(calendarRules.selectList(any())).thenReturn(List.of(rule));
        OrgShift shift = new OrgShift();
        shift.setShiftId(4L);
        shift.setShiftCode("LAMP-SHIFT-AM");
        shift.setStartTime(LocalTime.of(8, 0));
        shift.setEndTime(LocalTime.of(16, 0));
        when(shifts.selectById(4L)).thenReturn(shift);
    }

    @Test
    void plansInsideItsOwnLineShiftInsteadOfCopyingParentWindow() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        ComponentCapacityService.Plan plan = service.plan(line(), route(), new BigDecimal("40"),
                LocalDateTime.of(DAY, LocalTime.of(6, 0)), null);
        assertEquals(new BigDecimal("40.0000"), plan.produced().setScale(4));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(8, 0)), plan.start());
        // 80 件占满 480 分钟，40 件应占 240 分钟
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(12, 0)), plan.end());
    }

    @Test
    void splitAcrossShiftsKeepsWholePiecesAndReportsShortfall() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        // 单日只有 80 件产能，请求 200 件时应只排出 80 件，由调用方拆单
        ComponentCapacityService.Plan plan = service.plan(line(), route(), new BigDecimal("200"),
                LocalDateTime.of(DAY, LocalTime.of(8, 0)), null);
        assertEquals(new BigDecimal("80.0000"), plan.produced().setScale(4));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(16, 0)), plan.end());
    }

    @Test
    void batchOperationNeedsOneContinuousWindow() {
        singleDayShift();
        // 单班 480 分钟；批次 60 分钟、每批 50 件。1 件也要占满一个 60 分钟连续窗口。
        when(routeItems.selectList(any())).thenReturn(List.of(batch(2, 50, 60)));
        ComponentCapacityService.Plan plan = service.plan(line(), route(), new BigDecimal("10"),
                LocalDateTime.of(DAY, LocalTime.of(8, 0)), null);
        assertEquals(new BigDecimal("10.0000"), plan.produced().setScale(4));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(9, 0)), plan.end());
    }

    @Test
    void batchOperationBlocksWhenNoContinuousWindowLeft() {
        singleDayShift();
        // 每批 50 件、60 分钟；请求 50 件占满 60 分钟，剩 420 分钟仍够第二、三批，
        // 但把批次时长拉到 500 分钟就没有单段能容纳。
        when(routeItems.selectList(any())).thenReturn(List.of(batch(2, 50, 500)));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.plan(line(), route(), new BigDecimal("50"),
                        LocalDateTime.of(DAY, LocalTime.of(8, 0)), null));
        assertTrue(error.getMessage().contains("没有可用产能窗口"));
    }

    @Test
    void missingCalendarIsBlockedInsteadOfUsingParentTime() {
        when(calendarRules.selectList(any())).thenReturn(List.of());
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.plan(line(), route(), BigDecimal.TEN,
                        LocalDateTime.of(DAY, LocalTime.of(8, 0)), null));
        assertTrue(error.getMessage().contains("没有启用中的产线周历"));
    }

    @Test
    void stoppedLineOrZeroCapacityIsBlocked() {
        FactoryLine stopped = line();
        stopped.setStatus("STOPPED");
        BusinessException statusError = assertThrows(BusinessException.class,
                () -> service.plan(stopped, route(), BigDecimal.TEN,
                        LocalDateTime.of(DAY, LocalTime.of(8, 0)), null));
        assertTrue(statusError.getMessage().contains("组件产线未启用"));

        FactoryLine zero = line();
        zero.setDailyCapacity(BigDecimal.ZERO);
        BusinessException capacityError = assertThrows(BusinessException.class,
                () -> service.plan(zero, route(), BigDecimal.TEN,
                        LocalDateTime.of(DAY, LocalTime.of(8, 0)), null));
        assertTrue(capacityError.getMessage().contains("未配置有效日产能"));
    }

    @Test
    void existingBookingsConsumeCapacity() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        MfgProductionPlan booked = new MfgProductionPlan();
        booked.setPlanId(99L);
        booked.setLineId(11L);
        booked.setStatus("RELEASED");
        booked.setPlannedStartTime(LocalDateTime.of(DAY, LocalTime.of(8, 0)));
        booked.setPlannedEndTime(LocalDateTime.of(DAY, LocalTime.of(12, 0)));
        when(plans.selectList(any())).thenReturn(List.of(booked));
        // 上午 240 分钟已被占（相当于 40 件），下午只剩 40 件
        ComponentCapacityService.Plan plan = service.plan(line(), route(), new BigDecimal("100"),
                LocalDateTime.of(DAY, LocalTime.of(8, 0)), null);
        assertEquals(new BigDecimal("40.0000"), plan.produced().setScale(4));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(16, 0)), plan.end());
    }

    @Test
    void releaseCheckRejectsWhenWindowNoLongerFits() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        // 计划 100 件但单日只排得下 80 件 → 生效复核必须阻断
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.assertSchedulable(line(), route(), new BigDecimal("100"),
                        LocalDateTime.of(DAY, LocalTime.of(8, 0)),
                        LocalDateTime.of(DAY, LocalTime.of(16, 0)), 5L));
        assertTrue(error.getMessage().contains("重建草稿"));
    }

    @Test
    void releaseCheckToleratesRoundingDriftOfFewSeconds() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        // 40 件占 240 分钟，重算完工 12:00；草稿记的是 11:59:59（窗口裁剪+取整差 1 秒）→ 不应判成产能不足
        assertDoesNotThrow(() -> service.assertSchedulable(line(), route(), new BigDecimal("40"),
                LocalDateTime.of(DAY, LocalTime.of(8, 0)),
                LocalDateTime.of(DAY, LocalTime.of(11, 59, 59)), 5L));
    }

    @Test
    void releaseCheckStillRejectsWhenDriftExceedsTolerance() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        // 重算完工 12:00，草稿结束只到 11:00 → 差 1 小时，超出取整偏差，必须阻断
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.assertSchedulable(line(), route(), new BigDecimal("40"),
                        LocalDateTime.of(DAY, LocalTime.of(8, 0)),
                        LocalDateTime.of(DAY, LocalTime.of(11, 0)), 5L));
        assertTrue(error.getMessage().contains("重建草稿"));
    }

    @Test
    void multipleLaborStepsShareTheSameLineCapacityInsteadOfConsumingItTwice() {
        singleDayShift();
        // 灯头路线是两道装配工序（各 1 分钟）：串行同一件产品，不能各扣一遍整线产能
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1"), labor(2, "1")));
        ComponentCapacityService.Plan plan = service.plan(line(), route(), new BigDecimal("80"),
                LocalDateTime.of(DAY, LocalTime.of(8, 0)), null);
        assertEquals(new BigDecimal("80.0000"), plan.produced().setScale(4));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(16, 0)), plan.end());
    }

    @Test
    void capacityFactorScalesDailyCapacityForTheComponentProduct() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        // 日产能 80 件、能力系数 3 → 这条线一天能给该组件排 240 件
        ComponentCapacityService.Plan plan = service.plan(line(), route(), new BigDecimal("200"),
                LocalDateTime.of(DAY, LocalTime.of(8, 0)), null, null, new BigDecimal("3"));
        assertEquals(new BigDecimal("200.0000"), plan.produced().setScale(4));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(8, 0)), plan.start());
        // 240 件占满 480 分钟，200 件占 400 分钟 → 14:40
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(14, 40)), plan.end());
    }

    @Test
    void missingCapacityFactorFallsBackToOneInsteadOfZeroCapacity() {
        singleDayShift();
        when(routeItems.selectList(any())).thenReturn(List.of(labor(1, "1")));
        ComponentCapacityService.Plan plan = service.plan(line(), route(), new BigDecimal("40"),
                LocalDateTime.of(DAY, LocalTime.of(8, 0)), null, null, null);
        assertEquals(new BigDecimal("40.0000"), plan.produced().setScale(4));
        assertEquals(LocalDateTime.of(DAY, LocalTime.of(12, 0)), plan.end());
    }
}
