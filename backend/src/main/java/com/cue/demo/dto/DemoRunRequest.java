package com.cue.demo.dto;

/** 시연 실행 요청 (본문 전체가 선택). {@code force}는 기본 false. */
public record DemoRunRequest(Boolean force) {
    public boolean forceOrDefault() {
        return Boolean.TRUE.equals(force);
    }
}
