package com.cue.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cue.source.service.TextExcerpt;
import org.junit.jupiter.api.Test;

class TextExcerptTest {
    @Test
    void 프론트매터_배지_언어목록_링크_마크업을_걷어내고_본문_앞부분만_남긴다() {
        String readme = """
                ---
                license: apache-2.0
                tags:
                - gguf
                ---
                <div align="center">

                **English** · [简体中文](docs/README_zh.md) · [日本語](docs/README_ja.md) · [한국어](docs/README_ko.md) · [Türkçe](docs/README_tr.md)

                [![Build](https://img.shields.io/badge/build-passing.svg)](https://ci.example.com)

                # Rea

                Reverse engineer **anything** with [agents](https://example.com/agents), from `app behavior` down to binaries.
                ```bash
                npm install rea
                ```
                ## Features
                - Fast
                </div>
                """;
        String out = TextExcerpt.of(readme, 500);
        assertEquals("Rea Reverse engineer anything with agents, from app behavior down to binaries. Features - Fast", out);
        assertFalse(out.contains("license"));
        assertFalse(out.contains("简体中文"));
        assertFalse(out.contains("npm install"));
        assertFalse(out.contains("http"));
    }

    @Test
    void HTML_본문의_태그와_엔티티_줄바꿈을_정리한다() {
        String out = TextExcerpt.of("<p>I am building a <code>React</code> app.</p>\n<pre>x = 1</pre>\n<p>It fails.</p>", 200);
        assertEquals("I am building a React app. It fails.", out); // <pre> 코드 블록은 버린다
    }

    @Test
    void GitHub_경고_표시를_지우고_엔티티를_글자로_되돌린다() {
        String out = TextExcerpt.of("[!IMPORTANT]\nJoin Q&amp;A &lt;now&gt; &quot;today&quot; it&#39;s ok&nbsp;fine", 200);
        assertEquals("Join Q&A <now> \"today\" it's ok fine", out);
    }

    @Test
    void 길이를_넘으면_자르고_끝에_말줄임표를_붙인다() {
        String out = TextExcerpt.of("가".repeat(50), 20);
        assertEquals("가".repeat(20) + "…", out);
        assertTrue(TextExcerpt.of("짧은 글", 20).equals("짧은 글"));
    }

    @Test
    void 비었거나_정리하면_남는_글이_없으면_빈_문자열() {
        assertEquals("", TextExcerpt.of(null, 100));
        assertEquals("", TextExcerpt.of("   \n ", 100));
        assertEquals("", TextExcerpt.of("---\n===\n![badge](https://x/y.svg)", 100));
        assertEquals("", TextExcerpt.of("내용", 0));
    }
}
