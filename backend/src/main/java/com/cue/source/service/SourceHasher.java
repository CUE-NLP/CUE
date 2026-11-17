package com.cue.source.service;

import com.cue.source.dto.SourceItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 조회 결과의 변경 감지용 SHA-256(hex 64자) 해시.
 *
 * <p>규칙: <b>바뀌지 않는 값인 제목과 URL만</b> 사용한다. 요약·스니펫·지표(별 수, 다운로드 수)·날짜처럼 바뀔 수 있는 값은
 * 해시에 넣지 않는다. 또 순위만 바뀌고 항목 집합이 같으면 "새 정보"가 아니므로 url 기준으로 정렬한 뒤 계산해 순서와 무관하게 만든다.
 * 따라서 항목이 추가·교체되거나 제목·URL이 바뀔 때만 해시가 달라진다.
 */
public final class SourceHasher {
    private static final Comparator<SourceItem> BY_URL =
            Comparator.comparing(SourceItem::url).thenComparing(SourceItem::title);

    private SourceHasher() {}

    public static String hash(List<SourceItem> items) {
        StringBuilder sb = new StringBuilder();
        items.stream().sorted(BY_URL).forEach(i ->
                sb.append(i.title()).append('\u0001').append(i.url()).append('\u0002'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
