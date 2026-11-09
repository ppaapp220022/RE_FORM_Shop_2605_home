package com.re_form_shop_2605.repository.trade.projection;

import com.re_form_shop_2605.entity.Enum.PostStatus;

public interface PostStatusProjection {
    Long getPostId();
    PostStatus getStatus();
}
