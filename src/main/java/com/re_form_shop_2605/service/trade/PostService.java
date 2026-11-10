package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.trade.PostCardDTO;
import com.re_form_shop_2605.dto.trade.PostDetailDTO;
import com.re_form_shop_2605.dto.trade.PostRequestDTO;
import com.re_form_shop_2605.dto.trade.PostUpdateRequestDTO;
import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.Sport;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// 판매글 관련 서비스 인터페이스
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명: 판매글 도메인 서비스 인터페이스
 * ─────────────────────────────────────────────────────
 */

public interface PostService {
    // 프론트가 전달한 이미지 URL 목록과 함께 판매글을 등록
    Long addPost(Long sellerId, PostRequestDTO postRequestDTO, List<String> imageUrls);

    // 판매글 상세 정보 조회
    PostDetailDTO readPost(Long postId, Long viewerId);

    // 판매글 목록을 페이지 단위 조회
    PageResponse<PostCardDTO> readAllPosts(Long viewerId, int page, int size);

    // 프론트가 전달한 이미지 URL 목록 기준으로 판매글을 수정
    void modifyPost
    (Long postId, Long sellerId, PostUpdateRequestDTO postUpdateRequestDTO, List<String> imageUrls);

    // 판매글 삭제 상태로 변경
    void removePost(Long postId, Long sellerId);

    /**
     * 작성자: 손민정
     * 작성일: 2026-05-12
     * 설명: 거래 매물 게시글 검색 - 키워드/필터/정렬/페이지네이션
     */
    // 게시글 검색 조회
    PageResponse<PostCardDTO> searchPosts(String keyword, Sport sport,
                                          Grade condition, DeliveryType tradeType,
                                          Integer minPrice, Integer maxPrice, String itemSize,
                                          String sort, int page, int size,
                                          Long memberId);

    // 판매글 찜 상태를 토글하고 최신 찜 상태와 개수를 반환
    WishToggleResult toggleWish(Long postId, Long memberId);

    // 현재 회원이 찜한 판매글 목록을 최신순으로 반환 (마이페이지 찜 목록 탭)
    List<PostCardDTO> getMyWishes(Long memberId);

    record WishToggleResult(boolean isLiked, long likeCount) {
    }
}
