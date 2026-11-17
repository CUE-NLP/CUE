package com.cue.evaluation.entity;

import com.cue.idea.entity.Idea;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 평가로 총점이 바뀐 시점의 총점 스냅샷. 점수 이력 차트와 리더보드 스파크라인에 쓴다. */
@Entity
@Table(name = "idea_score_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdeaScoreHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "idea_id")
    private Idea idea;

    @Column(name = "total_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "total_difference", nullable = false, precision = 7, scale = 2)
    private BigDecimal totalDifference;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    public IdeaScoreHistory(Idea idea, BigDecimal totalScore, BigDecimal totalDifference, LocalDateTime evaluatedAt) {
        this.idea = idea;
        this.totalScore = totalScore;
        this.totalDifference = totalDifference;
        this.evaluatedAt = evaluatedAt;
    }
}
