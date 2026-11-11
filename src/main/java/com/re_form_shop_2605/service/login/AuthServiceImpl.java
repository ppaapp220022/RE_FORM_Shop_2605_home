package com.re_form_shop_2605.service.login;

import com.re_form_shop_2605.dto.login.AuthSessionResponseDTO;
import com.re_form_shop_2605.dto.login.AuthUserDTO;
import com.re_form_shop_2605.dto.login.LoginChallengeResponseDTO;
import com.re_form_shop_2605.dto.login.LoginRequestDTO;
import com.re_form_shop_2605.dto.login.LoginResponseDTO;
import com.re_form_shop_2605.dto.login.LoginVerificationRequestDTO;
import com.re_form_shop_2605.dto.login.LogoutRequestDTO;
import com.re_form_shop_2605.dto.login.LogoutSessionRequestDTO;
import com.re_form_shop_2605.dto.login.MemberSecurityDTO;
import com.re_form_shop_2605.dto.login.PasswordResetCodeRequestDTO;
import com.re_form_shop_2605.dto.login.PasswordResetRequestDTO;
import com.re_form_shop_2605.dto.login.TokenRefreshRequestDTO;
import com.re_form_shop_2605.dto.login.TokenRefreshResponseDTO;
import com.re_form_shop_2605.dto.member.MemberRequestDTO;
import com.re_form_shop_2605.dto.member.MemberResponseDTO;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.security.AuthException;
import com.re_form_shop_2605.security.JWT.CustomUserDetailsService;
import com.re_form_shop_2605.security.JWT.JwtTokenProvider;
import com.re_form_shop_2605.service.mail.MailService;
import com.re_form_shop_2605.service.member.MemberService;
import lombok.extern.log4j.Log4j2;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: 회원 인증 및 인가 서비스 구현체
 * ─────────────────────────────────────────────────────
 */
@Service
@RequiredArgsConstructor
@Transactional
@Log4j2
public class AuthServiceImpl implements AuthService {

    private static final long LOGIN_CODE_EXPIRATION_SECONDS = 300L;
    private static final long PASSWORD_RESET_CODE_EXPIRATION_SECONDS = 300L;
    private static final long PASSWORD_RESET_RESEND_INTERVAL_SECONDS = 60L;
    private static final int PASSWORD_RESET_MAX_ATTEMPTS = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthenticationManager authenticationManager;
    private final MemberService memberService;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CustomUserDetailsService customUserDetailsService;
    private final AuthTokenService authTokenService;
    private final MailService mailService;

    @Override
    public LoginChallengeResponseDTO login(LoginRequestDTO requestDTO) {
        // 비밀번호 인증을 통과한 사용자에게 2차 인증코드를 발급한다.
        log.info("[AuthServiceImpl] login start email={}", requestDTO.email());
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(requestDTO.email(), requestDTO.password())
        );
        MemberSecurityDTO principal = (MemberSecurityDTO) authentication.getPrincipal();
        LoginChallengeResponseDTO responseDTO = startLoginChallenge(principal);

