package com.benxin.llm.core.hook;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 把多个监听器合成一个，逐个转发；单个监听器抛异常不会影响其它监听器与主流程。 */
public class CompositeListener implements AgentListener {

    private final List<AgentListener> delegates;

    public CompositeListener(List<AgentListener> delegates) {
        this.delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }

    public static AgentListener of(AgentListener... listeners) {
        List<AgentListener> list = new ArrayList<>();
        for (AgentListener listener : listeners) {
            if (listener instanceof CompositeListener composite) {
                list.addAll(composite.delegates);
            } else if (listener != null) {
                list.add(listener);
            }
        }
        return list.isEmpty() ? AgentListener.noop() : new CompositeListener(list);
    }

    public static AgentListener of(List<AgentListener> listeners) {
        return of(listeners.toArray(new AgentListener[0]));
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 监听器故障不得影响主流程
        }
    }

    @Override
    public void onAgentStart(AgentInvocation invocation) {
        delegates.forEach(l -> safely(() -> l.onAgentStart(invocation)));
    }

    @Override
    public void onStepStart(String agentName, int step) {
        delegates.forEach(l -> safely(() -> l.onStepStart(agentName, step)));
    }

    @Override
    public void onTextDelta(String delta) {
        delegates.forEach(l -> safely(() -> l.onTextDelta(delta)));
    }

    @Override
    public void onThinkingDelta(String delta) {
        delegates.forEach(l -> safely(() -> l.onThinkingDelta(delta)));
    }

    @Override
    public void onToolCall(ToolInvocation invocation) {
        delegates.forEach(l -> safely(() -> l.onToolCall(invocation)));
    }

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        delegates.forEach(l -> safely(() -> l.onToolResult(invocation, result)));
    }

    @Override
    public void onStepEnd(String agentName, int step, ChatResponse response) {
        delegates.forEach(l -> safely(() -> l.onStepEnd(agentName, step, response)));
    }

    @Override
    public void onSubAgentStart(String parentAgent, String childAgent, String prompt) {
        delegates.forEach(l -> safely(() -> l.onSubAgentStart(parentAgent, childAgent, prompt)));
    }

    @Override
    public void onSubAgentEnd(String parentAgent, String childAgent, AgentResult result) {
        delegates.forEach(l -> safely(() -> l.onSubAgentEnd(parentAgent, childAgent, result)));
    }

    @Override
    public void onCustomEvent(String type, Object payload) {
        delegates.forEach(l -> safely(() -> l.onCustomEvent(type, payload)));
    }

    @Override
    public void onAgentEnd(AgentResult result) {
        delegates.forEach(l -> safely(() -> l.onAgentEnd(result)));
    }

    @Override
    public void onError(Throwable error) {
        delegates.forEach(l -> safely(() -> l.onError(error)));
    }

    public List<AgentListener> delegates() {
        return delegates;
    }

    @Override
    public String toString() {
        return "CompositeListener" + Arrays.toString(delegates.toArray());
    }
}