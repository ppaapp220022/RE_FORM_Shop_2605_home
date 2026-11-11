package com.re_form_shop_2605.config;

import com.re_form_shop_2605.dto.login.MemberSecurityDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 API가 경로 단위로 막히는지, 비밀번호 재설정 코드 요청이 로그인 없이 열려 있는지 확인한다.
 */
@SpringBootTest
class SecurityAccessTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 일반_회원은_재임베딩_API를_호출할_수_없다() throws Exception {
        mockMvc.perform(post("/api/admin/embed/posts").with(user(principal("USER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/embed/posts").with(user(principal("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 일반_회원은_관리자_API에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard").with(user(principal("USER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/withdraw-requests").with(user(principal("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자는_재임베딩_진행_상황을_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/api/admin/embed/posts").with(user(principal("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void 재설정_코드_요청은_로그인_없이_가능하고_가입_여부를_드러내지_않는다() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@test.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 코드_없이_비밀번호를_재설정할_수_없다() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@reform.com\",\"nickname\":\"관리자\",\"newPassword\":\"hacked1234\"}"))
                .andExpect(status().isBadRequest());
    }

    private MemberSecurityDTO principal(String role) {
        return new MemberSecurityDTO(
                1L, "user@test.com", "pw", "user@test.com", "user", null, false, "local",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
