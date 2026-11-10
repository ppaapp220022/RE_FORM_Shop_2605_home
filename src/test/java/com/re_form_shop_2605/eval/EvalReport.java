package com.re_form_shop_2605.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ─────────────────────────────────────────────────────
 * 설명: eval 리포트 공용 헬퍼 - 통과율, 실패 항목별 건수, 태그별 통과, 마크다운 저장
 *  - [Eval] 요약 줄의 "key=count" 부분은 countsText()로 만든다. 비교 스크립트가 이 형식(키 이름·순서)을 grep하므로
 *    키 순서는 호출하는 쪽이 넘긴 목록 순서를 그대로 따른다 (목록에 없는 라벨은 맨 뒤에 붙는다)
 * ─────────────────────────────────────────────────────
 */
public final class EvalReport {

    private EvalReport() {
    }

    /** 통과한 행 비율 (행이 없으면 0) */
    public static <T> double passRate(List<T> rows, Predicate<T> passed) {
        return rows.isEmpty() ? 0.0 : (double) rows.stream().filter(passed).count() / rows.size();
    }

    /**
     * 라벨별로 그 라벨이 붙은 행 수. keys 순서대로 (0건도 포함) 나오고,
     * keys에 없는 라벨은 조용히 빠지지 않도록 뒤에 나온 순서대로 붙인다
     */
    public static <T> Map<String, Long> counts(List<T> rows, List<String> keys,
                                               Function<T, ? extends Collection<String>> labels) {
        Map<String, Long> counts = new LinkedHashMap<>();
        keys.forEach(key -> counts.put(key, 0L));
        for (T r : rows) {
            for (String label : new LinkedHashSet<>(labels.apply(r))) {
                counts.merge(label, 1L, Long::sum);
            }
        }
        return counts;
    }

    /** 요약 줄용 " key=count key=count" (앞에 공백 포함) */
    public static String countsText(Map<String, Long> counts) {
        StringBuilder text = new StringBuilder();
        counts.forEach((key, count) -> text.append(' ').append(key).append('=').append(count));
        return text.toString();
    }

    /** 마크다운 "| 항목 | 건수 |" 표 본문 */
    public static String countsTable(Map<String, Long> counts) {
        StringBuilder md = new StringBuilder();
        counts.forEach((key, count) -> md.append(row(key, String.valueOf(count))));
        return md.toString();
    }

    /** 태그 순서(처음 나온 순서)대로 "| tag | 맞음 / 전체 |" 표 본문 */
    public static <T> String passByTagTable(List<T> rows, Function<T, String> tag, Predicate<T> passed) {
        Map<String, int[]> byTag = new LinkedHashMap<>();
        for (T r : rows) {
            int[] count = byTag.computeIfAbsent(tag.apply(r), k -> new int[2]);
            if (passed.test(r)) count[0]++;
            count[1]++;
        }
        StringBuilder md = new StringBuilder();
        byTag.forEach((key, count) -> md.append(row(key, count[0] + " / " + count[1])));
        return md.toString();
    }

    public static String row(String key, String value) {
        return "| " + key + " | " + value + " |\n";
    }

    /** 마크다운 표 칸 - 파이프를 escape하고 줄바꿈을 공백으로 */
    public static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replaceAll("\\s+", " ").trim();
    }

    /** eval.reportDir(Gradle이 build/reports/eval 로 지정)에 마크다운 리포트 저장 후 절대 경로 반환 */
    public static Path write(String fileName, String markdown) {
        try {
            Path dir = Path.of(System.getProperty("eval.reportDir", "build/reports/eval"));
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** -Pproperty=기준치를 넘겼을 때만 actual >= 기준치 검사 */
    public static void assertAtLeast(String property, double actual, String summary) {
        String threshold = System.getProperty(property);
        if (threshold != null) {
            assertTrue(actual >= Double.parseDouble(threshold), property + "=" + threshold + " 미달: " + summary);
        }
    }

    /** -Pproperty=기준치를 넘겼을 때만 actual <= 기준치 검사 */
    public static void assertAtMost(String property, double actual, String summary) {
        String threshold = System.getProperty(property);
        if (threshold != null) {
            assertTrue(actual <= Double.parseDouble(threshold), property + "=" + threshold + " 초과: " + summary);
        }
    }
}
