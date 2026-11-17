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

    /** 이 source의 상위 항목 요약들을 바탕으로 만든 소스 단위 최종 요약. 변경이 감지되면 덮어쓴다. */
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "summarized_at")
    private LocalDateTime summarizedAt;

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

    /** 평가가 실패했을 때 해시·시각을 이전 값으로 되돌려 다음 조회에서 같은 변화를 다시 감지하게 한다. */
    public void restore(String contentHash, LocalDateTime checkedAt) {
        this.contentHash = contentHash;
        this.checkedAt = checkedAt;
    }

    /** 소스 단위 최종 요약을 덮어쓴다 (null이면 요약 없음). */
    public void updateSummary(String summary) {
        this.summary = summary;
        this.summarizedAt = summary == null ? null : LocalDateTime.now();
    }
}
