package com.re_form_shop_2605.config;

import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Log4j2
@SpringBootTest
class RedisConfigTest {
    @Autowired
    private RedisTemplate<String,String> redisTemplate;

    @Test
    void getRedisTemplate() {
        // 실제 Redis에 쓰고 읽는 연동 테스트 - 로컬에 Redis가 없으면 실패 대신 건너뛴다
        assumeTrue(redisAvailable(), "Redis(localhost:6379)에 연결할 수 없어 건너뜁니다.");
        ValueOperations<String,String> valueOperations = redisTemplate.opsForValue();

        String key = "name";
        valueOperations.set(key,"test");
        String value = valueOperations.get(key);

        assertEquals("test", value);
    }

    private boolean redisAvailable() {
        try {
            redisTemplate.hasKey("ping");
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

}
