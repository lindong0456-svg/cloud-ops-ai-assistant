package com.cloudops.config;

import com.cloudops.agent.OpsAssistant;
import com.cloudops.agent.listener.TokenUsageListener;
import com.cloudops.registry.ToolRegistry;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Agent 配置类 — 用 AiServices.builder 构建 OpsAssistant 代理实例
 *
 * 流式方案：OpsAssistant.chatStream() 返回 TokenStream（AiServices 原生支持），
 *   Controller 层用 Flux.create() 将 TokenStream 的回调桥接到 Flux<String>。
 *   比 Flux<String> 作为返回类型更可靠，不依赖 langchain4j-reactor SPI 适配器的版本行为。
 *
 * 为什么用两个 Model Bean：
 *   - ChatLanguageModel（同步）：给 chat() 方法用
 *   - StreamingChatLanguageModel（流式）：给 chatStream() 方法用
 *   AiServices 代理根据返回类型自动选模型：
 *     String       → ChatLanguageModel
 *     TokenStream  → StreamingChatLanguageModel
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final ChatLanguageModel chatLanguageModel;

    // Step3: 多轮对话记忆 Provider（按 userId 隔离会话）
    private final ChatMemoryProvider chatMemoryProvider;

    // Step5: 配置驱动 Tool 注册器（替代硬编码 Tool 列表）
    private final ToolRegistry toolRegistry;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.temperature:0.7}")
    private double temperature;

    @Value("${langchain4j.open-ai.chat-model.max-tokens:4096}")
    private int maxTokens;

    @Value("classpath:/prompts/ops-assistant-system.txt")
    private org.springframework.core.io.Resource systemPromptResource;

    /**
     * 流式对话模型 Bean — 给 chatStream()（TokenStream）用
     *
     * AiServices 检测到方法返回 TokenStream 时自动使用此模型，
     * 不会回退到同步 ChatLanguageModel。
     */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel(TokenUsageListener listener) {
        log.info("[Agent] 构建 StreamingChatLanguageModel: baseUrl={}, model={}", baseUrl, modelName);
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .listeners(List.of(listener))
                .build();
    }

    @Bean
    public OpsAssistant opsAssistant(StreamingChatLanguageModel streamingChatLanguageModel) {
        log.info("[Agent] 开始构建 OpsAssistant 代理实例（同步 + TokenStream 流式）");

        // 1. 加载 System Prompt
        String systemPrompt = loadSystemPrompt();
        log.info("[Agent] System Prompt 加载完成, 长度={}字符", systemPrompt.length());

        // 2. 配置驱动收集 Tool
        List<Object> tools = toolRegistry.getEnabledTools();

        // 3. 构建 AiServices 代理实例
        //    - chatLanguageModel → chat() 返回 String
        //    - streamingChatLanguageModel → chatStream() 返回 TokenStream
        OpsAssistant assistant = AiServices.builder(OpsAssistant.class)
                .chatLanguageModel(chatLanguageModel)                    // 同步：chat()
                .streamingChatLanguageModel(streamingChatLanguageModel)   // 流式：chatStream() → TokenStream
                .systemMessageProvider(memoryId -> systemPrompt)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(tools)
                .maxSequentialToolsInvocations(10)
                .build();

        log.info("[Agent] OpsAssistant 构建完成（同步 + TokenStream流式 + 多轮记忆 + {} 个 Tool）", tools.size());
        return assistant;
    }

    private String loadSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[Agent] 加载 System Prompt 失败", e);
            throw new IllegalStateException("System Prompt 加载失败，请检查 resources/prompts/ops-assistant-system.txt", e);
        }
    }
}
