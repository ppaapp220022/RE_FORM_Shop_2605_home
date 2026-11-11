package com.re_form_shop_2605.dto.login;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;

/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: Spring Security의 UserDetails와 OAuth2User를 구현한 DTO, 로그인한 회원의 정보를 담는 클래스
 * ─────────────────────────────────────────────────────
 */
public class MemberSecurityDTO extends User implements OAuth2User {

    private final Long memberId;
    private final String email;
    private final String nickname;
    private final String profileImageUrl;
    private final boolean social;
    private final String loginProvider;
    private Map<String, Object> props;

    public MemberSecurityDTO(
            Long memberId,
            String username,
            String password,
            String email,
            String nickname,
            String profileImageUrl,
            boolean social,
            String loginProvider,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this(memberId, username, password, email, nickname, profileImageUrl, social, loginProvider, true, true, authorities);
    }

    // enabled=false(탈퇴), accountNonLocked=false(정지)면 Spring Security가 로그인과 인증을 거부한다.
    public MemberSecurityDTO(
            Long memberId,
            String username,
            String password,
            String email,
            String nickname,
            String profileImageUrl,
            boolean social,
            String loginProvider,
            boolean enabled,
            boolean accountNonLocked,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(username, password, enabled, true, true, accountNonLocked, authorities);
        this.memberId = memberId;
        this.email = email;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.social = social;
        this.loginProvider = loginProvider;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public boolean isSocial() {
        return social;
    }

    public String getLoginProvider() {
        return loginProvider;
    }

    public void setProps(Map<String, Object> props) {
        this.props = props;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return props;
    }

    @Override
    public String getName() {
        return String.valueOf(memberId);
    }
}
