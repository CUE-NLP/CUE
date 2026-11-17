package com.cue.evaluation.dto;

import com.cue.source.entity.ApiProvider;

/** 근거 출처 입력. title, provider는 없어도 된다. */
public record ReferenceInput(String url, String title, ApiProvider provider) {}
