package com.cue.evaluation.repository;

import com.cue.evaluation.entity.IdeaScoreHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaScoreHistoryRepository extends JpaRepository<IdeaScoreHistory, Long> {
    List<IdeaScoreHistory> findByIdeaIdOrderByEvaluatedAtAscIdAsc(Long ideaId);
}
