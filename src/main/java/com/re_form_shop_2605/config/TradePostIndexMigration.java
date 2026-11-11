package com.re_form_shop_2605.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ─────────────────────────────────────────────────────
 * 설명: trade.post_id의 unique 인덱스를 일반 인덱스로 바꾼다
 *  - 예전에는 판매글당 거래가 하나뿐이라 post_id가 unique였다. 그래서 판매자가 거래를 취소하면
 *    그 판매글로는 다시 거래를 만들 수 없었다
 *  - 이제 취소된 거래는 이력으로 남기고 새 거래를 만든다(진행 중 거래 하나만 허용은 서비스에서 검사)
 *  - ddl-auto=update는 기존 인덱스를 지우지 않으므로 운영 DB(MariaDB)에서 직접 바꾼다
 *  - FK(fk_trade_post)에 인덱스가 필요하므로 일반 인덱스를 먼저 만든 뒤 unique를 지운다
 *  - 바꿀 인덱스가 없으면 아무것도 하지 않아 기동마다 실행해도 된다. MariaDB·MySQL이 아니면(테스트 H2) 건너뛴다
 * ─────────────────────────────────────────────────────
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class TradePostIndexMigration implements ApplicationRunner {

    static final String FIND_UNIQUE_POST_INDEXES = """
            SELECT INDEX_NAME
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'trade'
              AND NON_UNIQUE = 0
              AND INDEX_NAME <> 'PRIMARY'
            GROUP BY INDEX_NAME
            HAVING COUNT(*) = 1 AND MAX(COLUMN_NAME) = 'post_id'
            """;

    static final String COUNT_PLAIN_POST_INDEXES = """
            SELECT COUNT(DISTINCT INDEX_NAME)
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'trade'
              AND NON_UNIQUE = 1
              AND COLUMN_NAME = 'post_id'
              AND SEQ_IN_INDEX = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!isMariaDbOrMySql()) {
                return;
            }
            List<String> uniqueIndexes = jdbcTemplate.queryForList(FIND_UNIQUE_POST_INDEXES, String.class);
            if (uniqueIndexes.isEmpty()) {
                return;
            }

            Integer plainIndexes = jdbcTemplate.queryForObject(COUNT_PLAIN_POST_INDEXES, Integer.class);
            if (plainIndexes == null || plainIndexes == 0) {
                jdbcTemplate.execute("CREATE INDEX idx_trade_post_id ON trade (post_id)");
            }
            for (String indexName : uniqueIndexes) {
                jdbcTemplate.execute("ALTER TABLE trade DROP INDEX `" + indexName.replace("`", "") + "`");
                log.info("[TradePostIndexMigration] trade.post_id unique 인덱스 제거: {}", indexName);
            }
        } catch (DataAccessException e) {
            // 실패해도 기동은 막지 않는다 - 다음 기동 때 다시 시도
            log.warn("[TradePostIndexMigration] 인덱스 변경 실패", e);
        }
    }

    private boolean isMariaDbOrMySql() {
        String product = jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName());
        return product != null && (product.toLowerCase().contains("mariadb") || product.toLowerCase().contains("mysql"));
    }
}
