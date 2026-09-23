package com.benxin.llm.spring;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentBuilder;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSession;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.tool.ToolRegistry;

import java.util.List;
import java.util.function.Supplier;

/**
 * 延迟构建的 Agent 代理。
 *
 * <p>存在的唯一理由：子代理关系可以是环状的（A 派生 B，B 也能派生 A），
 * 而 Agent 是不可变对象、必须在构建时拿到子代理实例。用一层惰性壳把"解析"推迟到
 * 第一次真正调用，环就自然解开了。</p>
 */
final class LazyAgent implements Agent {

    private final String name;
    private final Supplier<Agent> supplier;
    private volatile Agent delegate;

    LazyAgent(String name, Supplier<Agent> supplier) {
        this.name = name;
        this.supplier = supplier;
    }

    private Agent target() {
        Agent current = delegate;
        if (current == null) {
            synchronized (this) {
                current = delegate;
                if (current == null) {
                    current = supplier.get();
                    if (current == null) {
                        throw new IllegalStateException("Agent [" + name + "] 解析失败");
                    }
                    delegate = current;
                }
            }
        }
        return current;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentSpec spec() {
        return target().spec();
    }

    @Override
    public LlmModel model() {
        return target().model();
    }

    @Override
    public AgentLoop loop() {
        return target().loop();
    }

    @Override
    public ToolRegistry tools() {
        return target().tools();
    }

    @Override
    public AgentResult call(String input) {
        return target().call(input);
    }

    @Override
    public AgentResult call(String input, LlmStreamHandler stream) {
        return target().call(input, stream);
    }

    @Override
    public AgentResult call(String input, String sessionId) {
        return target().call(input, sessionId);
    }

    @Override
    public AgentResult call(String input, String sessionId, LlmStreamHandler stream) {
        return target().call(input, sessionId, stream);
    }

    @Override
    public AgentResult call(List<ChatMessage> messages) {
        return target().call(messages);
    }

    @Override
    public AgentResult call(String sessionId, List<ChatMessage> messages,
                            java.util.Map<String, Object> attributes, LlmStreamHandler stream) {
        return target().call(sessionId, messages, attributes, stream);
    }

    @Override
    public AgentSession session() {
        return target().session();
    }

    @Override
    public AgentSession session(String sessionId) {
        return target().session(sessionId);
    }

    @Override
    public List<ChatMessage> history(String sessionId) {
        return target().history(sessionId);
    }

    @Override
    public void clearHistory(String sessionId) {
        target().clearHistory(sessionId);
    }

    @Override
    public AgentBuilder toBuilder() {
        return target().toBuilder();
    }

    @Override
    public String toString() {
        return "LazyAgent(" + name + ")";
    }
}