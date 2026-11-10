SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE risk_analysis_result;
TRUNCATE TABLE notification;
TRUNCATE TABLE report;
TRUNCATE TABLE reply_like;
TRUNCATE TABLE community_like;
TRUNCATE TABLE reply;
TRUNCATE TABLE community_post;
TRUNCATE TABLE point_request;
TRUNCATE TABLE point_history;
TRUNCATE TABLE point_wallet;
TRUNCATE TABLE toss_log;
TRUNCATE TABLE payment;
TRUNCATE TABLE chat_message;
TRUNCATE TABLE chat_room;
TRUNCATE TABLE manner_review;
TRUNCATE TABLE trade;
TRUNCATE TABLE wish;
TRUNCATE TABLE post_image;
TRUNCATE TABLE post;
TRUNCATE TABLE interest_keyword;
TRUNCATE TABLE interest_setting;
TRUNCATE TABLE social_member;
TRUNCATE TABLE member;

SET FOREIGN_KEY_CHECKS = 1;
SET max_recursive_iterations = 5000;

DROP TEMPORARY TABLE IF EXISTS tmp_seq_40;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_60;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_80;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_120;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_1000;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_1800;
DROP TEMPORARY TABLE IF EXISTS tmp_team_catalog;
DROP TEMPORARY TABLE IF EXISTS tmp_payment_schedule;

CREATE TEMPORARY TABLE tmp_seq_40 AS
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 40
)
SELECT n FROM seq;

CREATE TEMPORARY TABLE tmp_seq_60 AS
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 60
)
SELECT n FROM seq;

CREATE TEMPORARY TABLE tmp_seq_80 AS
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 80
)
SELECT n FROM seq;

CREATE TEMPORARY TABLE tmp_seq_120 AS
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 120
)
SELECT n FROM seq;

CREATE TEMPORARY TABLE tmp_seq_1000 AS
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT n FROM seq;

CREATE TEMPORARY TABLE tmp_seq_1800 AS
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1800
)
SELECT n FROM seq;

CREATE TEMPORARY TABLE tmp_team_catalog (
                                            team_idx INT NOT NULL PRIMARY KEY,
                                            sport VARCHAR(20) NOT NULL,
                                            team VARCHAR(100) NOT NULL,
                                            uniform_name VARCHAR(120) NOT NULL,
                                            image_url VARCHAR(500) NOT NULL,
                                            keyword1 VARCHAR(50) NOT NULL,
                                            keyword2 VARCHAR(50) NOT NULL
);

INSERT INTO tmp_team_catalog (team_idx, sport, team, uniform_name, image_url, keyword1, keyword2) VALUES
                                                                                                      (1, 'SOCCER', '토트넘 홋스퍼', '24/25 홈 유니폼', '/uploads/post/seed/tottenham-hotspur.svg', '손흥민', '홈킷'),
                                                                                                      (2, 'SOCCER', 'FC 서울', '2025 홈 유니폼', '/uploads/post/seed/fc-seoul.svg', '레트로', '서울더비'),
                                                                                                      (3, 'SOCCER', '전북 현대', '2025 홈 유니폼', '/uploads/post/seed/jeonbuk-hyundai.svg', 'K리그', '어센틱'),
                                                                                                      (4, 'SOCCER', '울산 HD', '2025 홈 유니폼', '/uploads/post/seed/ulsan-hd.svg', '우승', '오리지널'),
                                                                                                      (5, 'BASEBALL', 'LG 트윈스', '2024 홈 유니폼', '/uploads/post/seed/lg-twins.svg', '우승패치', '잠실'),
                                                                                                      (6, 'BASEBALL', 'KIA 타이거즈', '2024 홈 유니폼', '/uploads/post/seed/kia-tigers.svg', '호랑이군단', '챔필'),
                                                                                                      (7, 'BASEBALL', '삼성 라이온즈', '2024 홈 유니폼', '/uploads/post/seed/samsung-lions.svg', '대구', '마킹'),
                                                                                                      (8, 'BASEBALL', '한화 이글스', '2024 홈 유니폼', '/uploads/post/seed/hanwha-eagles.svg', '이글스파크', '레플리카'),
                                                                                                      (9, 'BASKETBALL', '서울 SK 나이츠', '24/25 챔피언 에디션', '/uploads/post/seed/seoul-sk-knights.svg', '챔피언', '점보패치'),
                                                                                                      (10, 'BASKETBALL', '부산 KCC 이지스', '24/25 홈 저지', '/uploads/post/seed/busan-kcc-egis.svg', '파이널', '오센틱'),
                                                                                                      (11, 'ESPORTS', 'T1', '2025 월즈 기념 저지', '/uploads/post/seed/t1-esports.svg', '월즈', '페이커'),
                                                                                                      (12, 'ESPORTS', '젠지', '2025 프로킷', '/uploads/post/seed/gen-g-esports.svg', 'LCK', '프로킷');

