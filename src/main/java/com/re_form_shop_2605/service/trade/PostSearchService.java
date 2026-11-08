package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.domain.trade.PostCardVO;
import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.trade.PostCardDTO;
import com.re_form_shop_2605.dto.trade.SellerBriefDTO;
import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.mapper.trade.PostMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
/**
 * 작성자: 손민정
 * 작성일: 2026-05-13
 * 설명: 의미 기반 유사 상품 검색 - 검색 서비스 구현
 */

@Log4j2
@Service
public class PostSearchService {
    private final PostMapper postMapper;
    private final PostImageService postImageService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final PostVectorService vectorService;
    private final String embeddingModel;
    private final PostSearchReranker reranker;
    private final RerankCache rerankCache = new RerankCache(System::currentTimeMillis);

    /* reranker가 null이면 재정렬 없이 벡터 결과를 그대로 쓴다 */
    public PostSearchService(PostMapper postMapper, PostImageService postImageService,
                             ObjectProvider<VectorStore> vectorStoreProvider,
                             PostVectorService vectorService,
                             @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String embeddingModel,
                             PostSearchReranker reranker) {
        this.postMapper = postMapper;
        this.postImageService = postImageService;
        this.vectorStoreProvider = vectorStoreProvider;
        this.vectorService = vectorService;
        this.embeddingModel = embeddingModel;
        this.reranker = reranker;
    }

    public static final int SIMILARITY_TOP_K = 100;
    // 유사도 기준값은 임베딩 모델마다 달라 PostVectorService가 프로퍼티로 갖는다 (app.ai.search.*)
    // 재정렬은 비용·지연을 위해 상위 몇 묶음만 보낸다
    public static final int RERANK_CANDIDATES = 20;
    // 판매중이 아닌 글만 있는 묶음을 빼고도 RERANK_CANDIDATES개가 남도록 넉넉히 받는다
    static final int RERANK_CANDIDATE_GROUPS = RERANK_CANDIDATES * 3;
    static final Duration RERANK_CACHE_TTL = Duration.ofMinutes(10);
    static final int RERANK_CACHE_SIZE = 500;

    /* 검색어 적용 결과 목록 반환 */
    public PageResponse<PostCardDTO> search(String query, Sport sport, Grade condition,
                                    DeliveryType tradeType, Integer minPrice, Integer maxPrice,
                                    String sort, int page, int size, Long memberId) {
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? 10 : size;
        Set<Long> postIdSet = new LinkedHashSet<>();

        // 1) AI 통한 유사 의미 결과 검색
        List<Long> similarIds = findSimilarPostIds(query);
        postIdSet.addAll(similarIds);

        // 2) 키워드 검색
        List<PostCardVO> keywordResults = postMapper.findPostsByCondition(
                query, sport, condition, tradeType, minPrice, maxPrice, sort, 0, Integer.MAX_VALUE, memberId
        );
        keywordResults.forEach(post -> { postIdSet.add(post.getPostId()); });



        // 3) postIds가 비어있을 경우, 빈 결과 반환
        if (postIdSet.isEmpty()) {
            return new PageResponse<>(List.of(), 0, 0, safeSize, safePage, true, true);
        }

        // 4) 화면에 나오는 글(판매중·필터 조건)만 남긴 뒤 페이징 처리
        //    벡터 결과에는 판매 완료 글이나 필터에 안 맞는 글도 섞여 있어, 거르지 않고 자르면 빈 페이지가 생긴다
        //    키워드 결과는 이미 같은 조건으로 조회했으므로 벡터 결과 중 나머지만 확인한다
        Set<Long> visible = new HashSet<>();
        keywordResults.forEach(post -> visible.add(post.getPostId()));
        List<Long> unchecked = similarIds.stream().filter(id -> !visible.contains(id)).toList();
        if (!unchecked.isEmpty()) {
            visible.addAll(postMapper.findVisiblePostIds(unchecked, sport, condition, tradeType, minPrice, maxPrice));
        }
        List<Long> postIds = postIdSet.stream().filter(visible::contains).toList();
        int totalElements = postIds.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        int offset = (safePage - 1) * safeSize;
        List<Long> pageIds = postIds.stream()
                .skip(offset)
                .limit(safeSize)
                .toList();

        // 5) 상세 정보 조회
        List<PostCardVO> posts = postMapper.findPostsByIds(pageIds, memberId, sport, condition, tradeType, minPrice, maxPrice);

        // 6) DTO 변환
        List<PostCardDTO> content = posts.stream().map(post -> {
            SellerBriefDTO seller = new SellerBriefDTO(
                    post.getSellerMemberId(),
                    post.getSellerNickname(),
                    post.getSellerProfileImageUrl(),
                    post.getSellerMannerScore()
            );
            return new PostCardDTO(
                    post.getPostId(), post.getTitle(), post.getTeam(), post.getSport(),
                    post.getPrice(), post.getCondition(), post.getSize(), post.getTradeType(),
                    post.getStatus(), post.getViewCount(), post.getLikeCount(), post.isLiked(),
                    postImageService.getThumbnailUrl(post.getThumbnailUrl()), null, post.getCreatedAt(), seller
            );
        }).toList();

        // 7) PageResponse 반환
        boolean first = safePage == 1;
        boolean last = totalPages == 0 || safePage >= totalPages;

        return new PageResponse<>(content, totalElements, totalPages, safeSize, safePage, first, last);
    }

