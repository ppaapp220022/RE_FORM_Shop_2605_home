package com.re_form_shop_2605.service.mail;

import com.re_form_shop_2605.dto.etc.TradeNotificationTemplateDTO;
import com.re_form_shop_2605.entity.member.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 로그인 인증 메일과 거래 알림 메일을 공통 인프라로 발송한다.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class MailServiceImpl implements MailService {

    private final JavaMailSender javaMailSender;

    @Value("${app.mail.from:ppaapp330033@gmail.com}")
    private String mailFrom;

    @Value("${app.mail.enabled:true}")
    private boolean mailEnabled;

    @Value("${app.auth.login-code-delivery-mode:mail}")
    private String loginCodeDeliveryMode;

    @Override
    public void sendLoginCodeEmail(String email, String code) {
        if (!"mail".equalsIgnoreCase(loginCodeDeliveryMode)) {
            log.info("[MailService] login verification code email={} code={} mode={}",
                    email,
                    code,
                    loginCodeDeliveryMode);
            return;
        }

        sendMail(
                email,
                "[RE:FORM] 로그인 2차 인증코드",
                """
                        안녕하세요. RE:FORM 입니다.

                        로그인 2차 인증코드는 아래와 같습니다.

                        인증코드: %s

                        이 코드는 5분 동안만 유효합니다.

                        본인이 요청하지 않았다면 비밀번호를 변경하고 고객센터에 문의해 주세요.
                        """.formatted(code),
                false
        );
    }

    @Override
    public void sendPasswordResetCodeEmail(String email, String code) {
        if (!"mail".equalsIgnoreCase(loginCodeDeliveryMode)) {
            log.info("[MailService] password reset code email={} code={} mode={}",
                    email,
                    code,
                    loginCodeDeliveryMode);
            return;
        }

        sendMail(
                email,
                "[RE:FORM] 비밀번호 재설정 인증코드",
                """
                        안녕하세요. RE:FORM 입니다.

                        비밀번호 재설정 인증코드는 아래와 같습니다.

                        인증코드: %s

                        이 코드는 5분 동안만 유효합니다.

                        본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않습니다.
                        """.formatted(code),
                false
        );
    }

    @Override
    public void sendTradeNotificationEmail(Member member, TradeNotificationTemplateDTO template) {
        if (member == null || member.getEmail() == null || member.getEmail().isBlank()) {
            log.warn("[MailService] skip trade mail - missing recipient email");
            return;
        }

        sendMail(
                member.getEmail(),
                template.emailSubject(),
                """
                        안녕하세요. RE:FORM 입니다.

                        %s

                        거래 확인: %s
                        """.formatted(template.content(), template.linkUrl()),
                true
        );
    }

    private void sendMail(String email, String subject, String text, boolean swallowException) {
        if (!mailEnabled) {
            log.info("[MailService] mail disabled recipient={} subject={}", email, subject);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject(subject);
            message.setText(text);
            javaMailSender.send(message);
        } catch (Exception ex) {
            if (swallowException) {
                log.error("[MailService] failed to send mail recipient={} subject={}", email, subject, ex);
                return;
            }
            throw ex;
        }
    }
}
