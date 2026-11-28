package com.cue.evaluation.service;

import com.cue.evaluation.config.EvaluationExecutorConfig;
import com.cue.evaluation.dto.EvaluationRunResult;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.global.error.ErrorLocation;
import com.cue.global.error.ErrorReason;
import com.cue.idea.entity.EvaluationStatus;
import com.cue.idea.repository.IdeaRepository;
import com.cue.trend.service.GlobalTrendService;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 평가 실행을 비동기·직렬로 돌리고 아이디어의 평가 상태({@link EvaluationStatus})를 기록한다.
 *
 * <ul>
 *   <li>{@link #submit}: IDLE/FAILED → QUEUED로 바꾸고 전용 단일 스레드 실행기({@code evaluationExecutor})에 넣는다.
 *       이미 QUEUED/RUNNING이면 아무것도 하지 않고 현재 상태를 돌려준다(멱등). 대기열이 가득 차면 E004.</li>
 *   <li>{@link #execute}: 실행기 스레드와 일일 배치가 같이 쓰는 실제 실행. <b>전역 락</b>으로 한 번에 하나만 평가해 수동 요청과 배치가
 *       Gemini를 동시에 두드리지 않게 한다. QUEUED(배치는 IDLE/FAILED) → RUNNING → IDLE(성공) 또는 FAILED(예외, 에러 코드 기록). 예외는 로그로 남기고 삼키지 않는다(다시 던진다).</li>
 *   <li>{@link #executeBatch}·{@link #prepareTrendForBatch}: 일일 배치용 실행. <b>수동 평가(수동 요청·저장 직후 자동·시연)가 항상 배치보다 우선</b>한다.
 *       수동 작업은 큐에 넣기 전에 대기 수({@code manualPending})를 올리고 끝난 뒤에 내리며, 배치는 락을 잡은 뒤 이 값이 0일 때만 시작한다
 *       (0이 아니면 락을 풀고 기다림). 확인과 락 획득이 한 락 안에서 일어나므로 그 사이에 수동 요청이 끼어도 배치가 먼저 시작하지 못한다.
 *       이미 실행 중인 배치 아이디어 1개는 끊지 않고(비선점) 끝날 때까지 기다린 뒤 수동 평가가 먼저 실행된다.</li>
 * </ul>
 * 상태 전이는 {@link IdeaRepository}의 조건부 UPDATE로만 하고, 평가 동안 DB 트랜잭션을 잡지 않는다.
 */
@Slf4j
@Service
public class EvaluationQueueService {
    /** 실행기 작업은 큐에 넣은 것(QUEUED)만 집는다 (그사이 배치가 먼저 평가했다면 건너뜀). */
    private static final Set<EvaluationStatus> WORKER_ALLOWED = EnumSet.of(EvaluationStatus.QUEUED);
    /** 일일 배치는 대기·진행 중이 아닌 아이디어만 평가한다 (QUEUED는 사용자 요청이라 실행기가 처리한다). */
    private static final Set<EvaluationStatus> BATCH_ALLOWED =
            EnumSet.of(EvaluationStatus.IDLE, EvaluationStatus.FAILED);

    private final IdeaRepository ideas;
    private final IdeaEvaluationPipeline evaluation;
    private final ThreadPoolTaskExecutor executor;
    private final GlobalTrendService globalTrend;
    private final ReentrantLock lock = new ReentrantLock(true);
    /** 큐에 넣었지만 아직 끝나지 않은 수동 작업(평가·트렌드 갱신) 수. 0이 아니면 배치는 시작하지 않고 양보한다. */
    private final AtomicInteger manualPending = new AtomicInteger();

    public EvaluationQueueService(IdeaRepository ideas, IdeaEvaluationPipeline evaluation, GlobalTrendService globalTrend,
                                @Qualifier(EvaluationExecutorConfig.EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.ideas = ideas;
        this.evaluation = evaluation;
        this.executor = executor;
        this.globalTrend = globalTrend;
    }

    /**
     * 글로벌 트렌드 수집·요약을 같은 실행기·전역 락에 넣는다 (시연 API용). 이후에 큐에 들어가는 평가는 이 작업 뒤에 실행되어 새 트렌드를 쓴다.
     *
     * @throws CustomException 대기열 가득 E004
     */
    public void submitTrendRefresh() {
        manualPending.incrementAndGet(); // 락 대기 전에 올려 배치가 먼저 끼어들지 못하게 한다
        try {
            executor.execute(() -> {
                try {
                    lock.lock();
                    try {
                        globalTrend.refresh();
                    } catch (RuntimeException e) {
                        log.error("글로벌 트렌드 수동 갱신 실패", e);
                    } finally {
                        lock.unlock();
                    }
                } finally {
                    manualPending.decrementAndGet();
                }
            });
        } catch (TaskRejectedException e) {
            manualPending.decrementAndGet();
            throw new CustomException(ErrorCode.EVALUATION_QUEUE_FULL);
        }
    }

    /**
     * 평가를 큐에 넣는다.
     *
     * @param throwOnFull 대기열이 가득 찼을 때 true면 E004를 던지고(상태는 IDLE로 되돌림), false면 FAILED(E004)로 기록만 한다 (자동 시작용)
     * @return QUEUED(새로 넣음) 또는 이미 진행 중이던 QUEUED/RUNNING
     * @throws CustomException 없는 아이디어 I001, 대기열 가득 E004
     */
    public EvaluationStatus submit(Long ideaId, boolean throwOnFull) {
        manualPending.incrementAndGet(); // QUEUED로 바꾸기 전에 올려 배치가 먼저 끼어들지 못하게 한다
        boolean queued = false;
        try {
            queued = ideas.markQueued(ideaId) > 0;
        } finally {
            if (!queued) {
                manualPending.decrementAndGet();
            }
        }
        if (!queued) {
            return ideas.findEvaluationStatus(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        }
        try {
            executor.execute(() -> runQueued(ideaId));
            return EvaluationStatus.QUEUED;
        } catch (TaskRejectedException e) {
            manualPending.decrementAndGet();
            if (throwOnFull) {
                ideas.markIdle(ideaId, LocalDateTime.now());
                throw new CustomException(ErrorCode.EVALUATION_QUEUE_FULL);
            }
            ideas.markFailed(ideaId, LocalDateTime.now(), ErrorCode.EVALUATION_QUEUE_FULL.getCode(),
                    ErrorLocation.EVALUATION_QUEUE.label(), null, ErrorReason.QUEUE_FULL.text());
            log.warn("평가 대기열이 가득 차 자동 평가를 시작하지 못했습니다 ideaId={}", ideaId);
            return EvaluationStatus.FAILED;
        }
    }

    /**
     * 일일 배치가 아이디어 1개를 평가한다. 수동 작업이 대기·진행 중이면 시작하지 않고 모두 끝나길 기다린다(수동 우선).
     * 확인을 락 안에서 하므로, 확인 뒤 락을 얻기까지의 사이에 수동 요청이 올라와도 배치가 먼저 시작하지 못한다.
     * 이미 시작한 평가는 끊지 않는다. 기다리는 중 인터럽트되면(종료) 평가하지 않고 변화 없음으로 돌아간다.
     *
     * @throws RuntimeException 평가 실패 (상태는 FAILED와 에러 코드로 기록된 뒤다)
     */
    public EvaluationRunResult executeBatch(Long ideaId) {
        while (true) {
            if (!awaitManualIdle()) {
                return EvaluationRunResult.noChange();
            }
            lock.lock();
            try {
                if (manualPending.get() == 0) {
                    return execute(ideaId, BATCH_ALLOWED, false);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 일일 배치가 아이디어 루프 전에 오늘의 글로벌 트렌드를 확보한다. 수동 작업이 있으면 양보하고, 전역 락 안에서 수집해
     * 같은 시각에 Gemini를 두드리는 다른 평가와 겹치지 않게 한다.
     */
    public void prepareTrendForBatch() {
        while (true) {
            if (!awaitManualIdle()) {
                return;
            }
            lock.lock();
            try {
                if (manualPending.get() == 0) {
                    globalTrend.today();
                    return;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /** 수동 작업이 모두 끝날 때까지 락 없이 기다린다. 인터럽트되면 false. */
    private boolean awaitManualIdle() {
        if (manualPending.get() > 0) {
            log.info("수동 평가가 먼저 끝나길 기다립니다 (대기·진행 {}건)", manualPending.get());
        }
        while (manualPending.get() > 0) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 기록할 실패 원인. 위치를 붙이지 못한 곳에서 난 실패는 "아이디어 평가"로 남긴다. */
    private static ErrorDetail failureDetail(RuntimeException e) {
        if (e instanceof CustomException custom) {
            return custom.getDetail() != null ? custom.getDetail()
                    : ErrorDetail.from(custom.getErrorCode()).at(ErrorLocation.EVALUATION);
        }
        return ErrorDetail.of(null, ErrorReason.INTERNAL_ERROR).at(ErrorLocation.EVALUATION);
    }

    private void runQueued(Long ideaId) {
        try {
            execute(ideaId, WORKER_ALLOWED, true);
        } catch (RuntimeException e) {
            // execute가 이미 로그와 FAILED 상태를 남겼다. 실행기 스레드로 예외가 새지 않게만 한다.
        } finally {
            manualPending.decrementAndGet();
        }
    }

    /**
     * 전역 락 안에서 한 아이디어를 평가하며 상태를 기록한다. 이미 다른 상태라 시작하지 못하면 아무것도 하지 않는다(NO_CHANGE).
     *
     * @throws RuntimeException 평가 실패 (상태는 FAILED와 에러 코드로 기록된 뒤다)
     */
    private EvaluationRunResult execute(Long ideaId, Set<EvaluationStatus> allowedFrom, boolean userRequested) {
        lock.lock();
        try {
            if (ideas.markRunning(ideaId, LocalDateTime.now(), allowedFrom) == 0) {
                log.info("평가를 건너뜁니다 (이미 처리됐거나 대상이 아님) ideaId={}", ideaId);
                return EvaluationRunResult.noChange();
            }
            try {
                EvaluationRunResult result = evaluation.evaluate(ideaId, userRequested);
                ideas.markIdle(ideaId, LocalDateTime.now());
                return result;
            } catch (RuntimeException e) {
                String code = e instanceof CustomException ce
                        ? ce.getErrorCode().getCode() : ErrorCode.INTERNAL_SERVER_ERROR.getCode();
                ErrorDetail detail = failureDetail(e);
                log.error("평가 실패 ideaId={} code={} {}", ideaId, code, detail.summary(), e);
                ideas.markFailed(ideaId, LocalDateTime.now(), code, detail.location(), detail.api(), detail.reason());
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }
}
