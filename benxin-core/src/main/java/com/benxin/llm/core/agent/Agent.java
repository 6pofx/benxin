package com.benxin.llm.core.agent;

import com.benxin.llm.core.chat.ChatResponse;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.message.ChatMessage;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.LlmStreamHandler;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * Agent 运行时。由 {@link #builder()} 装配：模型、Loop、工具、上下文、记忆、提示词、
 * 拦截器、监听器、沙箱、审批策略 —— 每一项都可以在构建时替换掉。
 */
public interface Agent {

    String name();

    AgentSpec spec();

    LlmModel model();

    AgentLoop loop();

    ToolRegistry tools();

    // ---------- 调用 ----------

    AgentResult call(String input);

    AgentResult call(String input, LlmStreamHandler stream);

    AgentResult call(String input, String sessionId);

    AgentResult call(String input, String sessionId, LlmStreamHandler stream);

    /** 直接以给定历史运行（不复用记忆，也不写回记忆）。 */
    AgentResult call(List<ChatMessage> messages);

    /**
     * 以给定消息作为本次输入运行，并带上会话属性。
     *
     * <p>声明式接口代理走的就是这条路径：它需要把方法级的系统提示词、{@code @Ctx} 变量
     * 与预填充的 assistant 消息一并带进来。属性里的
     * {@link AgentAttributes#SYSTEM_PROMPT} 会覆盖 Agent 级提示词。</p>
     *
     * <p>当 {@code spec().memory()} 为 true 时，会先装载该会话已有历史、运行结束后再写回，
     * 因此 {@code @Memory} 参数的多轮语义得以成立。</p>
     */
    AgentResult call(String sessionId, List<ChatMessage> messages,
                     Map<String, Object> attributes, LlmStreamHandler stream);

    // ---------- 会话 ----------

    AgentSession session();

    AgentSession session(String sessionId);

    List<ChatMessage> history(String sessionId);

    void clearHistory(String sessionId);

    // ---------- 派生 ----------

    /** 基于当前 Agent 派生出改造后的新实例（换模型、换 Loop、换工具…）。 */
    AgentBuilder toBuilder();

    static AgentBuilder builder() {
        return new AgentBuilder();
    }

    static AgentBuilder builder(String name) {
        return new AgentBuilder().name(name);
    }

    // ---------- 便捷装配入口（供 Spring 之外的裸 Java 使用） ----------

    static AgentBuilder builder(String name, LlmModel model, AgentLoop loop) {
        return new AgentBuilder().name(name).model(model).loop(loop);
    }

    /** 装配所需的协作组件集合，暴露出来便于高级用法直接改写。 */
    interface Runtime {
        ModelRegistry modelRegistry();

        LlmModel resolveModel(String name);

        AgentLoop resolveLoop(String name);

        ContextManager contextManager();

        MemoryStore memoryStore();

        SystemPromptProvider systemPromptProvider();

        List<AgentInterceptor> interceptors();

        AgentListener listener();

        ToolSandbox sandbox();

        ApprovalHandler approvalHandler();

        ApprovalPolicy approvalPolicy();
    }

    /**
     * 一次纯模型调用（不经过 Loop），便于工具内部或测试里直接用。
     */
    default ChatResponse ask(String prompt) {
        return model().chat(com.benxin.llm.core.chat.ChatRequest.builder()
                .message(ChatMessage.user(prompt))
                .build());
    }
}