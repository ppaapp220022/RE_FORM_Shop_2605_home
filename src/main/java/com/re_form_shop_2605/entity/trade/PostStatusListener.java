package com.re_form_shop_2605.entity.trade;

import jakarta.persistence.PostUpdate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 판매글의 판매 상태가 바뀌어 저장되면 PostStatusChangedEvent를 발행한다
 *  - 상태를 바꾸는 곳(거래 예약·완료·취소, 결제 취소, 신고·관리자 숨김, 삭제)이 여러 서비스에 흩어져 있어 엔티티 저장 시점에 한 번에 잡는다
 *  - 받는 쪽(벡터 메타데이터 갱신 등)은 이벤트로 분리해 엔티티가 서비스 계층에 의존하지 않게 한다
 * ─────────────────────────────────────────────────────
 */
@Component
public class PostStatusListener {

    private final ApplicationEventPublisher eventPublisher;

    public PostStatusListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostUpdate
    void afterUpdate(Post post) {
        if (post.consumeStatusChange()) {
            eventPublisher.publishEvent(new PostStatusChangedEvent(post.getPostId(), post.getStatus()));
        }
    }
}
