package com.re_form_shop_2605.performance;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
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
@RequiredArgsConstructor
public class PerformanceAspect {
    private final RedisTemplate<String, Object> redisTemplate;
    private final String logKey = "reform:performance:logs";

    @Around("execution(* com.re_form_shop_2605.service..*.*(..))")
    // @Around는 메서드 실행 전과 후에 모두 관여할 수 있는 어드바이스. 메서드 실행 시간을 측정하기 위해 사용.
    // 매서드는 반환형이 Object여야 하며,ProceedingJoinPoint를 매개변수로 받아야 함.
    // ProceedingJoinPoint는 대상 메서드의 실행을 제어할 수 있는 객체
    public Object checkPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            // joinPoint.proceed()는 대상 메서드를 실행하는 역할. 이 메서드가 호출되어야 실제 비즈니스 로직이 실행됨.
            Object result = joinPoint.proceed();  return result;
        } catch (Throwable ex) {
            log.info("예외 발생: " + ex.getMessage());
            throw ex;
        } finally {
            long end = System.currentTimeMillis();
            long executionTime = (end - start);
            String className = joinPoint.getSignature().getDeclaringTypeName();
            String methodName = joinPoint.getSignature().getName();
            log.info("{} 실행 시간: {}ms", joinPoint.getSignature(), executionTime);
            PerformanceLogDTO performanceLogDTO = PerformanceLogDTO.builder()
                    .className(className).methodName(methodName)
                    .executionTime(executionTime)
                    .createdAt(System.currentTimeMillis())
                    .build();

            if (executionTime > 100) { // 0ms 초과일때만 Redis에 저장
                // 측정 기록은 부가 기능이라 Redis가 끊겨도 원래 메서드의 결과·예외를 덮어쓰지 않게 여기서 삼킨다
                try {
                    redisTemplate.opsForList().rightPush(logKey, performanceLogDTO);
                    log.info("Redis 저장 시도 key={}, class={}, method={}, time={}ms",
                            logKey, className, methodName, executionTime);
                } catch (RuntimeException redisException) {
                    log.warn("성능 기록을 Redis에 저장하지 못했습니다. class={}, method={}: {}",
                            className, methodName, redisException.getMessage());
                }
            }
        }
    }
}

