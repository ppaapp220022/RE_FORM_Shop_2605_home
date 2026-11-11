package com.re_form_shop_2605.service.etc;

import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.etc.NotificationDTO;
import com.re_form_shop_2605.dto.etc.NotificationResponseDTO;
import com.re_form_shop_2605.dto.etc.TradeNotificationTemplateDTO;
import com.re_form_shop_2605.entity.Enum.NotificationType;
import com.re_form_shop_2605.entity.etc.Notification;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.entity.trade.Wish;
import com.re_form_shop_2605.repository.etc.NotificationRepository;
import com.re_form_shop_2605.repository.trade.WishRepository;
import com.re_form_shop_2605.service.common.ServicePageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.modelmapper.ModelMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명: 알림 기능을 제공하는 서비스 구현체
 * ─────────────────────────────────────────────────────
 */
@Service
@Log4j2
@RequiredArgsConstructor
@Transactional
public class NotificationServiceImpl implements NotificationService {
    private final NotificationRepository notificationRepository;
    private final WishRepository wishRepository;
    private final ModelMapper modelMapper;
    private final SimpMessagingTemplate messagingTemplate; // WebSocket 실시간 push용

    @Override
    // 알림 목록과 읽지 않은 개수를 함께 반환
    public NotificationResponseDTO readNotifications(Long memberId, int page, int size) {
        List<Notification> notifications = notificationRepository.findAllByMember_MemberIdOrderByNotiIdDesc(memberId);
        List<NotificationDTO> items = new ArrayList<>();

        for (Notification notification : notifications) {
            items.add(toNotificationDTO(notification));
        }

        int unreadCount = notificationRepository.countByMember_MemberIdAndIsReadFalse(memberId);
        PageResponse<NotificationDTO> pageResponse = ServicePageResponse.of(items, page, size);
        return new NotificationResponseDTO(pageResponse, unreadCount);
    }

    @Override
    // 알림을 읽음 상태로 변경

    public void modifyNotificationRead(Long memberId, Long notiId) {
        Notification notification = notificationRepository.findById(notiId)
                .orElseThrow(() -> new IllegalArgumentException("알림이 존재하지 않습니다."));
        if (!notification.getMember().getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인 알림만 읽음 처리할 수 있습니다.");
        }
        notification.read();
    }

    @Override
    // 알림 삭제
    public void removeNotification(Long notiId) {
        notificationRepository.deleteById(notiId);
    }

    // 가격 인하 알림
    @Override
    public void createPriceDropNotifications(Long postId, Long sellerId, String title, int oldPrice, int newPrice) {
        List<Wish> wishes = wishRepository.findAllByPost_PostId(postId);
        if (wishes.isEmpty()) {
            return;
        }

        String linkUrl = "/listing/" + postId;
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();

        for (Wish wish : wishes) {
            Long receiverId = wish.getMember().getMemberId();
            if (receiverId.equals(sellerId)) {
                continue;
            }

             boolean hasUnreadToday = notificationRepository
                    .existsByMember_MemberIdAndTypeAndLinkUrlAndIsReadFalseAndCreatedAtGreaterThanEqual(
                            receiverId,
                            NotificationType.PRICE_DROP,
                            linkUrl,
                            startOfToday
                    );
            if (hasUnreadToday) {
                continue;
            }

            notificationRepository.save(Notification.builder()
                    .member(wish.getMember())
                    .type(NotificationType.PRICE_DROP)
                    .reportContent(buildPriceDropMessage(title, oldPrice, newPrice))
                    .linkUrl(linkUrl)
                    .build());
        }
    }

    // 거래 알림
    @Override
    public void createTradeNotification(Member member, String content, String linkUrl) {
        // DB에 알림 저장
        Notification saved = notificationRepository.save(Notification.builder()
                .member(member)
                .type(NotificationType.TRADE)
                .reportContent(content)
                .linkUrl(linkUrl)
                .build());

        // WebSocket으로 실시간 push — 프론트엔드 /sub/notification/{memberId} 구독 필요
        messagingTemplate.convertAndSend(
                "/sub/notification/" + member.getMemberId(),
                toNotificationDTO(saved)
        );
    }

