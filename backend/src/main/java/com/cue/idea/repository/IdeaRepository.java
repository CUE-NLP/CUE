package com.cue.idea.repository;

import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaStatus;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdeaRepository extends JpaRepository<Idea, Long> {
    /** 리더보드: 상태별 목록. 정렬은 호출하는 쪽에서 정한다. */
    List<Idea> findByUserIdAndStatus(Long userId, IdeaStatus status, Sort sort);

    /** 일일 평가 대상 id (HOLD 제외하려면 ACTIVE). */
    @Query("select i.id from Idea i where i.status = :status order by i.id")
    List<Long> findIdsByStatus(@Param("status") IdeaStatus status);
}
