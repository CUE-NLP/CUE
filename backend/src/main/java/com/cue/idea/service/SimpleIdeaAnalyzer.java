package com.cue.idea.service;

import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 임시 구현: AI 없이 종류는 IT, 키워드는 본문에서 긴 단어 3개를 고른다.
 * TODO: Gemini 연동 구현으로 교체한다 (이 클래스는 삭제).
 */
@Component
class SimpleIdeaAnalyzer implements IdeaAnalyzer {
    private static final int MAX_KEYWORDS = 3;

    @Override
    public IdeaAnalysis analyze(Idea idea) {
        String text = String.join(" ", idea.getProblem(), idea.getSolution(), idea.getTarget(), idea.getExpectation());
        List<String> keywords = Stream.of(text.split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 2)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(MAX_KEYWORDS)
                .toList();
        return new IdeaAnalysis(IdeaType.IT, keywords);
    }
}
