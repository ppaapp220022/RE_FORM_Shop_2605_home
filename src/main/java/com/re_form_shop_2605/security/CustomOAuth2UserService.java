package com.re_form_shop_2605.security;

import com.re_form_shop_2605.dto.login.MemberSecurityDTO;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.Enum.Provider;
import com.re_form_shop_2605.entity.Enum.Role;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.entity.member.SocialMember;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.repository.member.socialMemberRepository;
import com.re_form_shop_2605.entity.payment.PointWallet;
import com.re_form_shop_2605.repository.payment.PointWalletRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: 카카오/구글 OAuth2 사용자 정보를 내부 회원 계정과 매핑하는 서비스
 * ─────────────────────────────────────────────────────
 */
@Service
@Log4j2
// 카카오/구글 OAuth2 사용자 정보를 내부 회원 계정과 매핑하는 서비스
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final socialMemberRepository socialMemberRepository;
    private final PointWalletRepository pointWalletRepository; // 포인트 지갑 저장소

    // OAuth2 로그인 시 회원/소셜 계정을 연결하는 데 필요한 저장소와 인코더를 주입한다.
    public CustomOAuth2UserService(
            PasswordEncoder passwordEncoder,
            MemberRepository memberRepository,
            socialMemberRepository socialMemberRepository,
            PointWalletRepository pointWalletRepository
    ) {
        this.passwordEncoder = passwordEncoder;
        this.memberRepository = memberRepository;
        this.socialMemberRepository = socialMemberRepository;
        this.pointWalletRepository = pointWalletRepository;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        // 제공자별 응답 구조에서 이메일과 providerId를 추출해 내부 회원 계정과 연결한다.
        ClientRegistration clientRegistration = userRequest.getClientRegistration();
        String clientName = clientRegistration.getClientName().toLowerCase();

        OAuth2User oAuth2User = super.loadUser(userRequest);
        Map<String, Object> paramMap = oAuth2User.getAttributes();

        // 제공자별 응답 포맷이 다르므로 이메일 추출 로직을 분기한다.
        String email = switch (clientName) {
            case "kakao" -> getKakaoEmail(paramMap);
            case "google" -> getGoogleEmail(paramMap);
            default -> throw new OAuth2AuthenticationException("지원하지 않는 SNS로그인 제공자입니다.");
        };

        // 내부 SocialMember 매핑에 사용할 provider 측 고유 식별값을 추출한다.
        String providerId = switch (clientName) {
            case "kakao" -> String.valueOf(paramMap.get("id"));
            case "google" -> String.valueOf(paramMap.get("sub"));
            default -> throw new OAuth2AuthenticationException("지원하지 않는 SNS로그인 제공자입니다.");
        };

        Provider provider = Provider.valueOf(clientName.toUpperCase());
        return generateDTO(email, provider, providerId, paramMap);
    }

    private String getKakaoEmail(Map<String, Object> paramMap) {
        // 카카오는 kakao_account 하위에 이메일 정보가 들어있다.
        LinkedHashMap<?, ?> accountMap = (LinkedHashMap<?, ?>) paramMap.get("kakao_account");
        return (String) accountMap.get("email");
    }

    private String getGoogleEmail(Map<String, Object> paramMap) {
        // 구글은 최상위 속성에 이메일이 바로 포함된다.
        return (String) paramMap.get("email");
    }

    private MemberSecurityDTO generateDTO(
            String email,
            Provider provider,
            String providerId,
            Map<String, Object> paramMap
    ) {
        // 처음 로그인한 소셜 사용자는 회원/소셜 매핑을 생성하고, 기존 사용자는 그대로 재사용한다.
        Optional<Member> result = memberRepository.findByEmail(email);
        Member member;

        if (result.isEmpty()) {
            // 최초 소셜 로그인 사용자는 일반 Member와 SocialMember 연결을 함께 생성한다.
            String nickname = createUniqueNickname(email, provider);

            member = Member.builder()
                    .email(email)
                    .nickname(nickname)
                    .mannerScore(BigDecimal.ZERO)
                    .role(Role.USER)
                    .status(MemberStatus.ACTIVE)
                    .warningCount(0)
                    .emailEvent(false)
                    .build();
            // 소셜 회원은 이메일 로그인을 쓰지 않으므로 아무도 모르는 임의 비밀번호를 넣는다.
            member.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            memberRepository.save(member);

            // 포인트 지갑 자동 생성 (소셜 신규 가입자 대상)
            pointWalletRepository.save(PointWallet.builder().member(member).build());

            socialMemberRepository.save(
                    SocialMember.builder()
                            .member(member)
                            .provider(provider)
                            .providerId(providerId)
                            .build()
            );
        } else {
            // 기존 회원이면 소셜 로그인 principal만 새로 조립해 반환한다.
            member = result.get();
            if (member.getStatus() != MemberStatus.ACTIVE) {
                // 정지·탈퇴 회원은 소셜 로그인도 막는다. 실패 핸들러가 프론트 로그인 화면으로 돌려보낸다.
                throw new OAuth2AuthenticationException(new OAuth2Error("account_restricted"), "이용이 제한된 계정입니다.");
            }
        }

        // 이후 JWT 발급 시 local / kakao / google을 구분할 수 있도록 provider 정보를 principal에 담는다.
        MemberSecurityDTO memberSecurityDTO = new MemberSecurityDTO(
                member.getMemberId(),
                member.getEmail(),
                member.getPassword(),
                member.getEmail(),
                member.getNickname(),
                member.getProfileImageUrl(),
                true,
                provider.name().toLowerCase(),
                List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()))
        );
        memberSecurityDTO.setProps(paramMap);
        return memberSecurityDTO;
    }

    private String createUniqueNickname(String email, Provider provider) {
        // 이메일 앞부분을 기본 닉네임으로 쓰되, 충돌 시 provider와 숫자 suffix를 붙여 유일성을 맞춘다.
        String baseNickname = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;

        if (!memberRepository.existsByNickname(baseNickname)) {
            return baseNickname;
        }

        String prefixed = baseNickname + "_" + provider.name().toLowerCase();
        if (!memberRepository.existsByNickname(prefixed)) {
            return prefixed;
        }

        int suffix = 1;
        while (memberRepository.existsByNickname(prefixed + suffix)) {
            suffix++;
        }
        return prefixed + suffix;
    }
}
