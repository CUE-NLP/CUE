package com.cue.source.entity;

import com.cue.source.dto.EvaluatedItem;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code sources.evaluated_items}(JSON 배열 문자열) 변환. null은 "아직 기록한 적 없음"이라 빈 배열과 구분해 그대로 둔다. */
@Converter
public class EvaluatedItemsConverter implements AttributeConverter<List<EvaluatedItem>, String> {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<EvaluatedItem>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<EvaluatedItem> items) {
        return items == null ? null : MAPPER.writeValueAsString(items);
    }

    @Override
    public List<EvaluatedItem> convertToEntityAttribute(String json) {
        return json == null ? null : MAPPER.readValue(json, TYPE);
    }
}
