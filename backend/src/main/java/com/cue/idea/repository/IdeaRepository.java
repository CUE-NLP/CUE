package com.cue.idea.repository;

import com.cue.idea.entity.Idea;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdeaRepository extends JpaRepository<Idea, Long> {
    /** 리더보드: 총점 내림차순. */
    List<Idea> findByUserIdOrderByTotalScoreDesc(Long userId);
}
