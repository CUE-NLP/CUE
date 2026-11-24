package com.cue.source.service;

import java.util.regex.Pattern;

/**
 * 원문(README·HTML·마크다운·질문 본문)에서 평가 프롬프트에 넣을 앞부분 발췌를 만든다. AI를 쓰지 않고 잡음만 걷어 낸다.
 * <ul>
 *   <li>YAML 프론트매터, 코드 블록, HTML 주석·태그, 이미지(배지), 맨 URL을 지우고 링크는 글자만 남긴다.</li>
 *   <li>"English · 简体中文 · 日本語"처럼 구분자로만 이어진 짧은 항목의 탐색 줄은 버린다.</li>
 *   <li>공백을 한 칸으로 합쳐 한 줄로 만든 뒤 {@code maxChars}에서 자른다(넘치면 끝에 "…").</li>
 * </ul>
 */
public final class TextExcerpt {
    private TextExcerpt() {}

    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\s*\\n.*?\\n---\\s*(\\n|\\z)", Pattern.DOTALL);
    private static final Pattern CODE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    /** 질문 본문 HTML의 코드 블록(`<pre>…</pre>`)은 글이 아니라 코드라 통째로 버린다. */
    private static final Pattern PRE_BLOCK = Pattern.compile("<pre[^>]*>.*?</pre>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** GitHub 경고 표시(`[!IMPORTANT]`, `[!NOTE]` 등). */
    private static final Pattern ALERT_MARK = Pattern.compile("\\[![A-Za-z]+]");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{1,200}>");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern BARE_URL = Pattern.compile("https?://\\S+");
    private static final Pattern LINE_MARK = Pattern.compile("^\\s{0,3}(#{1,6}\\s*|>+\\s*)");
    private static final Pattern MARKUP_CHARS = Pattern.compile("[*`]|(?<!\\w)_|_(?!\\w)");
    private static final Pattern SEPARATOR = Pattern.compile("\\s[·|•]\\s");

    /** 정리한 발췌. 원문이 비었거나 정리 후 남는 글이 없으면 빈 문자열. */
    public static String of(String raw, int maxChars) {
        if (raw == null || raw.isBlank() || maxChars <= 0) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = FRONT_MATTER.matcher(text).replaceFirst("");
        text = CODE_BLOCK.matcher(text).replaceAll(" ");
        text = HTML_COMMENT.matcher(text).replaceAll(" ");
        text = PRE_BLOCK.matcher(text).replaceAll(" ");
        text = ALERT_MARK.matcher(text).replaceAll(" ");
        text = IMAGE.matcher(text).replaceAll(" ");
        text = LINK.matcher(text).replaceAll("$1");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = BARE_URL.matcher(text).replaceAll(" ");
        text = decodeEntities(text);

        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = LINE_MARK.matcher(line).replaceFirst("");
            l = MARKUP_CHARS.matcher(l).replaceAll("").replaceAll("\\s+", " ").strip();
            if (l.isEmpty() || isRule(l) || isNavigation(l)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(l);
            if (out.length() > maxChars) {
                break;
            }
        }
        String result = out.toString();
        return result.length() > maxChars ? result.substring(0, maxChars) + "…" : result;
    }

    /** HTML이 남긴 흔한 엔티티를 글자로 되돌린다(`&amp;`는 이중 변환을 피하려고 마지막에). */
    private static String decodeEntities(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
    }

    /** {@code ---}, {@code ===}처럼 글자가 없는 구분선. */
    private static boolean isRule(String line) {
        return line.chars().noneMatch(Character::isLetterOrDigit);
    }

    /** 구분자(· | •)로 짧은 항목이 3개 이상 이어진 탐색 줄(언어 목록, 메뉴 등). */
    private static boolean isNavigation(String line) {
        long separators = SEPARATOR.matcher(line).results().count();
        return separators >= 3 && line.length() / (separators + 1) < 20;
    }
}
