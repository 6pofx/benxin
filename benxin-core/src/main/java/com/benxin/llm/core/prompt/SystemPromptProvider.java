package com.benxin.llm.core.prompt;

import com.benxin.llm.core.agent.AgentSpec;

import java.util.Map;

/**
 * 系统提示词提供者。默认实现做模板插值；替换它即可接入提示词管理平台（如 Langfuse、
 * 自建配置中心），实现提示词的版本化与灰度。
 */
public interface SystemPromptProvider {

    /**
     * @param spec   Agent 描述（含名称、Loop、工具列表）
     * @param vars   上下文变量（{@code @Ctx} 参数 + 运行属性）
     * @return 最终系统提示词，null 或空表示不设置
     */
    String systemPrompt(AgentSpec spec, Map<String, Object> vars);
}