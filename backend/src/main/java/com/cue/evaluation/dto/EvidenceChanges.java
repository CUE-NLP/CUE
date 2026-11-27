package com.cue.evaluation.dto;

import com.cue.source.dto.EvaluatedItem;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 점수에 반영해 둔 항목과 이번 조회를 URL로 비교한 결과. 비교는 아이디어 전체 단위다: 같은 URL이 다른 키워드·provider에 남아 있으면
 * 삭제가 아니고, 이미 반영한 URL이 다른 source에 새로 나타나도 추가가 아니다. 순위·제목·지표만 달라진 것도 추가·삭제가 아니다.
 *
 * @param added   새로 추가된 항목만 담은 조회 결과 (추가가 없는 source는 빠진다. 같은 URL은 한 번만)
 * @param removed 더 이상 어떤 검색 결과에도 없는 항목
 * @param current 이번 평가가 성공하면 source별로 기록할 항목 (조회에 실패한 source는 빠져 이전 기록이 유지된다)
 */
public record EvidenceChanges(List<SourceCheckResult> added, List<EvaluatedItem> removed,
                              Map<Long, List<EvaluatedItem>> current) {

    public boolean any() {
        return !added.isEmpty() || !removed.isEmpty();
    }

    /**
     * @param recorded source id별 반영해 둔 항목. 기록이 없는 source는 키가 없다
     * @param checked  이번 조회 결과
     */
    public static EvidenceChanges between(Map<Long, List<EvaluatedItem>> recorded, List<SourceCheckResult> checked) {
        Map<Long, List<EvaluatedItem>> current = new LinkedHashMap<>();
        Map<String, EvaluatedItem> before = new LinkedHashMap<>();
        recorded.values().forEach(items -> items.forEach(i -> before.putIfAbsent(i.url(), i)));
        Set<String> present = new HashSet<>();
        for (SourceCheckResult r : checked) {
            if (r.status() == SourceCheckResult.Status.FAILED) {
                // 조회에 실패한 source의 항목은 사라졌는지 알 수 없으니 남아 있는 것으로 본다
                recorded.getOrDefault(r.sourceId(), List.of()).forEach(i -> present.add(i.url()));
                continue;
            }
            List<EvaluatedItem> items = r.items().stream().map(i -> new EvaluatedItem(i.url(), i.title())).toList();
            current.put(r.sourceId(), items);
            items.forEach(i -> present.add(i.url()));
            // 해시가 그대로인데 기록만 없는 source(기록 기능 이전에 평가한 것)는 이미 반영한 것으로 본다
            if (r.status() == SourceCheckResult.Status.UNCHANGED && !recorded.containsKey(r.sourceId())) {
                items.forEach(i -> before.putIfAbsent(i.url(), i));
            }
        }

        Set<String> seen = new HashSet<>(before.keySet());
        List<SourceCheckResult> added = new ArrayList<>();
        for (SourceCheckResult r : checked) {
            if (r.status() == SourceCheckResult.Status.FAILED) {
                continue;
            }
            List<SourceItem> fresh = r.items().stream().filter(i -> seen.add(i.url())).toList();
            if (!fresh.isEmpty()) {
                added.add(new SourceCheckResult(r.sourceId(), r.provider(), r.keyword(), r.status(), fresh,
                        r.checkedAt(), r.errorMessage()));
            }
        }
        List<EvaluatedItem> removed = before.values().stream().filter(i -> !present.contains(i.url())).toList();
        return new EvidenceChanges(List.copyOf(added), removed, current);
    }
}
