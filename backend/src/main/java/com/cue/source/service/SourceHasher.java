package com.cue.source.service;

import com.cue.source.dto.SourceItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 조회 결과의 제목·URL·요약으로 SHA-256(hex 64자) 해시를 만든다. 같은 내용이면 항상 같은 값. */
public final class SourceHasher {
    private SourceHasher() {}

    public static String hash(List<SourceItem> items) {
        StringBuilder sb = new StringBuilder();
        for (SourceItem i : items) {
            sb.append(i.title()).append('\u0001').append(i.url()).append('\u0001').append(i.summary()).append('\u0002');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
