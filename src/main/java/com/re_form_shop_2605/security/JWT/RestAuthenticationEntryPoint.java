package com.re_form_shop_2605.security.JWT;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-11
 * 설명: 인증되지 않은 요청을 프론트 친화적인 JSON 401 응답으로 바꾸는 진입점 핸들러
 * ─────────────────────────────────────────────────────
 */
// 인증이 안 된 요청을 프론트가 바로 처리할 수 있는 JSON 401 응답으로 바꾸는 진입점
@Log4j2
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {
        // 필터에서 저장한 예외 정보를 확인해 구체적인 에러 응답을 구성한다.
        String exception = (String) request.getAttribute("exception");
        log.warn("[RestAuthenticationEntryPoint] unauthorized path={} exception={} message={}",
                request.getRequestURI(), exception, authException.getMessage());

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        if ("EXPIRED_TOKEN".equals(exception)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"success\":false,\"message\":\"토큰이 만료되었습니다.\",\"code\":\"40101\"}");
        } else if ("INVALID_TOKEN".equals(exception)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"success\":false,\"message\":\"유효하지 않은 토큰입니다.\",\"code\":\"40102\"}");
        } else if ("AUTH_ERROR".equals(exception)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"success\":false,\"message\":\"인증 오류가 발생했습니다.\",\"code\":\"40103\"}");
        } else if ("ACCOUNT_RESTRICTED".equals(exception)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"success\":false,\"message\":\"이용이 제한된 계정입니다.\",\"code\":\"40301\"}");
        } else {
            // 토큰 자체가 없는 등의 일반적인 금지 요청
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("{\"success\":false,\"message\":\"인증이 필요합니다.\",\"code\":\"40300\"}");
        }
    }
}
