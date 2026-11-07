package com.re_form_shop_2605.controller.admin;

import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.repository.trade.PostRepository;
import com.re_form_shop_2605.service.trade.PostVectorService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "관리자 API", description = "임베딩")
public class PostEmbeddingAdminController {

    private final PostRepository postRepository;
    private final PostVectorService postVectorService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /* 전체 판매글 재임베딩 - 임베딩 모델을 바꾼 뒤 기존 벡터를 새 모델로 다시 만들 때 사용 */
    // 판매글 등록 때와 같은 PostVectorService.savePostVector로 저장해 텍스트 구성과 문서 id를 맞춘다 (같은 postId는 덮어씀)
    @PostMapping("/admin/embed/posts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> embedAllExistingPosts() {
        if (vectorStoreProvider.getIfAvailable() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("벡터 스토어가 비활성화되어 임베딩을 실행할 수 없습니다.");
        }

        int page = 0, size = 100, totalEmbedded = 0, totalFailed = 0;
        while (true) {
            List<Post> batch = postRepository.findAll(PageRequest.of(page++, size)).getContent();
            if (batch.isEmpty()) break;
            for (Post post : batch) {
                if (postVectorService.savePostVector(post)) {
                    totalEmbedded++;
                } else {
                    totalFailed++;
                }
            }
        }
        return ResponseEntity.ok("임베딩 완료: " + totalEmbedded + "건, 실패: " + totalFailed + "건");
    }
}
