package com.cue.source;

import static org.junit.jupiter.api.Assertions.*;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.fetch.UrlGuard;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SSRF 차단: 리터럴 IP만 써서 DNS 없이 검증한다. */
class UrlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/", "http://127.1.2.3:8080/x", "http://[::1]/", "http://localhost/",
            "http://10.0.0.5/", "http://172.16.0.1/", "http://192.168.1.10/", "http://169.254.169.254/latest/meta-data",
            "http://0.0.0.0/", "http://100.64.0.1/", "http://[fd00::1]/", "http://[fe80::1]/", "http://224.0.0.1/",
            "http://[::ffff:127.0.0.1]/",
            "ftp://93.184.216.34/file", "file:///etc/passwd", "gopher://93.184.216.34/", "javascript:alert(1)",
            "http://user:pass@93.184.216.34/", "/relative/path"})
    void 허용되지_않는_URL은_S003으로_거부한다(String url) {
        CustomException e = assertThrows(CustomException.class, () -> UrlGuard.assertSafe(URI.create(url)));
        assertEquals(ErrorCode.SOURCE_URL_BLOCKED, e.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://93.184.216.34/", "https://8.8.8.8/path?q=1", "https://[2606:4700:4700::1111]/"})
    void 공개_IP의_http_https는_통과한다(String url) {
        assertDoesNotThrow(() -> UrlGuard.assertSafe(URI.create(url)));
    }
}
