package com.neuedu.ems.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预警实时推送通道（SSE）。
 *
 * <p>为什么用 SSE 而不是轮询：预警的价值在于"到限度那一刻就弹出来"。轮询最短也要等一个间隔，
 * 而且每次都要查库；SSE 是一条长连接，服务端扫到新预警立刻推给浏览器，
 * 断线由浏览器自动重连，比 WebSocket 少一层握手与心跳维护，正好够用。</p>
 *
 * <p>连接按用户维度登记，一个账号开多个标签页也能都收到；推送失败就摘掉该连接，
 * 不阻塞扫描主流程。</p>
 */
@Service
public class AlertStreamService {

    private static final Logger log = LoggerFactory.getLogger(AlertStreamService.class);

    /** SSE 连接超时：30 分钟，到点由浏览器自动重连，避免代理层长连接被悄悄掐断。 */
    private static final long EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final Map<Long, Map<Long, SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 登记一条 SSE 连接。
     *
     * @param userId 当前登录用户
     * @return 已注册的 SseEmitter，调用方直接返回给 Spring MVC
     */
    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        long connectionId = sequence.incrementAndGet();
        emitters.computeIfAbsent(userId, key -> new ConcurrentHashMap<>()).put(connectionId, emitter);
        emitter.onCompletion(() -> remove(userId, connectionId));
        emitter.onTimeout(() -> {
            remove(userId, connectionId);
            emitter.complete();
        });
        emitter.onError(error -> remove(userId, connectionId));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "connectionId", connectionId,
                    "message", "预警通道已建立")));
        } catch (IOException ex) {
            remove(userId, connectionId);
        }
        return emitter;
    }

    /** 向所有在线连接广播一条预警事件。返回成功送达的连接数。 */
    public int broadcast(String eventName, Object payload) {
        int delivered = 0;
        for (Map.Entry<Long, Map<Long, SseEmitter>> entry : emitters.entrySet()) {
            for (Map.Entry<Long, SseEmitter> connection : entry.getValue().entrySet()) {
                try {
                    connection.getValue().send(SseEmitter.event().name(eventName).data(payload));
                    delivered++;
                } catch (IOException | IllegalStateException ex) {
                    remove(entry.getKey(), connection.getKey());
                }
            }
        }
        return delivered;
    }

    /** 当前在线连接数，用于健康检查。 */
    public int connectionCount() {
        return emitters.values().stream().mapToInt(Map::size).sum();
    }

    private void remove(Long userId, long connectionId) {
        Map<Long, SseEmitter> connections = emitters.get(userId);
        if (connections == null) {
            return;
        }
        connections.remove(connectionId);
        if (connections.isEmpty()) {
            emitters.remove(userId);
        }
    }

    /** 记录推送失败日志，供排查"为什么页面没弹"。 */
    public void logDelivery(int delivered, String alertNo) {
        if (delivered <= 0) {
            log.debug("预警 {} 已入库，但当前没有在线页面接收推送", alertNo);
        }
    }
}
