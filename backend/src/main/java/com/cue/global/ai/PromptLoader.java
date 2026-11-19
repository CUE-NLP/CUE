package com.cue.global.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * {@code resources/prompts/{name}.txt} 프롬프트를 읽고 {@code {{name}}} 플레이스홀더를 치환한다.
 * 값에 {@code {{...}}}가 들어 있어도 다시 치환하지 않으며(한 번만 치환), 주어지지 않은 플레이스홀더는 그대로 둔다.
 */
@Component
public class PromptLoader {
    private final Map<String, String> templates = new ConcurrentHashMap<>();

    public String render(String name, Map<String, String> variables) {
        String template = templates.computeIfAbsent(name, this::read);
        StringBuilder out = new StringBuilder(template.length() + 256);
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            int close = open < 0 ? -1 : template.indexOf("}}", open + 2);
            if (open < 0 || close < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            String key = template.substring(open + 2, close).trim();
            String value = variables.get(key);
            out.append(value != null ? value : template.substring(open, close + 2));
            i = close + 2;
        }
        return out.toString();
    }

    private String read(String name) {
        if (!name.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("잘못된 프롬프트 이름: " + name);
        }
        try (InputStream in = new ClassPathResource("prompts/" + name + ".txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 파일을 읽을 수 없습니다: prompts/" + name + ".txt", e);
        }
    }
}
