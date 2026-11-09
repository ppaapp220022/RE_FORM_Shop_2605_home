package com.re_form_shop_2605.performance;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 성능 기록 Aspect - Redis 장애가 원래 메서드와 로그에 미치는 영향 검증
 *  - 시간은 가짜 시계로 흘리고, Redis 저장은 같은 스레드에서 바로 실행해 결과가 항상 같게 한다
 * ─────────────────────────────────────────────────────
 */
class PerformanceAspectTest {

    private ProceedingJoinPoint joinPoint;
    private PerformanceAspect aspect;
    private ListOperations<String, Object> listOperations;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Runnable> queued = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPush(anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        aspect = new PerformanceAspect(redisTemplate, now::get, Runnable::run);

        joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringTypeName()).thenReturn("SlowService");
        when(signature.getName()).thenReturn("work");

        logger = (Logger) LoggerFactory.getLogger(PerformanceAspect.class);
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    @Test
    void returnsResultWhenRedisFailsOnSlowCall() throws Throwable {
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            now.addAndGet(150);
            return "result";
        });

        assertEquals("result", aspect.checkPerformance(joinPoint));
    }

    @Test
    void keepsOriginalExceptionWhenRedisFailsOnSlowCall() throws Throwable {
        IllegalStateException original = new IllegalStateException("business failure");
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            now.addAndGet(150);
            throw original;
        });

        assertSame(original, assertThrows(IllegalStateException.class, () -> aspect.checkPerformance(joinPoint)));
    }

    @Test
    void Redis가_끊기면_잠시_저장을_건너뛰고_지나면_다시_시도() throws Throwable {
        // 장애 중에는 느린 호출마다 연결 타임아웃을 기다리고 WARN을 남겼다
        call(150);

        call(150);
        call(150);
        verify(listOperations, times(1)).rightPush(anyString(), any());

        now.addAndGet(PerformanceAspect.REDIS_RETRY_INTERVAL_MS);
        call(150);
        verify(listOperations, times(2)).rightPush(anyString(), any());
        // 장애가 이어져도 WARN은 처음 한 번만
        assertThat(messages(Level.WARN)).hasSize(1);
    }

    @Test
    void Redis가_복구되면_건너뛴_건수를_한_번_알리고_바로_저장() throws Throwable {
        doThrow(new RedisConnectionFailureException("down")).doReturn(1L)
                .when(listOperations).rightPush(anyString(), any());
        call(150);
        call(150);
        now.addAndGet(PerformanceAspect.REDIS_RETRY_INTERVAL_MS);

        call(150);
        call(150);

        verify(listOperations, times(3)).rightPush(anyString(), any());
        // 실패 1건 + 건너뛴 1건
        assertThat(messages(Level.INFO)).filteredOn(message -> message.contains("복구"))
                .containsExactly("Redis 성능 기록 저장이 복구됐습니다. 장애 중 건너뛴 기록 2건");
    }

    @Test
    void 빠른_호출은_INFO를_남기지_않고_Redis에도_저장하지_않음() throws Throwable {
        call(PerformanceAspect.SLOW_THRESHOLD_MS);

        verify(listOperations, never()).rightPush(anyString(), any());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void Redis_저장은_요청_스레드에서_하지_않음() throws Throwable {
        PerformanceAspect queuedAspect = new PerformanceAspect(mock(RedisTemplate.class), now::get, queued::add);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            now.addAndGet(150);
            return "result";
        });

        assertEquals("result", queuedAspect.checkPerformance(joinPoint));
        assertThat(queued).hasSize(1);
    }

    private void call(long elapsedMs) throws Throwable {
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            now.addAndGet(elapsedMs);
            return "result";
        });
        aspect.checkPerformance(joinPoint);
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }
}
