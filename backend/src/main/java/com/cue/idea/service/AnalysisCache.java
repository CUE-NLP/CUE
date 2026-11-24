package com.cue.idea.service;

import com.cue.global.cache.TtlCache;
import com.cue.idea.entity.Idea;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 구조화 API가 이미 뽑아 둔 IT 세부 종류·키워드(AI 결과)를 저장 때 재사용하기 위한 캐시.
 * 키는 <b>사용자 ID + 4개 항목 내용의 해시</b>라서, 사용자가 구조화 결과를 한 글자라도 고쳐 저장하면 해시가 달라져 적중하지 않고 평소처럼 다시 추출한다.
 * 서버가 직접 만든 AI 결과만 들어가므로(클라이언트가 보낸 값이 아님) 믿어도 된다. 폴백(임시 분석기) 결과는 넣지 않는다.
 */
@Component
@EnableConfigurationProperties(StructuringProperties.class)
class AnalysisCache {
    private final TtlCache<String, IdeaAnalysis> cache;

    @Autowired
    AnalysisCache(StructuringProperties props) {
        this(props, Clock.systemUTC());
    }

    AnalysisCache(StructuringProperties props, Clock clock) {
        this.cache = new TtlCache<>(Duration.ofMinutes(props.cacheTtlMinutes()), props.cacheMaxEntries(), clock);
    }

    void put(Long userId, String target, String problem, String solution, String expectation, IdeaAnalysis analysis) {
        cache.put(key(userId, target, problem, solution, expectation), analysis);
    }

    /** 같은 사용자·같은 4개 항목으로 만든 추출 결과가 있으면 돌려주고, 없으면 null. */
    IdeaAnalysis find(Long userId, Idea idea) {
        return cache.get(key(userId, idea.getTarget(), idea.getProblem(), idea.getSolution(), idea.getExpectation()));
    }

    static String key(Long userId, String target, String problem, String solution, String expectation) {
        return userId + ":" + sha256(String.join("\u0000", norm(target), norm(problem), norm(solution), norm(expectation)));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
