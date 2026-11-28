package com.cue.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cue.evaluation.dto.EvaluationRunResult;
import com.cue.evaluation.service.IdeaEvaluationPipeline;
import com.cue.evaluation.service.EvaluationQueueService;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.repository.IdeaRepository;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.fetch.SourceApiLog;
import com.cue.trend.service.GlobalTrendService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 수동 평가가 일일 배치보다 항상 우선하는지 검증한다 (DB·Gemini 없이 목으로). */
class EvaluationQueuePriorityTest {
    private final IdeaRepository ideas = mock(IdeaRepository.class);
    private final IdeaEvaluationPipeline evaluation = mock(IdeaEvaluationPipeline.class);
    private final GlobalTrendService trend = mock(GlobalTrendService.class);

    private final List<String> order = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch firstStarted = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final ExecutorService batchThread = Executors.newSingleThreadExecutor();
    private ThreadPoolTaskExecutor executor;

    private EvaluationQueueService jobs(int queueCapacity) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        when(ideas.markQueued(anyLong())).thenReturn(1);
        when(ideas.markRunning(anyLong(), any(), any())).thenReturn(1);
        when(evaluation.evaluate(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            if (id == 1L) {
                order.add("수동1");
                firstStarted.countDown();
                releaseFirst.await(10, TimeUnit.SECONDS); // 첫 수동 평가를 붙잡아 둔다
            } else if (id == 2L) {
                order.add("수동2");
            } else {
                order.add("배치" + id);
            }
            return EvaluationRunResult.evaluated(false);
        });
        return new EvaluationQueueService(ideas, evaluation, trend,
                new SourceApiLog("", mock(SourceApiProperties.class), "", ""), executor);
    }

    @AfterEach
    void tearDown() {
        releaseFirst.countDown();
        batchThread.shutdownNow();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void 수동_작업이_대기_중이면_배치는_락을_먼저_기다렸어도_그_뒤에_시작한다() throws Exception {
        EvaluationQueueService jobs = jobs(10);
        jobs.submit(1L, true);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        jobs.submit(2L, true); // 실행기 대기열에 있어 아직 락에 도달하지 못한 수동 작업

        Future<EvaluationRunResult> batch = batchThread.submit(() -> jobs.executeBatch(3L));
        Thread.sleep(500); // 배치가 양보하며 기다리는 상태
        assertEquals(List.of("수동1"), List.copyOf(order));
        assertFalse(batch.isDone());

        releaseFirst.countDown();
        batch.get(10, TimeUnit.SECONDS);
        assertEquals(List.of("수동1", "수동2", "배치3"), List.copyOf(order));
    }

    @Test
    void 수동_작업이_없으면_배치는_바로_실행된다() throws Exception {
        EvaluationQueueService jobs = jobs(10);
        EvaluationRunResult result = batchThread.submit(() -> jobs.executeBatch(7L)).get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(List.of("배치7"), List.copyOf(order));
    }

    @Test
    void 대기열이_가득_차_수동_요청이_거절되면_대기_수가_원복되어_배치가_막히지_않는다() throws Exception {
        EvaluationQueueService jobs = jobs(0); // 대기열 0: 스레드가 바쁘면 즉시 거절
        jobs.submit(1L, true);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        CustomException e = assertThrows(CustomException.class, () -> jobs.submit(2L, true));
        assertEquals(ErrorCode.EVALUATION_QUEUE_FULL, e.getErrorCode());

        releaseFirst.countDown();
        EvaluationRunResult result = batchThread.submit(() -> jobs.executeBatch(3L)).get(10, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(List.of("수동1", "배치3"), List.copyOf(order));
    }
}
