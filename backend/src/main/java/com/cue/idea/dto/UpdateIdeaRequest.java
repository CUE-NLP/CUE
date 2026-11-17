package com.cue.idea.dto;

/** 아이디어 수정. null이면 그대로 두고, 빈 문자열이면 해당 항목을 비운다 (비우면 보류가 된다). */
public record UpdateIdeaRequest(String problem, String target, String solution, String expectation) {}
