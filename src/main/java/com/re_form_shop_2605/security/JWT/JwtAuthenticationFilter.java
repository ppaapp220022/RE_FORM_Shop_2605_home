package com.re_form_shop_2605.security.JWT;

import com.re_form_shop_2605.dto.login.MemberSecurityDTO;
import com.re_form_shop_2605.security.AuthException;
import com.re_form_shop_2605.service.login.AuthTokenService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: access token을 검증해 SecurityContext에 인증 정보를 설정하는 JWT 필터
 * ─────────────────────────────────────────────────────
 */

// 모든 API 요청마다 Bearer access token을 검사해 SecurityContext를 복원하는 필터
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final AuthTokenService authTokenService;

    // JWT 파싱기와 사용자 재조회 서비스를 받아 요청마다 인증을 복원한다.
    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            CustomUserDetailsService customUserDetailsService,
            AuthTokenService authTokenService
    ) {
        // 필터는 토큰 파싱과 사용자 재조회 책임만 가져가도록 의존성을 최소화한다.
        this.jwtTokenProvider = jwtTokenProvider;
        this.customUserDetailsService = customUserDetailsService;
        this.authTokenService = authTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Authorization 헤더가 없으면 JWT 인증 없이 다음 필터로 넘긴다.
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // "Bearer " 접두사를 떼고 순수 JWT 문자열만 추출한다.
        String token = authorization.substring(7);
        try {
            // [Blacklist Check] 로그아웃된 토큰인지 먼저 확인한다.
            if (authTokenService.isAccessTokenBlacklisted(token)) {
                throw new AuthException("이미 로그아웃된 토큰입니다.");
            }

            if (jwtTokenProvider.validateToken(token) && "access".equals(jwtTokenProvider.getTokenType(token))) {
                // 권한과 계정 상태는 토큰 claim이 아니라 DB 기준으로 판단한다.
                // 그래야 정지·탈퇴·권한 변경이 토큰 만료를 기다리지 않고 바로 반영된다.
                String email = jwtTokenProvider.getSubject(token);
                UserDetails userDetails = customUserDetailsService.loadUserByUsername(email);
                if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
                    // 인증을 세우지 않고 넘기면 보호된 경로에서 진입점이 403으로 응답한다.
                    request.setAttribute("exception", "ACCOUNT_RESTRICTED");
                } else {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (ExpiredJwtException ex) {
            request.setAttribute("exception", "EXPIRED_TOKEN");
            SecurityContextHolder.clearContext();
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException ex) {
            request.setAttribute("exception", "INVALID_TOKEN");
            SecurityContextHolder.clearContext();
        } catch (Exception ex) {
            request.setAttribute("exception", "AUTH_ERROR");
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
