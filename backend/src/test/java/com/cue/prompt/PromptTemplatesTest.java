package com.cue.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/** 프롬프트 파일, docs/ai-prompts.md의 플레이스홀더 표, JSON 예시가 서로 맞는지 검증한다. */
class PromptTemplatesTest {

    private static final List<String> FILES =
            List.of("trend-summary.txt", "item-summary.txt", "initial-evaluation.txt", "re-evaluation.txt");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Pattern SECTION = Pattern.compile("^### (\\S+\\.txt)\\s*$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|\\s*`\\{\\{(\\w+)}}`");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 프롬프트_파일이_클래스패스에_있고_비어있지_않다() throws IOException {
        for (String file : FILES) {
            ClassPathResource resource = new ClassPathResource("prompts/" + file);
            assertThat(resource.exists()).as(file).isTrue();
            assertThat(read(resource)).as(file).isNotBlank();
        }
    }

    @Test
    void 프롬프트의_플레이스홀더가_문서의_표와_일치한다() throws IOException {
        Map<String, Set<String>> documented = documentedPlaceholders();
        assertThat(documented.keySet()).containsExactlyInAnyOrderElementsOf(FILES);
        for (String file : FILES) {
            Set<String> used = new TreeSet<>();
            Matcher m = PLACEHOLDER.matcher(read(new ClassPathResource("prompts/" + file)));
            while (m.find()) {
                used.add(m.group(1));
            }
            assertThat(used).as(file).isEqualTo(documented.get(file));
        }
    }

    @Test
    void 문서의_JSON_예시가_유효한_JSON이다() throws IOException {
        List<String> blocks = jsonBlocks(Files.readString(docPath(), StandardCharsets.UTF_8));
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(4);
        for (String block : blocks) {
            assertThat(objectMapper.readTree(block).isObject()).as(block).isTrue();
        }
    }

    @Test
    void 프롬프트에_인젝션_방어와_JSON_전용_출력_규칙이_있다() throws IOException {
        for (String file : FILES) {
            String text = read(new ClassPathResource("prompts/" + file));
            assertThat(text).as(file).contains("데이터").contains("JSON");
        }
    }

    private Map<String, Set<String>> documentedPlaceholders() throws IOException {
        Map<String, Set<String>> result = new TreeMap<>();
        String current = null;
        for (String line : Files.readAllLines(docPath(), StandardCharsets.UTF_8)) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                current = section.group(1);
                result.put(current, new LinkedHashSet<>());
                continue;
            }
            if (line.startsWith("#")) {
                current = null;
                continue;
            }
            Matcher row = TABLE_ROW.matcher(line);
            if (current != null && row.find()) {
                result.get(current).add(row.group(1));
            }
        }
        result.replaceAll((k, v) -> new TreeSet<>(v));
        return result;
    }

    private List<String> jsonBlocks(String doc) {
        Matcher m = Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL).matcher(doc);
        List<String> blocks = new java.util.ArrayList<>();
        while (m.find()) {
            blocks.add(m.group(1));
        }
        return blocks;
    }

    /** Gradle 테스트의 작업 디렉터리는 backend/ 이므로 상위의 docs를 찾는다. */
    private Path docPath() {
        Path p = Path.of("..", "docs", "ai-prompts.md");
        return Files.exists(p) ? p : Path.of("docs", "ai-prompts.md");
    }

    private String read(ClassPathResource resource) throws IOException {
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
