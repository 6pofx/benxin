package com.benxin.llm.core.agent;

import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.DefaultLoopContext;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.LoopResult;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认 Agent 实现：负责把"一次调用"编排成"一次 Loop 运行"。
 *
 * <p>职责边界刻意保持狭窄 —— 真正的智能在 {@link AgentLoop} 里，
 * 本类只做装配、记忆读写、拦截器/监听器广播与结果封装。</p>
 */
public class DefaultAgent implements Agent {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    /** 工具执行用的共享线程池（守护线程，随 JVM 退出）。 */
    static final ExecutorService SHARED_TOOL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "benxin-tool-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final AgentSpec spec;
    private final LlmModel model;
    private final AgentLoop loop;
    private final ToolRegistry tools;
    private final ContextManager contextManager;
    private final MemoryStore memoryStore;
    private final SystemPromptProvider systemPromptProvider;
    private final List<AgentInterceptor> interceptors;
    private final AgentListener listener;
    private final ToolSandbox sandbox;
    private final ApprovalHandler approvalHandler;
    private final ApprovalPolicy approvalPolicy;
    private final Map<String, Agent> subAgents;
    private final ExecutorService toolExecutor;
    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;

    DefaultAgent(AgentBuilder b, AgentListener listener) {
        this.modelRegistry = b.modelRegistry == null ? new ModelRegistry() : b.modelRegistry;
        this.loopRegistry = b.loopRegistry == null ? new LoopRegistry() : b.loopRegistry;

        LlmModel resolvedModel = b.model;
        if (resolvedModel == null && b.modelName != null) {
            resolvedModel = this.modelRegistry.get(b.modelName);
        }
        if (resolvedModel == null) {
            resolvedModel = this.modelRegistry.defaultModel();
        }

        AgentLoop resolvedLoop = b.loop;
        if (resolvedLoop == null && b.loopName != null) {
            resolvedLoop = this.loopRegistry.get(b.loopName);
        }
        if (resolvedLoop == null) {
            resolvedLoop = this.loopRegistry.defaultLoop();
        }

        this.spec = b.spec
                .model(resolvedModel.name())
                .loop(resolvedLoop.name())
                .toolNames(new ArrayList<>(b.toolRegistry.names()))
                .build();
        this.model = resolvedModel;
        this.loop = resolvedLoop;
        this.tools = b.toolRegistry;
        this.contextManager = b.contextManager;
        this.memoryStore = b.memoryStore;
        this.systemPromptProvider = b.systemPromptProvider;
        this.interceptors = List.copyOf(b.interceptors);
        this.listener = listener == null ? AgentListener.noop() : listener;
        this.sandbox = b.sandbox;
        this.approvalHandler = b.approvalHandler;
        this.approvalPolicy = b.approvalPolicy;
        this.subAgents = new LinkedHashMap<>(b.subAgents);
        this.toolExecutor = b.toolExecutor == null ? SHARED_TOOL_EXECUTOR : b.toolExecutor;
    }

    // ---------- 基本访问 ----------

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public AgentSpec spec() {
        return spec;
    }

    @Override
    public LlmModel model() {
        return model;
    }

    @Override
    public AgentLoop loop() {
        return loop;
    }

    @Override
    public ToolRegistry tools() {
        return tools;
    }

    public ContextManager contextManager() {
        return contextManager;
    }

    public MemoryStore memoryStore() {
        return memoryStore;
    }

    public SystemPromptProvider systemPromptProvider() {
        return systemPromptProvider;
    }

    public ToolSandbox sandbox() {
        return sandbox;
    }

    public ApprovalHandler approvalHandler() {
        return approvalHandler;
    }

    public ApprovalPolicy approvalPolicy() {
        return approvalPolicy;
    }

    public ExecutorService toolExecutor() {
        return toolExecutor;
    }

    public AgentListener listener() {
        return listener;
    }

    public List<AgentInterceptor> activeInterceptors() {
        List<AgentInterceptor> active = new ArrayList<>();
        for (AgentInterceptor interceptor : interceptors) {
            if (interceptor.supports(name())) {
                active.add(interceptor);
            }
        }
        return active;
    }

    Agent childAgent(String childName) {
        return subAgents.get(childName);
    }

    // ---------- 调用 ----------

    @Override
    public AgentResult call(String input) {
        return call(input, defaultSessionId(), null);
    }

    @Override
    public AgentResult call(String input, LlmStreamHandler stream) {
        return call(input, defaultSessionId(), stream);
    }

    @Override
    public AgentResult call(String input, String sessionId) {
        return call(input, sessionId, null);
    }

    @Override
    public AgentResult call(String input, String sessionId, LlmStreamHandler stream) {
        String sid = sessionId == null || sessionId.isBlank() ? defaultSessionId() : sessionId;
        List<ChatMessage> history = spec.memory()
                ? new ArrayList<>(memoryStore.load(sid))
                : new ArrayList<>();
        if (input != null && !input.isBlank()) {
            history.add(ChatMessage.user(input));
        }
        return run(sid, input, history, null, stream, spec.memory());
    }

