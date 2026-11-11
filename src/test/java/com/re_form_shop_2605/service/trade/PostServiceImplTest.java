package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.config.PostSizeLabelMigration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.re_form_shop_2605.domain.trade.PostCardVO;
import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.trade.PostCardDTO;
import com.re_form_shop_2605.dto.trade.PostDetailDTO;
import com.re_form_shop_2605.dto.trade.PostRequestDTO;
import com.re_form_shop_2605.dto.trade.PostUpdateRequestDTO;
import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.Enum.MessageType;
import com.re_form_shop_2605.entity.Enum.NotificationType;
import com.re_form_shop_2605.entity.Enum.PostStatus;
import com.re_form_shop_2605.entity.Enum.Role;
import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.entity.chat.ChatMessage;
import com.re_form_shop_2605.entity.chat.ChatRoom;
import com.re_form_shop_2605.entity.etc.Notification;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.entity.trade.Wish;
import com.re_form_shop_2605.repository.chat.ChatMessageRepository;
import com.re_form_shop_2605.repository.chat.ChatRoomRepository;
import com.re_form_shop_2605.repository.etc.NotificationRepository;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.repository.trade.PostRepository;
import com.re_form_shop_2605.repository.trade.WishRepository;
import com.re_form_shop_2605.repository.trade.postImageRepository;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명:
 */
