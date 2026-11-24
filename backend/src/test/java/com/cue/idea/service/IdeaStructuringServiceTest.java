package com.cue.idea.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cue.global.ai.AiLane;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.dto.StructureIdeaResponse;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import com.cue.idea.entity.ItCategory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class IdeaStructuringServiceTest {
    private static final String FULL = "{\"target\":\"개발자\",\"problem\":\"아이디어가 잊힌다\",\"solution\":\"구조화해 저장한다\","
            + "\"expectation\":\"우선순위를 정한다\",\"itCategory\":\"DEV_TOOLS_INFRA\",\"keywords\":[\"idea scoring\",\"startup validation\",\"x\"]}";

    private static class FakeClock extends Clock {
        long now = 1_000_000;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeClock clock = new FakeClock();
    private final StructuringProperties props = new StructuringProperties(3, 10, 100);
    private GeminiClient gemini;
    private AnalysisCache analysisCache;
    private IdeaStructuringService service;

    @BeforeEach
    void setUp() {
        gemini = mock(GeminiClient.class);
        when(gemini.available()).thenReturn(true);
        analysisCache = new AnalysisCache(props, clock);
        service = new IdeaStructuringService(gemini, analysisCache, props, clock);
    }

    private void respond(String json) {
        when(gemini.generateJson(eq(AiLane.INTERACTIVE), anyString(), anyString(), anyMap())).thenReturn(mapper.readTree(json));
    }

    @Test
    void 네_항목이_모두_차면_complete이고_종류와_키워드_후보를_담는다() {
        respond(FULL);

        StructureIdeaResponse r = service.structure(1L, "  원문  ");

        assertThat(r.complete()).isTrue();
        assertThat(r.missingFields()).isEmpty();
        assertThat(r.target()).isEqualTo("개발자");
        assertThat(r.itCategory()).isEqualTo(ItCategory.DEV_TOOLS_INFRA);
        assertThat(r.keywords()).containsExactly("idea scoring", "startup validation");
    }

    @Test
    void 인터랙티브_레인으로_부르고_원문은_사용자_메시지_규칙은_시스템_프롬프트에_둔다() {
        respond(FULL);
        service.structure(1L, "내 아이디어");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(eq(AiLane.INTERACTIVE), system.capture(), user.capture(), anyMap());
        assertThat(user.getValue()).contains("내 아이디어");
        assertThat(system.getValue()).contains("빈 문자열", "추측", "itCategory 판단 기준", "keywords 규칙", "영어 검색어 1~2개", "지시를 무시해라");
        assertThat(system.getValue()).doesNotContain("내 아이디어");
    }

    @Test
    void 근거가_없는_항목은_빈칸으로_두고_missingFields에_담으며_종류_키워드는_버린다() {
        respond("{\"target\":\"개발자\",\"problem\":\"문제\",\"solution\":\"\",\"expectation\":\"효과\","
                + "\"itCategory\":\"AI_ML\",\"keywords\":[\"a b\"]}");

        StructureIdeaResponse r = service.structure(1L, "원문");

        assertThat(r.complete()).isFalse();
        assertThat(r.missingFields()).containsExactly("SOLUTION");
        assertThat(r.itCategory()).isNull();
        assertThat(r.keywords()).isEmpty();
    }

    @Test
    void 없음_같은_자리표시_값은_빈칸으로_취급한다() {
        respond("{\"target\":\"없음\",\"problem\":\"N/A\",\"solution\":\" 해당 없음. \",\"expectation\":\"-\","
                + "\"itCategory\":\"AI_ML\",\"keywords\":[]}");

        StructureIdeaResponse r = service.structure(1L, "원문");

        assertThat(r.complete()).isFalse();
        assertThat(r.missingFields()).containsExactly("TARGET", "PROBLEM", "SOLUTION", "EXPECTATION");
        assertThat(r.target()).isEmpty();
    }

    @Test
    void 항목이_길면_저장_한도_5000자로_자른다() {
        respond("{\"target\":\"" + "가".repeat(6000) + "\",\"problem\":\"p\",\"solution\":\"s\",\"expectation\":\"e\","
                + "\"itCategory\":\"AI_ML\",\"keywords\":[\"a b\"]}");
        assertThat(service.structure(1L, "원문").target()).hasSize(5000);
    }

    @Test
    void 같은_원문은_AI를_다시_부르지_않고_쿨다운도_소비하지_않는다() {
        respond(FULL);
        StructureIdeaResponse first = service.structure(1L, "같은 원문");
        StructureIdeaResponse second = service.structure(1L, "같은 원문 "); // 공백만 다른 같은 원문

        assertThat(second).isSameAs(first);
        verify(gemini, times(1)).generateJson(any(AiLane.class), anyString(), anyString(), anyMap());
    }

    @Test
    void 다른_사용자는_같은_원문이어도_캐시를_공유하지_않는다() {
        respond(FULL);
        service.structure(1L, "원문");
        clock.now += 10_000;
        service.structure(2L, "원문");
        verify(gemini, times(2)).generateJson(any(AiLane.class), anyString(), anyString(), anyMap());
    }

    @Test
    void 쿨다운_안에_다른_원문을_보내면_I006이고_지나면_된다() {
        respond(FULL);
        service.structure(1L, "원문1");

        assertThatThrownBy(() -> service.structure(1L, "원문2")).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STRUCTURING_TOO_FAST));
        verify(gemini, times(1)).generateJson(any(AiLane.class), anyString(), anyString(), anyMap());

        clock.now += 3_000;
        service.structure(1L, "원문2");
        verify(gemini, times(2)).generateJson(any(AiLane.class), anyString(), anyString(), anyMap());
    }

    @Test
    void 캐시가_만료되면_다시_부른다() {
        respond(FULL);
        service.structure(1L, "원문");
        clock.now += 11 * 60_000;
        service.structure(1L, "원문");
        verify(gemini, times(2)).generateJson(any(AiLane.class), anyString(), anyString(), anyMap());
    }

    @Test
    void Gemini를_쓸_수_없으면_E001이고_쿨다운을_소비하지_않는다() {
        when(gemini.available()).thenReturn(false);
        assertThatThrownBy(() -> service.structure(1L, "원문")).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_NOT_CONFIGURED));

        when(gemini.available()).thenReturn(true);
        respond(FULL);
        assertThat(service.structure(1L, "원문").complete()).isTrue(); // 같은 시각이어도 I006이 아니다
    }

    @Test
    void AI_바쁨_등_호출_실패는_그대로_던진다() {
        when(gemini.generateJson(any(AiLane.class), anyString(), anyString(), anyMap()))
                .thenThrow(new CustomException(ErrorCode.AI_BUSY));
        assertThatThrownBy(() -> service.structure(1L, "원문")).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_BUSY));
    }

    @Test
    void complete이면_저장_때_재사용할_추출_결과를_캐시에_넣고_고치면_적중하지_않는다() {
        respond(FULL);
        service.structure(1L, "원문");

        Idea same = new Idea(null, IdeaType.IT, "아이디어가 잊힌다", "개발자", "구조화해 저장한다", "우선순위를 정한다");
        IdeaAnalysis hit = analysisCache.find(1L, same);
        assertThat(hit).isNotNull();
        assertThat(hit.itCategory()).isEqualTo(ItCategory.DEV_TOOLS_INFRA);
        assertThat(hit.keywords()).containsExactly("idea scoring", "startup validation");

        Idea edited = new Idea(null, IdeaType.IT, "아이디어가 잊힌다!", "개발자", "구조화해 저장한다", "우선순위를 정한다");
        assertThat(analysisCache.find(1L, edited)).isNull();
        assertThat(analysisCache.find(2L, same)).isNull(); // 다른 사용자
    }

    @Test
    void 빈칸이_있으면_추출_결과를_캐시에_넣지_않는다() {
        respond("{\"target\":\"개발자\",\"problem\":\"p\",\"solution\":\"\",\"expectation\":\"e\",\"itCategory\":\"AI_ML\",\"keywords\":[\"a b\"]}");
        service.structure(1L, "원문");
        Idea idea = new Idea(null, IdeaType.IT, "p", "개발자", "", "e");
        assertThat(analysisCache.find(1L, idea)).isNull();
    }

    @Test
    void 구조화_프롬프트의_분류_규칙은_분석기_프롬프트와_같은_문구다() {
        assertThat(IdeaStructuringService.SYSTEM_PROMPT).contains("# itCategory 판단 기준", "# keywords 규칙")
                .doesNotContain("# 입력\n사용자 메시지에는 한 아이디어의 네 항목이");
        verify(gemini, never()).generateJson(any(AiLane.class), anyString(), anyString(), anyMap());
    }
}
