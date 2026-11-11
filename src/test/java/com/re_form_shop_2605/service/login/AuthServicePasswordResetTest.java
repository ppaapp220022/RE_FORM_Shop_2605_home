package com.re_form_shop_2605.service.login;

import com.re_form_shop_2605.dto.login.LoginVerificationRequestDTO;
import com.re_form_shop_2605.dto.login.PasswordResetCodeRequestDTO;
import com.re_form_shop_2605.dto.login.PasswordResetRequestDTO;
import com.re_form_shop_2605.entity.Enum.MemberStatus;
import com.re_form_shop_2605.entity.Enum.Role;
import com.re_form_shop_2605.entity.member.Member;
import com.re_form_shop_2605.repository.member.MemberRepository;
import com.re_form_shop_2605.security.AuthException;
import com.re_form_shop_2605.security.JWT.CustomUserDetailsService;
import com.re_form_shop_2605.service.mail.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 비밀번호 재설정이 이메일 인증코드 없이는 일어나지 않는지, 정지 회원에게 토큰을 주지 않는지 확인한다.
 */
@SuppressWarnings("unchecked")
class AuthServicePasswordResetTest {

    private static final String EMAIL = "user@test.com";
    private static final String KEY = "auth:password-reset:" + EMAIL;
    private static final String ATTEMPTS_KEY = "auth:password-reset-attempts:" + EMAIL;

    private MemberRepository memberRepository;
    private PasswordEncoder passwordEncoder;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private MailService mailService;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        memberRepository = mock(MemberRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        mailService = mock(MailService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(mock(SetOperations.class));
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "encoded:" + inv.getArgument(0));

        authService = new AuthServiceImpl(
                null,
                null,
                memberRepository,
                passwordEncoder,
                null,
                redisTemplate,
                new CustomUserDetailsService(memberRepository),
                mock(AuthTokenService.class),
                mailService
        );
    }

    @Test
    void 가입하지_않은_이메일이면_코드를_보내지_않고_조용히_끝난다() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        authService.sendPasswordResetCode(new PasswordResetCodeRequestDTO(EMAIL));

        verify(mailService, never()).sendPasswordResetCodeEmail(anyString(), anyString());
        verify(valueOps, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    void 활성_회원이면_코드를_저장하고_메일로_보낸다() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(-2L);

        authService.sendPasswordResetCode(new PasswordResetCodeRequestDTO(EMAIL));

        // 메일은 응답과 분리해 비동기로 보낸다.
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendPasswordResetCodeEmail(eq(EMAIL), code.capture());
        assertThat(code.getValue()).matches("\\d{6}");
        verify(valueOps).set(KEY, code.getValue(), 300L, TimeUnit.SECONDS);
    }

    @Test
    void 메일_발송이_실패해도_요청은_성공으로_끝난다() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(-2L);
        doThrow(new IllegalStateException("smtp down")).when(mailService).sendPasswordResetCodeEmail(anyString(), anyString());

        assertThatCode(() -> authService.sendPasswordResetCode(new PasswordResetCodeRequestDTO(EMAIL)))
                .doesNotThrowAnyException();
    }

    @Test
    void 일분_안에_다시_요청하면_메일을_또_보내지_않는다() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(280L);

        authService.sendPasswordResetCode(new PasswordResetCodeRequestDTO(EMAIL));

        verify(mailService, after(300).never()).sendPasswordResetCodeEmail(anyString(), anyString());
    }

    @Test
    void 코드가_없으면_비밀번호를_바꾸지_않는다() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequestDTO(EMAIL, "123456", "newpassword")))
                .isInstanceOf(AuthException.class);
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void 코드를_틀리면_실패_횟수를_원자적으로_올린다() {
        when(valueOps.get(KEY)).thenReturn("123456");
        when(valueOps.increment(ATTEMPTS_KEY)).thenReturn(1L);

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequestDTO(EMAIL, "000000", "newpassword")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("일치하지");
        verify(redisTemplate).expire(ATTEMPTS_KEY, 3600L, TimeUnit.SECONDS);
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void 다섯_번째로_틀리면_코드를_폐기한다() {
        when(valueOps.get(KEY)).thenReturn("123456");
        when(valueOps.get(ATTEMPTS_KEY)).thenReturn(4);
        when(valueOps.increment(ATTEMPTS_KEY)).thenReturn(5L);

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequestDTO(EMAIL, "000000", "newpassword")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("1시간");
        verify(redisTemplate).delete(KEY);
    }

    @Test
    void 실패가_쌓인_이메일은_새_코드가_맞아도_막는다() {
        // 코드를 새로 받아도 실패 횟수는 초기화되지 않는다.
        when(valueOps.get(ATTEMPTS_KEY)).thenReturn(5);
        when(valueOps.get(KEY)).thenReturn("123456");

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequestDTO(EMAIL, "123456", "newpassword")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("1시간");
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void 코드가_맞으면_비밀번호를_바꾸고_코드를_지운다() {
        Member member = member(MemberStatus.ACTIVE);
        when(valueOps.get(KEY)).thenReturn("123456");
        when(redisTemplate.delete(KEY)).thenReturn(true);
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));

        authService.resetPassword(new PasswordResetRequestDTO(EMAIL, "123456", "newpassword"));

        assertThat(member.getPassword()).isEqualTo("encoded:newpassword");
        verify(redisTemplate).delete(ATTEMPTS_KEY);
    }

    @Test
    void 같은_코드로_동시에_두_번_바꾸면_한_번만_성공한다() {
        when(valueOps.get(KEY)).thenReturn("123456");
        when(redisTemplate.delete(KEY)).thenReturn(false); // 다른 요청이 먼저 지움

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequestDTO(EMAIL, "123456", "newpassword")))
                .isInstanceOf(AuthException.class);
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void 정지된_회원은_2차_인증을_통과해도_토큰을_받지_못한다() {
        Member member = member(MemberStatus.SUSPENDED);
        when(valueOps.get("auth:login-code:challenge")).thenReturn("1:123456");
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> authService.verifyLoginCode(new LoginVerificationRequestDTO("challenge", "123456")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("제한");
    }

    private Member member(MemberStatus status) {
        return Member.builder()
                .memberId(1L)
                .email(EMAIL)
                .password("old")
                .nickname("user")
                .mannerScore(BigDecimal.ZERO)
                .role(Role.USER)
                .status(status)
                .build();
    }
}
