package com.cue.trend.entity;

import com.cue.source.entity.ApiProvider;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trends")
public class Trend {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApiProvider provider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Trend() {}

    public Trend(String summary, ApiProvider provider) {
        this.summary = summary;
        this.provider = provider;
    }

    public Long getId() { return id; }
    public String getSummary() { return summary; }
    public ApiProvider getProvider() { return provider; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
