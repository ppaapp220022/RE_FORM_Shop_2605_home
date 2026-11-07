package com.re_form_shop_2605.service.trade;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 검색 재정렬(PostSearchReranker) 단위 테스트 - 채팅 모델은 mock (품질은 SemanticSearchEvalTest)
 * ─────────────────────────────────────────────────────
 */
class PostSearchRerankerTest {

    private static final List<PostSearchReranker.Candidate> CANDIDATES = List.of(
            new PostSearchReranker.Candidate(30L, "리버풀 홈 저지", "SOCCER", "리버풀"),
            new PostSearchReranker.Candidate(10L, "수원삼성 홈 유니폼", "SOCCER", "수원 삼성 블루윙즈"),
            new PostSearchReranker.Candidate(20L, "울산 HD 홈", "SOCCER", "울산 HD"));

    private static PostSearchReranker reranker(String reply) {
        ChatModel chatModel = mock(ChatModel.class);
        // ChatClient가 기본 옵션을 복사해 쓰므로 null이면 호출 전에 실패한다
        when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(reply)))));
        return new PostSearchReranker(ChatClient.builder(chatModel));
    }

    @Test
    void rerank_고른_번호를_postId로_순서대로() {
        assertThat(reranker("{\"relevant\": [3, 2]}").rerank("파란색 축구 저지", CANDIDATES)).contains(List.of(20L, 10L));
    }

    @Test
    void rerank_빈_목록이면_결과_없음() {
        assertThat(reranker("```json\n{\"relevant\": []}\n```").rerank("노트북 팝니다", CANDIDATES)).contains(List.of());
    }

    @Test
    void rerank_형식이_틀리거나_호출이_실패하면_빈_Optional() {
        assertThat(reranker("관련 있는 글은 2번입니다").rerank("수원", CANDIDATES)).isEmpty();

        ChatModel failing = mock(ChatModel.class);
        when(failing.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        when(failing.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        assertThat(new PostSearchReranker(ChatClient.builder(failing)).rerank("수원", CANDIDATES)).isEmpty();
    }

    @Test
    void rerank_후보가_없으면_호출하지_않음() {
        ChatModel chatModel = mock(ChatModel.class);
        assertThat(new PostSearchReranker(ChatClient.builder(chatModel)).rerank("수원", List.of())).contains(List.of());
        org.mockito.Mockito.verifyNoInteractions(chatModel);
    }

    @Test
    void parse_범위_밖_번호와_중복은_버림() throws Exception {
        assertThat(PostSearchReranker.parse("{\"relevant\": [2, 9, 0, 2, 1]}", CANDIDATES)).containsExactly(10L, 30L);
        assertThatThrownBy(() -> PostSearchReranker.parse("{\"ids\": [1]}", CANDIDATES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_번호가_전부_범위_밖이면_결과_없음이_아니라_실패() {
        // 번호 대신 postId를 돌려준 경우
        assertThatThrownBy(() -> PostSearchReranker.parse("{\"relevant\": [770, 660]}", CANDIDATES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reranker("{\"relevant\": [770]}").rerank("수원", CANDIDATES)).isEmpty();
    }

    @Test
    void rerank_시간_안에_답이_없으면_빈_Optional() {
        ChatModel slow = mock(ChatModel.class);
        when(slow.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        when(slow.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(2000);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"relevant\": [1]}"))));
        });
        PostSearchReranker reranker = new PostSearchReranker(ChatClient.builder(slow), Duration.ofMillis(100));

        long started = System.nanoTime();
        assertThat(reranker.rerank("수원", CANDIDATES)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void userMessage_번호와_종목_팀을_붙여_보냄() {
        assertThat(PostSearchReranker.userMessage(" 파란색 축구 저지 ", CANDIDATES))
                .startsWith("검색어: 파란색 축구 저지\n")
                .contains("1. 리버풀 홈 저지 | 종목: SOCCER | 팀: 리버풀\n")
                .contains("3. 울산 HD 홈 | 종목: SOCCER | 팀: 울산 HD\n");
    }
}
