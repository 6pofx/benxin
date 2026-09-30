package com.benxin.llm.core.agent;

import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.hook.CompositeListener;
import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.DefaultToolRegistry;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolRegistry;
import com.benxin.llm.core.tool.ToolScanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Agent 装配器。每一项协作组件都可以替换，未显式提供的使用默认实现，
 * 这正是"一切皆可插拔"在 API 层面的直观体现。
 */
public class AgentBuilder {

    AgentSpec.Builder spec = AgentSpec.builder();
    LlmModel model;
    ModelRegistry modelRegistry;
    String modelName;
    AgentLoop loop;
    LoopRegistry loopRegistry;
    String loopName;
    ToolRegistry toolRegistry = new DefaultToolRegistry();
    ContextManager contextManager = new DefaultContextManager();
    MemoryStore memoryStore = new InMemoryMemoryStore();
    SystemPromptProvider systemPromptProvider = new TemplateSystemPromptProvider();
    final List<AgentInterceptor> interceptors = new ArrayList<>();
    final List<AgentListener> listeners = new ArrayList<>();
    ToolSandbox sandbox = ToolSandbox.permissive();
    ApprovalHandler approvalHandler = ApprovalHandler.autoApprove();
    ApprovalPolicy approvalPolicy = ApprovalPolicy.ON_REQUEST;
    final Map<String, Agent> subAgents = new LinkedHashMap<>();
    ExecutorService toolExecutor;
    boolean autoScanTools = true;
    /** 防呆硬上限；0 表示不夹取。 */
    int hardMaxSteps;

    public AgentBuilder name(String name) {
        spec.name(name);
        return this;
    }

    public AgentBuilder spec(AgentSpec spec) {
        this.spec = spec.toBuilder();
        return this;
    }

    public AgentBuilder model(LlmModel model) {
        this.model = model;
        return this;
    }

    public AgentBuilder model(String name) {
        this.modelName = name;
        return this;
    }

    public AgentBuilder modelRegistry(ModelRegistry registry) {
        this.modelRegistry = registry;
        return this;
    }

    public AgentBuilder loop(AgentLoop loop) {
        this.loop = loop;
        return this;
    }

    public AgentBuilder loop(String name) {
        this.loopName = name;
        return this;
    }

    public AgentBuilder loopRegistry(LoopRegistry registry) {
        this.loopRegistry = registry;
        return this;
    }

    public AgentBuilder toolRegistry(ToolRegistry registry) {
        this.toolRegistry = registry == null ? new DefaultToolRegistry() : registry;
        return this;
    }

    public AgentBuilder tool(ToolCallback callback) {
        this.toolRegistry.register(callback);
        return this;
    }

    public AgentBuilder tools(List<? extends ToolCallback> callbacks) {
        if (callbacks != null) {
            callbacks.forEach(this.toolRegistry::register);
        }
        return this;
    }

    /** 扫描一个对象上所有 {@code @LlmTool} 方法并注册为工具。 */
    public AgentBuilder tools(Object bean) {
        tools(ToolScanner.scan(bean));
        return this;
    }

    public AgentBuilder contextManager(ContextManager contextManager) {
        this.contextManager = contextManager == null ? new DefaultContextManager() : contextManager;
        return this;
    }

    public AgentBuilder memoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore == null ? new InMemoryMemoryStore() : memoryStore;
        return this;
    }

    public AgentBuilder systemPromptProvider(SystemPromptProvider provider) {
        this.systemPromptProvider = provider == null ? new TemplateSystemPromptProvider() : provider;
        return this;
    }

    public AgentBuilder systemPrompt(String prompt) {
        spec.systemPrompt(prompt);
        return this;
    }

    public AgentBuilder maxSteps(int maxSteps) {
        spec.maxSteps(maxSteps);
        return this;
    }

    /**
     * 防呆硬上限：无论 {@link #maxSteps(int)} 写了多少，最终都不会超过它。
     *
     * <p>夹取下沉到装配层，是为了让"声明式（{@code @LlmAgent}）"与"程序化 {@code Agent.builder()}"
     * 两条路径共用同一处逻辑 —— 以前它写在 Spring 侧的装配器里，只有注解路径拿得到这道安全阀。
     * 默认不夹取（{@code 0}）：程序化装配里的 {@code maxSteps} 是代码里的显式意图，
     * 需要防呆的装配方显式调用本方法（Spring 侧由 {@code LlmAgentFactory} 从
     * {@code llm.agent.hard-max-steps} 传入）。</p>
     *
     * @param value {@code <= 0} 表示不夹取
     */
    public AgentBuilder hardMaxSteps(int value) {
        this.hardMaxSteps = Math.max(0, value);
        return this;
    }

    /** 当前生效的防呆硬上限；{@code 0} 表示不夹取。 */
    public int hardMaxSteps() {
        return hardMaxSteps;
    }

    public AgentBuilder stream(boolean stream) {
        spec.stream(stream);
        return this;
    }

    public AgentBuilder memory(boolean memory) {
        spec.memory(memory);
        return this;
    }

    public AgentBuilder temperature(double temperature) {
        spec.temperature(temperature);
        return this;
    }

    public AgentBuilder maxTokens(int maxTokens) {
        spec.maxTokens(maxTokens);
        return this;
    }

    public AgentBuilder subAgent(String name) {
        spec.subAgent(name);
        return this;
    }

    public AgentBuilder subAgents(List<String> names) {
        spec.subAgents(names);
        return this;
    }

    /** 注册一个可被本 Agent 派生的子代理实例。 */
    public AgentBuilder subAgent(String name, Agent agent) {
        if (name != null && agent != null) {
            this.subAgents.put(name, agent);
            spec.subAgent(name);
        }
        return this;
    }

    public AgentBuilder interceptor(AgentInterceptor interceptor) {
        if (interceptor != null) {
            this.interceptors.add(interceptor);
        }
        return this;
    }

    public AgentBuilder interceptors(List<? extends AgentInterceptor> values) {
        if (values != null) {
            values.forEach(this::interceptor);
        }
        return this;
    }

    public AgentBuilder listener(AgentListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
        return this;
    }

    public AgentBuilder listeners(List<? extends AgentListener> values) {
        if (values != null) {
            values.forEach(this::listener);
        }
        return this;
    }

    public AgentBuilder sandbox(ToolSandbox sandbox) {
        this.sandbox = sandbox == null ? ToolSandbox.permissive() : sandbox;
        return this;
    }

    public AgentBuilder approvalHandler(ApprovalHandler handler) {
        this.approvalHandler = handler == null ? ApprovalHandler.autoApprove() : handler;
        return this;
    }

    public AgentBuilder approvalPolicy(ApprovalPolicy policy) {
        this.approvalPolicy = policy == null ? ApprovalPolicy.ON_REQUEST : policy;
        return this;
    }

    public AgentBuilder toolExecutor(ExecutorService executor) {
        this.toolExecutor = executor;
        return this;
    }

    public AgentBuilder metadata(String key, Object value) {
        spec.metadata(key, value);
        return this;
    }

    public Agent build() {
        Objects.requireNonNull(spec, "spec");
        // 唯一一处硬上限夹取：两条装配路径都从这里过
        if (hardMaxSteps > 0 && spec.build().maxSteps() > hardMaxSteps) {
            spec.maxSteps(hardMaxSteps);
        }
        interceptors.sort(Comparator.comparingInt(AgentInterceptor::order));
        AgentListener composite = CompositeListener.of(listeners);
        return new DefaultAgent(this, composite);
    }
}