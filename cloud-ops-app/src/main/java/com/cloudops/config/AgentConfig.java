package com.cloudops.config;

import com.cloudops.agent.OpsAssistant;
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
 * 阶段5 新增：StreamingChatLanguageModel（流式对话模型）
 *   OpsAssistant 接口新增 chatStream() 方法，返回 Flux<String>
 *   LangChain4j 原生支持 Flux 返回类型，绑定 StreamingChatLanguageModel 即可
 *
 * 为什么用两个 Model Bean：
 *   - ChatLanguageModel（同步）：给 chat() 方法用
 *   - StreamingChatLanguageModel（流式）：给 chatStream() 方法用
 *   AiServices 代理根据返回类型自动选模型
 *   LangChain4j 的 AiServices 会根据返回类型自动选择用哪个 Model
 *
 * 对标联通：
 *   联通报表导出有同步（@Async线程池）和流式（SXSSF流式写入）两种模式；
 *   这里 Agent 也有同步和流式两种模式，按场景选。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final ChatLanguageModel chatLanguageModel;

    // 阶段5: 流式对话模型（从 application.yml 的 chat-model 配置派生）
    private final dev.langchain4j.model.openai.OpenAiChatModel openAiChatModel;

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
     * 流式对话模型 Bean — 给 chatStream() 方法用
     *
     * 从 application.yml 读取同样的 DeepSeek 配置，构建流式版本
     */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        log.info("[Agent] 构建 StreamingChatLanguageModel: baseUrl={}, model={}", baseUrl, modelName);
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public OpsAssistant opsAssistant(StreamingChatLanguageModel streamingChatLanguageModel) {
        log.info("[Agent] 开始构建 OpsAssistant 代理实例（含流式）");

        // 1. 加载 System Prompt
        String systemPrompt = loadSystemPrompt();
        log.info("[Agent] System Prompt 加载完成, 长度={}字符", systemPrompt.length());

        // 2. 配置驱动收集 Tool
        List<Object> tools = toolRegistry.getEnabledTools();

        // 3. 构建 AiServices 代理实例（同时绑定同步+流式模型）
        OpsAssistant assistant = AiServices.builder(OpsAssistant.class)
                .chatLanguageModel(chatLanguageModel)                    // 同步：chat()
                .streamingChatLanguageModel(streamingChatLanguageModel)   // 流式：chatStream()
                .systemMessageProvider(memoryId -> systemPrompt)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(tools)
                .maxSequentialToolsInvocations(10)
                .build();

        log.info("[Agent] OpsAssistant 构建完成（含同步+流式 + 多轮记忆 + {} 个 Tool）", tools.size());
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
