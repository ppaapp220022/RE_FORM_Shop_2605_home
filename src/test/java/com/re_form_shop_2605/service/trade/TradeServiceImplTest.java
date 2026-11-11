package com.re_form_shop_2605.service.trade;


import com.re_form_shop_2605.entity.chat.ChatRoom;
import com.re_form_shop_2605.repository.chat.ChatRoomRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.trade.*;
import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.Enum.PostStatus;
import com.re_form_shop_2605.entity.Enum.Role;
import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.entity.Enum.TradeDeliveryType;
import com.re_form_shop_2605.entity.Enum.TradeStatus;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.entity.payment.PointWallet;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.entity.trade.Trade;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.repository.payment.PointWalletRepository;
import com.re_form_shop_2605.repository.trade.PostRepository;
import com.re_form_shop_2605.repository.trade.TradeRepository;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
/**
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명:
 */
@Log4j2
@SpringBootTest
//@Transactional
class TradeServiceImplTest {

    @Autowired
    private TradeService tradeService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private TradeRepository tradeRepository;
    @Autowired
    private PointWalletRepository pointWalletRepository;
    @Autowired
    private ChatRoomRepository chatRoomRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void addTradeTest() {
        Member seller = createMember("add_trade_seller");
        Member buyer = createMember("add_trade_buyer");
        Post post = createPost(seller, "trade add");

        Long tradeId = tradeService.addTrade(buyer.getMemberId(), new TradeRequestDTO(post.getPostId(), null));

        assertNotNull(tradeId);
    }

    @Test
    void addTradeWithBothDeliveryTypeRequiresSelection() {
        Member seller = createMember("add_trade_both_seller");
        Member buyer = createMember("add_trade_both_buyer");
        Post post = createPost(seller, "trade both", DeliveryType.BOTH);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.addTrade(buyer.getMemberId(), new TradeRequestDTO(post.getPostId(), null))
        );

