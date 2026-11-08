package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.domain.trade.PostCardVO;
import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.AI.RiskAnalysisResultDTO;
import com.re_form_shop_2605.dto.trade.PostCardDTO;
import com.re_form_shop_2605.dto.trade.PostDetailDTO;
import com.re_form_shop_2605.dto.trade.PostRequestDTO;
import com.re_form_shop_2605.dto.trade.PostUpdateRequestDTO;
import com.re_form_shop_2605.dto.trade.SellerBriefDTO;
import com.re_form_shop_2605.entity.Enum.*;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.entity.trade.PostImage;
import com.re_form_shop_2605.mapper.trade.PostMapper;
import com.re_form_shop_2605.entity.trade.Wish;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.repository.trade.PostRepository;
import com.re_form_shop_2605.repository.trade.WishRepository;
import com.re_form_shop_2605.repository.trade.postImageRepository;
import com.re_form_shop_2605.service.common.ServicePageResponse;
import com.re_form_shop_2605.service.AI.ModerationService;
import com.re_form_shop_2605.service.chat.ChatService;
import com.re_form_shop_2605.service.etc.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명: 판매글 관련 서비스 구현체
 * ─────────────────────────────────────────────────────
 */
@Service
@Log4j2
@RequiredArgsConstructor
@Transactional
public class PostServiceImpl implements PostService {

    private static final int MAX_POST_IMAGES = 10;

    private final PostRepository postRepository;
    private final MemberRepository memberRepository;
    private final postImageRepository postImageRepository;
    private final WishRepository wishRepository;
    private final NotificationService notificationService;
    private final PostImageService postImageService;
    private final ModelMapper modelMapper;
    private final PostMapper postMapper;
    private final PostVectorService postVectorService;
    private final ModerationService moderationService;
    private final ChatService chatService;

