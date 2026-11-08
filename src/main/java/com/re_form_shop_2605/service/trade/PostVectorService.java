package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.entity.trade.Post;
import com.pgvector.PGvector;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.ai.document.Document;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * 작성자: 손민정
 * 작성일: 2026-05-13
 * 설명: 의미 기반 유사 상품 검색 - 벡터 저장소 설정
 */

@Log4j2
@Service
public class PostVectorService {
    private static final String VECTOR_TABLE = "vector_store"; // PgVectorStore 기본 테이블
    private static final String MODEL_KEY = "embeddingModel";
    static final int MAX_CONTENT_LENGTH = 1000;
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    // PG가 없을 때(메모리 벡터 저장소 - eval·테스트) 그룹을 빠짐없이 모으려고 전부 받아 묶는다
    static final int IN_MEMORY_TOP_K = 10_000;
    // 그룹 집계 SQL이 실패하면 이만큼만 받아 Java에서 묶는다 (예전 방식)
    static final int FALLBACK_TOP_K = 100;
    // text-embedding-3-small 기준값. 모델을 바꾸면 유사도 분포가 달라지므로 SemanticSearchEvalTest로 다시 정해
    // app.ai.search.* 프로퍼티로 넘긴다 (모델 설정 바로 옆)
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.30;
    // 재정렬할 때는 관련 없는 글을 모델이 걸러 주므로 기준값을 낮춰 후보를 넓힌다
    public static final double DEFAULT_RERANK_CANDIDATE_THRESHOLD = 0.25;

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<DataSource> postgresDataSourceProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final String embeddingModel;
    private final double similarityThreshold;
    private final double rerankCandidateThreshold;
    // 등록·수정 요청을 임베딩 호출(OpenAI)이 붙잡지 않도록 커밋 뒤 이 스레드에서 저장한다
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "post-embed");
        thread.setDaemon(true);
        return thread;
    });

    // 생성자가 둘이라 스프링이 고를 수 있도록 표시한다 (없으면 기본 생성자를 찾다 기동 실패)
    @Autowired
    public PostVectorService(ObjectProvider<VectorStore> vectorStoreProvider,
                             @Qualifier("postgresDataSource") ObjectProvider<DataSource> postgresDataSourceProvider,
                             ObjectProvider<EmbeddingModel> embeddingModelProvider,
                             @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String embeddingModel,
                             @Value("${app.ai.search.similarity-threshold:" + DEFAULT_SIMILARITY_THRESHOLD + "}") double similarityThreshold,
                             @Value("${app.ai.search.rerank-candidate-threshold:" + DEFAULT_RERANK_CANDIDATE_THRESHOLD + "}") double rerankCandidateThreshold) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.postgresDataSourceProvider = postgresDataSourceProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingModel = embeddingModel;
        this.similarityThreshold = similarityThreshold;
        this.rerankCandidateThreshold = rerankCandidateThreshold;
    }

    /* 기본 기준값 (단위 테스트·eval용) */
    public PostVectorService(ObjectProvider<VectorStore> vectorStoreProvider, ObjectProvider<DataSource> postgresDataSourceProvider,
                             ObjectProvider<EmbeddingModel> embeddingModelProvider, String embeddingModel) {
        this(vectorStoreProvider, postgresDataSourceProvider, embeddingModelProvider, embeddingModel,
                DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_RERANK_CANDIDATE_THRESHOLD);
    }

    /* 검색 결과로 내보낼 최소 유사도 */
    public double similarityThreshold() {
        return similarityThreshold;
    }

    /* 재정렬 후보로 보낼 최소 유사도 */
    public double rerankCandidateThreshold() {
        return rerankCandidateThreshold;
    }

    @PreDestroy
    void shutdown() {
        saveExecutor.shutdown();
    }

    /* 판매글 등록·수정 트랜잭션이 커밋된 뒤 다른 스레드에서 벡터 저장 */
    // 트랜잭션 안에서 임베딩을 호출하면 응답이 올 때까지 DB 커넥션을 잡고 있고, 롤백되면 벡터만 남는다
    //  - afterCommit 콜백도 아직 커넥션을 놓기 전이라 그 안에서 직접 부르지 않고 스레드로 넘긴다
    //  - 엔티티는 다른 스레드에서 지연 로딩되지 않도록 임베딩에 쓰는 값만 복사해 넘긴다
    //  - 트랜잭션 밖에서 부르면 바로 넘긴다
    public void savePostVectorAfterCommit(Post post) {
        Post copy = Post.builder()
                .postId(post.getPostId())
                .title(post.getTitle())
                .sport(post.getSport())
                .team(post.getTeam())
                .uniformName(post.getUniformName())
                .content(post.getContent())
                .price(post.getPrice())
                .build();
        Runnable save = () -> saveExecutor.execute(() -> savePostVector(copy));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    save.run();
                }
            });
        } else {
            save.run();
        }
    }

    /* 판매글 등록·수정 시 벡터 저장 */
    // 용도 : 게시글 내용 벡터 변환해 PGVector 저장
    //  - 문서 id를 postId로 고정해 다시 저장하면 덮어쓴다 (PGVector id 컬럼은 UUID, ON CONFLICT DO UPDATE)
    //    지우고 다시 넣지 않으므로 임베딩 호출이 실패해도 이전 벡터는 남는다
    //  - 어떤 모델로 만든 벡터인지 메타데이터에 남겨, 검색은 현재 모델 벡터끼리만 비교한다 (modelFilter)
    //  - 저장 실패는 판매글 등록을 막지 않도록 로그만 남기고 false 반환
    public boolean savePostVector(Post post) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("벡터 저장소가 비활성화되어 임베딩 저장을 건너뜁니다. postId={}", post.getPostId());
            return false;
        }
        return trySave(vectorStore, post) == null;
    }

    /* 한 건 저장 - 성공이면 null, 실패면 원인 메시지 */
    private String trySave(VectorStore vectorStore, Post post) {
        try {
            vectorStore.add(List.of(toDocument(post)));
            log.info("savePostVector : 벡터 저장소에 저장 완료  |  postId {}", post.getPostId());
            return null;
        } catch (Exception exception) {
            log.error("savePostVector : 벡터 저장 실패  |  postId {}", post.getPostId(), exception);
            return rootMessage(exception);
        }
    }

    /* 여러 판매글을 한 번에 저장 (재임베딩용) */
    // 임베딩 API를 묶음 단위로 호출해 글마다 호출하는 것보다 빠르다. 묶음 저장이 실패하면 글마다 다시 시도해 실패한 글만 걸러낸다
    //  - 글마다 다시 시도해도 같은 원인으로 연달아 실패하면 키 만료·저장소 장애 같은 전체 장애로 보고 나머지는 시도하지 않는다 (aborted)
    //    원인이 서로 다른 실패는 글 내용 문제로 보고 계속 시도한다 (인접한 문제 글 몇 개 때문에 정상 글을 매번 건너뛰지 않도록)
    public BatchResult savePostVectors(List<Post> posts) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return new BatchResult(0, posts.size(), "벡터 저장소가 비활성화되어 있습니다.", true);
        }
        try {
            vectorStore.add(posts.stream().map(this::toDocument).toList());
            return new BatchResult(posts.size(), 0, null);
        } catch (Exception exception) {
            log.warn("savePostVectors : 묶음 저장 실패, 글마다 다시 시도합니다  |  {}건", posts.size(), exception);
            int saved = 0, sameErrorStreak = 0;
            String lastError = null;
            for (Post post : posts) {
                String error = trySave(vectorStore, post);
                if (error == null) {
                    saved++;
                    sameErrorStreak = 0;
                    continue;
                }
                sameErrorStreak = error.equals(lastError) ? sameErrorStreak + 1 : 1;
                lastError = error;
                if (sameErrorStreak >= MAX_CONSECUTIVE_FAILURES) {
                    log.error("savePostVectors : 글마다 다시 시도해도 같은 원인으로 {}건 연속 실패해 나머지를 건너뜁니다  |  {}",
                            sameErrorStreak, error);
                    return new BatchResult(saved, posts.size() - saved, error, true);
                }
            }
            return new BatchResult(saved, posts.size() - saved, lastError != null ? lastError : rootMessage(exception));
        }
    }

    /* aborted: 전체 장애로 보고 묶음 도중에 멈췄는지 (재임베딩 작업은 이것이 연달아 나와야 중단한다) */
    public record BatchResult(int saved, int failed, String error, boolean aborted) {
        public BatchResult(int saved, int failed, String error) {
            this(saved, failed, error, false);
        }
    }

    /* 현재 모델 벡터로 실제 검색이 되는지 확인 (재임베딩 뒤 점검용) */
    // PostSearchService.findSimilarPostIds는 실패를 삼키고 빈 결과를 돌려주므로, 여기서는 예외를 그대로 던진다
    // 운영 검색과 같은 기준값을 넘는 건수와 최고 점수를 함께 돌려줘, 저장은 됐지만 점수가 낮아 안 나오는 경우도 보이게 한다
    public SearchCheck checkSearch(String query, int topK, double threshold) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("벡터 저장소가 비활성화되어 있습니다.");
        }
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(modelFilter(embeddingModel))
                .build());
        int matched = (int) results.stream().filter(d -> d.getScore() != null && d.getScore() >= threshold).count();
        Double topScore = results.isEmpty() ? null : results.get(0).getScore();
        return new SearchCheck(matched, topScore);
    }

    public record SearchCheck(int matched, Double topScore) {
    }

    /* 제목·종목·팀이 같은 글 묶음 - postIds는 유사도 높은 순, scores는 postIds와 같은 순서의 유사도 */
    public record CandidateGroup(String title, String sport, String team, List<Long> postIds, List<Double> scores) {

        /* 묶음에서 가장 높은 유사도 */
        public double score() {
            return scores.get(0);
        }

        /* 유사도가 threshold 이상인 글만 */
        public List<Long> postIdsAbove(double threshold) {
            List<Long> above = new ArrayList<>();
            for (int i = 0; i < postIds.size(); i++) {
                if (scores.get(i) >= threshold) {
                    above.add(postIds.get(i));
                }
            }
            return above;
        }

        /* 남길 글만 고른 묶음 (유사도 순서 유지) */
        public CandidateGroup retain(java.util.function.Predicate<Long> keep) {
            List<Long> keptIds = new ArrayList<>();
            List<Double> keptScores = new ArrayList<>();
            for (int i = 0; i < postIds.size(); i++) {
                if (keep.test(postIds.get(i))) {
                    keptIds.add(postIds.get(i));
                    keptScores.add(scores.get(i));
                }
            }
            return new CandidateGroup(title, sport, team, keptIds, keptScores);
        }
    }

    /* 유사도가 threshold 이상인 글을 제목·종목·팀으로 묶어, 묶음 최고 유사도 순으로 최대 limit개 반환 (재정렬 후보용) */
    // 같은 글을 수십~수백 번 올리면 벡터 상위 N건을 한두 묶음이 다 차지해 다른 상품이 후보에서 빠진다
    //  - PG에서는 묶음 집계를 SQL로 한 번에 한다 (전체 스캔이지만 판매글 수천 건 규모에선 수 ms)
    //  - SQL 실행이 실패하면 예전처럼 상위 FALLBACK_TOP_K건만 받아 Java에서 묶는다
    //    (검색어 임베딩 실패는 대체 경로도 같은 호출을 다시 하므로 그대로 던진다)
    //  - PG가 없으면(메모리 저장소) 전부 받아 Java에서 묶는다
    public List<CandidateGroup> findCandidateGroups(String query, double threshold, int limit) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("벡터 저장소가 비활성화되어 있습니다.");
        }
        DataSource dataSource = postgresDataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return groupInMemory(vectorStore, query, threshold, limit, IN_MEMORY_TOP_K);
        }
        EmbeddingModel model = embeddingModelProvider.getIfUnique();
        if (model == null) {
            log.warn("findCandidateGroups : 임베딩 모델을 하나로 고를 수 없어 상위 {}건만 묶습니다  |  query={}", FALLBACK_TOP_K, query);
            return groupInMemory(vectorStore, query, threshold, limit, FALLBACK_TOP_K);
        }
        float[] queryEmbedding = model.embed(query);
        try {
            return groupsBySql(new JdbcTemplate(dataSource), queryEmbedding, threshold, limit);
        } catch (RuntimeException exception) {
            log.error("findCandidateGroups : 묶음 집계 SQL 실패, 상위 {}건만 묶습니다  |  query={}", FALLBACK_TOP_K, query, exception);
            return groupInMemory(vectorStore, query, threshold, limit, FALLBACK_TOP_K);
        }
    }

    private List<CandidateGroup> groupsBySql(JdbcTemplate jdbcTemplate, float[] queryEmbedding, double threshold, int limit) {
        PGvector vector = new PGvector(queryEmbedding);
        return jdbcTemplate.query("""
                        SELECT title, sport, team, MIN(distance) AS distance,
                               string_agg(post_id, ',' ORDER BY distance, post_id) AS post_ids,
                               string_agg((1 - distance)::text, ',' ORDER BY distance, post_id) AS scores
                        FROM (SELECT metadata->>'title' AS title, metadata->>'sport' AS sport, metadata->>'team' AS team,
                                     metadata->>'postId' AS post_id, embedding <=> ? AS distance
                              FROM %s
                              WHERE metadata->>'%s' = ? AND metadata->>'postId' IS NOT NULL) v
                        WHERE distance <= ?
                        GROUP BY title, sport, team
                        ORDER BY MIN(distance)
                        LIMIT ?
                        """.formatted(VECTOR_TABLE, MODEL_KEY),
                (rs, rowNum) -> new CandidateGroup(rs.getString("title"), rs.getString("sport"), rs.getString("team"),
                        Arrays.stream(rs.getString("post_ids").split(",")).map(Long::parseLong).toList(),
                        Arrays.stream(rs.getString("scores").split(",")).map(Double::parseDouble).toList()),
                vector, embeddingModel, 1 - threshold, limit);
    }

    private List<CandidateGroup> groupInMemory(VectorStore vectorStore, String query, double threshold, int limit, int topK) {
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .filterExpression(modelFilter(embeddingModel))
                .build());
        return groupDocuments(results, limit);
    }

    /* 유사도 순 결과를 SQL과 같은 기준(제목·종목·팀)으로 묶는다 - 처음 나온 순서(= 묶음 최고 유사도 순) 유지, postId 없는 결과는 건너뜀 */
    static List<CandidateGroup> groupDocuments(List<Document> results, int limit) {
        Map<String, List<Document>> groups = new LinkedHashMap<>();
        for (Document result : results) {
            Map<String, Object> metadata = result.getMetadata();
            if (metadata.get("postId") == null) {
                continue;
            }
            String key = metadata.getOrDefault("title", "") + "|" + metadata.getOrDefault("sport", "") + "|" + metadata.getOrDefault("team", "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
        }
        return groups.values().stream().limit(limit).map(members -> {
            Map<String, Object> metadata = members.get(0).getMetadata();
            return new CandidateGroup(
                    String.valueOf(metadata.getOrDefault("title", "")),
                    String.valueOf(metadata.getOrDefault("sport", "")),
                    String.valueOf(metadata.getOrDefault("team", "")),
                    members.stream().map(d -> Long.parseLong(d.getMetadata().get("postId").toString())).toList(),
                    members.stream().map(d -> d.getScore() != null ? d.getScore() : 0.0).toList());
        }).toList();
    }

    static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private Document toDocument(Post post) {
        return new Document(
                documentId(post.getPostId()),
                buildContent(post),
                Map.of(
                        "postId", post.getPostId().toString(),
                        "title", nullToEmpty(post.getTitle()),
                        "sport", post.getSport() != null ? post.getSport().name() : "",
                        "team", nullToEmpty(post.getTeam()),
                        "price", post.getPrice(),
                        MODEL_KEY, embeddingModel
                )
        );
    }

    /* 현재 모델로 만든 벡터만 남기고 나머지 삭제 */
    // 모델 교체 뒤 재임베딩을 마치고 호출한다. 이전 모델 벡터와 모델 표시가 없는 예전(무작위 id) 벡터를 지운다
    //  - 메타데이터 필터는 키가 없는 문서를 고를 수 없어 SQL로 직접 지운다
    public int deleteVectorsOfOtherModels() {
        DataSource dataSource = postgresDataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return 0;
        }
        int deleted = new JdbcTemplate(dataSource).update(
                "DELETE FROM " + VECTOR_TABLE + " WHERE metadata->>'" + MODEL_KEY + "' IS DISTINCT FROM ?", embeddingModel);
        log.info("deleteVectorsOfOtherModels : 이전 모델 벡터 {}건 삭제  |  현재 모델 {}", deleted, embeddingModel);
        return deleted;
    }

    /* 현재 모델 벡터만 고르는 검색 필터 */
    public static String modelFilter(String embeddingModel) {
        return MODEL_KEY + " == '" + embeddingModel + "'";
    }

    /* postId별 고정 문서 id */
    static String documentId(Long postId) {
        return UUID.nameUUIDFromBytes(("post-" + postId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /* 유사 검색 팀명 별칭 지정 */
    private static final Map<String, String> TEAM_ALIAS = Map.ofEntries(
            // 야구
            Map.entry("KIA 타이거즈", "기아 타이거즈 기아"),
            Map.entry( "삼성 라이온즈", "삼성 라이온즈 삼성"),
            Map.entry( "LG 트윈스" , "엘지 트윈스 엘지"),
            Map.entry( "두산 베어스" , "두산 베어스 두산"),
            Map.entry( "SSG 랜더스" , "SSG 랜더스 신세계"),
            Map.entry( "롯데 자이언츠" , "롯데 자이언츠 롯데"),
            Map.entry( "NC 다이노스" , "엔씨 다이노스 NC"),
            Map.entry( "KT 위즈" , "케이티 위즈 KT"),
            Map.entry( "한화 이글스" , "한화 이글스 한화"),
            Map.entry( "키움 히어로즈" , "키움 히어로즈 키움"),

            // 축구
            Map.entry( "토트넘 홋스퍼" , "토트넘 손흥민"),
            Map.entry( "PSG" , "파리 생제르맹 PSG 이강인"),
            Map.entry( "맨체스터 시티" , "맨시티 맨체스터시티"),
            Map.entry( "맨체스터 유나이티드" , "맨유 맨체스터유나이티드 박지성"),
            Map.entry( "FC 바르셀로나" , "바르사 바르셀로나 메시"),
            Map.entry( "레알 마드리드" , "레알 마드리드 호날두"),
            Map.entry( "리버풀" , "리버풀 살라"),
            Map.entry( "바이에른 뮌헨" , "바이에른 뮌헨 분데스리가"),
            Map.entry( "대한민국 국가대표" , "국대 태극전사 한국 축구"),
            Map.entry( "수원 삼성 블루윙즈" , "수원삼성 블루윙즈 수원 K리그"),
            Map.entry( "울산 HD" , "울산 현대 울산HD K리그"),
            Map.entry( "전북 현대" , "전북 현대 모터스 전북 K리그"),
            Map.entry( "FC 서울" , "FC서울 서울 K리그"),

            // 농구
            Map.entry( "LA 레이커스" , "레이커스 코비 르브론"),
            Map.entry( "골든스테이트 워리어스" , "워리어스 골든스테이트 커리"),
            Map.entry( "시카고 불스" , "불스 시카고 조던"),
            Map.entry( "보스턴 셀틱스" , "셀틱스 보스턴"),
            Map.entry( "서울 삼성 썬더스" , "삼성썬더스 서울삼성"),
            Map.entry( "부산 KCC 이지스" , "KCC 이지스 부산 KCC KBL"),
            Map.entry( "서울 SK 나이츠" , "SK 나이츠 서울 SK KBL"),

            // e스포츠
            Map.entry( "T1" , "T1 테원 페이커 SKT"),
            Map.entry( "Gen.G" , "젠지 GenG 쵸비"),
            // 운영 데이터는 팀명을 한글로 쓴다
            Map.entry( "젠지" , "젠지 Gen.G GenG 쵸비"),
            Map.entry( "DRX", "디알엑스 DRX"),

            // 배구
            Map.entry( "흥국생명 핑크스파이더스", "흥국생명 핑크스파이더스 김연경"),
            Map.entry( "대한항공 점보스", "대한항공 점보스 대한항공")
    );

    /* 종목 enum 대신 한국어 종목명·리그명을 넣어 "축구 유니폼", "롤 팀"처럼 종목으로 찾는 검색어와 맞게 한다 */
    private static final Map<Sport, String> SPORT_LABEL = Map.of(
            Sport.SOCCER, "축구",
            Sport.BASEBALL, "야구 프로야구 KBO",
            Sport.BASKETBALL, "농구 NBA KBL",
            Sport.VOLLEYBALL, "배구 V리그",
            Sport.ESPORTS, "e스포츠 이스포츠 리그오브레전드 롤 LCK 게임단",
            Sport.ETC, "기타"
    );

    /* 임베딩할 텍스트 구성 */
    // 내용 구성 : title + sport + team + uniformName + content
    private String buildContent(Post post) {
        // 별칭 표에 없는 팀도 검색되도록 팀명을 같이 넣는다
        String team = nullToEmpty(post.getTeam());
        String teamAlias = TEAM_ALIAS.getOrDefault(team, "");

        return String.join(" ",
                nullToEmpty(post.getTitle()),
                post.getSport() != null ? SPORT_LABEL.get(post.getSport()) : "",
                team,
                teamAlias,
                nullToEmpty(post.getUniformName()),
                truncate(nullToEmpty(post.getContent()), MAX_CONTENT_LENGTH)
                ).trim();
    }

    // 아주 긴 본문은 임베딩 모델 입력 한도(8191토큰)를 넘어 묶음 저장까지 실패시키므로 앞부분만 쓴다
    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
