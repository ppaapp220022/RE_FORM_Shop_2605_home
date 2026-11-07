package com.re_form_shop_2605.controller.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class VersionControllerTest {

    @Test
    void readVersion_빌드_버전과_기동_시각을_돌려줌() {
        LocalDateTime before = LocalDateTime.now();
        VersionController controller = new VersionController("a0545fb");

        VersionController.VersionResponse body = controller.readVersion().getBody();

        assertThat(body.version()).isEqualTo("a0545fb");
        assertThat(body.startedAt()).isAfterOrEqualTo(before);
        // 기동 시각은 요청마다 바뀌지 않는다
        assertThat(controller.readVersion().getBody()).isSameAs(body);
    }
}
