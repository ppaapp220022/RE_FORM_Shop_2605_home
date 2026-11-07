package com.re_form_shop_2605.controller.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionControllerTest {

    @Test
    void readVersion_빌드_버전을_돌려줌() {
        VersionController controller = new VersionController("a0545fb");

        VersionController.VersionResponse body = controller.readVersion().getBody();

        assertThat(body).isEqualTo(new VersionController.VersionResponse("a0545fb"));
    }
}
