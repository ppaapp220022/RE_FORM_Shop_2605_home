package com.re_form_shop_2605.eval.moderation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 모더레이션 eval 결과 집계 및 마크다운 리포트 작성
 *  - 이진 지표(유해 여부): precision / recall / false positive rate
 *  - 등급 지표: 기대 등급과 실제 등급 완전 일치율
 * ─────────────────────────────────────────────────────
 */
public class ModerationEvalReport {

    public record Result(ModerationEvalCase evalCase, String actual, String reason, String suggestion, long latencyMs) {

        public boolean actualFlagged() {
            return !ModerationEvalCase.SAFE.equals(actual);
        }

        public boolean binaryCorrect() {
            return evalCase.expectedFlagged() == actualFlagged();
        }

        public boolean exactCorrect() {
            return evalCase.expected().equals(actual);
        }
    }

    private final String title;
    private final List<Result> results;
    private final int tp;
    private final int fp;
    private final int fn;
    private final int tn;
    private final int exact;

    public ModerationEvalReport(String title, List<Result> results) {
        this.title = title;
        this.results = results;
        int tp = 0, fp = 0, fn = 0, tn = 0, exact = 0;
        for (Result r : results) {
            boolean expected = r.evalCase().expectedFlagged();
            boolean actual = r.actualFlagged();
            if (expected && actual) tp++;
            else if (!expected && actual) fp++;
            else if (expected) fn++;
            else tn++;
            if (r.exactCorrect()) exact++;
        }
        this.tp = tp;
        this.fp = fp;
        this.fn = fn;
        this.tn = tn;
        this.exact = exact;
    }

    public double precision() {
        return ratio(tp, tp + fp);
    }

    public double recall() {
        return ratio(tp, tp + fn);
    }

    public double falsePositiveRate() {
        return ratio(fp, fp + tn);
    }

    public double exactMatchRate() {
        return ratio(exact, results.size());
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "%s | cases=%d precision=%.3f recall=%.3f fpr=%.3f exact=%.3f (tp=%d fp=%d fn=%d tn=%d)",
                title, results.size(), precision(), recall(), falsePositiveRate(), exactMatchRate(), tp, fp, fn, tn);
    }

    /* eval.reportDir(Gradle이 build/reports/eval 로 지정)에 마크다운 리포트 저장 후 경로 반환 */
    public Path write(String fileName) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("| 지표 | 값 |\n|---|---|\n");
        md.append(row("케이스 수", String.valueOf(results.size())));
        md.append(row("Precision (유해 판정 중 실제 유해)", pct(precision())));
        md.append(row("Recall (실제 유해 중 탐지)", pct(recall())));
        md.append(row("False positive rate (정상인데 유해 판정)", pct(falsePositiveRate())));
        md.append(row("등급 완전 일치율", pct(exactMatchRate())));
        md.append(row("TP / FP / FN / TN", tp + " / " + fp + " / " + fn + " / " + tn));

        md.append("\n## 태그별 이진 정확도\n\n| tag | 정답 / 전체 |\n|---|---|\n");
        Map<String, int[]> byTag = new LinkedHashMap<>();
        for (Result r : results) {
            int[] count = byTag.computeIfAbsent(r.evalCase().tag(), k -> new int[2]);
            if (r.binaryCorrect()) count[0]++;
            count[1]++;
        }
        byTag.forEach((tag, count) -> md.append(row(tag, count[0] + " / " + count[1])));

        md.append("\n## 오판정 케이스\n\n| id | 기대 | 실제 | 텍스트 | 사유 | 메모 |\n|---|---|---|---|---|---|\n");
        for (Result r : results) {
            if (r.exactCorrect()) {
                continue;
            }
            ModerationEvalCase c = r.evalCase();
            md.append("| ").append(c.id())
                    .append(" | ").append(c.expected())
                    .append(" | ").append(r.binaryCorrect() ? r.actual() : "**" + r.actual() + "**")
                    .append(" | ").append(cell(c.text()))
                    .append(" | ").append(cell(r.reason()))
                    .append(" | ").append(cell(c.note()))
                    .append(" |\n");
        }

        boolean hasSuggestion = results.stream().anyMatch(r -> r.suggestion() != null);
        if (hasSuggestion) {
            md.append("\n## 개선 제안 (generateSuggestion) 샘플\n\n| id | 텍스트 | 제안 |\n|---|---|---|\n");
            for (Result r : results) {
                if (r.suggestion() != null) {
                    md.append("| ").append(r.evalCase().id())
                            .append(" | ").append(cell(r.evalCase().text()))
                            .append(" | ").append(cell(r.suggestion()))
                            .append(" |\n");
                }
            }
        }

        try {
            Path dir = Path.of(System.getProperty("eval.reportDir", "build/reports/eval"));
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static String row(String key, String value) {
        return "| " + key + " | " + value + " |\n";
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replaceAll("\\s+", " ").trim();
    }
}
