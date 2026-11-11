package com.re_form_shop_2605.repository.trade;

import com.re_form_shop_2605.entity.Enum.PostStatus;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.repository.trade.projection.AdminPostListProjection;
import com.re_form_shop_2605.repository.trade.projection.PostStatusProjection;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-08
 * 설명: 게시글 JPA 리포지토리 인터페이스
 * ─────────────────────────────────────────────────────
 */
public interface PostRepository extends JpaRepository<Post, Long> {
    @Query("""
            SELECT p.postId AS postId,
                   p.title AS title,
                   p.sport AS sport,
                   p.team AS team,
                   p.price AS price,
                   p.status AS status,
                   s.memberId AS sellerId,
                   s.nickname AS sellerNickname,
                   p.createdAt AS createdAt
            FROM Post p
            JOIN p.sellerId s
            ORDER BY p.postId DESC
            """)
    List<AdminPostListProjection> findAdminPostListProjections();

    @EntityGraph(attributePaths = {"sellerId"})
    Optional<Post> findWithSellerIdByPostId(Long postId);

    List<Post> findAllByStatusNotIn(List<PostStatus> statuses);

    /* 거래 생성 시 판매글 행을 잠가, 동시에 들어온 구매 요청이 둘 다 진행 중 거래를 만들지 못하게 한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Post p WHERE p.postId = :postId")
    Optional<Post> findByIdForUpdate(@Param("postId") Long postId);

    /* 조회수 1 증가. 동시 조회에도 값이 유실되지 않게 DB에서 더하고, 수정일(updatedAt)은 건드리지 않는다 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.postId = :postId AND p.sellerId.memberId <> :viewerId")
    int increaseViewCount(@Param("postId") Long postId, @Param("viewerId") Long viewerId);

    /* 전체 판매글의 판매 상태 (벡터 메타데이터 동기화용) */
    @Query("SELECT p.postId AS postId, p.status AS status FROM Post p")
    List<PostStatusProjection> findAllStatuses();

    /**
     * ─────────────────────────────────────────────────────
     * 작성자: 손민정
     * 작성일: 2026-05-14
     * 설명: 배치용 리포지토리
     * ─────────────────────────────────────────────────────
     */
    /* 미검사 & 지정 Status가 아닌 게시물 조회 */
    List<Post> findAllByRiskLevelIsNullAndStatusNotIn(List<PostStatus> statuses);

    /* status가 아닌 등록 상품 건 수 */
    long countByStatusNotIn(List<PostStatus> statuses);
}
