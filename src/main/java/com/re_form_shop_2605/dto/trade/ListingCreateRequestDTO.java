package com.re_form_shop_2605.dto.trade;

import com.re_form_shop_2605.entity.Enum.DeliveryType;
import com.re_form_shop_2605.entity.Enum.Grade;
import com.re_form_shop_2605.entity.Enum.Sport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: 판매글 작성 요청 DTO
 * ─────────────────────────────────────────────────────
 */
public record ListingCreateRequestDTO(
        @NotBlank
        @Size(max = 200)
        String title,

        @NotBlank
        String description,

        @NotNull
        @Positive
        Integer price,

        @NotNull
        Grade condition,

        @NotNull
        Sport sport,

        @Size(max = 50)
        String league,

        @NotBlank
        @Size(max = 50)
        String team,

        // 등록·수정·검색 필터 화면과 같은 영문 사이즈만 (UniformSizes.ALLOWED)
        @Pattern(regexp = "XS|S|M|L|XL|XXL", message = "사이즈는 XS·S·M·L·XL·XXL 중 하나여야 합니다.")
        @Size(max = 10)
        String size,

        @NotNull
        DeliveryType tradeType,

        @Size(max = 10)
        List<@NotBlank String> imageUrls
) {
    // 현재 도메인 모델에 없는 league는 일단 무시하고, uniformName은 title과 동일한 값으로 채운다.
    public PostRequestDTO toPostRequestDTO() {
        return new PostRequestDTO(
                title,
                description,
                sport,
                team,
                title,
                condition,
                size,
                Boolean.FALSE,
                price != null ? price : 0,
                tradeType
        );
    }
}
