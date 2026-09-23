package com.benxin.llm.core.loop;

import com.benxin.llm.core.agent.AgentResult;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.chat.ChatRequest;
import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.message.ToolUsePart;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Loop 的运行时环境。Loop 需要的所有能力都从这里取，
 * 因此 Loop 实现本身不需要依赖 Spring、不需要知道 Agent 是怎么装配出来的。
 *
 * <p>{@link #callModel} 与 {@link #executeTools} 已经把拦截器、沙箱、审批、
 * 结果截断、事件广播全部处理完，写一个自定义 Loop 通常只需要十几行。</p>
 */
public interface LoopContext {

    // ---------- 身份 ----------

    String agentName();

    String sessionId();

    AgentSpec spec();

    /** 当前步数（从 1 开始）。 */
    int step();

    int maxSteps();

    // ---------- 协作者 ----------

    LlmModel model();

    ToolRegistry tools();

    ContextManager contextManager();

    SystemPromptProvider systemPromptProvider();

    ToolSandbox sandbox();

    ApprovalHandler approvalHandler();

    ApprovalPolicy approvalPolicy();

    List<AgentInterceptor> interceptors();

    AgentListener listener();

    /** 下游流式消费者（可能为 null）。 */
    LlmStreamHandler streamHandler();

    // ---------- 会话状态 ----------

    /** 可变的对话历史（含本轮新增的 user / assistant / tool 消息）。 */
    List<ChatMessage> messages();

    /** 运行期共享属性，Loop 与工具都可读写。 */
    Map<String, Object> attributes();

    /** 本次运行开始时的系统提示词（已渲染）。 */
    String systemPrompt();

    // ---------- 能力 ----------

    /**
     * 调用一次模型：自动应用上下文管理、拦截器改写、流式聚合与用量统计。
     * Loop 只需关心"什么时候调、传什么历史"。
     */
    ChatResponse callModel(List<ChatMessage> history);

    /**
     * 用当前 {@link #messages()} 调一次模型，并把 assistant 响应追加进历史。
     *
     * <p>标准实现（{@code DefaultLoopContext}）会自动完成追加，因此 Loop 里
     * 通常只需关心"什么时候调"。</p>
     */
    default ChatResponse callModel() {
        return callModel(messages());
    }

    /**
     * 执行模型请求的一批工具调用，返回可直接追加进历史的结果消息。
     * 内部完成：沙箱校验 → 审批 → 拦截器 → 执行 → 超时与截断 → 事件广播。
     */
    List<ChatMessage> executeTools(List<ToolUsePart> toolUses);

    /** 直接执行单个工具（不经过模型）。 */
    ToolResult callTool(String name, Map<String, Object> arguments);

    /** 把一条消息追加进历史并同步给监听器。 */
    void append(ChatMessage message);

    /** 派生一个子代理执行独立任务；未配置子代理时抛异常。 */
    default AgentResult spawnSubAgent(String subAgentName, String prompt, Map<String, Object> attributes) {
        throw new UnsupportedOperationException("该 Agent 未配置子代理能力: " + subAgentName);
    }

    /** 是否支持派生子代理。 */
    default boolean supportsSubAgents() {
        return false;
    }

    /** 发出一个自定义事件给监听器。 */
    void emit(String type, Object payload);

    /** 当前 Agent 可用的工具名列表，用于渲染内置提示词。 */
    default List<String> toolNames() {
        return List.copyOf(tools().names());
    }

    /** 把历史交给上下文管理器组装成最终下发请求。 */
    default ChatRequest buildRequest() {
        return ChatRequest.builder()
                .model(spec() == null ? null : spec().model())
                .messages(contextManager().prepare(systemPrompt(), messages(), model()))
                .tools(tools().specs())
                .temperature(spec() == null || spec().temperature() < 0 ? null : spec().temperature())
                .maxTokens(spec() == null || spec().maxTokens() < 0 ? null : spec().maxTokens())
                .build();
    }
}