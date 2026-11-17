package com.cue.source.entity;

import com.cue.evaluation.entity.Evaluation;
import com.cue.idea.entity.Idea;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Source {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "idea_id")
    private Idea idea;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evaluation_id")
    private Evaluation evaluation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApiProvider provider;

    @Column(nullable = false)
    private String keyword;

    /** 수집 결과 해시 — 이전 값과 비교해 변경을 감지한다. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    /** 직전 조회 결과의 항목 URL(줄바꿈 구분). null이면 아직 한 번도 조회하지 않은 것. 새로 나타난 항목을 가려내는 기준. */
    @Column(name = "known_urls", columnDefinition = "TEXT")
    private String knownUrls;

    public Source(Idea idea, ApiProvider provider, String keyword) {
        this.idea = idea;
        this.provider = provider;
        this.keyword = keyword;
    }

    /** 새 해시를 기록하고, 이전과 달라졌으면 true를 반환한다. */
    public boolean updateHash(String newHash) {
        boolean changed = contentHash != null && !contentHash.equals(newHash);
        contentHash = newHash;
        checkedAt = LocalDateTime.now();
        return changed;
    }

    /** 이번 조회 결과(해시·URL 목록·시각)를 기록한다. */
    public void recordSnapshot(String newHash, java.util.List<String> urls) {
        contentHash = newHash;
        knownUrls = String.join("\n", urls);
        checkedAt = LocalDateTime.now();
    }

    /** 한 번도 조회하지 않았는지 (이때는 기준만 기록하고 변경으로 보지 않는다). */
    public boolean isFirstCheck() {
        return knownUrls == null;
    }

    /** 직전 조회에 있던 URL 집합. */
    public java.util.Set<String> knownUrlSet() {
        return knownUrls == null || knownUrls.isEmpty()
                ? java.util.Set.of() : new java.util.HashSet<>(java.util.Arrays.asList(knownUrls.split("\n")));
    }

    /** 평가가 실패했을 때 해시·URL·시각을 이전 값으로 되돌려 다음 조회에서 같은 변화를 다시 감지하게 한다. */
    public void restore(String contentHash, LocalDateTime checkedAt, String knownUrls) {
        this.contentHash = contentHash;
        this.checkedAt = checkedAt;
        this.knownUrls = knownUrls;
    }
}
