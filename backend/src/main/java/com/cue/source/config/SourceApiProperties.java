package com.cue.source.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 외부 자료 API 설정 (prefix {@code cue.source}). 토큰·키는 환경변수로만 주입하고 커밋하지 않는다. 모두 선택 값.
 *
 * @param topN               source마다 읽고 요약할 상위 항목 수 (Gemini 호출 수에 직접 비례)
 * @param trendingDays       "급상승" 판단 기간(일). 최근 N일 안의 글/저장소만 인기 순으로 본다
 * @param maxContentChars    원문을 Gemini에 보내기 전 앞에서부터 자르는 길이
 * @param fetchMaxBytes      웹 원문 응답 크기 상한(바이트)
 * @param maxRedirects       웹 원문 가져오기에서 따라가는 리다이렉트 최대 횟수
 * @param batchContentChars  일괄 요약에서 항목 하나당 원문을 자르는 길이 (한 호출에 항목 여러 개를 넣으므로 {@code maxContentChars}보다 작게)
 * @param batchTotalChars    일괄 요약 한 호출의 원문 전체 상한. 넘으면 항목당 길이를 균등하게 더 줄인다
 * @param summaryReuseDays   같은 URL의 항목 요약을 다시 만들지 않고 재사용하는 기간(일). 0이면 항상 다시 요약
 */
@ConfigurationProperties(prefix = "cue.source")
public record SourceApiProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("5") int topN,
        @DefaultValue("30") int trendingDays,
        @DefaultValue("8000") int maxContentChars,
        @DefaultValue("1048576") int fetchMaxBytes,
        @DefaultValue("3") int maxRedirects,
        @DefaultValue("7") int summaryReuseDays,
        @DefaultValue("4000") int batchContentChars,
        @DefaultValue("16000") int batchTotalChars,
        @DefaultValue Api github,
        @DefaultValue Api huggingFace,
        @DefaultValue Api stackExchange,
        @DefaultValue Api hackerNews) {

    public record Api(@DefaultValue("") String baseUrl, @DefaultValue("") String token) {}
}
