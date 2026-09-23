package com.benxin.llm.core.memory;

import com.benxin.llm.core.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存记忆实现。带 LRU 上限，避免长时间运行时无限增长。
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final int maxSessions;
    private final Map<String, List<ChatMessage>> sessions;

    public InMemoryMemoryStore() {
        this(256);
    }

    public InMemoryMemoryStore(int maxSessions) {
        this.maxSessions = Math.max(1, maxSessions);
        this.sessions = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<ChatMessage>> eldest) {
                return size() > InMemoryMemoryStore.this.maxSessions;
            }
        });
    }

    @Override
    public List<ChatMessage> load(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        List<ChatMessage> history = sessions.get(sessionId);
        return history == null ? List.of() : List.copyOf(history);
    }

    @Override
    public void save(String sessionId, List<ChatMessage> history) {
        if (sessionId == null) {
            return;
        }
        sessions.put(sessionId, new ArrayList<>(history == null ? List.of() : history));
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}