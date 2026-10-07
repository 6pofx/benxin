package com.benxin.llm.spring;

import com.benxin.llm.core.agent.Agent;
import com.benxin.llm.core.agent.AgentBuilder;
import com.benxin.llm.core.agent.AgentSpec;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.LlmRetry;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.model.impl.RetryingLlmModel;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ApprovalPolicy;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.RetryingToolCallback;
import com.benxin.llm.core.tool.ToolCallback;
import com.benxin.llm.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 把 {@code @LlmAgent} 注解 + {@code llm.*} 配置 + 容器里的各种协作组件，
 * 组装成一个可运行的 {@link Agent}。
 *
 * <p>这是"声明式"与"可插拔"的交汇点：注解只描述意图，真正的组件（模型、Loop、工具、
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
    private final ObjectProvider<ToolSandbox> sandboxProvider;
    private final ObjectProvider<ApprovalHandler> approvalHandlerProvider;
    private final AgentRegistry agentRegistry;
    private final ObjectProvider<LlmInterceptorChain> interceptorChainProvider;
    private final ObjectProvider<AgentListener> listenerProvider;

    private BeanFactory beanFactory;

    public LlmAgentFactory(LlmProperties properties,
                           ModelRegistry modelRegistry,
                           LoopRegistry loopRegistry,
                           LlmToolCatalog toolCatalog,
                           ContextManager contextManager,
                           MemoryStore memoryStore,
                           SystemPromptProvider systemPromptProvider,
                           ObjectProvider<ToolSandbox> sandboxProvider,
                           ObjectProvider<ApprovalHandler> approvalHandlerProvider,
                           AgentRegistry agentRegistry,
                           ObjectProvider<LlmInterceptorChain> interceptorChainProvider,
                           ObjectProvider<AgentListener> listenerProvider) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.loopRegistry = loopRegistry;
        this.toolCatalog = toolCatalog;
        this.contextManager = contextManager;
        this.memoryStore = memoryStore;
        this.systemPromptProvider = systemPromptProvider;
        this.sandboxProvider = sandboxProvider;
        this.approvalHandlerProvider = approvalHandlerProvider;
        this.agentRegistry = agentRegistry;
        this.interceptorChainProvider = interceptorChainProvider;
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
        ToolRegistry tools = resolveTools(annotation, spec.name());
        RetryPolicy retry = resolveRetryPolicy(agentInterface);
        if (retry != null && retry.includeTools()) {
            tools = withToolRetries(tools, retry.annotation());
        }
        return create(spec, tools, annotationInterceptors(annotation), retry == null ? null : retry.annotation());
    }

    /**
     * 读取 {@code @LlmRetry} 策略。
     *
     * <p>这个注解以前在整个仓库里只有定义、没有任何消费点 —— 按 README 打了它等于没打。
     * 现在它的落点是：<b>模型调用</b>（包一层 {@code RetryingLlmModel}），
     * {@code includeTools = true} 时再加上<b>该 Agent 的全部工具</b>（包 {@code RetryingToolCallback}）。</p>
     *
     * <p>接口上写最直观；写在接口方法上时，取"尝试次数最大"的那条作为整个 Agent 的策略
     * （模型调用无法按方法区分），{@code includeTools} 则是"有一条写了 true 就算 true"。</p>
     */
    /** 解析后的重试策略：注解本体 + 合成出来的 {@code includeTools}。 */
    private record RetryPolicy(LlmRetry annotation, boolean includeTools) {
    }

    /**
     * 读取 {@code @LlmRetry} 策略。
     *
     * <p>这个注解以前在整个仓库里只有定义、没有任何消费点 —— 按 README 打了它等于没打
     * （既不重试、也不报错、也不警告）。现在它的落点是：<b>模型调用</b>
     * （包一层 {@link RetryingLlmModel}），{@code includeTools = true} 时再加上
     * <b>该 Agent 的全部工具</b>（包 {@link RetryingToolCallback}）。</p>
     *
     * <p>接口上写最直观；写在接口方法上时，取"尝试次数最大"的那条作为整个 Agent 的策略
     * （模型调用无法按方法区分），{@code includeTools} 则是"有一条写了 true 就算 true"。</p>
     */
    private RetryPolicy resolveRetryPolicy(Class<?> agentInterface) {
        LlmRetry strongest = agentInterface.getAnnotation(LlmRetry.class);
        boolean includeTools = strongest != null && strongest.includeTools();
        for (Method method : agentInterface.getMethods()) {
            LlmRetry onMethod = method.getAnnotation(LlmRetry.class);
            if (onMethod == null) {
                continue;
            }
            includeTools |= onMethod.includeTools();
            if (strongest == null || onMethod.maxAttempts() > strongest.maxAttempts()) {
                strongest = onMethod;
            }
        }
        return strongest == null ? null : new RetryPolicy(strongest, includeTools);
    }

    /** 给注册表里的每个工具包一层重试装饰器（{@code @LlmRetry(includeTools = true)} 时使用）。 */
    private ToolRegistry withToolRetries(ToolRegistry tools, LlmRetry retry) {
        return ToolRegistry.of(tools.all().stream()
                .map(callback -> callback instanceof RetryingToolCallback ? callback
                        : (ToolCallback) new RetryingToolCallback(callback, retry.maxAttempts(),
                                retry.backoffMillis(), retry.multiplier()))
                .toList());
    }

    /** 用一个现成的 {@link AgentSpec} 构建 Agent（工具取全局工具集）。 */
    public Agent create(AgentSpec spec) {
        return create(spec, toolCatalog.global(), List.of());
    }

    /** 完整装配。 */
    public Agent create(AgentSpec spec, ToolRegistry tools) {
        return create(spec, tools, List.of());
    }

    /**
     * 完整装配（含注解上追加的拦截器）。
     *
     * @param extraInterceptors {@code @LlmAgent(interceptors = {...})} 实例化出来的拦截器
     */
    public Agent create(AgentSpec spec, ToolRegistry tools, List<AgentInterceptor> extraInterceptors) {
        return create(spec, tools, extraInterceptors, null);
    }

    private Agent create(AgentSpec spec, ToolRegistry tools, List<AgentInterceptor> extraInterceptors,
                         LlmRetry retry) {
        LlmProperties.AgentProperties defaults = properties.getAgent();
        int maxSteps = Math.max(1, spec.maxSteps());

        List<AgentInterceptor> interceptors = new ArrayList<>(interceptorChain());
        if (extraInterceptors != null) {
            interceptors.addAll(extraInterceptors);
        }
        interceptors.sort(Comparator.comparingInt(AgentInterceptor::order));

        AgentBuilder builder = Agent.builder(spec.name())
                .spec(spec.toBuilder().maxSteps(maxSteps).build())
                .modelRegistry(modelRegistry)
                .loopRegistry(loopRegistry)
                .toolRegistry(tools)
                .contextManager(contextManager)
                .memoryStore(memoryStore)
                .systemPromptProvider(systemPromptProvider)
                .sandbox(selectFor(spec.name(), sandboxProvider, ToolSandbox.class))
                .approvalHandler(selectFor(spec.name(), approvalHandlerProvider, ApprovalHandler.class))
                .approvalPolicy(ApprovalPolicy.from(defaults.getApprovalPolicy()))
                .hardMaxSteps(defaults.getHardMaxSteps())
                .interceptors(interceptors)
                .listeners(listenerProvider.orderedStream().toList());

        if (spec.model() != null) {
            builder.model(spec.model());
        }
        if (retry != null) {
            // 实例后设，按 AgentBuilder 的"后设者胜"覆盖上面的名字解析（见 model(String)/model(LlmModel) 的约定）
            builder.model(withModelRetry(spec, retry));
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
                agent.name(), agent.loop().name(), agent.model().name(), agent.tools().names(),
                agent.spec().maxSteps());
        return agent;
    }

    /**
     * 取"已被 {@code @LlmGuard} 包装过"的拦截器链。
     *
     * <p>必须依赖 {@link LlmInterceptorChain} 而不是 {@code ObjectProvider<AgentInterceptor>}：
     * 后者的解析目标是"类型为 AgentInterceptor 的 bean"，会绕开
     * {@code LlmAutoConfiguration.llmInterceptors()} 返回的那份包装清单，
     * 于是 {@code @LlmGuard(agents = {...})} 声明的范围完全失效。</p>
     */
    private List<AgentInterceptor> interceptorChain() {
        LlmInterceptorChain chain = interceptorChainProvider.getIfAvailable();
        return chain == null ? List.of() : chain.interceptors();
    }

    /**
     * 实例化 {@code @LlmAgent(interceptors = {...})} 里声明的拦截器。
     *
     * <p>以前这个注解属性彻底空转：{@code specOf()} 不读它，{@code instantiateInterceptor()}
     * 在整个插件里没有任何调用点 —— 写了等于没写，而且不报错、不警告。</p>
     */
    private List<AgentInterceptor> annotationInterceptors(LlmAgent annotation) {
        if (annotation == null || annotation.interceptors().length == 0) {
            return List.of();
        }
        List<AgentInterceptor> result = new ArrayList<>();
        for (Class<?> type : annotation.interceptors()) {
            result.add(instantiateInterceptor(type));
        }
        return result;
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

    /**
     * 按 {@code @LlmRetry} 给模型包一层重试装饰器。
     *
     * <p>{@code maxAttempts} 是"含首次"的口径，而 {@code RetryingLlmModel} 收的是"不含首次"的
     * 重试次数，这里做一次换算 —— 两个口径都各自合理，混用就会差一次。</p>
     */
    private LlmModel withModelRetry(AgentSpec spec, LlmRetry retry) {
        LlmModel base = spec.model() == null ? modelRegistry.defaultModel() : modelRegistry.get(spec.model());
        return new RetryingLlmModel(base, Math.max(0, retry.maxAttempts() - 1),
                retry.backoffMillis(), retry.multiplier());
    }

    /**
     * 为某个 Agent 选择沙箱 / 审批器。
     *
     * <p>挑选顺序：① bean 名与 Agent 名相同的那个 → ② 唯一的那个（或被 {@code @Primary} 标记的那个）。
     * 两者都定不下来时抛异常并列出候选，要求使用者用 bean 名或 {@code @Primary} 明确表态 ——
     * 沙箱与审批器是安全相关的组件，替使用者随便挑一个是危险的。</p>
     *
     * <p>以前这里是构造注入裸类型：容器里只要出现第二个同类型 bean，应用会在启动时直接
     * {@code NoUniqueBeanDefinitionException}，于是"给这个 Agent 换一套审批策略"这种正常需求
     * 只能靠 {@code @Primary} 绕开，做不到按 Agent 区分。</p>
     *
     * @return 选中的组件；容器里一个都没有时返回 {@code null}（由 {@link AgentBuilder} 用内置默认值兜底）
     */
    private <T> T selectFor(String agentName, ObjectProvider<T> provider, Class<T> type) {
        if (beanFactory instanceof ListableBeanFactory listable
                && agentName != null && !agentName.isBlank()) {
            for (String beanName : listable.getBeanNamesForType(type, true, false)) {
                if (beanName.equals(agentName)) {
                    log.info("[benxin] Agent [{}] 使用同名 bean 作为 {}", agentName, type.getSimpleName());
                    return beanFactory.getBean(beanName, type);
                }
            }
        }
        T unique = provider.getIfUnique();
        if (unique != null) {
            return unique;
        }
        List<String> candidates = new ArrayList<>();
        provider.orderedStream().forEach(bean -> candidates.add(String.valueOf(bean)));
        if (candidates.isEmpty()) {
            return null;
        }
        throw new LlmAgentInvocationException("[benxin] 容器里有 " + candidates.size() + " 个 " + type.getSimpleName()
                + " bean，无法为 Agent [" + agentName + "] 决定用哪一个。"
                + "请把其中一个的 bean 名起成 Agent 名（" + agentName + "），或给其中一个加 @Primary。当前候选: "
                + candidates);
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