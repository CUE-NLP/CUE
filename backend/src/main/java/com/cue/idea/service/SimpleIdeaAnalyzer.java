package com.cue.idea.service;

import com.cue.idea.entity.Idea;
import com.cue.idea.entity.ItCategory;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * AI 없이 세부 종류는 DEV_TOOLS_INFRA, 키워드는 본문에서 긴 단어 2개를 고른다.
 * Gemini 비활성/키 없음/호출·파싱 실패 시의 폴백 구현이다 ({@link GeminiIdeaAnalyzer}).
 */
@Component
class SimpleIdeaAnalyzer implements IdeaAnalyzer {
    private static final int MAX_KEYWORDS = 2;

    @Override
    public IdeaAnalysis analyze(Idea idea) {
        String text = String.join(" ", idea.getProblem(), idea.getSolution(), idea.getTarget(), idea.getExpectation());
        List<String> keywords = Stream.of(text.split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 2)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(MAX_KEYWORDS)
                .toList();
        return new IdeaAnalysis(ItCategory.DEFAULT, keywords);
    }

    @Override
    public ItCategory analyzeCategory(Idea idea) {
        return ItCategory.DEFAULT;
    }
}
