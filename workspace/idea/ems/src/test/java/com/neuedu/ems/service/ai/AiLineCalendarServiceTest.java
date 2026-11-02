package com.neuedu.ems.service.ai;

import com.neuedu.ems.mapper.ai.AiDataQueryMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiLineCalendarServiceTest {
    @Test void overlappingShiftsAndPlansDoNotMultiplyCapacity() {
        var mapper = mock(AiDataQueryMapper.class);
        when(mapper.selectCalendarRules(any(), any())).thenReturn(List.of(rule("08:00:00", "12:00:00"), rule("10:00:00", "14:00:00")));
        when(mapper.selectBookedPlanWindows(any(), any())).thenReturn(List.of(plan("09:00", "11:00"), plan("10:00", "12:00")));
        var result = new AiLineCalendarService(mapper).availability(LocalDate.of(2026,10,19), LocalDate.of(2026,10,19)).get(1L);
        assertEquals(0, new BigDecimal("360").compareTo(result.available()));
        assertEquals(0, new BigDecimal("180").compareTo(result.remaining()));
    }
    @Test void overnightShiftIncludesPreviousDateAndClipsAtBoundary() {
        var mapper=mock(AiDataQueryMapper.class);
        when(mapper.selectCalendarRules(any(),any())).thenReturn(List.of(rule("22:00:00","06:00:00")));
        when(mapper.selectBookedPlanWindows(any(),any())).thenReturn(List.of());
        var result=new AiLineCalendarService(mapper).availability(LocalDate.of(2026,10,20),LocalDate.of(2026,10,20)).get(1L);
        assertEquals(0,new BigDecimal("360").compareTo(result.available()));
    }
    @Test void absentCalendarCannotFabricateAnEightHourShift() {
        var mapper=mock(AiDataQueryMapper.class);
        when(mapper.selectCalendarRules(any(),any())).thenReturn(List.of());
        when(mapper.selectBookedPlanWindows(any(),any())).thenReturn(List.of(plan("09:00","11:00")));
        var result=new AiLineCalendarService(mapper).availability(LocalDate.of(2026,10,19),LocalDate.of(2026,10,19)).get(1L);
        assertEquals(0,result.available().signum()); assertEquals(0,result.remaining().signum());
    }
    @Test void rejectsUnboundedRangeBeforeQuery() {
        var mapper=mock(AiDataQueryMapper.class);
        assertThrows(IllegalArgumentException.class,()->new AiLineCalendarService(mapper).availability(LocalDate.of(2025,1,1),LocalDate.of(2026,10,24)));
        verifyNoInteractions(mapper);
    }
    private Map<String,Object> rule(String start,String end) {
        return Map.of("lineId",1L,"dayOfWeek",1,"effectiveFrom",LocalDate.of(2026,1,1),"capacityFactor",BigDecimal.ONE,"startTime",LocalTime.parse(start),"endTime",LocalTime.parse(end));
    }
    private Map<String,Object> plan(String start,String end) {
        return Map.of("lineId",1L,"plannedStartTime",LocalDateTime.parse("2026-10-19T"+start),"plannedEndTime",LocalDateTime.parse("2026-10-19T"+end));
    }
}
