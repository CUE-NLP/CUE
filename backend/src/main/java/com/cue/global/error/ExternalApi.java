package com.cue.global.error;

/** 실패 원인에 알리는 외부 API 이름. */
public enum ExternalApi {
    GEMINI("Gemini"),
    CLAUDE_GATEWAY("Claude Gateway"),
    OPENAI_GATEWAY("OpenAI Gateway"),
    GITHUB("GitHub"),
    HUGGING_FACE("Hugging Face"),
    STACK_EXCHANGE("Stack Exchange"),
    HACKER_NEWS("Hacker News"),
    SEMANTIC_SCHOLAR("Semantic Scholar"),
    WIKIPEDIA("Wikipedia"),
    /** 검색 결과가 가리키는 임의의 웹 페이지. {@code %s}는 호스트. */
    WEB_PAGE("웹 페이지(%s)");

    private final String template;

    ExternalApi(String template) {
        this.template = template;
    }

    public String label(Object... args) {
        return template.formatted(args);
    }
}
