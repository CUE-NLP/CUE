package com.cue.trend.repository;

import com.cue.trend.entity.TrendItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrendItemRepository extends JpaRepository<TrendItem, Long> {
    List<TrendItem> findByTrendIdOrderById(Long trendId);
}
