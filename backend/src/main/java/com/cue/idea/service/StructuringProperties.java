package com.cue.idea.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 구조화 API 설정 (prefix {@code cue.structuring}).
 *
 * @param cooldownSeconds 같은 사용자의 구조화 요청 최소 간격(초). 안에 다시 보내면 I006. 캐시 적중은 소비하지 않는다
 * @param cacheTtlMinutes 같은 원문의 구조화 결과와, 저장 때 재사용할 키워드·종류 추출 결과를 들고 있는 시간(분)
 * @param cacheMaxEntries 캐시 최대 개수 (넘으면 오래 안 쓴 것부터 버림)
 */
@ConfigurationProperties(prefix = "cue.structuring")
public record StructuringProperties(@DefaultValue("3") int cooldownSeconds,
                                    @DefaultValue("10") int cacheTtlMinutes,
                                    @DefaultValue("500") int cacheMaxEntries) {}
