# Auth Backend Guide

## Overview

이 문서는 현재 프로젝트의 백엔드 인증 구현 구조와 운영 규칙을 정리한다.

- 일반 로그인: 이메일/비밀번호 + Spring Security `AuthenticationManager`
- 소셜 로그인: Spring Security OAuth2 + 카카오/구글 사용자 정보 매핑
- access token: 짧은 수명, API 인증 전용
- refresh token: 긴 수명, Redis 세션 관리 및 재발급 전용
- 세션 단위 관리: `memberId + sessionId`

## Exception Policy

인증/세션/토큰 관련 예외는 `403 Forbidden`으로 통일한다.

- 토큰 없음
- access token 형식 오류
- refresh token 위조/만료/불일치
- 현재 로그인 사용자와 토큰 소유자 불일치
- 잘못된 이메일/비밀번호

관련 클래스:

- `src/main/java/com/re_form_shop_2605/security/JWT/RestAuthenticationEntryPoint.java`
- `src/main/java/com/re_form_shop_2605/controller/common/advice/CustomRestAdvice.java`

## Token Claims

access/refresh token 공통 claim:

- `sub`: 회원 이메일
- `memberId`: 회원 번호
- `role`: Spring Security 권한 문자열
- `type`: `access` 또는 `refresh`
- `provider`: `local`, `kakao`, `google`
- `loginType`: `local`, `social`
- `sessionId`: 세션 식별자(UUID)
- `iat`: 발급 시각
- `exp`: 만료 시각

## Redis Structure

### Refresh Token Key

```text
auth:refresh:{memberId}:{sessionId}
```

- value: refresh token 원문
- TTL: refresh token 만료 시간과 동일

### Session Index Key

```text
auth:sessions:{memberId}
```

- type: Set
- value: 해당 회원의 모든 `sessionId`
- 목적: 세션 목록 조회, 전체 로그아웃, 특정 세션 로그아웃 시 `KEYS` 명령 없이 처리

## Session Policy

- 로그인 성공 시 새로운 `sessionId`를 발급한다.
- refresh 요청 시 기존 refresh token이 유효한지 Redis에서 확인한 뒤 새 access token만 재발급한다.
- refresh token은 만료 전까지 같은 세션에서 계속 재사용한다.
- 비밀번호 재설정 시 해당 회원의 모든 세션을 삭제한다.
- 특정 세션 로그아웃과 전체 로그아웃을 모두 지원한다.

## Auth API

### Public

- `POST /api/auth/login`
- `POST /api/auth/register`
- `GET /api/auth/check-nickname`
- `POST /api/auth/token/refresh`
- `POST /api/auth/password/reset/code` (가입 이메일로 6자리 인증코드 발송)
- `POST /api/auth/password/reset` (이메일 + 인증코드 + 새 비밀번호)
- `GET /api/auth/oauth/kakao`
- `GET /api/auth/oauth/google`

### Protected

- `GET /api/auth/me`
- `GET /api/auth/sessions`
- `POST /api/auth/logout`
- `POST /api/auth/logout/session`
- `POST /api/auth/logout/all`

## API Examples

### Login

```json
{
  "email": "test@test.com",
  "password": "password123"
}
```

응답:

```json
{
  "success": true,
  "message": "로그인 완료",
  "data": {
    "accessToken": "...",
    "refreshToken": "...",
    "user": {
      "id": 1,
      "email": "test@test.com",
      "nickname": "tester",
      "profileImageUrl": null,
      "role": "USER",
      "mannerScore": 0
    }
  }
}
```

### Refresh

```json
{
  "refreshToken": "..."
}
```

응답:

```json
{
  "success": true,
  "message": "토큰 재발급 완료",
  "data": {
    "accessToken": "..."
  }
}
```

중요:

- refresh 응답을 받아도 기존 `refreshToken`은 그대로 유지한다.
- 서버는 Redis에 저장된 refresh token과 요청 토큰이 일치할 때만 access token을 다시 발급한다.

### Specific Session Logout

```json
{
  "sessionId": "18df380b-fc5f-4043-84e2-fcc5e9466c5e"
}
```

### Session List Response

```json
{
  "success": true,
  "message": "세션 목록 조회 완료",
  "data": [
    {
      "sessionId": "18df380b-fc5f-4043-84e2-fcc5e9466c5e",
      "loginType": "social",
      "provider": "kakao",
      "issuedAt": "2026-05-12T01:00:00Z",
      "expiresAt": "2026-05-26T01:00:00Z",
      "current": true
    }
  ]
}
```

## OAuth2 Flow

### Kakao

1. 브라우저가 `GET /api/auth/oauth/kakao` 호출
2. 서버가 `/oauth2/authorization/kakao`로 리다이렉트
3. 카카오 인증/동의 완료
4. Spring Security가 `/login/oauth2/code/kakao` 콜백 처리
5. `CustomOAuth2UserService`가 내부 회원과 소셜 계정 매핑
6. `CustomSocialLoginSuccessHandler`가 JWT 발급
7. 프론트 콜백 URL로 `#accessToken=...&refreshToken=...` fragment 전달

### Frontend Callback

프론트는 `window.location.hash`에서 토큰을 읽고 저장한 뒤 주소 fragment를 지우는 것이 좋다.

예시:

```js
const hash = new URLSearchParams(window.location.hash.slice(1));
const accessToken = hash.get("accessToken");
const refreshToken = hash.get("refreshToken");
window.history.replaceState({}, document.title, window.location.pathname);
```

## Manual Test Checklist

### General Login

1. `POST /api/auth/register`
2. `POST /api/auth/login`
3. `GET /api/auth/me`
4. `GET /api/auth/sessions`
5. `POST /api/auth/token/refresh`
6. `POST /api/auth/logout`
7. 이전 refresh token으로 재발급 실패 확인

### Social Login

1. 브라우저에서 `http://localhost:8080/api/auth/oauth/kakao`
2. 카카오 로그인 성공 후 프론트 콜백 도달 확인
3. fragment에서 `accessToken`, `refreshToken` 확인
4. Swagger `Authorize`에 access token 적용
5. `GET /api/auth/me`
6. `GET /api/auth/sessions`

### Security Fail Cases

1. `Bearer` 없이 보호 API 호출 -> 403
2. access token 대신 refresh token으로 보호 API 호출 -> 403
3. 토큰 1글자 변조 후 호출 -> 403
4. 로그아웃 후 이전 refresh token으로 재발급 시도 -> 403
5. 특정 세션 로그아웃 후 그 세션 refresh token 재사용 시도 -> 403

## Log Points

진입/종료 로그는 다음 위치에서 남긴다.

- `AuthController`
- `AuthServiceImpl`
- `AuthTokenServiceImpl`
- `CustomSocialLoginSuccessHandler`
- `RestAuthenticationEntryPoint`
- `RestAccessDeniedHandler`

## Future Improvements

- 세션 메타데이터에 `userAgent`, `ip`, `deviceName` 저장
- 관리자용 강제 세션 종료 API
- 비밀번호 변경 시 최근 세션 유지/전체 종료 정책 선택 기능
- Redis 세션 Set에 대한 정리 배치 또는 보조 검증 로직
