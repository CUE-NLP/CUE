package com.cue.evaluation.entity;

import com.cue.source.entity.ApiProvider;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 평가 근거 출처 하나 (URL). */
@Entity
@Table(name = "evaluation_references")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationReference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evaluation_id")
    private Evaluation evaluation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ApiProvider provider;

    EvaluationReference(Evaluation evaluation, String url, String title, ApiProvider provider) {
        this.evaluation = evaluation;
        this.url = url;
        this.title = title;
        this.provider = provider;
    }
}
