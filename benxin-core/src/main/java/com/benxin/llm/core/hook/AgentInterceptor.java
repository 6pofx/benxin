package com.benxin.llm.core.hook;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;

/**
 * 拦截器：在 Agent 运行、模型调用、工具执行三个层级上埋的可替换钩子。
 *
 * <p>日志、脱敏、限流、计费、注入额外系统提示、缓存模型响应、给工具打补丁，
 * 全部可以在这里完成，而不必修改 Loop。用 {@code @LlmGuard} 注解或直接注册 bean 即可生效。</p>
 */
public interface AgentInterceptor {

    /** 执行顺序，值越小越靠外（越早进入、越晚退出）。 */
    default int order() {
        return 0;
    }

    /** 是否作用于指定 Agent。 */
    default boolean supports(String agentName) {
        return true;
    }

    default void beforeAgent(AgentInvocation invocation) {
    }

    default void afterAgent(AgentInvocation invocation, AgentResult result) {
    }

    default void onError(AgentInvocation invocation, Throwable error) {
    }

    /** 改写将要下发的请求（返回 null 表示不改写）。 */
    default ChatRequest beforeModel(AgentInvocation invocation, ChatRequest request) {
        return request;
    }

    /** 改写模型响应（返回 null 表示不改写）。 */
    default ChatResponse afterModel(AgentInvocation invocation, ChatResponse response) {
        return response;
    }

    /**
     * 工具执行前拦截。
     *
     * @return {@code null} 表示放行；返回非 null 则短路，直接使用该结果
     */
    default ToolResult beforeTool(ToolInvocation invocation) {
        return null;
    }

    default void afterTool(ToolInvocation invocation, ToolResult result) {
    }
}