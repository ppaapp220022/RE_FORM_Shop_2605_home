package com.re_form_shop_2605.eval.moderation;

import com.re_form_shop_2605.service.AI.ModerationKeyword;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 로컬 키워드 규칙(ModerationKeyword) eval
 *  - API 호출 없이 결정적으로 실행되므로 일반 test 태스크에 포함
 *  - 로컬 규칙에 걸리면 OpenAI 판정을 건너뛰므로, 여기서의 오탐은 곧 서비스 오탐
 *  - 기준치는 현재 성능(baseline)이며, 규칙을 개선하면 함께 올려서 회귀를 막는다
 * ─────────────────────────────────────────────────────
 */
@Log4j2
class ModerationKeywordEvalTest {

    /*
     * 2026-11-04 규칙 개선 전: precision 0.600 / recall 0.429 / fpr 0.455 (57 cases)
     * 2026-11-04 규칙 개선 후: precision 1.000 / recall 0.750 / fpr 0.000 (67 cases)
     * 케이스 추가 시 한두 건 흔들려도 깨지지 않도록 약간의 여유를 둔다
     */
    private static final double MIN_PRECISION = 0.95;
    private static final double MIN_RECALL = 0.72;
    private static final double MAX_FALSE_POSITIVE_RATE = 0.05;

    @Test
    void localKeywordRules() {
        ModerationKeyword moderationKeyword = new ModerationKeyword();
        List<ModerationEvalReport.Result> results = new ArrayList<>();

        for (ModerationEvalCase evalCase : ModerationEvalCase.load()) {
            ModerationKeyword.LocalModerationHit hit = moderationKeyword.detect(evalCase.text());
            results.add(new ModerationEvalReport.Result(
                    evalCase,
                    hit == null ? ModerationEvalCase.SAFE : hit.riskLevel().name(),
                    hit == null ? null : hit.reason(),
                    null,
                    0L
            ));
        }

        ModerationEvalReport report = new ModerationEvalReport("Moderation eval - 로컬 키워드 규칙", results);
        Path path = report.write("moderation-local.md");
        log.info("[Eval] {}", report.summary());
        log.info("[Eval] report={}", path);

        assertTrue(report.precision() >= MIN_PRECISION, "precision 회귀: " + report.summary());
        assertTrue(report.recall() >= MIN_RECALL, "recall 회귀: " + report.summary());
        assertTrue(report.falsePositiveRate() <= MAX_FALSE_POSITIVE_RATE, "false positive 회귀: " + report.summary());
    }
}
