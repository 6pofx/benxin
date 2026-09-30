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
// 三个构造依赖全部纳入守卫。以前只守了 AgentRegistry，于是"容器里另有其人提供 AgentRegistry"
// 而 llm.enabled=false 把 ModelRegistry / LoopRegistry 关掉时，守卫放行、依赖不全，
// 失败方式是【整个应用起不来】（UnsatisfiedDependency），而不是"端点不暴露"。
// 插件把"定义一个同类型 bean 就完成替换"写成正式扩展点，所以这个组合并非空想。
@ConditionalOnBean({AgentRegistry.class, ModelRegistry.class, LoopRegistry.class})
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