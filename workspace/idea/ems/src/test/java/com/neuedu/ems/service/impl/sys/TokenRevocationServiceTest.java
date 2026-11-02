package com.neuedu.ems.service.impl.sys;

import com.neuedu.ems.util.RedisUtil;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.CompletableFuture;

class TokenRevocationServiceTest {

    @Test
    void contentionDoesNotAcceptARemotelyRevokedToken() {
        var service = new TokenRevocationService(mock(RedisUtil.class));
        var lock = (ReentrantLock) ReflectionTestUtils.getField(service, "redisCheckLock");
        lock.lock();
        try {
            var result = CompletableFuture.supplyAsync(() -> {
                try {
                    service.isRevoked("remote-revoked-token");
                    return 0;
                } catch (ResponseStatusException error) {
                    return error.getStatusCode().value();
                }
            });
            assertEquals(503, result.orTimeout(3, TimeUnit.SECONDS).join());
        } finally {
            lock.unlock();
        }
    }

    @Test
    void outageCooldownAvoidsRepeatedConnectionsAndRechecksAfterRecovery() {
        RedisUtil redis = mock(RedisUtil.class);
        when(redis.exists(anyString())).thenThrow(new IllegalStateException("offline")).thenReturn(true);
        var service = new TokenRevocationService(redis);
        assertFalse(service.isRevoked("remote-token"));
        assertFalse(service.isRevoked("remote-token"));
        verify(redis, times(1)).exists(anyString());
        ReflectionTestUtils.setField(service, "redisRetryAfterNanos", 0L);
        assertTrue(service.isRevoked("remote-token"));
        verify(redis, times(2)).exists(anyString());
    }

    @Test
    void revokedTokenIsImmediatelyRejectedAndStoredWithTtl() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        TokenRevocationService service = new TokenRevocationService(redisUtil);
        Date expiration = new Date(System.currentTimeMillis() + 60_000);

        service.revoke("jwt-token", expiration);

        assertTrue(service.isRevoked("jwt-token"));
        verify(redisUtil).set(anyString(), eq(Boolean.TRUE), org.mockito.ArgumentMatchers.longThat(value -> value > 0), eq(TimeUnit.SECONDS));
    }
}
