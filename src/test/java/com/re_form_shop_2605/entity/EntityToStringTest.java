package com.re_form_shop_2605.entity;

import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.entity.trade.Trade;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 양방향 연관을 가진 엔티티의 toString이 서로를 무한히 부르지 않는지,
 * 빌더로 만들어도 컬렉션·기본값이 null이 아닌지 확인한다.
 */
class EntityToStringTest {

    @Test
    void 판매글과_거래가_서로_참조해도_toString이_끝난다() {
        Member seller = Member.builder().memberId(1L).email("s@test.com").nickname("s").build();
        Post post = Post.builder().postId(10L).sellerId(seller).title("t").build();
        Trade trade = Trade.builder().tradeId(100L).post(post).seller(seller).buyer(seller).build();
        // Post.trade는 mappedBy라 빌더에 없으므로 리플렉션으로 순환을 만든다.
        org.springframework.test.util.ReflectionTestUtils.setField(post, "trade", trade);

        assertThatCode(post::toString).doesNotThrowAnyException();
        assertThatCode(trade::toString).doesNotThrowAnyException();
        assertThatCode(seller::toString).doesNotThrowAnyException();
    }

    @Test
    void 빌더로_만들어도_기본값이_채워진다() {
        Post post = Post.builder().title("t").build();
        Member member = Member.builder().email("m@test.com").build();
        Trade trade = Trade.builder().build();

        assertThat(post.getImages()).isNotNull();
        assertThat(post.getViewCount()).isZero();
        assertThat(post.getWishCount()).isZero();
        assertThat(trade.getChatRooms()).isNotNull();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getSocialMembers()).isNotNull();
    }
}
