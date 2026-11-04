package com.re_form_shop_2605.service.AI;

import com.re_form_shop_2605.entity.Enum.RiskLevel;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class ModerationKeyword {

    private static final List<LocalModerationRule> LOCAL_MODERATION_RULES = List.of(
            // 명령형/위협형만 잡는다 ("죽어도", "기가 죽어요", "옷장 뒤져서", "불이 꺼져서" 같은 일상 표현 제외)
            new LocalModerationRule("죽어라|죽어버려|죽여버|죽일거|죽인다|뒤져라|디져라|뒤질래|찔러버|꺼져(?!서|있|버렸|요|도|야)", "폭력적이거나 위협적인 표현", RiskLevel.HIGH),
            new LocalModerationRule("느금마|니애미|니엄마|네애미", "가족을 향한 심한 비하 표현", RiskLevel.HIGH),
            // "택배비 선불"은 정상 거래 표현이므로 제외, 플랫폼 밖 송금/메신저 유도 추가
            new LocalModerationRule("(?<!택배비|택배비는|배송비|배송비는)선불|계좌이체|계좌로(바로)?(송금|입금)|카톡아이디|오픈채팅|텔레그램", "사기 위험이 높은 거래 유도 표현", RiskLevel.MID),
            // "가품 아닙니다" 같은 정품 강조 문장 제외, 은어 추가 ("짭짤" 제외)
            new LocalModerationRule("가품(?!아니|아님|아닙)|레플리카|이미테이션|짭(?!짤)", "위조품 판매가 의심되는 표현", RiskLevel.HIGH),
            new LocalModerationRule("정품아님", "주의가 필요한 판매 표현", RiskLevel.MID),
            // 숫자 삽입("시1발"), 한영키 우회("tlqkf") 포함, "시발점" 제외
            new LocalModerationRule("씨+발|시+발(?!점)|[시씨쉬]\\d+발|씹+팔|ㅆㅂ|ㅅㅂ|시바|씨바|쉬발|끼발|tlqkf", "강한 욕설 또는 공격적 표현", RiskLevel.HIGH),
            new LocalModerationRule("병+신|븅+신|빙+신|ㅂㅅ|qudtls", "개인에 대한 공격적 표현", RiskLevel.HIGH),
            new LocalModerationRule("개새끼|개색끼|개쉐이|개쉑|새끼(?!손가락|발가락|를낳|낳|고양이|강아지)", "개인에 대한 공격적 표현", RiskLevel.HIGH),
            new LocalModerationRule("지랄|좆|존나|ㅈㄴ", "비속어 또는 공격적 표현", RiskLevel.HIGH)
    );

    public LocalModerationHit detect(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return null;
        }

        LocalModerationRule matchedRule = null;
        for (LocalModerationRule rule : LOCAL_MODERATION_RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                if (matchedRule == null || rule.riskLevel().ordinal() > matchedRule.riskLevel().ordinal()) {
                    matchedRule = rule;
                }
            }
        }

        if (matchedRule == null) {
            return null;
        }

        return new LocalModerationHit(matchedRule.riskLevel(), matchedRule.reason(), normalized);
    }

    private String normalize(String content) {
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC).toLowerCase();
        // NFKC는 호환 자모(ㅅ, U+3145)를 조합용 자모(U+1109)로 바꾸므로 U+1100-U+11FF 도 남긴다
        normalized = normalized.replaceAll("[^0-9a-z가-힣ㄱ-ㅎㅏ-ㅣ\\u1100-\\u11FF]", "");
        return normalized;
    }

    private record LocalModerationRule(Pattern pattern, String reason, RiskLevel riskLevel) {
        private LocalModerationRule(String regex, String reason, RiskLevel riskLevel) {
            // 본문과 같은 NFKC 정규화를 패턴에도 적용해야 "ㅅㅂ" 같은 초성 규칙이 매칭된다
            this(Pattern.compile(Normalizer.normalize(regex, Normalizer.Form.NFKC)), reason, riskLevel);
        }
    }

    public record LocalModerationHit(RiskLevel riskLevel, String reason, String normalizedContent) {
    }
}
