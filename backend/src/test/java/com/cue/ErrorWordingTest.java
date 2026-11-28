package com.cue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 에러 위치·API·원인 문구가 {@code global/error} 밖에서 만들어지지 않는지 소스를 훑어 확인한다. */
class ErrorWordingTest {
    /** 문자열로 detail을 만들거나, 로그에 위치·API 이름을 직접 적는 코드. */
    private static final Pattern INLINE = Pattern.compile("new ErrorDetail\\(\\s*\"|\\.at\\(\\s*\"|\"[^\"]*(위치|API|원인)=[^{\"][^\"]*\"");

    @Test
    void 에러_문구는_global_error에서만_정한다() throws IOException {
        Path root = Path.of("src/main/java/com/cue");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> found = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(root.resolve("global/error")))
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                                    .filter(line -> INLINE.matcher(line).find()).map(line -> p.getFileName() + ": " + line.strip());
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).toList();
            assertEquals(List.of(), found);
        }
    }
}
