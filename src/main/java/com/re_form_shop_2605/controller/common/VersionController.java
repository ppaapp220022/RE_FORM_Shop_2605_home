package com.re_form_shop_2605.controller.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 지금 응답하는 백엔드의 빌드 버전(커밋 SHA)
 *  - 배포 뒤 사이트가 실제로 새 버전으로 응답하는지 서버 접속 없이 확인하는 용도
 *  - version은 CI가 이미지 빌드 때 APP_VERSION으로 넣는다 (로컬 실행은 dev)
 *  - 로그인 없이 열려 있으므로 기동 시각처럼 배포·재시작 주기를 드러내는 값은 넣지 않는다
 * ─────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

    public record VersionResponse(String version) {
    }

    private final VersionResponse response;

    public VersionController(@Value("${app.version:dev}") String version) {
        this.response = new VersionResponse(version);
    }

    @GetMapping
    public ResponseEntity<VersionResponse> readVersion() {
        return ResponseEntity.ok(response);
    }
}
