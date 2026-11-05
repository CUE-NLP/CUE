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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String problem;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String target;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String solution;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String expectation;

    @Column(name = "total_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal totalScore = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "idea", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Score> scores = new ArrayList<>();

    public Idea(User user, IdeaType type, String problem, String target, String solution, String expectation) {
        this.user = user;
        this.type = type;
        this.problem = problem;
        this.target = target;
        this.solution = solution;
        this.expectation = expectation;
        for (ScoreType scoreType : ScoreType.values()) {
            scores.add(new Score(this, scoreType, scoreType.defaultWeight()));
        }
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
