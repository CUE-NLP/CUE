package com.cue.source.service;

import com.cue.source.client.SourceClient;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 변경이 감지된 source의 상위 N개 항목을 읽고, 평가 프롬프트에 그대로 넣을 <b>원문 발췌</b>를 만든다. <b>Gemini를 부르지 않는다.</b>
 *
 * <p>예전에는 항목 일괄 요약 1회 + 소스 요약 1회(source당 2회)를 쓰고 그 요약을 다시 평가에 넣었다. 지금은 API가 주는 짧은 설명과
 * 원문 앞부분({@link TextExcerpt})을 합쳐 {@link SourceItem#summary()} 자리에 넣고, 아이디어 하나를 한 번의 평가 호출로 처리한다.
 * 무료 티어의 병목이 토큰이 아니라 요청 수이기 때문이고, 이중 요약으로 정보가 깎이는 것도 막는다.
 *
 * <p>발췌는 저장하지 않는다(원문 저장 금지 방침). 원문 읽기에 실패한 항목도 버리지 않고 API가 준 짧은 설명만으로 남긴다.
 * API 설명이 이미 발췌 길이만큼 충분하면 원문을 다시 읽지 않는다(GitHub README 같은 호출의 익명 한도를 아낀다).
 * 외부 HTTP만 쓰므로 @Transactional을 쓰지 않는다.
 */
@Slf4j
@Service
public class SourceExcerptService {
    /** 항목 발췌에서 API 설명이 차지할 수 있는 최대 길이. 나머지는 원문 발췌에 쓴다. */
    private static final int MAX_DESCRIPTION_CHARS = 300;
    private static final int MIN_EXCERPT_CHARS = 100;

    private final Map<ApiProvider, SourceClient> clients = new EnumMap<>(ApiProvider.class);
    private final SourceApiProperties props;

    public SourceExcerptService(List<SourceClient> clientList, SourceApiProperties props) {
        clientList.forEach(c -> clients.put(c.provider(), c));
        this.props = props;
    }

    /** 상위 N개 항목의 {@code summary}를 원문 발췌로 바꾼 조회 결과를 돌려준다. */
    public SourceCheckResult excerpt(SourceCheckResult changed) {
        SourceClient client = clients.get(changed.provider());
        List<SourceItem> items = new ArrayList<>();
        for (SourceItem item : changed.items().stream().limit(props.topN()).toList()) {
            items.add(new SourceItem(item.title(), item.url(), summaryOf(client, changed, item), item.publishedAt(), item.metrics()));
        }
        return SourceCheckResult.changed(changed.sourceId(), changed.provider(), changed.keyword(), items, changed.checkedAt());
    }

    private String summaryOf(SourceClient client, SourceCheckResult changed, SourceItem item) {
        String description = clip(normalize(item.summary()), MAX_DESCRIPTION_CHARS);
        int budget = Math.max(props.excerptChars() - description.length() - 3, MIN_EXCERPT_CHARS);
        if (client == null || normalize(item.summary()).length() >= props.excerptChars()) {
            return clip(normalize(item.summary()), props.excerptChars());
        }
        String excerpt = "";
        try {
            excerpt = TextExcerpt.of(client.fetchContent(item), budget);
        } catch (RuntimeException e) {
            log.warn("원문 읽기 실패, API 설명만 씁니다 sourceId={} url={}: {}", changed.sourceId(), item.url(), e.toString());
        }
        if (excerpt.isEmpty()) {
            return description;
        }
        if (description.isEmpty() || excerpt.startsWith(description)) {
            return excerpt;
        }
        return description + " — " + excerpt;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String clip(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
