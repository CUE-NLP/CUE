package com.cue.source.fetch;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** HTML에서 본문 텍스트만 뽑는다 (스크립트·스타일·내비게이션 등 제외). */
public final class HtmlText {
    private HtmlText() {}

    public static String toText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg").remove();
        return doc.body().text().replaceAll("\\s+", " ").strip();
    }
}
