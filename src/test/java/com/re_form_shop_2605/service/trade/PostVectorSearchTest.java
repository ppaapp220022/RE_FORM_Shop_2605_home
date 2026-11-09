package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.dto.common.PageResponse;
import com.re_form_shop_2605.dto.trade.PostCardDTO;
import com.re_form_shop_2605.entity.Enum.PostStatus;
import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.mapper.trade.PostMapper;
import com.re_form_shop_2605.entity.trade.Post;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 벡터 저장(PostVectorService)·벡터 검색(PostSearchService.findSimilarPostIds) 단위 테스트
 *  - 실제 임베딩 없이 VectorStore를 mock으로 대체해 분기만 검증한다 (품질은 SemanticSearchEvalTest)
 * ─────────────────────────────────────────────────────
 */
class PostVectorSearchTest {

    private static final String MODEL = "text-embedding-3-small";

    private static PostVectorService vectorService(VectorStore vectorStore) {
        return new PostVectorService(provider(vectorStore), new StaticListableBeanFactory().getBeanProvider(DataSource.class),
                new StaticListableBeanFactory().getBeanProvider(EmbeddingModel.class), MODEL);
    }

    private static PostSearchService searchService(VectorStore vectorStore) {
        return new PostSearchService(null, null, provider(vectorStore), vectorService(vectorStore), MODEL, null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<VectorStore> provider(VectorStore vectorStore) {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vectorStore);
        return provider;
    }

    private static Post post(long postId) {
        return Post.builder().postId(postId).title("KIA 타이거즈 홈 유니폼").sport(Sport.BASEBALL)
                .team("KIA 타이거즈").uniformName("2024 홈").content("사이즈 100").price(50000).status(PostStatus.ON_SALE).build();
    }

    private static Document result(Object postId, double score) {
        Map<String, Object> metadata = postId == null ? Map.of() : Map.of("postId", postId.toString());
        return Document.builder().text("t").metadata(metadata).score(score).build();
    }

    @Test
    void savePostVector_같은_postId는_고정_id로_덮어쓰고_지우지_않음() {
        VectorStore vectorStore = mock(VectorStore.class);

        assertThat(vectorService(vectorStore).savePostVector(post(7L))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        // 지우고 넣으면 임베딩 실패 시 이전 벡터까지 사라지므로 delete를 부르지 않는다
        verify(vectorStore, never()).delete(anyString());
        Document saved = captor.getValue().get(0);
        assertThat(saved.getId()).isEqualTo(PostVectorService.documentId(7L));
        assertThat(saved.getId()).isNotEqualTo(PostVectorService.documentId(8L));
        assertThat(saved.getMetadata()).containsEntry("postId", "7").containsEntry("sport", "BASEBALL")
                .containsEntry("status", "ON_SALE").containsEntry("embeddingModel", MODEL);
        // 판매 상태·가격은 수시로 바뀌므로 임베딩하는 글에는 넣지 않는다 (메타데이터에는 남고, 나머지는 예전과 같이 들어간다)
        assertThat(saved.getMetadata()).containsKey("price");
        assertThat(saved.getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
                .doesNotContain("ON_SALE").doesNotContain("price:").contains("postId: 7");
        assertThat(saved.getText()).contains("KIA 타이거즈", "기아");
    }

    @Test
    void savePostVector_저장_실패는_예외_없이_false() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new RuntimeException("embedding down")).when(vectorStore).add(anyList());

        assertThat(vectorService(vectorStore).savePostVector(post(1L))).isFalse();
    }

    @Test
    void savePostVectors_묶음_실패면_글마다_다시_시도해_실패만_센다() {
        VectorStore vectorStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            if (documents.size() > 1 || documents.get(0).getMetadata().get("postId").equals("2")) {
                throw new RuntimeException("batch", new IllegalStateException("token limit"));
            }
            return null;
        }).when(vectorStore).add(anyList());

