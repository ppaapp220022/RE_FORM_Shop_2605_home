package com.re_form_shop_2605.eval.moderation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 개선 제안 eval의 실패 라벨과 [Eval] 요약 줄 키(SUMMARY_KEYS)가 어긋나지 않는지 확인
 *  - 요약 줄 개수는 라벨 이름으로 세므로, 이름에 오타가 나면 컴파일 오류 없이 그 항목이 0으로 나온다
 *  - OpenAI를 부르지 않으므로 일반 test 태스크에서 돈다
 * ─────────────────────────────────────────────────────
 */
class ModerationSuggestionLabelsTest {

    @Test
    void everyLabelIsSummaryKey() {
        ModerationSuggestionEvalTest.Verdict all = new ModerationSuggestionEvalTest.Verdict(true, true, true, true, "");
        ModerationSuggestionEvalTest.Row row = new ModerationSuggestionEvalTest.Row(null, null, null, true, all);
        ModerationSuggestionEvalTest.Row judgeError = new ModerationSuggestionEvalTest.Row(null, null, null, false,
                ModerationSuggestionEvalTest.Verdict.JUDGE_ERROR);

        assertEquals(List.of("concealsRisk", "fabricatesPromise", "repeatsAbuse", "offTarget", "localRuleHit"), row.labels());
        assertEquals(List.of("judgeError"), judgeError.labels());
        assertEquals(Set.copyOf(ModerationSuggestionEvalTest.SUMMARY_KEYS),
                Set.of("concealsRisk", "fabricatesPromise", "repeatsAbuse", "offTarget", "localRuleHit", "judgeError"));
    }

    @Test
    void localRuleHitDoesNotFail() {
        ModerationSuggestionEvalTest.Verdict clean = new ModerationSuggestionEvalTest.Verdict(false, false, false, false, "");
        assertEquals(true, new ModerationSuggestionEvalTest.Row(null, null, null, true, clean).passed());
        assertEquals(false, new ModerationSuggestionEvalTest.Row(null, null, null, false,
                ModerationSuggestionEvalTest.Verdict.JUDGE_ERROR).passed());
    }
}
