package com.cue.trend.entity;

import com.cue.source.entity.ApiProvider;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 트렌드 하나에서 provider(source)별 요약. 항목 요약들로 만든 중간 단계 결과다. */
@Entity
@Table(name = "trend_sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrendSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trend_id")
    private Trend trend;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApiProvider provider;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    public TrendSource(Trend trend, ApiProvider provider, String summary) {
        this.trend = trend;
        this.provider = provider;
        this.summary = summary;
    }
}
