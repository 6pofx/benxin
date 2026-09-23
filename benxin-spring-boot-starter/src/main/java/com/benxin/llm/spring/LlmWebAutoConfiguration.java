package com.benxin.llm.spring;

import com.benxin.llm.core.loop.LoopRegistry;
import com.benxin.llm.core.model.ModelRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 可选的 Web 层：把 Agent 暴露成 REST + SSE 接口。
 *
 * <p>需要宿主应用引入 {@code spring-boot-starter-web}，且必须显式设置
 * {@code llm.web.enabled=true} 才会生效。</p>
 */
@AutoConfiguration(after = LlmAgentAutoConfiguration.class)
@ConditionalOnClass(DispatcherServlet.class)
// 依赖 AgentRegistry 而不是再叠一个 @ConditionalOnProperty —— 后者不可重复，
// 而且"依赖的 bean 在不在"本来就是比配置项更准确的判断依据。
@ConditionalOnBean(AgentRegistry.class)
@ConditionalOnProperty(prefix = "llm.web", name = "enabled", havingValue = "true")
public class LlmWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LlmAgentController llmAgentController(AgentRegistry agentRegistry,
                                                 ModelRegistry modelRegistry,
                                                 LoopRegistry loopRegistry) {
        return new LlmAgentController(agentRegistry, modelRegistry, loopRegistry);
    }
}