package com.benxin.llm.spring;

import com.benxin.llm.core.context.ContextManager;
import com.benxin.llm.core.hook.AgentInterceptor;
import com.benxin.llm.core.hook.AgentListener;
import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.memory.MemoryStore;
import com.benxin.llm.core.model.ModelRegistry;
import com.benxin.llm.core.prompt.SystemPromptProvider;
import com.benxin.llm.core.sandbox.ApprovalHandler;
import com.benxin.llm.core.sandbox.ToolSandbox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Agent 装配层的自动配置：注册 {@link AgentRegistry} 与 {@link LlmAgentFactory}。
 *
 * <p>单独成类是为了让 {@code @LlmAgent} 的注册时机晚于上面那些基础组件 —— 注解注册器
 * 会在 bean 定义阶段就引用 {@link LlmAgentFactory}，靠 {@code after} 保证顺序可避免
 * 过早初始化导致的"依赖的 bean 还没准备好"。</p>
 */
@AutoConfiguration(after = LlmAutoConfiguration.class)
// 必须跟随主开关：@AutoConfiguration(after=...) 只表达顺序，不表达依赖，
// 少了这一行会导致 llm.enabled=false 时主配置被跳过、而本类仍试图注入 LlmProperties 并启动失败。
@ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry llmAgentRegistry() {
        return new AgentRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmAgentFactory llmAgentFactory(LlmProperties properties,
                                           ModelRegistry modelRegistry,
                                           LoopRegistry loopRegistry,
                                           LlmToolCatalog toolCatalog,
                                           ContextManager contextManager,
                                           MemoryStore memoryStore,
                                           SystemPromptProvider systemPromptProvider,
                                           ToolSandbox sandbox,
                                           ApprovalHandler approvalHandler,
                                           AgentRegistry agentRegistry,
                                           ObjectProvider<AgentInterceptor> interceptors,
                                           ObjectProvider<AgentListener> listeners) {
        return new LlmAgentFactory(properties, modelRegistry, loopRegistry, toolCatalog,
                contextManager, memoryStore, systemPromptProvider, sandbox, approvalHandler,
                agentRegistry, interceptors, listeners);
    }
}