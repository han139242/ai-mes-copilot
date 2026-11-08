package com.neuedu.ems.service.impl.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Browser identity is replaced with the authenticated MES user before signing. */
@Service
public class PrivateAssistantClient {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Value("${mes.private-assistant.enabled:false}")
    private boolean enabled;
    @Value("${mes.private-assistant.url:http://127.0.0.1:8091}")
    private String url;
    @Value("${mes.private-assistant.secret:}")
    private String secret;

    public PrivateAssistantClient(ObjectMapper mapper) { this.mapper = mapper; }

    public Object call(Long uid, String action, Map<String, Object> payload) {
        if (uid == null || uid <= 0) throw new IllegalArgumentException("Invalid trusted user");
        return send(uid, action, payload);
    }

    public Object identify(String action, Map<String, Object> payload) {
        if (!java.util.Set.of("face.identify.challenge", "face.identify.frame", "face.identify.verify").contains(action)) {
            throw new IllegalArgumentException("Invalid identification action");
        }
        return send(0L, action, payload);
    }

    private Object send(Long uid, String action, Map<String, Object> payload) {
        if (!enabled || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "私有助手尚未启用，请先完成独立服务配置");
        }
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("uid", uid, "action", action, "payload", payload));
            String time = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/v1/dispatch"))
                    .timeout(Duration.ofSeconds(300)).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-MES-Time", time).header("X-MES-Nonce", nonce)
                    .header("X-MES-Signature", signature(secret, time, nonce, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Map<String, Object> result = mapper.readValue(response.body(), new TypeReference<>() {});
            if (response.statusCode() == 400) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        String.valueOf(result.getOrDefault("detail", "请求参数无效")));
            }
            if (response.statusCode() == 429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "检测服务忙，请稍后继续");
            if (response.statusCode() != 200 || !Boolean.TRUE.equals(result.get("ok"))) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "私有助手暂不可用，请检查独立服务状态");
            }
            return result.get("data");
        } catch (ResponseStatusException e) { throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "请求已中断");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法连接私有助手，请稍后重试");
        }
    }

    static String signature(String secret, String time, String nonce, byte[] body) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String canonical = "POST\n/v1/dispatch\n" + time + "\n" + nonce + "\n" + hash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}
