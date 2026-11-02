package com.neuedu.ems.controller.ai;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.exception.JwtException;
import com.neuedu.ems.service.ai.AlertStreamService;
import com.neuedu.ems.service.sys.PermissionService;
import com.neuedu.ems.util.JwtUtil;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 预警实时推送端点。
 *
 * <p>浏览器原生的 {@code EventSource} 不能自定义请求头，所以这里用 {@code ?token=} 传令牌，
 * 并在 Controller 内部自行校验（该路径在 JWT 过滤器里放行，鉴权由本类完成），
 * 避免"为了实时推送把整条 /api 路径都放开"的安全问题。</p>
 */
@RestController
@RequestMapping("/api/ai/alert")
public class AiAlertStreamController {

    private final AlertStreamService streamService;
    private final JwtUtil jwtUtil;
    private final PermissionService permissionService;

    public AiAlertStreamController(AlertStreamService streamService,
                                   JwtUtil jwtUtil,
                                   PermissionService permissionService) {
        this.streamService = streamService;
        this.jwtUtil = jwtUtil;
        this.permissionService = permissionService;
    }

    /** 建立预警推送长连接。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("token") String token) {
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
            throw new JwtException("预警通道令牌无效或已过期");
        }
        Long userId;
        try {
            userId = jwtUtil.getUserIdFromToken(token);
        } catch (Exception ex) {
            throw new JwtException("预警通道令牌无法解析");
        }
        AuthorizationContext context = permissionService.getContext(userId);
        if (context == null || !context.hasPermission("ai:assistant:use")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return streamService.subscribe(userId);
    }

    // SSE 请求不应把鉴权失败写成事件流或交给 JSON 兜底，从而引发二次序列化异常。
    @ExceptionHandler(JwtException.class)
    public ResponseEntity<Void> unauthorizedStream() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Void> rejectedStream(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).build();
    }
}
