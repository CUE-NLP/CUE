package com.cue.idea.service;

import com.cue.idea.entity.ItCategory;
import java.util.List;

/** AI가 아이디어에서 추출한 결과: IT 세부 종류와 외부 자료 검색용 키워드. (대분류 type은 항상 IT라 AI가 정하지 않는다.) */
public record IdeaAnalysis(ItCategory itCategory, List<String> keywords) {}
