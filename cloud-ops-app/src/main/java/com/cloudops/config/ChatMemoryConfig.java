package com.cloudops.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatMemory 配置 — 多轮对话记忆
 *
 * 设计思路（对标联通 Redis 会话保持）：
 *   联通用 Redis 存用户会话状态实现多系统共享；
 *   这里用 ChatMemoryProvider 按 userId 隔离会话，内存版开发够用，
 *   生产可换 ChatMemoryStore 接口的 MySQL/Redis 实现。
 *
 * 工作原理：
 *   1. Agent 调 chat(userId, message) 时，@MemoryId 传入 userId
 *   2. ChatMemoryProvider.get(userId) 按userId取或建ChatMemory
 *   3. 同一userId的消息进同一ChatMemory，多轮对话有上下文
 *   4. 不同userId的ChatMemory互不干扰
 *
 * 为什么用 MessageWindowChatMemory 而不是 TokenWindowChatMemory：
 *   - MessageWindow 按消息条数截断（maxMessages=20），逻辑简单
 *   - TokenWindow 按token数截断，需要算token，多一次调用
 *   - 排障场景20条够用（10轮问答），面试讲"按token截断更精准，但排障场景消息条数足够"
 *
 * 内存版的风险与演进：
 *   - 风险：重启丢失记忆、多实例不共享
 *   - 演进：实现 ChatMemoryStore 接口的 MySQL/Redis 版即可持久化
 *   - 面试讲法："先内存版跑通，生产换 MySQL Store，按月分表存储"
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 每个会话最多保留 20 条消息（约 10 轮问答）
     * 超过后自动淘汰最早的消息（FIFO）
     */
    private static final int MAX_MESSAGES = 20;

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        // 按需创建：第一次调 get(userId) 时创建，后续复用
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(MAX_MESSAGES)
                .build();
    }
}
