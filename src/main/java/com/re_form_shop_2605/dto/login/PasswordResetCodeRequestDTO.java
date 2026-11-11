package com.re_form_shop_2605.dto.login;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 인증코드를 이메일로 받기 위한 요청 DTO
 */
public record PasswordResetCodeRequestDTO(
        @NotBlank
        @Email
        @Size(max = 100)
        String email
) {
}
