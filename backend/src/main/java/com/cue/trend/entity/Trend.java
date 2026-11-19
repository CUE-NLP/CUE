package com.cue.trend.entity;

import com.cue.source.entity.ApiProvider;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 글로벌 트렌드 1행(하루 1회 수집, 키워드 없음). 5번째 평가 기준 TREND의 근거로, 그 평가의 {@code evaluations.trend_id}가 가리킨다.
 * {@code content_hash}는 상위 항목(제목·URL)의 해시라서 어제와 같으면 새 행을 만들지 않고 기존 행을 그대로 쓴다.
 */
@Entity
@Table(name = "trends")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trend {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 최종 트렌드 요약 (provider별 요약을 합친 것). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    /** 수집에 쓰인 provider 이름 (쉼표 구분, 예: "HACKER_NEWS,GITHUB"). */
    @Column(nullable = false)
    private String providers;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Trend(String summary, List<ApiProvider> providers, String contentHash) {
        this.summary = summary;
        this.providers = providers.stream().map(Enum::name).collect(Collectors.joining(","));
        this.contentHash = contentHash;
    }
}
