package com.cue.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 목록 API의 {@code data} 형태: {@code {items: [...], count: N}}.
 * 응답 데이터가 배열이면 항상 이 래퍼를 쓴다 (컨트롤러는 {@link CommonResponse#onSuccessList(List)}).
 * 페이지네이션을 넣을 때는 {@code page}, {@code size}, {@code totalCount} 같은 nullable 필드를 이 record에 추가한다.
 * 값이 null이면 응답에서 생략되므로(NON_NULL) 기존 응답과 호환된다. {@code count}는 항상 이번 응답의 items 크기다.
 */
@Schema(description = "목록 응답 데이터. items 배열과 개수")
@JsonPropertyOrder({"items", "count"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListData<T>(
        @Schema(description = "목록 항목. 없으면 빈 배열") List<T> items,
        @Schema(description = "items의 크기", example = "3") int count) {

    public static <T> ListData<T> of(List<T> items) {
        List<T> safe = items == null ? List.of() : List.copyOf(items);
        return new ListData<>(safe, safe.size());
    }
}
