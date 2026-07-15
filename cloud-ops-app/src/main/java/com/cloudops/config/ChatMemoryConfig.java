package com.cloudops.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatMemory 配置 — 多轮对话记忆（MySQL 持久化版）
 *
 * 双防线设计：
 *   1. MessageWindowChatMemory 框架层滑动窗口（maxMessages=20）
 *   2. MysqlChatMemoryStore MySQL 层二次兜底（超过 20 条截断）
 *
 * 改动：加了一行 .chatMemoryStore(chatMemoryStore)，从内存版切换到持久化版。
 */
@Configuration
@RequiredArgsConstructor
public class ChatMemoryConfig {

    private static final int MAX_MESSAGES = 50;
    private final ChatMemoryStore chatMemoryStore;

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(MAX_MESSAGES)
                .chatMemoryStore(chatMemoryStore)  // ← MySQL 持久化
                .build();
    }
}
