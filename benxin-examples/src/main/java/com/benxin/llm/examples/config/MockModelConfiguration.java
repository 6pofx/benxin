package com.benxin.llm.examples.config;

import com.benxin.llm.core.model.LlmModel;
import com.benxin.llm.examples.mock.MockLlmModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 示例用的离线模型。
 *
 * <p>这里顺带演示了一条重要的扩展路径：<b>模型不一定要来自 yml 配置</b>。
 * 任何 {@code LlmModel} bean 都会被自动登记进模型注册表（并以 bean 上的
 * {@code @LlmModelDef} 或 {@code name()} 作为注册名），因此"自带一个模型实现"
 * 和"在 yml 里配一个模型端点"在使用体验上完全等价。</p>
 */
@Configuration
public class MockModelConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "mockLlmModel")
    @ConditionalOnProperty(prefix = "benxin.mock", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LlmModel mockLlmModel() {
        return new MockLlmModel("mock");
    }
}