    @Override
    public AgentResult call(List<ChatMessage> messages) {
        return run(defaultSessionId(), null,
                new ArrayList<>(messages == null ? List.of() : messages), null, null, false);
    }

    @Override
    public AgentResult call(String sessionId, List<ChatMessage> messages,
                            Map<String, Object> seedAttributes, LlmStreamHandler stream) {
        String sid = sessionId == null || sessionId.isBlank() ? defaultSessionId() : sessionId;
        // 开启记忆时必须续接历史并写回 —— 声明式接口的 @Memory 参数走的正是这条路径，
        // 如果这里绕过记忆，"多轮对话"的承诺就会静默失效。
        boolean persist = spec.memory();
        List<ChatMessage> history = persist ? new ArrayList<>(memoryStore.load(sid)) : new ArrayList<>();
        if (messages != null) {
            history.addAll(messages);
        }
        String input = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() == com.benxin.llm.core.message.Role.USER) {
                input = history.get(i).text();
                break;
            }
        }
        return run(sid, input, history, seedAttributes, stream, persist);
    }

    private String defaultSessionId() {
        return name() + "-default";
    }

    private AgentResult run(String sessionId, String input, List<ChatMessage> history,
                            Map<String, Object> seedAttributes, LlmStreamHandler stream, boolean persist) {
        long startNanos = System.nanoTime();
        Map<String, Object> attributes = seedAttributes == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(seedAttributes);
        AgentInvocation invocation = new AgentInvocation(name(), sessionId, spec, input, history, attributes);
        List<AgentInterceptor> active = activeInterceptors();

        listener.onAgentStart(invocation);
        try {
            for (AgentInterceptor interceptor : active) {
                interceptor.beforeAgent(invocation);
            }

            // 声明式接口的方法级 @SystemPrompt 通过属性下发，优先级高于 Agent 级提示词
            Object override = attributes.get(AgentAttributes.SYSTEM_PROMPT);
            String systemPrompt = override == null
                    ? systemPromptProvider.systemPrompt(spec, attributes)
                    : String.valueOf(override);
            DefaultLoopContext context = new DefaultLoopContext(this, invocation, history,
                    systemPrompt, stream, active);
            LoopResult loopResult = loop.run(context);

            if (persist) {
                memoryStore.save(sessionId, context.messages());
            }
            long duration = (System.nanoTime() - startNanos) / 1_000_000;
            AgentResult result = AgentResult.from(name(), sessionId, model.name(), loopResult, duration,
                    spec.toBuilder().metadata(attributes).build());

            for (AgentInterceptor interceptor : active) {
                interceptor.afterAgent(invocation, result);
            }
            listener.onAgentEnd(result);
            return result;
        } catch (RuntimeException | Error e) {
            for (AgentInterceptor interceptor : active) {
                safely(() -> interceptor.onError(invocation, e));
            }
            listener.onError(e);
            throw e;
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 拦截器的错误回调失败不应掩盖原始异常
        }
    }

    /** 派生子代理（供支持子代理的 Loop 使用）。 */
    public AgentResult spawn(String childName, String prompt, Map<String, Object> extraAttributes,
                      AgentListener parentListener, String parentName) {
        Agent child = subAgents.get(childName);
        if (child == null) {
            throw new IllegalArgumentException("未注册子代理 [" + childName + "]，可用: " + subAgents.keySet());
        }
        parentListener.onSubAgentStart(parentName, childName, prompt);
        AgentResult result = child.call(prompt);
        parentListener.onSubAgentEnd(parentName, childName, result);
        return result;
    }

    // ---------- 会话 ----------

    @Override
    public AgentSession session() {
        return new AgentSession(java.util.UUID.randomUUID().toString(), this);
    }

    @Override
    public AgentSession session(String sessionId) {
        return new AgentSession(sessionId, this);
    }

    @Override
    public List<ChatMessage> history(String sessionId) {
        return memoryStore.load(sessionId);
    }

    @Override
    public void clearHistory(String sessionId) {
        memoryStore.clear(sessionId);
    }

    @Override
    public AgentBuilder toBuilder() {
        AgentBuilder b = new AgentBuilder()
                .spec(spec)
                .model(model)
                .loop(loop)
                .modelRegistry(modelRegistry)
                .loopRegistry(loopRegistry)
                .toolRegistry(tools)
                .contextManager(contextManager)
                .memoryStore(memoryStore)
                .systemPromptProvider(systemPromptProvider)
                .sandbox(sandbox)
                .approvalHandler(approvalHandler)
                .approvalPolicy(approvalPolicy)
                .toolExecutor(toolExecutor);
        interceptors.forEach(b::interceptor);
        subAgents.forEach(b::subAgent);
        return b;
    }

    @Override
    public String toString() {
        return "Agent[" + name() + " → " + loop.name() + " / " + model.name() + " / 工具 " + tools.names() + "]";
    }
}