    // 결제 완료 구매자 알림
    @Override
    public TradeNotificationTemplateDTO buildSellerPaymentCompletedTemplate(Long tradeId, String postTitle) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                "[RE:FORM] 결제가 완료되었습니다. 배송 정보를 입력해주세요.",
                "'" + postTitle + "' 거래 결제가 완료되었습니다. 택배사와 송장번호를 입력해 주세요.",
                buildTradeLink(tradeId)
        );
    }

    // 결제 완료 판매자 알림
    @Override
    public TradeNotificationTemplateDTO buildBuyerPaymentCompletedTemplate(Long tradeId, String postTitle) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                "[RE:FORM] 결제가 완료되었습니다. 판매자의 배송 등록을 기다리고 있습니다.",
                "'" + postTitle + "' 거래 결제가 완료되었습니다. 판매자의 배송 정보 등록을 기다리고 있습니다.",
                buildTradeLink(tradeId)
        );
    }

    @Override
    public TradeNotificationTemplateDTO buildSellerTradeCreatedTemplate(Long tradeId, String postTitle) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                "[RE:FORM] 새로운 거래 요청이 도착했습니다.",
                "'" + postTitle + "' 상품에 새로운 거래 요청이 도착했습니다. 거래 내용을 확인해 주세요.",
                buildTradeLink(tradeId)
        );
    }

    @Override
    public TradeNotificationTemplateDTO buildBuyerTradeCanceledTemplate(Long tradeId, String postTitle) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                "[RE:FORM] 판매자가 거래를 취소했습니다.",
                "'" + postTitle + "' 거래를 판매자가 취소했습니다.",
                buildTradeLink(tradeId)
        );
    }

    @Override
    public TradeNotificationTemplateDTO buildBuyerTradeAcceptedTemplate(Long tradeId, String postTitle, boolean directTrade) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                directTrade
                        ? "[RE:FORM] 거래가 수락되었습니다. 직거래 일정을 조율해 주세요."
                        : "[RE:FORM] 거래가 수락되었습니다. 결제를 진행해 주세요.",
                directTrade
                        ? "'" + postTitle + "' 거래가 수락되었습니다. 채팅으로 직거래 장소와 시간을 조율해 주세요."
                        : "'" + postTitle + "' 거래가 수락되었습니다. 결제를 진행해 주세요.",
                buildTradeLink(tradeId)
        );
    }

    @Override
    public TradeNotificationTemplateDTO buildTradeDeliveryUpdatedTemplate(
            Long tradeId,
            String postTitle,
            boolean deliveryTrade,
            boolean receiverIsSeller
    ) {
        String subject;
        String content;

        if (deliveryTrade && receiverIsSeller) {
            subject = "[RE:FORM] 구매자가 배송지를 입력하거나 수정했습니다.";
            content = "'" + postTitle + "' 거래의 배송지가 입력 또는 수정되었습니다. 배송 준비 전에 내용을 확인해 주세요.";
        } else if (!deliveryTrade && !receiverIsSeller) {
            subject = "[RE:FORM] 판매자가 직거래 장소를 입력하거나 수정했습니다.";
            content = "'" + postTitle + "' 거래의 직거래 장소가 입력 또는 수정되었습니다. 채팅에서 일정을 다시 확인해 주세요.";
        } else {
            subject = "[RE:FORM] 거래 정보가 변경되었습니다.";
            content = "'" + postTitle + "' 거래 정보가 변경되었습니다. 상세 내용을 확인해 주세요.";
        }

        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                subject,
                content,
                buildTradeLink(tradeId)
        );
    }

    // 판매자 배송정보 등록 알림
    @Override
    public TradeNotificationTemplateDTO buildSellerShippingRegisteredTemplate(Long tradeId, String postTitle) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                "[RE:FORM] 배송 정보 등록이 완료되었습니다.",
                "'" + postTitle + "' 거래의 배송 정보 등록이 완료되었습니다.",
                buildTradeLink(tradeId)
        );
    }

    // 구매자 배송정보 확인 알림
    @Override
    public TradeNotificationTemplateDTO buildBuyerShippingRegisteredTemplate(Long tradeId, String postTitle) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                "[RE:FORM] 배송 추적이 시작되었습니다.",
                "'" + postTitle + "' 거래의 송장 정보가 등록되었습니다. 배송 추적을 확인해 주세요.",
                buildTradeLink(tradeId)
        );
    }

    @Override
    public TradeNotificationTemplateDTO buildSellerTradeConfirmedTemplate(Long tradeId, String postTitle) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                "[RE:FORM] 구매자가 거래를 확정했습니다. 거래가 완료되었습니다.",
                "'" + postTitle + "' 거래를 구매자가 확정했습니다. 거래가 완료되었습니다.",
                buildTradeLink(tradeId)
        );
    }

    @Override
    public TradeNotificationTemplateDTO buildBuyerTradeConfirmedTemplate(Long tradeId, String postTitle) {
        return new TradeNotificationTemplateDTO(
                NotificationType.TRADE,
                "[RE:FORM] 구매 확정이 완료되었습니다.",
                "'" + postTitle + "' 거래의 구매 확정이 완료되었습니다.",
                buildTradeLink(tradeId)
        );
    }

    // 알림 엔티티를 응답 DTO로 변환
    private NotificationDTO toNotificationDTO(Notification notification) {
        return new NotificationDTO(
                notification.getNotiId(),
                notification.getType(),
                notification.getReportContent(),
                notification.getLinkUrl(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }

    // 가격인하 알림 응답 dto
    private String buildPriceDropMessage(String title, int oldPrice, int newPrice) {
        return "'" + title + "' 상품 가격이 " + oldPrice + "원에서 " + newPrice + "원으로 인하되었습니다.";
    }

    private String buildTradeLink(Long tradeId) {
        return "/trade/" + tradeId; // 프론트 라우트: /trade/:id
    }

    /**
     * ─────────────────────────────────────────────────────
     * 작성자: 진혜림
     * 작성일: 2026-05-15
     * 설명: 채팅 메시지 수신 알림을 생성
     * ─────────────────────────────────────────────────────
     */

    /**
     * 채팅 알림 생성 — 채팅방 밖에 있는 상대방에게 알림
     *
     * @param receiverMember: 알림을 받을 상대방
     * @param senderNickname: 발신자
     * @param chatId: 채팅방 id
     */
    @Override
    public void createChatNotification(Member receiverMember, String senderNickname, Long chatId) {
        // 알림 내용
        String content = senderNickname + "님이 메세지를 보냈습니다.";

        // 클릭 시 해당 채팅방으로 이동
        String linkUrl = "/chat?roomId=" + chatId;

        // DB에 알림 저장
        Notification saved = notificationRepository.save(Notification.builder()
                .member(receiverMember)            // 수신자
                .type(NotificationType.CHAT)       // 알림 타입 (CHAT 이미 enum에 존재)
                .reportContent(content)            // 알림 내용
                .linkUrl(linkUrl)                  // 클릭 경로
                .build());

        // WebSocket으로 실시간 push — GNB 배지 즉시 갱신
        messagingTemplate.convertAndSend(
                "/sub/notification/" + receiverMember.getMemberId(),
                toNotificationDTO(saved)
        );
    }
}
