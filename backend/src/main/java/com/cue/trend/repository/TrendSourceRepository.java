package com.cue.trend.repository;

import com.cue.trend.entity.TrendSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrendSourceRepository extends JpaRepository<TrendSource, Long> {
    List<TrendSource> findByTrendIdOrderById(Long trendId);
}
