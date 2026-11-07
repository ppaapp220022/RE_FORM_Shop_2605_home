package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.entity.trade.Post;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import org.springframework.ai.document.Document;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<DataSource> postgresDataSourceProvider;
    private final String embeddingModel;

    public PostVectorService(ObjectProvider<VectorStore> vectorStoreProvider,
                             @Qualifier("postgresDataSource") ObjectProvider<DataSource> postgresDataSourceProvider,
                             @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String embeddingModel) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.postgresDataSourceProvider = postgresDataSourceProvider;
        this.embeddingModel = embeddingModel;
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

        try {
            vectorStore.add(List.of(toDocument(post)));
            log.info("savePostVector : 벡터 저장소에 저장 완료  |  postId {}", post.getPostId());
            return true;
        } catch (Exception exception) {
            log.error("savePostVector : 벡터 저장 실패  |  postId {}", post.getPostId(), exception);
            return false;
        }
    }

    /* 여러 판매글을 한 번에 저장 (재임베딩용) */
    // 임베딩 API를 묶음 단위로 호출해 글마다 호출하는 것보다 빠르다. 묶음 저장이 실패하면 글마다 다시 시도해 실패한 글만 걸러낸다
    //  - 글마다 다시 시도해도 연달아 실패하면 키 만료·저장소 장애 같은 전체 장애로 보고 나머지는 시도하지 않는다
    public BatchResult savePostVectors(List<Post> posts) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return new BatchResult(0, posts.size(), "벡터 저장소가 비활성화되어 있습니다.");
        }
        try {
            vectorStore.add(posts.stream().map(this::toDocument).toList());
            return new BatchResult(posts.size(), 0, null);
        } catch (Exception exception) {
            log.warn("savePostVectors : 묶음 저장 실패, 글마다 다시 시도합니다  |  {}건", posts.size(), exception);
            int saved = 0, consecutiveFailures = 0;
            for (Post post : posts) {
                if (savePostVector(post)) {
                    saved++;
                    consecutiveFailures = 0;
                } else if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.error("savePostVectors : 글마다 다시 시도해도 {}건 연속 실패해 나머지를 건너뜁니다", consecutiveFailures);
                    break;
                }
            }
            return new BatchResult(saved, posts.size() - saved, rootMessage(exception));
        }
    }

    public record BatchResult(int saved, int failed, String error) {
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
            Map.entry( "수원 삼성 블루윙즈" , "수원삼성 블루윙즈 수원"),

            // 농구
            Map.entry( "LA 레이커스" , "레이커스 코비 르브론"),
            Map.entry( "골든스테이트 워리어스" , "워리어스 골든스테이트 커리"),
            Map.entry( "시카고 불스" , "불스 시카고 조던"),
            Map.entry( "보스턴 셀틱스" , "셀틱스 보스턴"),
            Map.entry( "서울 삼성 썬더스" , "삼성썬더스 서울삼성"),

            // e스포츠
            Map.entry( "T1" , "T1 테원 페이커 SKT"),
            Map.entry( "Gen.G" , "젠지 GenG 쵸비"),
            Map.entry( "DRX", "디알엑스 DRX")
    );

    /* 임베딩할 텍스트 구성 */
    // 내용 구성 : title + sport + team + uniformName + content
    private String buildContent(Post post) {
        // 별칭 표에 없는 팀도 검색되도록 팀명을 같이 넣는다
        String team = nullToEmpty(post.getTeam());
        String teamAlias = TEAM_ALIAS.getOrDefault(team, "");

        return String.join(" ",
                nullToEmpty(post.getTitle()),
                post.getSport() != null ? post.getSport().name() : "",
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
