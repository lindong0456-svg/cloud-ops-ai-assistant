package com.cloudops.config;

import com.cloudops.agent.OpsAssistant;
import com.cloudops.agent.listener.TokenUsageListener;
import com.cloudops.registry.ToolRegistry;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 模型管理器 — 把“写死的 DeepSeek”升级为“配置驱动的模型注册表”
 *
 * 能力：
 *   1. 从 application.yml 的 app.models 读取多个候选模型（deepseek / qwen-vl ...）
 *   2. 按 app.active-model 构建激活的 Streaming/Sync ChatModel + OpsAssistant 代理
 *   3. switchTo(key) 运行时切换模型并重建代理（切换持久化到磁盘，重启后保持）
 *
 * 设计要点：
 *   - OpsAssistant 不再是 Spring 单例 Bean，而由本管理器按需重建并缓存。
 *     ChatController 通过 getOpsAssistant() 获取当前激活实例。
 *   - 切换过程加锁重建，进行中的请求自然落到旧/新实例，不影响正确性。
 *   - System Prompt 从 classpath 加载（与改造前 AgentConfig 行为一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "app")
@Data
public class ModelManager {

    private static final Path ACTIVE_MODEL_FILE =
            Path.of(System.getProperty("user.dir"), ".cloud-ops-active-model");
    private final ChatMemoryProvider chatMemoryProvider;
    private final ToolRegistry toolRegistry;
    private final TokenUsageListener tokenUsageListener;
    private final Object lock = new Object();
    /** 模型注册表（来自 application.yml 的 app.models） */
    private List<ModelDef> models;
    /** 当前激活模型 key（来自 app.active-model，运行时可切换） */
    private String activeModel;
    private volatile OpsAssistant activeAssistant;
    private volatile ModelDef activeDef;

    @PostConstruct
    public void init() {
        // 优先读取磁盘持久化的激活模型（支持重启后保持切换结果）
        String persisted = readPersisted();
        if (persisted != null && !persisted.isBlank()) {
            this.activeModel = persisted;
        }
        rebuild();
        log.info("[ModelManager] 初始化完成, 激活模型={} (可选: {})",
                activeDef != null ? activeDef.getKey() : "null",
                models.stream().map(ModelDef::getKey).toList());
    }

    /** 当前激活的 Agent 实例（ChatController 调用） */
    public OpsAssistant getOpsAssistant() {
        return activeAssistant;
    }

    /** 当前激活模型定义（用于 done 事件回传模型名 + 前端徽标） */
    public ModelDef getActiveDef() {
        return activeDef;
    }

    /** 全部可选模型（前端切换下拉用） */
    public List<ModelDef> getModelDefs() {
        return models;
    }

    /**
     * 切换激活模型 — 重建 Streaming/Sync 模型 + OpsAssistant 代理
     * 线程安全：synchronized 保证切换期间不会并发重建
     */
    public synchronized void switchTo(String key) {
        ModelDef def = models.stream()
                .filter(m -> m.getKey().equals(key))
                .findFirst()
                .orElse(null);
        if (def == null) {
            throw new IllegalArgumentException("未知模型: " + key + "，可选: " +
                    models.stream().map(ModelDef::getKey).toList());
        }
        if (key.equals(this.activeModel) && activeAssistant != null) {
            log.info("[ModelManager] 模型 {} 已激活，无需切换", key);
            return;
        }
        this.activeModel = key;
        writePersisted(key);
        rebuild();
        log.info("[ModelManager] 已切换模型为 {}", key);
    }

    /** 用当前 activeModel 重建模型与 Agent 代理 */
    private void rebuild() {
        ModelDef def = resolve(activeModel);
        if (def == null) {
            // 兜底：用第一个模型
            def = models.get(0);
            this.activeModel = def.getKey();
        }
        synchronized (lock) {
            double temperature = def.getTemperature() != null ? def.getTemperature() : 0.7;
            int maxTokens = def.getMaxTokens() != null ? def.getMaxTokens() : 4096;

            StreamingChatLanguageModel streaming = OpenAiStreamingChatModel.builder()
                    .apiKey(def.getApiKey())
                    .baseUrl(def.getBaseUrl())
                    .modelName(def.getModelName())
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(60))
                    .listeners(List.of(tokenUsageListener))
                    .build();

            ChatLanguageModel sync = OpenAiChatModel.builder()
                    .apiKey(def.getApiKey())
                    .baseUrl(def.getBaseUrl())
                    .modelName(def.getModelName())
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(60))
                    .build();

            OpsAssistant assistant = AiServices.builder(OpsAssistant.class)
                    .chatLanguageModel(sync)
                    .streamingChatLanguageModel(streaming)
                    .systemMessageProvider(memoryId -> loadSystemPrompt())
                    .chatMemoryProvider(chatMemoryProvider)
                    .tools(toolRegistry.getEnabledTools())
                    .maxSequentialToolsInvocations(10)
                    .build();

            this.activeDef = def;
            this.activeAssistant = assistant;
        }
    }

    private ModelDef resolve(String key) {
        return models.stream()
                .filter(m -> m.getKey().equals(key))
                .findFirst()
                .orElse(null);
    }

    private String loadSystemPrompt() {
        try {
            return new String(getClass().getClassLoader()
                    .getResourceAsStream("prompts/ops-assistant-system.txt")
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("System Prompt 加载失败，请检查 resources/prompts/ops-assistant-system.txt", e);
        }
    }

    private String readPersisted() {
        try {
            if (Files.exists(ACTIVE_MODEL_FILE)) {
                return Files.readString(ACTIVE_MODEL_FILE).trim();
            }
        } catch (IOException e) {
            log.warn("[ModelManager] 读取持久化激活模型失败", e);
        }
        return null;
    }

    private void writePersisted(String key) {
        try {
            Files.writeString(ACTIVE_MODEL_FILE, key);
        } catch (IOException e) {
            log.warn("[ModelManager] 持久化激活模型失败", e);
        }
    }
}
