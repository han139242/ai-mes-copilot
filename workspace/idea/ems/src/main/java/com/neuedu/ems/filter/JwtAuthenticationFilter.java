package com.neuedu.ems.filter;


import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.exception.JwtException;
import com.neuedu.ems.common.ResultData;
import com.neuedu.ems.util.JwtConstants;
import com.neuedu.ems.util.JwtUtil;
import com.neuedu.ems.service.impl.sys.TokenRevocationService;
import com.neuedu.ems.security.PermissionCodeResolver;
import com.neuedu.ems.service.sys.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;

/**
 * JWT过滤器：拦截请求，验证token有效性
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private ObjectMapper objectMapper; // Spring默认注入的JSON工具
    @Autowired
    private TokenRevocationService tokenRevocationService;
    @Autowired
    private PermissionService permissionService;
    @Autowired
    private PermissionCodeResolver permissionCodeResolver;


    // 放行的路径（无需鉴权的接口，如登录、注册、静态资源）
    private static final String[] EXCLUDE_PATHS = {
            "/api/auth/login",
            "/register",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/error",
            "/swagger-ui/",
            "/swagger-ui.html",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/webjars/swagger-ui/**",
            "/upload/**",
            "/captcha/**",
            "/temp/**",
            "/favicon.ico",
            // 浏览器 EventSource 不能设置请求头，预警推送用 ?token= 传令牌，
            // 令牌校验与权限判断在 AiAlertStreamController 内部完成。
            "/api/ai/alert/stream"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 判断哪些访问路径不需要验证token令牌，直接放行
        try {
            if (!authenticate(request, response)) {
                return;
            }
            filterChain.doFilter(request, response);
        }catch (JwtException e) {
            log.debug("JWT request rejected: method={}, uri={}, reason={}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
            // 当捕获到异常信息之后，说明，token是无效的，应该给前端返回错误信息
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.TOKEN_ERROR);
        }catch (ResponseStatusException e) {
            // 认证依赖拥塞属于临时不可用，不能当作令牌失效，也不能放行业务。
            if (e.getStatusCode().value() != HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
                throw e;
            }
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ResultCode.FAILED);
        }catch (Exception e) {
            // 鉴权要查权限，查权限要连库。数据库断开时这里抛的是连接异常，
            // 按 500 抛出去前端只能看到「服务器错误」，用户会以为是自己没登录或系统坏了。
            // 明确回 503 并说明数据库连不上，前端据此提示「等数据库恢复后重试」。
            if (isConnectionUnavailable(e)) {
                log.error("权限校验需要数据库，但数据库连不上：method={}, uri={}",
                        request.getMethod(), request.getRequestURI());
                writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ResultCode.FAILED,
                        "数据库暂时连不上，请确认数据库已启动后重试；本次请求没有执行");
                return;
            }
            log.error("JWT filter failed: method={}, uri={}", request.getMethod(), request.getRequestURI(), e);
            throw new ServletException("JWT filter processing failed", e);
        }
    }

    /**
     * 连接层失败的异常类型清单：拒绝连接、连接超时、链路中断、事务开不起来。
     *
     * <p>抽成集合而不是一长串 {@code instanceof} 或，既好读，加类型也只改一处。</p>
     */
    private static final java.util.List<Class<? extends Throwable>> CONNECTION_FAILURE_TYPES = java.util.List.of(
            org.springframework.jdbc.CannotGetJdbcConnectionException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            java.sql.SQLTransientConnectionException.class,
            java.sql.SQLNonTransientConnectionException.class,
            java.net.ConnectException.class,
            java.net.SocketTimeoutException.class,
            java.net.UnknownHostException.class);

    /** 沿 cause 链判断是不是连接层的失败（拒绝连接、连接超时、链路中断）。 */
    private boolean isConnectionUnavailable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (isConnectionFailureType(cause)) {
                return true;
            }
        }
        return false;
    }

    /** 单个异常是否属于连接层失败的任一类型。 */
    private static boolean isConnectionFailureType(Throwable cause) {
        for (Class<? extends Throwable> type : CONNECTION_FAILURE_TYPES) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 完成放行判定、令牌校验与权限校验。
     *
     * @return true 表示请求可以继续走过滤链；false 表示响应已写好，直接结束
     */
    private boolean authenticate(HttpServletRequest request, HttpServletResponse response) throws Exception {
        // 1. 放行OPTIONS预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return true;
        }
        if (isExcludePath(request.getRequestURI())) {
            return true;
        }
        // 获取没有前缀的token
        String token = jwtUtil.extractTokenFromHeader(request.getHeader(JwtConstants.JWT_HEADER));
        requireUsableToken(token);
        // 先把UserId存储到request中
        Long userId = jwtUtil.getUserIdFromToken(token);
        request.setAttribute("userId", userId);
        return authorize(request, response, userId);
    }

    /** 令牌必须存在、未过期且未被注销，否则直接抛出 JWT 异常。 */
    private void requireUsableToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new JwtException("没有登录，请先登录");
        }
        if (!jwtUtil.validateToken(token)) {
            throw new JwtException("token无效或者过期");
        }
        if (tokenRevocationService.isRevoked(token)) {
            throw new JwtException("token已注销");
        }
    }

    /** 需要鉴权的 /api/ 接口逐条校验权限码，不通过时直接写出 403。 */
    private boolean authorize(HttpServletRequest request, HttpServletResponse response, Long userId) throws IOException {
        if (!needsPermissionCheck(request.getRequestURI())) {
            return true;
        }
        Optional<String> permissionCode = permissionCodeResolver.resolve(request);
        AuthorizationContext context = permissionService.getContext(userId);
        if (context == null) {
            throw new JwtException("用户不存在、已停用或未配置有效身份");
        }
        request.setAttribute("authorizationContext", context);
        if (permissionCode.isPresent() && context.hasPermission(permissionCode.get())) {
            return true;
        }
        log.info("Permission denied: userId={}, method={}, uri={}, required={}",
                userId, request.getMethod(), request.getRequestURI(), permissionCode.orElse("UNMAPPED"));
        writeError(response, HttpServletResponse.SC_FORBIDDEN, ResultCode.FORBIDDEN);
        return false;
    }

    /** 只对 /api/ 接口做权限码校验，且跳过免鉴权接口。 */
    private boolean needsPermissionCheck(String uri) {
        return uri.startsWith("/api/") && !permissionCodeResolver.isPermissionExempt(uri);
    }
    /**
     * 核心过滤逻辑
     */


    /**
     * 判断当前请求路径是否需要放行
     * /user
     */
    private boolean isExcludePath(String requestUri) {
        for (String excludePath : EXCLUDE_PATHS) {
            // 支持通配符（如/swagger-ui/**）
            String path = excludePath.replace("/**", ".*");
            if (requestUri.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private void writeError(HttpServletResponse response, int status, ResultCode resultCode) throws IOException {
        writeError(response, status, resultCode, null);
    }

    /** 带业务提示的写响应：message 为空时与原来一致，只给结果码。 */
    private void writeError(HttpServletResponse response, int status, ResultCode resultCode, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ResultData body = message == null ? ResultData.fail(resultCode)
                : ResultData.fail(resultCode, java.util.Map.of("message", message));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
