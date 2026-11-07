package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.entity.trade.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import org.springframework.ai.document.Document;

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
@RequiredArgsConstructor
public class PostVectorService {
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /* 판매글 등록·수정 시 벡터 저장 */
    // 용도 : 게시글 내용 벡터 변환해 PGVector 저장
    //  - 문서 id를 postId로 고정해 다시 저장하면 덮어쓴다 (PGVector id 컬럼은 UUID)
    //  - 고정 id 이전에 무작위 id로 저장된 벡터가 남아 있을 수 있어, 같은 postId 벡터를 지운 뒤 저장한다
    //  - 저장 실패는 판매글 등록을 막지 않도록 로그만 남기고 false 반환
    public boolean savePostVector(Post post) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("벡터 저장소가 비활성화되어 임베딩 저장을 건너뜁니다. postId={}", post.getPostId());
            return false;
        }

        String postId = post.getPostId().toString();
        Document document = new Document(
                documentId(post.getPostId()),
                buildContent(post),
                Map.of(
                        "postId", postId,
                        "title", nullToEmpty(post.getTitle()),
                        "sport", post.getSport() != null ? post.getSport().name() : "",
                        "team", nullToEmpty(post.getTeam()),
                        "price", post.getPrice()
                )
        );

        try {
            vectorStore.delete("postId == '" + postId + "'");
            vectorStore.add(List.of(document));
            log.info("savePostVector : 벡터 저장소에 저장 완료  |  postId {}", post.getPostId());
            return true;
        } catch (Exception exception) {
            log.error("savePostVector : 벡터 저장 실패  |  postId {}", post.getPostId(), exception);
            return false;
        }
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
                nullToEmpty(post.getContent())
                ).trim();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
