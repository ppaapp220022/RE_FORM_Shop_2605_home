package com.re_form_shop_2605.service.mail;

import com.re_form_shop_2605.dto.etc.TradeNotificationTemplateDTO;
import com.re_form_shop_2605.entity.member.Member;

/**
 * 메일 발송 비즈니스 로직을 한곳에 모은 서비스 인터페이스.
 */
public interface MailService {

    void sendLoginCodeEmail(String email, String code);

    void sendPasswordResetCodeEmail(String email, String code);

    void sendTradeNotificationEmail(Member member, TradeNotificationTemplateDTO template);
}