CREATE TEMPORARY TABLE tmp_payment_schedule AS
SELECT
    n,
    FLOOR((n - 1) / 10) AS day_offset,
    MOD(n - 1, 10) AS slot_in_day
FROM tmp_seq_1800;

INSERT INTO member (
    member_id, email, password, nickname, profile_image_url, bio,
    manner_score, role, status, warning_count, email_event, created_at
) VALUES
    (1, 'admin@reform.com', '$2a$10$r9J2LsXfNf6TaNCMjevt2OFdaHq1ruCt43Dg0WI3Cx945hLyaS4Om', '관리자계정', NULL, '대량 더미 데이터 검수용 관리자 계정', 5.00, 'ADMIN', 'ACTIVE', 0, TRUE, DATE_SUB(NOW(), INTERVAL 360 DAY));

INSERT INTO member (
    member_id, email, password, nickname, profile_image_url, bio,
    manner_score, role, status, warning_count, email_event, created_at
)
SELECT
    s.n + 1 AS member_id,
    CONCAT('user', LPAD(s.n, 3, '0'), '@reform.com') AS email,
    '$2a$10$r9J2LsXfNf6TaNCMjevt2OFdaHq1ruCt43Dg0WI3Cx945hLyaS4Om' AS password,
    CONCAT('리폼회원', LPAD(s.n, 3, '0')) AS nickname,
    NULL AS profile_image_url,
    CONCAT(tc.team, ' 중심으로 수집하는 더미 회원 ', LPAD(s.n, 3, '0')) AS bio,
    ROUND(3.30 + (MOD(s.n * 7, 17) * 0.10), 2) AS manner_score,
    'USER' AS role,
    CASE
        WHEN s.n IN (91, 102, 113) THEN 'SUSPENDED'
        WHEN s.n IN (119, 120) THEN 'WITHDRAWN'
        ELSE 'ACTIVE'
        END AS status,
    CASE
        WHEN s.n IN (91, 102, 113) THEN 2
        WHEN MOD(s.n, 17) = 0 THEN 1
        ELSE 0
        END AS warning_count,
    MOD(s.n, 2) = 0 AS email_event,
    DATE_SUB(NOW(), INTERVAL (10 + MOD(s.n * 5, 170)) DAY) AS created_at
FROM tmp_seq_120 s
         JOIN tmp_team_catalog tc
              ON tc.team_idx = 1 + MOD(s.n - 1, 12);

INSERT INTO social_member (social_id, member_id, provider, provider_id)
SELECT
    s.n AS social_id,
    s.n + 1 AS member_id,
    CASE WHEN MOD(s.n, 2) = 1 THEN 'KAKAO' ELSE 'GOOGLE' END AS provider,
    CONCAT(LOWER(CASE WHEN MOD(s.n, 2) = 1 THEN 'kakao' ELSE 'google' END), '-', LPAD(s.n + 1, 5, '0')) AS provider_id
FROM tmp_seq_40 s
WHERE s.n <= 16;

INSERT INTO interest_setting (member_id, sport, team)
SELECT
    m.member_id,
    tc.sport,
    tc.team
FROM member m
         JOIN tmp_team_catalog tc
              ON tc.team_idx = 1 + MOD(m.member_id - 2, 12)
WHERE m.member_id > 1
  AND m.status <> 'WITHDRAWN';

INSERT INTO interest_keyword (keyword_id, member_id, keyword)
SELECT
    ROW_NUMBER() OVER (ORDER BY member_id, keyword) AS keyword_id,
    member_id,
    keyword
