package com.re_form_shop_2605.security.JWT;

import com.re_form_shop_2605.dto.login.MemberSecurityDTO;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.Enum.Role;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.service.login.AuthTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JWT 필터가 토큰 claim이 아니라 DB의 현재 상태·권한으로 인증을 세우는지 확인한다.
 */
class JwtAuthenticationFilterTest {

    private static final String EMAIL = "user@test.com";

    private final JwtTokenProvider tokenProvider =
            new JwtTokenProvider("test-secret-test-secret-test-secret-test-secret", 600, 3600);
    private MemberRepository memberRepository;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        filter = new JwtAuthenticationFilter(
                tokenProvider,
                new CustomUserDetailsService(memberRepository),
                mock(AuthTokenService.class)
        );
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 정지된_회원의_토큰은_인증되지_않는다() throws Exception {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.SUSPENDED, Role.USER)));
        MockHttpServletRequest request = requestWith(tokenFor(Role.USER));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute("exception")).isEqualTo("ACCOUNT_RESTRICTED");
    }

    @Test
    void 탈퇴한_회원의_토큰은_인증되지_않는다() throws Exception {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.WITHDRAWN, Role.USER)));

        filter.doFilter(requestWith(tokenFor(Role.USER)), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 권한은_토큰이_아니라_DB_기준이다() throws Exception {
        // 관리자 토큰을 받은 뒤 일반 회원으로 강등된 경우
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.ACTIVE, Role.USER)));

        filter.doFilter(requestWith(tokenFor(Role.ADMIN)), new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    private String tokenFor(Role role) {
        MemberSecurityDTO principal = new MemberSecurityDTO(
                1L, EMAIL, "pw", EMAIL, "user", null, false, "local",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
        return tokenProvider.generateAccessToken(principal, "session");
    }

    private MockHttpServletRequest requestWith(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private Member member(MemberStatus status, Role role) {
        return Member.builder()
                .memberId(1L)
                .email(EMAIL)
                .password("pw")
                .nickname("user")
                .mannerScore(BigDecimal.ZERO)
                .role(role)
                .status(status)
                .build();
    }
}
