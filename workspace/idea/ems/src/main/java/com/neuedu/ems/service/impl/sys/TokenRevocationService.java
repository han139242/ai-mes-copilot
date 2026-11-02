package com.neuedu.ems.service.impl.sys;

import com.neuedu.ems.util.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 注销令牌黑名单。Redis 不可用时使用当前进程内存作为降级方案。
 */
@Service
@Slf4j
public class TokenRevocationService {

    private static final String KEY_PREFIX = "revoked_token_";

    private final RedisUtil redisUtil;
    private final Map<String, Long> localRevokedTokens = new ConcurrentHashMap<>();
    private final ReentrantLock redisCheckLock = new ReentrantLock();
    private volatile long redisRetryAfterNanos;

    public TokenRevocationService(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    public void revoke(String token, Date expiration) {
        if (token == null || expiration == null) {
            return;
        }
        String fingerprint = fingerprint(token);
        long expirationMillis = expiration.getTime();
        localRevokedTokens.put(fingerprint, expirationMillis);
        long ttlSeconds = Math.max(1, expiration.toInstant().getEpochSecond() - Instant.now().getEpochSecond());
        try {
            redisUtil.set(KEY_PREFIX + fingerprint, Boolean.TRUE, ttlSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while revoking token; using local blacklist: {}", e.getMessage());
        }
    }

    public boolean isRevoked(String token) {
        if (token == null) {
            return false;
        }
        String fingerprint = fingerprint(token);
        if (locallyRevoked(fingerprint)) {
            return true;
        }
        return revokedInRedis(fingerprint);
    }

    /** 本地黑名单命中且未过期即视为已撤销；已过期则顺手清理，避免无限增长。 */
    private boolean locallyRevoked(String fingerprint) {
        Long expirationMillis = localRevokedTokens.get(fingerprint);
        if (expirationMillis == null) {
            return false;
        }
        if (expirationMillis > System.currentTimeMillis()) {
            return true;
        }
        localRevokedTokens.remove(fingerprint);
        return false;
    }

    /** Redis 不可用时退化为「未撤销」，由本地黑名单兜底。 */
    private boolean revokedInRedis(String fingerprint) {
        // 一次失败后短暂冷却，避免并发鉴权在 Lettuce 共享连接锁前逐个重连。
        // 本机已注销令牌在进入此方法前已拦截；JWT 和权限校验仍照常执行。
        if (cooldownActive()) {
            return false;
        }
        boolean locked = false;
        try {
            locked = redisCheckLock.tryLock(1, TimeUnit.SECONDS);
            if (!locked) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "认证服务繁忙，请稍后重试");
            }
            return existsUnderLock(fingerprint);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            return markRedisUnavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "认证检查已中断，请稍后重试", e);
        } finally {
            if (locked) {
                redisCheckLock.unlock();
            }
        }
    }

    /** 是否仍处在上一轮 Redis 故障后的冷却窗口内。 */
    private boolean cooldownActive() {
        return System.nanoTime() < redisRetryAfterNanos;
    }

    /** 拿到锁后再查一次冷却与 Redis；等锁期间可能已被其它线程标记为不可用。 */
    private boolean existsUnderLock(String fingerprint) {
        if (cooldownActive()) {
            return false;
        }
        return redisUtil.exists(KEY_PREFIX + fingerprint);
    }

    /** Redis 抛错时进入 5 秒冷却并按「未撤销」处理。 */
    private boolean markRedisUnavailable(RuntimeException e) {
        redisRetryAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        log.debug("Redis unavailable while checking revoked token: {}", e.getMessage());
        return false;
    }

    private String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
