package com.re_form_shop_2605.controller.login;

import com.re_form_shop_2605.dto.common.ApiResponse;
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
import com.re_form_shop_2605.dto.member.MemberResponseDTO;
import com.re_form_shop_2605.dto.member.NicknameResponseDTO;
import com.re_form_shop_2605.service.login.AuthService;
import com.re_form_shop_2605.service.member.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: 회원가입과 닉네임 중복 확인 같은 인증 진입 API
 * ─────────────────────────────────────────────────────
 */
@Validated
@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증 API", description = "로그인, 회원가입, 닉네임 중복 확인 등 인증 관련 API")
@Log4j2
public class AuthController {

    private final MemberService memberService;
    private final AuthService authService;

    // 인증 컨트롤러가 사용하는 회원/인증 서비스를 주입한다.
    public AuthController(MemberService memberService, AuthService authService) {
        this.memberService = memberService;
        this.authService = authService;
    }

    @Operation(
            summary = "이메일 로그인",
            description = "이메일과 비밀번호를 확인한 뒤 사용자 이메일로 6자리 2차 인증코드를 발송합니다."
    )
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginChallengeResponseDTO>> login(@Valid @RequestBody LoginRequestDTO request) {
        // 비밀번호 인증 후 이메일 2차 인증을 시작한다. JWT는 코드 검증 후 발급된다.
        log.info("[AuthController] login start email={}", request.email());
        LoginChallengeResponseDTO responseDTO = authService.login(request);
        log.info("[AuthController] login challenge sent email={} challengeId={}", request.email(), responseDTO.challengeId());
        return ResponseEntity.ok(ApiResponse.ok(responseDTO, "이메일 인증코드가 발송되었습니다."));
    }

    @Operation(
            summary = "이메일 로그인 2차 인증",
            description = "로그인 시 발급된 challengeId와 이메일 인증코드를 검증하고 JWT 액세스 토큰과 리프레시 토큰을 발급합니다."
    )
    @PostMapping("/login/verify")
    public ResponseEntity<ApiResponse<LoginResponseDTO>> verifyLoginCode(
            @Valid @RequestBody LoginVerificationRequestDTO request
    ) {
        // 인증코드가 맞을 때만 최종 로그인 토큰을 발급한다.
        log.info("[AuthController] verifyLoginCode start challengeId={}", request.challengeId());
        LoginResponseDTO responseDTO = authService.verifyLoginCode(request);
        log.info("[AuthController] verifyLoginCode end challengeId={} memberId={}",
                request.challengeId(),
                responseDTO.user().id());
        return ResponseEntity.ok(ApiResponse.ok(responseDTO, "로그인 완료"));
    }

    @Operation(
            summary = "회원가입",
            description = "이메일, 비밀번호, 닉네임과 약관 동의 여부를 받아 새 회원을 등록합니다."
    )
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<MemberResponseDTO>> register(@Valid @RequestBody RegisterRequest request) {
        // 필수 약관 동의를 확인한 뒤 회원가입과 초기 토큰 발급을 함께 처리한다.
        log.info("[AuthController] register start email={} nickname={}", request.email(), request.nickname());
        if (!request.agreeTerms() || !request.agreePrivacy()) {
            throw new IllegalArgumentException("필수 약관 동의가 필요합니다.");
        }

        MemberResponseDTO responseDTO = authService.register(
                request.email(),
                request.password(),
                request.nickname(),
                request.agreeMarketing()
        );

        log.info("[AuthController] register end email={} memberId={}", request.email(), responseDTO.user().id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(responseDTO, "회원가입이 완료되었습니다."));
    }

    @Operation(
            summary = "닉네임 중복 확인",
            description = "입력한 닉네임의 사용 가능 여부를 확인합니다."
    )
    @GetMapping("/check-nickname")
    public ResponseEntity<ApiResponse<NicknameResponseDTO>> checkNickname(
            @RequestParam @NotBlank @Size(min = 2, max = 20) String nickname
    ) {
        // 회원가입 전 닉네임 중복 여부를 조회해 프론트 검증에 사용한다.
        log.info("[AuthController] checkNickname start nickname={}", nickname);
        NicknameResponseDTO responseDTO = memberService.checkNickname(nickname);
        log.info("[AuthController] checkNickname end nickname={} available={}", nickname, responseDTO.available());
        return ResponseEntity.ok(ApiResponse.ok(responseDTO, "닉네임 확인 완료"));
    }

    @Operation(
            summary = "내 인증 사용자 정보 조회",
            description = "JWT 액세스 토큰 기준으로 현재 로그인한 사용자 정보를 조회합니다."
    )
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthUserDTO>> readMe(@AuthenticationPrincipal MemberSecurityDTO principal) {
        if (principal == null) {
            log.info("[AuthController] readMe guest");
            return ResponseEntity.ok(ApiResponse.ok(null, "비로그인 사용자입니다."));
        }

        // SecurityContext에 담긴 현재 사용자 기준으로 프로필 요약 정보를 반환한다.
        log.info("[AuthController] readMe start memberId={}", principal.getMemberId());
        AuthUserDTO responseDTO = authService.readMe(principal);
        log.info("[AuthController] readMe end memberId={}", principal.getMemberId());
        return ResponseEntity.ok(ApiResponse.ok(responseDTO, "내 정보 조회 완료"));
    }

