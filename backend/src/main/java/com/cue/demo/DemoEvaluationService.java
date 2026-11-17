package com.cue.demo;

import com.cue.demo.dto.DemoRunResponse;
import com.cue.evaluation.service.EvaluationJobService;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.EvaluationStatus;
import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.repository.IdeaRepository;
import com.cue.source.repository.SourceRepository;
import com.cue.trend.service.GlobalTrendService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 시연용 "지금 조회·평가" 실행. 호출한 사용자의 ACTIVE 아이디어를 기존 비동기 실행기 큐({@link EvaluationJobService})에 넣고,
 * 필요하면 글로벌 트렌드 수집도 같은 큐 맨 앞에 넣는다(다른 사용자 아이디어는 건드리지 않는다). 상태 전이는 수동 평가·배치와 같은 조건부 UPDATE를 쓰고,
 * 큐에 들어간 아이디어는 끝날 때까지 수정할 수 없다(I005). 평가 동안 트랜잭션을 잡지 않는다.
 *
 * <p>{@code force=true}면 트렌드 하루 1회·해시 캐시를 무시하고 다시 수집하고, 각 아이디어의 source 해시를 실제 해시와 다른 값으로 바꿔
 * 다음 조회에서 "변경 감지됨"이 되게 한다(자료를 다시 읽고 평가한다). 호출 수가 많아 Gemini 무료 한도를 쉽게 소진하므로 시연용으로만 켠다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoEvaluationService {
    /** source 해시에 넣는 값: SHA-256 hex(64자)가 될 수 없어 실제 해시와 항상 다르다. */
    public static final String FORCED_HASH = "FORCED-REFRESH";

    private final DemoProperties props;
    private final GeminiClient gemini;
    private final IdeaRepository ideas;
    private final SourceRepository sources;
    private final EvaluationJobService jobs;
    private final GlobalTrendService globalTrend;

    private final ConcurrentMap<Long, Instant> lastRun = new ConcurrentHashMap<>();

    /**
     * @throws CustomException 꺼져 있음 D001, 쿨다운 D002, Gemini 불가 E001, 아무것도 큐에 넣지 못할 만큼 대기열이 가득 E004
     */
    public DemoRunResponse run(Long userId, boolean force) {
        if (!props.enabled()) {
            throw new CustomException(ErrorCode.DEMO_API_DISABLED);
        }
        if (!gemini.available()) {
            throw new CustomException(ErrorCode.AI_NOT_CONFIGURED);
        }
        acquireCooldown(userId);
        try {
            return doRun(userId, force);
        } catch (RuntimeException e) {
            lastRun.remove(userId); // 접수하지 못했으면 쿨다운을 쓰지 않은 것으로 한다
            throw e;
        }
    }

    private DemoRunResponse doRun(Long userId, boolean force) {
        List<Long> ideaIds = ideas.findIdsByUserIdAndStatus(userId, IdeaStatus.ACTIVE); // HOLD 제외, 본인 것만

        // 트렌드를 먼저 큐에 넣어 이후 평가가 새 트렌드를 쓰게 한다
        boolean trendRefreshed = force || !globalTrend.isCurrent();
        if (trendRefreshed) {
            jobs.submitTrendRefresh(force);
        }

        List<Long> queued = new ArrayList<>();
        List<DemoRunResponse.Skipped> skipped = new ArrayList<>();
        boolean queueFull = false;
        for (Long id : ideaIds) {
            EvaluationStatus current = ideas.findEvaluationStatus(id).orElse(EvaluationStatus.IDLE);
            if (current == EvaluationStatus.QUEUED || current == EvaluationStatus.RUNNING) {
                skipped.add(new DemoRunResponse.Skipped(id, current == EvaluationStatus.QUEUED ? "ALREADY_QUEUED" : "ALREADY_RUNNING"));
                continue;
            }
            if (force) {
                sources.forceChangedByIdeaId(id, FORCED_HASH); // 큐에 넣기 전에: 실행기가 먼저 읽지 않게
            }
            try {
                jobs.submit(id, true);
                queued.add(id);
            } catch (CustomException e) {
                if (e.getErrorCode() != ErrorCode.EVALUATION_QUEUE_FULL) {
                    throw e;
                }
                queueFull = true;
                skipped.add(new DemoRunResponse.Skipped(id, "QUEUE_FULL"));
            }
        }
        if (queued.isEmpty() && queueFull) {
            throw new CustomException(ErrorCode.EVALUATION_QUEUE_FULL);
        }
        log.info("시연 실행 접수 userId={} force={} queued={} skipped={} trendRefreshed={}",
                userId, force, queued.size(), skipped.size(), trendRefreshed);
        return new DemoRunResponse(queued, skipped, trendRefreshed);
    }

    private void acquireCooldown(Long userId) {
        Instant now = Instant.now();
        boolean[] blocked = {false};
        lastRun.compute(userId, (k, prev) -> {
            if (prev != null && prev.plusSeconds(props.cooldownSeconds()).isAfter(now)) {
                blocked[0] = true;
                return prev;
            }
            return now;
        });
        if (blocked[0]) {
            throw new CustomException(ErrorCode.DEMO_COOLDOWN);
        }
    }
}
