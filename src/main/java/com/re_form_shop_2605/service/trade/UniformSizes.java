package com.re_form_shop_2605.service.trade;

import java.util.List;
import java.util.Locale;

/**
 * 설명: 판매글 사이즈 필터 값 정리
 *      - 화면은 XS·S·M·L·XL·XXL만 보낸다
 *      - 예전 글(더미 데이터 포함)은 사이즈가 숫자(85~110)로 저장돼 있어,
 *        PostMapper.xml의 itemSizeFilter가 문자와 같은 숫자 사이즈(85=XS … 110=XXL)도 함께 찾는다
 */
public final class UniformSizes {
    public static final List<String> ALLOWED = List.of("XS", "S", "M", "L", "XL", "XXL");

    private UniformSizes() {
    }

    /* 비어 있으면 null(필터 없음), 앞뒤 공백·대소문자는 정리, 허용하지 않는 값은 400 */
    public static String normalize(String itemSize) {
        if (itemSize == null || itemSize.isBlank()) {
            return null;
        }
        String normalized = itemSize.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED.contains(normalized)) {
            throw new IllegalArgumentException("지원하지 않는 사이즈입니다: " + itemSize.trim()
                    + " (허용: " + String.join(", ", ALLOWED) + ")");
        }
        return normalized;
    }
}
