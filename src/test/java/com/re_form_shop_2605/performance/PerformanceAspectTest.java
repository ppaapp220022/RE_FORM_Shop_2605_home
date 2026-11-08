package com.re_form_shop_2605.performance;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerformanceAspectTest {

    private ProceedingJoinPoint joinPoint;
    private PerformanceAspect aspect;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ListOperations<String, Object> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPush(anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        aspect = new PerformanceAspect(redisTemplate);

        joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringTypeName()).thenReturn("SlowService");
        when(signature.getName()).thenReturn("work");
    }

    @Test
    void returnsResultWhenRedisFailsOnSlowCall() throws Throwable {
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            Thread.sleep(150);
            return "result";
        });

        assertEquals("result", aspect.checkPerformance(joinPoint));
    }

    @Test
    void keepsOriginalExceptionWhenRedisFailsOnSlowCall() throws Throwable {
        IllegalStateException original = new IllegalStateException("business failure");
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            Thread.sleep(150);
            throw original;
        });

        assertSame(original, assertThrows(IllegalStateException.class, () -> aspect.checkPerformance(joinPoint)));
    }
}
