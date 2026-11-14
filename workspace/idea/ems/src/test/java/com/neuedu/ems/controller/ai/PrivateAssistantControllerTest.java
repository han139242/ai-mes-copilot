package com.neuedu.ems.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.service.impl.ai.PrivateAssistantClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateAssistantControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void reviewSheetsAlwaysUseAuthenticatedIdentity() {
        PrivateAssistantClient client=mock(PrivateAssistantClient.class);
        PrivateAssistantController controller=new PrivateAssistantController(client, mapper);
        for(String action: new String[]{"notes.list","notes.add","notes.update","notes.delete"}) {
            Map<String,Object> body=Map.of("uid",999,"historyId",7);
            controller.dispatch(12L,new PrivateAssistantController.Dispatch(action,body));
            verify(client).call(12L,action,body);
        }
    }
    @Test void authenticatedIdentityOverridesBodyIdentity() {
        PrivateAssistantClient client = mock(PrivateAssistantClient.class);
        PrivateAssistantController controller = new PrivateAssistantController(client, mapper);
        Map<String,Object> payload = Map.of("uid",999,"question","库存标准是什么");
        controller.dispatch(12L, new PrivateAssistantController.Dispatch("question",payload));
        verify(client).call(12L,"question",payload);
    }
    @Test void knowledgeDispatchCannotCallFaceVerificationOrEnrollment() {
        PrivateAssistantClient client=mock(PrivateAssistantClient.class);
        PrivateAssistantController controller=new PrivateAssistantController(client, mapper);
        assertThrows(ResponseStatusException.class,()->controller.dispatch(1L,
                new PrivateAssistantController.Dispatch("face.verify",Map.of())));
        verifyNoInteractions(client);
    }
    @Test void missingActionIsRejectedBeforeCallingPrivateService() {
        PrivateAssistantClient client=mock(PrivateAssistantClient.class);
        PrivateAssistantController controller=new PrivateAssistantController(client, mapper);
        assertThrows(ResponseStatusException.class,()->controller.dispatch(1L,
                new PrivateAssistantController.Dispatch(null,Map.of())));
        assertThrows(ResponseStatusException.class,()->controller.dispatch(1L,null));
        verifyNoInteractions(client);
    }

    @Test void toolsUseTrustedIdentityAndMultipartActionsAreAllowlisted() throws Exception {
        PrivateAssistantClient client = mock(PrivateAssistantClient.class);
        PrivateAssistantController controller = new PrivateAssistantController(client, mapper);
        var body = Map.<String,Object>of("uid", 999, "query", "公开规格", "confirmed", true);
        controller.dispatch(12L, new PrivateAssistantController.Dispatch("tools.search", body));
        verify(client).call(12L, "tools.search", body);
        assertThrows(ResponseStatusException.class, () -> controller.dispatch(12L,
                new PrivateAssistantController.Dispatch("scans.publish", body)));
        var file = new MockMultipartFile("file", "scan.pdf", "application/pdf", new byte[]{1, 2});
        assertThrows(ResponseStatusException.class, () -> controller.recognize(12L, file, false, "ticket", "[2]"));
        assertThrows(ResponseStatusException.class, () -> controller.publish(12L, file, "ticket", "{}", true));
        controller.prepareScan(12L, file, "current", 1);
        verify(client).call(12L, "scans.prepare", Map.of("name", "scan.pdf", "content", "AQI=",
                "documentId", "current", "baseVersion", 1));
        controller.recognize(12L, file, true, "ticket", "[2]");
        verify(client).call(12L, "scans.recognize",
                Map.of("confirmed", true, "reviewId", "ticket", "content", "AQI=", "pages", mapper.readTree("[2]")));
        controller.publish(12L, file, "ticket", "[{\"page\":1,\"text\":\"synthetic\"}]", true);
        verify(client).call(12L, "scans.publish", Map.of("confirmed", true,
                "content", "AQI=", "reviewId", "ticket", "pages", mapper.readTree("[{\"page\":1,\"text\":\"synthetic\"}]")));
        controller.transcribe(12L, file, true);
        verify(client).call(12L, "tools.asr", Map.of("confirmed", true, "content", "AQI="));
        verifyNoMoreInteractions(client);
    }
}
