package com.cue.source.repository;

import com.cue.source.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceRepository extends JpaRepository<Source, Long> {
    /** 키워드를 다시 추출할 때 지우는 수집 설정. 평가의 근거로 이미 연결된 행은 남긴다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Source s where s.idea.id = :ideaId and s.evaluation is null")
    void deleteUnlinkedByIdeaId(@Param("ideaId") Long ideaId);
}
