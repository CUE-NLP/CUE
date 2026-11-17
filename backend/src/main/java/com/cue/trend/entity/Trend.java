package com.cue.trend.entity;

import com.cue.source.entity.ApiProvider;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 재평가 한 번에서 변경이 감지된 외부 자료를 Gemini로 요약한 결과. 그 재평가의 {@code evaluations.trend_id}가 가리킨다. */
@Entity
@Table(name = "trends")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trend {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    /** 요약에 쓰인 provider 이름 (쉼표 구분, 예: "GITHUB,HACKER_NEWS"). */
    @Column(nullable = false)
    private String providers;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Trend(String summary, List<ApiProvider> providers) {
        this.summary = summary;
        this.providers = providers.stream().map(Enum::name).collect(Collectors.joining(","));
    }

}
