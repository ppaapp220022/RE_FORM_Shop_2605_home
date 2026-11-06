package com.re_form_shop_2605.controller.common.advice;

import com.re_form_shop_2605.dto.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomRestAdviceTest {

    @Test
    void 업로드_용량_초과는_413으로_응답한다() {
        // 용량 초과가 500 서버 오류로 섞이지 않아야 프론트가 "더 작은 파일" 안내를 할 수 있다
        ResponseEntity<ApiResponse<Map<String, String>>> response = new CustomRestAdvice()
                .handleMaxUploadSizeExceededException(new MaxUploadSizeExceededException(10L * 1024 * 1024));

        assertEquals(HttpStatus.CONTENT_TOO_LARGE, response.getStatusCode());
    }
}
