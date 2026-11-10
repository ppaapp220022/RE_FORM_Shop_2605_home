package com.re_form_shop_2605.dto.trade;

import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.Sport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: 판매글 수정 요청 DTO
 * ─────────────────────────────────────────────────────
 */
// 프론트엔드 API 명세에 맞춘 판매글 수정 요청 DTO
public record ListingUpdateRequestDTO(
        @Size(max = 200)
        String title,

        String description,

        Sport sport,

        @Size(max = 50)
        String team,

        @Size(max = 200)
        String uniformName,

        Grade condition,

        // 등록·수정·검색 필터 화면과 같은 영문 사이즈만 (UniformSizes.ALLOWED)
        @Pattern(regexp = "XS|S|M|L|XL|XXL", message = "사이즈는 XS·S·M·L·XL·XXL 중 하나여야 합니다.")
        @Size(max = 10)
        String size,

        @Positive
        Integer price,

        DeliveryType tradeType,

        @Size(max = 10)
        List<@NotBlank String> imageUrls
) {
    // 프론트 요청 필드를 현재 도메인 수정 DTO로 맞춘다.
    public PostUpdateRequestDTO toPostUpdateRequestDTO() {
        return new PostUpdateRequestDTO(
                title,
                description,
                sport,
                team,
                uniformName,
                condition,
                size,
                null,
                price,
                tradeType
        );
    }
}
