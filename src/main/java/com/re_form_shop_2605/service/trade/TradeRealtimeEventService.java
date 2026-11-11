package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.dto.trade.TradeRealtimeEventDTO;
import com.re_form_shop_2605.entity.chat.ChatRoom;
import com.re_form_shop_2605.entity.trade.Trade;
import com.re_form_shop_2605.repository.chat.ChatRoomRepository;
import com.re_form_shop_2605.repository.trade.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 거래 상태 변경을 WebSocket으로 브로드캐스트한다.
 */
@Service
@RequiredArgsConstructor
public class TradeRealtimeEventService {

    private final ChatRoomRepository chatRoomRepository;
    private final TradeRepository tradeRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public ChatRoom ensureTradeChatRoom(Trade trade) {
        ChatRoom chatRoom = chatRoomRepository
                .findByPost_PostIdAndBuyer_MemberId(
                        trade.getPost().getPostId(),
                        trade.getBuyer().getMemberId()
                )
                .orElseGet(() -> chatRoomRepository.save(ChatRoom.builder()
                        .post(trade.getPost())
                        .buyer(trade.getBuyer())
                        .trade(trade)
                        .build()));

        if (chatRoom.getTrade() == null
                || !chatRoom.getTrade().getTradeId().equals(trade.getTradeId())) {
            // ChatRoom.trade는 updatable=false라 save로는 DB에 반영되지 않는다(취소 뒤 같은 구매자가 다시 요청한 경우).
            // DB는 직접 갱신하고, 메모리의 엔티티도 맞춰 둔다.
            tradeRepository.relinkChatRoomTrade(chatRoom.getChatId(), trade.getTradeId());
            chatRoom.connectTrade(trade);
        }

        return chatRoom;
    }

    @Transactional(readOnly = true)
    public void publishTradeUpdated(Trade trade, String eventType) {
        ChatRoom chatRoom = chatRoomRepository.findFirstByTrade_TradeId(trade.getTradeId())
                .orElse(null);

        TradeRealtimeEventDTO payload = new TradeRealtimeEventDTO(
                eventType,
                trade.getTradeId(),
                chatRoom != null ? chatRoom.getChatId() : null,
                trade.getPost().getPostId(),
                trade.getBuyer().getMemberId(),
                trade.getSeller().getMemberId(),
                trade.getStatus(),
                trade.getDeliveryType(),
                trade.getDeliveryAddress(),
                trade.getCourierCode(),
                trade.getCourierName(),
                trade.getTrackingNumber(),
                trade.getTradePrice(),
                trade.getShippingStartedAt(),
                trade.getConfirmedAt(),
                trade.getCompletedAt(),
                LocalDateTime.now()
        );

        messagingTemplate.convertAndSend("/sub/trade/" + trade.getTradeId(), payload);

        if (chatRoom != null) {
            messagingTemplate.convertAndSend("/sub/chat/" + chatRoom.getChatId() + "/trade", payload);
        }
    }
}
