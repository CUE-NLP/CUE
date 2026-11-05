package com.cue.global.security;

/**
 * 인증된 사용자 정보. 컨트롤러에서 {@code @AuthenticationPrincipal UserPrincipal principal} 로 주입받는다.
 * JWT 클레임에서 복원하므로 요청마다 DB를 조회하지 않는다.
 */
public record UserPrincipal(Long id, String email, String nickname) {}
