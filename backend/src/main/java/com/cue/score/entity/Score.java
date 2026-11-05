package com.cue.score.entity;

import com.cue.idea.entity.Idea;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "scores")
public class Score {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "idea_id")
    private Idea idea;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ScoreType type;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal weight;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score = BigDecimal.ZERO;

    protected Score() {}

    public Score(Idea idea, ScoreType type, BigDecimal weight) {
        this.idea = idea;
        this.type = type;
        this.weight = weight;
    }

    /** 전날 평가의 변화량을 반영한 최신 점수로 갱신한다 (0~100 범위로 제한). */
    public void applyDifference(BigDecimal difference) {
        BigDecimal next = score.add(difference);
        score = next.max(BigDecimal.ZERO).min(new BigDecimal("100"));
    }

    public Long getId() { return id; }
    public Idea getIdea() { return idea; }
    public ScoreType getType() { return type; }
    public BigDecimal getWeight() { return weight; }
    public BigDecimal getScore() { return score; }
}
