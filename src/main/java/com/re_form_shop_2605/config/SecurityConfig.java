package com.re_form_shop_2605.config;

import com.re_form_shop_2605.security.CustomOAuth2UserService;
import com.re_form_shop_2605.security.JWT.CustomUserDetailsService;
import com.re_form_shop_2605.security.JWT.JwtAuthenticationFilter;
import com.re_form_shop_2605.security.JWT.JwtTokenProvider;
import com.re_form_shop_2605.security.RestAccessDeniedHandler;
import com.re_form_shop_2605.security.JWT.RestAuthenticationEntryPoint;
import com.re_form_shop_2605.security.handler.CustomSocialLoginSuccessHandler;
import com.re_form_shop_2605.service.login.AuthTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: 스프링 시큐리티 설정
 * ─────────────────────────────────────────────────────
 */
@Log4j2
@Configuration
@RequiredArgsConstructor
@EnableWebSecurity
// 애플리케이션 전반의 인증/인가 규칙과 스프링 시큐리티 메인 체인을 정의하는 설정 클래스
public class SecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final PasswordEncoder passwordEncoder;

    @Value("${security.jwt.secret:test-jwt-secret}")
    private String jwtSecret;

    @Value("${security.jwt.access-token-expiration-seconds:3600}")
    private long accessTokenExpirationSeconds;

    @Value("${security.jwt.refresh-token-expiration-seconds:1209600}")
    private long refreshTokenExpirationSeconds;

    @Value("${app.auth.oauth-success-redirect-uri:http://localhost:5173/oauth/callback}")
    private String oauthSuccessRedirectUri;

    // OAuth2 로그인 실패 시 리다이렉트할 프론트엔드 URL
    @Value("${app.auth.oauth-failure-redirect-uri:http://localhost:5173/login?error=oauth}")
    private String oauthFailureRedirectUri;

    @Value("${app.cors.allowed-origin-patterns:http://localhost:5173}")
    private String corsAllowedOriginPatterns;

    // 정적 리소스 보안 제외 설정
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        log.info("--- [SecurityConfig] webSecurityCustomizer: 정적 리소스 보안 제외 설정 ---");
        return web -> web.ignoring()
                .requestMatchers(PathRequest.toStaticResources().atCommonLocations())
                .requestMatchers("/css/**", "/js/**", "/images/**", "/fonts/**", "/webjars/**");
    }
    // JWT 토큰 생성/검정
    @Bean
    public JwtTokenProvider jwtTokenProvider() {
        // JWT 구현체가 흩어진 @Value에 직접 의존하지 않도록 SecurityConfig에서 한 번에 조립
        return new JwtTokenProvider(jwtSecret, accessTokenExpirationSeconds, refreshTokenExpirationSeconds);
    }

    // JWT accessToken 검증 필터
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            AuthTokenService authTokenService
    ) {
        // Authorization 헤더의 Bearer access token을 검사할 필터를 등록한다.
        return new JwtAuthenticationFilter(jwtTokenProvider(), customUserDetailsService, authTokenService);
    }

    // 인증 실패시 응답을 브라우저 기본 HTML 대신 JSON 403으로 고정
    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint() {
        // 인증되지 않은 요청을 JSON 403 응답으로 바꿔 줄 진입점 핸들러를 등록한다.
        return new RestAuthenticationEntryPoint();
    }

    // 권한 부족시 응답을 브라우저 기본 HTML 대신 JSON 403으로 고정
    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler() {
        // 인증은 되었지만 권한이 부족한 요청을 JSON 403 응답으로 바꿔 줄 핸들러를 등록한다.
        return new RestAccessDeniedHandler();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(
                Arrays.stream(corsAllowedOriginPatterns.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList()
        );
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.addAllowedHeader("*");
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Set-Cookie"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // 소셜 로그인 성공시 JWT 발급 후 프론트 콜백으로 리다이렉트 핸들러
    @Bean
    public CustomSocialLoginSuccessHandler customSocialLoginSuccessHandler(
            AuthTokenService authTokenService
    ) {
        // OAuth2 로그인 성공 시 JWT를 발급하고 프론트 콜백으로 리다이렉트할 핸들러를 등록한다.
        return new CustomSocialLoginSuccessHandler(authTokenService, oauthSuccessRedirectUri);
    }

    // 스프링 시큐리티 필터 설정
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CustomSocialLoginSuccessHandler customSocialLoginSuccessHandler,
            JwtAuthenticationFilter jwtAuthenticationFilter
    ) throws Exception {
        log.info("--- [SecurityConfig] securityFilterChain: 회원 시큐리티 설정 ---");

        http
                // 인증 진입점과 공개 조회 경로만 열고 나머지는 JWT 인증을 요구한다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/api/auth/login",
                                "/api/auth/login/verify",
                                "/api/auth/register",
                                "/api/auth/check-nickname",
                                "/api/auth/me",
                                "/api/auth/token/refresh",
                                "/api/auth/password/reset",
                                "/api/auth/oauth2/**",
                                "/api/auth/oauth/**",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/login/**",
                                "/stomp/**",
                                "/api/delivery/tracking/**",
                                "/uploads/**",
                                "/confirm"
                        ).permitAll()
                        .requestMatchers(
                                org.springframework.http.HttpMethod.GET,
                                "/api/community",
                                "/api/community/**",
                                "/api/listings",
                                "/api/listings/**",
                                "/api/statistics",
                                "/api/recommendations",
                                "/api/version"
                        )
                        .permitAll()
                        .anyRequest().authenticated()
                )
                // csrf 토큰을 사용 안함
                .csrf(csrf -> csrf.disable())
                .cors(org.springframework.security.config.Customizer.withDefaults())
                // JWT를 쓰므로 서버 세션은 만들지 않는다.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .oauth2Login(oauth2 -> oauth2
                        // 카카오/구글 사용자 정보를 내부 회원 계정과 매핑한다.
                        .userInfoEndpoint(userInfo ->
                                userInfo.userService(customOAuth2UserService))
                        // OAuth2 로그인 성공 시 자체 JWT를 발급해 프론트 콜백으로 보낸다.
                        .successHandler(customSocialLoginSuccessHandler)
                        // OAuth2 로그인 실패 시 프론트 로그인 페이지로 리다이렉트 (기본값: 8080/login?error 방지)
                        .failureHandler((request, response, exception) ->
                                response.sendRedirect(oauthFailureRedirectUri))
                )
                // 인증 실패/권한 부족 응답을 브라우저 기본 HTML 대신 JSON 403으로 고정한다.
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(restAuthenticationEntryPoint())
                        .accessDeniedHandler(restAccessDeniedHandler())
                )

                // 컨트롤러 진입 전에 Authorization 헤더의 access token을 먼저 검사한다.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // 이메일 회원 가입 시 사용할 인증 provider
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        // 이메일/비밀번호 로그인 시 사용할 인증 provider를 구성한다.
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(customUserDetailsService);
        // DB 비밀번호와 로그인 요청 비밀번호 비교에 BCrypt 인코더를 사용한다.
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    // AuthenticationManager 사용
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration
    ) throws Exception {
        // 서비스 계층에서 직접 authenticate() 호출할 수 있도록 AuthenticationManager를 노출한다.
        return authenticationConfiguration.getAuthenticationManager();
    }
}
