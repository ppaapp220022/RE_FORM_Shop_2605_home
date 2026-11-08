package com.re_form_shop_2605.mapper.trade;

import com.re_form_shop_2605.domain.trade.PostCardVO;
import com.re_form_shop_2605.domain.trade.PostVO;
import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.Sport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-08
 * 설명: 게시글 관련 매퍼 인터페이스
 * ─────────────────────────────────────────────────────
 */
@Mapper
public interface PostMapper {
    // 게시글 등록
    int insertPost(PostVO post);

    // 게시글 번호로 단건 조회
    PostVO findPostById(@Param("postId") Long postId);

    // 전체 게시글 목록 조회
    List<PostVO> findAllPosts();

    // 게시글 전체 정보 수정
    int updatePost(PostVO post);

    // 게시글 상태만 수정
    int updatePostStatus(@Param("postId") Long postId, @Param("status") String status);


    /**
     * ─────────────────────────────────────────────────────
     * 작성자: 손민정
     * 작성일: 2026-05-12
     * 설명: 조건 따른 게시글 조회 Mapper
     * ─────────────────────────────────────────────────────
     */
    /* 조건 따른 게시글 조회 */
    // 1. 검색/필터/정렬 조회
    List<PostCardVO> findPostsByCondition(@Param("keyword") String keyword,
                                          @Param("sport") Sport sport,
                                          @Param("condition") Grade condition,
                                          @Param("tradeType") DeliveryType tradeType,
                                          @Param("minPrice") Integer minPrice,
                                          @Param("maxPrice") Integer maxPrice,
                                          @Param("sort") String sort,
                                          @Param("offset") int offset,
                                          @Param("size") int size,
                                          @Param("memberId") Long memberId); // 로그인 사용자 찜 여부 확인용

    // 2. 전체 건수 (페이지네이션용)
    int countPostByCondition(@Param("keyword") String keyword,
                             @Param("sport") Sport sport,
                             @Param("condition") Grade condition,
                             @Param("tradeType") DeliveryType tradeType,
                             @Param("minPrice") Integer minPrice,
                             @Param("maxPrice") Integer maxPrice);

    // 3. postId 통해 게시글 상세 정보 목록 가져오기 (프론트에 보여줄 PostCardDTO 생성용)
    List<PostCardVO> findPostsByIds(@Param("postIds") List<Long> postIds,
                                    @Param("memberId") Long memberId,
                                    @Param("sport") Sport sport,
                                    @Param("condition") Grade condition,
                                    @Param("tradeType") DeliveryType tradeType,
                                    @Param("minPrice") Integer minPrice,
                                    @Param("maxPrice") Integer maxPrice); // 찜 여부 확인용

    // 4. postId 목록 중 화면에 나오는 글(판매중·필터 조건)의 postId 반환 (순서 없음, 페이지네이션용)
    List<Long> findVisiblePostIds(@Param("postIds") List<Long> postIds,
                        @Param("sport") Sport sport,
                        @Param("condition") Grade condition,
                        @Param("tradeType") DeliveryType tradeType,
                        @Param("minPrice") Integer minPrice,
                        @Param("maxPrice") Integer maxPrice);

    // 5. 판매중인 글 postId 전체 (재정렬 후보를 판매중 글로만 묶을 때)
    List<Long> findOnSalePostIds();
}
