package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.entity.trade.Post;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 판매글 상태가 바뀌면 벡터 메타데이터의 판매 상태도 바꾼다
 *  - 상태를 바꾸는 곳(거래 예약·완료·취소, 결제 취소, 신고·관리자 숨김, 삭제)이 여러 서비스에 흩어져 있어 엔티티 저장 시점에 한 번에 잡는다
 *  - 의미 검색은 벡터 메타데이터의 상태로 판매중 글만 후보로 묶으므로, 여기서 놓친 변경은 기동·재임베딩 때 동기화로 맞춘다
 *  - 벡터 저장소가 없는 환경(테스트 등)에서도 엔티티를 쓸 수 있도록 서비스는 있을 때만 쓴다
 * ─────────────────────────────────────────────────────
 */
@Component
public class PostStatusVectorListener {

    private final ObjectProvider<PostVectorService> postVectorServiceProvider;

    public PostStatusVectorListener(ObjectProvider<PostVectorService> postVectorServiceProvider) {
        this.postVectorServiceProvider = postVectorServiceProvider;
    }

    @PostUpdate
    void afterUpdate(Post post) {
        if (post.consumeStatusChange()) {
            postVectorServiceProvider.ifAvailable(service -> service.updateStatusAfterCommit(post.getPostId(), post.getStatus()));
        }
    }
}
