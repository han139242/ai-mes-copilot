package com.neuedu.ems.controller.ai;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.common.GlobalExceptionHandler;
import com.neuedu.ems.service.ai.AlertStreamService;
import com.neuedu.ems.service.sys.PermissionService;
import com.neuedu.ems.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiAlertStreamStabilityTest {
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final AlertStreamService streams = mock(AlertStreamService.class);
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.standaloneSetup(new AiAlertStreamController(streams, jwt, permissions))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void expiredStreamReturns401InsteadOfSerializationFailure() throws Exception {
        mvc.perform(get("/api/ai/alert/stream").param("token", "expired")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        verifyNoInteractions(streams);
    }

    @Test
    void malformedIdentityReturns401WithoutSubscribing() throws Exception {
        when(jwt.validateToken("broken")).thenReturn(true);
        when(jwt.getUserIdFromToken("broken")).thenThrow(new IllegalArgumentException("invalid"));
        mvc.perform(get("/api/ai/alert/stream").param("token", "broken")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(streams);
    }

    @Test
    void validIdentityWithoutPermissionReturns403() throws Exception {
        when(jwt.validateToken("valid")).thenReturn(true);
        when(jwt.getUserIdFromToken("valid")).thenReturn(7L);
        when(permissions.getContext(7L)).thenReturn(new AuthorizationContext(7L, null, null,
                "SELF", Set.of(), Set.of(), Set.of()));
        mvc.perform(get("/api/ai/alert/stream").param("token", "valid")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
        verifyNoInteractions(streams);
    }

    @Test
    void authorizedIdentityStillOpensAnEventStream() throws Exception {
        when(jwt.validateToken("valid")).thenReturn(true);
        when(jwt.getUserIdFromToken("valid")).thenReturn(7L);
        when(permissions.getContext(7L)).thenReturn(new AuthorizationContext(7L, null, null,
                "SELF", Set.of(), Set.of("ai:assistant:use"), Set.of()));
        SseEmitter emitter = new SseEmitter(1000L);
        when(streams.subscribe(7L)).thenReturn(emitter);
        mvc.perform(get("/api/ai/alert/stream").param("token", "valid")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(streams).subscribe(7L);
        emitter.complete();
    }
}
