package com.re_form_shop_2605.controller.admin;

import com.re_form_shop_2605.service.trade.PostReembedJob;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "관리자 API", description = "임베딩")
public class PostEmbeddingAdminController {

    private final PostReembedJob postReembedJob;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /* 전체 판매글 재임베딩 시작 - 임베딩 모델을 바꾼 뒤 기존 벡터를 새 모델로 다시 만들 때 사용 */
    // 수천 건은 요청 안에서 끝나지 않으므로 백그라운드로 실행하고 바로 202를 돌려준다. 진행 상황은 GET으로 확인
    @PostMapping("/admin/embed/posts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PostReembedJob.Status> embedAllExistingPosts() {
        if (vectorStoreProvider.getIfAvailable() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!postReembedJob.start()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(postReembedJob.status());
        }
        return ResponseEntity.accepted().body(postReembedJob.status());
    }

    /* 재임베딩 진행 상황 */
    @GetMapping("/admin/embed/posts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PostReembedJob.Status> readEmbedStatus() {
        return ResponseEntity.ok(postReembedJob.status());
    }
}
