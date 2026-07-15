package com.cloudops.agent.listener;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM 调用层可观测性 — 记录每次模型调用的 token 消耗
 *
 * 注册位置：OpenAiStreamingChatModel.Builder.listeners()
 * 回调链路：onResponse(ctx) → ctx.chatResponse() → ChatResponse.tokenUsage()
 *           → TokenUsage.inputTokenCount() / outputTokenCount() / totalTokenCount()
 *
 * ChatModelListener 是 LangChain4j 原生监听机制，
 * 每次 LLM 调用自动记录 token 消耗到 Micrometer，
 * 配合 Prometheus + Grafana 可做 token 消耗趋势监控。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenUsageListener implements ChatModelListener {

    private final MeterRegistry meterRegistry;

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        TokenUsage usage = ctx.chatResponse().tokenUsage();
        if (usage == null) return;

        if (usage.inputTokenCount() != null) {
            meterRegistry.counter("llm.token.input").increment(usage.inputTokenCount());
        }
        if (usage.outputTokenCount() != null) {
            meterRegistry.counter("llm.token.output").increment(usage.outputTokenCount());
        }
        if (usage.totalTokenCount() != null) {
            meterRegistry.counter("llm.token.total").increment(usage.totalTokenCount());
        }

        log.debug("[TokenUsage] input={} output={} total={}",
                usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
    }
}
