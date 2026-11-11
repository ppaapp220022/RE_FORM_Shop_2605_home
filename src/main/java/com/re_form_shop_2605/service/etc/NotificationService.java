package com.re_form_shop_2605.service.etc;

import com.re_form_shop_2605.dto.etc.TradeNotificationTemplateDTO;
import com.re_form_shop_2605.dto.etc.NotificationResponseDTO;
import com.re_form_shop_2605.entity.member.Member;

/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명: 알림 기능을 제공하는 서비스 인터페이스
 * ─────────────────────────────────────────────────────
 */
public interface NotificationService {
    // 알림 목록을 조회
    NotificationResponseDTO readNotifications(Long memberId, int page, int size);

    // 알림을 읽음 상태로 변경
    void modifyNotificationRead(Long memberId, Long notiId);

    // 알림을 삭제
    void removeNotification(Long notiId);

    // 판매글 가격 인하 알림을 생성
    void createPriceDropNotifications(Long postId, Long sellerId, String title, int oldPrice, int newPrice);

    // 거래 관련 단건 알림을 생성
    void createTradeNotification(Member member, String content, String linkUrl);

    // 결제 완료 후 판매자 안내용 알림/이메일 공통 템플릿
    TradeNotificationTemplateDTO buildSellerPaymentCompletedTemplate(Long tradeId, String postTitle);

    // 결제 완료 후 구매자 안내용 알림/이메일 공통 템플릿
    TradeNotificationTemplateDTO buildBuyerPaymentCompletedTemplate(Long tradeId, String postTitle);

    // 거래 생성 직후 판매자 안내용 템플릿
    TradeNotificationTemplateDTO buildSellerTradeCreatedTemplate(Long tradeId, String postTitle);

    TradeNotificationTemplateDTO buildBuyerTradeCanceledTemplate(Long tradeId, String postTitle);

    // 거래 수락 직후 구매자 안내용 템플릿
    TradeNotificationTemplateDTO buildBuyerTradeAcceptedTemplate(Long tradeId, String postTitle, boolean directTrade);

    // 배송지/직거래 장소 수정 직후 상대방 안내용 템플릿
    TradeNotificationTemplateDTO buildTradeDeliveryUpdatedTemplate(Long tradeId, String postTitle, boolean deliveryTrade, boolean receiverIsSeller);

    // 판매자의 배송 정보 등록 완료 안내용 공통 템플릿
    TradeNotificationTemplateDTO buildSellerShippingRegisteredTemplate(Long tradeId, String postTitle);

    // 구매자의 배송 추적 시작 안내용 공통 템플릿
    TradeNotificationTemplateDTO buildBuyerShippingRegisteredTemplate(Long tradeId, String postTitle);

    // 구매 확정 직후 판매자 안내용 공통 템플릿
    TradeNotificationTemplateDTO buildSellerTradeConfirmedTemplate(Long tradeId, String postTitle);

    // 구매 확정 직후 구매자 안내용 공통 템플릿
    TradeNotificationTemplateDTO buildBuyerTradeConfirmedTemplate(Long tradeId, String postTitle);

    /**
     * ─────────────────────────────────────────────────────
     * 작성자: 진혜림
     * 작성일: 2026-05-15
     * 설명: 채팅 메시지 수신 알림을 생성
     * ─────────────────────────────────────────────────────
     */
    void createChatNotification(Member receiverMember, String senderNickname, Long chatId);
}
