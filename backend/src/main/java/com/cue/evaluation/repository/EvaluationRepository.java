package com.cue.evaluation.repository;

import com.cue.evaluation.entity.Evaluation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {
    Optional<Evaluation> findFirstByScoreIdOrderByCreatedAtDesc(Long scoreId);
}
