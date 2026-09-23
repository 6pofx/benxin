package com.benxin.llm.spring;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentBuilder;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把 {@code @LlmAgent} 注解 + {@code llm.*} 配置 + 容器里的各种协作组件，
 * 组装成一个可运行的 {@link Agent}。
 *
 * <p>这是"声明式"与"可替换"的交汇点：注解只描述意图，真正的组件（模型、Loop、工具、
 * 上下文管理器、沙箱……）全部来自容器 —— 因此用户替换任意一个 bean，所有声明式 Agent
 * 都会自动用上新的实现。</p>
 */
public class LlmAgentFactory implements BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(LlmAgentFactory.class);

    private final LlmProperties properties;
    private final ModelRegistry modelRegistry;
    private final LoopRegistry loopRegistry;
    private final LlmToolCatalog toolCatalog;
    private final ContextManager contextManager;
    private final MemoryStore memoryStore;
    private final SystemPromptProvider systemPromptProvider;
    private final ToolSandbox sandbox;
    private final ApprovalHandler approvalHandler;
    private final AgentRegistry agentRegistry;
    private final ObjectProvider<AgentInterceptor> interceptorProvider;
    private final ObjectProvider<AgentListener> listenerProvider;

    private BeanFactory beanFactory;

    public LlmAgentFactory(LlmProperties properties,
                           ModelRegistry modelRegistry,
                           LoopRegistry loopRegistry,
                           LlmToolCatalog toolCatalog,
                           ContextManager contextManager,
                           MemoryStore memoryStore,
                           SystemPromptProvider systemPromptProvider,
                           ToolSandbox sandbox,
                           ApprovalHandler approvalHandler,
                           AgentRegistry agentRegistry,
                           ObjectProvider<AgentInterceptor> interceptorProvider,
                           ObjectProvider<AgentListener> listenerProvider) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.toolCatalog = toolCatalog;
        this.contextManager = contextManager;
        this.memoryStore = memoryStore;
        this.systemPromptProvider = systemPromptProvider;
        this.sandbox = sandbox;
        this.approvalHandler = approvalHandler;
        this.agentRegistry = agentRegistry;
        this.interceptorProvider = interceptorProvider;
        this.listenerProvider = listenerProvider;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    // ------------------------------------------------------------------
    // 注解 -> AgentSpec
    // ------------------------------------------------------------------

    /** 把接口上的 {@code @LlmAgent} 翻译成 {@link AgentSpec}，空缺项回落到 {@code llm.agent.*}。 */
    public AgentSpec specOf(Class<?> agentInterface) {
        LlmAgent annotation = agentInterface.getAnnotation(LlmAgent.class);
        if (annotation == null) {
            throw new LlmAgentInvocationException(
                    "接口 " + agentInterface.getName() + " 上没有 @LlmAgent 注解");
        }
        LlmProperties.AgentProperties defaults = properties.getAgent();

        String name = firstNonBlank(annotation.value(), annotation.name());
        if (name == null) {
            name = decapitalize(agentInterface.getSimpleName());
        }

        String prompt = firstNonBlank(annotation.systemPrompt(), defaults.getSystemPrompt());
        String loop = firstNonBlank(annotation.loop(), defaults.getDefaultLoop());
        if (loop != null && !loopRegistry.contains(loop)) {
            log.warn("[benxin] Agent [{}] 指定的 Loop [{}] 未注册，可用: {}；将回退到默认 Loop",
                    name, loop, loopRegistry.names());
            loop = null;
        }

        return AgentSpec.builder()
                .name(name)
                .model(blankToNull(annotation.model()))
                .loop(loop)
                .systemPrompt(prompt)
                .maxSteps(annotation.maxSteps() > 0 ? annotation.maxSteps() : defaults.getMaxSteps())
                .stream(annotation.stream() || defaults.isStream())
                .memory(annotation.memory() && defaults.isMemory())
                .temperature(annotation.temperature() >= 0 ? annotation.temperature() : defaults.getTemperature())
                .maxTokens(annotation.maxTokens() > 0 ? annotation.maxTokens() : defaults.getMaxTokens())
                .subAgents(Arrays.asList(annotation.subAgents()))
                .metadata("interface", agentInterface.getName())
                .build();
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    /** 按接口构建 Agent 与它的工具集。 */
    public Agent createFromInterface(Class<?> agentInterface) {
        LlmAgent annotation = agentInterface.getAnnotation(LlmAgent.class);
        AgentSpec spec = specOf(agentInterface);
        return create(spec, resolveTools(annotation, spec.name()));
    }

    /** 用一个现成的 {@link AgentSpec} 构建 Agent（工具取全局工具集）。 */
    public Agent create(AgentSpec spec) {
        return create(spec, toolCatalog.global());
    }

    /** 完整装配。 */
    public Agent create(AgentSpec spec, ToolRegistry tools) {
        LlmProperties.AgentProperties defaults = properties.getAgent();
        int maxSteps = Math.min(Math.max(1, spec.maxSteps()), Math.max(1, defaults.getHardMaxSteps()));

        AgentBuilder builder = Agent.builder(spec.name())
                .spec(spec.toBuilder().maxSteps(maxSteps).build())
                .modelRegistry(modelRegistry)
                .loopRegistry(loopRegistry)
                .toolRegistry(tools)
                .contextManager(contextManager)
                .memoryStore(memoryStore)
                .systemPromptProvider(systemPromptProvider)
                .sandbox(sandbox)
                .approvalHandler(approvalHandler)
                .approvalPolicy(ApprovalPolicy.from(defaults.getApprovalPolicy()))
                .interceptors(interceptorProvider.orderedStream().toList())
                .listeners(listenerProvider.orderedStream().toList());

        if (spec.model() != null) {
            builder.model(spec.model());
        }
        if (spec.loop() != null) {
            builder.loop(spec.loop());
        }

        // 子代理用惰性壳注册，避免 A ⇄ B 的构建环
        for (String subAgent : spec.subAgents()) {
            builder.subAgent(subAgent, new LazyAgent(subAgent, () -> agentRegistry.get(subAgent)));
        }

        Agent agent = builder.build();
        log.info("[benxin] 装配 Agent [{}]：loop={}，model={}，工具={}，最大步数={}",
                agent.name(), agent.loop().name(), agent.model().name(), agent.tools().names(), maxSteps);
        return agent;
    }

    /** 工具解析：注解里显式列出的类/名字优先；两者都为空时使用全局工具表。 */
    private ToolRegistry resolveTools(LlmAgent annotation, String agentName) {
        if (annotation == null) {
            return toolCatalog.global();
        }
        boolean hasClasses = annotation.tools().length > 0;
        boolean hasNames = annotation.toolNames().length > 0;
        if (!hasClasses && !hasNames) {
            return toolCatalog.global();
        }
        List<ToolRegistry> parts = new ArrayList<>();
        if (hasClasses) {
            parts.add(toolCatalog.forClasses(annotation.tools()));
        }
        if (hasNames) {
            parts.add(toolCatalog.forNames(annotation.toolNames(), agentName));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return ToolRegistry.composite(parts.toArray(new ToolRegistry[0]));
    }

    /** 实例化注解上声明的拦截器：优先取容器里的 bean，取不到再直接 new。 */
    public AgentInterceptor instantiateInterceptor(Class<?> type) {
        if (beanFactory != null) {
            try {
                return (AgentInterceptor) beanFactory.getBean(type);
            } catch (BeansException ignored) {
                // 未交给容器管理，走下面的直接实例化
            }
        }
        try {
            return (AgentInterceptor) org.springframework.beans.BeanUtils.instantiateClass(type);
        } catch (RuntimeException e) {
            throw new LlmAgentInvocationException("无法实例化拦截器 " + type.getName(), e);
        }
    }

    public LlmProperties properties() {
        return properties;
    }

    public ModelRegistry modelRegistry() {
        return modelRegistry;
    }

    public LoopRegistry loopRegistry() {
        return loopRegistry;
    }

    public LlmToolCatalog toolCatalog() {
        return toolCatalog;
    }

    // ------------------------------------------------------------------

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}