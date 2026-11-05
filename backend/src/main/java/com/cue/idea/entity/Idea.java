package com.cue.idea.entity;

import com.cue.score.entity.Score;
import com.cue.score.entity.ScoreType;
import com.cue.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ideas")
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

    protected Idea() {}

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

    public Long getId() { return id; }
    public User getUser() { return user; }
    public IdeaType getType() { return type; }
    public String getProblem() { return problem; }
    public String getTarget() { return target; }
    public String getSolution() { return solution; }
    public String getExpectation() { return expectation; }
    public BigDecimal getTotalScore() { return totalScore; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<Score> getScores() { return scores; }
}
