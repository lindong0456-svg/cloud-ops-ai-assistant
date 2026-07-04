package com.cloudops.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 运维排障 Agent 接口 — LangChain4j @AiService 风格
 *
 * 提供两种调用方式：
 *   1. chat()        — 同步阻塞，等全部生成完返回完整字符串
 *   2. chatStream()   — 流式返回 TokenStream，逐 token 推送
 *
 * ★ 为什么用 TokenStream 而不是 Flux<String>：
 *   TokenStream 是 AiServices 原生支持的流式返回类型，代理内部直接使用
 *   StreamingChatLanguageModel + AiServiceStreamingResponseHandler 逐 token 回调，
 *   不存在 SPI 适配器的兼容性风险。
 *   Controller 层用 Flux.create() 将 TokenStream 桥接到 Flux<String>，
 *   灵活度更高，且不依赖 langchain4j-reactor 的版本行为。
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
     * TokenStream 是 AiServices 原生流式返回类型，Agent 内部的
     * ReAct 工具调用链（思考→工具调用→观察→...→结论）对调用方透明，
     * 只在最终生成结论时才触发 onNext 回调。
     *
     * Controller 层用 Flux.create() 将回调转为 Flux<String>。
     *
     * @param userId  会话ID，用于多轮记忆隔离
     * @param message 用户问题
     * @return TokenStream，调用方注册 onPartialResponse/onCompleteResponse/onError 后调用 start()
     */
    TokenStream chatStream(@MemoryId String userId, @UserMessage String message);
}
