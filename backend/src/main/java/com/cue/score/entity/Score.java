package com.cue.score.entity;

import com.cue.idea.entity.Idea;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "scores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    /** 변경 예약된 가중치. 다음 평가 턴 시작 시 weight에 적용되고 null로 비워진다. */
    @Column(name = "pending_weight", precision = 4, scale = 3)
    private BigDecimal pendingWeight;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score = BigDecimal.ZERO;

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

    /** 가중치(비율, 0~1)를 예약한다. 현재 weight는 다음 턴까지 유지된다. */
    public void reserveWeight(BigDecimal fraction) {
        this.pendingWeight = fraction;
    }

    /** 예약된 가중치가 있으면 적용하고 true를 반환한다. */
    public boolean applyPendingWeight() {
        if (pendingWeight == null) {
            return false;
        }
        weight = pendingWeight;
        pendingWeight = null;
        return true;
    }
}
