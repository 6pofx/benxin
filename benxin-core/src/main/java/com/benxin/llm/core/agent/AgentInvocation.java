package com.benxin.llm.core.agent;

import com.benxin.llm.core.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次 Agent 调用的上下文，传给拦截器与监听器。 */
public final class AgentInvocation {

    private final String agentName;
    private final String sessionId;
    private final AgentSpec spec;
    private final String input;
    private final List<ChatMessage> inputMessages;
    private final Map<String, Object> attributes;
    private final long startedAtNanos;

    public AgentInvocation(String agentName, String sessionId, AgentSpec spec, String input,
                           List<ChatMessage> inputMessages, Map<String, Object> attributes) {
        this.agentName = agentName;
        this.sessionId = sessionId;
        this.spec = spec;
        this.input = input;
        this.inputMessages = inputMessages == null ? List.of() : List.copyOf(inputMessages);
        this.attributes = attributes == null ? new LinkedHashMap<>() : attributes;
        this.startedAtNanos = System.nanoTime();
    }

    public String agentName() {
        return agentName;
    }

    public String sessionId() {
        return sessionId;
    }

    public AgentSpec spec() {
        return spec;
    }

    /** 本次调用的用户输入文本（可能为空）。 */
    public String input() {
        return input;
    }

    public List<ChatMessage> inputMessages() {
        return inputMessages;
    }

    /** 可写的运行属性：拦截器可以在这里塞入下游要用的东西。 */
    public Map<String, Object> attributes() {
        return attributes;
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    public List<ChatMessage> mutableInputMessages() {
        return new ArrayList<>(inputMessages);
    }

    public Map<String, Object> snapshotAttributes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}