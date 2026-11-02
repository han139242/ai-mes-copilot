package com.neuedu.ems.util;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.RedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RehearsalIsolationTest {
    @Test void differentEnvironmentSigningKeysRejectEachOthersTokens() {
        JwtUtil legacy=new JwtUtil();JwtUtil lamp=new JwtUtil();
        ReflectionTestUtils.setField(lamp,"configuredSecret","lamp-rehearsal-test-secret-with-more-than-thirty-two-characters");
        assertFalse(lamp.validateToken(legacy.generateToken(1L,"admin")));
        assertFalse(legacy.validateToken(lamp.generateToken(1L,"admin")));
        assertTrue(lamp.validateToken(lamp.generateToken(1L,"admin")));
    }
    @Test void redisKeysAreScopedWithoutChangingLegacyDefault() {
        RedisUtil util=new RedisUtil();RedisTemplate<String,Object> template=mock(RedisTemplate.class);
        ReflectionTestUtils.setField(util,"redisTemplate",template);
        util.exists("user:1");verify(template).hasKey("user:1");
        ReflectionTestUtils.setField(util,"namespace","lamp:rehearsal");
        util.exists("user:1");verify(template).hasKey("lamp:rehearsal:user:1");
    }
}
