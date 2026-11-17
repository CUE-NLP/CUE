package com.cue.idea.repository;

import com.cue.idea.entity.EvaluationStatus;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Sort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface IdeaRepository extends JpaRepository<Idea, Long> {
    // ===== 평가 실행 상태 전이 =====
    // 조건부 UPDATE 한 줄로 전이해 동시 요청에도 한 번만 성공한다. 호출하는 쪽 트랜잭션과 무관하게(AFTER_COMMIT 콜백 안에서도) 바로 커밋되도록 REQUIRES_NEW.

    /** IDLE/FAILED → QUEUED. 이미 QUEUED/RUNNING이면 0. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Idea i set i.evaluationStatus = com.cue.idea.entity.EvaluationStatus.QUEUED, i.lastEvaluationErrorCode = null "
            + "where i.id = :id and i.evaluationStatus in (com.cue.idea.entity.EvaluationStatus.IDLE, com.cue.idea.entity.EvaluationStatus.FAILED)")
    int markQueued(@Param("id") Long id);

    /** allowed 중 하나 → RUNNING (시작 시각 기록). 이미 다른 상태면 0. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Idea i set i.evaluationStatus = com.cue.idea.entity.EvaluationStatus.RUNNING, i.evaluationStartedAt = :now, "
            + "i.evaluationFinishedAt = null, i.lastEvaluationErrorCode = null where i.id = :id and i.evaluationStatus in :allowed")
    int markRunning(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("allowed") Collection<EvaluationStatus> allowed);

    /** → IDLE (완료 시각 기록, 에러 코드 비움). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Idea i set i.evaluationStatus = com.cue.idea.entity.EvaluationStatus.IDLE, i.evaluationFinishedAt = :now, "
            + "i.lastEvaluationErrorCode = null where i.id = :id")
    int markIdle(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** → FAILED (완료 시각과 에러 코드 기록). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Idea i set i.evaluationStatus = com.cue.idea.entity.EvaluationStatus.FAILED, i.evaluationFinishedAt = :now, "
            + "i.lastEvaluationErrorCode = :code where i.id = :id")
    int markFailed(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("code") String code);

    /** 부팅 시: 서버가 죽어 남은 QUEUED/RUNNING을 FAILED(code)로 정리한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Idea i set i.evaluationStatus = com.cue.idea.entity.EvaluationStatus.FAILED, i.evaluationFinishedAt = :now, "
            + "i.lastEvaluationErrorCode = :code where i.evaluationStatus in (com.cue.idea.entity.EvaluationStatus.QUEUED, com.cue.idea.entity.EvaluationStatus.RUNNING)")
    int failStaleEvaluations(@Param("now") LocalDateTime now, @Param("code") String code);

    /** 수정용 조회: 행을 잠가(SELECT ... FOR UPDATE) 상태 확인과 수정 사이에 평가 상태 전이가 끼어들지 못하게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Idea i where i.id = :id")
    java.util.Optional<Idea> findByIdForUpdate(@Param("id") Long id);

    @Query("select i.evaluationStatus from Idea i where i.id = :id")
    java.util.Optional<EvaluationStatus> findEvaluationStatus(@Param("id") Long id);

    /** 리더보드: 상태별 목록. 정렬은 호출하는 쪽에서 정한다. */
    List<Idea> findByUserIdAndStatus(Long userId, IdeaStatus status, Sort sort);

    /** 일일 평가 대상 id (HOLD 제외하려면 ACTIVE). */
    @Query("select i.id from Idea i where i.status = :status order by i.id")
    List<Long> findIdsByStatus(@Param("status") IdeaStatus status);
}
