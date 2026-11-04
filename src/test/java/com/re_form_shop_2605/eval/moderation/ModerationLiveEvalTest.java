package com.re_form_shop_2605.eval.moderation;

import com.re_form_shop_2605.dto.AI.RiskAnalysisResultDTO;
import com.re_form_shop_2605.entity.Enum.TargetType;
import com.re_form_shop_2605.service.AI.ModerationKeyword;
import com.re_form_shop_2605.service.AI.ModerationService;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiModerationModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 실제 OpenAI를 호출하는 모더레이션 end-to-end eval
 *  - 로컬 규칙 → OpenAI Moderation → 개선 제안까지 ModerationService.checkDraft 그대로 실행
 *  - 비용이 들기 때문에 @Tag("eval")로 분리, ./gradlew evalTest 로만 실행
 *  - OPENAI_API_KEY 환경변수가 없으면 건너뜀
 *  - 기준치는 -Peval.moderation.minRecall=0.8 처럼 넘겼을 때만 검사 (기본은 리포트만 작성)
 * ─────────────────────────────────────────────────────
 */
@Log4j2
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ModerationLiveEvalTest {

    @Test
    void endToEndModeration() {
        // 운영과 같은 기본 모델 사용 (omni-moderation-latest / OpenAiChatOptions 기본 채팅 모델)
        OpenAiModerationModel moderationModel = OpenAiModerationModel.builder().build();
        ChatClient.Builder chatClientBuilder = ChatClient.builder(OpenAiChatModel.builder().build());

        // checkDraft는 API 장애 시 SAFE로 fallback 하므로, 먼저 직접 호출해 장애가 정상 판정으로 집계되는 것을 막는다
        moderationModel.call(new ModerationPrompt("eval smoke check"));

        // checkDraft는 DB를 쓰지 않으므로 repository 없이 생성
        ModerationService moderationService =
                new ModerationService(moderationModel, new ModerationKeyword(), chatClientBuilder, null);

        List<ModerationEvalReport.Result> results = new ArrayList<>();
        for (ModerationEvalCase evalCase : ModerationEvalCase.load()) {
            long start = System.currentTimeMillis();
            RiskAnalysisResultDTO dto = moderationService.checkDraft(evalCase.text(), TargetType.CHAT);
            long latencyMs = System.currentTimeMillis() - start;

            results.add(new ModerationEvalReport.Result(
                    evalCase,
                    ModerationEvalCase.label(dto.riskLevel()),
                    dto.reason(),
                    dto.suggestion(),
                    latencyMs
            ));
        }

        ModerationEvalReport report = new ModerationEvalReport("Moderation eval - end-to-end (OpenAI)", results);
        Path path = report.write("moderation-live.md");
        log.info("[Eval] {}", report.summary());
        log.info("[Eval] report={}", path);

        assertAtLeast("eval.moderation.minPrecision", report.precision(), report);
        assertAtLeast("eval.moderation.minRecall", report.recall(), report);
        assertAtMost("eval.moderation.maxFalsePositiveRate", report.falsePositiveRate(), report);
    }

    private static void assertAtLeast(String property, double actual, ModerationEvalReport report) {
        String threshold = System.getProperty(property);
        if (threshold != null) {
            assertTrue(actual >= Double.parseDouble(threshold), property + "=" + threshold + " 미달: " + report.summary());
        }
    }

    private static void assertAtMost(String property, double actual, ModerationEvalReport report) {
        String threshold = System.getProperty(property);
        if (threshold != null) {
            assertTrue(actual <= Double.parseDouble(threshold), property + "=" + threshold + " 초과: " + report.summary());
        }
    }
}
