package com.cue.evaluation.service;

import com.cue.evaluation.config.EvaluationExecutorConfig;
import com.cue.evaluation.dto.EvaluationRunResult;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.EvaluationStatus;
import com.cue.idea.repository.IdeaRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
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
 *       Gemini를 동시에 두드리지 않게 한다. QUEUED/IDLE → RUNNING → IDLE(성공) 또는 FAILED(예외, 에러 코드 기록). 예외는 로그로 남기고 삼키지 않는다(다시 던진다).</li>
 * </ul>
 * 상태 전이는 {@link IdeaRepository}의 조건부 UPDATE로만 하고, 평가 동안 DB 트랜잭션을 잡지 않는다.
 */
@Slf4j
@Service
public class EvaluationJobService {
    /** 실행기 작업은 큐에 넣은 것(QUEUED)만 집는다 (그사이 배치가 먼저 평가했다면 건너뜀). */
    static final Set<EvaluationStatus> WORKER_ALLOWED = EnumSet.of(EvaluationStatus.QUEUED);
    /** 일일 배치는 대기 중이 아닌 모든 아이디어를 대상으로 한다 (QUEUED도 같이 처리해 중복 평가를 피함). */
    public static final Set<EvaluationStatus> BATCH_ALLOWED =
            EnumSet.of(EvaluationStatus.IDLE, EvaluationStatus.FAILED, EvaluationStatus.QUEUED);

    private final IdeaRepository ideas;
    private final DailyEvaluationService evaluation;
    private final ThreadPoolTaskExecutor executor;
    private final ReentrantLock lock = new ReentrantLock(true);

    public EvaluationJobService(IdeaRepository ideas, DailyEvaluationService evaluation,
                                @Qualifier(EvaluationExecutorConfig.EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.ideas = ideas;
        this.evaluation = evaluation;
        this.executor = executor;
    }

    /**
     * 평가를 큐에 넣는다.
     *
     * @param throwOnFull 대기열이 가득 찼을 때 true면 E004를 던지고(상태는 IDLE로 되돌림), false면 FAILED(E004)로 기록만 한다 (자동 시작용)
     * @return QUEUED(새로 넣음) 또는 이미 진행 중이던 QUEUED/RUNNING
     * @throws CustomException 없는 아이디어 I001, 대기열 가득 E004
     */
    public EvaluationStatus submit(Long ideaId, boolean throwOnFull) {
        if (ideas.markQueued(ideaId) == 0) {
            return ideas.findEvaluationStatus(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        }
        try {
            executor.execute(() -> runQueued(ideaId));
            return EvaluationStatus.QUEUED;
        } catch (TaskRejectedException e) {
            if (throwOnFull) {
                ideas.markIdle(ideaId, LocalDateTime.now());
                throw new CustomException(ErrorCode.EVALUATION_QUEUE_FULL);
            }
            ideas.markFailed(ideaId, LocalDateTime.now(), ErrorCode.EVALUATION_QUEUE_FULL.getCode());
            log.warn("평가 대기열이 가득 차 자동 평가를 시작하지 못했습니다 ideaId={}", ideaId);
            return EvaluationStatus.FAILED;
        }
    }

    private void runQueued(Long ideaId) {
        try {
            execute(ideaId, WORKER_ALLOWED);
        } catch (RuntimeException e) {
            // execute가 이미 로그와 FAILED 상태를 남겼다. 실행기 스레드로 예외가 새지 않게만 한다.
        }
    }

    /**
     * 전역 락 안에서 한 아이디어를 평가하며 상태를 기록한다. 이미 다른 상태라 시작하지 못하면 아무것도 하지 않는다(NO_CHANGE).
     *
     * @throws RuntimeException 평가 실패 (상태는 FAILED와 에러 코드로 기록된 뒤다)
     */
    public EvaluationRunResult execute(Long ideaId, Set<EvaluationStatus> allowedFrom) {
        lock.lock();
        try {
            if (ideas.markRunning(ideaId, LocalDateTime.now(), allowedFrom) == 0) {
                log.info("평가를 건너뜁니다 (이미 처리됐거나 대상이 아님) ideaId={}", ideaId);
                return EvaluationRunResult.noChange();
            }
            try {
                EvaluationRunResult result = evaluation.evaluate(ideaId);
                ideas.markIdle(ideaId, LocalDateTime.now());
                return result;
            } catch (RuntimeException e) {
                String code = e instanceof CustomException ce
                        ? ce.getErrorCode().getCode() : ErrorCode.INTERNAL_SERVER_ERROR.getCode();
                log.error("평가 실패 ideaId={} code={}", ideaId, code, e);
                ideas.markFailed(ideaId, LocalDateTime.now(), code);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }
}