FROM (
         SELECT
             m.member_id,
             tc.keyword1 AS keyword
         FROM member m
                  JOIN tmp_team_catalog tc
                       ON tc.team_idx = 1 + MOD(m.member_id - 2, 12)
         WHERE m.member_id > 1
           AND m.status <> 'WITHDRAWN'
         UNION ALL
         SELECT
             m.member_id,
             tc.keyword2 AS keyword
         FROM member m
                  JOIN tmp_team_catalog tc
                       ON tc.team_idx = 1 + MOD(m.member_id - 2, 12)
         WHERE m.member_id > 1
           AND m.status <> 'WITHDRAWN'
     ) keywords;

INSERT INTO point_wallet (wallet_id, member_id, balance, withdrawable, pending)
SELECT
    member_id AS wallet_id,
    member_id,
    0,
    0,
    0
FROM member;

INSERT INTO post (
    post_id, seller_id, title, content, sport, team, uniform_name, grade, size,
    marking, price, delivery_type, status, view_count, wish_count, risk_level, created_at, updated_at
)
SELECT
    s.n AS post_id,
    2 + MOD(s.n - 1, 80) AS seller_id,
    CONCAT(tc.team, ' ', tc.uniform_name, ' ', CASE MOD(s.n, 4) WHEN 0 THEN '실착용' WHEN 1 THEN '오센틱' WHEN 2 THEN '레플리카' ELSE '마킹 버전' END, ' 판매') AS title,
    CONCAT(
            '배치 테스트용 대량 더미 판매글입니다. ',
            tc.team, ' ', tc.uniform_name,
            ' 상태는 양호하며 사진은 팀 대표 유니폼 시드 이미지를 사용합니다. ',
            '사이즈 문의와 가격 제안이 자주 들어오는 설정입니다.'
    ) AS content,
    tc.sport,
    tc.team,
    tc.uniform_name,
    CASE MOD(s.n, 4) WHEN 0 THEN 'S' WHEN 1 THEN 'A' WHEN 2 THEN 'B' ELSE 'C' END AS grade,
    CASE MOD(s.n, 6) WHEN 0 THEN 'XS' WHEN 1 THEN 'S' WHEN 2 THEN 'M' WHEN 3 THEN 'L' WHEN 4 THEN 'XL' ELSE 'XXL' END AS size,
    MOD(s.n, 2) = 0 AS marking,
    59000 + (MOD(s.n * 137, 17) * 7000) AS price,
    CASE MOD(s.n, 3) WHEN 0 THEN 'DIRECT' WHEN 1 THEN 'DELIVERY' ELSE 'BOTH' END AS delivery_type,
    'ON_SALE' AS status,
    30 + MOD(s.n * 19, 420) AS view_count,
    0 AS wish_count,
    CASE
        WHEN MOD(s.n, 91) = 0 THEN 'MID'
        WHEN MOD(s.n, 57) = 0 THEN 'LOW'
        ELSE NULL
        END AS risk_level,
    DATE_SUB(NOW(), INTERVAL (15 + MOD(s.n * 7, 110)) DAY) AS created_at,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 5, 14) DAY) AS updated_at
FROM tmp_seq_1000 s
         JOIN tmp_team_catalog tc
              ON tc.team_idx = 1 + MOD(s.n - 1, 12);

INSERT INTO post (
    post_id, seller_id, title, content, sport, team, uniform_name, grade, size,
    marking, price, delivery_type, status, view_count, wish_count, risk_level, created_at, updated_at
)
SELECT
    1000 + ps.n AS post_id,
    2 + MOD(ps.n - 1, 50) AS seller_id,
    CONCAT(tc.team, ' ', tc.uniform_name, ' 거래 완료용 이력 샘플 #', LPAD(ps.n, 4, '0')) AS title,
    CONCAT(
            '결제/정산 배치 테스트를 위한 거래 완료형 판매글입니다. ',
            '최근 6개월 동안 하루 10건 이상 결제 데이터가 생성되도록 구성되었습니다.'
    ) AS content,
    tc.sport,
    tc.team,
    tc.uniform_name,
    CASE MOD(ps.n + 1, 4) WHEN 0 THEN 'S' WHEN 1 THEN 'A' WHEN 2 THEN 'B' ELSE 'C' END AS grade,
    CASE MOD(ps.n + 2, 6) WHEN 0 THEN 'XS' WHEN 1 THEN 'S' WHEN 2 THEN 'M' WHEN 3 THEN 'L' WHEN 4 THEN 'XL' ELSE 'XXL' END AS size,
    MOD(ps.n, 3) = 0 AS marking,
    69000 + (MOD(ps.n * 53, 19) * 8000) AS price,
    CASE WHEN MOD(ps.n, 4) = 0 THEN 'DIRECT' ELSE 'DELIVERY' END AS delivery_type,
    'SOLD' AS status,
    80 + MOD(ps.n * 13, 900) AS view_count,
    0 AS wish_count,
    NULL AS risk_level,
    DATE_SUB(
            TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL ps.day_offset DAY), MAKETIME(8 + MOD(ps.slot_in_day, 4), MOD(ps.slot_in_day * 6, 60), 0)),
            INTERVAL 3 DAY
    ) AS created_at,
    DATE_SUB(
            TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL ps.day_offset DAY), MAKETIME(9 + MOD(ps.slot_in_day, 4), MOD(ps.slot_in_day * 7, 60), 0)),
            INTERVAL 1 DAY
    ) AS updated_at
