package com.re_form_shop_2605.eval.listing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * 설명: 판매글 자동 작성 eval 데이터셋의 케이스 한 건
 *  - render: 이미지 생성 스펙 (ListingEvalImageGenerator가 images/{id}.png로 그림)
 *  - expect: 결정적 검사용 정답
 *    teamAliases    : 제목+설명에 하나 이상 나와야 하는 팀명 (공백 무시, 대소문자 무시). 비어 있으면 검사 안 함
 *    number         : 보이는 등번호 (나와야 함)
 *    size           : 라벨에 보이는 사이즈. null이면 사이즈를 지어내면 안 됨
 *    mustMentionAny : 하나 이상 나와야 하는 표현 (얼룩, 선수 이름 등)
 *    mustNotMention : 나오면 안 되는 표현 (이미지 속 지시, 근거 없는 정품 주장 등)
 *    noProduct      : 상품이 보이지 않는 사진 (status=NO_PRODUCT여야 함)
 *  - visible: 이미지에 실제로 보이는 것. LLM judge가 지어낸 내용인지 판단하는 기준
 * ─────────────────────────────────────────────────────
 */
public record ListingEvalCase(String id, String tag, JsonNode render, Expect expect, String visible, String note) {

    public static final String DATASET = "/eval/listing/cases.jsonl";
    public static final String IMAGE_DIR = "/eval/listing/images/";

    public record Expect(List<String> teamAliases, String number, String size,
                         List<String> mustMentionAny, List<String> mustNotMention, boolean noProduct) {
    }

    public String imageResource() {
        return IMAGE_DIR + id + ".png";
    }

    public static List<ListingEvalCase> load() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ListingEvalCase> cases = new ArrayList<>();

        try (InputStream in = ListingEvalCase.class.getResourceAsStream(DATASET)) {
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
                JsonNode e = node.get("expect");
                Expect expect = new Expect(
                        strings(e.get("teamAliases")),
                        text(e.get("number")),
                        text(e.get("size")),
                        strings(e.get("mustMentionAny")),
                        strings(e.get("mustNotMention")),
                        e.path("noProduct").asBoolean(false)
                );
                cases.add(new ListingEvalCase(
                        node.get("id").asText(),
                        node.get("tag").asText(),
                        node.get("render"),
                        expect,
                        node.get("visible").asText(),
                        text(node.get("note"))
                ));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cases;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null) {
            node.forEach(v -> values.add(v.asText()));
        }
        return values;
    }
}
