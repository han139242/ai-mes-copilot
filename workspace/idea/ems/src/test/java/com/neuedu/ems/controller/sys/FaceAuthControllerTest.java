package com.neuedu.ems.controller.sys;

import com.neuedu.ems.entity.sys.SysUser;
import com.neuedu.ems.service.impl.ai.PrivateAssistantClient;
import com.neuedu.ems.service.sys.AuthService;
import com.neuedu.ems.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FaceAuthControllerTest {
    private final AuthService auth=mock(AuthService.class);
    private final PrivateAssistantClient client=mock(PrivateAssistantClient.class);
    private final JwtUtil jwt=mock(JwtUtil.class);
    private final FaceAuthController controller=new FaceAuthController(auth,client,jwt);

    @Test void discoveryRosterIsServerOwnedAndNoAccountIsReturnedByGuidance() {
        when(auth.faceIdentificationCandidates()).thenReturn(java.util.List.of(8L,9L));
        controller.identificationChallenge(new MockHttpServletRequest());
        verify(client).identify("face.identify.challenge",Map.of("eligibleIds",java.util.List.of(8L,9L)));
        controller.identificationFrame(new MockHttpServletRequest(),frame());
        verify(client).identify("face.identify.frame",Map.of("challengeId","a".repeat(32),"step",0,
                "frame","synthetic-controller-test","purpose","login"));
        verifyNoInteractions(jwt);
        verify(auth,never()).faceCandidate(anyString());
    }
    @Test void discoveryRechecksAccountBeforeJwtAndIgnoresBrowserIdentity() {
        when(auth.faceIdentificationCandidates()).thenReturn(java.util.List.of(8L));
        when(client.identify(eq("face.identify.verify"),anyMap())).thenReturn(Map.of("verified",true,"userId",8));
        SysUser user=new SysUser();user.setUserId(8L);user.setUsername("synthetic-user");
        when(auth.completeFaceLogin(8L,true)).thenReturn(user);
        when(jwt.generateToken(8L,"synthetic-user")).thenReturn("synthetic-jwt");
        assertEquals("synthetic-jwt",controller.identificationLogin(new MockHttpServletRequest(),frame()).get("token"));
        verify(client).identify(eq("face.identify.verify"),argThat(m->
                m.get("eligibleIds").equals(java.util.List.of(8L)) && !m.containsKey("username") && !m.containsKey("uid")));
    }
    @Test void ambiguousForeignOrDisabledIdentityCannotIssueJwt() {
        when(auth.faceIdentificationCandidates()).thenReturn(java.util.List.of(8L));
        for(Object answer:new Object[]{Map.of("verified",false),Map.of("verified",true,"userId",999),
                Map.of("verified",true,"userId",8)}) {
            FaceAuthController instance=new FaceAuthController(auth,client,jwt);
            when(client.identify(eq("face.identify.verify"),anyMap())).thenReturn(answer);
            assertThrows(ResponseStatusException.class,()->instance.identificationLogin(new MockHttpServletRequest(),frame()));
        }
        verifyNoInteractions(jwt);
    }
    @Test void captureQualityErrorsDoNotCountAsWrongIdentity() {
        when(auth.faceCandidate("test-user")).thenReturn(8L);
        when(client.call(eq(8L),eq("face.verify"),anyMap())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"bad capture"));
        assertThrows(ResponseStatusException.class,()->controller.login(new MockHttpServletRequest(),frame()));
        verify(auth,never()).completeFaceLogin(anyLong(),anyBoolean());
    }
    @Test void sideGuidanceRequiresAndForwardsOriginalFrontalFrame() {
        var p=new java.util.HashMap<String,Object>(frame());p.put("step",1);
        assertThrows(ResponseStatusException.class,()->controller.identificationFrame(new MockHttpServletRequest(),p));
        p.put("referenceFrame","synthetic-front");
        controller.identificationFrame(new MockHttpServletRequest(),p);
        verify(client).identify(eq("face.identify.frame"),argThat(m->"synthetic-front".equals(m.get("referenceFrame"))));
    }

    private Map<String,Object> frame() {
        return Map.of("username","test-user","uid",999L,"purpose","enroll",
                "challengeId","a".repeat(32),"step",0,"frame","synthetic-controller-test");
    }
    @Test void loginGuidanceIsNotAnAuthenticationResultAndPurposeIsServerOwned() {
        when(auth.faceCandidate("test-user")).thenReturn(8L);
        controller.loginFrame(new MockHttpServletRequest(),frame());
        verify(client).call(8L,"face.frame",Map.of("challengeId","a".repeat(32),"step",0,
                "frame","synthetic-controller-test","purpose","login"));
        verify(auth,never()).completeFaceLogin(anyLong(),anyBoolean());
        verifyNoInteractions(jwt);
    }
    @Test void enrollmentGuidanceUsesAuthenticatedOwnerAndValidChallengeWithoutAnotherPassword() {
        when(auth.getUserInfo(8L)).thenReturn(mock(com.neuedu.ems.common.AuthUserInfo.class));
        controller.enrollmentFrame(new MockHttpServletRequest(),8L,frame());
        verify(client).call(8L,"face.frame",Map.of("challengeId","a".repeat(32),"step",0,
                "frame","synthetic-controller-test","purpose","enroll"));
        verify(auth,never()).reauthenticate(anyLong(),anyString());
        verifyNoInteractions(jwt);
    }
    @Test void guidanceIsRateLimitedWithoutAffectingLoginLockout() {
        when(auth.faceCandidate("test-user")).thenReturn(8L);
        controller.loginFrame(new MockHttpServletRequest(),frame());
        var failure=assertThrows(ResponseStatusException.class,
                ()->controller.loginFrame(new MockHttpServletRequest(),frame()));
        assertEquals(429,failure.getStatusCode().value());
        verify(auth,never()).completeFaceLogin(anyLong(),anyBoolean());
    }
    @Test void guidanceRejectsMissingChallengeAndInvalidStep() {
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->controller.loginFrame(new MockHttpServletRequest(),Map.of("step",-1))).getStatusCode().value());
        verifyNoInteractions(client,jwt);
    }
    @Test void independentChallengesShareIpCapacityWithoutSharingThePoseInterval() {
        var clock=new java.util.concurrent.atomic.AtomicLong(10000);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"frameClock",
                (java.util.function.LongSupplier)clock::get);
        when(auth.faceCandidate("test-user")).thenReturn(8L);
        when(auth.faceCandidate("second-user")).thenReturn(9L);
        controller.loginFrame(new MockHttpServletRequest(),frame());
        var second=new java.util.HashMap<String,Object>(frame());
        second.put("username","second-user");second.put("challengeId","b".repeat(32));
        clock.addAndGet(200);
        controller.loginFrame(new MockHttpServletRequest(),second);
        clock.addAndGet(100);
        assertEquals(429,assertThrows(ResponseStatusException.class,
                ()->controller.loginFrame(new MockHttpServletRequest(),frame())).getStatusCode().value());
        verify(client).call(eq(9L),eq("face.frame"),anyMap());
    }
    @Test void unknownCandidatesStillConsumeIpQuotaBeforeAnotherDatabaseLookup() {
        when(auth.faceCandidate("test-user")).thenReturn(null);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"frameClock",
                (java.util.function.LongSupplier)()->10000L);
        var request=new MockHttpServletRequest();
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->controller.loginFrame(request,frame())).getStatusCode().value());
        assertEquals(429,assertThrows(ResponseStatusException.class,
                ()->controller.loginFrame(new MockHttpServletRequest(),frame())).getStatusCode().value());
        verify(auth,times(1)).faceCandidate("test-user");
        verifyNoInteractions(client,jwt);
    }

    @Test void unsuccessfulFaceUsesOriginalLockoutAccounting() {
        Map<String,Object> payload=Map.of("username","test-user");
        when(auth.faceCandidate("test-user")).thenReturn(8L);
        when(client.call(8L,"face.verify",payload)).thenReturn(Map.of("verified",false));
        assertThrows(ResponseStatusException.class,()->controller.login(new MockHttpServletRequest(),payload));
        verify(auth).completeFaceLogin(8L,false);
        verifyNoInteractions(jwt);
    }
    @Test void successfulFaceRechecksAccountBeforeIssuingOriginalJwt() {
        Map<String,Object> payload=Map.of("username","test-user");
        when(auth.faceCandidate("test-user")).thenReturn(8L);
        when(client.call(8L,"face.verify",payload)).thenReturn(Map.of("verified",true));
        SysUser user=new SysUser();user.setUserId(8L);user.setUsername("test-user");
        when(auth.completeFaceLogin(8L,true)).thenReturn(user);
        when(jwt.generateToken(8L,"test-user")).thenReturn("signed-test-token");
        assertEquals("signed-test-token",controller.login(new MockHttpServletRequest(),payload).get("token"));
    }
    @Test void adminCanResetExistingLockedAccountWithoutLoggingItIn() {
        when(auth.userExists(9L)).thenReturn(true);
        controller.adminReset(1L,9L);
        verify(auth,never()).getUserInfo(9L);
        verify(client).call(9L,"face.reset",Map.of("actor",1L));
    }
}