@Log4j2
@SpringBootTest
//@Transactional
class PostServiceImplTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PostService postService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostVectorService postVectorService;
    @Autowired
    private postImageRepository postImageRepository;
    @Autowired
    private WishRepository wishRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private ChatRoomRepository chatRoomRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Test
    void addPostTest() {
        Member seller = createSeller("add_post");
        List<String> imageUrls = List.of(
                "/uploads/post/temp/" + seller.getMemberId() + "/first.png",
                "/uploads/post/temp/" + seller.getMemberId() + "/second.png"
        );
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("add title"), imageUrls);

        assertNotNull(postId);
        assertEquals(2, postImageRepository.findAllByPost_PostIdOrderBySortOrderAsc(postId).size());
    }

    @Test
    void readPostTest() {
        Member seller = createSeller("read_post");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("read title"), List.of());

        PostDetailDTO postDetailDTO = postService.readPost(postId, null);

        assertEquals("read title", postDetailDTO.title());
        assertEquals(PostStatus.ON_SALE, postDetailDTO.status());
    }

    @Test
    void readAllPostsTest() {
        Member seller = createSeller("all_post");
        String titlePrefix = "bulk_title_" + System.nanoTime() + "_";
        for (int i = 0; i < 10; i++) {
            postService.addPost(seller.getMemberId(), createPostRequestDTO(titlePrefix + i), List.of());
        }

        PageResponse<PostCardDTO> posts = postService.readAllPosts(null, 0, 10000);
        int matchedCount = 0;

        for (PostCardDTO post : posts.content()) {
            if (post.title().startsWith(titlePrefix)) {
                matchedCount++;
            }
        }

        assertEquals(10, matchedCount);
    }

    @Test
    void modifyPostTest() {
        Member seller = createSeller("modify_post");
        Long postId = postService.addPost(
                seller.getMemberId(),
                createPostRequestDTO("origin title"),
                List.of("/uploads/post/temp/" + seller.getMemberId() + "/origin.png")
        );

        PostUpdateRequestDTO requestDTO = new PostUpdateRequestDTO(
                "updated title",
                "updated content",
                null,
                null,
                null,
                Grade.S,
                "105",
                false,
                12000,
                DeliveryType.DELIVERY
        );

        List<String> updatedImageUrls = List.of(
                "/uploads/post/temp/" + seller.getMemberId() + "/updated-1.png",
                "/uploads/post/temp/" + seller.getMemberId() + "/updated-2.png"
        );
        postService.modifyPost(postId, seller.getMemberId(), requestDTO, updatedImageUrls);

        PostDetailDTO postDetailDTO = postService.readPost(postId, null);
        assertEquals("updated title", postDetailDTO.title());
        assertEquals(12000, postDetailDTO.price());
        assertEquals(Grade.S, postDetailDTO.grade());
        assertEquals(updatedImageUrls, postDetailDTO.imageUrls());
    }

    @Test
    void modifyPostSendsSystemNoticeToExistingChatRooms() {
        Member seller = createSeller("modify_notice_seller");
        Member buyer = createSeller("modify_notice_buyer");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("notice title"), List.of());

        Post post = postRepository.findById(postId).orElseThrow();
        ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder()
                .post(post)
                .buyer(buyer)
                .build());

        postService.modifyPost(
                postId,
                seller.getMemberId(),
                new PostUpdateRequestDTO("notice updated", null, null, null, null, null, null, null, 9000, DeliveryType.DELIVERY),
                null
        );

        List<ChatMessage> messages = chatMessageRepository
                .findByChatRoom_ChatIdOrderByCreatedAtDesc(chatRoom.getChatId(), PageRequest.of(0, 10))
                .getContent();

        assertFalse(messages.isEmpty());
        assertEquals(MessageType.SYSTEM, messages.get(0).getType());
        assertTrue(messages.get(0).getContent().contains("판매글 수정 안내"));
        assertTrue(messages.get(0).getContent().contains("가격 10000원 -> 9000원"));
    }

    @Test
    void removePostTest() {
        Member seller = createSeller("remove_post");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("remove title"), List.of());

        postService.removePost(postId, seller.getMemberId());

        assertEquals(PostStatus.DELETED, postRepository.findById(postId).orElseThrow().getStatus());
    }

    @Test
    void modifyPostFailsWhenPostIsNotOnSale() {
        Member seller = createSeller("modify_blocked");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("blocked title"), List.of());
        Post post = postRepository.findById(postId).orElseThrow();
        post.changeStatus(PostStatus.RESERVED);
        postRepository.save(post);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> postService.modifyPost(
                        postId,
                        seller.getMemberId(),
                        new PostUpdateRequestDTO("new title", null, null, null, null, null, null, null, null, null),
                        null
                ));

        assertEquals("판매 중인 판매글만 수정할 수 있습니다.", ex.getMessage());
    }

    @Test
    void removePostFailsWhenPostIsNotOnSale() {
        Member seller = createSeller("remove_blocked");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("blocked remove"), List.of());
        Post post = postRepository.findById(postId).orElseThrow();
        post.changeStatus(PostStatus.SOLD);
        postRepository.save(post);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> postService.removePost(postId, seller.getMemberId()));

        assertEquals("판매 중인 판매글만 삭제할 수 있습니다.", ex.getMessage());
    }

    @Test
    void toggleWishAddAndCancelTest() {
        Member seller = createSeller("wish_seller");
        Member buyer = createSeller("wish_buyer");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("wish title"), List.of());

        PostService.WishToggleResult added = postService.toggleWish(postId, buyer.getMemberId());
        assertTrue(added.isLiked());
        assertEquals(1, added.likeCount());
        assertTrue(wishRepository.existsByMember_MemberIdAndPost_PostId(buyer.getMemberId(), postId));

        PostService.WishToggleResult canceled = postService.toggleWish(postId, buyer.getMemberId());
        assertFalse(canceled.isLiked());
        assertEquals(0, canceled.likeCount());
        assertFalse(wishRepository.existsByMember_MemberIdAndPost_PostId(buyer.getMemberId(), postId));
    }

    @Test
    void addPostFailsWhenPriceIsNotPositive() {
        Member seller = createSeller("invalid_price");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> postService.addPost(
                        seller.getMemberId(),
                        new PostRequestDTO(
                                "invalid price",
                                "본문",
                                Sport.BASEBALL,
                                "LG",
                                "유니폼",
                                Grade.A,
                                "100",
                                false,
                                0,
                                DeliveryType.DIRECT
                        ),
                        List.of()
                ));

        assertEquals("판매 가격은 0보다 커야 합니다.", ex.getMessage());
    }

    @Test
    void addPostFailsWhenImageCountExceedsLimit() {
        Member seller = createSeller("too_many_images");
        List<String> imageUrls = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "/uploads/post/temp/" + seller.getMemberId() + "/image-" + i + ".png")
                .toList();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> postService.addPost(seller.getMemberId(), createPostRequestDTO("too many images"), imageUrls));

        assertEquals("판매글 이미지는 최대 10장까지 등록할 수 있습니다.", ex.getMessage());
    }

    @Test
    void modifyPostFailsWhenImageUrlIsBlank() {
        Member seller = createSeller("blank_image");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("blank image title"), List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> postService.modifyPost(
                        postId,
                        seller.getMemberId(),
                        new PostUpdateRequestDTO("new title", null, null, null, null, null, null, null, 1000, null),
                        List.of(" ")
                ));

        assertEquals("판매글 이미지 URL은 비어 있을 수 없습니다.", ex.getMessage());
    }

    @Test
    void modifyPostCreatesPriceDropNotificationsForWishMembers() {
        Member seller = createSeller("price_drop_seller");
        Member watcher = createSeller("price_drop_watcher");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("price drop title"), List.of());

        Post post = postRepository.findById(postId).orElseThrow();
        wishRepository.save(Wish.builder()
                .member(watcher)
                .post(post)
                .build());

        postService.modifyPost(
                postId,
                seller.getMemberId(),
                new PostUpdateRequestDTO(null, null, null, null, null, null, null, null, 9000, null),
                null
        );

        java.util.List<Notification> notifications =
                notificationRepository.findAllByMember_MemberIdOrderByNotiIdDesc(watcher.getMemberId());

        assertEquals(1, notifications.size());
        assertEquals(NotificationType.PRICE_DROP, notifications.get(0).getType());
        assertEquals("/listing/" + postId, notifications.get(0).getLinkUrl());
    }

    @Test
    void modifyPostDoesNotCreatePriceDropNotificationWhenPriceIsRaised() {
        Member seller = createSeller("price_up_seller");
        Member watcher = createSeller("price_up_watcher");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("price up title"), List.of());

        Post post = postRepository.findById(postId).orElseThrow();
        wishRepository.save(Wish.builder()
                .member(watcher)
                .post(post)
                .build());

        postService.modifyPost(
                postId,
                seller.getMemberId(),
                new PostUpdateRequestDTO(null, null, null, null, null, null, null, null, 12000, null),
                null
        );

        assertEquals(0, notificationRepository.findAllByMember_MemberIdOrderByNotiIdDesc(watcher.getMemberId()).size());
    }

    @Test
    void modifyPostDoesNotCreateDuplicateUnreadPriceDropNotificationOnSameDay() {
        Member seller = createSeller("dup_drop_seller");
        Member watcher = createSeller("dup_drop_watcher");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("dup drop title"), List.of());

        Post post = postRepository.findById(postId).orElseThrow();
        wishRepository.save(Wish.builder()
                .member(watcher)
                .post(post)
                .build());

        postService.modifyPost(
                postId,
                seller.getMemberId(),
                new PostUpdateRequestDTO(null, null, null, null, null, null, null, null, 9000, null),
                null
        );
        postService.modifyPost(
                postId,
                seller.getMemberId(),
                new PostUpdateRequestDTO(null, null, null, null, null, null, null, null, 8000, null),
                null
        );

        assertEquals(1, notificationRepository.findAllByMember_MemberIdOrderByNotiIdDesc(watcher.getMemberId()).size());
    }

    @Test
    void modifyPostCanCreatePriceDropNotificationAgainWhenPreviousOneIsRead() {
        Member seller = createSeller("repeat_drop_seller");
        Member watcher = createSeller("repeat_drop_watcher");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("repeat drop title"), List.of());

        Post post = postRepository.findById(postId).orElseThrow();
        wishRepository.save(Wish.builder()
                .member(watcher)
                .post(post)
                .build());

        postService.modifyPost(
                postId,
                seller.getMemberId(),
                new PostUpdateRequestDTO(null, null, null, null, null, null, null, null, 9000, null),
                null
        );

        Notification firstNotification = notificationRepository
                .findAllByMember_MemberIdOrderByNotiIdDesc(watcher.getMemberId())
                .get(0);
        firstNotification.read();
        notificationRepository.save(firstNotification);

        postService.modifyPost(
                postId,
                seller.getMemberId(),
                new PostUpdateRequestDTO(null, null, null, null, null, null, null, null, 8000, null),
                null
        );

        assertEquals(2, notificationRepository.findAllByMember_MemberIdOrderByNotiIdDesc(watcher.getMemberId()).size());
    }

    @Test
    void 상세_조회마다_조회수가_오르고_판매자_본인_조회는_세지_않는다() {
        Member seller = createSeller("view_count");
        Member viewer = createSeller("view_count_viewer");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("view title"), List.of());

        postService.increaseViewCount(postId, viewer.getMemberId());
        postService.increaseViewCount(postId, null);
        postService.increaseViewCount(postId, seller.getMemberId());

        assertEquals(2, postService.readPost(postId, null).viewCount());
    }

    @Test
    void 다른_판매글의_이미지나_외부_URL은_붙일_수_없다() {
        Member seller = createSeller("foreign_image");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("foreign image title"), List.of());
        PostUpdateRequestDTO update = new PostUpdateRequestDTO("new title", null, null, null, null, null, null, null, 1000, null);

        for (String url : List.of(
                "/uploads/post/" + (postId + 1) + "/other.png",
                "https://evil.example.com/a.png",
                "/uploads/post/" + postId + "/../" + (postId + 1) + "/other.png",
                "/uploads/post/temp/" + (seller.getMemberId() + 1) + "/someone-else.png",
                "/uploads/post/" + postId + "/%2e%2e%2f" + (postId + 1) + "%2fother.png"
        )) {
            assertThrows(IllegalArgumentException.class,
                    () -> postService.modifyPost(postId, seller.getMemberId(), update, List.of(url)),
                    url);
        }
    }

    @Test
    void 자기_판매글_이미지와_시드_이미지는_그대로_쓸_수_있다() {
        Member seller = createSeller("own_image");
        Long postId = postService.addPost(seller.getMemberId(), createPostRequestDTO("own image title"), List.of());
        PostUpdateRequestDTO update = new PostUpdateRequestDTO("new title", null, null, null, null, null, null, null, 1000, null);

        postService.modifyPost(postId, seller.getMemberId(), update,
                List.of("/uploads/post/" + postId + "/mine.png", "/uploads/post/seed/lg-twins.svg"));

        assertEquals(
                List.of("/uploads/post/" + postId + "/mine.png", "/uploads/post/seed/lg-twins.svg"),
                postService.readPost(postId, null).imageUrls()
        );
    }

    private Member createSeller(String prefix) {
        long seed = System.nanoTime();
        Member seller = Member.builder()
                .email(prefix + "_" + seed + "@test.com")
                .password("1234")
                .nickname(prefix + "_" + seed)
                .mannerScore(BigDecimal.ZERO)
                .role(Role.USER)
                .status(MemberStatus.ACTIVE)
                .warningCount(0)
                .emailEvent(false)
                .build();
        return memberRepository.save(seller);
    }

    private PostRequestDTO createPostRequestDTO(String title) {
        return createPostRequestDTO(title, "100");
    }

    private PostRequestDTO createPostRequestDTO(String title, String size) {
        return new PostRequestDTO(
                title,
                "테스트 본문",
                Sport.BASEBALL,
                "LG",
                "유니폼",
                Grade.A,
                size,
                false,
                10000,
                DeliveryType.DIRECT
        );
    }

    @Test
    void testSearchPosts() {
        String keyword = null;
        Sport sport = Sport.BASEBALL;
        int page = 0;
        int size = 10;

        PageResponse<PostCardDTO> response = postService.searchPosts(
                keyword, sport, null, null, null, null, null,
                "latest", page, size, null
        );

        log.info("조회된 게시글 수 : " + response.content().size());
        log.info("전체 게시글 수 : " + response.totalElements());

        assertThat(response.content()).isNotNull();

        if (response.totalElements() > 0) {
            assertThat(response.content().get(0).sport()).isEqualTo(Sport.BASEBALL);
            assertThat(response.content().get(0).seller()).isNotNull();
        }
    }

    @Test
    void searchPosts_사이즈_필터는_같은_영문_사이즈만_찾음() {
        Member seller = createSeller("size_filter");
        String title = "사이즈필터 " + System.nanoTime();
        List<String> images = List.of("/uploads/post/temp/" + seller.getMemberId() + "/first.png");
        Long large = postService.addPost(seller.getMemberId(), createPostRequestDTO(title, "L"), images);
        Long other = postService.addPost(seller.getMemberId(), createPostRequestDTO(title, "M"), images);

        // 앞뒤 공백·소문자도 L로 정리된다
        PageResponse<PostCardDTO> response = postService.searchPosts(
                title, null, null, null, null, null, " l ", "latest", 1, 20, null);

        org.assertj.core.api.Assertions.assertThat(response.content().stream().map(PostCardDTO::postId).toList())
                .containsExactly(large)
                .doesNotContain(other);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void PostSizeLabelMigration_숫자_사이즈를_영문으로_바꿔_필터에_걸리게_함() {
        // 예전 더미 글은 국내 숫자 표기로 저장돼 있었다
        Member seller = createSeller("size_migration");
        String title = "사이즈변환 " + System.nanoTime();
        List<String> images = List.of("/uploads/post/temp/" + seller.getMemberId() + "/first.png");
        Long large = postService.addPost(seller.getMemberId(), createPostRequestDTO(title, "100"), images);
        Long xxl = postService.addPost(seller.getMemberId(), createPostRequestDTO(title, "110"), images);
        Long bigger = postService.addPost(seller.getMemberId(), createPostRequestDTO(title, "115"), images);

        PostSizeLabelMigration migration = new PostSizeLabelMigration(jdbcTemplate);
        migration.run(null);
        migration.run(null); // 두 번 돌려도 결과가 같다

        org.assertj.core.api.Assertions.assertThat(postRepository.findById(large).orElseThrow().getSize()).isEqualTo("L");
        PageResponse<PostCardDTO> xxlResponse = postService.searchPosts(
                title, null, null, null, null, null, "XXL", "latest", 1, 20, null);
        org.assertj.core.api.Assertions.assertThat(xxlResponse.content().stream().map(PostCardDTO::postId).toList())
                .containsExactlyInAnyOrder(xxl, bigger);
    }

    @Test
    void initPostVectors() {
        List<Post> posts = postRepository.findAllByStatusNotIn(List.of(PostStatus.DELETED, PostStatus.HIDDEN));
        log.info("전체 게시글 수: {}", posts.size());

        for (Post post : posts) {
            try {
                postVectorService.savePostVector(post);
            } catch (Exception e) {
                log.error("벡터 저장 실패 : postId: {} | {}", post.getPostId(), e.getMessage());
            }
        }
        log.info("벡터 저장 완료");
    }
}
