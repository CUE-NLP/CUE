package com.cue.trend.entity;

import com.cue.source.entity.ApiProvider;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 트렌드에 쓰인 상위 항목 하나와 그 항목의 Gemini 요약 (원문은 저장하지 않는다). 같은 URL은 다음 수집에서 재사용된다. */
@Entity
@Table(name = "trend_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrendItem {
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
    private String url;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "published_at", length = 10)
    private String publishedAt;

    @Column(length = 255)
    private String metrics;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "summarized_at", nullable = false)
    private LocalDateTime summarizedAt;

    public TrendItem(Trend trend, ApiProvider provider, String url, String title, String publishedAt, String metrics,
                     String summary, LocalDateTime summarizedAt) {
        this.trend = trend;
        this.provider = provider;
        this.url = url;
        this.title = title.length() > 500 ? title.substring(0, 500) : title;
        this.publishedAt = publishedAt;
        this.metrics = metrics;
        this.summary = summary;
        this.summarizedAt = summarizedAt;
    }
}
