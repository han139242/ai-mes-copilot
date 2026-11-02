package com.neuedu.ems.service.impl.ai;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.controller.ai.AiTaskController;
import com.neuedu.ems.exception.DataScopeAccessDeniedException;
import com.neuedu.ems.service.ai.AiTaskService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiStartPlanAccessTest {
    private final AiTaskService tasks=mock(AiTaskService.class);
    private final AiTaskController controller=new AiTaskController(tasks);
    private AuthorizationContext context(String scope) {
        return new AuthorizationContext(5L,7L,2L,scope,Set.of("EMPLOYEE"),
                Set.of("ai:assistant:use","mfg:work-order:view","wms:outbound-order:view"),Set.of());
    }
    @Test void selfScopedUserCannotReadArbitraryWorkOrdersThroughTaskCreation() {
        assertThrows(DataScopeAccessDeniedException.class,()->controller.fromStartPlan(
                Map.of("workOrderNo","B-WO-20261027-001"),5L,context("SELF")));
        verifyNoInteractions(tasks);
    }
    @Test void authorizedGlobalUserCanCreateTheDraft() {
        when(tasks.createDefinitionFromStartPlan("B-WO-20261027-001",5L)).thenReturn(Map.of("definitionId",9L));
        var result=controller.fromStartPlan(Map.of("workOrderNo","B-WO-20261027-001"),5L,context("ALL"));
        assertEquals(200,result.getCode());
        verify(tasks).createDefinitionFromStartPlan("B-WO-20261027-001",5L);
    }
}
