package com.neuedu.ems.controller.ai;

import com.neuedu.ems.service.impl.ai.PrivateAssistantClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateAssistantControllerTest {
    @Test void authenticatedIdentityOverridesBodyIdentity() {
        PrivateAssistantClient client = mock(PrivateAssistantClient.class);
        PrivateAssistantController controller = new PrivateAssistantController(client);
        Map<String,Object> payload = Map.of("uid",999,"question","库存标准是什么");
        controller.dispatch(12L, new PrivateAssistantController.Dispatch("question",payload));
        verify(client).call(12L,"question",payload);
    }
    @Test void knowledgeDispatchCannotCallFaceVerificationOrEnrollment() {
        PrivateAssistantClient client=mock(PrivateAssistantClient.class);
        PrivateAssistantController controller=new PrivateAssistantController(client);
        assertThrows(ResponseStatusException.class,()->controller.dispatch(1L,
                new PrivateAssistantController.Dispatch("face.verify",Map.of())));
        verifyNoInteractions(client);
    }
    @Test void missingActionIsRejectedBeforeCallingPrivateService() {
        PrivateAssistantClient client=mock(PrivateAssistantClient.class);
        PrivateAssistantController controller=new PrivateAssistantController(client);
        assertThrows(ResponseStatusException.class,()->controller.dispatch(1L,
                new PrivateAssistantController.Dispatch(null,Map.of())));
        assertThrows(ResponseStatusException.class,()->controller.dispatch(1L,null));
        verifyNoInteractions(client);
    }
}
