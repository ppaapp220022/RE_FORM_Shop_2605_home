package com.re_form_shop_2605.dto.AI;

/**
 * ─────────────────────────────────────────────────────
 * 작성자: 진혜림
 * 작성일: 2026-05-14
 * 설명: AI가 이미지를 분석해 제안하는 판매글 제목/설명 응답 DTO
 *  - status: OK(제안 있음) / NO_PRODUCT(사진에서 상품을 찾지 못함) / FAILED(AI 호출·파싱 실패)
 *    OK가 아니면 title, description은 빈 문자열이므로 프론트는 폼을 채우지 않고 안내만 보여준다
 * ─────────────────────────────────────────────────────
 */
public record AiListingSuggestResponseDTO(
        String title,
        String description,
        String status
) {
    public static final String OK = "OK";
    public static final String NO_PRODUCT = "NO_PRODUCT";
    public static final String FAILED = "FAILED";

    public static AiListingSuggestResponseDTO fallback() {
        return new AiListingSuggestResponseDTO("", "", FAILED);
    }

    public static AiListingSuggestResponseDTO noProduct() {
        return new AiListingSuggestResponseDTO("", "", NO_PRODUCT);
    }
}
