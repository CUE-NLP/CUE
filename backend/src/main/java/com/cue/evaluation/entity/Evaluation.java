package com.cue.evaluation.entity;

import com.cue.score.entity.Score;
import com.cue.source.entity.ApiProvider;
import com.cue.trend.entity.Trend;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

    /** AI가 작성한 근거 요약. 출처 URL은 {@link #references}에 따로 저장한다. */
    @Column(name = "evidence_summary", nullable = false, columnDefinition = "TEXT")
    private String evidenceSummary;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal difference;

    /** 평가 당시 적용된 가중치(비율). 가중치가 바뀌어도 과거 변화의 총점 영향을 알 수 있다. */
    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal weight;

    /** 이 평가를 반영한 뒤의 항목 점수. */
    @Column(name = "score_after", nullable = false, precision = 5, scale = 2)
    private BigDecimal scoreAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 근거 출처 (URL). */
    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EvaluationReference> references = new ArrayList<>();

    public Evaluation(Score score, Trend trend, String evidenceSummary, BigDecimal difference,
                      BigDecimal weight, BigDecimal scoreAfter, LocalDateTime createdAt) {
        this.score = score;
        this.trend = trend;
        this.evidenceSummary = evidenceSummary;
        this.difference = difference;
        this.weight = weight;
        this.scoreAfter = scoreAfter;
        this.createdAt = createdAt;
    }

    public void addReference(String url, String title, ApiProvider provider) {
        references.add(new EvaluationReference(this, url, title, provider));
    }

}
