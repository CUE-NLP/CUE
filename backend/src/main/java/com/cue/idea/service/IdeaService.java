package com.cue.idea.service;

import com.cue.evaluation.repository.EvaluationRepository;
import com.cue.evaluation.repository.IdeaScoreHistoryRepository;
import com.cue.evaluation.service.EvaluationPolicy;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.dto.CreateIdeaRequest;
import com.cue.idea.dto.EvaluationResponse;
import com.cue.idea.dto.IdeaHistoryResponse;
import com.cue.idea.dto.IdeaResponse;
import com.cue.idea.dto.ScoreResponse;
import com.cue.idea.dto.UpdateIdeaRequest;
import com.cue.idea.dto.UpdateWeightsRequest;
import com.cue.idea.entity.EvaluationStatus;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaSort;
import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.entity.IdeaType;
import com.cue.idea.entity.ItCategory;
import com.cue.idea.entity.KeywordOrigin;
import com.cue.idea.event.IdeaEvaluationRequested;
import com.cue.idea.repository.IdeaRepository;
import com.cue.source.entity.ApiProvider;
import com.cue.source.entity.Source;
import com.cue.source.repository.SourceRepository;
import com.cue.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdeaService {
    private static final int MAX_KEYWORDS = 2;

    private final IdeaRepository ideas;
    private final UserRepository users;
    private final EvaluationRepository evaluations;
    private final IdeaScoreHistoryRepository histories;
    private final SourceRepository sources;
    private final IdeaAnalyzer analyzer;
    private final AnalysisCache analysisCache;
    private final EvaluationPolicy evaluationPolicy;
    private final org.springframework.context.ApplicationEventPublisher events;

    /**
     * 프론트가 구조화한 아이디어를 저장한다. 서버가 4개 항목 채움 여부로 ACTIVE/HOLD를 정하고,
     * ACTIVE일 때만 AI로 종류·키워드를 추출해 외부 자료 수집 설정(sources)을 만든다. 점수는 첫 평가 전까지 0.
     */
    @Transactional
    public IdeaResponse create(Long userId, CreateIdeaRequest req) {
        var user = users.findById(userId).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        List<String> keywords = normalizeKeywords(req.keywords());
        IdeaType type = req.type() != null ? req.type() : IdeaType.IT;
        Idea idea = new Idea(user, type, req.problem(), req.target(), req.solution(), req.expectation());
        idea.rename(req.title());
        idea.editRawText(req.rawText());
        ideas.save(idea);
        if (!keywords.isEmpty()) {
            idea.applyKeywords(keywords, KeywordOrigin.USER);
        }
        if (idea.isActive()) {
            extractAndRegisterSources(idea);
            events.publishEvent(new IdeaEvaluationRequested(idea.getId())); // 커밋 뒤 첫 평가 자동 시작
        }
        return toDetail(idea);
    }

    /**
     * 아이디어를 수정한다. 제목만 바꾸면 분류·자료 수집과 평가 상태는 유지한다.
     * 평가가 대기(QUEUED)·진행(RUNNING) 중이면 {@code I005}로 거절한다. 행을 잠근 채 상태를 확인하고 수정하므로, 평가를 시작하는 쪽의
     * 상태 전이(조건부 UPDATE)는 이 트랜잭션이 끝날 때까지 기다린다: 평가가 먼저 시작됐으면 수정이 거절되고, 수정이 먼저면 평가는 수정된 내용으로 시작한다.
     */
    @Transactional
    public IdeaResponse update(Long userId, Long ideaId, UpdateIdeaRequest req) {
        Idea idea = findOwnedForUpdate(userId, ideaId); // I001 → I002 → I005 순서로 검사
        idea.rename(req.title());
        idea.editRawText(req.rawText());
        List<String> keywordsBefore = idea.keywordList();
        boolean contentChanged = idea.edit(req.problem(), req.target(), req.solution(), req.expectation());
        if (req.keywords() != null) {
            // 1개 이상이면 교체(USER), 빈 배열이면 사용자 키워드를 지우고 아래에서 AI로 다시 추출한다 (HOLD면 저장만).
            idea.applyKeywords(normalizeKeywords(req.keywords()), KeywordOrigin.USER);
        }
        boolean keywordsChanged = !keywordsBefore.equals(idea.keywordList());
        boolean resetKeywords = req.keywords() != null && req.keywords().isEmpty();
        if (idea.isActive() && (contentChanged || keywordsChanged || resetKeywords)) {
            extractAndRegisterSources(idea);
            keywordsChanged = keywordsChanged || !keywordsBefore.equals(idea.keywordList());
        }
        if (keywordsChanged) {
            idea.markContentChanged();
        }
        if (idea.isActive() && (contentChanged || keywordsChanged)) {
            events.publishEvent(new IdeaEvaluationRequested(idea.getId()));
        }
        return toDetail(idea);
    }

    /**
     * 키워드 우선순위: 사용자 입력(USER) > AI 추출. 사용자 키워드가 있으면 종류만 AI로 분류하고 키워드는 그대로 쓴다.
     * 없으면 AI가 종류·키워드를 함께 추출한다. 이후 종류별 검색 API × 키워드로 수집 설정을 새로 만든다 (해시는 초기화된다).
     */
    private void extractAndRegisterSources(Idea idea) {
        ItCategory category;
        List<String> keywords;
        // 구조화 API가 같은 4개 항목으로 이미 뽑아 둔 결과가 있으면(사용자가 고치지 않은 경우) AI를 다시 부르지 않는다
        IdeaAnalysis cached = analysisCache.find(idea.getUser().getId(), idea);
        if (idea.hasUserKeywords()) {
            category = cached != null ? cached.itCategory() : analyzer.analyzeCategory(idea);
            keywords = idea.keywordList();
        } else {
            IdeaAnalysis analysis = cached != null ? cached : analyzer.analyze(idea);
            category = analysis.itCategory();
            keywords = analysis.keywords().stream().limit(MAX_KEYWORDS).toList();
            idea.applyKeywords(keywords, KeywordOrigin.AI);
        }
        idea.changeItCategory(category);
        sources.deleteUnlinkedByIdeaId(idea.getId());
        for (ApiProvider provider : category.searchProviders()) {
            for (String keyword : keywords) {
                sources.save(new Source(idea, provider, keyword));
            }
        }
    }

    /** 앞뒤 공백 제거 후 빈 값·중복을 없앤다. 개수·길이 검증은 요청 DTO(@Valid)가 맡는다. */
    private static List<String> normalizeKeywords(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(k -> k != null).map(String::trim).filter(k -> !k.isEmpty())
                .distinct().limit(MAX_KEYWORDS).toList();
    }

    @Transactional(readOnly = true)
    public IdeaResponse get(Long userId, Long ideaId) {
        return toDetail(findOwned(userId, ideaId));
    }

    /** 평가를 요청할 수 있는지 확인한다: 없으면 I001, 남의 것이면 I002, 보류면 I004, 오늘 이미 평가했으면 E007, 대기 시간 중이면 E008. */
    @Transactional(readOnly = true)
    public void assertEvaluable(Long userId, Long ideaId) {
        Idea idea = findOwned(userId, ideaId);
        if (!idea.isActive()) {
            throw new CustomException(ErrorCode.IDEA_NOT_ACTIVE);
        }
        evaluationPolicy.blockReason(idea, LocalDateTime.now()).ifPresent(code -> {
            throw new CustomException(code);
        });
    }

    private Idea findOwnedForUpdate(Long userId, Long ideaId) {
        Idea idea = ideas.findByIdForUpdate(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        if (!idea.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.IDEA_ACCESS_DENIED);
        }
        EvaluationStatus status = idea.getEvaluationStatus();
        if (status == EvaluationStatus.QUEUED || status == EvaluationStatus.RUNNING) {
            throw new CustomException(ErrorCode.IDEA_EVALUATING);
        }
        return idea;
    }

    private Idea findOwned(Long userId, Long ideaId) {
        Idea idea = ideas.findById(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        if (!idea.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.IDEA_ACCESS_DENIED);
        }
        return idea;
    }

    /**
     * 아이디어를 삭제한다(되돌릴 수 없음). 점수·평가·근거·총점 이력·source는 DB의 ON DELETE CASCADE로 함께 지워지고, 여러 아이디어가
     * 함께 쓰는 글로벌 트렌드는 남는다. 수정과 같이 행을 잠근 채 평가 상태를 확인하므로, 평가 중이면 {@code I005}로 거절한다.
     */
    @Transactional
    public void delete(Long userId, Long ideaId) {
        ideas.delete(findOwnedForUpdate(userId, ideaId)); // I001 → I002 → I005 순서로 검사
    }

    /** 항목별 가중치를 예약한다. 현재 점수에는 영향이 없고 다음 평가 턴부터 적용된다. */
    @Transactional
    public IdeaResponse reserveWeights(Long userId, Long ideaId, UpdateWeightsRequest req) {
        Idea idea = findOwned(userId, ideaId);
        idea.reserveWeights(req.weights());
        return toDetail(idea);
    }

    /** 최신 점수를 확인했음을 기록한다. 이후 새 평가가 생기기 전까지 hasNewChange가 false가 된다. */
    @Transactional
    public IdeaResponse markChecked(Long userId, Long ideaId) {
        Idea idea = findOwned(userId, ideaId);
        idea.markChecked();
        return toDetail(idea);
    }

    /** 즐겨찾기를 바꾼다. 평가와 무관하므로 평가 중에도 가능하다. */
    @Transactional
    public IdeaResponse changeFavorite(Long userId, Long ideaId, boolean favorite) {
        Idea idea = findOwned(userId, ideaId);
        idea.changeFavorite(favorite);
        return toDetail(idea);
    }

    /** 리더보드: 로그인한 사용자의 아이디어를 상태별(ACTIVE 목록 / HOLD 보류 목록)로 총점순 또는 최근 상승순 정렬. {@code favoriteOnly}면 즐겨찾기만. */
    @Transactional(readOnly = true)
    public List<IdeaResponse> leaderboard(Long userId, IdeaStatus status, IdeaSort sort, boolean favoriteOnly) {
        Sort order = switch (sort) {
            case TOTAL -> Sort.by(Sort.Order.desc("totalScore"), Sort.Order.desc("id"));
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            case RECENT_RISE -> Sort.by(Sort.Order.desc("lastDifference").nullsLast(),
                    Sort.Order.desc("totalScore"), Sort.Order.desc("id"));
        };
        List<Idea> found = favoriteOnly
                ? ideas.findByUserIdAndStatusAndFavoriteTrue(userId, status, order)
                : ideas.findByUserIdAndStatus(userId, status, order);
        return found.stream().map(idea -> toResponse(idea, null)).toList();
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

    /** 단건 응답: 원문을 담는다. */
    private IdeaResponse toDetail(Idea idea) {
        return toResponse(idea, idea.getRawText());
    }

    private IdeaResponse toResponse(Idea idea, String rawText) {
        List<ScoreResponse> scores = idea.getScores().stream()
                .map(s -> new ScoreResponse(
                        s.getType(), percent(s.getWeight()), percent(s.getPendingWeight()), s.getScore(),
                        evaluations.findFirstByScoreIdOrderByCreatedAtDesc(s.getId())
                                .map(e -> e.getDifference()).orElse(null)))
                .toList();
        return new IdeaResponse(idea.getId(), idea.getTitle(), idea.getType(), idea.getItCategory(), idea.getStatus(), idea.getProblem(), idea.getTarget(),
                idea.getSolution(), idea.getExpectation(), idea.getTotalScore(), idea.getLastDifference(),
                idea.getLastEvaluatedAt(), idea.hasNewChange(), idea.getCreatedAt(), scores,
                idea.keywordList(), idea.getKeywordOrigin(), idea.getEvaluationStatus(), idea.getLastEvaluationErrorCode(),
                idea.lastEvaluationError(), idea.isFavorite(), rawText);
    }

    private static Integer percent(BigDecimal fraction) {
        return fraction == null ? null : fraction.movePointRight(2).intValue();
    }
}
