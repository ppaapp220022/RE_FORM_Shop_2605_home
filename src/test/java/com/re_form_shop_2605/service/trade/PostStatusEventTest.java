package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.Enum.PostStatus;
import com.re_form_shop_2605.entity.Enum.Role;
import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.repository.trade.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 판매 상태가 바뀐 판매글이 저장되면 커밋 뒤 벡터 메타데이터 갱신까지 이어지는지 검증
 *  - 엔티티 리스너 → 이벤트 → 커밋 뒤 처리 → 저장 스레드로 이어지는 연결을 실제 JPA 저장으로 확인한다
 *    (테스트에는 PG가 없어 SQL은 실행되지 않고 updateStatuses 호출까지만 본다)
 * ─────────────────────────────────────────────────────
 */
@SpringBootTest
class PostStatusEventTest {

    @MockitoSpyBean
    private PostVectorService postVectorService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 판매_상태를_바꾸면_커밋_뒤_벡터_메타데이터를_갱신() {
        Long postId = createPost().getPostId();

        transactionTemplate.executeWithoutResult(status ->
                postRepository.findById(postId).orElseThrow().changeStatus(PostStatus.SOLD));

        verify(postVectorService, timeout(2000)).updateStatuses(Map.of(postId, PostStatus.SOLD));
    }

    @Test
    void 롤백되면_갱신하지_않음() {
        Long postId = createPost().getPostId();

        transactionTemplate.executeWithoutResult(status -> {
            postRepository.findById(postId).orElseThrow().changeStatus(PostStatus.SOLD);
            postRepository.flush();
            status.setRollbackOnly();
        });

        verify(postVectorService, after(500).never()).updateStatuses(anyMap());
    }

    @Test
    void 상태가_아닌_값만_바뀌면_요청하지_않음() {
        Long postId = createPost().getPostId();

        transactionTemplate.executeWithoutResult(status ->
                postRepository.findById(postId).orElseThrow().increaseWishCount());

        verify(postVectorService, after(500).never()).updateStatuses(anyMap());
    }

    private Post createPost() {
        long seed = System.nanoTime();
        Member seller = memberRepository.save(Member.builder()
                .email("vector_status_" + seed + "@test.com")
                .password("1234")
                .nickname("vector_status_" + seed)
                .mannerScore(BigDecimal.ZERO)
                .role(Role.USER)
                .status(MemberStatus.ACTIVE)
                .warningCount(0)
                .emailEvent(false)
                .build());
        return postRepository.save(Post.builder()
                .sellerId(seller)
                .title("벡터 상태 테스트 " + seed)
                .content("본문")
                .sport(Sport.BASEBALL)
                .team("LG 트윈스")
                .uniformName("유니폼")
                .grade(Grade.A)
                .size("100")
                .marking(false)
                .price(10000)
                .deliveryType(DeliveryType.DIRECT)
                .status(PostStatus.ON_SALE)
                .viewCount(0)
                .wishCount(0)
                .build());
    }
}
