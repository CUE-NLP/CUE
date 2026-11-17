package com.cue.idea.repository;

import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaStatus;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaRepository extends JpaRepository<Idea, Long> {
    /** 리더보드: 상태별 목록. 정렬은 호출하는 쪽에서 정한다. */
    List<Idea> findByUserIdAndStatus(Long userId, IdeaStatus status, Sort sort);
}
