package com.benxin.llm.spring;

import com.benxin.llm.core.loop.AgentLoop;
import com.benxin.llm.core.annotation.LlmGuard;
import com.benxin.llm.core.annotation.LlmLoop;
import com.benxin.llm.core.annotation.LlmModelDef;
import com.benxin.llm.core.context.ContextCompactor;
import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.context.DefaultContextManager;
import com.benxin.llm.core.context.SummarizingCompactor;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.hook.LoggingListener;
import com.benxin.llm.core.loop.BuiltinLoops;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.loop.WorkflowLoop;
import com.benxin.llm.core.workflow.WorkflowDefinition;
import com.benxin.llm.core.workflow.WorkflowIo;
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.protocol.Protocol;
import com.benxin.llm.core.protocol.ProtocolCodec;
import com.benxin.llm.core.protocol.ProtocolRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.prompt.TemplateSystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;
import com.benxin.llm.core.tool.builtin.BuiltinToolkit;
import com.benxin.llm.core.transport.HttpTransport;
import com.benxin.llm.core.transport.JdkHttpTransport;
import com.benxin.llm.core.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本心主自动配置。
 *
 * <p>这里每一个 {@code @Bean} 都带 {@link ConditionalOnMissingBean} —— 这不是样板，
 * 而是"一切皆可插拔"在 Spring 层面的落地方式：<b>用户只要定义同类型的 bean，
 * 就自动接管该组件</b>，无需任何开关、无需排包。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(LlmProperties.class)
@ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmAutoConfiguration.class);

    // ------------------------------------------------------------------
    // 传输层
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public HttpTransport llmHttpTransport() {
        return new JdkHttpTransport();
    }

    // ------------------------------------------------------------------
    // 工具目录（必须是 static，才能作为 BeanPostProcessor 提前就绪）
    // ------------------------------------------------------------------

    @Bean
    public static LlmToolCatalog llmToolCatalog() {
        return new LlmToolCatalog();
    }

    /**
     * 自动发现 {@code @LlmLoop} / {@code @LlmGuard} 组件。
     *
     * <p>同样必须是 static：它是 {@code BeanDefinitionRegistryPostProcessor}，
     * 要在 {@code llmLoopRegistry} 等普通 bean 实例化之前完成注册，
     * 否则"打个注解就能用"的承诺会在时序上落空。</p>
     */
    @Bean
    public static LlmComponentRegistrar llmComponentRegistrar() {
        return new LlmComponentRegistrar();
    }

    // ------------------------------------------------------------------
    // 协议注册表
    // ------------------------------------------------------------------

    /**
     * 协议注册表：内置四协议 + 容器里所有的 {@link ProtocolCodec} bean。
     *
     * <p>这是"加第四种协议"能兑现的关键一环 —— 在此之前，用户按文档注册的
     * {@code ProtocolCodec} bean <b>是个完全惰性的 bean</b>，starter 里没有任何一处消费它。
     * 现在它会被收进来，于是配置里写 {@code protocol: bedrock} 就能生效。</p>
     *
     * <p>用户 bean 在内置之后登记，因此<b>同名即覆盖</b>：想用自己的实现顶掉内置
     * {@code openai}，注册一个 {@code protocol()} 返回 {@link Protocol#OPENAI} 的 Codec 即可。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ProtocolRegistry llmProtocolRegistry(ObjectProvider<ProtocolCodec> codecBeans) {
        ProtocolRegistry registry = ProtocolRegistry.withBuiltins();
        codecBeans.orderedStream().forEach(codec -> {
            registry.register(codec);
            log.info("[benxin] 注册自定义协议 [{}] ← {}", codec.protocol().id(),
                    AopUtils.getTargetClass(codec).getName());
        });
        log.info("[benxin] 可用协议 {}（内置 {}）", registry.describe(), Protocol.builtinIds());
        return registry;
    }

    // ------------------------------------------------------------------
    // 模型
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry llmModelRegistry(LlmProperties properties, HttpTransport transport,
                                          ProtocolRegistry protocolRegistry,
                                          ObjectProvider<LlmModel> modelBeans) {
        Map<String, ModelConfig> configs = new LinkedHashMap<>();
        properties.getModels().forEach((name, model) -> configs.put(name, model.toModelConfig(name)));

        String configured = properties.getDefaultModel();
        // primary 只在 yml 里有意义；configured 还可能指向一个由 bean 提供的模型，
        // 所以先只把"确定存在于配置里"的那个交给工厂，剩下的等 bean 注册完再定。
        String fromProperties = configured == null
                ? properties.getModels().entrySet().stream()
                        .filter(e -> e.getValue().isPrimary())
                        .sorted(Comparator.comparingInt(e -> e.getValue().getOrder()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null)
                : (properties.getModels().containsKey(configured) ? configured : null);

        ModelRegistry registry = ModelFactory.registry(configs, transport, fromProperties, protocolRegistry);

        // 容器里的 LlmModel bean 优先，可覆盖同名配置项 —— 这是"配置不够用时用代码接管"的通道
        List<String> beanModels = new ArrayList<>();
        modelBeans.orderedStream().forEach(model -> {
            Class<?> targetClass = AopUtils.getTargetClass(model);
            LlmModelDef annotation = targetClass.getAnnotation(LlmModelDef.class);
            String name = annotation != null && !annotation.value().isBlank() ? annotation.value() : model.name();
            registry.register(name, model);
            beanModels.add(name);
        });

        if (configured != null) {
            // 无论默认模型来自配置还是来自 bean，都在这里统一落地
            registry.setDefault(configured);
        } else if (registry.configuredDefaultName() == null && !beanModels.isEmpty()) {
            registry.setDefault(beanModels.get(0));
        }
        if (registry.configuredDefaultName() != null
                && !registry.names().contains(registry.configuredDefaultName())) {
            log.warn("[benxin] llm.default-model=[{}] 未匹配到任何已注册模型 {}，将回退到第一个可用模型",
                    registry.configuredDefaultName(), registry.names());
        }
        log.info("[benxin] 已注册模型 {}（默认 {}）", registry.names(), registry.defaultName());
        return registry;
    }

    // ------------------------------------------------------------------
    // Loop
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public LoopRegistry llmLoopRegistry(LlmProperties properties, ObjectProvider<AgentLoop> loopBeans,
                                        ResourceLoader resourceLoader) {
        LoopRegistry registry = new LoopRegistry();
        BuiltinLoops.registerAll(registry);
        registerDeclaredWorkflows(registry, properties, resourceLoader);

        String annotatedDefault = null;
        for (AgentLoop loop : loopBeans.orderedStream().toList()) {
            Class<?> targetClass = AopUtils.getTargetClass(loop);
            LlmLoop annotation = targetClass.getAnnotation(LlmLoop.class);
            String name = annotation != null && !annotation.value().isBlank() ? annotation.value() : loop.name();
            registry.register(name, loop);
            if (annotation != null && annotation.defaultLoop() && annotatedDefault == null) {
                annotatedDefault = name;
            }
            log.info("[benxin] 注册自定义 Loop [{}] ← {}", name, targetClass.getName());
        }

        String configured = properties.getAgent().getDefaultLoop();
        if (configured != null && registry.contains(configured)) {
            registry.setDefault(configured);
        } else if (annotatedDefault != null) {
            registry.setDefault(annotatedDefault);
        } else {
            registry.setDefault("dsh-minimal");
        }
        log.info("[benxin] 可用 Loop {}（默认 {}）", registry.names(), registry.defaultName());
        return registry;
    }

    /**
     * 把 {@code llm.workflows.*} 里声明的定义各注册成一个 {@code workflow:<key>} Loop。
     *
     * <p>这样"加一个工作流"就只是加一个 YAML 文件加一行配置，不需要碰任何 Java 代码 ——
     * 这正是声明式工作流模式想要兑现的东西。定义有问题时<b>直接让启动失败</b>：
     * 一份跑起来才发现画错的图，比一个启动期的报错昂贵得多。</p>
     */
    private void registerDeclaredWorkflows(LoopRegistry registry, LlmProperties properties,
                                           ResourceLoader resourceLoader) {
        for (Map.Entry<String, LlmProperties.WorkflowProperties> entry
                : properties.getWorkflows().entrySet()) {
            String key = entry.getKey();
            String loopName = WorkflowLoop.NAME + ":" + key;
            WorkflowDefinition definition = loadWorkflow(key, entry.getValue(), resourceLoader);
            registry.register(loopName, WorkflowLoop.named(loopName, definition));
            log.info("[benxin] 注册工作流 Loop [{}] ← {}（{} 个节点）",
                    loopName, definition.name(), definition.nodes().size());
        }
    }

    private WorkflowDefinition loadWorkflow(String key, LlmProperties.WorkflowProperties config,
                                            ResourceLoader resourceLoader) {
        String where = "llm.workflows." + key;
        boolean hasInline = config.getInline() != null && !config.getInline().isBlank();
        boolean hasLocation = config.getLocation() != null && !config.getLocation().isBlank();
        if (hasInline && hasLocation) {
            throw new IllegalStateException(where + " 同时配置了 location 与 inline，请二选一");
        }
        if (!hasInline && !hasLocation) {
            throw new IllegalStateException(where + " 需要配置 location 或 inline 之一");
        }
        try {
            if (hasInline) {
                return WorkflowIo.read(config.getInline());
            }
            Resource resource = resourceLoader.getResource(config.getLocation());
            if (!resource.exists()) {
                throw new IllegalStateException(where + " 指向的资源不存在：" + config.getLocation());
            }
            try (InputStream in = resource.getInputStream()) {
                return WorkflowIo.read(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException(where + " 读取失败：" + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + " 的定义非法：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 其余可插拔组件
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public TokenEstimator llmTokenEstimator() {
        return TokenEstimator.DEFAULT;
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextCompactor llmContextCompactor(LlmProperties properties) {
        // 默认用摘要压缩：长任务里比"直接丢弃旧消息"更不容易丢关键信息
        return new SummarizingCompactor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextManager llmContextManager(ContextCompactor compactor, TokenEstimator estimator) {
        return new DefaultContextManager(compactor, estimator, 0.8, 8, 4096);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryStore llmMemoryStore() {
        return new InMemoryMemoryStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SystemPromptProvider llmSystemPromptProvider() {
        return new TemplateSystemPromptProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalHandler llmApprovalHandler() {
        // 默认自动批准：真正的守门交给沙箱与 ApprovalPolicy，
        // 无人值守环境下弹窗式审批只会把请求挂死。
        return ApprovalHandler.autoApprove();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolSandbox llmToolSandbox(LlmProperties properties) {
        // 沙箱与"启用了哪些内置工具"同源：BuiltinToolkit 会按实际装配的工具集推导授权
        // （enable("write") 等价于 allowWrite(true)），以前这里只看 builtin-enabled 与
        // allow-write，于是 enabled:[write] 而没写 allow-write 时工具"看得见却跑不动"。
        return BuiltinToolkit.sandbox(LlmBuiltinToolRegistrar.toSandboxConfig(properties.getTools()));
    }

    /**
     * 内置事件监听器。
     *
     * <p>条件按<b>类型</b>判断，与其它 13 个切面保持同一套替换机制：
     * 使用者定义任意一个 {@code AgentListener} bean，内置这个就让位。
     * 以前写的是 {@code @ConditionalOnMissingBean(name = "llmLoggingListener")} ——
     * 按 bean 名字判，于是自定义监听器会被"并存"而不是"顶掉"，
     * 与 README 承诺的统一机制不一致。</p>
     */
    @Bean
    @ConditionalOnMissingBean(AgentListener.class)
    public AgentListener llmLoggingListener(LlmProperties properties) {
        return properties.getAgent().isLoggingListener()
                ? new LoggingListener()
                : AgentListener.noop();
    }

    /**
     * 拦截器链：这里是 {@code @LlmGuard(agents = {...})} 的范围包装发生的地方。
     *
     * <p>返回的 {@code List<AgentInterceptor>} 仍然保留为 bean（外部可以按名字取到它做诊断），
     * 但消费端请依赖 {@link LlmInterceptorChain} —— {@code ObjectProvider<AgentInterceptor>}
     * 取到的是容器里<b>未包装</b>的原始 bean，会让 {@code @LlmGuard} 的范围声明彻底失效。</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "llmInterceptors")
    public List<AgentInterceptor> llmInterceptors(ObjectProvider<AgentInterceptor> interceptors) {
        return resolveInterceptors(interceptors);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmInterceptorChain llmInterceptorChain(ObjectProvider<AgentInterceptor> interceptors,
                                                   ObjectProvider<List<AgentInterceptor>> resolved) {
        List<AgentInterceptor> chain = resolved.orderedStream().findFirst().orElse(null);
        if (chain == null) {
            chain = resolveInterceptors(interceptors);
        }
        return new LlmInterceptorChain(chain);
    }

    /** 把带 {@code @LlmGuard} 的拦截器包成按 Agent 名过滤的 {@link SelectiveInterceptor} 并按 order 排序。 */
    private static List<AgentInterceptor> resolveInterceptors(ObjectProvider<AgentInterceptor> interceptors) {
        List<AgentInterceptor> resolved = new ArrayList<>();
        interceptors.orderedStream().forEach(interceptor -> {
            LlmGuard guard = AopUtils.getTargetClass(interceptor).getAnnotation(LlmGuard.class);
            resolved.add(guard == null ? interceptor : new SelectiveInterceptor(interceptor, guard));
        });
        resolved.sort(Comparator.comparingInt(AgentInterceptor::order));
        return resolved;
    }

    @Bean
    @ConditionalOnProperty(prefix = "llm.tools", name = "builtin-enabled", havingValue = "true")
    public LlmBuiltinToolRegistrar llmBuiltinToolRegistrar(LlmProperties properties, LlmToolCatalog catalog) {
        return new LlmBuiltinToolRegistrar(properties, catalog);
    }
}