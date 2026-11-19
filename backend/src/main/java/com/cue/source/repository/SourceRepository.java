package com.cue.source.repository;

import com.cue.source.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceRepository extends JpaRepository<Source, Long> {
    java.util.List<Source> findByIdeaId(Long ideaId);

    /** 시연용: 아이디어의 모든 source 해시를 실제 해시와 절대 같지 않은 값으로 바꿔 다음 조회에서 "변경 감지됨"이 되게 한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update Source s set s.contentHash = :hash where s.idea.id = :ideaId")
    int forceChangedByIdeaId(@Param("ideaId") Long ideaId, @Param("hash") String hash);

    /** 키워드를 다시 추출할 때 지우는 수집 설정. 평가의 근거로 이미 연결된 행은 남긴다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Source s where s.idea.id = :ideaId and s.evaluation is null")
    void deleteUnlinkedByIdeaId(@Param("ideaId") Long ideaId);
}
