package com.cue.idea.entity;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.score.entity.Score;
import com.cue.score.entity.ScoreType;
import com.cue.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@org.hibernate.annotations.DynamicUpdate // 평가 상태 컬럼은 별도 UPDATE로 바뀌므로 오래된 엔티티가 덮어쓰지 않게 바뀐 컬럼만 갱신한다
@Table(name = "ideas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Idea {
    public static final int TITLE_MAX_LENGTH = 60;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IdeaType type;

    /** IT 세부 종류. 검색에 쓰는 외부 API 조합을 결정한다. AI가 분류하며 분류 전(HOLD 등)에는 기본값. */
    @Enumerated(EnumType.STRING)
    @Column(name = "it_category", nullable = false, length = 30)
    private ItCategory itCategory = ItCategory.DEFAULT;

    /** 서버가 4개 항목 채움 여부로 결정한다. 클라이언트가 보낸 값은 쓰지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdeaStatus status;

    @Column(length = TITLE_MAX_LENGTH)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String problem;

    @Column(columnDefinition = "TEXT")
    private String target;

    @Column(columnDefinition = "TEXT")
    private String solution;

    @Column(columnDefinition = "TEXT")
    private String expectation;

    /** 검색 키워드(줄바꿈 구분, 최대 2개). 키워드가 없으면 null. */
    @Column(columnDefinition = "TEXT")
    private String keywords;

    @Enumerated(EnumType.STRING)
    @Column(name = "keyword_origin", length = 20)
    private KeywordOrigin keywordOrigin;

    @Column(name = "total_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal totalScore = BigDecimal.ZERO;

    /** 가장 최근 평가에서의 총점 변화량. 평가가 없으면 null. 리더보드 "최근 상승" 정렬에 쓴다. */
    @Column(name = "last_difference", precision = 7, scale = 2)
    private BigDecimal lastDifference;

    /** 가장 최근에 점수가 바뀐 평가 시각. 변화가 없어 평가가 일어나지 않았다면 갱신하지 않는다. */
    @Column(name = "last_evaluated_at")
    private LocalDateTime lastEvaluatedAt;

    /** 사용자가 최신 점수를 확인한 시각. 이후 평가가 있으면 "새로운 변화"로 본다. */
    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    /** true면 다음 평가에서 자료 변화와 무관하게 Gemini 전체(초기) 평가를 한 번 한다. 등록·내용 수정 시 켜지고 평가 완료 시 꺼진다. */
    @Column(name = "needs_full_evaluation", nullable = false)
    private boolean needsFullEvaluation = true;

    /** 평가를 마지막으로 끝까지 마친 날짜. 점수가 안 바뀌었어도 기록한다. */
    @Column(name = "evaluated_on")
    private LocalDate evaluatedOn;

    /** 이 아이디어의 TREND 점수를 마지막으로 평가한 글로벌 트렌드 id. null이면 TREND 첫 평가 전 (내용이 바뀌면 다시 null). */
    @Column(name = "last_trend_id")
    private Long lastTrendId;

    /** 평가 실행 상태. 상태 전이는 {@code IdeaRepository}의 조건부 UPDATE로만 한다(동시 요청에도 한 번만 전이). */
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_status", nullable = false, length = 10)
    private EvaluationStatus evaluationStatus = EvaluationStatus.IDLE;

    @Column(name = "evaluation_started_at")
    private LocalDateTime evaluationStartedAt;

    @Column(name = "evaluation_finished_at")
    private LocalDateTime evaluationFinishedAt;

    /** 마지막 평가가 실패했을 때 사용자에게 안내할 에러 코드(예: G002). FAILED가 아니면 null. */
    @Column(name = "last_evaluation_error_code", length = 10)
    private String lastEvaluationErrorCode;

    /** 마지막 평가가 실패한 위치·외부 API·원인. FAILED가 아니거나 알 수 없으면 null. */
    @Column(name = "last_evaluation_error_location", length = 100)
    private String lastEvaluationErrorLocation;

    @Column(name = "last_evaluation_error_api", length = 100)
    private String lastEvaluationErrorApi;

    @Column(name = "last_evaluation_error_reason", length = 255)
    private String lastEvaluationErrorReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "idea", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Score> scores = new ArrayList<>();

    public Idea(User user, IdeaType type, String problem, String target, String solution, String expectation) {
        this.user = user;
        this.type = type;
        this.problem = blankToNull(problem);
        this.target = blankToNull(target);
        this.solution = blankToNull(solution);
        this.expectation = blankToNull(expectation);
        refreshStatus();
        for (ScoreType scoreType : ScoreType.values()) {
            scores.add(new Score(this, scoreType, scoreType.defaultWeight()));
        }
    }

    public boolean isActive() {
        return status == IdeaStatus.ACTIVE;
    }

    /** 4개 항목이 모두 채워졌으면 ACTIVE, 아니면 HOLD. */
    private void refreshStatus() {
        boolean complete = problem != null && target != null && solution != null && expectation != null;
        status = complete ? IdeaStatus.ACTIVE : IdeaStatus.HOLD;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 제목은 표시용이므로 평가 상태를 변경하지 않는다. */
    public void rename(String title) {
        if (title != null) {
            this.title = title.strip();
        }
    }

    /**
     * 항목 수정. null이면 그대로 두고, 빈 문자열이면 비운다. 수정 후 상태를 다시 판단한다.
     *
     * @return 4개 항목의 내용이 실제로 바뀌었으면 true
     */
    public boolean edit(String problem, String target, String solution, String expectation) {
        String[] before = {this.problem, this.target, this.solution, this.expectation};
        if (problem != null) this.problem = blankToNull(problem);
        if (target != null) this.target = blankToNull(target);
        if (solution != null) this.solution = blankToNull(solution);
        if (expectation != null) this.expectation = blankToNull(expectation);
        refreshStatus();
        String[] after = {this.problem, this.target, this.solution, this.expectation};
        boolean contentChanged = !java.util.Arrays.equals(before, after);
        if (contentChanged) {
            needsFullEvaluation = true; // 내용이 바뀌었으니 다음 평가에서 전체 재평가 (TREND도 첫 평가부터 다시)
            lastTrendId = null;
        }
        return contentChanged;
    }

    /** 평가 근거가 달라지는 변경(예: 검색 키워드)이 있었음을 기록한다: 다음 평가는 근거 4개 기준·TREND 모두 첫 평가부터 다시 한다. */
    public void markContentChanged() {
        needsFullEvaluation = true;
        lastTrendId = null;
    }

    /** 마지막 평가 실패의 위치와 원인. 기록이 없으면 null. */
    public ErrorDetail lastEvaluationError() {
        if (lastEvaluationErrorLocation == null && lastEvaluationErrorApi == null && lastEvaluationErrorReason == null) {
            return null;
        }
        return new ErrorDetail(lastEvaluationErrorLocation, lastEvaluationErrorApi, lastEvaluationErrorReason);
    }

    /** 평가는 하루에 한 번이다: {@code today}에 이미 마쳤으면 true. 내용·키워드가 바뀌어 전체 평가가 필요하면 같은 날에도 다시 평가한다. */
    public boolean dailyEvaluationDone(LocalDate today) {
        return !needsFullEvaluation && today.equals(evaluatedOn);
    }

    /** {@code date}의 평가를 끝까지 마쳤음을 기록한다. */
    public void completeDailyEvaluation(LocalDate date) {
        evaluatedOn = date;
    }

    /** 전체(초기) 평가를 끝냈음을 기록한다. */
    public void completeFullEvaluation() {
        needsFullEvaluation = false;
    }

    /** 글로벌 트렌드({@code trendId})로 TREND 평가를 끝냈음을 기록한다 (점수가 안 바뀌었어도 평가는 한 것이다). */
    public void completeTrendEvaluation(Long trendId) {
        lastTrendId = trendId;
    }

    /** 키워드 목록. 없으면 빈 리스트. */
    public List<String> keywordList() {
        return keywords == null ? List.of() : List.of(keywords.split("\\n"));
    }

    /** 키워드와 출처를 저장한다. 빈 목록이면 키워드를 지운다. */
    public void applyKeywords(List<String> values, KeywordOrigin origin) {
        if (values == null || values.isEmpty()) {
            keywords = null;
            keywordOrigin = null;
        } else {
            keywords = String.join("\n", values);
            keywordOrigin = origin;
        }
    }

    public boolean hasUserKeywords() {
        return keywordOrigin == KeywordOrigin.USER && keywords != null;
    }

    /** AI가 다시 분류한 IT 세부 종류를 반영한다. */
    public void changeItCategory(ItCategory itCategory) {
        this.itCategory = itCategory;
    }

    public Score score(ScoreType type) {
        return scores.stream().filter(s -> s.getType() == type).findFirst().orElseThrow();
    }

    /** 사용자가 최신 점수를 확인했음을 기록한다. */
    public void markChecked() {
        lastCheckedAt = LocalDateTime.now();
    }

    /** 확인하지 않은 새 평가가 있는지. */
    public boolean hasNewChange() {
        return lastEvaluatedAt != null && (lastCheckedAt == null || lastEvaluatedAt.isAfter(lastCheckedAt));
    }

    /** 평가로 총점이 바뀐 시점의 변화량·시각을 기록한다. */
    public void recordEvaluation(BigDecimal totalDifference, LocalDateTime evaluatedAt) {
        lastDifference = totalDifference;
        lastEvaluatedAt = evaluatedAt;
    }

    /** 항목별 점수의 가중합으로 총점을 다시 계산한다. */
    public void recalculateTotal() {
        totalScore = scores.stream()
                .map(s -> s.getScore().multiply(s.getWeight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 항목별 가중치(퍼센트 정수)를 예약한다. 5개 기준(TREND 포함)이 모두 있어야 하고 합이 100이어야 한다.
     * 현재 점수·총점에는 영향이 없으며, 다음 평가 턴에 {@link #startNextTurn()} 으로 적용된다.
     */
    public void reserveWeights(Map<ScoreType, Integer> percents) {
        boolean complete = percents != null
                && percents.keySet().containsAll(List.of(ScoreType.values()))
                && percents.size() == ScoreType.values().length;
        if (!complete
                || percents.values().stream().anyMatch(p -> p == null || p < 0 || p > 100)
                || percents.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw new CustomException(ErrorCode.INVALID_WEIGHT);
        }
        scores.forEach(s -> s.reserveWeight(
                BigDecimal.valueOf(percents.get(s.getType())).movePointLeft(2).setScale(3, RoundingMode.UNNECESSARY)));
    }

    /** 새 평가 턴 시작: 예약된 가중치를 적용하고 총점을 다시 계산한다. */
    public void startNextTurn() {
        boolean changed = false;
        for (Score s : scores) {
            changed |= s.applyPendingWeight();
        }
        if (changed) {
            recalculateTotal();
        }
    }
}
