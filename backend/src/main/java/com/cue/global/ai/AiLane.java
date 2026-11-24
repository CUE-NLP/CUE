package com.cue.global.ai;

/**
 * Gemini 호출 레인. 사용자가 화면에서 기다리는 호출과, 아무도 기다리지 않는 배치 호출을 나눠 서로의 대기열·한도를 먹지 않게 한다.
 * <ul>
 *   <li>{@link #INTERACTIVE}: 구조화, 키워드·종류 추출. 가벼운 모델, 짧은 간격, 오래 기다리지 않고 {@code E006}로 빨리 실패한다.</li>
 *   <li>{@link #BATCH}: 근거 평가, 트렌드 요약·평가. 기본 모델, 호출 사이 긴 간격, 한도가 풀릴 때까지 기다린다.</li>
 * </ul>
 */
public enum AiLane {
    INTERACTIVE, BATCH
}
