package com.re_form_shop_2605.performance;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceAspectTest {

    private ProceedingJoinPoint joinPoint;
    private PerformanceAspect aspect;
    private ListOperations<String, Object> listOperations;
    private final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPush(anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        aspect = new PerformanceAspect(redisTemplate, now::get);

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

    @Test
    void Redis가_끊기면_잠시_저장을_건너뛰고_지나면_다시_시도() throws Throwable {
        // 장애 중에는 느린 호출마다 연결 타임아웃을 기다리고 WARN을 남겼다
        slowCall();

        slowCall();
        slowCall();
        verify(listOperations, times(1)).rightPush(anyString(), any());

        now.addAndGet(PerformanceAspect.REDIS_RETRY_INTERVAL_MS);
        slowCall();
        verify(listOperations, times(2)).rightPush(anyString(), any());
    }

    @Test
    void Redis가_복구되면_다음_호출부터_바로_저장() throws Throwable {
        doThrow(new RedisConnectionFailureException("down")).doReturn(1L)
                .when(listOperations).rightPush(anyString(), any());
        slowCall();
        now.addAndGet(PerformanceAspect.REDIS_RETRY_INTERVAL_MS);

        slowCall();
        slowCall();

        verify(listOperations, times(3)).rightPush(anyString(), any());
    }

    private void slowCall() throws Throwable {
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            Thread.sleep(110);
            return "result";
        });
        aspect.checkPerformance(joinPoint);
    }
}
