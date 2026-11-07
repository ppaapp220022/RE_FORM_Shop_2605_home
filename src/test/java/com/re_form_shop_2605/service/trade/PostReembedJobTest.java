package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.entity.Enum.Sport;
import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.repository.trade.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 재임베딩 백그라운드 작업(PostReembedJob) 단위 테스트 - 저장소·벡터 서비스는 mock
 * ─────────────────────────────────────────────────────
 */
class PostReembedJobTest {

    private static List<Post> posts(long from, long to) {
        return LongStream.rangeClosed(from, to)
                .mapToObj(id -> Post.builder().postId(id).title("t").sport(Sport.SOCCER).team("팀").uniformName("u")
                        .content("c").build())
                .toList();
    }

    /* 페이지 번호마다 주어진 목록을 돌려주고, 그 뒤는 빈 페이지 */
    private static PostRepository repository(List<List<Post>> pages) {
        PostRepository repository = mock(PostRepository.class);
        when(repository.count()).thenReturn((long) pages.stream().mapToInt(List::size).sum());
        when(repository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            assertThat(pageable.getSort().getOrderFor("postId")).isNotNull();
            List<Post> content = pageable.getPageNumber() < pages.size() ? pages.get(pageable.getPageNumber()) : List.of();
            return (Page<Post>) new PageImpl<>(content);
        });
        return repository;
    }

    @Test
    void run_실패_없이_끝나면_이전_모델_벡터를_지우고_기준값_검색_결과를_기록() {
        PostVectorService vectorService = mock(PostVectorService.class);
        when(vectorService.savePostVectors(anyList()))
                .thenReturn(new PostVectorService.BatchResult(100, 0, null))
                .thenReturn(new PostVectorService.BatchResult(50, 0, null));
        when(vectorService.deleteVectorsOfOtherModels()).thenReturn(30);
        when(vectorService.checkSearch(anyString(), anyInt(), anyDouble()))
                .thenReturn(new PostVectorService.SearchCheck(4, 0.512));
        PostReembedJob job = new PostReembedJob(repository(List.of(posts(1, 100), posts(101, 150))), vectorService);

        assertThat(job.start()).isTrue();
        PostReembedJob.Status status = await(job);

        assertThat(status.state()).isEqualTo(PostReembedJob.State.DONE);
        assertThat(status.total()).isEqualTo(150);
        assertThat(status.processed()).isEqualTo(150);
        assertThat(status.embedded()).isEqualTo(150);
        assertThat(status.failed()).isZero();
        assertThat(status.cleanup()).isEqualTo("이전 모델 벡터 30건 삭제");
        assertThat(status.searchCheck()).isEqualTo("기준값 0.30 이상 4건 (최고 점수 0.512)");
        verify(vectorService).checkSearch("유니폼", 5, PostSearchService.SIMILARITY_THRESHOLD);
        assertThat(status.finishedAt()).isNotNull();
    }

    @Test
    void run_실패가_있으면_이전_모델_벡터를_남김() {
        PostVectorService vectorService = mock(PostVectorService.class);
        when(vectorService.savePostVectors(anyList()))
                .thenReturn(new PostVectorService.BatchResult(98, 2, "RateLimitException: slow down"));
        when(vectorService.checkSearch(anyString(), anyInt(), anyDouble()))
                .thenReturn(new PostVectorService.SearchCheck(0, null));
        PostReembedJob job = new PostReembedJob(repository(List.of(posts(1, 100))), vectorService);

        job.start();
        PostReembedJob.Status status = await(job);

        assertThat(status.state()).isEqualTo(PostReembedJob.State.DONE);
        assertThat(status.failed()).isEqualTo(2);
        assertThat(status.lastError()).contains("RateLimitException");
        assertThat(status.cleanup()).startsWith("건너뜀 - 실패 2건");
        assertThat(status.searchCheck()).isEqualTo("기준값 0.30 이상 0건 (최고 점수 -)");
        verify(vectorService, never()).deleteVectorsOfOtherModels();
    }

    @Test
    void run_하나도_저장하지_못하면_이전_모델_벡터를_남김() {
        PostVectorService vectorService = mock(PostVectorService.class);
        PostReembedJob job = new PostReembedJob(repository(List.of()), vectorService);

        job.start();
        PostReembedJob.Status status = await(job);

        assertThat(status.cleanup()).startsWith("건너뜀");
        verify(vectorService, never()).deleteVectorsOfOtherModels();
    }

    @Test
    void run_검색_확인이_실패하면_원인을_남김() {
        PostVectorService vectorService = mock(PostVectorService.class);
        when(vectorService.savePostVectors(anyList())).thenReturn(new PostVectorService.BatchResult(3, 0, null));
        when(vectorService.checkSearch(anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("wrap", new IllegalArgumentException("bad jsonpath")));
        PostReembedJob job = new PostReembedJob(repository(List.of(posts(1, 3))), vectorService);

        job.start();
        PostReembedJob.Status status = await(job);

        assertThat(status.state()).isEqualTo(PostReembedJob.State.DONE);
        assertThat(status.searchCheck()).isEqualTo("실패 - IllegalArgumentException: bad jsonpath");
    }

    @Test
    void run_도중_예외가_나면_FAILED() {
        PostVectorService vectorService = mock(PostVectorService.class);
        when(vectorService.savePostVectors(anyList())).thenReturn(new PostVectorService.BatchResult(3, 0, null));
        when(vectorService.deleteVectorsOfOtherModels()).thenThrow(new IllegalStateException("pg down"));
        PostReembedJob job = new PostReembedJob(repository(List.of(posts(1, 3))), vectorService);

        job.start();
        PostReembedJob.Status status = await(job);

        assertThat(status.state()).isEqualTo(PostReembedJob.State.FAILED);
        assertThat(status.embedded()).isEqualTo(3);
        assertThat(status.lastError()).isEqualTo("IllegalStateException: pg down");
    }

    @Test
    void start_실행_중이면_다시_시작하지_않음() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        PostVectorService vectorService = mock(PostVectorService.class);
        when(vectorService.savePostVectors(anyList())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return new PostVectorService.BatchResult(3, 0, null);
        });
        PostReembedJob job = new PostReembedJob(repository(List.of(posts(1, 3))), vectorService);

        assertThat(job.start()).isTrue();
        assertThat(job.start()).isFalse();
        release.countDown();
        assertThat(await(job).state()).isEqualTo(PostReembedJob.State.DONE);
        // 끝난 뒤에는 다시 시작할 수 있다
        assertThat(job.start()).isTrue();
        await(job);
    }

    @Test
    void run_묶음이_연달아_통째로_실패하면_중단() {
        PostVectorService vectorService = mock(PostVectorService.class);
        when(vectorService.savePostVectors(anyList()))
                .thenReturn(new PostVectorService.BatchResult(0, 100, "AuthenticationException: invalid key"));
        PostReembedJob job = new PostReembedJob(
                repository(List.of(posts(1, 100), posts(101, 200), posts(201, 300), posts(301, 400))), vectorService);

        job.start();
        PostReembedJob.Status status = await(job);

        assertThat(status.state()).isEqualTo(PostReembedJob.State.FAILED);
        assertThat(status.processed()).isEqualTo(300);
        assertThat(status.lastError()).contains("3묶음 연속 전체 실패").contains("invalid key");
        verify(vectorService, times(3)).savePostVectors(anyList());
        verify(vectorService, never()).deleteVectorsOfOtherModels();
    }

    @Test
    void status_재시작_뒤에는_다시_실행하라는_안내() {
        PostReembedJob job = new PostReembedJob(repository(List.of()), mock(PostVectorService.class));

        assertThat(job.status().state()).isEqualTo(PostReembedJob.State.IDLE);
        assertThat(job.status().cleanup()).contains("다시 실행");
    }

    @Test
    void start_실행기가_닫혔으면_FAILED로_되돌림() {
        PostReembedJob job = new PostReembedJob(repository(List.of(posts(1, 3))), mock(PostVectorService.class));
        job.shutdown();

        assertThat(job.start()).isFalse();
        assertThat(job.status().state()).isEqualTo(PostReembedJob.State.FAILED);
        assertThat(job.status().lastError()).startsWith("RejectedExecutionException");
    }

    /* 작업 스레드가 끝날 때까지 짧게 쉬며 기다린다. 5초 안에 끝나지 않으면 실패 */
    private static PostReembedJob.Status await(PostReembedJob job) {
        long deadline = System.currentTimeMillis() + 5000;
        while (job.status().state() == PostReembedJob.State.RUNNING) {
            if (System.currentTimeMillis() > deadline) {
                fail("재임베딩 작업이 5초 안에 끝나지 않았습니다: " + job.status());
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("대기 중 인터럽트");
            }
        }
        return job.status();
    }
}
