package com.benxin.llm.spring;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

import java.util.Arrays;
import java.util.Set;

/**
 * 给 {@link AgentInterceptor} 加上 {@code @LlmGuard} 声明的"只对指定 Agent 生效"能力。
 *
 * <p>拦截器本身只暴露 {@code supports(agentName)} 这一个过滤点，而注解是更自然的表达方式；
 * 本类就是两者之间的适配层，让用户既能用注解声明范围，也能在代码里动态判断。</p>
 */
public class SelectiveInterceptor implements AgentInterceptor {

    private final AgentInterceptor delegate;
    private final Set<String> agents;

    public SelectiveInterceptor(AgentInterceptor delegate, LlmGuard guard) {
        this.delegate = delegate;
        this.agents = guard == null ? Set.of() : Set.copyOf(Arrays.asList(guard.agents()));
    }

    @Override
    public int order() {
        return delegate.order();
    }

    @Override
    public boolean supports(String agentName) {
        return (agents.isEmpty() || agents.contains(agentName)) && delegate.supports(agentName);
    }

    @Override
    public void beforeAgent(AgentInvocation invocation) {
        delegate.beforeAgent(invocation);
    }

    @Override
    public void afterAgent(AgentInvocation invocation, AgentResult result) {
        delegate.afterAgent(invocation, result);
    }

    @Override
    public void onError(AgentInvocation invocation, Throwable error) {
        delegate.onError(invocation, error);
    }

    @Override
    public ChatRequest beforeModel(AgentInvocation invocation, ChatRequest request) {
        return delegate.beforeModel(invocation, request);
    }

    @Override
    public ChatResponse afterModel(AgentInvocation invocation, ChatResponse response) {
        return delegate.afterModel(invocation, response);
    }

    @Override
    public ToolResult beforeTool(ToolInvocation invocation) {
        return delegate.beforeTool(invocation);
    }

    @Override
    public void afterTool(ToolInvocation invocation, ToolResult result) {
        delegate.afterTool(invocation, result);
    }

    @Override
    public String toString() {
        return "SelectiveInterceptor(" + delegate + ", agents=" + agents + ")";
    }
}