package com.benxin.llm.core.memory;

import com.benxin.llm.core.message.ChatMessage;

import java.util.List;

/**
 * 会话记忆。默认内存实现，替换为 Redis / JDBC / 向量库只需提供同类型 bean。
 */
public interface MemoryStore {

    List<ChatMessage> load(String sessionId);

    void save(String sessionId, List<ChatMessage> history);

    void clear(String sessionId);

    default boolean exists(String sessionId) {
        return !load(sessionId).isEmpty();
    }

    static MemoryStore inMemory() {
        return new InMemoryMemoryStore();
    }

    static MemoryStore disabled() {
        return new MemoryStore() {
            @Override
            public List<ChatMessage> load(String sessionId) {
                return List.of();
            }

            @Override
            public void save(String sessionId, List<ChatMessage> history) {
                // 不持久化
            }

            @Override
            public void clear(String sessionId) {
                // 无状态
            }
        };
    }
}