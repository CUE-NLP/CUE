package com.cue.idea.service;

import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;

/** 구조화가 끝난(ACTIVE) 아이디어에서 종류와 검색 키워드를 추출한다. 실제 구현은 Gemini 연동 (실패 시 G002). */
public interface IdeaAnalyzer {
    IdeaAnalysis analyze(Idea idea);

    /** 사용자가 키워드를 직접 정한 경우: 종류만 분류한다 (키워드 추출 없음). 실패 시 폴백(IT). */
    IdeaType analyzeType(Idea idea);
}
