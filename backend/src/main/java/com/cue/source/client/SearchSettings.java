package com.cue.source.client;

import java.time.Clock;
import java.time.LocalDate;

/** 클라이언트 공통 조회 설정: 상위 몇 개를 볼지, "급상승" 판단 기간(최근 N일). 시계는 테스트에서 고정할 수 있다. */
public record SearchSettings(int topN, int trendingDays, Clock clock) {
    public SearchSettings(int topN, int trendingDays) {
        this(topN, trendingDays, Clock.systemUTC());
    }

    /** 키워드 근거 조회용: 기간을 두지 않는다(기간 필터가 필요한 트렌드 조회는 호출하는 쪽이 기간을 담은 설정을 따로 넘긴다). */
    public static SearchSettings forKeywordSearch(int topN) {
        return new SearchSettings(topN, 0);
    }

    /** 이 날짜 이후(포함하지 않음)만 최근으로 본다. */
    public LocalDate cutoffDate() {
        return LocalDate.now(clock).minusDays(trendingDays);
    }

    public long cutoffEpochSecond() {
        return cutoffDate().atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
    }
}
