package com.cue.trend.repository;

import com.cue.trend.entity.Trend;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrendRepository extends JpaRepository<Trend, Long> {
    Optional<Trend> findFirstByOrderByIdDesc();
}
