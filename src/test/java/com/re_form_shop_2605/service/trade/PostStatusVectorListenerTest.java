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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 판매 상태가 바뀐 판매글이 저장되면 엔티티 리스너가 벡터 메타데이터 반영을 요청하는지 검증
 *  - 상태를 바꾸는 서비스가 여러 곳이라 리스너가 실제 JPA 저장 시점에 불리는지를 컨텍스트로 확인한다
 * ─────────────────────────────────────────────────────
 */
@SpringBootTest
class PostStatusVectorListenerTest {

    @MockitoBean
    private PostVectorService postVectorService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 판매_상태를_바꾸면_벡터_메타데이터_반영을_요청() {
        Long postId = createPost().getPostId();

        transactionTemplate.executeWithoutResult(status ->
                postRepository.findById(postId).orElseThrow().changeStatus(PostStatus.SOLD));

        verify(postVectorService).updateStatusAfterCommit(postId, PostStatus.SOLD);
    }

    @Test
    void 상태가_아닌_값만_바뀌면_요청하지_않음() {
        Long postId = createPost().getPostId();

        transactionTemplate.executeWithoutResult(status ->
                postRepository.findById(postId).orElseThrow().increaseWishCount());

        verify(postVectorService, never()).updateStatusAfterCommit(anyLong(), any());
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
