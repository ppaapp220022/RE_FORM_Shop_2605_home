package com.re_form_shop_2605.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 숫자 사이즈(국내 표기)로 저장된 판매글을 영문 사이즈로 바꾼다
 *  - 등록·수정·검색 필터 화면은 XS·S·M·L·XL·XXL만 쓰는데, 더미 판매글은 90~115로 저장돼 사이즈 필터에 걸리지 않았다
 *  - 85=XS, 90=S, 95=M, 100=L, 105=XL, 110=XXL, 화면에 더 큰 사이즈가 없어 115·120도 XXL
 *  - 숫자 사이즈만 바꾸므로 여러 번 실행해도 결과가 같다 (기동마다 실행, 바꿀 글이 없으면 0건)
 *  - 사이즈는 임베딩 텍스트에 들어가지 않아 재임베딩은 필요 없다
 * ─────────────────────────────────────────────────────
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class PostSizeLabelMigration implements ApplicationRunner {

    static final String SQL = """
            UPDATE post
            SET size = CASE size
                WHEN '85' THEN 'XS'
                WHEN '90' THEN 'S'
                WHEN '95' THEN 'M'
                WHEN '100' THEN 'L'
                WHEN '105' THEN 'XL'
                ELSE 'XXL'
            END
            WHERE size IN ('85', '90', '95', '100', '105', '110', '115', '120')
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int updated = jdbcTemplate.update(SQL);
            if (updated > 0) {
                log.info("[PostSizeLabelMigration] 숫자 사이즈를 영문 사이즈로 변경 {}건", updated);
            }
        } catch (DataAccessException e) {
            // 실패해도 기동은 막지 않는다 - 다음 기동 때 다시 시도
            log.warn("[PostSizeLabelMigration] 사이즈 변경 실패", e);
        }
    }
}
