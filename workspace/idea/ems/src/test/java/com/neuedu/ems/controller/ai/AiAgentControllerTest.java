package com.neuedu.ems.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.service.ai.AiCommandService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentControllerTest {
    @Test void structuredFormPreservesWarehouseAndActualReportValuesButRejectsInternalKeys() throws Exception {
        var service=mock(AiCommandService.class);
        var controller=new AiAgentController(service);
        var request=new ObjectMapper().readValue("""
                {"action":"GENERATE_MATERIAL_ISSUE","targetNo":"C1-WO-20261101-010",
                 "source":"WORKBENCH","params":{"warehouseId":3,"warehouseType":"SEMI_FINISHED",
                 "qualifiedQuantity":1,"defectQuantity":0,"remark":"课程演练",
                 "auditResult":"APPROVED","targetId":999,"canExecute":true}}
                """,AiAgentController.StructuredPreviewRequest.class);
        controller.previewStructured(request,mock(AuthorizationContext.class));
        verify(service).previewStructured(eq("GENERATE_MATERIAL_ISSUE"),argThat(params ->
                Integer.valueOf(3).equals(params.get("warehouseId"))
                && "SEMI_FINISHED".equals(params.get("warehouseType"))
                && Integer.valueOf(1).equals(params.get("qualifiedQuantity"))
                && Integer.valueOf(0).equals(params.get("defectQuantity"))
                && "APPROVED".equals(params.get("auditResult"))
                && !params.containsKey("canExecute") && !params.containsKey("targetId")),any(),any());
    }
}
