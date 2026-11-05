package com.cue.evaluation.entity;

import com.cue.score.entity.Score;
import com.cue.trend.entity.Trend;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 하루 1회 AI 재평가 결과. 점수에 변화가 있을 때만 생성된다. */
@Entity
@Table(name = "evaluations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Evaluation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "score_id")
    private Score score;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trend_id")
    private Trend trend;

    /** AI가 작성한 근거 요약 (출처 URL 포함). */
    @Column(name = "evidence_summary", nullable = false, columnDefinition = "TEXT")
    private String evidenceSummary;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal difference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Evaluation(Score score, Trend trend, String evidenceSummary, BigDecimal difference) {
        this.score = score;
        this.trend = trend;
        this.evidenceSummary = evidenceSummary;
        this.difference = difference;
    }

}
