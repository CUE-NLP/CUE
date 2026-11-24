package com.cue.trend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 글로벌 트렌드 수집 설정 (prefix {@code cue.trend}).
 *
 * @param days "급상승" 판단 기간(일): 최근 N일 안에 올라온(만들어진) 글·저장소를 인기 순으로 본다
 * @param topN 고정 provider(HN·GitHub·Hugging Face)마다 읽고 요약할 상위 항목 수 (Gemini 호출 수에 비례)
 * @param minReevalDays 아이디어의 TREND를 다시 평가하기까지의 최소 간격(일). 새 트렌드가 생겨도 마지막 TREND 평가에 쓴 트렌드와 이 기간 안이면 호출하지 않는다.
 *                      첫 평가는 바로 한다. 0이면 새 트렌드마다 평가한다
 */
@ConfigurationProperties(prefix = "cue.trend")
public record TrendProperties(@DefaultValue("7") int days, @DefaultValue("5") int topN,
                              @DefaultValue("3") int minReevalDays) {}
