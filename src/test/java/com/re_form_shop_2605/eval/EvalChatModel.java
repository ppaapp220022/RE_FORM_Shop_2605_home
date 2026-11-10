package com.re_form_shop_2605.eval;

import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ─────────────────────────────────────────────────────
 * 설명: eval에서 서비스가 쓰는 채팅 모델 - 호출 실패와 토큰 사용량을 센다
 *  - 기본은 서비스가 프롬프트에 넣은 운영 설정(모델·추론 강도) 그대로
 *  - -Peval.chatModel=gpt-4.1-mini, -Peval.chatReasoning=low 로 그 위에 덮어써 비용과 품질을 비교한다
 *  - 출력 토큰에는 추론 토큰도 들어간다 (OpenAI는 추론 토큰을 출력 가격으로 받는다)
 * ─────────────────────────────────────────────────────
 */
@Log4j2
public class EvalChatModel implements ChatModel {
    // 서비스가 프롬프트에 모델·추론 강도를 직접 넣으므로(운영 설정) 비교할 값은 프롬프트 옵션 위에 덮어쓴다
    private static final String MODEL_OVERRIDE = System.getProperty("eval.chatModel");
    private static final String REASONING_OVERRIDE = System.getProperty("eval.chatReasoning");

    private final OpenAiChatModel delegate;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicReference<String> sentOptions = new AtomicReference<>("none");

    public EvalChatModel() {
        delegate = OpenAiChatModel.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        try {
            Prompt sent = withOverrides(prompt);
            if (sent.getOptions() instanceof OpenAiChatOptions options) {
                sentOptions.set(options.getModel() + "/" + options.getReasoningEffort());
            }
            ChatResponse response = delegate.call(sent);
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens.addAndGet(nullToZero(usage.getPromptTokens()));
                completionTokens.addAndGet(nullToZero(usage.getCompletionTokens()));
            }
            return response;
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.error("[Eval] Chat API 호출 실패: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static Prompt withOverrides(Prompt prompt) {
        if (MODEL_OVERRIDE == null && REASONING_OVERRIDE == null) {
            return prompt;
        }
        OpenAiChatOptions options = prompt.getOptions() instanceof OpenAiChatOptions openAi
                ? OpenAiChatOptions.fromOptions(openAi)
                : new OpenAiChatOptions();
        if (MODEL_OVERRIDE != null) {
            options.setModel(MODEL_OVERRIDE);
        }
        if (REASONING_OVERRIDE != null) {
            options.setReasoningEffort(REASONING_OVERRIDE);
        }
        return new Prompt(prompt.getInstructions(), options);
    }

    public int failures() {
        return failures.get();
    }

    /** 리포트용 한 줄 - 모델, 호출 수, 호출당 평균 입력·출력 토큰 */
    public String usageSummary() {
        int n = Math.max(calls.get(), 1);
        return String.format(Locale.ROOT, "sent(model/reasoning)=%s chatModelOverride=%s reasoningOverride=%s calls=%d avgPromptTokens=%.0f avgCompletionTokens=%.0f totalPrompt=%d totalCompletion=%d",
                sentOptions.get(), MODEL_OVERRIDE != null ? MODEL_OVERRIDE : "service", REASONING_OVERRIDE != null ? REASONING_OVERRIDE : "service", calls.get(),
                (double) promptTokens.get() / n, (double) completionTokens.get() / n, promptTokens.get(), completionTokens.get());
    }

    private static long nullToZero(Integer value) {
        return value != null ? value : 0;
    }
}
