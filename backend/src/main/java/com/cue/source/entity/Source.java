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

}
