package com.benxin.llm.core.hook;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

/**
 * 事件监听器：只读地观察 Agent 的运行过程，适合做前端推送、可观测性埋点、审计留痕。
 *
 * <p>与 {@link AgentInterceptor} 的分工：拦截器可以改变行为，监听器不改行为。</p>
 */
public interface AgentListener {

    default void onAgentStart(AgentInvocation invocation) {
    }

    default void onStepStart(String agentName, int step) {
    }

    default void onTextDelta(String delta) {
    }

    default void onThinkingDelta(String delta) {
    }

    default void onToolCall(ToolInvocation invocation) {
    }

    default void onToolResult(ToolInvocation invocation, ToolResult result) {
    }

    default void onStepEnd(String agentName, int step, ChatResponse response) {
    }

    default void onSubAgentStart(String parentAgent, String childAgent, String prompt) {
    }

    default void onSubAgentEnd(String parentAgent, String childAgent, AgentResult result) {
    }

    /** 工具或循环发出的自定义事件。 */
    default void onCustomEvent(String type, Object payload) {
    }

    default void onAgentEnd(AgentResult result) {
    }

    default void onError(Throwable error) {
    }

    static AgentListener noop() {
        return new AgentListener() {
        };
    }
}