    @Override
    // 판매글을 저장하고, 프론트가 이미 업로드한 이미지 URL 목록을 함께 저장한다.
    public Long addPost(Long sellerId, PostRequestDTO postRequestDTO, List<String> imageUrls) {
        validatePrice(postRequestDTO.price());
        validateImageUrls(imageUrls);

        Member seller = memberRepository.findById(sellerId)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));

        Post post = modelMapper.map(postRequestDTO, Post.class);
        post = Post.builder()
                .sellerId(seller)
                .title(post.getTitle())
                .content(post.getContent())
                .sport(post.getSport())
                .team(post.getTeam())
                .uniformName(post.getUniformName())
                .grade(post.getGrade())
                .size(post.getSize())
                .marking(post.getMarking() != null ? post.getMarking() : Boolean.FALSE)
                .price(post.getPrice())
                .deliveryType(post.getDeliveryType())
                .status(PostStatus.ON_SALE)
                .viewCount(0)
                .wishCount(0)
                .build();

        Post savedPost = postRepository.save(post);
        List<String> finalizedImageUrls = postImageService.finalizePostImageUrls(savedPost.getPostId(), sellerId, imageUrls);

        if (finalizedImageUrls != null && !finalizedImageUrls.isEmpty()) {
            List<PostImage> postImages = new ArrayList<>();
            for (int i = 0; i < finalizedImageUrls.size(); i++) {
                postImages.add(PostImage.builder()
                        .post(savedPost)
                        .imageUrl(finalizedImageUrls.get(i))
                        .sortOrder(i + 1)
                        .build());
            }
            postImageRepository.saveAll(postImages);
        }

        applyPostModeration(savedPost);
        postVectorService.savePostVectorAfterCommit(savedPost); // 벡터 저장 (유사 의미 검색용, 커밋 뒤)

        return savedPost.getPostId();
    }

    @Override
    @Transactional(readOnly = true)
    // 판매글 상세 화면에 필요한 본문, 이미지, 찜 여부 반환
    public PostDetailDTO readPost(Long postId, Long viewerId) {
        Post post = getPost(postId);
        List<PostImage> postImages = postImageRepository.findAllByPost_PostIdOrderBySortOrderAsc(postId);
        List<String> imageUrls = new ArrayList<>();

        for (PostImage postImage : postImages) {
            imageUrls.add(postImage.getImageUrl());
        }

        boolean isWished = false;
        if (viewerId != null) {
            isWished = wishRepository.existsByMember_MemberIdAndPost_PostId(viewerId, postId);
        }

        SellerBriefDTO seller = toSellerBriefDTO(post.getSellerId());

        return new PostDetailDTO(
                post.getPostId(),
                post.getTitle(),
                post.getContent(),
                post.getSport(),
                post.getTeam(),
                post.getUniformName(),
                post.getGrade(),
                post.getSize(),
                post.getMarking(),
                post.getPrice(),
                post.getDeliveryType(),
                post.getStatus(),
                post.getRiskLevel(),
                post.getViewCount(),
                post.getWishCount(),
                isWished,
                imageUrls,
                post.getCreatedAt(),
                post.getUpdatedAt(),
                seller,
                post.getTrade() != null ? post.getTrade().getTradeId() : null
        );
    }

    @Override
    @Transactional(readOnly = true)
    // 노출 가능한 판매글 목록을 카드 DTO로 변환해 페이지 단위로 반환
    public PageResponse<PostCardDTO> readAllPosts(Long viewerId, int page, int size) {
        List<Post> posts = postRepository.findAllByStatusNotIn(List.of(PostStatus.HIDDEN, PostStatus.DELETED));
        List<PostCardDTO> postCardDTOList = new ArrayList<>();

        for (Post post : posts) {
            List<PostImage> postImages = postImageRepository.findAllByPost_PostIdOrderBySortOrderAsc(post.getPostId());
            String thumbnailUrl = null;
            if (!postImages.isEmpty()) {
                thumbnailUrl = postImageService.getThumbnailUrl(postImages.get(0).getImageUrl());
            }

            boolean isWished = false;
            if (viewerId != null) {
                isWished = wishRepository.existsByMember_MemberIdAndPost_PostId(viewerId, post.getPostId());
            }

            SellerBriefDTO seller = toSellerBriefDTO(post.getSellerId());

            postCardDTOList.add(new PostCardDTO(
                    post.getPostId(),
                    post.getTitle(),
                    post.getTeam(),
                    post.getSport(),
                    post.getPrice(),
                    post.getGrade(),
                    post.getSize(),
                    post.getDeliveryType(),
                    post.getStatus(),
                    post.getViewCount(),
                    post.getWishCount(),
                    isWished,
                    thumbnailUrl,
                    toTimeAgo(post.getCreatedAt()),
                    post.getCreatedAt(),
                    seller
            ));
        }
        return ServicePageResponse.of(postCardDTOList, page, size);
    }

    @Override
    // 판매글과 이미지 URL 목록을 수정한다.
    public void modifyPost(Long postId, Long sellerId, PostUpdateRequestDTO postUpdateRequestDTO, List<String> imageUrls) {
        Post post = getPost(postId);
        int oldPrice = post.getPrice();
        PostSnapshot beforeUpdate = PostSnapshot.from(post);

        if (!post.getSellerId().getMemberId().equals(sellerId)) {
            throw new IllegalArgumentException("판매글 수정 권한이 없습니다.");
        }
        validateEditable(post);
        validatePrice(postUpdateRequestDTO.price());
        validateImageUrls(imageUrls);
        List<String> finalizedImageUrls = postImageService.finalizePostImageUrls(postId, sellerId, imageUrls);
        post.changePost(
                postUpdateRequestDTO.title(),
                postUpdateRequestDTO.content(),
                postUpdateRequestDTO.sport(),
                postUpdateRequestDTO.team(),
                postUpdateRequestDTO.uniformName(),
                postUpdateRequestDTO.grade(),
                postUpdateRequestDTO.size(),
                postUpdateRequestDTO.marking(),
                postUpdateRequestDTO.price(),
                postUpdateRequestDTO.deliveryType()
        );

        if (finalizedImageUrls != null) {
            postImageRepository.deleteByPost_PostId(postId);
            List<PostImage> postImages = new ArrayList<>();

            for (int i = 0; i < finalizedImageUrls.size(); i++) {
                postImages.add(PostImage.builder()
                        .post(post)
                        .imageUrl(finalizedImageUrls.get(i))
                        .sortOrder(i + 1)
                        .build());
            }
            postImageRepository.saveAll(postImages);
        }

        String chatNotice = buildPostUpdateNotice(beforeUpdate, post, finalizedImageUrls != null);
        if (chatNotice != null) {
            chatService.sendSystemMessageToExistingRooms(post.getPostId(), chatNotice);
        }

        if (postUpdateRequestDTO.price() != null && post.getPrice() < oldPrice) {
            notificationService.createPriceDropNotifications(
                    post.getPostId(),
                    sellerId,
                    post.getTitle(),
                    oldPrice,
                    post.getPrice()
            );
        }

        applyPostModeration(post);
        // 임베딩에 쓰는 값(제목·종목·팀·유니폼명·본문)이 바뀐 경우에만 벡터 덮어쓰기 - 가격·이미지만 바꾸면 그대로 둔다
        if (beforeUpdate.embeddingChanged(post)) {
            postVectorService.savePostVectorAfterCommit(post);
        }
    }

    @Override
    // 판매글을 삭제 상태로 변경
    public void removePost(Long postId, Long sellerId) {
        Post post = getPost(postId);

        if (!post.getSellerId().getMemberId().equals(sellerId)) {
            throw new IllegalArgumentException("판매글 삭제 권한이 없습니다.");
        }
        validateDeletable(post);

        post.markDeleted();
    }

    // 판매글 찜을 토글하고 실제 찜 개수와 동기화
    @Override
    public WishToggleResult toggleWish(Long postId, Long memberId) {
        Post post = getPost(postId);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));

        return wishRepository.findByMember_MemberIdAndPost_PostId(memberId, postId)
                .map(existingWish -> cancelWish(post, existingWish))
                .orElseGet(() -> addWish(post, member));
    }

    // 공통으로 사용하는 판매글 조회 메서드
    private Post getPost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("판매글이 존재하지 않습니다."));
    }

    // 카드 목록에서 사용할 상대 시간 문자열 생성
    private String toTimeAgo(LocalDateTime createdAt) {
        Duration duration = Duration.between(createdAt, LocalDateTime.now());
        long minutes = duration.toMinutes();
        long hours = duration.toHours();
        long days = duration.toDays();

        if (minutes < 1) {
            return "방금 전";
        }
        if (minutes < 60) {
            return minutes + "분 전";
        }
        if (hours < 24) {
            return hours + "시간 전";
        }
        return days + "일 전";
    }

    private void applyPostModeration(Post post) {
        String contentCheck = buildModerationContent(post.getTitle(), post.getContent());
        RiskAnalysisResultDTO moderation = moderationService.checkAndSave(
                contentCheck,
                TargetType.POST,
                post.getPostId()
        );
        post.updateRiskLevel(moderation.riskLevel());
        log.info("[Post Moderation] postId={}, riskLevel={}", post.getPostId(), moderation.riskLevel());
    }

    private String buildModerationContent(String title, String content) {
        String safeTitle = title == null ? "" : title.trim();
        String safeContent = content == null ? "" : content.trim();
        return (safeTitle + " " + safeContent).trim();
    }

    // 판매자 엔티티를 요약 DTO로 변환
    private SellerBriefDTO toSellerBriefDTO(Member member) {
        return new SellerBriefDTO(
                member.getMemberId(),
                member.getNickname(),
                member.getProfileImageUrl(),
                member.getMannerScore()
        );
    }

    /**
     * 작성자: 손민정
     * 작성일: 2026-05-12
     * 설명: 거래 매물 게시글 검색 - 필터/정렬 페이지네이션
     *      - 주의! 사용자가 필터 및 정렬 기준을 설정했을 때 나오는 검색 결과
     *      - 검색어가 포함된 경우는 PostSearchService.search() 이용할 것 (AI 기능 추가)
     */
    /* 필터, 정렬 기준에 따른 검색 결과 조회 */
    @Override
    public PageResponse<PostCardDTO> searchPosts(String keyword, Sport sport, Grade condition, DeliveryType tradeType,
                                                  Integer minPrice, Integer maxPrice, String sort,
                                                  int page, int size, Long memberId) {
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? 10 : size;

        // 1) 게시글 목록 조회
        int offset = (safePage - 1) * safeSize;
        List<PostCardVO> posts = postMapper.findPostsByCondition(
                keyword, sport, condition, tradeType, minPrice, maxPrice, sort, offset, safeSize, memberId);

        // 2) 전체 건수 조회
        int totalElements = postMapper.countPostByCondition(
                keyword, sport, condition, tradeType, minPrice, maxPrice);

        // 3) DTO 변환
        List<PostCardDTO> content = new ArrayList<>();
        for (PostCardVO post : posts) {
            SellerBriefDTO seller = new SellerBriefDTO(
                    post.getSellerMemberId(),
                    post.getSellerNickname(),
                    post.getSellerProfileImageUrl(),
                    post.getSellerMannerScore()
            );
            PostCardDTO dto = new PostCardDTO(
                    post.getPostId(),
                    post.getTitle(),
                    post.getTeam(),
                    post.getSport(),
                    post.getPrice(),
                    post.getCondition(),
                    post.getSize(),
                    post.getTradeType(),
                    post.getStatus(),
                    post.getViewCount(),
                    post.getLikeCount(),
                    post.isLiked(),
                    postImageService.getThumbnailUrl(post.getThumbnailUrl()),
                    null,
                    post.getCreatedAt(),
                    seller
            );
            content.add(dto);
        }

        // 4) PageResponse
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        boolean first = safePage == 1;             // 첫 페이지
        boolean last = totalPages == 0 || safePage >= totalPages; // 마지막 페이지

        return new PageResponse<>(content, totalElements, totalPages, safeSize, safePage, first, last);
    }

    // 판매중인 상태에서만 판매글 수정 가능
    private void validateEditable(Post post) {
        if (post.getStatus() != PostStatus.ON_SALE) {
            throw new IllegalArgumentException("판매 중인 판매글만 수정할 수 있습니다.");
        }
    }

    // 판매글이 삭제 가능한 판매 중 상태인지 검증한다.
    private void validateDeletable(Post post) {
        if (post.getStatus() != PostStatus.ON_SALE) {
            throw new IllegalArgumentException("판매 중인 판매글만 삭제할 수 있습니다.");
        }
    }

    // 판매 가격이 0보다 큰지 검증한다.
    private void validatePrice(Integer price) {
        if (price != null && price <= 0) {
            throw new IllegalArgumentException("판매 가격은 0보다 커야 합니다.");
        }
    }

    //판매글 이미지 URL 목록의 개수와 공백 여부를 검증한다.
    private void validateImageUrls(List<String> imageUrls) {
        if (imageUrls == null) {
            return;
        }

        if (imageUrls.size() > MAX_POST_IMAGES) {
            throw new IllegalArgumentException("판매글 이미지는 최대 10장까지 등록할 수 있습니다.");
        }

        for (String imageUrl : imageUrls) {
            if (imageUrl == null || imageUrl.isBlank()) {
                throw new IllegalArgumentException("판매글 이미지 URL은 비어 있을 수 없습니다.");
            }
        }
    }

    private String buildPostUpdateNotice(PostSnapshot beforeUpdate, Post post, boolean imagesChanged) {
        List<String> changes = new ArrayList<>();

        if (!Objects.equals(beforeUpdate.title(), post.getTitle())) {
            changes.add("제목");
        }
        if (beforeUpdate.price() != post.getPrice()) {
            changes.add(String.format("가격 %d원 -> %d원", beforeUpdate.price(), post.getPrice()));
        }
        if (!Objects.equals(beforeUpdate.deliveryType(), post.getDeliveryType())) {
            changes.add(String.format("수령 방식 %s -> %s",
                    formatDeliveryType(beforeUpdate.deliveryType()),
                    formatDeliveryType(post.getDeliveryType())));
        }
        if (!Objects.equals(beforeUpdate.grade(), post.getGrade())) {
            changes.add(String.format("등급 %s -> %s", beforeUpdate.grade(), post.getGrade()));
        }
        if (!Objects.equals(beforeUpdate.size(), post.getSize())) {
            changes.add(String.format("사이즈 %s -> %s", nullSafe(beforeUpdate.size()), nullSafe(post.getSize())));
        }
        if (!Objects.equals(beforeUpdate.marking(), post.getMarking())) {
            changes.add(String.format("마킹 %s -> %s",
                    Boolean.TRUE.equals(beforeUpdate.marking()) ? "있음" : "없음",
                    Boolean.TRUE.equals(post.getMarking()) ? "있음" : "없음"));
        }
        if (!Objects.equals(beforeUpdate.team(), post.getTeam()) || !Objects.equals(beforeUpdate.uniformName(), post.getUniformName())) {
            changes.add("상품 정보");
        }
        if (!Objects.equals(beforeUpdate.content(), post.getContent())) {
            changes.add("설명");
        }
        if (imagesChanged) {
            changes.add("이미지");
        }

        if (changes.isEmpty()) {
            return null;
        }

        return String.format("[판매글 수정 안내] '%s' 게시글 정보가 변경되었습니다. 변경 항목: %s",
                post.getTitle(),
                String.join(", ", changes));
    }

    private String formatDeliveryType(DeliveryType deliveryType) {
        if (deliveryType == null) {
            return "-";
        }

        return switch (deliveryType) {
            case DIRECT -> "직거래";
            case DELIVERY -> "택배";
            case BOTH -> "직거래/택배";
        };
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record PostSnapshot(
            String title,
            String content,
            Sport sport,
            String team,
            String uniformName,
            Grade grade,
            String size,
            Boolean marking,
            int price,
            DeliveryType deliveryType
    ) {
        private static PostSnapshot from(Post post) {
            return new PostSnapshot(
                    post.getTitle(),
                    post.getContent(),
                    post.getSport(),
                    post.getTeam(),
                    post.getUniformName(),
                    post.getGrade(),
                    post.getSize(),
                    post.getMarking(),
                    post.getPrice(),
                    post.getDeliveryType()
            );
        }

        /* 임베딩에 쓰는 값(PostVectorService.buildContent)이 바뀌었는지 */
        private boolean embeddingChanged(Post post) {
            return !Objects.equals(title, post.getTitle())
                    || !Objects.equals(content, post.getContent())
                    || sport != post.getSport()
                    || !Objects.equals(team, post.getTeam())
                    || !Objects.equals(uniformName, post.getUniformName());
        }
    }

    @Override
    @Transactional(readOnly = true)
    // 현재 회원이 찜한 판매글 목록을 최신순으로 반환 (마이페이지 찜 목록 탭)
    public List<PostCardDTO> getMyWishes(Long memberId) {
        List<Wish> wishes = wishRepository.findAllByMember_MemberIdOrderByCreatedAtDesc(memberId);
        List<PostCardDTO> result = new ArrayList<>();

        for (Wish wish : wishes) {
            Post post = wish.getPost();

            // 삭제·숨김 상태 글은 목록에서 제외
            if (post.getStatus() == PostStatus.HIDDEN || post.getStatus() == PostStatus.DELETED) {
                continue;
            }

            // 썸네일: 정렬 순서 1번 이미지 사용
            List<PostImage> images = postImageRepository.findAllByPost_PostIdOrderBySortOrderAsc(post.getPostId());
            String thumbnailUrl = images.isEmpty() ? null : postImageService.getThumbnailUrl(images.get(0).getImageUrl());

            SellerBriefDTO seller = toSellerBriefDTO(post.getSellerId());

            result.add(new PostCardDTO(
                    post.getPostId(),
                    post.getTitle(),
                    post.getTeam(),
                    post.getSport(),
                    post.getPrice(),
                    post.getGrade(),
                    post.getSize(),
                    post.getDeliveryType(),
                    post.getStatus(),
                    post.getViewCount(),
                    post.getWishCount(),
                    true,               // 찜 목록이므로 isWished 항상 true
                    thumbnailUrl,
                    toTimeAgo(post.getCreatedAt()),
                    post.getCreatedAt(),
                    seller
            ));
        }
        return result;
    }

     // 찜을 추가하고 실제 찜 수를 다시 계산한다.
    private WishToggleResult addWish(Post post, Member member) {
        wishRepository.save(Wish.builder()
                .member(member)
                .post(post)
                .build());
        syncWishCount(post);
        return new WishToggleResult(true, post.getWishCount());
    }

    // 찜을 취소하고 실제 찜 수를 다시 계산한다.
    private WishToggleResult cancelWish(Post post, Wish existingWish) {
        wishRepository.delete(existingWish);
        syncWishCount(post);
        return new WishToggleResult(false, post.getWishCount());
    }

    // 저장 시점마다 실제 찜 개수를 다시 조회해 판매글 찜 수와 동기화한다.
    private void syncWishCount(Post post) {
        long wishCount = wishRepository.countByPost_PostId(post.getPostId());
        post.changeWishCount(Math.toIntExact(wishCount));
    }
}
