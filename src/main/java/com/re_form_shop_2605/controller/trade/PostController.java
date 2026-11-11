package com.re_form_shop_2605.controller.trade;

import com.re_form_shop_2605.dto.AI.AiListingSuggestResponseDTO;
import com.re_form_shop_2605.dto.common.ApiResponse;
import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.login.MemberSecurityDTO;
import com.re_form_shop_2605.dto.trade.*;
import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.service.trade.AiListingService;
import com.re_form_shop_2605.service.trade.PostSearchService;
import com.re_form_shop_2605.service.trade.PostImageService;
import com.re_form_shop_2605.service.trade.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명: 판매글 조회/작성/수정/삭제와 이미지 업로드, 찜 토글 API
 * ─────────────────────────────────────────────────────
 */
@RestController
@Tag(name = "판매글 게시물 API", description = "")
@RequestMapping("/api/listings")
@RequiredArgsConstructor
public class PostController {
    private final PostService postService;
    private final PostSearchService postSearchService;
    private final PostImageService postImageService;
    private final AiListingService aiListingService;

    /**
     * 작성자: 손민정
     * 작성일: 2026-05-13
     * 설명: 판매글 게시글 검색
     *      - 필터/정렬 페이지네이션
     *      - 키워드 의미 기반 유사 상품 검색 페이지네이션
     */
    @Operation(
            summary = "판매글 검색 및 목록 조회",
            description = "키워드 제외 searchPosts, 키워드 포함 search 사용해 유사 의미 검색 결과 반환 구현. "
                    + "사이즈 필터는 itemSize(XS·S·M·L·XL·XXL), size는 페이지 크기"
    )
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PostCardDTO>>> readListings(
            @AuthenticationPrincipal MemberSecurityDTO principal,
            @RequestParam(required = false) Sport sport,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) DeliveryType tradeType,
            @RequestParam(required = false) Grade condition,    // 추가 (확인 후 주석 삭제)
            @RequestParam(required = false) Integer minPrice,   // 추가 (확인 후 주석 삭제)
            @RequestParam(required = false) Integer maxPrice,   // 추가 (확인 후 주석 삭제)
            // 유니폼 사이즈 필터 (XS·S·M·L·XL·XXL) - size는 페이지 크기라 이름을 따로 둔다
            @Parameter(description = "유니폼 사이즈 필터 (XS·S·M·L·XL·XXL, 대소문자 무관). 다른 값은 400")
            @RequestParam(required = false) String itemSize,
            // 없으면 검색어 검색은 관련도순, 목록은 최신순 (latest·price_asc·price_desc·popular)
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (사이즈 필터는 itemSize)")
            @RequestParam(defaultValue = "10") int size
    ) {
        Long memberId = principal != null ? principal.getMemberId() : null;
        PageResponse<PostCardDTO> response;

        if (keyword != null && !keyword.isBlank()) {
            // 1) 키워드가 있을 경우, AI 통한 유사 의미 검색 + 일반 검색
            response = postSearchService.search(
                    keyword, sport, condition, tradeType, minPrice, maxPrice, itemSize, sort, page, size, memberId);
        } else {
            // 2) 키워드가 없을 경우, 필터/정렬 적용한 일반 검색
            response = postService.searchPosts(
                    null, sport, condition, tradeType, minPrice, maxPrice, itemSize, sort, page, size, memberId);
        }
        return ResponseEntity.ok(ApiResponse.ok(response,
                "판매글 목록 조회 완료"));
    }

    @Operation(
            summary = "AI 개인화 추천 목록 조회",
            description = "로그인 회원에게 우선 인기 게시글 기반 추천 목록을 반환합니다. 비로그인 사용자는 빈 목록을 반환합니다."
    )
    @GetMapping("/recommendations")
    public ResponseEntity<ApiResponse<List<RecommendPostCardDTO>>> readRecommendations(
            @AuthenticationPrincipal MemberSecurityDTO principal,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (principal == null) {
            return ResponseEntity.ok(ApiResponse.ok(List.of(), "비로그인 추천 목록 조회 완료"));
        }

        int safeSize = Math.max(1, Math.min(size, 50));
        PageResponse<PostCardDTO> response = postService.searchPosts(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "popular",
                1,
                safeSize,
                principal.getMemberId()
        );

        List<RecommendPostCardDTO> content = response.content().stream()
                .map(item -> RecommendPostCardDTO.from(item, "인기 게시글 기반 추천"))
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(content, "AI 추천 목록 조회 완료"));
    }

    // GET /api/listings/{id}
    // 판매글 상세 조회
    @Operation(
            summary = "판매글 상세 조회",
            description = "판매글 ID로 상세 정보와 작성자 기준의 부가 정보를 조회합니다."
    )
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PostDetailDTO>> readListing(
            @PathVariable("id") Long postId,
            @AuthenticationPrincipal MemberSecurityDTO principal
    ) {
        Long viewerId = principal != null ? principal.getMemberId() : null;
        postService.increaseViewCount(postId, viewerId);
        return ResponseEntity.ok(ApiResponse.ok(postService.readPost(postId, viewerId), "판매글 상세 조회 완료"));
    }

    // POST /api/listings/images
    // 프론트가 판매글 작성 전에 파일을 먼저 업로드하고 imageUrls[]를 받을 수 있게 한다.
    @Operation(
            summary = "판매글 이미지 업로드",
            description = "판매글 작성 전에 이미지 파일들을 먼저 업로드하고, 이후 판매글 JSON 본문에 넣을 수 있는 이미지 URL 목록을 반환합니다."
    )
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImageUploadResponse>> uploadListingImages(
            @AuthenticationPrincipal MemberSecurityDTO principal,
            @RequestPart("images") List<MultipartFile> images
    ) {
        List<String> imageUrls = postImageService.saveTemporaryPostImages(principal.getMemberId(), images);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(new ImageUploadResponse(imageUrls), "판매글 이미지 업로드 완료"));
    }
    /**
     * ─────────────────────────────────────────────────────
     * 작성자: 진혜림
     * 작성일: 2026-05-14
     * 설명: 판매 상품 이미지를 업로드하면 AI가 제목과 설명을 자동 제안
     * ─────────────────────────────────────────────────────
     */
    @Operation(
            summary = "AI 판매글 제목/설명 제안",
            description = "판매 상품 이미지를 업로드하면 AI가 판매글 제목과 설명을 자동으로 제안합니다."
    )
    @PostMapping(value = "/ai-suggest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AiListingSuggestResponseDTO>> suggestListingInfo(
        @RequestParam("image") MultipartFile image
    ) throws IOException {
        AiListingSuggestResponseDTO result = aiListingService.suggestFromImage(
            image.getContentType(),
                image.getBytes()
        );
        return ResponseEntity.ok(ApiResponse.ok(result, "AI 제목/설명 제안 완료"));
    }

    // POST /api/listings
    // 프론트 명세에 맞춰 JSON 본문으로 판매글을 등록
    @Operation(
            summary = "판매글 등록",
            description = "프론트엔드 명세에 맞춰 판매글 정보와 이미지 URL 목록을 JSON 형식으로 전달받아 새 판매글을 등록합니다."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<IdResponse>> addListing(
            @AuthenticationPrincipal MemberSecurityDTO principal,
            @Valid @RequestBody ListingCreateRequestDTO requestDTO
    ) {
        Long postId = postService.addPost(
                principal.getMemberId(),
                requestDTO.toPostRequestDTO(),
                requestDTO.imageUrls()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(new IdResponse(postId), "판매글 작성 완료"));
    }

    // PATCH /api/listings/{id}
    // 프론트 명세에 맞춰 JSON 본문으로 판매글을 수정
    @Operation(
            summary = "판매글 수정",
            description = "프론트엔드 명세에 맞춰 판매글 ID에 해당하는 게시글의 정보와 이미지 URL 목록을 수정합니다."
    )
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<IdResponse>> modifyListing(
            @PathVariable("id") Long postId,
            @AuthenticationPrincipal MemberSecurityDTO principal,
            @Valid @RequestBody ListingUpdateRequestDTO requestDTO
    ) {
        postService.modifyPost(
                postId,
                principal.getMemberId(),
                requestDTO.toPostUpdateRequestDTO(),
                requestDTO.imageUrls()
        );
        return ResponseEntity.ok(ApiResponse.ok(new IdResponse(postId), "판매글 수정 완료"));
    }

    // DELETE /api/listings/{id}
    // 판매글을 삭제 상태로 변경
    @Operation(
            summary = "판매글 삭제",
            description = "판매글 ID에 해당하는 게시글을 삭제 상태로 변경합니다."
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> removeListing(
            @PathVariable("id") Long postId,
            @AuthenticationPrincipal MemberSecurityDTO principal
    ) {
        postService.removePost(postId, principal.getMemberId());
        return ResponseEntity.ok(ApiResponse.ok(null, "판매글 삭제 완료"));
    }

    // GET /api/listings/my/likes
    // 현재 로그인 사용자가 찜한 판매글 목록을 최신순으로 조회
    @Operation(
            summary = "내 찜 목록 조회",
            description = "현재 회원이 찜한 판매글 목록을 최신순으로 반환합니다. 삭제·숨김 상태 글은 제외됩니다."
    )
    @GetMapping("/my/likes")
    public ResponseEntity<ApiResponse<List<PostCardDTO>>> getMyWishes(
            @AuthenticationPrincipal MemberSecurityDTO principal
    ) {
        List<PostCardDTO> result = postService.getMyWishes(principal.getMemberId());
        return ResponseEntity.ok(ApiResponse.ok(result, "내 찜 목록 조회 완료"));
    }

    // POST /api/listings/{id}/like
    // 판매글 찜 상태를 토글
    @Operation(
            summary = "판매글 찜 토글",
            description = "현재 회원 기준으로 판매글 찜을 추가 또는 취소하고 최신 찜 상태와 개수를 반환합니다."
    )
    @PostMapping("/{id}/like")
    public ResponseEntity<ApiResponse<LikeToggleResponse>> toggleListingLike(
            @PathVariable("id") Long postId,
            @AuthenticationPrincipal MemberSecurityDTO principal
    ) {
        PostService.WishToggleResult result = postService.toggleWish(postId, principal.getMemberId());
        return ResponseEntity.ok(ApiResponse.ok(
                new LikeToggleResponse(result.isLiked(), result.likeCount()),
                result.isLiked() ? "판매글 찜 추가 완료" : "판매글 찜 취소 완료"
        ));
    }

    // 생성/수정 응답에서 사용하는 식별자 DTO
    public record IdResponse(Long id) {
    }

    // 이미지 업로드 응답에서 사용하는 URL 목록 DTO
    public record ImageUploadResponse(List<String> urls) {
    }

    // 찜 토글 응답 DTO
    public record LikeToggleResponse(boolean isLiked, long likeCount) {
    }
}


