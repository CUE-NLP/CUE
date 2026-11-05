package com.cue.global.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** 검증된 JWT를 UserPrincipal을 principal로 갖는 Authentication으로 변환한다. */
@Component
public class JwtPrincipalConverter implements Converter<Jwt, UsernamePasswordAuthenticationToken> {
    @Override
    public UsernamePasswordAuthenticationToken convert(Jwt jwt) {
        UserPrincipal principal = new UserPrincipal(
                Long.valueOf(jwt.getSubject()),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("nickname"));
        return new UsernamePasswordAuthenticationToken(
                principal, jwt.getTokenValue(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
