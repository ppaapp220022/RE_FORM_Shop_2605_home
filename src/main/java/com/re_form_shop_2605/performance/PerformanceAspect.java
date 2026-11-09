package com.re_form_shop_2605.performance;

import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-14
 * 설명: 성능 모니터링을 위한 Aspect 클래스.
 * 서비스 레이어의 메서드 실행 시간을 측정하여 로그로 기록하고, 일정 시간 이상 걸리는 경우 Redis에 저장.
 * ─────────────────────────────────────────────────────
 */
@Log4j2
@Component
@Aspect
public class PerformanceAspect {
    // 이보다 오래 걸린 호출만 INFO로 남기고 Redis에 저장한다 (나머지는 DEBUG - 모든 서비스 호출마다 INFO를 남겨 로그가 쌓였다)
    static final long SLOW_THRESHOLD_MS = 100;
    // Redis 저장이 실패하면 이 시간 동안은 시도하지 않는다
    //  - 장애 중에는 저장마다 연결 타임아웃(2초)을 기다리고 WARN을 남겼다
    //  - 장애 시작에 WARN 한 번, 복구되면 건너뛴 건수와 함께 INFO 한 번만 남긴다
    static final long REDIS_RETRY_INTERVAL_MS = 30_000;
    // 저장 대기가 이만큼 쌓이면(Redis가 느릴 때) 새 기록은 버린다 - 측정 기록은 부가 기능이라 요청을 붙잡지 않는다
    static final int SAVE_QUEUE_SIZE = 1000;

    private final RedisTemplate<String, Object> redisTemplate;
    private final String logKey = "reform:performance:logs";
    private final LongSupplier clock;
    // Redis 저장은 요청 스레드가 아니라 이 실행기에서 한다 (Redis가 느려도 응답이 느려지지 않게)
    private final Executor saveExecutor;
    // 아래 두 값은 saveExecutor(단일 스레드)에서만 읽고 쓴다
    // 0이면 정상, 아니면 이 시각(ms)까지 Redis 저장을 건너뛴다
    private long redisRetryAt;
    private int skippedRecords;

    @Autowired
    public PerformanceAspect(RedisTemplate<String, Object> redisTemplate) {
        this(redisTemplate, System::currentTimeMillis, new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(SAVE_QUEUE_SIZE), runnable -> {
            Thread thread = new Thread(runnable, "performance-log");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardPolicy()));
    }

    PerformanceAspect(RedisTemplate<String, Object> redisTemplate, LongSupplier clock, Executor saveExecutor) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.saveExecutor = saveExecutor;
    }

    @PreDestroy
    void shutdown() {
        if (saveExecutor instanceof ExecutorService executorService) {
            executorService.shutdown();
        }
    }

    @Around("execution(* com.re_form_shop_2605.service..*.*(..))")
    // @Around는 메서드 실행 전과 후에 모두 관여할 수 있는 어드바이스. 메서드 실행 시간을 측정하기 위해 사용.
    // 매서드는 반환형이 Object여야 하며,ProceedingJoinPoint를 매개변수로 받아야 함.
    // ProceedingJoinPoint는 대상 메서드의 실행을 제어할 수 있는 객체
    public Object checkPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = clock.getAsLong();
        try {
            // joinPoint.proceed()는 대상 메서드를 실행하는 역할. 이 메서드가 호출되어야 실제 비즈니스 로직이 실행됨.
            Object result = joinPoint.proceed();  return result;
        } catch (Throwable ex) {
            log.info("예외 발생: " + ex.getMessage());
            throw ex;
        } finally {
            long executionTime = clock.getAsLong() - start;
            if (executionTime <= SLOW_THRESHOLD_MS) {
                log.debug("{} 실행 시간: {}ms", joinPoint.getSignature(), executionTime);
            } else {
                log.info("{} 실행 시간: {}ms", joinPoint.getSignature(), executionTime);
                PerformanceLogDTO performanceLogDTO = PerformanceLogDTO.builder()
                        .className(joinPoint.getSignature().getDeclaringTypeName())
                        .methodName(joinPoint.getSignature().getName())
                        .executionTime(executionTime)
                        .createdAt(clock.getAsLong())
                        .build();
                saveExecutor.execute(() -> saveToRedis(performanceLogDTO));
            }
        }
    }

    /* 성능 기록을 Redis에 저장 - Redis가 끊겨 있으면 잠시 건너뛴다 (saveExecutor에서 실행) */
    // 측정 기록은 부가 기능이라 Redis 오류는 로그만 남기고 삼킨다
    private void saveToRedis(PerformanceLogDTO performanceLogDTO) {
        if (redisRetryAt != 0 && clock.getAsLong() < redisRetryAt) {
            skippedRecords++;
            return;
        }
        try {
            redisTemplate.opsForList().rightPush(logKey, performanceLogDTO);
            log.debug("Redis 저장 key={}, class={}, method={}, time={}ms",
                    logKey, performanceLogDTO.getClassName(), performanceLogDTO.getMethodName(), performanceLogDTO.getExecutionTime());
            if (redisRetryAt != 0) {
                log.info("Redis 성능 기록 저장이 복구됐습니다. 장애 중 건너뛴 기록 {}건", skippedRecords);
                redisRetryAt = 0;
                skippedRecords = 0;
            }
        } catch (RuntimeException redisException) {
            // 장애가 시작될 때만 WARN을 남기고, 이어지는 재시도 실패는 DEBUG로 남긴다
            if (redisRetryAt == 0) {
                log.warn("성능 기록을 Redis에 저장하지 못해 {}초 동안 저장을 건너뜁니다: {}",
                        REDIS_RETRY_INTERVAL_MS / 1000, redisException.getMessage());
            } else {
                log.debug("Redis 성능 기록 저장 재시도 실패: {}", redisException.getMessage());
            }
            redisRetryAt = clock.getAsLong() + REDIS_RETRY_INTERVAL_MS;
            skippedRecords++;
        }
    }
}
