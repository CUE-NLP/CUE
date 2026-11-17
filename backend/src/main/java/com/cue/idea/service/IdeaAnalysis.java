package com.cue.idea.service;

import com.cue.idea.entity.IdeaType;
import java.util.List;

/** AI가 아이디어에서 추출한 결과: 아이디어 종류와 외부 자료 검색용 키워드. */
public record IdeaAnalysis(IdeaType type, List<String> keywords) {}