FROM tmp_payment_schedule ps
         JOIN tmp_team_catalog tc
              ON tc.team_idx = 1 + MOD(ps.n - 1, 12);

INSERT INTO post_image (image_id, post_id, image_url, sort_order)
SELECT
    ROW_NUMBER() OVER (ORDER BY p.post_id) AS image_id,
    p.post_id,
    tc.image_url,
    1 AS sort_order
FROM post p
         JOIN tmp_team_catalog tc
              ON p.team = tc.team;

INSERT INTO wish (wish_id, member_id, post_id, created_at)
SELECT
    s.n AS wish_id,
    82 + MOD(s.n - 1, 40) AS member_id,
    s.n AS post_id,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 2, 20) DAY) AS created_at
FROM tmp_seq_1000 s;

INSERT INTO wish (wish_id, member_id, post_id, created_at)
SELECT
    1000 + s.n AS wish_id,
    42 + MOD(s.n - 1, 40) AS member_id,
    s.n AS post_id,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 3, 25) DAY) AS created_at
FROM tmp_seq_1000 s
WHERE s.n <= 500;

UPDATE post p
SET p.wish_count = (
    SELECT COUNT(*)
    FROM wish w
    WHERE w.post_id = p.post_id
)
WHERE EXISTS (
    SELECT 1
    FROM wish w
    WHERE w.post_id = p.post_id
);

INSERT INTO trade (
    trade_id, post_id, buyer_id, seller_id, status, delivery_type, delivery_address,
    courier_code, courier_name, tracking_number, shipping_started_at, trade_price,
    completed_at, confirmed_at, created_at, received_at
)
SELECT
    ps.n AS trade_id,
    1000 + ps.n AS post_id,
    52 + MOD(ps.n - 1, 70) AS buyer_id,
    p.seller_id,
    CASE
        WHEN ps.day_offset >= 30 THEN 'COMPLETED'
        WHEN ps.day_offset >= 14 THEN 'CONFIRMED'
        WHEN ps.day_offset >= 7 THEN 'RECEIVED'
        ELSE 'PAID'
        END AS status,
    CASE WHEN MOD(ps.n, 4) = 0 THEN 'DIRECT' ELSE 'DELIVERY' END AS delivery_type,
    CASE
        WHEN MOD(ps.n, 4) = 0 THEN NULL
        ELSE CONCAT('서울시 테스트구 리폼로 ', 100 + MOD(ps.n, 400))
        END AS delivery_address,
    CASE
        WHEN MOD(ps.n, 4) = 0 OR ps.day_offset < 2 THEN NULL
        WHEN MOD(ps.n, 2) = 0 THEN 'kr.cjlogistics'
        ELSE 'kr.hanjin'
        END AS courier_code,
    CASE
        WHEN MOD(ps.n, 4) = 0 OR ps.day_offset < 2 THEN NULL
        WHEN MOD(ps.n, 2) = 0 THEN 'CJ대한통운'
        ELSE '한진택배'
        END AS courier_name,
    CASE
        WHEN MOD(ps.n, 4) = 0 OR ps.day_offset < 2 THEN NULL
        ELSE CONCAT('TRK', LPAD(ps.n, 7, '0'))
        END AS tracking_number,
    CASE
        WHEN MOD(ps.n, 4) = 0 OR ps.day_offset < 2 THEN NULL
        ELSE DATE_ADD(TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL ps.day_offset DAY), MAKETIME(9 + MOD(ps.slot_in_day, 5), MOD(ps.slot_in_day * 7, 60), 0)), INTERVAL 1 DAY)
        END AS shipping_started_at,
    p.price AS trade_price,
    CASE
        WHEN ps.day_offset >= 30 THEN DATE_ADD(TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL ps.day_offset DAY), MAKETIME(9 + MOD(ps.slot_in_day, 5), MOD(ps.slot_in_day * 7, 60), 0)), INTERVAL 6 DAY)
        ELSE NULL
        END AS completed_at,
    CASE
        WHEN ps.day_offset >= 14 THEN DATE_ADD(TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL ps.day_offset DAY), MAKETIME(9 + MOD(ps.slot_in_day, 5), MOD(ps.slot_in_day * 7, 60), 0)), INTERVAL 4 DAY)
        ELSE NULL
        END AS confirmed_at,
    TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL ps.day_offset DAY), MAKETIME(9 + MOD(ps.slot_in_day, 5), MOD(ps.slot_in_day * 7, 60), 0)) AS created_at,
    CASE
        WHEN ps.day_offset >= 7 THEN DATE_ADD(TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL ps.day_offset DAY), MAKETIME(9 + MOD(ps.slot_in_day, 5), MOD(ps.slot_in_day * 7, 60), 0)), INTERVAL 3 DAY)
        ELSE NULL
        END AS received_at
