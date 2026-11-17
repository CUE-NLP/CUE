package com.cue.trend.repository;

import com.cue.trend.entity.Trend;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrendRepository extends JpaRepository<Trend, Long> {}
