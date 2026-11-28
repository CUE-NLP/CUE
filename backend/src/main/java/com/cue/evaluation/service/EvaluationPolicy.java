package com.cue.evaluation.service;

import com.cue.evaluation.config.EvaluationProperties;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.Idea;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 아이디어를 지금 다시 평가해도 되는지 정한다. 수동 요청·시연·파이프라인이 같은 규칙을 쓴다.
 * <ul>
 *   <li>하루 제한 켜짐(기본): 오늘 AI 평가를 마친 아이디어는 {@code E007}. 새벽 일일 평가도 이 한 번에 들어간다.</li>
 *   <li>하루 제한 꺼짐: 마지막으로 마친 평가에서 대기 시간이 지나지 않았으면 {@code E008}.</li>
 *   <li>내용·키워드를 수정한 아이디어(전체 평가 필요)는 어느 쪽도 적용하지 않는다. 실패한 평가는 마친 것이 아니라 바로 다시 요청할 수 있다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EvaluationPolicy {
    private final EvaluationProperties props;

    /** 하루 제한이 켜져 있고 {@code today}의 평가를 이미 마쳤으면 true. */
    public boolean dailyLimitReached(Idea idea, LocalDate today) {
        return props.dailyLimitEnabled() && idea.dailyEvaluationDone(today);
    }

    /** 사용자가 요청한 평가를 거절할 이유. 평가해도 되면 비어 있다. */
    public Optional<ErrorCode> blockReason(Idea idea, LocalDateTime now) {
        if (dailyLimitReached(idea, now.toLocalDate())) {
            return Optional.of(ErrorCode.EVALUATION_ALREADY_DONE_TODAY);
        }
        LocalDateTime completed = idea.getEvaluationCompletedAt();
        boolean coolingDown = !props.dailyLimitEnabled() && !idea.isNeedsFullEvaluation() && completed != null
                && completed.plusSeconds(props.manualCooldownSeconds()).isAfter(now);
        return coolingDown ? Optional.of(ErrorCode.EVALUATION_COOLDOWN) : Optional.empty();
    }
}
