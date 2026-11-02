package com.neuedu.ems.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.security.PermissionCodeResolver;
import com.neuedu.ems.service.impl.sys.TokenRevocationService;
import com.neuedu.ems.service.sys.PermissionService;
import com.neuedu.ems.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

class JwtAuthenticationFilterTest {

    private JwtAuthenticationFilter filter;
    private JwtUtil jwtUtil;
    private PermissionService permissionService;
    private TokenRevocationService tokenRevocationService;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter();
        jwtUtil = mock(JwtUtil.class);
        permissionService = mock(PermissionService.class);
        tokenRevocationService = mock(TokenRevocationService.class);
        ReflectionTestUtils.setField(filter, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(filter, "permissionService", permissionService);
        ReflectionTestUtils.setField(filter, "permissionCodeResolver", new PermissionCodeResolver());
        ReflectionTestUtils.setField(filter, "tokenRevocationService", tokenRevocationService);
        ReflectionTestUtils.setField(filter, "objectMapper", new ObjectMapper());
    }

    @Test
    void temporaryRevocationCheckFailureReturns503WithoutExecutingBusiness() throws Exception {
        var request = authenticatedRequest("GET", "/api/sys/user/page");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        when(tokenRevocationService.isRevoked("token")).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        filter.doFilter(request, response, chain);
        assertEquals(503, response.getStatus());
        assertEquals(500, new ObjectMapper().readTree(response.getContentAsString()).get("code").asInt());
        assertNull(chain.getRequest());
    }

    @Test
    void returns403WhenAuthenticatedUserLacksPermission() throws Exception {
        MockHttpServletRequest request = authenticatedRequest("GET", "/api/sys/user/page");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(permissionService.getContext(7L)).thenReturn(context(Set.of("dashboard:summary:view")));

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertEquals(403, new ObjectMapper().readTree(response.getContentAsString()).get("code").asInt());
    }

    @Test
    void databaseOutageDuringAuthorizationReturns503WithReason() throws Exception {
        // 数据库断开时权限查询会抛连接异常。这里不能回 500，也不能当成令牌失效回 401，
        // 否则用户会以为登录过期而去重新登录，实际重登也失败。要回 503 并说明原因。
        MockHttpServletRequest request = authenticatedRequest("GET", "/api/sys/user/page");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(permissionService.getContext(7L)).thenThrow(
                new org.mybatis.spring.MyBatisSystemException(
                        new org.springframework.jdbc.CannotGetJdbcConnectionException(
                                "Failed to obtain JDBC Connection",
                                new java.sql.SQLTransientConnectionException(
                                        "EmsHikariPool - Connection is not available"))));

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(503, response.getStatus());
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals(500, body.get("code").asInt());
        assertEquals(true, body.get("data").get("message").asText().contains("数据库暂时连不上"));
    }

    @Test
    void letsAuthorizedRequestReachTheChain() throws Exception {
        MockHttpServletRequest request = authenticatedRequest("GET", "/api/sys/user/page");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(permissionService.getContext(7L)).thenReturn(context(Set.of("sys:user:view")));

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(7L, chain.getRequest().getAttribute("userId"));
    }

    @Test
    void letsSuperAdminReachNewPermissionWithoutExplicitRelation() throws Exception {
        MockHttpServletRequest request = authenticatedRequest("PUT", "/api/mfg/schedule-scenario/5/confirm");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        AuthorizationContext context = new AuthorizationContext(
                7L, 21L, 3L, "ALL", Set.of("SUPER_ADMIN"), Set.of(), Set.of());
        when(permissionService.getContext(7L)).thenReturn(context);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    private MockHttpServletRequest authenticatedRequest(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer token");
        when(jwtUtil.extractTokenFromHeader("Bearer token")).thenReturn("token");
        when(jwtUtil.validateToken("token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("token")).thenReturn(7L);
        when(tokenRevocationService.isRevoked("token")).thenReturn(false);
        return request;
    }

    private AuthorizationContext context(Set<String> permissions) {
        return new AuthorizationContext(7L, 21L, 3L, "ALL", Set.of("ROLE"), permissions, Set.of());
    }
}