FROM tmp_payment_schedule ps
         JOIN post p
              ON p.post_id = 1000 + ps.n;

INSERT INTO payment (
    payment_id, trade_id, pay_method, approval_no, toss_order_id, toss_payment_key,
    amount, status, paid_at, created_at
)
SELECT
    tr.trade_id AS payment_id,
    tr.trade_id,
    CASE WHEN MOD(tr.trade_id, 3) = 0 THEN 'Pay' ELSE 'Card' END AS pay_method,
    CASE WHEN MOD(tr.trade_id, 3) = 0 THEN NULL ELSE CONCAT('AP-', DATE_FORMAT(tr.created_at, '%Y%m%d'), '-', LPAD(tr.trade_id, 4, '0')) END AS approval_no,
    CONCAT('ORDER-TRADE-', LPAD(tr.trade_id, 5, '0')) AS toss_order_id,
    CONCAT('pay_trade_', LPAD(tr.trade_id, 5, '0')) AS toss_payment_key,
    tr.trade_price AS amount,
    'PAID' AS status,
    DATE_ADD(tr.created_at, INTERVAL 10 MINUTE) AS paid_at,
    tr.created_at
FROM trade tr;

INSERT INTO toss_log (log_id, payment_id, toss_payment_key, raw_response, created_at)
SELECT
    p.payment_id AS log_id,
    p.payment_id,
    p.toss_payment_key,
    JSON_OBJECT('status', 'DONE', 'method', p.pay_method, 'amount', p.amount, 'tradeId', p.trade_id),
    p.paid_at
FROM payment p;

