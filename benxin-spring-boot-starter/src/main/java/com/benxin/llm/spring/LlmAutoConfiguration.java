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
import com.benxin.llm.core.memory.InMemoryMemoryStore;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.core.model.ModelConfig;
import com.benxin.llm.core.model.ModelFactory;
import com.benxin.llm.core.model.ModelRegistry;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本心主自动配置。
 *
 * <p>这里每一个 {@code @Bean} 都带 {@link ConditionalOnMissingBean} —— 这不是样板，
 * 而是"一切皆可替换"在 Spring 层面的落地方式：<b>用户只要定义同类型的 bean，
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
    // 模型
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry llmModelRegistry(LlmProperties properties, HttpTransport transport,
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

        ModelRegistry registry = ModelFactory.registry(configs, transport, fromProperties);

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
        } else if (registry.defaultName() == null && !beanModels.isEmpty()) {
            registry.setDefault(beanModels.get(0));
        }
        if (registry.defaultName() != null && !registry.names().contains(registry.defaultName())) {
            log.warn("[benxin] llm.default-model=[{}] 未匹配到任何已注册模型 {}，将回退到第一个可用模型",
                    registry.defaultName(), registry.names());
        }
        log.info("[benxin] 已注册模型 {}（默认 {}）", registry.names(), registry.defaultName());
        return registry;
    }

    // ------------------------------------------------------------------
    // Loop
    // ------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public LoopRegistry llmLoopRegistry(LlmProperties properties, ObjectProvider<AgentLoop> loopBeans) {
        LoopRegistry registry = new LoopRegistry();
        BuiltinLoops.registerAll(registry);

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

    // ------------------------------------------------------------------
    // 其余可替换组件
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
        if (!properties.getTools().isBuiltinEnabled()) {
            // 内置工具未开启时，沙箱不应干扰用户自己写的工具
            return ToolSandbox.permissive();
        }
        return BuiltinToolkit.sandbox(LlmBuiltinToolRegistrar.toSandboxConfig(properties.getTools()));
    }

    @Bean
    @ConditionalOnMissingBean(name = "llmLoggingListener")
    public AgentListener llmLoggingListener(LlmProperties properties) {
        return properties.getAgent().isLoggingListener()
                ? new LoggingListener()
                : AgentListener.noop();
    }

    @Bean
    @ConditionalOnMissingBean
    public List<AgentInterceptor> llmInterceptors(ObjectProvider<AgentInterceptor> interceptors) {
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