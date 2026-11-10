package com.re_form_shop_2605.eval.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.re_form_shop_2605.dto.AI.AiListingSuggestResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 판매글 eval의 결정적 채점 함수(ListingSuggestEvalTest.check) 단위 테스트
 *  - OpenAI를 부르지 않으므로 일반 test 태스크에서 돈다
 *  - 채점 규칙을 고칠 때 의도하지 않은 판정 변화(오탐·미탐)를 막는다
 * ─────────────────────────────────────────────────────
 */
class ListingCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* FC SEOUL 10번, 사이즈 라벨 없음 */
    private static final ListingEvalCase SEOUL = evalCase(
            render("FC SEOUL", "10"),
            new ListingEvalCase.Expect(List.of("FC서울", "서울"), "10", null, List.of(), List.of("정품"), false));

    @Test
    void passesCleanListing() {
        assertEquals(List.of(), check(SEOUL, "FC서울 10번 유니폼", "FC 서울 홈 유니폼입니다. 등번호 10번."));
    }

    @Test
    void failedStatusIsParseFailedOnly() {
        assertEquals(List.of("parseFailed"), checkKnown(SEOUL, AiListingSuggestResponseDTO.fallback()));
    }

    @Test
    void productStatusMustMatchExpectation() {
        assertEquals(List.of("productStatus"), checkKnown(SEOUL, AiListingSuggestResponseDTO.noProduct()));

        ListingEvalCase empty = evalCase(MAPPER.createObjectNode(),
                new ListingEvalCase.Expect(List.of(), null, null, List.of(), List.of(), true));
        assertEquals(List.of(), checkKnown(empty, AiListingSuggestResponseDTO.noProduct()));
        assertEquals(List.of("productStatus"), check(empty, "유니폼", "설명"));
    }

    @Test
    void tooLongCountsCodePoints() {
        // "FC서울 10번 "이 9자
        assertEquals(List.of(), check(SEOUL, "FC서울 10번 " + "가".repeat(11), "설명"));          // 20자
        assertEquals(List.of("tooLong"), check(SEOUL, "FC서울 10번 " + "가".repeat(12), "설명")); // 21자
        // 이모지는 UTF-16 두 칸이지만 한 글자로 센다
        assertEquals(List.of(), check(SEOUL, "FC서울 10번 " + "🏆".repeat(11), "설명"));
        assertEquals(List.of("tooLong"), check(SEOUL, "FC서울 10번", "가".repeat(151)));
    }

    @Test
    void teamMatchIgnoresSpacesAndCase() {
        ListingEvalCase t1 = evalCase(render("T1", null),
                new ListingEvalCase.Expect(List.of("T1", "티원"), null, null, List.of(), List.of(), false));
        assertEquals(List.of(), check(t1, "t1 유니폼", "설명"));
        assertEquals(List.of(), check(SEOUL, "F C 서 울 10", "설명"));
        assertEquals(List.of("missingTeam"), check(SEOUL, "축구 유니폼 10번", "설명"));
    }

    @Test
    void numberNeedsDigitBoundary() {
        assertEquals(List.of("missingNumber"), check(SEOUL, "FC서울 107번", "설명"));
        assertEquals(List.of("missingNumber"), check(SEOUL, "FC서울 유니폼", "2010 시즌"));
        assertEquals(List.of(), check(SEOUL, "FC서울 #10", "설명"));
    }

    @Test
    void labeledSizeMustAppearAsToken() {
        ListingEvalCase sized = evalCase(render("FC SEOUL", "10"),
                new ListingEvalCase.Expect(List.of("서울"), "10", "L", List.of(), List.of(), false));
        assertEquals(List.of(), check(sized, "FC서울 10번 L", "설명"));
        assertEquals(List.of(), check(sized, "FC서울 10번", "사이즈 l"));
        assertEquals(List.of("wrongSize"), check(sized, "FC서울 10번 XL", "설명"));
        assertEquals(List.of("wrongSize"), check(sized, "FC서울 10번", "사이즈 문의"));
    }

    @Test
    void inventedSizeWithoutLabelIsWrongSize() {
        assertEquals(List.of("wrongSize"), check(SEOUL, "FC서울 10번 M", "설명"));
        assertEquals(List.of("wrongSize"), check(SEOUL, "FC서울 10번", "사이즈 100"));
        assertEquals(List.of("wrongSize"), check(SEOUL, "FC서울 10번", "프리사이즈"));
        // 퍼센트는 사이즈가 아니다
        assertEquals(List.of(), check(SEOUL, "FC서울 10번", "상태 100% 깨끗"));
    }

    @Test
    void visibleTextIsNotMistakenForSize() {
        // 등번호 95, 가슴 글씨 "M"처럼 이미지에 실제로 있는 글자는 사이즈로 보지 않는다
        ListingEvalCase visible = evalCase(render("M", "95"),
                new ListingEvalCase.Expect(List.of(), "95", null, List.of(), List.of(), false));
        assertEquals(List.of(), check(visible, "M 95번 유니폼", "설명"));
        // 토큰 단위로만 지우므로 "9"번이 "95"의 9를 지워 사이즈를 숨기지 않는다
        ListingEvalCase nine = evalCase(render("TEAM", "9"),
                new ListingEvalCase.Expect(List.of(), "9", null, List.of(), List.of(), false));
        assertEquals(List.of("wrongSize"), check(nine, "9번 유니폼", "사이즈 95"));
    }

    @Test
    void mustMentionAnyIgnoresSpaces() {
        ListingEvalCase stain = evalCase(render("FC SEOUL", "10"),
                new ListingEvalCase.Expect(List.of("서울"), "10", null, List.of("얼룩", "오염"), List.of(), false));
        assertEquals(List.of(), check(stain, "FC서울 10번", "앞면에 오 염 있음"));
        assertEquals(List.of("missingMention"), check(stain, "FC서울 10번", "깨끗합니다"));
    }

    @Test
    void forbiddenSkipsNegatedAuthenticity() {
        assertEquals(List.of("forbidden"), check(SEOUL, "FC서울 10번", "정품 유니폼입니다"));
        assertEquals(List.of(), check(SEOUL, "FC서울 10번", "비정품입니다"));
        assertEquals(List.of(), check(SEOUL, "FC서울 10번", "정품 유니폼 아닙니다."));
        // 부정 표현을 지운 뒤에도 남은 정품 주장은 잡는다
        assertEquals(List.of("forbidden"), check(SEOUL, "FC서울 10번", "비정품 아님, 정품 보증"));
    }

    @Test
    void reportsEveryFailureInOrder() {
        assertEquals(List.of("tooLong", "missingTeam", "missingNumber", "wrongSize", "forbidden"),
                check(SEOUL, "아주 긴 제목으로 쓴 유니폼 판매글입니다 정말로요", "정품 XL"));
    }

    @Test
    void judgeLabelsAreSummaryKeys() {
        // judge 항목이 FAILURE_KEYS에 없으면 [Eval] 요약 줄 정해진 자리에 안 나온다
        ListingSuggestEvalTest.Verdict all = new ListingSuggestEvalTest.Verdict(true, true, true, true, "");
        assertTrue(ListingSuggestEvalTest.FAILURE_KEYS.containsAll(all.failures()), all.failures().toString());
        assertTrue(ListingSuggestEvalTest.FAILURE_KEYS.containsAll(ListingSuggestEvalTest.Verdict.JUDGE_ERROR.failures()));
    }

    /* check()가 낸 라벨이 모두 FAILURE_KEYS에 있는지도 함께 확인한다 (이름이 어긋나면 요약 줄 자리에서 빠진다) */
    private static List<String> checkKnown(ListingEvalCase evalCase, AiListingSuggestResponseDTO dto) {
        List<String> failures = ListingSuggestEvalTest.check(evalCase, dto);
        assertTrue(ListingSuggestEvalTest.FAILURE_KEYS.containsAll(failures), "FAILURE_KEYS에 없는 라벨: " + failures);
        return failures;
    }

    private static List<String> check(ListingEvalCase evalCase, String title, String description) {
        return checkKnown(evalCase,
                new AiListingSuggestResponseDTO(title, description, AiListingSuggestResponseDTO.OK));
    }

    private static ObjectNode render(String chest, String number) {
        ObjectNode render = MAPPER.createObjectNode().put("chest", chest);
        if (number != null) {
            render.put("number", number);
        } else {
            render.putNull("number");
        }
        return render;
    }

    private static ListingEvalCase evalCase(ObjectNode render, ListingEvalCase.Expect expect) {
        return new ListingEvalCase("test", "test", render, expect, "", null);
    }
}
