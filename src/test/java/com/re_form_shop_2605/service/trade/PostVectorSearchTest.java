package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.entity.trade.Post;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import javax.sql.DataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        return new PostVectorService(provider(vectorStore), new StaticListableBeanFactory().getBeanProvider(DataSource.class), MODEL);
    }

    private static PostSearchService searchService(VectorStore vectorStore) {
        return new PostSearchService(null, null, provider(vectorStore), MODEL);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<VectorStore> provider(VectorStore vectorStore) {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vectorStore);
        return provider;
    }

    private static Post post(long postId) {
        return Post.builder().postId(postId).title("KIA 타이거즈 홈 유니폼").sport(Sport.BASEBALL)
                .team("KIA 타이거즈").uniformName("2024 홈").content("사이즈 100").price(50000).build();
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
                .containsEntry("embeddingModel", MODEL);
        assertThat(saved.getText()).contains("KIA 타이거즈", "기아");
    }

    @Test
    void savePostVector_저장_실패는_예외_없이_false() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new RuntimeException("embedding down")).when(vectorStore).add(anyList());

        assertThat(vectorService(vectorStore).savePostVector(post(1L))).isFalse();
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
        assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(PostSearchService.SIMILARITY_THRESHOLD);
        assertThat(captor.getValue().getTopK()).isEqualTo(PostSearchService.SIMILARITY_TOP_K);
        // 다른 모델로 만든 벡터와 비교하지 않도록 현재 모델 벡터만 고른다
        assertThat(captor.getValue().getFilterExpression())
                .isEqualTo(new FilterExpressionTextParser().parse(PostVectorService.modelFilter(MODEL)));
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
}
