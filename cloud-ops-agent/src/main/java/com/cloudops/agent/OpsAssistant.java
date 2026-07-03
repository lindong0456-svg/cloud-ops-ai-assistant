package com.cloudops.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 运维排障 Agent 接口 — LangChain4j @AiService 风格
 *
 * 提供两种调用方式：
 *   1. chat()        — 同步阻塞，等全部生成完返回完整字符串
 *   2. chatStream()   — 流式返回 Flux<String>，逐 token 推送
 *
 * LangChain4j 原生支持 Flux 返回类型：
 *   接口方法返回 Flux<String> + 绑定 StreamingChatLanguageModel，
 *   AiServices 代理会自动把流式 token 包装成 Flux，不用手动桥接。
 *
 * @MemoryId：多会话隔离，同一个 userId 的对话共享记忆，不同 userId 互不干扰
 * @UserMessage：用户输入，会作为 UserMessage 发给 LLM
 */
public interface OpsAssistant {

    /**
     * 同步排障对话（阻塞，等全部生成完）
     *
     * @param userId  会话ID，用于多轮记忆隔离
     * @param message 用户问题
     * @return Agent 排障结论（完整文本）
     */
    String chat(@MemoryId String userId, @UserMessage String message);

    /**
     * 流式排障对话（逐 token 返回）
     *
     * LangChain4j 原生支持 Flux<String>：
     *   代理内部把 StreamingChatModel 的 token 回调自动转成 Flux，
     *   Controller 层直接 .map() 转 ServerSentEvent 即可，不用 Sinks 桥接。
     *
     * @param userId  会话ID，用于多轮记忆隔离
     * @param message 用户问题
     * @return Flux<String>，每个元素是一个 token
     */
    Flux<String> chatStream(@MemoryId String userId, @UserMessage String message);
}