INSERT INTO point_history (point_id, wallet_id, trade_id, type, change_amount, balance, created_at)
SELECT
    ROW_NUMBER() OVER (ORDER BY tr.completed_at, tr.trade_id) AS point_id,
    tr.seller_id AS wallet_id,
    tr.trade_id,
    'EARN' AS type,
    tr.trade_price AS change_amount,
    SUM(tr.trade_price) OVER (
        PARTITION BY tr.seller_id
        ORDER BY tr.completed_at, tr.trade_id
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS balance,
    tr.completed_at AS created_at
FROM trade tr
WHERE tr.status = 'COMPLETED';

UPDATE point_wallet pw
SET
    pw.balance = COALESCE((
                              SELECT SUM(tr.trade_price)
                              FROM trade tr
                              WHERE tr.seller_id = pw.member_id
                                AND tr.status = 'COMPLETED'
                          ), 0),
    pw.withdrawable = COALESCE((
                                   SELECT SUM(tr.trade_price)
                                   FROM trade tr
                                   WHERE tr.seller_id = pw.member_id
                                     AND tr.status = 'COMPLETED'
                               ), 0),
    pw.pending = COALESCE((
                              SELECT SUM(tr.trade_price)
                              FROM trade tr
                              WHERE tr.seller_id = pw.member_id
                                AND tr.status IN ('PAID', 'RECEIVED', 'CONFIRMED')
                          ), 0)
WHERE EXISTS (
    SELECT 1
    FROM trade tr
    WHERE tr.seller_id = pw.member_id
);

INSERT INTO point_request (
    withdraw_id, member_id, request_amount, bank_name, account_number, status, reject_reason, created_at
)
SELECT
    s.n AS withdraw_id,
    seller_pool.member_id,
    20000 + (MOD(s.n * 9, 6) * 10000) AS request_amount,
    CASE MOD(s.n, 4) WHEN 0 THEN '국민은행' WHEN 1 THEN '신한은행' WHEN 2 THEN '우리은행' ELSE '카카오뱅크' END AS bank_name,
    CONCAT('11', LPAD(100000 + s.n * 37, 8, '0')) AS account_number,
    CASE MOD(s.n, 3) WHEN 0 THEN 'PENDING' WHEN 1 THEN 'REJECTED' ELSE 'CANCELED' END AS status,
    CASE WHEN MOD(s.n, 3) = 1 THEN '예금주 확인 불가' ELSE NULL END AS reject_reason,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 2, 18) DAY) AS created_at
FROM tmp_seq_40 s
         JOIN (
    SELECT member_id, rn
    FROM (
             SELECT
                 member_id,
                 ROW_NUMBER() OVER (ORDER BY member_id) AS rn
             FROM point_wallet
             WHERE withdrawable > 0
         ) ranked_wallet
    WHERE rn <= 18
) seller_pool
              ON seller_pool.rn = s.n
WHERE s.n <= 18;

INSERT INTO chat_room (chat_id, trade_id, buyer_id, post_id, created_at)
SELECT
    s.n AS chat_id,
    NULL AS trade_id,
    82 + MOD(s.n - 1, 20) AS buyer_id,
    s.n AS post_id,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 2, 12) DAY) AS created_at
FROM tmp_seq_60 s;

INSERT INTO chat_room (chat_id, trade_id, buyer_id, post_id, created_at)
SELECT
    60 + s.n AS chat_id,
    s.n AS trade_id,
    tr.buyer_id,
    tr.post_id,
    tr.created_at
FROM tmp_seq_60 s
         JOIN trade tr
              ON tr.trade_id = s.n;

INSERT INTO chat_message (message_id, chat_id, sender_id, content, type, is_read, created_at)
SELECT
    ROW_NUMBER() OVER (ORDER BY chat_id, msg_order) AS message_id,
    chat_id,
    sender_id,
    content,
    type,
    is_read,
    created_at
FROM (
         SELECT
             cr.chat_id,
             1 AS msg_order,
             cr.buyer_id AS sender_id,
             '안녕하세요. 실측과 정품 여부 확인 부탁드립니다.' AS content,
             'TEXT' AS type,
             TRUE AS is_read,
             cr.created_at AS created_at
         FROM chat_room cr
         UNION ALL
         SELECT
             cr.chat_id,
             2 AS msg_order,
             p.seller_id AS sender_id,
             '네, 사진과 동일한 제품이며 상태 좋습니다.' AS content,
             'TEXT' AS type,
             MOD(cr.chat_id, 3) <> 0 AS is_read,
             DATE_ADD(cr.created_at, INTERVAL 30 MINUTE) AS created_at
         FROM chat_room cr
                  JOIN post p
                       ON p.post_id = cr.post_id
     ) chat_seed;

INSERT INTO manner_review (manner_id, trade_id, buyer_id, seller_id, score, content, created_at)
SELECT
    s.n AS manner_id,
    tr.trade_id,
    tr.buyer_id,
    tr.seller_id,
    4.0 + (MOD(s.n, 6) * 0.1) AS score,
    CASE MOD(s.n, 3)
        WHEN 0 THEN '응답이 빠르고 포장 상태가 좋았습니다.'
        WHEN 1 THEN '설명과 동일한 상품이 왔고 거래가 매끄러웠습니다.'
        ELSE '재거래 의사 있습니다. 만족스러운 거래였어요.'
        END AS content,
    DATE_ADD(tr.completed_at, INTERVAL 1 DAY) AS created_at
