package com.benxin.llm.examples.agent;

import com.benxin.llm.core.annotation.Ctx;
import com.benxin.llm.core.annotation.LlmAgent;
import com.benxin.llm.core.annotation.SystemPrompt;
import com.benxin.llm.core.annotation.User;

import java.util.List;

/**
 * 声明式 Agent 的进阶用法：
 * <ul>
 *   <li>{@code loop = "claude-code"} —— 换成带子代理、待办管理与自动压缩的循环；</li>
 *   <li>方法级 {@link SystemPrompt} —— 同一个接口的不同方法可以有完全不同的角色设定；</li>
 *   <li>{@code @Ctx} —— 上下文变量既不进消息体，又能参与提示词渲染；</li>
 *   <li>返回 DTO —— 模型输出被当作 JSON 自动反序列化，调用方拿到的就是强类型对象。</li>
 * </ul>
 */
@LlmAgent(
        model = "mock",
        loop = "claude-code",
        systemPrompt = "你是一名严谨的 Java 代码审查专家，说话直接，不客套。",
        maxSteps = 16)
public interface CodeReviewer {

    /**
     * 返回一个强类型对象。为了让离线 Mock 模型也能演示这条链路，
     * 提示词里显式要求了 JSON 输出；接真实模型时这同样是推荐做法。
     */
    @SystemPrompt("""
            请审查用户给出的 Java 代码，并以 JSON 格式输出结论，字段如下：
            {"summary": "一句话总评", "issues": ["问题1", "问题2"], "score": 0-100 的整数}
            只输出 JSON，不要任何解释性文字或 ``` 围栏。
            """)
    ReviewReport review(@User String code);

    /** 上下文变量只参与提示词渲染，不进入消息体。 */
    @SystemPrompt("""
            请以 {style} 的风格，面向 {audience} 解释这段代码做了什么。
            目标语言：{language}。
            """)
    String explain(@User String code,
                   @Ctx("style") String style,
                   @Ctx("audience") String audience,
                   @Ctx("language") String language);

    /** 审查报告（示例用的强类型返回值）。 */
    record ReviewReport(String summary, List<String> issues, int score) {
    }
}