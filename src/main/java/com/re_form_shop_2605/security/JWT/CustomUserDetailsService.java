package com.re_form_shop_2605.security.JWT;

import com.re_form_shop_2605.dto.login.MemberSecurityDTO;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.repository.member.MemberRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: 이메일 기반으로 회원 인증 정보를 조회하는 UserDetailsService
 * ─────────────────────────────────────────────────────
 */
@Log4j2
@Service
// 이메일/비밀번호 로그인과 JWT 재인증에서 공통으로 사용할 사용자 조회 서비스
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    // 이메일 로그인과 JWT 재인증에 사용할 회원 조회 저장소를 주입한다.
    public CustomUserDetailsService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 이메일을 username으로 사용하므로 DB에서 회원을 찾은 뒤 JWT/로그인 공통 principal로 변환한다.
        log.info("loadUserByUsername: {}", username);

        Member member = memberRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("회원 정보가 없음"));

        // 일반 로그인 principal은 provider를 local로 고정해 JWT claim과 세션 메타데이터가 일관되게 한다.
        return new MemberSecurityDTO(
                member.getMemberId(),
                member.getEmail(),
                member.getPassword(),
                member.getEmail(),
                member.getNickname(),
                member.getProfileImageUrl(),
                false,
                "local",
                member.getStatus() != MemberStatus.WITHDRAWN,
                member.getStatus() != MemberStatus.SUSPENDED,
                List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()))
        );
    }
}
