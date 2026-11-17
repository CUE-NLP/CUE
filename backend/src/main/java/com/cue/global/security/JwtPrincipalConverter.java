package com.cue.global.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/** 검증된 JWT를 UserPrincipal을 principal로 갖는 Authentication으로 변환한다. */
@Component
public class JwtPrincipalConverter implements Converter<Jwt, UsernamePasswordAuthenticationToken> {
    @Override
    public UsernamePasswordAuthenticationToken convert(Jwt jwt) {
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            // 서명은 맞아도 sub가 사용자 ID가 아니면 무효 토큰(A005)으로 처리한다 (500으로 새지 않게).
            throw new InvalidBearerTokenException("Invalid JWT subject");
        }
        UserPrincipal principal = new UserPrincipal(
                userId,
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("nickname"));
        return new UsernamePasswordAuthenticationToken(
                principal, jwt.getTokenValue(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