        PostVectorService.BatchResult result = vectorService(vectorStore).savePostVectors(List.of(post(1L), post(2L), post(3L)));

        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.error()).isEqualTo("IllegalStateException: token limit");
    }

    @Test
    void savePostVectors_같은_원인으로_연달아_실패하면_나머지는_건너뜀() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new RuntimeException("down", new IllegalStateException("invalid key"))).when(vectorStore).add(anyList());

        PostVectorService.BatchResult result = vectorService(vectorStore)
                .savePostVectors(List.of(post(1L), post(2L), post(3L), post(4L), post(5L)));

        assertThat(result.saved()).isZero();
        assertThat(result.failed()).isEqualTo(5);
        assertThat(result.aborted()).isTrue();
        // 묶음 1번 + 글마다 3번까지만 시도
        verify(vectorStore, times(1 + PostVectorService.MAX_CONSECUTIVE_FAILURES)).add(anyList());
    }

    @Test
    void savePostVectors_원인이_다른_연속_실패는_글_내용_문제로_보고_계속_시도() {
        VectorStore vectorStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            String postId = (String) documents.get(0).getMetadata().get("postId");
            if (documents.size() > 1 || !postId.equals("5")) {
                throw new IllegalArgumentException("bad post " + postId);
            }
            return null;
        }).when(vectorStore).add(anyList());

        PostVectorService.BatchResult result = vectorService(vectorStore)
                .savePostVectors(List.of(post(1L), post(2L), post(3L), post(4L), post(5L)));

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(4);
        assertThat(result.aborted()).isFalse();
        verify(vectorStore, times(1 + 5)).add(anyList());
    }

    @Test
    void savePostVectors_성공이_끼면_연속_실패를_처음부터_센다() {
        VectorStore vectorStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            // 실패·실패·성공·실패·실패·성공 - 같은 원인이지만 3건 연속은 없다
            if (documents.size() > 1 || !List.of("3", "6").contains(documents.get(0).getMetadata().get("postId"))) {
                throw new IllegalStateException("rate limit");
            }
            return null;
        }).when(vectorStore).add(anyList());

        PostVectorService.BatchResult result = vectorService(vectorStore)
                .savePostVectors(List.of(post(1L), post(2L), post(3L), post(4L), post(5L), post(6L)));

        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(4);
        assertThat(result.aborted()).isFalse();
        verify(vectorStore, times(1 + 6)).add(anyList());
    }

    @Test
    void savePostVectors_한_번에_저장() {
        VectorStore vectorStore = mock(VectorStore.class);

        PostVectorService.BatchResult result = vectorService(vectorStore).savePostVectors(List.of(post(1L), post(2L)));

        assertThat(result).isEqualTo(new PostVectorService.BatchResult(2, 0, null));
        verify(vectorStore).add(argThat(documents -> documents.size() == 2));
    }

    @Test
    void savePostVector_긴_본문은_앞부분만_임베딩() {
        VectorStore vectorStore = mock(VectorStore.class);
        Post longPost = Post.builder().postId(9L).title("제목").sport(Sport.SOCCER).team("팀").uniformName("u")
                .content("가".repeat(PostVectorService.MAX_CONTENT_LENGTH) + "끝부분").price(1).build();

        vectorService(vectorStore).savePostVector(longPost);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue().get(0).getText()).doesNotContain("끝부분")
                .contains("가".repeat(PostVectorService.MAX_CONTENT_LENGTH));
    }

    @Test
    void checkSearch_운영_기준값_이상_건수와_최고_점수() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(result(1L, 0.45), result(2L, 0.31), result(3L, 0.29)));

        PostVectorService.SearchCheck check = vectorService(vectorStore).checkSearch("유니폼", 5, 0.30);

        assertThat(check).isEqualTo(new PostVectorService.SearchCheck(2, 0.45));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression())
                .isEqualTo(new FilterExpressionTextParser().parse(PostVectorService.modelFilter(MODEL)));
    }

    @Test
    void savePostVector_저장소_비활성이면_false() {
        assertThat(vectorService(null).savePostVector(post(1L))).isFalse();
    }

    @Test
    void findSimilarPostIds_유사도_순서를_유지하고_postId_없는_결과는_건너뜀() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(result(3L, 0.6), result(null, 0.5), result(1L, 0.4)));

        List<Long> ids = searchService(vectorStore).findSimilarPostIds("기아 유니폼");

        assertThat(ids).containsExactly(3L, 1L);
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(PostVectorService.DEFAULT_SIMILARITY_THRESHOLD);
        assertThat(captor.getValue().getTopK()).isEqualTo(PostSearchService.SIMILARITY_TOP_K);
        // 다른 모델로 만든 벡터와 비교하지 않도록 현재 모델 벡터만, 판매중인 글만 고른다
        assertThat(captor.getValue().getFilterExpression())
                .isEqualTo(new FilterExpressionTextParser().parse(PostVectorService.onSaleFilter(MODEL)));
    }

    @Test
    void deleteVectorsOfOtherModels_PG_비활성이면_0() {
        assertThat(vectorService(mock(VectorStore.class)).deleteVectorsOfOtherModels()).isZero();
    }

    @Test
    void findSimilarPostIds_빈_검색어는_검색하지_않음() {
        VectorStore vectorStore = mock(VectorStore.class);
        PostSearchService service = searchService(vectorStore);

        assertThat(service.findSimilarPostIds("  ")).isEmpty();
        assertThat(service.findSimilarPostIds(null)).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void findSimilarPostIds_저장소_비활성이나_검색_실패면_빈_목록() {
        assertThat(searchService(null).findSimilarPostIds("기아")).isEmpty();

        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("db down"));
        assertThat(searchService(vectorStore).findSimilarPostIds("기아")).isEmpty();
    }

    private static Document titled(long postId, double score, String title, String team) {
        return Document.builder().text("t")
                .metadata(Map.of("postId", String.valueOf(postId), "title", title, "team", team, "sport", "SOCCER"))
                .score(score).build();
    }

    private static PostSearchService rerankingService(VectorStore vectorStore, PostSearchReranker reranker) {
        return new PostSearchService(null, null, provider(vectorStore), vectorService(vectorStore), MODEL, reranker);
    }

    @Test
    void findSimilarPostIds_재정렬하면_후보를_넓혀_모델이_고른_글만_순서대로() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                titled(3L, 0.40, "리버풀 홈", "리버풀"), titled(1L, 0.35, "맨유 홈", "맨유"), titled(2L, 0.27, "수원 홈", "수원")));
        PostSearchReranker reranker = mock(PostSearchReranker.class);
        when(reranker.rerank(anyString(), anyList())).thenReturn(Optional.of(List.of(2L, 3L)));

        List<Long> ids = rerankingService(vectorStore, reranker).findSimilarPostIds("파란색 축구 저지");

        assertThat(ids).containsExactly(2L, 3L);
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(PostVectorService.DEFAULT_RERANK_CANDIDATE_THRESHOLD);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PostSearchReranker.Candidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(anyString(), candidates.capture());
        assertThat(candidates.getValue()).extracting(PostSearchReranker.Candidate::postId).containsExactly(3L, 1L, 2L);
    }

    @Test
    void findSimilarPostIds_제목_팀이_같은_글은_한_후보로_보내고_고르면_모두_돌려줌() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                titled(5L, 0.50, "KIA 홈 유니폼", "KIA"), titled(6L, 0.50, "KIA 홈 유니폼", "KIA"),
                titled(7L, 0.45, "LG 원정", "LG"), titled(8L, 0.40, "KIA 홈 유니폼", "KIA")));
        PostSearchReranker reranker = mock(PostSearchReranker.class);
        when(reranker.rerank(anyString(), anyList())).thenReturn(Optional.of(List.of(5L)));

        List<Long> ids = rerankingService(vectorStore, reranker).findSimilarPostIds("기아 유니폼");

        assertThat(ids).containsExactly(5L, 6L, 8L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PostSearchReranker.Candidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(anyString(), candidates.capture());
        assertThat(candidates.getValue()).extracting(PostSearchReranker.Candidate::postId).containsExactly(5L, 7L);
    }

    @Test
    void findSimilarPostIds_재정렬은_상위_후보만_보냄() {
        VectorStore vectorStore = mock(VectorStore.class);
        List<Document> results = java.util.stream.LongStream.rangeClosed(1, PostSearchService.RERANK_CANDIDATES + 5)
                .mapToObj(id -> titled(id, 0.5, "글 " + id, "팀")).toList();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(results);
        PostSearchReranker reranker = mock(PostSearchReranker.class);
        when(reranker.rerank(anyString(), anyList())).thenReturn(Optional.of(List.of()));

        rerankingService(vectorStore, reranker).findSimilarPostIds("유니폼");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PostSearchReranker.Candidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(anyString(), candidates.capture());
        assertThat(candidates.getValue()).hasSize(PostSearchService.RERANK_CANDIDATES);
    }

    @Test
    void findSimilarPostIds_같은_검색어는_재정렬_결과를_다시_씀() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(titled(1L, 0.4, "a", "x"), titled(2L, 0.4, "b", "y")));
        PostSearchReranker reranker = mock(PostSearchReranker.class);
        when(reranker.rerank(anyString(), anyList())).thenReturn(Optional.of(List.of(2L)), Optional.of(List.of(1L)));
        PostSearchService service = rerankingService(vectorStore, reranker);

        assertThat(service.findSimilarPostIds("수원 ")).containsExactly(2L);
        // 2페이지 요청 - 모델을 다시 부르지 않아 1페이지와 같은 순서
        assertThat(service.findSimilarPostIds("수원")).containsExactly(2L);
        verify(reranker, times(1)).rerank(anyString(), anyList());
    }

    @Test
    void findSimilarPostIds_재정렬이_실패하면_운영_기준값으로_거르고_캐시하지_않음() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                titled(3L, 0.40, "a", "x"), titled(1L, 0.31, "b", "y"), titled(2L, 0.27, "c", "z")));
        PostSearchReranker reranker = mock(PostSearchReranker.class);
        when(reranker.rerank(anyString(), anyList())).thenReturn(Optional.empty(), Optional.of(List.of(2L)));
        PostSearchService service = rerankingService(vectorStore, reranker);

        // 운영 기준값(0.30) 미만은 재정렬 후보로만 쓰고, 재정렬 없이 내보내지 않는다
        assertThat(service.findSimilarPostIds("기아")).containsExactly(3L, 1L);
        assertThat(service.findSimilarPostIds("기아")).containsExactly(2L);
    }

    @Test
    void rerankCache_시간이_지나면_버리고_가득_차면_오래된_것부터_지움() {
        long[] now = {0};
        PostSearchService.RerankCache cache = new PostSearchService.RerankCache(() -> now[0]);
        cache.put("a", List.of(1L));
        assertThat(cache.get("a")).containsExactly(1L);
        now[0] = PostSearchService.RERANK_CACHE_TTL.toMillis();
        assertThat(cache.get("a")).isNull();

        for (int i = 0; i <= PostSearchService.RERANK_CACHE_SIZE; i++) {
            cache.put("q" + i, List.of((long) i));
        }
        assertThat(cache.get("q0")).isNull();
        assertThat(cache.get("q" + PostSearchService.RERANK_CACHE_SIZE)).containsExactly((long) PostSearchService.RERANK_CACHE_SIZE);
    }

    @Test
    void findSimilarPostIds_같은_글이_많아도_다른_묶음이_후보에_들어감() {
        // 운영 더미 데이터처럼 같은 글이 수십 건씩 있으면, 상위 N건만 받아 묶을 때 한두 묶음만 남아 재정렬이 고를 게 없었다
        VectorStore vectorStore = mock(VectorStore.class);
        List<Document> results = new java.util.ArrayList<>();
        for (long id = 1; id <= 150; id++) {
            results.add(titled(id, 0.35, "KCC 홈 저지", "KCC"));
        }
        results.add(titled(200L, 0.26, "울산 HD 홈 유니폼", "울산 HD"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(results);
        PostSearchReranker reranker = mock(PostSearchReranker.class);
        when(reranker.rerank(anyString(), anyList())).thenReturn(Optional.of(List.of(200L)));

        List<Long> ids = rerankingService(vectorStore, reranker).findSimilarPostIds("파란색 축구 저지");

        assertThat(ids).containsExactly(200L);
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        // PG가 없으면(메모리 저장소) 전부 받아 묶는다
        assertThat(request.getValue().getTopK()).isEqualTo(PostVectorService.IN_MEMORY_TOP_K);
    }

    @Test
    void findCandidateGroups_묶음_SQL이_실패하면_상위_일부만_받아_묶음() throws SQLException {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(titled(1L, 0.4, "a", "x")));
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("pg down"));
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        PostVectorService service = new PostVectorService(provider(vectorStore),
                new StaticListableBeanFactory(Map.of("postgresDataSource", dataSource)).getBeanProvider(DataSource.class),
                new StaticListableBeanFactory(Map.of("embeddingModel", embeddingModel)).getBeanProvider(EmbeddingModel.class), MODEL);

        List<PostVectorService.CandidateGroup> groups = service.findCandidateGroups("기아", 0.25, 10);

        assertThat(groups).extracting(PostVectorService.CandidateGroup::postIds).containsExactly(List.of(1L));
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(PostVectorService.FALLBACK_TOP_K);
        // 대체 경로도 판매중 글로 먼저 거른다 - 판매 완료 글이 상위 FALLBACK_TOP_K건을 차지하지 않도록
        assertThat(request.getValue().getFilterExpression())
                .isEqualTo(new FilterExpressionTextParser().parse(PostVectorService.onSaleFilter(MODEL)));
    }

    @Test
    void search_화면에_안_나오는_글을_빼고_페이지를_나눔() {
        // 판매 완료 글이 벡터 결과 앞쪽을 채우면, 거르지 않고 자를 때 1페이지가 비고 총 건수와 어긋났다
        VectorStore vectorStore = mock(VectorStore.class);
        List<Document> results = java.util.stream.LongStream.rangeClosed(1, 12)
                .mapToObj(id -> result(id, 0.5)).toList();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(results);
        PostMapper postMapper = mock(PostMapper.class);
        when(postMapper.findPostsByCondition(anyString(), any(), any(), any(), any(), any(), any(), eq(0), eq(Integer.MAX_VALUE), any()))
                .thenReturn(List.of());
        when(postMapper.findVisiblePostIds(anyList(), any(), any(), any(), any(), any())).thenReturn(List.of(12L, 11L));
        PostSearchService service = new PostSearchService(postMapper, null, provider(vectorStore), vectorService(vectorStore), MODEL, null);

        PageResponse<PostCardDTO> page = service.search("T1", null, null, null, null, null, "latest", 1, 10, null);

        assertThat(page.totalElements()).isEqualTo(2);
        verify(postMapper).findPostsByIds(eq(List.of(11L, 12L)), any(), any(), any(), any(), any(), any());
    }

    @Test
    void findSimilarPostIds_재정렬이_실패하면_같은_묶음이라도_기준값_미만_글은_뺌() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                titled(1L, 0.32, "KIA 홈", "KIA"), titled(2L, 0.27, "KIA 홈", "KIA")));
        PostSearchReranker reranker = mock(PostSearchReranker.class);
        when(reranker.rerank(anyString(), anyList())).thenReturn(Optional.empty());

        assertThat(rerankingService(vectorStore, reranker).findSimilarPostIds("기아")).containsExactly(1L);
    }

    @Test
    void groupDocuments_종목이_다르면_다른_묶음() {
        Document soccer = titled(1L, 0.4, "서울 홈", "서울");
        Document basketball = Document.builder().text("t")
                .metadata(Map.of("postId", "2", "title", "서울 홈", "team", "서울", "sport", "BASKETBALL")).score(0.3).build();

        assertThat(PostVectorService.groupDocuments(List.of(soccer, basketball), 10)).hasSize(2);
    }

    @Test
    void savePostVectorAfterCommit_트랜잭션이_커밋된_뒤에만_저장() {
        VectorStore vectorStore = mock(VectorStore.class);
        PostVectorService service = vectorService(vectorStore);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.savePostVectorAfterCommit(post(7L));
            verify(vectorStore, never()).add(anyList());

            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        verify(vectorStore, timeout(2000)).add(argThat(documents -> documents.get(0).getMetadata().get("postId").equals("7")));
    }

    @Test
    void savePostVectorAfterCommit_트랜잭션_밖이면_바로_저장() {
        VectorStore vectorStore = mock(VectorStore.class);

        vectorService(vectorStore).savePostVectorAfterCommit(post(8L));

        verify(vectorStore, timeout(2000)).add(anyList());
    }

    @Test
    void 기준값은_프로퍼티로_바꿀_수_있음() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(result(1L, 0.5), result(2L, 0.35)));
        PostVectorService vectorService = new PostVectorService(provider(vectorStore),
                new StaticListableBeanFactory().getBeanProvider(DataSource.class),
                new StaticListableBeanFactory().getBeanProvider(EmbeddingModel.class), MODEL, 0.4, 0.3);

        List<Long> ids = new PostSearchService(null, null, provider(vectorStore), vectorService, MODEL, null).findSimilarPostIds("기아");

        assertThat(ids).containsExactly(1L);
    }

    @Test
    void candidateSummaryListsTeamSportAndScore() {
        List<PostVectorService.CandidateGroup> groups = List.of(
                new PostVectorService.CandidateGroup("T1 저지", "ESPORTS", "T1", List.of(1L, 2L), List.of(0.3731, 0.31)),
                new PostVectorService.CandidateGroup("젠지 저지", "ESPORTS", "젠지", List.of(3L), List.of(0.25)));

        assertThat(PostSearchService.candidateSummary(groups)).isEqualTo("[T1(ESPORTS) 0.373, 젠지(ESPORTS) 0.250]");
        assertThat(PostSearchService.candidateSummary(List.of())).isEqualTo("[]");
    }

    @Test
    void chosenGroupsFollowRerankedOrder() {
        PostVectorService.CandidateGroup t1 = new PostVectorService.CandidateGroup("T1 저지", "ESPORTS", "T1", List.of(1L, 2L), List.of(0.37, 0.31));
        PostVectorService.CandidateGroup gen = new PostVectorService.CandidateGroup("젠지 저지", "ESPORTS", "젠지", List.of(3L, 4L), List.of(0.25, 0.24));
        PostVectorService.CandidateGroup kia = new PostVectorService.CandidateGroup("KIA 유니폼", "BASEBALL", "KIA", List.of(5L), List.of(0.20));

        // 재정렬 결과는 고른 묶음의 글 전체가 모델 순서대로 펼쳐진 목록
        assertThat(PostSearchService.chosenGroups(List.of(t1, gen, kia), List.of(3L, 4L, 1L, 2L))).containsExactly(gen, t1);
        assertThat(PostSearchService.chosenGroups(List.of(t1, gen, kia), List.of())).isEmpty();
    }

    @Test
    void findCandidateGroups_판매중_글만_고르는_필터로_묶음() {
        // 운영처럼 판매 완료 글만 있는 묶음이 유사도 상위를 차지하지 않도록 벡터 메타데이터의 판매 상태로 먼저 거른다
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                titled(4L, 0.30, "T1 저지", "T1"), titled(5L, 0.29, "젠지 저지", "젠지")));

        assertThat(vectorService(vectorStore).findCandidateGroups("롤 팀 유니폼", 0.0, 2))
                .extracting(PostVectorService.CandidateGroup::team).containsExactly("T1", "젠지");

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getFilterExpression())
                .isEqualTo(new FilterExpressionTextParser().parse(PostVectorService.onSaleFilter(MODEL)));
    }

    /* PG 연결을 흉내 내 실행된 SQL과 인자를 확인한다 */
    private record FakePg(DataSource dataSource, Connection connection, PreparedStatement statement) {
        static FakePg create() throws SQLException {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeUpdate()).thenReturn(1);
            return new FakePg(dataSource, connection, statement);
        }

        PostVectorService service(VectorStore vectorStore) {
            return new PostVectorService(provider(vectorStore),
                    new StaticListableBeanFactory(Map.of("postgresDataSource", dataSource)).getBeanProvider(DataSource.class),
                    new StaticListableBeanFactory().getBeanProvider(EmbeddingModel.class), MODEL);
        }
    }

    @Test
    void onPostStatusChanged_문서_id와_상태를_배열로_넘겨_메타데이터만_바꿈() throws SQLException {
        FakePg pg = FakePg.create();
        VectorStore vectorStore = mock(VectorStore.class);

        pg.service(vectorStore).onPostStatusChanged(new com.re_form_shop_2605.entity.trade.PostStatusChangedEvent(7L, PostStatus.SOLD));

        verify(pg.statement(), timeout(2000)).executeUpdate();
        // 기본 키(문서 id)로 찾아 전체를 훑지 않는다
        verify(pg.connection()).prepareStatement(argThat(sql -> sql.contains("jsonb_set") && sql.contains("'{status}'")
                && sql.contains("v.id = s.id")));
        verify(pg.statement()).setString(1, "{" + PostVectorService.documentId(7L) + "}");
        verify(pg.statement()).setString(2, "{SOLD}");
        // 임베딩은 다시 만들지 않는다
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void syncStatuses_원본_상태로_갱신하고_원본에_없는_글은_DELETED_표시() throws SQLException {
        FakePg pg = FakePg.create();

        pg.service(mock(VectorStore.class)).syncStatuses(() -> Map.of(3L, PostStatus.ON_SALE));

        verify(pg.statement(), timeout(2000).times(2)).executeUpdate();
        verify(pg.connection()).prepareStatement(argThat(sql -> sql.contains("NOT (id = ANY(?::uuid[]))")));
        verify(pg.statement()).setString(1, "{" + PostVectorService.documentId(3L) + "}");
        verify(pg.statement()).setString(2, "{ON_SALE}");
        verify(pg.statement()).setString(1, "DELETED");
        verify(pg.statement()).setString(2, "{" + PostVectorService.documentId(3L) + "}");
        verify(pg.statement()).setString(3, "DELETED");
    }

    @Test
    void syncStatuses_원본이_비어_있으면_전부_DELETED로_표시하지_않음() throws SQLException, InterruptedException {
        FakePg pg = FakePg.create();
        PostVectorService service = pg.service(mock(VectorStore.class));

        service.syncStatuses(Map::of);
        service.shutdown();

        verify(pg.connection(), never()).prepareStatement(anyString());
    }

    @Test
    void syncStatuses_PG가_없으면_원본을_읽지_않음() throws InterruptedException {
        java.util.concurrent.atomic.AtomicBoolean read = new java.util.concurrent.atomic.AtomicBoolean();
        PostVectorService service = vectorService(mock(VectorStore.class));

        service.syncStatuses(() -> {
            read.set(true);
            return Map.of();
        });
        // 저장 스레드의 작업이 모두 끝날 때까지 기다린 뒤 확인한다
        service.shutdown();

        assertThat(read).isFalse();
    }

    @Test
    void 판매_상태가_실제로_바뀔_때만_벡터_반영_표시() {
        Post post = post(1L);

        post.changeStatus(PostStatus.ON_SALE);
        assertThat(post.consumeStatusChange()).isFalse();

        post.changeStatus(PostStatus.SOLD);
        assertThat(post.consumeStatusChange()).isTrue();
        assertThat(post.consumeStatusChange()).isFalse();

        post.markDeleted();
        assertThat(post.consumeStatusChange()).isTrue();
    }
}
