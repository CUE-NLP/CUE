package com.cue.source.fetch;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 웹 원문을 가져오기 전 URL 검사 (SSRF 방어). http/https만, 사용자정보(user:pass@) 없음,
 * 호스트가 가리키는 모든 IP가 공개 주소여야 한다 (루프백·사설·링크로컬·멀티캐스트·미지정·CGNAT·IPv6 ULA 차단).
 * 리다이렉트로 이동한 URL도 매번 다시 검사한다.
 * 한계: 검사 시점과 실제 연결 시점의 DNS 결과가 달라지는 DNS rebinding은 완전히 막지 못한다.
 */
public final class UrlGuard {
    private UrlGuard() {}

    public static void assertSafe(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new CustomException(ErrorCode.SOURCE_URL_BLOCKED);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new CustomException(ErrorCode.SOURCE_URL_BLOCKED);
        }
        for (InetAddress a : addresses) {
            if (isInternal(a)) {
                throw new CustomException(ErrorCode.SOURCE_URL_BLOCKED);
            }
        }
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127); // 0.0.0.0/8, CGNAT 100.64.0.0/10
        }
        return (b[0] & 0xfe) == 0xfc; // IPv6 unique local fc00::/7
    }
}
