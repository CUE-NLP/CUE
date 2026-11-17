package com.cue.idea.service;

import com.cue.evaluation.repository.EvaluationRepository;
import com.cue.evaluation.repository.IdeaScoreHistoryRepository;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.dto.CreateIdeaRequest;
import com.cue.idea.dto.EvaluationResponse;
import com.cue.idea.dto.IdeaHistoryResponse;
import com.cue.idea.dto.IdeaResponse;
import com.cue.idea.dto.ScoreResponse;
import com.cue.idea.dto.UpdateIdeaRequest;
import com.cue.idea.dto.UpdateWeightsRequest;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaSort;
import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.entity.IdeaType;
import com.cue.idea.repository.IdeaRepository;
import com.cue.source.entity.ApiProvider;
import com.cue.source.entity.Source;
import com.cue.source.repository.SourceRepository;
import com.cue.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdeaService {
    private final IdeaRepository ideas;
    private final UserRepository users;
    private final EvaluationRepository evaluations;
    private final IdeaScoreHistoryRepository histories;
    private final SourceRepository sources;
    private final IdeaAnalyzer analyzer;

    /**
     * 프론트가 구조화한 아이디어를 저장한다. 서버가 4개 항목 채움 여부로 ACTIVE/HOLD를 정하고,
     * ACTIVE일 때만 AI로 종류·키워드를 추출해 외부 자료 수집 설정(sources)을 만든다. 점수는 첫 평가 전까지 0.
     */
    @Transactional
    public IdeaResponse create(Long userId, CreateIdeaRequest req) {
        var user = users.findById(userId).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        IdeaType type = req.type() != null ? req.type() : IdeaType.IT;
        Idea idea = ideas.save(new Idea(user, type, req.problem(), req.target(), req.solution(), req.expectation()));
        if (idea.isActive()) {
            extractAndRegisterSources(idea);
        }
        return toResponse(idea);
    }

    /** 아이디어를 수정한다. 모두 채워진 상태(ACTIVE)면 종류·키워드를 다시 추출한다. 재평가는 평가 단계에서 처리한다. */
    @Transactional
    public IdeaResponse update(Long userId, Long ideaId, UpdateIdeaRequest req) {
        Idea idea = findOwned(userId, ideaId);
        idea.edit(req.problem(), req.target(), req.solution(), req.expectation());
        if (idea.isActive()) {
            extractAndRegisterSources(idea);
        }
        return toResponse(idea);
    }

    /** AI로 종류·키워드를 추출하고, 종류별 검색 API × 키워드로 수집 설정을 새로 만든다 (해시는 초기화된다). */
    private void extractAndRegisterSources(Idea idea) {
        IdeaAnalysis analysis = analyzer.analyze(idea);
        idea.changeType(analysis.type());
        sources.deleteUnlinkedByIdeaId(idea.getId());
        for (ApiProvider provider : analysis.type().searchProviders()) {
            for (String keyword : analysis.keywords()) {
                sources.save(new Source(idea, provider, keyword));
            }
        }
    }

    @Transactional(readOnly = true)
    public IdeaResponse get(Long userId, Long ideaId) {
        return toResponse(findOwned(userId, ideaId));
    }

    /** 평가 가능한지 확인한다: 없으면 I001, 남의 것이면 I002, 보류면 I004. */
    @Transactional(readOnly = true)
    public void assertEvaluable(Long userId, Long ideaId) {
        if (!findOwned(userId, ideaId).isActive()) {
            throw new CustomException(ErrorCode.IDEA_NOT_ACTIVE);
        }
    }

    private Idea findOwned(Long userId, Long ideaId) {
        Idea idea = ideas.findById(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        if (!idea.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.IDEA_ACCESS_DENIED);
        }
        return idea;
    }

    /** 항목별 가중치를 예약한다. 현재 점수에는 영향이 없고 다음 평가 턴부터 적용된다. */
    @Transactional
    public IdeaResponse reserveWeights(Long userId, Long ideaId, UpdateWeightsRequest req) {
        Idea idea = findOwned(userId, ideaId);
        idea.reserveWeights(req.weights());
        return toResponse(idea);
    }

    /** 최신 점수를 확인했음을 기록한다. 이후 새 평가가 생기기 전까지 hasNewChange가 false가 된다. */
    @Transactional
    public IdeaResponse markChecked(Long userId, Long ideaId) {
        Idea idea = findOwned(userId, ideaId);
        idea.markChecked();
        return toResponse(idea);
    }

    /** 리더보드: 로그인한 사용자의 아이디어를 상태별(ACTIVE 목록 / HOLD 보류 목록)로 총점순 또는 최근 상승순 정렬. */
    @Transactional(readOnly = true)
    public List<IdeaResponse> leaderboard(Long userId, IdeaStatus status, IdeaSort sort) {
        Sort order = switch (sort) {
            case TOTAL -> Sort.by(Sort.Order.desc("totalScore"), Sort.Order.desc("id"));
            case RECENT_RISE -> Sort.by(Sort.Order.desc("lastDifference").nullsLast(),
                    Sort.Order.desc("totalScore"), Sort.Order.desc("id"));
        };
        return ideas.findByUserIdAndStatus(userId, status, order).stream().map(this::toResponse).toList();
    }

    /** 총점 이력 (오래된 순). 점수 이력 차트용. */
    @Transactional(readOnly = true)
    public List<IdeaHistoryResponse> history(Long userId, Long ideaId) {
        findOwned(userId, ideaId);
        return histories.findByIdeaIdOrderByEvaluatedAtAscIdAsc(ideaId).stream()
                .map(h -> new IdeaHistoryResponse(h.getEvaluatedAt(), h.getTotalScore(), h.getTotalDifference()))
                .toList();
    }

    /** 항목별 평가 이력 (최신순): 변화량, 근거 요약, 출처. */
    @Transactional(readOnly = true)
    public List<EvaluationResponse> evaluationHistory(Long userId, Long ideaId) {
        findOwned(userId, ideaId);
        return evaluations.findByIdeaId(ideaId).stream()
                .map(e -> new EvaluationResponse(e.getId(), e.getScore().getType(), e.getDifference(),
                        e.getScoreAfter(), percent(e.getWeight()), e.getEvidenceSummary(), e.getCreatedAt(),
                        e.getReferences().stream()
                                .map(r -> new EvaluationResponse.ReferenceResponse(r.getUrl(), r.getTitle(), r.getProvider()))
                                .toList()))
                .toList();
    }

    private IdeaResponse toResponse(Idea idea) {
        List<ScoreResponse> scores = idea.getScores().stream()
                .map(s -> new ScoreResponse(
                        s.getType(), percent(s.getWeight()), percent(s.getPendingWeight()), s.getScore(),
                        evaluations.findFirstByScoreIdOrderByCreatedAtDesc(s.getId())
                                .map(e -> e.getDifference()).orElse(null)))
                .toList();
        return new IdeaResponse(idea.getId(), idea.getType(), idea.getStatus(), idea.getProblem(), idea.getTarget(),
                idea.getSolution(), idea.getExpectation(), idea.getTotalScore(), idea.getLastDifference(),
                idea.getLastEvaluatedAt(), idea.hasNewChange(), idea.getCreatedAt(), scores);
    }

    private static Integer percent(BigDecimal fraction) {
        return fraction == null ? null : fraction.movePointRight(2).intValue();
    }
}
