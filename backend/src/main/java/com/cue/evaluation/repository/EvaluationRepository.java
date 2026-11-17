package com.cue.evaluation.repository;

import com.cue.evaluation.entity.Evaluation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {
    Optional<Evaluation> findFirstByScoreIdOrderByCreatedAtDesc(Long scoreId);

    /** 아이디어의 평가 이력 (최신순). 근거 보기 화면용. */
    @Query("select e from Evaluation e join fetch e.score s where s.idea.id = :ideaId order by e.createdAt desc, e.id desc")
    List<Evaluation> findByIdeaId(@Param("ideaId") Long ideaId);
}
