package com.re_form_shop_2605.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ─────────────────────────────────────────────────────
 * 설명: eval 리포트 공용 헬퍼(EvalReport) 단위 테스트 - [Eval] 요약 줄 "key=count" 형식과 집계 규칙
 * ─────────────────────────────────────────────────────
 */
class EvalReportTest {

    @Test
    void countsKeepKeyOrderAndAppendUnknownLabels() {
        List<List<String>> rows = List.of(List.of("b"), List.of("b", "b", "x"), List.of());
        Map<String, Long> counts = EvalReport.counts(rows, List.of("a", "b"), r -> r);

        // 키 순서 유지, 0건도 표시, 한 행 안의 중복 라벨은 한 번, 목록에 없는 라벨은 뒤에 붙음
        assertEquals(" a=0 b=2 x=1", EvalReport.countsText(counts));
    }

    @Test
    void passRateOfEmptyRowsIsZero() {
        assertEquals(0.0, EvalReport.passRate(List.<Boolean>of(), b -> b));
        assertEquals(0.5, EvalReport.passRate(List.of(true, false), b -> b));
    }

    @Test
    void cellEscapesPipesAndNewlines() {
        assertEquals("a \\| b c", EvalReport.cell(" a | b\n c "));
        assertEquals("", EvalReport.cell(null));
    }

    @Test
    void passByTagTableKeepsFirstSeenOrder() {
        List<String[]> rows = List.of(new String[]{"t2", "ok"}, new String[]{"t1", "no"}, new String[]{"t2", "no"});
        assertEquals("| t2 | 1 / 2 |\n| t1 | 0 / 1 |\n",
                EvalReport.passByTagTable(rows, r -> r[0], r -> "ok".equals(r[1])));
    }
}
