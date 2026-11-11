CREATE DATABASE IF NOT EXISTS reform_shop_2605
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
#
# -- 1. 혹시 모를 기존 유저들 삭제
# DROP USER IF EXISTS 'admin'@'%';
# DROP USER IF EXISTS 'admin'@'localhost';
#
# -- 2. 유저 생성 (localhost와 % 모두를 위해 각각 생성)
# CREATE USER 'admin'@'%' IDENTIFIED BY '0507';
# CREATE USER 'admin'@'localhost' IDENTIFIED BY '0507';
#
# -- 3. 데이터베이스 권한 부여
# GRANT ALL PRIVILEGES ON reform_shop_2605.* TO 'admin'@'%';
# GRANT ALL PRIVILEGES ON reform_shop_2605.* TO 'admin'@'localhost';
#
# -- 4. 변경사항 즉시 반영
# FLUSH PRIVILEGES;

USE reform_shop_2605;

CREATE TABLE IF NOT EXISTS member (
    member_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '회원 ID',
    email VARCHAR(100) NOT NULL COMMENT '회원 이메일',
    password VARCHAR(255) NULL COMMENT '회원 비밀번호 (암호화 처리)',
    nickname VARCHAR(50) NOT NULL COMMENT '회원 닉네임',
    profile_image_url VARCHAR(500) NULL COMMENT '회원 프로필 이미지 URL',
    bio VARCHAR(200) NULL COMMENT '회원 자기소개',
    manner_score DECIMAL(3, 2) NOT NULL DEFAULT 0.00 COMMENT '매너 평균 점수',
    role ENUM ('USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT '사용자 분류',
    status ENUM ('ACTIVE', 'SUSPENDED', 'WITHDRAWN') NOT NULL DEFAULT 'ACTIVE' COMMENT '활동 상태',
    warning_count INT NOT NULL DEFAULT 0 COMMENT '누적 경고 횟수',
    email_event BOOLEAN NOT NULL DEFAULT FALSE COMMENT '이벤트 이메일 수신 여부',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입일',
    PRIMARY KEY (member_id),
    UNIQUE KEY uk_member_email (email),
    UNIQUE KEY uk_member_nickname (nickname)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS social_member (
    social_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '소셜 로그인 ID',
    member_id BIGINT NOT NULL COMMENT '회원 ID',
    provider ENUM ('KAKAO', 'GOOGLE') NOT NULL COMMENT '소셜 로그인 경로',
    provider_id VARCHAR(100) NOT NULL COMMENT '소셜 제공자 고유 ID',
    PRIMARY KEY (social_id),
    UNIQUE KEY uk_social_provider_user (provider, provider_id),
    UNIQUE KEY uk_social_member_provider (member_id, provider),
    CONSTRAINT fk_social_member_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS interest_setting (
    member_id BIGINT NOT NULL COMMENT '회원 ID',
    sport ENUM ('SOCCER', 'BASEBALL', 'BASKETBALL', 'VOLLEYBALL', 'ESPORTS', 'ETC') NOT NULL COMMENT '관심 종목',
    team VARCHAR(100) NULL COMMENT '관심 구단',
    PRIMARY KEY (member_id),
    CONSTRAINT fk_interest_setting_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS interest_keyword (
    keyword_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL COMMENT '회원 ID',
    keyword VARCHAR(200) NOT NULL COMMENT '관심 키워드',
    PRIMARY KEY (keyword_id),
    UNIQUE KEY uk_interest_keyword_member_keyword (member_id, keyword),
    CONSTRAINT fk_interest_keyword_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS post (
    post_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '게시글 번호',
    seller_id BIGINT NOT NULL COMMENT '판매자 member id',
    title VARCHAR(200) NOT NULL COMMENT '제목',
    content TEXT NOT NULL COMMENT '본문',
    sport ENUM ('BASEBALL', 'SOCCER', 'BASKETBALL', 'VOLLEYBALL', 'ESPORTS', 'ETC') NOT NULL COMMENT '종목',
    team VARCHAR(50) NOT NULL COMMENT '구단명',
    uniform_name VARCHAR(200) NOT NULL COMMENT '유니폼명',
    grade ENUM ('S', 'A', 'B', 'C') NOT NULL COMMENT '유니폼 상태 등급',
    size VARCHAR(10) NULL COMMENT '유니폼 사이즈',
    marking BOOLEAN NULL DEFAULT FALSE COMMENT '마킹 여부',
    price INT NOT NULL COMMENT '희망 가격',
    delivery_type ENUM ('DIRECT', 'DELIVERY', 'BOTH') NOT NULL COMMENT '수령 방법',
    status ENUM ('ON_SALE', 'RESERVED', 'SOLD', 'HIDDEN', 'DELETED') NOT NULL DEFAULT 'ON_SALE' COMMENT '거래 상태',
    view_count INT NOT NULL DEFAULT 0 COMMENT '조회수',
    wish_count INT NOT NULL DEFAULT 0 COMMENT '찜 수',
    risk_level ENUM ('LOW', 'MID', 'HIGH') NULL COMMENT '위험 탐지 등급',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '게시글 등록일',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '게시글 수정일',
    PRIMARY KEY (post_id),
    KEY idx_post_seller_id (seller_id),
    KEY idx_post_status (status),
    CONSTRAINT fk_post_member
        FOREIGN KEY (seller_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS post_image (
    image_id BIGINT NOT NULL AUTO_INCREMENT,
    post_id BIGINT NOT NULL COMMENT '게시글 ID',
    image_url VARCHAR(500) NOT NULL COMMENT '이미지 URL',
    sort_order INT NULL DEFAULT 0 COMMENT '이미지 순서',
    PRIMARY KEY (image_id),
    KEY idx_post_image_post_id (post_id),
    CONSTRAINT fk_post_image_post
        FOREIGN KEY (post_id) REFERENCES post (post_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wish (
    wish_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (wish_id),
    UNIQUE KEY uk_wish_member_post (member_id, post_id),
    KEY idx_wish_post_id (post_id),
    CONSTRAINT fk_wish_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_wish_post
        FOREIGN KEY (post_id) REFERENCES post (post_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS trade (
    trade_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '거래 ID',
    post_id BIGINT NOT NULL COMMENT '게시글 ID',
    buyer_id BIGINT NOT NULL COMMENT '구매자 member id',
    seller_id BIGINT NOT NULL COMMENT '판매자 member id',
    status ENUM ('REQUESTED', 'ACCEPTED', 'PAID', 'IN_PROGRESS', 'RECEIVED', 'CONFIRMED', 'COMPLETED', 'CANCELED', 'DISPUTED') NOT NULL COMMENT '거래 상태',
    delivery_type ENUM ('DIRECT', 'DELIVERY') NULL COMMENT '전달 방법',
    delivery_address VARCHAR(300) NULL COMMENT '배송 주소',
    courier_code VARCHAR(50) NULL COMMENT '택배사 코드',
    courier_name VARCHAR(100) NULL COMMENT '택배사명',
    tracking_number VARCHAR(100) NULL COMMENT '송장번호',
    shipping_started_at DATETIME NULL COMMENT '배송 시작 일시',
    trade_price INT NOT NULL COMMENT '실제 거래 금액',
    completed_at DATETIME NULL COMMENT '정산 완료 일시',
    confirmed_at DATETIME NULL COMMENT '구매 확정 일시',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    received_at DATETIME NULL,
    PRIMARY KEY (trade_id),
    KEY idx_trade_post_id (post_id), -- 취소 뒤 재거래를 위해 unique 아님 (진행 중 거래 하나는 서비스에서 검사)
    KEY idx_trade_buyer_id (buyer_id),
    KEY idx_trade_seller_id (seller_id),
    KEY idx_trade_status (status),
    KEY idx_trade_status_received_at (status, received_at),
    CONSTRAINT fk_trade_post
        FOREIGN KEY (post_id) REFERENCES post (post_id),
    CONSTRAINT fk_trade_buyer_member
        FOREIGN KEY (buyer_id) REFERENCES member (member_id),
    CONSTRAINT fk_trade_seller_member
        FOREIGN KEY (seller_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS manner_review (
    manner_id BIGINT NOT NULL AUTO_INCREMENT,
    trade_id BIGINT NOT NULL COMMENT '거래 ID',
    buyer_id BIGINT NOT NULL COMMENT '구매자(작성자) member id',
    seller_id BIGINT NOT NULL COMMENT '판매자(평가 대상) member id',
    score DOUBLE NOT NULL COMMENT '매너 점수',
    content TEXT NULL COMMENT '텍스트 후기',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '작성 시간',
    PRIMARY KEY (manner_id),
    UNIQUE KEY uk_manner_review_trade_buyer (trade_id, buyer_id),
    KEY idx_manner_review_seller_id (seller_id),
    CONSTRAINT fk_manner_review_trade
        FOREIGN KEY (trade_id) REFERENCES trade (trade_id),
    CONSTRAINT fk_manner_review_buyer_member
        FOREIGN KEY (buyer_id) REFERENCES member (member_id),
    CONSTRAINT fk_manner_review_seller_member
        FOREIGN KEY (seller_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS chat_room (
    chat_id BIGINT NOT NULL AUTO_INCREMENT,
    trade_id BIGINT NULL COMMENT '거래 ID',
    buyer_id BIGINT NOT NULL COMMENT '구매자 member_id',
    post_id BIGINT NOT NULL COMMENT '게시글 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '채팅방 생성일',
    PRIMARY KEY (chat_id),
    UNIQUE KEY uk_chat_room_post_buyer (post_id, buyer_id),
    KEY idx_chat_room_buyer_created_at (buyer_id, created_at),
    KEY idx_chat_room_post_created_at (post_id, created_at),
    CONSTRAINT fk_chat_room_post
        FOREIGN KEY (post_id) REFERENCES post (post_id),
    CONSTRAINT fk_chat_room_buyer
        FOREIGN KEY (buyer_id) REFERENCES member (member_id),
    CONSTRAINT fk_chat_room_trade
        FOREIGN KEY (trade_id) REFERENCES trade (trade_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS chat_message (
    message_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '메시지 ID',
    chat_id BIGINT NOT NULL COMMENT '채팅방 ID',
    sender_id BIGINT NOT NULL COMMENT '발신자 member_id',
    content TEXT NULL COMMENT '메시지 내용',
    type ENUM ('TEXT', 'IMAGE', 'SYSTEM') NOT NULL COMMENT '메시지 타입',
    is_read BOOLEAN NOT NULL DEFAULT FALSE COMMENT '읽음 여부',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '메시지 전송 시각',
    PRIMARY KEY (message_id),
    KEY idx_chat_message_chat_id_created_at (chat_id, created_at),
    KEY idx_chat_message_chat_id_is_read_sender (chat_id, is_read, sender_id),
    CONSTRAINT fk_chat_message_room
        FOREIGN KEY (chat_id) REFERENCES chat_room (chat_id),
    CONSTRAINT fk_chat_message_sender
        FOREIGN KEY (sender_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payment (
    payment_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '결제 번호',
    trade_id BIGINT NOT NULL COMMENT '거래 ID',
    pay_method ENUM ('Card', 'Pay') NOT NULL COMMENT '결제 방식',
    approval_no VARCHAR(50) NULL COMMENT '카드 승인 번호',
    toss_order_id VARCHAR(100) NOT NULL COMMENT 'Toss 주문 ID',
    toss_payment_key VARCHAR(200) NULL COMMENT 'Toss 결제 키',
    amount INT NOT NULL COMMENT '결제 금액',
    status ENUM ('READY', 'PAID', 'FAILED', 'CANCELED', 'REFUNDED') NOT NULL DEFAULT 'READY' COMMENT '결제 상태',
    paid_at DATETIME NULL COMMENT '결제 완료 일시',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (payment_id),
    UNIQUE KEY uk_payment_trade_id (trade_id),
    UNIQUE KEY uk_payment_toss_order_id (toss_order_id),
    CONSTRAINT fk_payment_trade
        FOREIGN KEY (trade_id) REFERENCES trade (trade_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS toss_log (
    log_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '로그 번호',
    payment_id BIGINT NOT NULL COMMENT '결제 FK',
    toss_payment_key VARCHAR(200) NULL COMMENT 'Toss 결제 키',
    raw_response JSON NULL COMMENT '토스 API 원문',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (log_id),
    CONSTRAINT fk_toss_log_payment
        FOREIGN KEY (payment_id) REFERENCES payment (payment_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS point_wallet (
    wallet_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    balance INT NOT NULL DEFAULT 0 COMMENT '회원별 보유 포인트',
    withdrawable INT NOT NULL DEFAULT 0 COMMENT '출금 가능 포인트',
    pending INT NOT NULL DEFAULT 0 COMMENT '출금 대기 포인트',
    PRIMARY KEY (wallet_id),
    UNIQUE KEY uk_point_wallet_member_id (member_id),
    CONSTRAINT fk_point_wallet_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS point_history (
    point_id BIGINT NOT NULL AUTO_INCREMENT,
    wallet_id BIGINT NOT NULL,
    trade_id BIGINT NULL COMMENT '중복 지급 방지 거래 ID',
    type ENUM ('EARN', 'WITHDRAW') NOT NULL COMMENT '지급 / 출금',
    change_amount INT NOT NULL COMMENT '변동량',
    balance INT NOT NULL DEFAULT 0 COMMENT '회원별 보유 포인트',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (point_id),
    UNIQUE KEY uk_point_history_trade_id (trade_id),
    KEY idx_point_history_wallet_id (wallet_id),
    KEY idx_point_history_trade_id (trade_id),
    CONSTRAINT fk_point_history_wallet
        FOREIGN KEY (wallet_id) REFERENCES point_wallet (wallet_id),
    CONSTRAINT fk_point_history_trade
        FOREIGN KEY (trade_id) REFERENCES trade (trade_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS point_request (
    withdraw_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    request_amount INT NOT NULL COMMENT '출금 요청 금액',
    bank_name VARCHAR(50) NULL COMMENT '은행명',
    account_number VARCHAR(30) NULL COMMENT '계좌번호',
    status ENUM ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED') NOT NULL COMMENT '출금 처리 상태',
    reject_reason VARCHAR(300) NULL COMMENT '반려 사유',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (withdraw_id),
    KEY idx_point_request_member_id (member_id),
    KEY idx_point_request_status (status),
    CONSTRAINT fk_point_request_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS community_post (
    comm_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '커뮤니티 게시글 ID',
    member_id BIGINT NOT NULL COMMENT '작성자 member_id',
    sport_category ENUM ('SOCCER', 'BASEBALL', 'BASKETBALL', 'VOLLEYBALL', 'ESPORTS', 'ETC') NOT NULL COMMENT '종목',
    team_category VARCHAR(50) NULL COMMENT '구단명',
    comm_title VARCHAR(200) NOT NULL COMMENT '제목',
    comm_content TEXT NOT NULL COMMENT '본문',
    comm_image_url VARCHAR(500) NULL COMMENT '첨부 이미지 URL',
    comm_view_count INT NOT NULL DEFAULT 0 COMMENT '조회수',
    like_count INT NOT NULL DEFAULT 0 COMMENT '좋아요 수',
    comment_count INT NOT NULL DEFAULT 0 COMMENT '댓글 수',
    status ENUM ('ACTIVE', 'HIDDEN', 'DELETED') NOT NULL COMMENT '게시글 상태',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '작성일',
    PRIMARY KEY (comm_id),
    KEY idx_community_post_member_id (member_id),
    CONSTRAINT fk_community_post_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reply (
    reply_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '댓글 ID',
    post_id BIGINT NOT NULL COMMENT '커뮤니티 게시글 ID',
    member_id BIGINT NOT NULL COMMENT '작성자 member_id',
    parent_id BIGINT NULL COMMENT '대댓글의 부모 댓글',
    reply_content TEXT NULL COMMENT '댓글 내용',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '삭제 여부',
    like_count INT NOT NULL DEFAULT 0 COMMENT '좋아요 수',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '작성일',
    PRIMARY KEY (reply_id),
    KEY idx_comment_post_id (post_id),
    KEY idx_comment_member_id (member_id),
    KEY idx_comment_parent_id (parent_id),
    CONSTRAINT fk_comment_community_post
        FOREIGN KEY (post_id) REFERENCES community_post (comm_id),
    CONSTRAINT fk_comment_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_comment_parent
        FOREIGN KEY (parent_id) REFERENCES reply (reply_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS community_like (
    like_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL COMMENT '좋아요 누른 회원 ID',
    comm_id BIGINT NOT NULL COMMENT '대상 게시글 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (like_id),
    UNIQUE KEY uk_community_like_member_comm (member_id, comm_id),
    CONSTRAINT fk_community_like_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_community_like_comm
        FOREIGN KEY (comm_id) REFERENCES community_post (comm_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reply_like (
    like_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL COMMENT '좋아요 누른 회원 ID',
    reply_id BIGINT NOT NULL COMMENT '대상 댓글 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (like_id),
    UNIQUE KEY uk_reply_like_member_reply (member_id, reply_id),
    CONSTRAINT fk_reply_like_member
        FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_reply_like_reply
        FOREIGN KEY (reply_id) REFERENCES reply (reply_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS report (
    report_id BIGINT NOT NULL AUTO_INCREMENT,
    reporter_id BIGINT NOT NULL COMMENT '신고자 member id',
    target_type ENUM ('POST', 'COMMUNITY_POST') NOT NULL COMMENT '신고 대상',
    target_id BIGINT NOT NULL COMMENT '신고 대상 ID',
    reason ENUM ('FAKE', 'INAPPROPRIATE', 'FRAUD', 'ETC') NOT NULL COMMENT '신고 사유',
    detail TEXT NULL COMMENT '상세 내용',
    status ENUM ('PENDING', 'NORMAL', 'WARNING', 'DELETED') NOT NULL COMMENT '신고 처리 상태',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (report_id),
    KEY idx_report_reporter_id (reporter_id),
    KEY idx_report_target (target_type, target_id),
    CONSTRAINT fk_report_member
        FOREIGN KEY (reporter_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS notification (
    noti_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL COMMENT '수신자',
    type ENUM ('TRADE', 'CHAT', 'PRICE_DROP', 'REVIEW', 'SYSTEM') NOT NULL COMMENT '알림 분류',
    report_content VARCHAR(300) NOT NULL COMMENT '알림 내용',
    link_url VARCHAR(300) NULL COMMENT '클릭 시 경로',
    is_read BOOLEAN NOT NULL DEFAULT FALSE COMMENT '읽음 여부',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (noti_id),
    KEY idx_notification_member_id (member_id),
    KEY idx_notification_type (type),
    CONSTRAINT fk_notification_member
        FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS risk_analysis_result (
    risk_id BIGINT NOT NULL AUTO_INCREMENT,
    target_type ENUM ('POST', 'CHAT') NOT NULL COMMENT '감지된 대상 분류',
    target_id BIGINT NOT NULL COMMENT '대상 ID',
    risk_level ENUM ('LOW', 'MID', 'HIGH') NOT NULL COMMENT '위험 레벨',
    reason TEXT NULL COMMENT '탐지 사유',
    suggestion TEXT NULL COMMENT '개선 제안',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (risk_id),
    KEY idx_risk_analysis_target (target_type, target_id)
) ENGINE=InnoDB;
