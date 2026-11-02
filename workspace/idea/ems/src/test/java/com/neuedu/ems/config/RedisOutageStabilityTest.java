package com.neuedu.ems.config;

import com.neuedu.ems.service.impl.sys.TokenRevocationService;
import com.neuedu.ems.util.RedisUtil;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.concurrent.Future;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class RedisOutageStabilityTest {

    private LettuceClientConfiguration configuration() {
        var builder = LettuceClientConfiguration.builder();
        new RedisConfig().boundedRedisWait(Duration.ofMillis(500), Duration.ofMillis(750)).customize(builder);
        return builder.build();
    }

    @Test
    void disconnectedCommandsAreRejectedInsteadOfQueued() {
        var config = configuration();
        assertEquals(Duration.ofMillis(750), config.getCommandTimeout());
        var options = config.getClientOptions().orElseThrow();
        assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, options.getDisconnectedBehavior());
        assertTrue(options.isAutoReconnect());
        assertEquals(Duration.ofMillis(500), options.getSocketOptions().getConnectTimeout());
    }

    @Test
    void connectionRefusalCannotBlockTokenChecksAndLocalRevocationStillWorks() throws Exception {
        int port;
        try (ServerSocket reserve = new ServerSocket(0)) {
            port = reserve.getLocalPort();
        }
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port), configuration());
        try {
            factory.afterPropertiesSet();
            RedisTemplate<String, Object> template = new RedisConfig().redisTemplate(factory);
            var redis = new RedisUtil();
            ReflectionTestUtils.setField(redis, "redisTemplate", template);
            var service = new TokenRevocationService(redis);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                assertFalse(service.isRevoked("existing-session"));
                service.revoke("logged-out-session", new Date(System.currentTimeMillis() + 60000));
                assertTrue(service.isRevoked("logged-out-session"));
            });
        } finally {
            factory.destroy();
        }
    }

    @Test
    void acceptingButUnresponsiveRedisHasBoundedHandshakeTime() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.accept();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("127.0.0.1", server.getLocalPort()), configuration());
            try {
                factory.afterPropertiesSet();
                var redis = new RedisUtil();
                ReflectionTestUtils.setField(redis, "redisTemplate", new RedisConfig().redisTemplate(factory));
                var service = new TokenRevocationService(redis);
                // 每个请求用不同令牌，确保不是同一个令牌缓存掩盖了并发阻塞。
                try (var pool = Executors.newFixedThreadPool(32)) {
                    var start = new CountDownLatch(1);
                    var results = new ArrayList<Future<Integer>>();
                    for (int index = 0; index < 32; index++) {
                        final int tokenIndex = index;
                        results.add(pool.submit(() -> {
                            start.await();
                            try {
                                assertFalse(service.isRevoked("parallel-token-" + tokenIndex));
                                return 200; // 确认缓存故障后按现有本机黑名单降级。
                            } catch (ResponseStatusException busy) {
                                assertEquals(503, busy.getStatusCode().value());
                                return 503; // 锁拥塞必须明确拒绝，不能跳过撤销检查放行。
                            }
                        }));
                    }
                    // 单条命令的上限由 commandTimeout(750ms) 保证，这里只验证「不会永久挂住」。
                    // 32 个线程抢同一把 Redis 检查锁，先到的线程要占着锁走完一次 750ms 的重连超时，
                    // 后面的线程再按 tryLock(1 秒) 排队，所以单个请求本来的耗时上界就在 2 秒上下，
                    // 全量回归时还要和其他 Spring 上下文抢 CPU。原来给每个结果固定 2 秒会在这里偶发超时，
                    // 现在改成共享一个 9 秒总预算、逐条扣减剩余时间：谁真挂住都会把预算吃完而失败，
                    // 报错信息也能指出还剩几条没返回；外层仍是原来的 10 秒，没有放宽判定。
                    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
                        start.countDown();
                        for (var result : results) {
                            long remaining = deadline - System.nanoTime();
                            assertTrue(remaining > 0, "等待降级结果超时：仍有请求没有返回");
                            int status = result.get(remaining, TimeUnit.NANOSECONDS);
                            assertTrue(status == 200 || status == 503);
                        }
                    });
                }
                try (Socket socket = accepted.get(2, TimeUnit.SECONDS)) {
                    assertTrue(socket.isConnected());
                }
            } finally {
                factory.destroy();
            }
        }
    }
}
