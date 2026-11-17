package com.cue.evaluation.service;

import com.cue.global.error.ErrorCode;
import com.cue.idea.repository.IdeaRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 서버가 죽거나 종료되며 남은 QUEUED/RUNNING 평가를 FAILED(E005)로 정리한다. (큐는 메모리라 재시작하면 사라진다.)
 * 사용자는 다시 평가를 요청하면 되고, 평가가 필요한 아이디어는 다음 일일 배치에서도 다시 평가된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationStateRecovery implements ApplicationRunner {
    private final IdeaRepository ideas;

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /** @return 정리한 아이디어 수 */
    public int recover() {
        int n = ideas.failStaleEvaluations(LocalDateTime.now(), ErrorCode.EVALUATION_INTERRUPTED.getCode());
        if (n > 0) {
            log.warn("재시작으로 중단된 평가 {}건을 FAILED({})로 정리했습니다", n, ErrorCode.EVALUATION_INTERRUPTED.getCode());
        }
        return n;
    }
}
