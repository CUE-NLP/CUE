package com.cue.source.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 외부 자료 API 설정 (prefix {@code cue.source}). 토큰·키는 환경변수로만 주입하고 커밋하지 않는다. 모두 선택 값.
 *
 * @param topN               source마다 읽고 요약할 상위 항목 수 (Gemini 호출 수에 직접 비례)
 * @param maxContentChars    원문을 Gemini에 보내기 전 앞에서부터 자르는 길이
 * @param fetchMaxBytes      웹 원문 응답 크기 상한(바이트)
 * @param maxRedirects       웹 원문 가져오기에서 따라가는 리다이렉트 최대 횟수
 * @param batchContentChars  일괄 요약에서 항목 하나당 원문을 자르는 길이 (한 호출에 항목 여러 개를 넣으므로 {@code maxContentChars}보다 작게)
 * @param batchTotalChars    일괄 요약 한 호출의 원문 전체 상한. 넘으면 항목당 길이를 균등하게 더 줄인다
 * @param pageviewsDays      Wikipedia: 문서 조회수를 합산하는 최근 일수
 * @param wikimediaUserAgent Wikimedia 정책상 필수인 User-Agent. 프로젝트를 식별하는 일반 문자열로 두고 개인 이메일은 넣지 않는다
 * @param pageviewsBaseUrl   Wikimedia pageviews REST API 주소 (테스트에서 바꿀 수 있다)
 * @param summaryReuseDays   (글로벌 트렌드만) 같은 URL의 항목 요약을 다시 만들지 않고 재사용하는 기간(일). 0이면 항상 다시 요약
 * @param excerptChars       키워드 근거 자료에서 항목 하나당 평가 프롬프트에 직접 넣는 원문 발췌 길이(글자). AI 요약 호출 없이 정리한 원문 앞부분을 쓴다
 */
@ConfigurationProperties(prefix = "cue.source")
public record SourceApiProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("5") int topN,
        @DefaultValue("8000") int maxContentChars,
        @DefaultValue("1048576") int fetchMaxBytes,
        @DefaultValue("3") int maxRedirects,
        @DefaultValue("7") int summaryReuseDays,
        @DefaultValue("4000") int batchContentChars,
        @DefaultValue("16000") int batchTotalChars,
        @DefaultValue("30") int pageviewsDays,
        @DefaultValue("CUE-idea-portfolio/1.0 (student project; non-commercial)") String wikimediaUserAgent,
        @DefaultValue("https://wikimedia.org") String pageviewsBaseUrl,
        @DefaultValue("800") int excerptChars,
        @DefaultValue Api github,
        @DefaultValue Api huggingFace,
        @DefaultValue Api stackExchange,
        @DefaultValue Api hackerNews,
        @DefaultValue Api semanticScholar,
        @DefaultValue Api wikipedia) {

    public record Api(@DefaultValue("") String baseUrl, @DefaultValue("") String token) {}
}
