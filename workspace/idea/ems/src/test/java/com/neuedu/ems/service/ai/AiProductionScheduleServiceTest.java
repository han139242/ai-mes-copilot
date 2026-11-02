package com.neuedu.ems.service.ai;

import com.neuedu.ems.entity.mfg.MfgProductionPlan;
import com.neuedu.ems.entity.mfg.MfgScheduleItem;
import com.neuedu.ems.entity.mfg.MfgScheduleScenario;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.mfg.CommandRequestService;
import com.neuedu.ems.service.mfg.MfgProductionPlanService;
import com.neuedu.ems.service.mfg.MfgScheduleScenarioService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiProductionScheduleServiceTest {
    private final MfgScheduleScenarioService scenarios = mock(MfgScheduleScenarioService.class);
    private final MfgProductionPlanService plans = mock(MfgProductionPlanService.class);
    private final CommandRequestService requests = mock(CommandRequestService.class);
    private final AiProductionScheduleService service = new AiProductionScheduleService(scenarios, plans, requests);
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 1, 8, 0);
    private final LocalDateTime end = LocalDateTime.of(2026, 11, 1, 10, 0);

    @Test
    void previewIsReadOnly() {
        when(scenarios.listEligibleLinesForOrder(1L)).thenReturn(List.of());
        assertTrue(service.preview(1L).isEmpty());
        verify(scenarios, never()).openManualDraft(any());
    }

    @Test
    void confirmsExactlyThePreviewedSlotAndRegistersIdempotency() {
        when(requests.digest(anyString())).thenReturn("digest");
        when(requests.replayed("AI_SCHEDULE_PRODUCTION_ORDER", "key", "digest")).thenReturn(null);
        var draft = new MfgScheduleScenario(); draft.setScenarioId(5L); draft.setVersion(0);
        var updated = new MfgScheduleScenario(); updated.setScenarioId(5L); updated.setVersion(1);
        var item = new MfgScheduleItem(); item.setScheduleItemId(6L); item.setVersion(0);
        item.setPlannedStartTime(start); item.setPlannedEndTime(end);
        var plan = new MfgProductionPlan(); plan.setPlanId(9L);
        when(scenarios.openManualDraft(7L)).thenReturn(draft);
        when(scenarios.addManualItem(5L, 0, 1L, 2L, 7L)).thenReturn(item);
        when(scenarios.getScenarioById(5L)).thenReturn(updated);
        when(scenarios.confirmManualItem(5L, 6L, 1, 0, "AI 工作台确认排程", 7L)).thenReturn(plan);
        assertSame(plan, service.confirm(1L, 2L, start, end, 7L, "key"));
        verify(scenarios).validatePlanningPlacement(1L, 2L, start, end);
        verify(requests).record(eq("AI_SCHEDULE_PRODUCTION_ORDER"), eq("key"), eq("PRODUCTION_ORDER"),
                eq(1L), eq("digest"), eq(9L), eq(7L), isNull(), any());
    }

    @Test
    void confirmKeepsTheApprovedSlotWhenTheDefaultCandidateMoves() {
        when(requests.digest(anyString())).thenReturn("digest");
        when(requests.replayed("AI_SCHEDULE_PRODUCTION_ORDER", "key", "digest")).thenReturn(null);
        var draft = new MfgScheduleScenario(); draft.setScenarioId(5L); draft.setVersion(0);
        var afterAdd = new MfgScheduleScenario(); afterAdd.setScenarioId(5L); afterAdd.setVersion(1);
        var afterUpdate = new MfgScheduleScenario(); afterUpdate.setScenarioId(5L); afterUpdate.setVersion(2);
        var item = new MfgScheduleItem(); item.setScheduleItemId(6L); item.setVersion(0);
        item.setPlannedStartTime(start.plusMinutes(1)); item.setPlannedEndTime(end.plusMinutes(1));
        var fixed = new MfgScheduleItem(); fixed.setScheduleItemId(6L); fixed.setVersion(1);
        var plan = new MfgProductionPlan(); plan.setPlanId(9L);
        when(scenarios.openManualDraft(7L)).thenReturn(draft);
        when(scenarios.addManualItem(5L, 0, 1L, 2L, 7L)).thenReturn(item);
        when(scenarios.getScenarioById(5L)).thenReturn(afterAdd, afterUpdate);
        when(scenarios.updateManualPlacement(5L, 6L, 1, 0, 2L, start, end, "AI 工作台确认排程", 7L))
                .thenReturn(fixed);
        when(scenarios.confirmManualItem(5L, 6L, 2, 1, "AI 工作台确认排程", 7L)).thenReturn(plan);
        assertSame(plan, service.confirm(1L, 2L, start, end, 7L, "key"));
        verify(scenarios).updateManualPlacement(5L, 6L, 1, 0, 2L, start, end, "AI 工作台确认排程", 7L);
    }

    @Test
    void conflictPreventsCreatingDraftOrPlan() {
        when(requests.digest(anyString())).thenReturn("digest");
        when(requests.replayed("AI_SCHEDULE_PRODUCTION_ORDER", "key", "digest")).thenReturn(null);
        when(scenarios.validatePlanningPlacement(1L, 2L, start, end))
                .thenThrow(new BusinessException(400, "产线冲突"));
        assertThrows(BusinessException.class, () -> service.confirm(1L, 2L, start, end, 7L, "key"));
        verify(scenarios, never()).openManualDraft(any());
    }
}