        assertEquals("수령 방식을 선택해야 합니다.", exception.getMessage());
    }

    @Test
    void addTradeWithBothDeliveryTypeAcceptsBuyerSelection() {
        Member seller = createMember("add_trade_select_seller");
        Member buyer = createMember("add_trade_select_buyer");
        Post post = createPost(seller, "trade select", DeliveryType.BOTH);

        Long tradeId = tradeService.addTrade(
                buyer.getMemberId(),
                new TradeRequestDTO(post.getPostId(), TradeDeliveryType.DIRECT)
        );

        Trade savedTrade = tradeRepository.findById(tradeId).orElseThrow();
        assertEquals(TradeDeliveryType.DIRECT, savedTrade.getDeliveryType());
    }

    @Test
    void addTradeFailsWhenDeliveryOnlyPostReceivesDirectSelection() {
        Member seller = createMember("add_trade_delivery_seller");
        Member buyer = createMember("add_trade_delivery_buyer");
        Post post = createPost(seller, "trade delivery", DeliveryType.DELIVERY);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.addTrade(
                        buyer.getMemberId(),
                        new TradeRequestDTO(post.getPostId(), TradeDeliveryType.DIRECT)
                )
        );

        assertEquals("배송만 가능한 판매글입니다.", exception.getMessage());
    }

    @Test
    void readTradeTest() {
        Member seller = createMember("read_trade_seller");
        Member buyer = createMember("read_trade_buyer");
        Trade trade = createTrade(seller, buyer, "read trade");

        TradeResponseDTO responseDTO = tradeService.readTrade(trade.getTradeId());

        assertEquals(trade.getTradeId(), responseDTO.tradeId());
    }

    @Test
    void readBuyerTradesTest() {
        Member buyer = createMember("buyer_trades_buyer");
        for (int i = 0; i < 10; i++) {
            Member seller = createMember("buyer_trades_seller_" + i);
            createTrade(seller, buyer, "buyer_trade_" + i);
        }

        PageResponse<TradeResponseDTO> trades = tradeService.readBuyerTrades(buyer.getMemberId(), null, 0, 10);
        assertEquals(10, trades.content().size());
    }

    @Test
    void readBuyerTradesFiltersByStatus() {
        Member buyer = createMember("buyer_status_buyer");
        Member seller = createMember("buyer_status_seller");
        createTrade(seller, buyer, "buyer_requested_trade");
        createPaidTrade(seller, buyer, "buyer_paid_trade");

        PageResponse<TradeResponseDTO> trades = tradeService.readBuyerTrades(
                buyer.getMemberId(),
                TradeStatus.PAID,
                0,
                10
        );

        assertEquals(1, trades.content().size());
        assertEquals(TradeStatus.PAID, trades.content().get(0).status());
    }

    @Test
    void readSellerTradesTest() {
        Member seller = createMember("seller_trades_seller");
        for (int i = 0; i < 10; i++) {
            Member buyer = createMember("seller_trades_buyer_" + i);
            createTrade(seller, buyer, "seller_trade_" + i);
        }

        PageResponse<TradeResponseDTO> trades = tradeService.readSellerTrades(seller.getMemberId(), null, 0, 10);
        assertEquals(10, trades.content().size());
    }

    @Test
    void readSellerTradesFiltersByStatus() {
        Member seller = createMember("seller_status_seller");
        Member buyer = createMember("seller_status_buyer");
        createTrade(seller, buyer, "seller_requested_trade");
        createAcceptedTrade(seller, buyer, "seller_accepted_trade");

        PageResponse<TradeResponseDTO> trades = tradeService.readSellerTrades(
                seller.getMemberId(),
                TradeStatus.ACCEPTED,
                0,
                10
        );

        assertEquals(1, trades.content().size());
        assertEquals(TradeStatus.ACCEPTED, trades.content().get(0).status());
    }

    @Test
    void acceptTradeTest() {
        Member seller = createMember("accept_trade_seller");
        Member buyer = createMember("accept_trade_buyer");
        Trade trade = createTrade(seller, buyer, "modify status");

        tradeService.acceptTrade(seller.getMemberId(), trade.getTradeId());

        Trade savedTrade = tradeRepository.findById(trade.getTradeId()).orElseThrow();
        assertEquals(TradeStatus.ACCEPTED, savedTrade.getStatus());
        assertEquals(PostStatus.RESERVED, postRepository.findById(savedTrade.getPost().getPostId()).orElseThrow().getStatus());
    }

    @Test
    void acceptTradeFailsWhenRequesterIsNotSeller() {
        Member seller = createMember("accept_fail_seller");
        Member buyer = createMember("accept_fail_buyer");
        Trade trade = createTrade(seller, buyer, "accept fail");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.acceptTrade(buyer.getMemberId(), trade.getTradeId())
        );

        assertEquals("거래 수락 권한이 없습니다.", exception.getMessage());
    }

    @Test
    void acceptTradeFailsWhenTradeAlreadyAccepted() {
        Member seller = createMember("accept_state_seller");
        Member buyer = createMember("accept_state_buyer");
        Trade trade = createAcceptedTrade(seller, buyer, "accept state fail");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.acceptTrade(seller.getMemberId(), trade.getTradeId())
        );

        assertEquals("구매 요청 상태의 거래만 수락할 수 있습니다.", exception.getMessage());
    }

    @Test
    void confirmTradeTest() {
        Member seller = createMember("confirm_trade_seller");
        Member buyer = createMember("confirm_trade_buyer");
        pointWalletRepository.save(PointWallet.builder().member(seller).build());
        Trade trade = createPaidTrade(seller, buyer, "confirm trade");
        tradeService.confirmTrade(buyer.getMemberId(), trade.getTradeId());

        Trade savedTrade = tradeRepository.findById(trade.getTradeId()).orElseThrow();
        assertEquals(TradeStatus.CONFIRMED, savedTrade.getStatus());
        assertEquals(PostStatus.SOLD, postRepository.findById(savedTrade.getPost().getPostId()).orElseThrow().getStatus());
        assertNotNull(savedTrade.getConfirmedAt());
    }

    @Test
    void confirmTradeFailsWhenTradeNotPaidOrProgressing() {
        Member seller = createMember("confirm_fail_seller");
        Member buyer = createMember("confirm_fail_buyer");
        Trade trade = createTrade(seller, buyer, "confirm fail");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.confirmTrade(buyer.getMemberId(), trade.getTradeId())
        );

        assertEquals("구매 확정이 가능한 거래 상태가 아닙니다.", exception.getMessage());
    }

    @Test
    void confirmTradeFailsWhenTradeAlreadyCompleted() {
        Member seller = createMember("confirm_completed_seller");
        Member buyer = createMember("confirm_completed_buyer");
        Trade trade = createCompletedTrade(seller, buyer, "confirm completed fail");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.confirmTrade(buyer.getMemberId(), trade.getTradeId())
        );

        assertEquals("구매 확정이 가능한 거래 상태가 아닙니다.", exception.getMessage());
    }

    @Test
    void modifyDeliveryTest() {
        Member seller = createMember("modify_delivery_seller");
        Member buyer = createMember("modify_delivery_buyer");
        Trade trade = createDeliveryTrade(seller, buyer, "modify delivery", TradeStatus.REQUESTED);

        tradeService.modifyDelivery(buyer.getMemberId(), trade.getTradeId(), new DeliveryRequestDTO("서울시 강남구"));

        assertEquals("서울시 강남구", tradeRepository.findById(trade.getTradeId()).orElseThrow().getDeliveryAddress());
    }

    @Test
    void modifyDeliveryFailsWhenRequesterIsNotBuyer() {
        Member seller = createMember("delivery_fail_seller");
        Member buyer = createMember("delivery_fail_buyer");
        Trade trade = createDeliveryTrade(seller, buyer, "delivery fail auth", TradeStatus.REQUESTED);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.modifyDelivery(seller.getMemberId(), trade.getTradeId(), new DeliveryRequestDTO("서울시 송파구"))
        );

        assertEquals("배송지 수정 권한이 없습니다.", exception.getMessage());
    }

    @Test
    void modifyDeliveryFailsWhenTradeAlreadyInProgress() {
        Member seller = createMember("delivery_progress_seller");
        Member buyer = createMember("delivery_progress_buyer");
        Trade trade = createDeliveryTrade(seller, buyer, "delivery progress fail", TradeStatus.IN_PROGRESS);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.modifyDelivery(buyer.getMemberId(), trade.getTradeId(), new DeliveryRequestDTO("서울시 용산구"))
        );

        assertEquals("현재 거래 상태에서는 배송지 정보를 수정할 수 없습니다.", exception.getMessage());
    }

    @Test
    void modifyDeliveryForDirectTradeBySellerTest() {
        Member seller = createMember("direct_address_seller");
        Member buyer = createMember("direct_address_buyer");
        Trade trade = createDirectTrade(seller, buyer, "direct address");

        tradeService.modifyDelivery(seller.getMemberId(), trade.getTradeId(), new DeliveryRequestDTO("잠실야구장 2번 출구"));

        assertEquals("잠실야구장 2번 출구", tradeRepository.findById(trade.getTradeId()).orElseThrow().getDeliveryAddress());
    }

    @Test
    void modifyDeliveryForDirectTradeFailsWhenRequesterIsNotSeller() {
        Member seller = createMember("direct_auth_seller");
        Member buyer = createMember("direct_auth_buyer");
        Trade trade = createDirectTrade(seller, buyer, "direct auth fail");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.modifyDelivery(buyer.getMemberId(), trade.getTradeId(), new DeliveryRequestDTO("고척스카이돔"))
        );

        assertEquals("직거래 주소 수정 권한이 없습니다.", exception.getMessage());
    }


    @Test
    void addReviewTest() {
        Member seller = createMember("add_review_seller");
        Member buyer = createMember("add_review_buyer");
        Trade trade = createCompletedTrade(seller, buyer, "add review");

        Long reviewId = tradeService.addReview(buyer.getMemberId(), new ReviewRequestDTO(trade.getTradeId(), 5, "좋아요"));

        assertNotNull(reviewId);
    }

    @Test
    void addReviewUpdatesSellerMannerScore() {
        Member seller = createMember("score_update_seller");
        Member buyer1 = createMember("score_update_buyer_1");
        Member buyer2 = createMember("score_update_buyer_2");
        Trade trade1 = createCompletedTrade(seller, buyer1, "score review 1");
        Trade trade2 = createCompletedTrade(seller, buyer2, "score review 2");

        tradeService.addReview(buyer1.getMemberId(), new ReviewRequestDTO(trade1.getTradeId(), 5, "좋아요"));
        tradeService.addReview(buyer2.getMemberId(), new ReviewRequestDTO(trade2.getTradeId(), 4, "괜찮아요"));

        Member savedSeller = memberRepository.findById(seller.getMemberId()).orElseThrow();
        assertEquals(BigDecimal.valueOf(4.50).setScale(2), savedSeller.getMannerScore());
    }

    @Test
    void addReviewFailsAfterReviewWritePeriod() {
        Member seller = createMember("review_period_seller");
        Member buyer = createMember("review_period_buyer");
        Trade trade = createCompletedTrade(seller, buyer, "expired review", LocalDateTime.now().minusDays(6));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.addReview(buyer.getMemberId(), new ReviewRequestDTO(trade.getTradeId(), 5, "늦은 리뷰"))
        );

        assertEquals("리뷰는 거래 완료 후 5일 이내에만 작성할 수 있습니다.", exception.getMessage());
    }

    @Test
    void readReviewTest() {
        Member seller = createMember("read_review_seller");
        Member buyer = createMember("read_review_buyer");
        Trade trade = createCompletedTrade(seller, buyer, "read review");
        Long reviewId = tradeService.addReview(buyer.getMemberId(), new ReviewRequestDTO(trade.getTradeId(), 4, "괜찮아요"));

        ReviewResponseDTO responseDTO = tradeService.readReview(reviewId);

        assertEquals(reviewId, responseDTO.mannerId());
        assertEquals("괜찮아요", responseDTO.content());
    }

    @Test
    void readSellerReviewsTest() {
        Member seller = createMember("seller_reviews_seller");
        for (int i = 0; i < 10; i++) {
            Member buyer = createMember("seller_reviews_buyer_" + i);
            Trade trade = createCompletedTrade(seller, buyer, "seller review " + i);
            tradeService.addReview(buyer.getMemberId(), new ReviewRequestDTO(trade.getTradeId(), 5, "review_" + i));
        }

        PageResponse<ReviewResponseDTO> reviews = tradeService.readSellerReviews(seller.getMemberId(), 0, 10);
        assertEquals(10, reviews.content().size());
    }

    @Test
    void 판매자가_취소한_판매글은_다른_구매자가_다시_거래할_수_있다() {
        Member seller = createMember("retrade_seller");
        Member firstBuyer = createMember("retrade_buyer1");
        Member secondBuyer = createMember("retrade_buyer2");
        Post post = createPost(seller, "retrade");

        Long firstTradeId = tradeService.addTrade(firstBuyer.getMemberId(), new TradeRequestDTO(post.getPostId(), null));
        tradeService.acceptTrade(seller.getMemberId(), firstTradeId);
        tradeService.cancelTrade(seller.getMemberId(), firstTradeId);

        Long secondTradeId = tradeService.addTrade(secondBuyer.getMemberId(), new TradeRequestDTO(post.getPostId(), null));

        assertNotEquals(firstTradeId, secondTradeId);
        assertEquals(TradeStatus.CANCELED, tradeRepository.findById(firstTradeId).orElseThrow().getStatus());
        assertEquals(TradeStatus.REQUESTED, tradeRepository.findById(secondTradeId).orElseThrow().getStatus());
    }

    @Test
    void 같은_구매자가_취소_뒤_다시_요청하면_채팅방이_새_거래로_연결된다() {
        Member seller = createMember("rerequest_seller");
        Member buyer = createMember("rerequest_buyer");
        Post post = createPost(seller, "rerequest");

        Long firstTradeId = tradeService.addTrade(buyer.getMemberId(), new TradeRequestDTO(post.getPostId(), null));
        tradeService.cancelTrade(seller.getMemberId(), firstTradeId);
        Long secondTradeId = tradeService.addTrade(buyer.getMemberId(), new TradeRequestDTO(post.getPostId(), null));

        ChatRoom chatRoom = chatRoomRepository
                .findByPost_PostIdAndBuyer_MemberId(post.getPostId(), buyer.getMemberId())
                .orElseThrow();
        Long linkedTradeId = jdbcTemplate.queryForObject(
                "SELECT trade_id FROM chat_room WHERE chat_id = ?", Long.class, chatRoom.getChatId());
        assertEquals(secondTradeId, linkedTradeId);
    }

    @Test
    void 진행_중인_거래가_있으면_새_거래를_만들_수_없다() {
        Member seller = createMember("active_trade_seller");
        Member firstBuyer = createMember("active_trade_buyer1");
        Member secondBuyer = createMember("active_trade_buyer2");
        Post post = createPost(seller, "active trade");

        tradeService.addTrade(firstBuyer.getMemberId(), new TradeRequestDTO(post.getPostId(), null));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tradeService.addTrade(secondBuyer.getMemberId(), new TradeRequestDTO(post.getPostId(), null)));
        assertEquals("이미 거래가 진행 중인 게시글입니다.", ex.getMessage());
    }

    private Member createMember(String prefix) {
        long seed = System.nanoTime();
        Member member = Member.builder()
                .email(prefix + "_" + seed + "@test.com")
                .password("1234")
                .nickname(prefix + "_" + seed)
                .mannerScore(BigDecimal.ZERO)
                .role(Role.USER)
                .status(MemberStatus.ACTIVE)
                .warningCount(0)
                .emailEvent(true)
                .build();
        return memberRepository.save(member);
    }

    private Post createPost(Member seller, String title) {
        return createPost(seller, title, DeliveryType.DIRECT);
    }

    private Post createPost(Member seller, String title, DeliveryType deliveryType) {
        Post post = Post.builder()
                .sellerId(seller)
                .title(title)
                .content("content")
                .sport(Sport.BASEBALL)
                .team("LG")
                .uniformName("uniform")
                .grade(Grade.A)
                .size("100")
                .marking(false)
                .price(10000)
                .deliveryType(deliveryType)
                .status(PostStatus.ON_SALE)
                .viewCount(0)
                .wishCount(0)
                .build();
        return postRepository.save(post);
    }

    private Trade createTrade(Member seller, Member buyer, String title) {
        Post post = createPost(seller, title);
        return tradeRepository.save(Trade.builder()
                .post(post)
                .buyer(buyer)
                .seller(seller)
                .status(TradeStatus.REQUESTED)
                .deliveryType(TradeDeliveryType.DIRECT)
                .tradePrice(10000)
                .build());
    }

    private Trade createCompletedTrade(Member seller, Member buyer, String title) {
        return createCompletedTrade(seller, buyer, title, LocalDateTime.now());
    }

    private Trade createCompletedTrade(Member seller, Member buyer, String title, LocalDateTime completedAt) {
        Post post = createPost(seller, title);
        post.changeStatus(PostStatus.SOLD);
        return tradeRepository.save(Trade.builder()
                .post(post)
                .buyer(buyer)
                .seller(seller)
                .status(TradeStatus.COMPLETED)
                .tradePrice(10000)
                .completedAt(completedAt)
                .build());
    }

    private Trade createPaidTrade(Member seller, Member buyer, String title) {
        Post post = createPost(seller, title);
        post.changeStatus(PostStatus.RESERVED);
        return tradeRepository.save(Trade.builder()
                .post(post)
                .buyer(buyer)
                .seller(seller)
                .status(TradeStatus.PAID)
                .tradePrice(10000)
                .build());
    }

    private Trade createAcceptedTrade(Member seller, Member buyer, String title) {
        Post post = createPost(seller, title);
        post.changeStatus(PostStatus.RESERVED);
        return tradeRepository.save(Trade.builder()
                .post(post)
                .buyer(buyer)
                .seller(seller)
                .status(TradeStatus.ACCEPTED)
                .tradePrice(10000)
                .build());
    }

    private Trade createDeliveryTrade(Member seller, Member buyer, String title, TradeStatus tradeStatus) {
        Post post = createPost(seller, title);
        if (tradeStatus != TradeStatus.REQUESTED) {
            post.changeStatus(PostStatus.RESERVED);
        }
        return tradeRepository.save(Trade.builder()
                .post(post)
                .buyer(buyer)
                .seller(seller)
                .status(tradeStatus)
                .deliveryType(TradeDeliveryType.DELIVERY)
                .tradePrice(10000)
                .build());
    }

    private Trade createDirectTrade(Member seller, Member buyer, String title) {
        Post post = createPost(seller, title, DeliveryType.DIRECT);
        return tradeRepository.save(Trade.builder()
                .post(post)
                .buyer(buyer)
                .seller(seller)
                .status(TradeStatus.REQUESTED)
                .deliveryType(TradeDeliveryType.DIRECT)
                .tradePrice(10000)
                .build());
    }

}
