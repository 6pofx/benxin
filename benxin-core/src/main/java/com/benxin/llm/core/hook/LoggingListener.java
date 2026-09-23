package com.benxin.llm.core.hook;

import com.benxin.llm.core.agent.AgentInvocation;
import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.tool.ToolInvocation;
import com.benxin.llm.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 把 Agent 运行过程打到日志里，开箱即可观测。 */
public class LoggingListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingListener.class);

    private final boolean logDeltas;

    public LoggingListener() {
        this(false);
    }

    public LoggingListener(boolean logDeltas) {
        this.logDeltas = logDeltas;
    }

    @Override
    public void onAgentStart(AgentInvocation invocation) {
        log.info("[benxin] Agent [{}] 开始，会话={}，loop={}，输入长度={}",
                invocation.agentName(), invocation.sessionId(),
                invocation.spec() == null ? "-" : invocation.spec().loop(),
                invocation.input() == null ? 0 : invocation.input().length());
    }

    @Override
    public void onStepStart(String agentName, int step) {
        log.debug("[benxin] [{}] 第 {} 步", agentName, step);
    }

    @Override
    public void onTextDelta(String delta) {
        if (logDeltas) {
            log.info("[benxin] 增量: {}", delta);
        }
    }

    @Override
    public void onToolCall(ToolInvocation invocation) {
        log.info("[benxin] 调用工具 {} 参数={}", invocation.toolName(),
                com.benxin.llm.core.util.Json.abbreviate(
                        com.benxin.llm.core.util.Json.writeQuietly(invocation.arguments())));
    }

    @Override
    public void onToolResult(ToolInvocation invocation, ToolResult result) {
        if (result.error()) {
            log.warn("[benxin] 工具 {} 执行失败: {}", invocation.toolName(),
                    com.benxin.llm.core.util.Json.abbreviate(result.content()));
        } else {
            log.info("[benxin] 工具 {} 返回 {} 字符", invocation.toolName(), result.content().length());
        }
    }

    @Override
    public void onStepEnd(String agentName, int step, ChatResponse response) {
        log.debug("[benxin] [{}] 第 {} 步结束，原因={}，用量={}/{}", agentName, step,
                response.finishReason(), response.usage().inputTokens(), response.usage().outputTokens());
    }

    @Override
    public void onSubAgentStart(String parentAgent, String childAgent, String prompt) {
        log.info("[benxin] 子代理 {} -> {} 启动", parentAgent, childAgent);
    }

    @Override
    public void onSubAgentEnd(String parentAgent, String childAgent, AgentResult result) {
        log.info("[benxin] 子代理 {} -> {} 结束，{} 步", parentAgent, childAgent, result.steps());
    }

    @Override
    public void onAgentEnd(AgentResult result) {
        log.info("[benxin] Agent [{}] 结束，{} 步，{} ms，token {}/{}", result.agentName(), result.steps(),
                result.durationMillis(), result.usage().inputTokens(), result.usage().outputTokens());
    }

    @Override
    public void onError(Throwable error) {
        log.error("[benxin] Agent 运行出错: {}", error.toString(), error);
    }
}