    /* 벡터 유사도 검색 - 관련도 높은 순 postId 목록 (검색 eval에서도 사용) */
    //  재정렬이 되면 후보 중 모델이 고른 글만, 안 되면 기준값을 넘는 유사도 순 결과
    public List<Long> findSimilarPostIds(String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>();
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("벡터 검색이 비활성화되어 키워드 검색만 수행합니다.");
            return new ArrayList<>();
        }
        // 페이지를 넘길 때마다 재정렬을 다시 하면 모델 출력이 달라져 페이지끼리 어긋나므로, 같은 검색어는 잠시 같은 결과를 쓴다
        String cacheKey = query.trim();
        if (reranker != null) {
            List<Long> cached = rerankCache.get(cacheKey);
            if (cached != null) {
                log.info("AI 검색 재정렬 캐시 사용 {}건", cached.size());
                return new ArrayList<>(cached);
            }
        }
        try {
            if (reranker != null) {
                List<PostVectorService.CandidateGroup> groups = onSaleOnly(
                        vectorService.findCandidateGroups(query, vectorService.rerankCandidateThreshold(), RERANK_CANDIDATE_GROUPS));
                // 검색어 원문은 개인정보가 섞일 수 있어 운영 로그(INFO)에는 남기지 않는다
                // 후보 팀·점수는 검색어가 아니라 판매글 정보라 INFO로 남긴다 - 재정렬이 0건일 때 후보 부족인지 모델 판단인지 가린다
                log.info("AI 검색 재정렬 후보 {}묶음, 최고 점수 {}, 후보 {}", groups.size(),
                        groups.isEmpty() ? null : groups.get(0).score(), candidateSummary(groups));
                log.debug("AI 검색 재정렬 후보 query={}", query);
                groups.forEach(group -> log.debug("AI 검색 재정렬 후보 {} | {} | score {} | {}건",
                        group.team(), group.title(), group.score(), group.postIds().size()));
                Optional<List<Long>> reranked = rerank(query, groups);
                // 재정렬이 실패하면 캐시하지 않고 운영 기준값으로 거른 유사도 순 결과로 돌아간다
                reranked.ifPresent(postIds -> rerankCache.put(cacheKey, postIds));
                reranked.ifPresent(postIds -> log.info("AI 검색 재정렬 선택 {}", candidateSummary(chosenGroups(groups, postIds))));
                return new ArrayList<>(reranked.orElseGet(() -> groupsAboveThreshold(groups)));
            }
            List<Document> similaritySearch = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(SIMILARITY_TOP_K)
                            .similarityThreshold(vectorService.similarityThreshold())
                            // 다른 모델로 만든 벡터와의 유사도는 의미가 없으므로 현재 모델 벡터만 비교
                            .filterExpression(PostVectorService.modelFilter(embeddingModel))
                            .build());
            similaritySearch.forEach(result -> log.debug("AI 검색 결과 postId: {}, score: {}",
                    result.getMetadata().get("postId"), result.getScore()));
            log.info("AI 검색 결과 {}건, 최고 점수 {}",
                    similaritySearch.size(), similaritySearch.isEmpty() ? null : similaritySearch.get(0).getScore());
            log.debug("AI 검색 query={}", query);
            return aboveThreshold(similaritySearch);
        } catch (Exception exception) {
            log.error("벡터 검색에 실패해 키워드 검색만 수행합니다.", exception);
            return new ArrayList<>();
        }
    }

    /* 판매중인 글만 남기고, 판매중인 글이 없는 묶음은 뺀 뒤 상위 RERANK_CANDIDATES개 */
    // 판매 완료 글만 있는 묶음을 모델이 고르면 결과가 0건이 되고, 후보 자리만 차지한다
    //  (postMapper가 없으면 - eval·단위 테스트 - 거르지 않는다)
    private List<PostVectorService.CandidateGroup> onSaleOnly(List<PostVectorService.CandidateGroup> groups) {
        if (postMapper == null || groups.isEmpty()) {
            return groups.stream().limit(RERANK_CANDIDATES).toList();
        }
        List<Long> ids = groups.stream().flatMap(group -> group.postIds().stream()).toList();
        Set<Long> onSale = new HashSet<>(postMapper.findVisiblePostIds(ids, null, null, null, null, null));
        return groups.stream()
                .map(group -> group.retain(onSale::contains))
                .filter(group -> !group.postIds().isEmpty())
                .limit(RERANK_CANDIDATES)
                .toList();
    }

    /* 로그용 후보 요약 - "팀(종목) 점수" 목록 */
    static String candidateSummary(List<PostVectorService.CandidateGroup> groups) {
        return groups.stream()
                .map(group -> String.format(Locale.ROOT, "%s(%s) %.3f", group.team(), group.sport(), group.score()))
                .toList()
                .toString();
    }

    /* 재정렬 결과에 대표 글이 나온 묶음을 모델이 고른 순서대로 */
    static List<PostVectorService.CandidateGroup> chosenGroups(List<PostVectorService.CandidateGroup> groups, List<Long> postIds) {
        Map<Long, PostVectorService.CandidateGroup> byRepresentative = new HashMap<>();
        groups.forEach(group -> byRepresentative.put(group.postIds().get(0), group));
        return postIds.stream()
                .map(byRepresentative::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /* 묶음마다 대표 글 하나를 후보로 보내고, 고른 후보는 묶인 글을 모두 돌려준다 */
    private Optional<List<Long>> rerank(String query, List<PostVectorService.CandidateGroup> groups) {
        Map<Long, List<Long>> membersByRepresentative = new HashMap<>();
        List<PostSearchReranker.Candidate> candidates = groups.stream()
                .map(group -> {
                    membersByRepresentative.put(group.postIds().get(0), group.postIds());
                    return new PostSearchReranker.Candidate(group.postIds().get(0), group.title(), group.sport(), group.team());
                })
                .toList();
        return reranker.rerank(query, candidates).map(chosen -> chosen.stream()
                .flatMap(representative -> membersByRepresentative.getOrDefault(representative, List.of(representative)).stream())
                .toList());
    }

    /* 재정렬이 실패했을 때 - 운영 기준값 이상인 글만, 묶음 최고 유사도 순 */
    private List<Long> groupsAboveThreshold(List<PostVectorService.CandidateGroup> groups) {
        return groups.stream()
                .flatMap(group -> group.postIdsAbove(vectorService.similarityThreshold()).stream())
                .toList();
    }

    private List<Long> aboveThreshold(List<Document> results) {
        return results.stream()
                .filter(result -> result.getScore() != null && result.getScore() >= vectorService.similarityThreshold())
                .map(result -> result.getMetadata().get("postId"))
                .filter(Objects::nonNull)
                .map(postId -> Long.parseLong(postId.toString()))
                .toList();
    }

    /* 검색어별 재정렬 결과를 잠시 보관 - 오래된 것부터 지우고, RERANK_CACHE_TTL이 지나면 다시 재정렬한다 */
    static final class RerankCache {
        private record Entry(List<Long> postIds, long expiresAt) {
        }

        private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > RERANK_CACHE_SIZE;
            }
        };
        private final java.util.function.LongSupplier clock;

        RerankCache(java.util.function.LongSupplier clock) {
            this.clock = clock;
        }

        synchronized List<Long> get(String query) {
            Entry entry = entries.get(query);
            if (entry == null || entry.expiresAt() <= clock.getAsLong()) {
                entries.remove(query);
                return null;
            }
            return entry.postIds();
        }

        synchronized void put(String query, List<Long> postIds) {
            entries.put(query, new Entry(List.copyOf(postIds), clock.getAsLong() + RERANK_CACHE_TTL.toMillis()));
        }
    }
}
