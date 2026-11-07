package com.re_form_shop_2605.eval.search;

import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.service.trade.PostSearchService;
import com.re_form_shop_2605.service.trade.PostVectorService;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 의미 검색(PostSearchService) 품질 eval
 *  - 판매글을 운영과 같은 PostVectorService.savePostVector로 임베딩해 메모리 벡터 저장소(SimpleVectorStore)에 넣고
 *    PostSearchService.findSimilarPostIds로 검색한다. 임베딩 모델·topK·유사도 기준값은 운영 코드 그대로
 *    (PGVector COSINE_DISTANCE의 score도 1 - 거리 = 코사인 유사도라 기준값 의미가 같다)
 *  - 두 가지 순위를 채점
 *    vector : 벡터 검색 결과만
 *    hybrid : 운영 search()와 같은 순서 - 벡터 결과 뒤에 키워드 LIKE(제목·유니폼명·팀명, 대소문자 무시) 결과를 최신순으로 붙임
 *  - 지표 (정답이 있는 검색어 평균)
 *    Recall@K : 상위 K개 안의 정답 수 / min(정답 수, K)
 *    MRR      : 첫 정답 순위의 역수 (없으면 0)
 *    통과     : 정답이 있으면 hybrid 상위 5개 안에 정답이 하나 이상, 정답이 없으면 hybrid 결과가 0건
 *  - 유사도 기준값 진단: 기준값 없이 전체 순위를 받아, 기준값별 Recall@10과 정답 없는 검색어의 오탐 건수를 비교
 *  - 기준치는 -Peval.search.minRecallAt10=0.8 처럼 넘겼을 때만 검사
 * ─────────────────────────────────────────────────────
 */
