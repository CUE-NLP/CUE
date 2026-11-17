package com.cue.source.repository;

import com.cue.source.entity.SourceItemSummary;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceItemSummaryRepository extends JpaRepository<SourceItemSummary, Long> {
    List<SourceItemSummary> findBySourceId(Long sourceId);

    @Modifying(flushAutomatically = true)
    @Query("delete from SourceItemSummary i where i.source.id = :sourceId")
    void deleteBySourceId(@Param("sourceId") Long sourceId);
}
