package com.cue.trend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 글로벌 트렌드 수집 설정 (prefix {@code cue.trend}).
 *
 * @param days "급상승" 판단 기간(일): 최근 N일 안에 올라온(만들어진) 글·저장소를 인기 순으로 본다
 * @param topN 고정 provider(HN·GitHub·Hugging Face)마다 읽고 요약할 상위 항목 수 (Gemini 호출 수에 비례)
 */
@ConfigurationProperties(prefix = "cue.trend")
public record TrendProperties(@DefaultValue("7") int days, @DefaultValue("5") int topN) {}
