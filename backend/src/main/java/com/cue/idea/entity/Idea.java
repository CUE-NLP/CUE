package com.cue.idea.entity;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.score.entity.Score;
import com.cue.score.entity.ScoreType;
import com.cue.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ideas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Idea {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IdeaType type;

    /** 서버가 4개 항목 채움 여부로 결정한다. 클라이언트가 보낸 값은 쓰지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdeaStatus status;

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

    /** 항목 수정. null이면 그대로 두고, 빈 문자열이면 비운다. 수정 후 상태를 다시 판단한다. */
    public void edit(String problem, String target, String solution, String expectation) {
        if (problem != null) this.problem = blankToNull(problem);
        if (target != null) this.target = blankToNull(target);
        if (solution != null) this.solution = blankToNull(solution);
        if (expectation != null) this.expectation = blankToNull(expectation);
        refreshStatus();
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

    /** AI가 다시 추출한 아이디어 종류를 반영한다. */
    public void changeType(IdeaType type) {
        this.type = type;
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
     * 항목별 가중치(퍼센트 정수)를 예약한다. 4개 항목이 모두 있어야 하고 합이 100이어야 한다.
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
