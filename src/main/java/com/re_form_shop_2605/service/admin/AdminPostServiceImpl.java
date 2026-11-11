package com.re_form_shop_2605.service.admin;

import com.re_form_shop_2605.dto.admin.AdminPostDetailDTO;
import com.re_form_shop_2605.dto.admin.AdminPostListDTO;
import com.re_form_shop_2605.dto.admin.AdminPostActionRequestDTO;
import com.re_form_shop_2605.dto.admin.PostAction;
import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.entity.Enum.PostStatus;
import com.re_form_shop_2605.entity.Enum.ReportTargetType;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.entity.trade.PostImage;
import com.re_form_shop_2605.entity.trade.Trade;
import com.re_form_shop_2605.repository.etc.ReportRepository;
import com.re_form_shop_2605.repository.trade.PostRepository;
import com.re_form_shop_2605.repository.trade.TradeRepository;
import com.re_form_shop_2605.repository.trade.projection.AdminPostListProjection;
import com.re_form_shop_2605.repository.trade.postImageRepository;
import com.re_form_shop_2605.service.common.ServicePageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-12
 * 설명: 관리자 게시글 관리 기능의 조회와 상태 처리 로직을 제공하는 서비스 구현체
 * ─────────────────────────────────────────────────────
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminPostServiceImpl implements AdminPostService {

    private final PostRepository postRepository;
    private final postImageRepository postImageRepository;
    private final ReportRepository reportRepository;
    private final TradeRepository tradeRepository;

    @Override
    @Transactional(readOnly = true)
    // 키워드와 상태 조건으로 관리자용 게시글 목록을 조회한다.
    public PageResponse<AdminPostListDTO> readPosts(String keyword, PostStatus status, int page, int size) {
        List<AdminPostListProjection> posts = postRepository.findAdminPostListProjections();
        Map<Long, Long> reportCountMap = readReportCounts(posts);
        List<AdminPostListDTO> postDTOs = new ArrayList<>();

        for (AdminPostListProjection post : posts) {
            if (!matchesStatus(post, status) || !matchesKeyword(post, keyword)) {
                continue;
            }
            postDTOs.add(toAdminPostListDTO(post, reportCountMap));
        }

        return ServicePageResponse.of(postDTOs, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    // 관리자용 게시글 상세 정보를 조회한다.
    public AdminPostDetailDTO readPost(Long postId) {
        return toAdminPostDetailDTO(getPost(postId));
    }

    @Override
    // 관리자 액션에 따라 게시글을 숨김 또는 삭제 상태로 변경한다.
    public AdminPostDetailDTO processPost(Long postId, AdminPostActionRequestDTO requestDTO) {
        Post post = getPost(postId);

        if (requestDTO.action() == PostAction.HIDE) {
            post.changeStatus(PostStatus.HIDDEN);
        } else if (requestDTO.action() == PostAction.DELETE) {
            post.markDeleted();
        }

        return toAdminPostDetailDTO(post);
    }

    // 게시글 ID로 게시글 엔티티를 조회한다.
    private Post getPost(Long postId) {
        return postRepository.findWithSellerIdByPostId(postId)
                .orElseThrow(() -> new IllegalArgumentException("판매글이 존재하지 않습니다."));
    }

    // 게시글 상태가 필터 조건과 일치하는지 확인한다.
    private boolean matchesStatus(AdminPostListProjection post, PostStatus status) {
        return status == null || post.getStatus() == status;
    }

    // 제목, 팀명, 판매자 닉네임이 검색 키워드와 일치하는지 확인한다.
    private boolean matchesKeyword(AdminPostListProjection post, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }

        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
        return post.getTitle().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                || post.getTeam().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                || post.getSellerNickname().toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    // 게시글 엔티티를 관리자 게시글 목록 응답 DTO로 변환한다.
    private AdminPostListDTO toAdminPostListDTO(AdminPostListProjection post, Map<Long, Long> reportCountMap) {
        return new AdminPostListDTO(
                post.getPostId(),
                post.getTitle(),
                post.getSport(),
                post.getTeam(),
                post.getPrice(),
                post.getStatus(),
                reportCountMap.getOrDefault(post.getPostId(), 0L),
                post.getSellerId(),
                post.getSellerNickname(),
                post.getCreatedAt()
        );
    }

    // 게시글 엔티티를 관리자 게시글 상세 응답 DTO로 변환한다.
    private AdminPostDetailDTO toAdminPostDetailDTO(Post post) {
        List<PostImage> postImages = postImageRepository.findAllByPost_PostIdOrderBySortOrderAsc(post.getPostId());
        Long tradeId = readTradeId(post.getPostId());
        List<String> imageUrls = new ArrayList<>();

        for (PostImage postImage : postImages) {
            imageUrls.add(postImage.getImageUrl());
        }

        return new AdminPostDetailDTO(
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
                post.getViewCount(),
                post.getWishCount(),
                readReportCount(post.getPostId()),
                imageUrls,
                post.getSellerId().getMemberId(),
                post.getSellerId().getNickname(),
                post.getSellerId().getEmail(),
                post.getCreatedAt(),
                post.getUpdatedAt(),
                tradeId
        );
    }

    // 해당 판매글에 접수된 신고 수를 조회한다.
    private long readReportCount(Long postId) {
        return reportRepository.countByTargetTypeAndTargetId(ReportTargetType.POST, postId);
    }

    // 거래 상세가 필요 없을 때는 trade 엔티티 전체 대신 ID만 조회한다.
    private Long readTradeId(Long postId) {
        return tradeRepository.findFirstByPost_PostIdOrderByTradeIdDesc(postId).map(Trade::getTradeId).orElse(null);
    }

    // 목록 화면에서는 게시글별 count 쿼리를 반복하지 않도록 신고 수를 한 번에 집계한다.
    private Map<Long, Long> readReportCounts(List<AdminPostListProjection> posts) {
        List<Long> postIds = posts.stream()
                .map(AdminPostListProjection::getPostId)
                .toList();

        if (postIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> reportCountMap = new HashMap<>();
        for (var row : reportRepository.countGroupedByTargetTypeAndTargetIds(ReportTargetType.POST, postIds)) {
            reportCountMap.put(row.getTargetId(), row.getReportCount());
        }
        return reportCountMap;
    }
}
