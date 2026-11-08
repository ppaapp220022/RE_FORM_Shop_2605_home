package com.re_form_shop_2605.eval.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.entity.trade.Post;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 의미 검색 eval 데이터셋
 *  - posts.jsonl  : 검색 대상 판매글 (PostVectorService.savePostVector로 임베딩)
 *  - queries.jsonl: 검색어와 정답 postId 목록
 *    tag      : exact(정확한 팀명) / alias(별칭) / player(선수) / describe(설명형) / typo(오타)
 *               / english(영문) / category(종목·리그) / none(정답 없음)
 *    relevant : 검색 의도에 맞는 판매글 전부. 비어 있으면 아무것도 나오지 않아야 한다
 *  - 데이터셋은 -Peval.search.dataset=prod 로 고른다 (기본 = 직접 만든 34개 검색어)
 *    prod : 운영 판매중 글 1000건(2026-11-08 공개 API 기준). 팀 12개 × 같은 본문 80여 건씩이라
 *           벡터 상위 N건을 한 팀이 다 차지하는 경우를 재현한다
 * ─────────────────────────────────────────────────────
 */
public record SearchEvalCase(String id, String tag, String query, List<Long> relevant) {

    private static final String DIR = "default".equals(dataset()) ? "/eval/search" : "/eval/search/" + dataset();
    public static final String POSTS = DIR + "/posts.jsonl";
    public static final String QUERIES = DIR + "/queries.jsonl";

    public static String dataset() {
        return System.getProperty("eval.search.dataset", "default");
    }

    public boolean answerable() {
        return !relevant.isEmpty();
    }

    public static List<SearchEvalCase> loadQueries() {
        return readLines(QUERIES, node -> {
            List<Long> relevant = new ArrayList<>();
            node.get("relevant").forEach(v -> relevant.add(v.asLong()));
            return new SearchEvalCase(node.get("id").asText(), node.get("tag").asText(),
                    node.get("query").asText(), relevant);
        });
    }

    public static List<Post> loadPosts() {
        return readLines(POSTS, node -> Post.builder()
                .postId(node.get("id").asLong())
                .sport(Sport.valueOf(node.get("sport").asText()))
                .team(node.get("team").asText())
                .uniformName(node.get("uniformName").asText())
                .title(node.get("title").asText())
                .content(node.get("content").asText())
                .price(node.get("price").asInt())
                .build());
    }

    private static <T> List<T> readLines(String resource, Function<JsonNode, T> mapper) {
        ObjectMapper objectMapper = new ObjectMapper();
        List<T> values = new ArrayList<>();

        try (InputStream in = SearchEvalCase.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("데이터셋을 찾을 수 없습니다: " + resource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    values.add(mapper.apply(objectMapper.readTree(line)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return values;
    }
}
