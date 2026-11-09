package com.re_form_shop_2605.entity.trade;

import com.re_form_shop_2605.entity.Enum.PostStatus;

/* 판매글의 판매 상태가 바뀌어 저장됨 (PostStatusListener가 발행) */
public record PostStatusChangedEvent(Long postId, PostStatus status) {
}
