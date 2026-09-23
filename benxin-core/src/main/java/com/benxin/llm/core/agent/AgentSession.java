package com.benxin.llm.core.agent;

import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmStreamHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多轮会话句柄。持有会话 id 与跨轮共享的属性，
 * 历史本身由 {@link MemoryStore} 托管，因此换成 Redis 后多轮自然跨进程可用。
 */
public final class AgentSession {

    private final String id;
    private final Agent agent;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    AgentSession(String id, Agent agent) {
        this.id = id;
        this.agent = agent;
    }

    public String id() {
        return id;
    }

    public Agent agent() {
        return agent;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public AgentResult chat(String input) {
        return agent.call(input, id);
    }

    public AgentResult chat(String input, LlmStreamHandler handler) {
        return agent.call(input, id, handler);
    }

    public List<com.benxin.llm.core.message.ChatMessage> history() {
        return agent.history(id);
    }

    public void clear() {
        agent.clearHistory(id);
    }

    @Override
    public String toString() {
        return "AgentSession(" + agent.name() + "#" + id + ")";
    }
}