FROM tmp_seq_80 s
         JOIN (
    SELECT
        trade_id,
        buyer_id,
        seller_id,
        completed_at,
        ROW_NUMBER() OVER (ORDER BY completed_at, trade_id) AS rn
    FROM trade
    WHERE status = 'COMPLETED'
) tr
              ON tr.rn = s.n;

INSERT INTO community_post (
    comm_id, member_id, sport_category, team_category, comm_title, comm_content,
    comm_image_url, comm_view_count, like_count, comment_count, status, created_at
)
SELECT
    s.n AS comm_id,
    2 + MOD(s.n - 1, 80) AS member_id,
    tc.sport AS sport_category,
    tc.team AS team_category,
    CONCAT(tc.team, ' 사이즈/보관 팁 공유 #', LPAD(s.n, 2, '0')) AS comm_title,
    CONCAT('커뮤니티 테스트용 게시글입니다. ', tc.team, ' 유니폼 세탁, 보관, 마킹 관리 팁을 공유합니다.') AS comm_content,
    CASE WHEN MOD(s.n, 2) = 0 THEN tc.image_url ELSE NULL END AS comm_image_url,
    50 + MOD(s.n * 11, 500) AS comm_view_count,
    0,
    0,
    CASE WHEN MOD(s.n, 13) = 0 THEN 'HIDDEN' ELSE 'ACTIVE' END AS status,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 3, 40) DAY) AS created_at
FROM tmp_seq_40 s
         JOIN tmp_team_catalog tc
              ON tc.team_idx = 1 + MOD(s.n - 1, 12);

INSERT INTO reply (reply_id, post_id, member_id, parent_id, reply_content, is_deleted, like_count, created_at)
SELECT
    s.n AS reply_id,
    1 + MOD(s.n - 1, 40) AS post_id,
    42 + MOD(s.n - 1, 40) AS member_id,
    NULL AS parent_id,
    '정보 감사합니다. 실측과 소재감이 궁금하네요.' AS reply_content,
    FALSE AS is_deleted,
    0 AS like_count,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 2, 20) DAY) AS created_at
FROM tmp_seq_40 s;

INSERT INTO reply (reply_id, post_id, member_id, parent_id, reply_content, is_deleted, like_count, created_at)
SELECT
    40 + s.n AS reply_id,
    s.n AS post_id,
    82 + MOD(s.n - 1, 20) AS member_id,
    s.n AS parent_id,
    '저도 같은 팀 유니폼 모으는데 도움됐습니다.' AS reply_content,
    FALSE AS is_deleted,
    0 AS like_count,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 2, 18) DAY) AS created_at
FROM tmp_seq_40 s;

INSERT INTO community_like (like_id, member_id, comm_id, created_at)
SELECT
    s.n AS like_id,
    22 + MOD(s.n - 1, 80) AS member_id,
    1 + MOD(s.n - 1, 40) AS comm_id,
    DATE_SUB(NOW(), INTERVAL MOD(s.n, 12) DAY) AS created_at
FROM tmp_seq_80 s;

INSERT INTO reply_like (like_id, member_id, reply_id, created_at)
SELECT
    s.n AS like_id,
    22 + MOD(s.n - 1, 40) AS member_id,
    1 + MOD(s.n - 1, 80) AS reply_id,
    DATE_SUB(NOW(), INTERVAL MOD(s.n, 10) DAY) AS created_at
FROM tmp_seq_60 s;

UPDATE community_post cp
SET
    cp.like_count = (
        SELECT COUNT(*)
        FROM community_like cl
        WHERE cl.comm_id = cp.comm_id
    ),
    cp.comment_count = (
        SELECT COUNT(*)
        FROM reply r
        WHERE r.post_id = cp.comm_id
    )
WHERE EXISTS (
    SELECT 1
    FROM community_like cl
    WHERE cl.comm_id = cp.comm_id
)
   OR EXISTS (
    SELECT 1
    FROM reply r
    WHERE r.post_id = cp.comm_id
);

UPDATE reply r
SET r.like_count = (
    SELECT COUNT(*)
    FROM reply_like rl
    WHERE rl.reply_id = r.reply_id
)
WHERE EXISTS (
    SELECT 1
    FROM reply_like rl
    WHERE rl.reply_id = r.reply_id
);

