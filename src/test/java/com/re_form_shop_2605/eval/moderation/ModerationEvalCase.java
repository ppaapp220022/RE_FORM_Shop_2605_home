package com.re_form_shop_2605.eval.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.re_form_shop_2605.entity.Enum.RiskLevel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 모더레이션 eval 데이터셋의 케이스 한 건
 *  - expected: SAFE / LOW / MID / HIGH (서비스가 내려야 하는 최종 판정)
 *  - tag: 리포트 집계용 분류 (profanity, scam, safe_fp_trap ...)
 * ─────────────────────────────────────────────────────
 */
public record ModerationEvalCase(String id, String tag, String expected, String text, String note) {

    public static final String SAFE = "SAFE";
    public static final String DATASET = "/eval/moderation/cases.jsonl";

    public boolean expectedFlagged() {
        return !SAFE.equals(expected);
    }

    /* RiskLevel(null = 정상)을 eval 라벨 문자열로 변환 */
    public static String label(RiskLevel riskLevel) {
        return riskLevel == null ? SAFE : riskLevel.name();
    }

    public static List<ModerationEvalCase> load() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ModerationEvalCase> cases = new ArrayList<>();

        try (InputStream in = ModerationEvalCase.class.getResourceAsStream(DATASET)) {
            if (in == null) {
                throw new IllegalStateException("데이터셋을 찾을 수 없습니다: " + DATASET);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                String expected = node.get("expected").asText();
                if (!SAFE.equals(expected)) {
                    RiskLevel.valueOf(expected); // 라벨 오타 방지
                }
                cases.add(new ModerationEvalCase(
                        node.get("id").asText(),
                        node.get("tag").asText(),
                        expected,
                        node.get("text").asText(),
                        node.has("note") ? node.get("note").asText() : null
                ));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cases;
    }
}
