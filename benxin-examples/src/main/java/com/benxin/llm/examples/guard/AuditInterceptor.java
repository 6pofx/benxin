package com.benxin.llm.examples.guard;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 拦截器示例：给指定 Agent 加一层审计。
 *
 * <p>{@code @LlmGuard(agents = {"codeReviewer"})} 表示只对代码审查 Agent 生效 ——
 * 注解负责"作用范围"，接口方法负责"做什么"，两者互补。
 * 想给所有 Agent 生效，去掉 {@code agents} 即可。</p>
 */
@Component
@LlmGuard(agents = {"codeReviewer"})
public class AuditInterceptor implements AgentInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void beforeAgent(AgentInvocation invocation) {
        runs.incrementAndGet();
        log.info("[审计] Agent [{}] 开始，会话 {}，输入 {} 字符",
                invocation.agentName(), invocation.sessionId(),
                invocation.input() == null ? 0 : invocation.input().length());
    }

    @Override
    public void afterAgent(AgentInvocation invocation, AgentResult result) {
        log.info("[审计] Agent [{}] 结束：{} 步 / {} ms / {} 个工具调用",
                invocation.agentName(), result.steps(), result.durationMillis(), result.toolCalls().size());
    }

    @Override
    public ChatRequest beforeModel(AgentInvocation invocation, ChatRequest request) {
        // 拦截器可以改写请求：这里示范"给每次调用加上审计标记"
        return request.toBuilder().extra("benxin_audit", true).build();
    }

    @Override
    public ToolResult beforeTool(ToolInvocation invocation) {
        toolCalls.incrementAndGet();
        return null; // 返回 null 表示放行
    }

    public int runs() {
        return runs.get();
    }

    public int toolCalls() {
        return toolCalls.get();
    }
}