    @Operation(
            summary = "내 로그인 세션 목록 조회",
            description = "현재 회원 계정에 연결된 refresh 세션 목록을 최신 발급 순으로 조회합니다."
    )
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<AuthSessionResponseDTO>>> readSessions(
            @AuthenticationPrincipal MemberSecurityDTO principal,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        // 서버가 관리 중인 세션 목록을 내려주고 현재 요청 세션도 함께 표시한다.
        log.info("[AuthController] readSessions start memberId={}", principal.getMemberId());
        List<AuthSessionResponseDTO> responseDTO = authService.readSessions(principal, authorization);
        log.info("[AuthController] readSessions end memberId={} count={}", principal.getMemberId(), responseDTO.size());
        return ResponseEntity.ok(ApiResponse.ok(responseDTO, "세션 목록 조회 완료"));
    }

    @Operation(
            summary = "로그아웃",
            description = "리프레시 토큰을 무효화하고 액세스 토큰을 블랙리스트에 등록하여 로그아웃 처리합니다."
    )
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal MemberSecurityDTO principal,
            @Valid @RequestBody LogoutRequestDTO requestDTO,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        // 요청한 refresh token을 서버 저장소에서 제거하고 access token을 블랙리스트에 등록한다.
        log.info("[AuthController] logout start memberId={}", principal.getMemberId());
        authService.logout(principal, requestDTO, authorization);
        log.info("[AuthController] logout end memberId={}", principal.getMemberId());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "특정 세션 로그아웃",
            description = "현재 회원 계정에 연결된 특정 세션 하나만 종료합니다."
    )
    @PostMapping("/logout/session")
    public ResponseEntity<Void> logoutSession(
            @AuthenticationPrincipal MemberSecurityDTO principal,
            @Valid @RequestBody LogoutSessionRequestDTO requestDTO
    ) {
        // 세션 ID 기준으로 특정 브라우저/기기 로그인만 종료한다.
        log.info("[AuthController] logoutSession start memberId={} sessionId={}",
                principal.getMemberId(),
                requestDTO.sessionId());
        authService.logoutSession(principal, requestDTO);
        log.info("[AuthController] logoutSession end memberId={} sessionId={}",
                principal.getMemberId(),
                requestDTO.sessionId());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "전체 세션 로그아웃",
            description = "현재 회원 계정에 연결된 모든 세션을 종료합니다."
    )
    @PostMapping("/logout/all")
    public ResponseEntity<Void> logoutAll(
            @AuthenticationPrincipal MemberSecurityDTO principal
    ) {
        // 비밀번호 변경/탈취 의심 대응처럼 전체 세션 무효화가 필요할 때 사용한다.
        log.info("[AuthController] logoutAll start memberId={}", principal.getMemberId());
        authService.logoutAll(principal);
        log.info("[AuthController] logoutAll end memberId={}", principal.getMemberId());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "비밀번호 재설정 인증코드 요청",
            description = "가입한 이메일로 6자리 인증코드를 보냅니다. 가입 여부와 관계없이 같은 응답을 돌려줍니다."
    )
    @PostMapping("/password/reset/code")
    public ResponseEntity<ApiResponse<Void>> sendPasswordResetCode(@Valid @RequestBody PasswordResetCodeRequestDTO requestDTO) {
        authService.sendPasswordResetCode(requestDTO);
        return ResponseEntity.ok(ApiResponse.ok(null, "가입된 이메일이라면 인증코드를 보냈습니다."));
    }

    @Operation(
            summary = "비밀번호 재설정",
            description = "이메일로 받은 인증코드를 확인한 뒤 새 비밀번호로 재설정합니다."
    )
    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody PasswordResetRequestDTO requestDTO) {
        // 인증코드가 맞으면 새 비밀번호를 암호화해 저장한다.
        log.info("[AuthController] resetPassword start email={}", requestDTO.email());
        authService.resetPassword(requestDTO);
        log.info("[AuthController] resetPassword end email={}", requestDTO.email());
        return ResponseEntity.ok(ApiResponse.ok(null, "비밀번호가 재설정되었습니다."));
    }

    @Operation(
            summary = "액세스 토큰 재발급",
            description = "리프레시 토큰을 검증한 뒤 새 액세스 토큰을 발급합니다."
    )
    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponseDTO>> refresh(@RequestBody TokenRefreshRequestDTO requestDTO) {
        // 유효한 refresh token으로 새 access token을 발급한다.
        log.info("[AuthController] refresh start");
        TokenRefreshResponseDTO responseDTO = authService.refresh(requestDTO);
        log.info("[AuthController] refresh end");
        return ResponseEntity.ok(ApiResponse.ok(responseDTO, "토큰 재발급 완료"));
    }

    @Operation(
            summary = "카카오 OAuth 진입",
            description = "스프링 시큐리티 OAuth2 카카오 로그인 진입 경로로 리다이렉트합니다."
    )
    @GetMapping("/oauth2/kakao")
    public ResponseEntity<Void> redirectKakao() {
        // 프론트가 호출할 수 있는 카카오 OAuth 시작 주소를 스프링 시큐리티 경로로 연결한다.
        log.info("[AuthController] redirectKakao start");
        log.info("[AuthController] redirectKakao end");
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/kakao"))
                .build();
    }

    @Operation(
            summary = "구글 OAuth 진입",
            description = "스프링 시큐리티 OAuth2 구글 로그인 진입 경로로 리다이렉트합니다."
    )
    @GetMapping("/oauth2/google")
    public ResponseEntity<Void> redirectGoogle() {
        // 프론트가 호출할 수 있는 구글 OAuth 시작 주소를 스프링 시큐리티 경로로 연결한다.
        log.info("[AuthController] redirectGoogle start");
        log.info("[AuthController] redirectGoogle end");
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/google"))
                .build();
    }

    // 프론트 회원가입 폼에서 전달하는 요청 본문
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 100) String email,
            @NotBlank @Size(min = 8) String password,
            @NotBlank @Size(min = 2, max = 20) String nickname,
            boolean agreeTerms,
            boolean agreePrivacy,
            boolean agreeMarketing
    ) {
    }
}
