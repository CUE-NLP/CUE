package com.cue.global.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptLoaderTest {
    private final PromptLoader loader = new PromptLoader();

    @Test
    void 플레이스홀더를_값으로_치환한다() {
        String out = loader.render("trend-summary", Map.of("keywords", "agent, rag", "items", "- 항목 하나"));
        assertTrue(out.contains("agent, rag"));
        assertTrue(out.contains("- 항목 하나"));
        assertFalse(out.contains("{{keywords}}"));
        assertFalse(out.contains("{{items}}"));
    }

    @Test
    void 값에_들어_있는_플레이스홀더_모양은_다시_치환하지_않는다() {
        String out = loader.render("trend-summary", Map.of("keywords", "{{items}}", "items", "X"));
        assertTrue(out.contains("[키워드]\n{{items}}"));
    }

    @Test
    void 주어지지_않은_플레이스홀더는_그대로_둔다() {
        String out = loader.render("trend-summary", Map.of("keywords", "k"));
        assertTrue(out.contains("{{items}}"));
    }

    @Test
    void 없는_프롬프트_파일이나_잘못된_이름은_예외() {
        assertThrows(IllegalStateException.class, () -> loader.render("no-such-prompt", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> loader.render("../application", Map.of()));
    }
}
