package com.cue.source.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** source의 상위 항목 하나를 읽고 Gemini로 요약한 결과. 변경이 감지되면 source 단위로 덮어쓰고, 같은 URL은 일정 기간 재사용한다. */
@Entity
@Table(name = "source_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceItemSummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id")
    private Source source;

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

    public SourceItemSummary(Source source, String url, String title, String publishedAt, String metrics,
                             String summary, LocalDateTime summarizedAt) {
        this.source = source;
        this.url = url;
        this.title = title.length() > 500 ? title.substring(0, 500) : title;
        this.publishedAt = publishedAt;
        this.metrics = metrics;
        this.summary = summary;
        this.summarizedAt = summarizedAt;
    }
}