@Log4j2
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SemanticSearchEvalTest {

    private static final double[] THRESHOLDS = {0.0, 0.70, 0.75, 0.78, 0.80, 0.82, 0.84, 0.86, 0.88};

    /* scores: 기준값 없이 받은 전체 순위의 postId → 유사도 (진단용) */
    record Row(SearchEvalCase evalCase, List<Long> vector, List<Long> hybrid, Map<Long, Double> scores) {

        boolean passed() {
            if (!evalCase.answerable()) {
                return hybrid.isEmpty();
            }
            int rank = firstRank(hybrid, evalCase.relevant());
            return rank > 0 && rank <= 5;
        }
    }

    @Test
    void semanticSearchQuality() {
        // 운영과 같은 기본 임베딩 모델 (application.properties에 모델 지정 없음)
        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel();
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        ObjectProvider<VectorStore> provider = new StaticListableBeanFactory(Map.of("vectorStore", vectorStore))
                .getBeanProvider(VectorStore.class);

        List<Post> posts = SearchEvalCase.loadPosts();
        PostVectorService vectorService = new PostVectorService(provider);
        posts.forEach(vectorService::savePostVector);
        // savePostVector는 저장 실패를 로그로만 남기므로 실제로 다 들어갔는지 확인한다
        int stored = vectorStore.similaritySearch(SearchRequest.builder().query("유니폼").topK(posts.size()).build()).size();
        assertEquals(posts.size(), stored, "임베딩 저장에 실패한 판매글이 있습니다. 다시 실행해주세요.");

        PostSearchService searchService = new PostSearchService(null, null, provider);
        List<Row> rows = new ArrayList<>();
        for (SearchEvalCase evalCase : SearchEvalCase.loadQueries()) {
            List<Long> vector = searchService.findSimilarPostIds(evalCase.query());
            rows.add(new Row(evalCase, vector, hybrid(vector, evalCase.query(), posts),
                    allScores(vectorStore, evalCase.query(), posts.size())));
        }

        String summary = summarize(rows, embeddingModel.getOptions().getModel());
        Path path = write(rows, posts, summary);
        log.info("[Eval] {}", summary);
        log.info("[Eval] report={}", path);

        String minRecall = System.getProperty("eval.search.minRecallAt10");
        if (minRecall != null) {
            double recall = mean(answerable(rows), r -> recallAt(r.hybrid(), r.evalCase().relevant(), 10));
            assertTrue(recall >= Double.parseDouble(minRecall),
                    "eval.search.minRecallAt10=" + minRecall + " 미달: " + summary);
        }
    }

    /* PostSearchService.search()의 1)~4) 순서를 재현 - 키워드 검색은 PostMapper.findPostsByCondition의 LIKE 조건 (기본 정렬 = 최신순) */
    private static List<Long> hybrid(List<Long> vector, String query, List<Post> posts) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>(vector);
        String keyword = query.toLowerCase(Locale.ROOT);
        posts.stream()
                .filter(p -> p.getTitle().toLowerCase(Locale.ROOT).contains(keyword)
                        || p.getUniformName().toLowerCase(Locale.ROOT).contains(keyword)
                        || p.getTeam().toLowerCase(Locale.ROOT).contains(keyword))
                .sorted(Comparator.comparing(Post::getPostId).reversed())
                .forEach(p -> ids.add(p.getPostId()));
        return new ArrayList<>(ids);
    }

    private static Map<Long, Double> allScores(VectorStore vectorStore, String query, int size) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (Document document : vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(size).build())) {
            scores.put(Long.parseLong(document.getMetadata().get("postId").toString()), document.getScore());
        }
        return scores;
    }

    static int firstRank(List<Long> ranked, List<Long> relevant) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    static double recallAt(List<Long> ranked, List<Long> relevant, int k) {
        long hits = ranked.stream().limit(k).filter(relevant::contains).count();
        return (double) hits / Math.min(relevant.size(), k);
    }

    static double reciprocalRank(List<Long> ranked, List<Long> relevant) {
        int rank = firstRank(ranked, relevant);
        return rank == 0 ? 0.0 : 1.0 / rank;
    }

    private static List<Long> aboveThreshold(Row row, double threshold) {
        return row.scores().entrySet().stream().filter(e -> e.getValue() >= threshold).map(Map.Entry::getKey).toList();
    }

    private static List<Row> answerable(List<Row> rows) {
        return rows.stream().filter(r -> r.evalCase().answerable()).toList();
    }

    private static List<Row> unanswerable(List<Row> rows) {
        return rows.stream().filter(r -> !r.evalCase().answerable()).toList();
    }

    private static double mean(List<Row> rows, ToDoubleFunction<Row> metric) {
        return rows.stream().mapToDouble(metric).average().orElse(0.0);
    }

    private static double passRate(List<Row> rows) {
        return rows.isEmpty() ? 0.0 : (double) rows.stream().filter(Row::passed).count() / rows.size();
    }

    private static String summarize(List<Row> rows, String model) {
        List<Row> answerable = answerable(rows);
        return String.format(Locale.ROOT,
                "Search eval | model=%s queries=%d passRate=%.3f hybrid R@5=%.3f R@10=%.3f MRR=%.3f"
                        + " | vector R@5=%.3f R@10=%.3f MRR=%.3f | noneFalsePositives=%d",
                model, rows.size(), passRate(rows),
                mean(answerable, r -> recallAt(r.hybrid(), r.evalCase().relevant(), 5)),
                mean(answerable, r -> recallAt(r.hybrid(), r.evalCase().relevant(), 10)),
                mean(answerable, r -> reciprocalRank(r.hybrid(), r.evalCase().relevant())),
                mean(answerable, r -> recallAt(r.vector(), r.evalCase().relevant(), 5)),
                mean(answerable, r -> recallAt(r.vector(), r.evalCase().relevant(), 10)),
                mean(answerable, r -> reciprocalRank(r.vector(), r.evalCase().relevant())),
                unanswerable(rows).stream().mapToInt(r -> r.hybrid().size()).sum());
    }

    private static Path write(List<Row> rows, List<Post> posts, String summary) {
        Map<Long, String> titles = new LinkedHashMap<>();
        posts.forEach(p -> titles.put(p.getPostId(), p.getTitle()));

        StringBuilder md = new StringBuilder("# Search eval - 의미 검색\n\n")
                .append("`").append(summary).append("`\n\n")
                .append("## 태그별 지표 (hybrid)\n\n| tag | 통과 / 전체 | R@5 | R@10 | MRR |\n|---|---|---|---|---|\n");
        Map<String, List<Row>> byTag = new LinkedHashMap<>();
        rows.forEach(r -> byTag.computeIfAbsent(r.evalCase().tag(), k -> new ArrayList<>()).add(r));
        byTag.forEach((tag, tagRows) -> {
            List<Row> answerable = answerable(tagRows);
            md.append("| ").append(tag)
                    .append(" | ").append(tagRows.stream().filter(Row::passed).count()).append(" / ").append(tagRows.size());
            if (answerable.isEmpty()) {
                md.append(" | - | - | - |\n");
            } else {
                md.append(String.format(Locale.ROOT, " | %.2f | %.2f | %.2f |\n",
                        mean(answerable, r -> recallAt(r.hybrid(), r.evalCase().relevant(), 5)),
                        mean(answerable, r -> recallAt(r.hybrid(), r.evalCase().relevant(), 10)),
                        mean(answerable, r -> reciprocalRank(r.hybrid(), r.evalCase().relevant()))));
            }
        });

        md.append("\n## 유사도 기준값별 비교 (vector)\n\n")
                .append("운영 기준값은 ").append(PostSearchService.SIMILARITY_THRESHOLD)
                .append(". 오탐 = 정답 없는 검색어에 나온 결과 수 합계, 평균 결과 수 = 정답 있는 검색어 기준\n\n")
                .append("| 기준값 | R@10 | MRR | 오탐 | 평균 결과 수 |\n|---|---|---|---|---|\n");
        for (double threshold : THRESHOLDS) {
            List<Row> answerable = answerable(rows);
            md.append(String.format(Locale.ROOT, "| %.2f | %.3f | %.3f | %d | %.1f |\n", threshold,
                    mean(answerable, r -> recallAt(aboveThreshold(r, threshold), r.evalCase().relevant(), 10)),
                    mean(answerable, r -> reciprocalRank(aboveThreshold(r, threshold), r.evalCase().relevant())),
                    unanswerable(rows).stream().mapToInt(r -> aboveThreshold(r, threshold).size()).sum(),
                    mean(answerable, r -> aboveThreshold(r, threshold).size())));
        }

        md.append("\n## 실패 검색어\n\n| id | 검색어 | 정답 | 첫 정답 순위 (hybrid / vector) | 정답 최고 유사도 | hybrid 상위 3개 (유사도) |\n|---|---|---|---|---|---|\n");
        rows.stream().filter(r -> !r.passed()).forEach(r -> md.append(detail(r, titles)));
        md.append("\n## 통과 검색어\n\n| id | 검색어 | 정답 | 첫 정답 순위 (hybrid / vector) | 정답 최고 유사도 | hybrid 상위 3개 (유사도) |\n|---|---|---|---|---|---|\n");
        rows.stream().filter(Row::passed).forEach(r -> md.append(detail(r, titles)));

        try {
            Path dir = Path.of(System.getProperty("eval.reportDir", "build/reports/eval"));
            Files.createDirectories(dir);
            Path file = dir.resolve("semantic-search.md");
            Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String detail(Row r, Map<Long, String> titles) {
        List<Long> relevant = r.evalCase().relevant();
        String bestRelevant = relevant.stream().map(r.scores()::get).filter(s -> s != null)
                .max(Double::compare).map(s -> String.format(Locale.ROOT, "%.3f", s)).orElse("-");
        List<String> top = r.hybrid().stream().limit(3).map(id -> String.format(Locale.ROOT, "%d %s (%.3f)",
                id, titles.get(id), r.scores().getOrDefault(id, 0.0))).toList();
        return "| " + r.evalCase().id()
                + " | " + r.evalCase().query()
                + " | " + relevant
                + " | " + rankText(r.hybrid(), relevant) + " / " + rankText(r.vector(), relevant)
                + " | " + bestRelevant
                + " | " + String.join("<br>", top).replace("|", "\\|")
                + " |\n";
    }

    private static String rankText(List<Long> ranked, List<Long> relevant) {
        int rank = firstRank(ranked, relevant);
        return rank == 0 ? "-" : String.valueOf(rank);
    }
}