        log.info("[AuthServiceImpl] login challenge created email={} memberId={} challengeId={}",
                requestDTO.email(),
                principal.getMemberId(),
                responseDTO.challengeId());
        return responseDTO;
    }

    @Override
    public LoginChallengeResponseDTO startLoginChallenge(MemberSecurityDTO principal) {
        String challengeId = UUID.randomUUID().toString();
        String code = generateLoginCode();
        redisTemplate.opsForValue().set(
                buildLoginCodeKey(challengeId),
                principal.getMemberId() + ":" + code,
                LOGIN_CODE_EXPIRATION_SECONDS,
                TimeUnit.SECONDS
        );
        mailService.sendLoginCodeEmail(principal.getEmail(), code);

        log.info("[AuthServiceImpl] login challenge sent email={} memberId={} challengeId={}",
                principal.getEmail(),
                principal.getMemberId(),
                challengeId);
        // verificationCode: 개발 편의용 — 프론트 로그인 화면 DEV 힌트 박스에 코드 직접 표시
        return new LoginChallengeResponseDTO(challengeId, principal.getEmail(), LOGIN_CODE_EXPIRATION_SECONDS, code);
    }

    // 2차 로그인 인증
    @Override
    public LoginResponseDTO verifyLoginCode(LoginVerificationRequestDTO requestDTO) {
        log.info("[AuthServiceImpl] verifyLoginCode start challengeId={}", requestDTO.challengeId());
        String redisKey = buildLoginCodeKey(requestDTO.challengeId());
        Object savedChallenge = redisTemplate.opsForValue().get(redisKey);

        if (savedChallenge == null) {
            throw new AuthException("인증코드가 만료되었거나 존재하지 않습니다.");
        }

        String[] challengeParts = savedChallenge.toString().split(":", 2);
        if (challengeParts.length != 2 || !requestDTO.code().equals(challengeParts[1])) {
            throw new AuthException("인증코드가 일치하지 않습니다.");
        }

        redisTemplate.delete(redisKey);

        Long memberId = Long.valueOf(challengeParts[0]);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));
        MemberSecurityDTO principal = (MemberSecurityDTO) customUserDetailsService.loadUserByUsername(member.getEmail());
        assertAccountUsable(principal);
        LoginResponseDTO responseDTO = authTokenService.issueTokens(principal);
        log.info("[AuthServiceImpl] verifyLoginCode end memberId={} challengeId={}", memberId, requestDTO.challengeId());
        return responseDTO;
    }

    @Override
    public MemberResponseDTO register(String email, String password, String nickname, boolean marketingAgreed) {
        // 회원가입 직후 다시 사용자 정보를 로드해 JWT를 발급
        log.info("[AuthServiceImpl] register start email={} nickname={}", email, nickname);
        MemberResponseDTO joined = memberService.join(new MemberRequestDTO(email, nickname, password, marketingAgreed));
        MemberSecurityDTO principal = (MemberSecurityDTO) customUserDetailsService.loadUserByUsername(email);
        LoginResponseDTO loginResponseDTO = authTokenService.issueTokens(principal);
        log.info("[AuthServiceImpl] register end email={} memberId={}", email, principal.getMemberId());
        return new MemberResponseDTO(loginResponseDTO.accessToken(), loginResponseDTO.refreshToken(), joined.user());
    }

    @Override
    @Transactional(readOnly = true)
    public AuthUserDTO readMe(MemberSecurityDTO principal) {
        // access token으로 인증된 사용자의 최신 회원 정보를 응답 DTO로 변환한다.
        return toAuthUserDTO(principal);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuthSessionResponseDTO> readSessions(MemberSecurityDTO principal, String accessToken) {
        // 현재 사용자에게 연결된 refresh 세션 목록을 Redis 기준으로 조회한다.
        log.info("[AuthServiceImpl] readSessions start memberId={}", principal.getMemberId());
        String currentSessionId = extractBearerToken(accessToken) != null
                ? jwtTokenProvider.getSessionId(extractBearerToken(accessToken))
                : null;

        Set<Object> sessionIds = redisTemplate.opsForSet().members(buildSessionSetKey(principal.getMemberId()));
        List<AuthSessionResponseDTO> sessions = new ArrayList<>();
        if (sessionIds == null) {
            return sessions;
        }

        for (Object sessionValue : sessionIds) {
            String sessionId = String.valueOf(sessionValue);
            Object savedToken = redisTemplate.opsForValue().get(buildRefreshTokenKey(principal.getMemberId(), sessionId));
            if (savedToken == null) {
                // 세션 집합에는 남아 있지만 실제 토큰이 없으면 정리하고 응답에서는 제외한다.
                redisTemplate.opsForSet().remove(buildSessionSetKey(principal.getMemberId()), sessionId);
                continue;
            }

            String refreshToken = savedToken.toString();
            sessions.add(new AuthSessionResponseDTO(
                    sessionId,
                    jwtTokenProvider.getLoginType(refreshToken),
                    jwtTokenProvider.getLoginProvider(refreshToken),
                    jwtTokenProvider.getClaims(refreshToken).getIssuedAt().toInstant(),
                    jwtTokenProvider.getClaims(refreshToken).getExpiration().toInstant(),
                    sessionId.equals(currentSessionId)
            ));
        }

        sessions.sort(Comparator.comparing(AuthSessionResponseDTO::issuedAt).reversed());
        log.info("[AuthServiceImpl] readSessions end memberId={} count={}", principal.getMemberId(), sessions.size());
        return sessions;
    }

    @Override
    public void logout(MemberSecurityDTO principal, LogoutRequestDTO requestDTO, String accessToken) {
        // 현재 사용자에게 저장된 refresh token만 삭제해 로그아웃을 보장
        log.info("[AuthServiceImpl] logout start memberId={}", principal.getMemberId());
        String sessionId = jwtTokenProvider.getSessionId(requestDTO.refreshToken());
        Long tokenMemberId = jwtTokenProvider.getMemberId(requestDTO.refreshToken());

        if (!principal.getMemberId().equals(tokenMemberId)) {
            throw new AuthException("현재 로그인 사용자와 토큰 소유자가 일치하지 않습니다.");
        }

        String redisKey = buildRefreshTokenKey(principal.getMemberId(), sessionId);
        Object savedToken = redisTemplate.opsForValue().get(redisKey);

        if (savedToken == null || !requestDTO.refreshToken().equals(savedToken.toString())) {
            throw new AuthException("유효한 리프레시 토큰이 아닙니다.");
        }

        // 액세스 토큰을 블랙리스트에 등록하여 즉시 무효화한다.
        String pureAccessToken = extractBearerToken(accessToken);
        if (pureAccessToken != null) {
            authTokenService.blacklistAccessToken(pureAccessToken);
        }

        removeSession(principal.getMemberId(), sessionId);
        log.info("[AuthServiceImpl] logout end memberId={} sessionId={}", principal.getMemberId(), sessionId);
    }

    @Override
    public void logoutSession(MemberSecurityDTO principal, LogoutSessionRequestDTO requestDTO) {
        // 현재 사용자 소유의 특정 세션 하나만 종료한다.
        log.info("[AuthServiceImpl] logoutSession start memberId={} sessionId={}",
                principal.getMemberId(),
                requestDTO.sessionId());
        Object savedToken = redisTemplate.opsForValue().get(buildRefreshTokenKey(principal.getMemberId(), requestDTO.sessionId()));
        if (savedToken == null) {
            throw new AuthException("해당 세션이 존재하지 않습니다.");
        }

        removeSession(principal.getMemberId(), requestDTO.sessionId());
        log.info("[AuthServiceImpl] logoutSession end memberId={} sessionId={}",
                principal.getMemberId(),
                requestDTO.sessionId());
    }

    @Override
    public void logoutAll(MemberSecurityDTO principal) {
        // 현재 사용자에게 연결된 모든 세션을 한 번에 종료한다.
        log.info("[AuthServiceImpl] logoutAll start memberId={}", principal.getMemberId());
        Set<Object> sessionIds = redisTemplate.opsForSet().members(buildSessionSetKey(principal.getMemberId()));
        if (sessionIds == null) {
            return;
        }

        for (Object sessionValue : sessionIds) {
            removeSession(principal.getMemberId(), String.valueOf(sessionValue));
        }
        log.info("[AuthServiceImpl] logoutAll end memberId={} count={}", principal.getMemberId(), sessionIds.size());
    }

    @Override
    public void sendPasswordResetCode(PasswordResetCodeRequestDTO requestDTO) {
        // 가입 여부를 응답으로 드러내지 않도록, 없는 이메일이어도 같은 응답으로 끝낸다.
        String email = requestDTO.email();
        Member member = memberRepository.findByEmail(email).orElse(null);
        if (member == null || member.getStatus() != MemberStatus.ACTIVE) {
            log.info("[AuthServiceImpl] sendPasswordResetCode skipped email={}", email);
            return;
        }

        // 1분 안에 다시 요청하면 메일을 새로 보내지 않는다(메일 폭탄 방지).
        String redisKey = buildPasswordResetKey(email);
        Long remainingSeconds = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        if (remainingSeconds != null && remainingSeconds > PASSWORD_RESET_CODE_EXPIRATION_SECONDS - PASSWORD_RESET_RESEND_INTERVAL_SECONDS) {
            log.info("[AuthServiceImpl] sendPasswordResetCode throttled email={}", email);
            return;
        }

        String code = generateLoginCode();
        redisTemplate.opsForValue().set(redisKey, "0:" + code, PASSWORD_RESET_CODE_EXPIRATION_SECONDS, TimeUnit.SECONDS);
        mailService.sendPasswordResetCodeEmail(email, code);
        log.info("[AuthServiceImpl] sendPasswordResetCode sent email={} memberId={}", email, member.getMemberId());
    }

    @Override
    public void resetPassword(PasswordResetRequestDTO requestDTO) {
        // 이메일로 받은 인증코드가 맞을 때만 새 비밀번호를 암호화해 갱신한다.
        log.info("[AuthServiceImpl] resetPassword start email={}", requestDTO.email());
        String redisKey = buildPasswordResetKey(requestDTO.email());
        Object saved = redisTemplate.opsForValue().get(redisKey);
        if (saved == null) {
            throw new AuthException("인증코드가 만료되었거나 존재하지 않습니다.");
        }

        // 저장 형식은 "실패횟수:코드". 6자리 코드를 무차별 대입하지 못하도록 실패 횟수를 센다.
        String[] parts = saved.toString().split(":", 2);
        int failedAttempts = Integer.parseInt(parts[0]);
        if (!requestDTO.code().equals(parts[1])) {
            failedAttempts++;
            if (failedAttempts >= PASSWORD_RESET_MAX_ATTEMPTS) {
                redisTemplate.delete(redisKey);
                throw new AuthException("인증코드를 여러 번 틀려 무효가 되었습니다. 다시 요청해 주세요.");
            }
            Long remainingSeconds = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            if (remainingSeconds != null && remainingSeconds > 0) {
                redisTemplate.opsForValue().set(redisKey, failedAttempts + ":" + parts[1], remainingSeconds, TimeUnit.SECONDS);
            }
            throw new AuthException("인증코드가 일치하지 않습니다.");
        }

        redisTemplate.delete(redisKey);
        Member member = memberRepository.findByEmail(requestDTO.email())
                .orElseThrow(() -> new AuthException("인증코드가 만료되었거나 존재하지 않습니다."));
        member.setPassword(passwordEncoder.encode(requestDTO.newPassword()));
        clearAllSessions(member.getMemberId());
        log.info("[AuthServiceImpl] resetPassword end email={} memberId={}", requestDTO.email(), member.getMemberId());
    }

    @Override
    public TokenRefreshResponseDTO refresh(TokenRefreshRequestDTO requestDTO) {
        String refreshToken = requestDTO.refreshToken();
        log.info("[AuthServiceImpl] refresh start");

        try {
            // 형식상 유효하고 refresh 타입인지 먼저 확인
            if (!jwtTokenProvider.validateToken(refreshToken) || !"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
                throw new AuthException("유효한 리프레시 토큰이 아닙니다.");
            }
        } catch (Exception ex) {
            throw new AuthException("유효한 리프레시 토큰이 아닙니다.");
        }

        Long memberId = jwtTokenProvider.getMemberId(refreshToken);
        String sessionId = jwtTokenProvider.getSessionId(refreshToken);
        Object savedToken = redisTemplate.opsForValue().get(buildRefreshTokenKey(memberId, sessionId));

        // Redis에 저장한 최신 refresh token과 일치할 때만 access token을 재발급
        if (savedToken == null || !refreshToken.equals(savedToken.toString())) {
            throw new AuthException("유효한 리프레시 토큰이 아닙니다.");
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));
        MemberSecurityDTO loadedPrincipal = (MemberSecurityDTO) customUserDetailsService.loadUserByUsername(member.getEmail());
        assertAccountUsable(loadedPrincipal);
        MemberSecurityDTO principal = new MemberSecurityDTO(
                loadedPrincipal.getMemberId(),
                loadedPrincipal.getUsername(),
                loadedPrincipal.getPassword(),
                loadedPrincipal.getEmail(),
                loadedPrincipal.getNickname(),
                loadedPrincipal.getProfileImageUrl(),
                !"local".equalsIgnoreCase(jwtTokenProvider.getLoginProvider(refreshToken)),
                jwtTokenProvider.getLoginProvider(refreshToken),
                loadedPrincipal.getAuthorities()
        );
        // [Refresh Token Rotation] 기존 리프레시 토큰을 삭제하고 새로 한 쌍을 발급한다.
        removeSession(memberId, sessionId);
        LoginResponseDTO newTokens = authTokenService.issueTokens(principal, sessionId);

        log.info("[AuthServiceImpl] refresh end memberId={} sessionId={} (Rotated)", memberId, sessionId);

        return new TokenRefreshResponseDTO(newTokens.accessToken(), newTokens.refreshToken());
    }

    private AuthUserDTO toAuthUserDTO(MemberSecurityDTO principal) {
        // principal의 식별값으로 DB를 다시 조회해 최신 프로필 정보를 내려준다.
        Member member = memberRepository.findById(principal.getMemberId())
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));

        return new AuthUserDTO(
                member.getMemberId(),
                member.getEmail(),
                member.getNickname(),
                member.getProfileImageUrl(),
                member.getRole(),
                member.getMannerScore()
        );
    }
    private String buildRefreshTokenKey(Long memberId, String sessionId) {
        // 사용자와 세션별로 refresh token을 구분해 멀티 디바이스 로그인도 수용한다.
        return "auth:refresh:" + memberId + ":" + sessionId;
    }

    private String buildSessionSetKey(Long memberId) {
        // 회원별 세션 ID 집합을 따로 유지해 전체 로그아웃과 세션 목록 조회를 빠르게 처리한다.
        return "auth:sessions:" + memberId;
    }

    private String buildPasswordResetKey(String email) {
        return "auth:password-reset:" + email.toLowerCase();
    }

    private void assertAccountUsable(MemberSecurityDTO principal) {
        // 정지·탈퇴 회원에게는 새 토큰을 발급하지 않는다.
        if (!principal.isEnabled() || !principal.isAccountNonLocked()) {
            throw new AuthException("이용이 제한된 계정입니다.");
        }
    }

    private String buildLoginCodeKey(String challengeId) {
        return "auth:login-code:" + challengeId;
    }

    private String generateLoginCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private void removeSession(Long memberId, String sessionId) {
        // 세션별 refresh token 키와 세션 인덱스 집합을 함께 지워 서버 기준 로그아웃을 완결한다.
        redisTemplate.delete(buildRefreshTokenKey(memberId, sessionId));
        redisTemplate.opsForSet().remove(buildSessionSetKey(memberId), sessionId);
    }

    private void clearAllSessions(Long memberId) {
        // 비밀번호 재설정 같은 보안 민감 이벤트에서는 모든 세션을 끊어 재로그인을 강제한다.
        Set<Object> sessionIds = redisTemplate.opsForSet().members(buildSessionSetKey(memberId));
        if (sessionIds == null) {
            return;
        }
        for (Object sessionValue : sessionIds) {
            removeSession(memberId, String.valueOf(sessionValue));
        }
    }

    private String extractBearerToken(String authorizationHeader) {
        // Authorization 헤더가 없거나 형식이 다르면 현재 세션 판별은 생략한다.
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring(7);
    }
}