INSERT INTO report (report_id, reporter_id, target_type, target_id, reason, detail, status, created_at)
SELECT
    s.n AS report_id,
    52 + MOD(s.n - 1, 25) AS reporter_id,
    CASE MOD(s.n, 2) WHEN 0 THEN 'POST' ELSE 'COMMUNITY_POST' END AS target_type,
    CASE MOD(s.n, 2) WHEN 0 THEN 1 + MOD(s.n * 7, 1000) ELSE 1 + MOD(s.n * 3, 40) END AS target_id,
    CASE MOD(s.n, 4) WHEN 0 THEN 'FAKE' WHEN 1 THEN 'INAPPROPRIATE' WHEN 2 THEN 'FRAUD' ELSE 'ETC' END AS reason,
    '운영/배치 테스트용 신고 더미 데이터입니다.' AS detail,
    CASE MOD(s.n, 3) WHEN 0 THEN 'PENDING' WHEN 1 THEN 'WARNING' ELSE 'NORMAL' END AS status,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 2, 25) DAY) AS created_at
FROM tmp_seq_40 s
WHERE s.n <= 12;

INSERT INTO notification (noti_id, member_id, type, report_content, link_url, is_read, created_at)
SELECT
    s.n AS noti_id,
    2 + MOD(s.n - 1, 100) AS member_id,
    CASE MOD(s.n, 4) WHEN 0 THEN 'TRADE' WHEN 1 THEN 'CHAT' WHEN 2 THEN 'REVIEW' ELSE 'SYSTEM' END AS type,
    CASE MOD(s.n, 4)
        WHEN 0 THEN '새 결제 요청이 등록되었습니다.'
        WHEN 1 THEN '새 채팅 메시지가 도착했습니다.'
        WHEN 2 THEN '새 매너 후기가 등록되었습니다.'
        ELSE '정산/출금 상태를 확인해주세요.'
        END AS report_content,
    CASE MOD(s.n, 4)
        WHEN 0 THEN CONCAT('/trades/', 1 + MOD(s.n, 1800))
        WHEN 1 THEN CONCAT('/chat/', 1 + MOD(s.n, 120))
        WHEN 2 THEN '/mypage/reviews'
        ELSE '/mypage/points'
        END AS link_url,
    MOD(s.n, 3) = 0 AS is_read,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 2, 15) DAY) AS created_at
FROM tmp_seq_40 s;

INSERT INTO risk_analysis_result (risk_id, target_type, target_id, risk_level, reason, suggestion, created_at)
SELECT
    s.n AS risk_id,
    CASE WHEN MOD(s.n, 3) = 0 THEN 'CHAT' ELSE 'POST' END AS target_type,
    CASE WHEN MOD(s.n, 3) = 0 THEN 1 + MOD(s.n, 120) ELSE 1 + MOD(s.n * 17, 1000) END AS target_id,
    CASE MOD(s.n, 3) WHEN 0 THEN 'LOW' WHEN 1 THEN 'MID' ELSE 'HIGH' END AS risk_level,
    CASE MOD(s.n, 3)
        WHEN 0 THEN '반복적인 가격 문의 패턴이 감지되었습니다.'
        WHEN 1 THEN '가격 산정 근거가 부족한 게시글입니다.'
        ELSE '실물 정보 부족 및 과도한 거래 유도 표현이 감지되었습니다.'
        END AS reason,
    CASE MOD(s.n, 3)
        WHEN 0 THEN '자동 차단 없이 모니터링만 유지합니다.'
        WHEN 1 THEN '상세 실측과 추가 사진을 등록하도록 안내하세요.'
        ELSE '운영자 검수 후 노출 유지 여부를 판단하세요.'
        END AS suggestion,
    DATE_SUB(NOW(), INTERVAL MOD(s.n * 2, 35) DAY) AS created_at
FROM tmp_seq_40 s
WHERE s.n <= 20;

DROP TEMPORARY TABLE IF EXISTS tmp_payment_schedule;
DROP TEMPORARY TABLE IF EXISTS tmp_team_catalog;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_1800;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_1000;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_120;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_80;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_60;
DROP TEMPORARY TABLE IF EXISTS tmp_seq_40;
