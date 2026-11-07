package com.re_form_shop_2605.service.trade;

import com.re_form_shop_2605.entity.trade.Post;
import com.re_form_shop_2605.repository.trade.PostRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 전체 판매글 재임베딩을 백그라운드에서 실행하고 진행 상황을 보관
 *  - 판매글이 수천 건이면 HTTP 요청 안에서 끝나지 않아 프록시가 504로 끊으므로, 요청은 시작만 하고 바로 응답한다
 *  - 한 번에 하나만 실행 (실행 중에 다시 요청하면 거절)
 *  - 실패 없이 끝났을 때만 이전 모델 벡터를 지운다 (실패가 있으면 이전 모델로 롤백할 수 있게 남겨 둔다)
 *  - 끝나면 운영 기준값으로 실제 검색이 되는지 확인해 결과를 남긴다
 *    (검색 실패는 PostSearchService가 빈 결과로 삼키므로 여기서 확인해야 원인이 보인다)
 * ─────────────────────────────────────────────────────
 */
@Log4j2
@Service
public class PostReembedJob {

    static final int BATCH_SIZE = 100;
    static final int MAX_CONSECUTIVE_FAILED_BATCHES = 3;
    private static final String SEARCH_CHECK_QUERY = "유니폼";

    public enum State { IDLE, RUNNING, DONE, FAILED }

    /* 상태는 메모리에만 있어 서버가 재시작되면 IDLE로 돌아간다. IDLE이면 cleanup에 그 사실과 다시 실행하라는 안내를 넣는다
       cleanup: 이전 모델 벡터 삭제 결과나 건너뛴 이유
       searchCheck: 끝난 뒤 운영 기준값을 넘는 검색 결과 수(최대 5)와 최고 점수, 또는 오류 메시지 */
    public record Status(State state, long total, int processed, int embedded, int failed, String cleanup,
                         String searchCheck, String lastError, LocalDateTime startedAt, LocalDateTime finishedAt) {

        static final Status IDLE = new Status(State.IDLE, 0, 0, 0, 0,
                "이 서버가 시작된 뒤 실행한 재임베딩이 없습니다. 재시작으로 이전 작업이 끊겼을 수 있으니 다시 실행하세요 (덮어쓰기라 안전)",
                null, null, null, null);
    }

    private final PostRepository postRepository;
    private final PostVectorService postVectorService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "post-reembed");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Status> status = new AtomicReference<>(Status.IDLE);

    public PostReembedJob(PostRepository postRepository, PostVectorService postVectorService) {
        this.postRepository = postRepository;
        this.postVectorService = postVectorService;
    }

    public Status status() {
        return status.get();
    }

    /* 재임베딩 시작 - 이미 실행 중이면 false */
    public boolean start() {
        Status current = status.get();
        if (current.state() == State.RUNNING) {
            return false;
        }
        Status running = new Status(State.RUNNING, postRepository.count(), 0, 0, 0, null, null, null,
                LocalDateTime.now(), null);
        if (!status.compareAndSet(current, running)) {
            return false;
        }
        try {
            executor.submit(this::run);
        } catch (RejectedExecutionException exception) {
            // 종료 중이라 실행기가 닫혔으면 RUNNING에 멈추지 않도록 되돌린다
            status.set(new Status(State.FAILED, running.total(), 0, 0, 0, null, null,
                    PostVectorService.rootMessage(exception), running.startedAt(), LocalDateTime.now()));
            return false;
        }
        return true;
    }

    void run() {
        try {
            int page = 0, consecutiveFailedBatches = 0;
            while (true) {
                List<Post> batch = postRepository.findAll(PageRequest.of(page++, BATCH_SIZE, Sort.by("postId"))).getContent();
                if (batch.isEmpty()) break;
                PostVectorService.BatchResult result = postVectorService.savePostVectors(batch);
                // 묶음이 연달아 통째로 실패하면 전체 장애로 보고 멈춘다 (계속 돌면 실패 호출과 로그만 쌓인다)
                consecutiveFailedBatches = result.saved() == 0 ? consecutiveFailedBatches + 1 : 0;
                status.updateAndGet(s -> new Status(s.state(), s.total(), s.processed() + batch.size(),
                        s.embedded() + result.saved(), s.failed() + result.failed(), null, null,
                        result.error() != null ? result.error() : s.lastError(), s.startedAt(), null));
                log.info("재임베딩 진행 {} / {} (실패 {})", status.get().processed(), status.get().total(), status.get().failed());
                if (consecutiveFailedBatches >= MAX_CONSECUTIVE_FAILED_BATCHES) {
                    throw new IllegalStateException(consecutiveFailedBatches + "묶음 연속 전체 실패로 중단 - " + result.error());
                }
            }

            Status progress = status.get();
            String cleanup;
            if (progress.failed() == 0 && progress.embedded() > 0) {
                cleanup = "이전 모델 벡터 " + postVectorService.deleteVectorsOfOtherModels() + "건 삭제";
            } else {
                // 실패가 있으면 이전 모델 벡터를 남겨 롤백할 수 있게 한다 (검색은 현재 모델 벡터만 보므로 남아도 섞이지 않음)
                cleanup = "건너뜀 - 실패 " + progress.failed() + "건, 성공 " + progress.embedded() + "건. 다시 실행해 실패 없이 끝나면 삭제";
            }
            String searchCheck;
            try {
                PostVectorService.SearchCheck check = postVectorService.checkSearch(
                        SEARCH_CHECK_QUERY, 5, PostSearchService.SIMILARITY_THRESHOLD);
                searchCheck = String.format(Locale.ROOT, "기준값 %.2f 이상 %d건 (최고 점수 %s)",
                        PostSearchService.SIMILARITY_THRESHOLD, check.matched(),
                        check.topScore() == null ? "-" : String.format(Locale.ROOT, "%.3f", check.topScore()));
            } catch (Exception exception) {
                log.error("재임베딩 뒤 검색 확인 실패", exception);
                searchCheck = "실패 - " + PostVectorService.rootMessage(exception);
            }
            String checked = searchCheck;
            status.updateAndGet(s -> new Status(State.DONE, s.total(), s.processed(), s.embedded(), s.failed(), cleanup,
                    checked, s.lastError(), s.startedAt(), LocalDateTime.now()));
            log.info("재임베딩 완료 {}", status.get());
        } catch (Exception exception) {
            log.error("재임베딩 중단", exception);
            status.updateAndGet(s -> new Status(State.FAILED, s.total(), s.processed(), s.embedded(), s.failed(),
                    s.cleanup(), s.searchCheck(), PostVectorService.rootMessage(exception), s.startedAt(),
                    LocalDateTime.now()));
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
