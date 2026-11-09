package com.re_form_shop_2605.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 테스트가 main 설정 위에 test 프로필을 덮어써 실행되는지 검증
 *  - 예전에는 테스트 리소스의 application.properties가 main 파일을 가려 운영 설정이 테스트에서 빠졌다
 * ─────────────────────────────────────────────────────
 */
@SpringBootTest
class TestProfileTest {

    @Autowired
    private Environment environment;

    @Test
    void main_설정은_그대로_쓰고_test_프로필_값으로_덮어씀() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        // main에만 있는 값
        assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("2s");
        assertThat(environment.getProperty("app.ai.search.similarity-threshold")).isEqualTo("0.30");
        // test 프로필이 덮어쓴 값
        assertThat(environment.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
        assertThat(environment.getProperty("app.ai.vector.enabled")).isEqualTo("false");
    }
}
